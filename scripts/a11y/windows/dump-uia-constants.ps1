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

# What the interop assembly cannot answer, named so it is not mistaken for a reading. The
# runtime-id marker and the HRESULTs live in uiautomationcoreapi.h and winerror.h, which need an
# SDK this guest does not have; the spike proved the marker's behaviour rather than its spelling.
Write-Output '// ---- NOT read here (no Windows SDK on this guest): UiaAppendRuntimeId,'
Write-Output '// UIA_E_ELEMENTNOTAVAILABLE, UIA_E_INVALIDOPERATION. See the bridge for how each is'
Write-Output '// sourced and what proves it.'
