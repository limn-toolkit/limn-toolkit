# Reads UI Automation's own identifiers off the machine this runs on, and prints them as the
# Java constant bodies the bridge compiles.
#
# ADR 039 §12.3: a recalled constant is a defect that compiles. The numbers a provider answers
# with are the client's and not ours -- a property id guessed wrong is a property the client
# silently never sees, with nothing to fail. So they are read from the interop assembly the guest
# has installed (uiautomationcore.h is the other permitted source; this guest carries no Windows
# SDK), and the output is pasted into the bridge rather than typed there.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-constants.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

# LoadWithPartialName, not Add-Type -AssemblyName: the latter resolves against the reference
# assemblies of an installed SDK, and this guest has none.
$types = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes')
$provider = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationProvider')
if (-not $types) { throw 'UIAutomationTypes is not installed on this machine' }

Write-Output "// Read from $($types.Location)"
if ($provider) { Write-Output "// and from $($provider.Location)" }
Write-Output "// on $([System.Environment]::OSVersion.VersionString), $env:PROCESSOR_ARCHITECTURE"
Write-Output ''

# One name per id. The assembly declares each property twice -- on AutomationElement for a client
# to pass around, and again on AutomationElementIdentifiers -- and a pattern's own properties once
# more on its identifiers type. The id is the identity; the name is ours.
function Constant-Name($typeName, $fieldName) {
    $bare = $fieldName -replace '(Property|Event|Pattern)$', ''
    $prefix = switch -Regex ($typeName) {
        '^AutomationElement(Identifiers)?$' { ''; break }
        '^(.+?)PatternIdentifiers$'         { $Matches[1] + '_'; break }
        '^(.+?)Pattern$'                    { $Matches[1] + '_'; break }
        '^ControlType$'                     { 'CONTROL_'; break }
        default                             { $typeName + '_' }
    }
    # A pattern's own id is declared as XPattern.Pattern, so stripping both leaves nothing but the
    # prefix: name those XPATTERN rather than XPATTERN_.
    if ($bare -eq '') { $bare = 'Pattern' }
    $name = (($prefix + $bare) -creplace '([a-z0-9])([A-Z])', '$1_$2').ToUpperInvariant()
    $name = $name -replace '_+', '_'
    # TextPattern.TextChangedEvent and the like say their own type twice.
    return ($name -replace '^([A-Z0-9]+)_\1_', '$1_')
}

function Collect($fieldTypeName) {
    $found = @()
    foreach ($t in $types.GetTypes()) {
        foreach ($f in $t.GetFields([System.Reflection.BindingFlags]'Public,Static')) {
            if ($f.FieldType.FullName -eq $fieldTypeName) {
                $found += [pscustomobject]@{
                    Name = (Constant-Name $t.Name $f.Name)
                    Id = $f.GetValue($null).Id
                }
            }
        }
    }
    # Shortest name first, so AutomationElement's bare spelling wins over the identifiers type's.
    return $found | Sort-Object -Property @{Expression = {$_.Name.Length}}, Name
}

function Emit-Group($title, $pairs) {
    Write-Output "// ---- $title"
    $seen = @{}
    foreach ($p in $pairs) {
        if (-not $seen.ContainsKey($p.Id)) { $seen[$p.Id] = $p.Name }
    }
    foreach ($id in ($seen.Keys | Sort-Object)) {
        Write-Output ("    static final int {0} = {1};" -f $seen[$id], $id)
    }
    Write-Output ''
}

Emit-Group 'property ids' (Collect 'System.Windows.Automation.AutomationProperty')
Emit-Group 'event ids' (Collect 'System.Windows.Automation.AutomationEvent')
Emit-Group 'pattern ids' (Collect 'System.Windows.Automation.AutomationPattern')

$controlType = $types.GetType('System.Windows.Automation.ControlType')
$controls = @()
foreach ($f in $controlType.GetFields([System.Reflection.BindingFlags]'Public,Static')) {
    if ($f.FieldType -eq $controlType) {
        $controls += [pscustomobject]@{
            Name = (Constant-Name 'ControlType' $f.Name)
            Id = $f.GetValue($null).Id
        }
    }
}
Emit-Group 'control type ids' $controls

# The provider-side enumerations: what a server implementation answers and is navigated by.
# Neither is in UIAutomationTypes.
if ($provider) {
    Write-Output '// ---- provider-side enumerations'
    foreach ($name in @('System.Windows.Automation.Provider.ProviderOptions',
                        'System.Windows.Automation.Provider.NavigateDirection')) {
        $t = $provider.GetType($name)
        if (-not $t) { $t = $types.GetType($name) }
        if (-not $t) { Write-Output "//   $name : not found"; continue }
        foreach ($n in [System.Enum]::GetNames($t)) {
            $constant = (($t.Name + '_' + $n) -creplace '([a-z0-9])([A-Z])', '$1_$2').ToUpperInvariant()
            Write-Output ("    static final int {0} = {1};" -f $constant, [int][System.Enum]::Parse($t, $n))
        }
    }
    Write-Output ''
}

# Beyond ids: the enumerations a provider answers with, and the values a client compares against.
# Added 2026-09-13 for the component audit -- StructureChangeType (WINDOWS-NEW-3, MODEL-NEW-8),
# ScrollAmount and NoScroll (W1), the notification kinds (WINDOWS-NEW-1), RowOrColumnMajor, and
# whatever the machine calls a tree row's level, position and set size.
#
# Each is searched for BY NAME across every type of every UI Automation assembly installed, WPF's
# own included, because the .NET Framework put some of the newer ones beside AutomationPeer rather
# than in the UIA interop assemblies. Every line says which assembly answered, and a name no
# assembly carries is printed as NOT FOUND -- an absence here is a line of output, not a silence,
# and it is what sends the reading on to dump-uia-typelib.ps1.
$client = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient')
$presentationCore = [System.Reflection.Assembly]::LoadWithPartialName('PresentationCore')
$windowsBase = [System.Reflection.Assembly]::LoadWithPartialName('WindowsBase')
$searched = @($types, $provider, $client, $presentationCore, $windowsBase) | Where-Object { $_ }

Write-Output '// ---- searched, for every group below'
foreach ($asm in $searched) { Write-Output "//   $(Describe-Assembly $asm)" }
Write-Output ''

$allTypes = New-Object 'System.Collections.Generic.List[object]'
foreach ($asm in $searched) {
    foreach ($t in (Get-LoadableTypes $asm)) {
        $allTypes.Add([pscustomobject]@{ Assembly = $asm.GetName().Name; Type = $t })
    }
}

Write-Output '// ---- enumerations beyond ids'
foreach ($enumName in @('StructureChangeType', 'ScrollAmount', 'RowOrColumnMajor',
                        'AutomationNotificationKind', 'AutomationNotificationProcessing',
                        'NotificationKind', 'NotificationProcessing')) {
    $hits = @($allTypes | Where-Object { $_.Type.IsEnum -and $_.Type.Name -eq $enumName })
    if ($hits.Count -eq 0) {
        Write-Output "//   $enumName : NOT FOUND in any assembly searched"
        Write-Output ''
        continue
    }
    foreach ($hit in $hits) {
        $t = $hit.Type
        Write-Output ("//   {0} (underlying {1}, public={2}) from {3}" -f `
            $t.FullName, [System.Enum]::GetUnderlyingType($t).Name, $t.IsPublic, $hit.Assembly)
        # Declaration order, and the raw constant rather than a cast of the enum value, so a
        # non-int underlying type would show as itself.
        foreach ($f in $t.GetFields([System.Reflection.BindingFlags]'Public,Static')) {
            $constant = (($t.Name + '_' + $f.Name) -creplace '([a-z0-9])([A-Z])', '$1_$2').ToUpperInvariant()
            Write-Output ("    static final int {0} = {1};" -f $constant, $f.GetRawConstantValue())
        }
        Write-Output ''
    }
}

# A value is not an id, so Collect never sees it: ScrollPatternIdentifiers.NoScroll is a double a
# client compares a percentage against. And a tree row's level is a property no managed
# identifiers type may carry, which is exactly the absence worth printing. Public and non-public
# alike, on every type, so a WPF-internal spelling is seen too. AppendRuntimeId and
# ItemsInvalidateLimit ride along: the runtime-id marker the bridge could only prove by behaviour,
# and the bulk threshold a StructureChanged raise is compared against, if the provider side
# declares them.
Write-Output '// ---- static fields named NoScroll, Level, PositionInSet, SizeOfSet, HeadingLevel, AppendRuntimeId or ItemsInvalidateLimit'
$fieldHits = 0
$levelHits = 0
foreach ($hit in $allTypes) {
    $flags = [System.Reflection.BindingFlags]'Public,NonPublic,Static,DeclaredOnly'
    $fields = @()
    try { $fields = $hit.Type.GetFields($flags) } catch { continue }
    foreach ($f in $fields) {
        if ($f.Name -notmatch '^(NoScroll|Level|PositionInSet|SizeOfSet|HeadingLevel|AppendRuntimeId|ItemsInvalidateLimit)(Property)?$') { continue }
        $fieldHits++
        if ($f.Name -match '^Level(Property)?$') { $levelHits++ }
        $value = $null
        try {
            if ($f.IsLiteral) { $value = $f.GetRawConstantValue() } else { $value = $f.GetValue($null) }
        } catch {
            $value = "(unreadable: $($_.Exception.GetType().Name))"
        }
        $shown = "$value"
        if ($value -is [double]) {
            $shown = $value.ToString('R', [System.Globalization.CultureInfo]::InvariantCulture)
        } elseif ($value -and $value.GetType().FullName -eq 'System.Windows.Automation.AutomationProperty') {
            $shown = "AutomationProperty Id=$($value.Id) ProgrammaticName=$($value.ProgrammaticName)"
        } elseif ($value -and $value.GetType().FullName -eq 'System.Windows.DependencyProperty') {
            $shown = "DependencyProperty Name=$($value.Name) OwnerType=$($value.OwnerType.FullName) (a WPF property, not a UIA id)"
        }
        Write-Output ("//   {0}.{1} : {2} = {3}  (from {4})" -f `
            $hit.Type.FullName, $f.Name, $f.FieldType.Name, $shown, $hit.Assembly)
        if ($value -is [double]) {
            Write-Output ("    static final double {0} = {1};" -f (Constant-Name $hit.Type.Name $f.Name), $shown)
        } elseif ($value -is [int] -and $f.FieldType -eq [int]) {
            Write-Output ("    static final int {0} = {1};" -f (($f.Name -creplace '([a-z0-9])([A-Z])', '$1_$2').ToUpperInvariant()), $value)
        }
    }
}
if ($fieldHits -eq 0) { Write-Output '//   none of these names exists as a static field in any assembly searched' }
if ($levelHits -eq 0) { Write-Output '//   Level / LevelProperty : NOT FOUND in any assembly searched' }
Write-Output ''

# The inventory the name search above could miss: every type in an automation namespace whose name
# mentions one of these, enum or not, so a differently spelt one is seen rather than assumed away.
Write-Output '// ---- every automation type whose name mentions Notification, StructureChange, ScrollAmount, RowOrColumn, Level, PositionInSet or SizeOfSet'
foreach ($hit in $allTypes) {
    $t = $hit.Type
    if ("$($t.Namespace)" -notmatch 'Automation') { continue }
    if ($t.Name -notmatch 'Notification|StructureChange|ScrollAmount|RowOrColumn|Level|PositionInSet|SizeOfSet') { continue }
    $kind = if ($t.IsEnum) { 'enum' } elseif ($t.IsInterface) { 'interface' } elseif ($t.IsValueType) { 'struct' } else { 'class' }
    Write-Output ("//   {0} {1} (public={2}) from {3}" -f $kind, $t.FullName, $t.IsPublic, $hit.Assembly)
}
Write-Output ''

# And every method in an automation namespace that raises or carries a notification, which says
# whether this Framework's managed side can raise one at all. Names and parameter types only; the
# native declarations behind them are dump-uia-entry-points.ps1's.
# UIAutomationTypes keeps its own table of every identifier it knows, as internal enumerations --
# wider than the public identifiers types, which is how Level turns up above with no LevelProperty
# beside it. Printed whole, because an identifier the public surface lacks (DescribedBy was one)
# may be in it, and a second managed source that agrees with the type library is worth the lines.
Write-Output '// ---- every enumerator of the internal identifier table, MS.Internal.Automation.AutomationIdentifierConstants'
$constantsTable = @($allTypes | Where-Object { $_.Type.FullName -like 'MS.Internal.Automation.AutomationIdentifierConstants*' })
if ($constantsTable.Count -eq 0) { Write-Output '//   NOT FOUND in any assembly searched' }
foreach ($hit in $constantsTable) {
    $t = $hit.Type
    if (-not $t.IsEnum) {
        Write-Output "//   $($t.FullName) (not an enum) from $($hit.Assembly)"
        continue
    }
    Write-Output "//   $($t.FullName) (underlying $([System.Enum]::GetUnderlyingType($t).Name)) from $($hit.Assembly)"
    foreach ($f in $t.GetFields([System.Reflection.BindingFlags]'Public,Static')) {
        Write-Output ("//     {0} = {1}" -f $f.Name, $f.GetRawConstantValue())
    }
}
Write-Output ''

Write-Output '// ---- automation methods whose name mentions Notification'
foreach ($hit in $allTypes) {
    $t = $hit.Type
    if ("$($t.Namespace)" -notmatch 'Automation') { continue }
    $methods = @()
    try { $methods = $t.GetMethods([System.Reflection.BindingFlags]'Public,NonPublic,Static,Instance,DeclaredOnly') } catch { continue }
    foreach ($m in $methods) {
        if ($m.Name -notmatch 'Notification') { continue }
        $params = ($m.GetParameters() | ForEach-Object { "$($_.ParameterType.Name) $($_.Name)" }) -join ', '
        Write-Output ("//   {0}.{1}({2}) : {3}  public={4} from {5}" -f `
            $t.FullName, $m.Name, $params, $m.ReturnType.Name, $m.IsPublic, $hit.Assembly)
    }
}
Write-Output ''

# What the interop assembly cannot answer, named so it is not mistaken for a reading. The
# runtime-id marker and the HRESULTs live in uiautomationcoreapi.h and winerror.h, which need an
# SDK this guest does not have; the spike proved the marker's behaviour rather than its spelling.
# Identifiers newer than the assemblies are in UIAutomationCore.dll's own type library, which
# dump-uia-typelib.ps1 reads; the HRESULTs the managed side does carry, dump-uia-hresults.ps1 reads.
# The runtime-id marker turned out to be in the provider assembly after all, as
# AutomationInteropProvider.AppendRuntimeId (printed above with the other named fields): that is
# the managed provider API's own spelling of it, and the header's UiaAppendRuntimeId still is not
# on this guest.
Write-Output '// ---- NOT read here (no Windows SDK on this guest): the header spellings UiaAppendRuntimeId,'
Write-Output '// UIA_E_ELEMENTNOTAVAILABLE, UIA_E_INVALIDOPERATION. AutomationInteropProvider.AppendRuntimeId'
Write-Output '// above is the managed spelling of the first; dump-uia-hresults.ps1 reads what the managed'
Write-Output '// side carries for the other two, and dump-uia-typelib.ps1 what the type library adds.'
