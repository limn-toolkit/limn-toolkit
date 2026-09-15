# Reads the thresholds past which a UI Automation provider stops raising one event per item and
# raises one "invalidated" event for the container instead, off the machine this runs on.
#
# ADR 039 §12.3: a recalled constant is a defect that compiles. The Windows bridge raises
# ElementAddedToSelection / ElementRemovedFromSelection per member of a multi-select container and
# Selection_Invalidated for a bulk change, and "bulk" is a number the platform's own provider API
# names. So every static field whose name ends in InvalidateLimit is printed from every managed
# UI Automation assembly the machine carries (public and non-public, so an internal spelling is
# seen too), with the XML documentation the machine carries for it, if any: the value is read
# here and nowhere else.
#
# Run it on a Windows machine, beside reading-environment.ps1:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-invalidate-limits.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

# LoadWithPartialName, not Add-Type -AssemblyName: the latter resolves against the reference
# assemblies of an installed SDK, which a machine need not have.
$names = @('UIAutomationProvider', 'UIAutomationTypes', 'UIAutomationClient', 'PresentationCore',
           'PresentationFramework', 'WindowsBase')
$assemblies = @()
foreach ($name in $names) {
    $a = $null
    try { $a = [System.Reflection.Assembly]::LoadWithPartialName($name) } catch { }
    if ($a) {
        Write-Output "// Read from $(Describe-Assembly $a)"
        $assemblies += $a
    } else {
        Write-Output "// $name : not loadable on this machine"
    }
}
Write-Output ''

Write-Output '// ---- every static field whose name ends in InvalidateLimit'
$hits = @()
foreach ($a in $assemblies) {
    foreach ($t in (Get-LoadableTypes $a)) {
        $fields = @()
        try {
            $fields = $t.GetFields([System.Reflection.BindingFlags]'Public,NonPublic,Static,DeclaredOnly')
        } catch { continue }
        foreach ($f in $fields) {
            if ($f.Name -notmatch 'InvalidateLimit$') { continue }
            $value = $null
            try {
                if ($f.IsLiteral) { $value = $f.GetRawConstantValue() } else { $value = $f.GetValue($null) }
            } catch {
                $value = "(unreadable: $($_.Exception.GetType().Name))"
            }
            $visibility = if ($f.IsPublic) { 'public' } elseif ($f.IsAssembly) { 'internal' } else { 'non-public' }
            $kind = if ($f.IsLiteral) { 'const' } else { 'static' }
            Write-Output ("//   {0}.{1} : {2} = {3}  ({4} {5}, from {6})" -f `
                $t.FullName, $f.Name, $f.FieldType.Name, $value, $visibility, $kind, $a.GetName().Name)
            if ($value -is [int]) {
                Write-Output ("    static final int {0} = {1};" -f `
                    (($f.Name -creplace '([a-z0-9])([A-Z])', '$1_$2').ToUpperInvariant()), $value)
            }
            $hits += [pscustomobject]@{ Member = "F:$($t.FullName).$($f.Name)"; Assembly = $a.GetName().Name }
        }
    }
}
if ($hits.Count -eq 0) { Write-Output '//   none in any assembly searched' }
Write-Output ''

# What the platform's own documentation says each one is for, when the machine carries it: a
# targeting pack or an IntelliSense file installs an XML beside the reference assembly. Searched,
# never assumed present; an absent file is printed as absent.
Write-Output '// ---- the XML documentation for each field found, where this machine carries it'
$roots = @(
    (Join-Path ${env:ProgramFiles(x86)} 'Reference Assemblies\Microsoft\Framework\.NETFramework'),
    (Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319'),
    (Join-Path $env:SystemRoot 'Microsoft.NET\Framework\v4.0.30319'),
    (Join-Path $env:SystemRoot 'Microsoft.NET\FrameworkArm64\v4.0.30319')
)
foreach ($hit in $hits) {
    $shown = $false
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $files = @(Get-ChildItem -Path $root -Recurse -Filter "$($hit.Assembly).xml" -ErrorAction SilentlyContinue)
        foreach ($file in $files) {
            $xml = $null
            try { [xml]$xml = Get-Content -Path $file.FullName -Raw -Encoding UTF8 } catch { continue }
            $member = $xml.doc.members.member | Where-Object { $_.name -eq $hit.Member }
            if ($member) {
                $summary = ($member.summary.InnerText -replace '\s+', ' ').Trim()
                Write-Output "//   $($hit.Member) in $($file.FullName): $summary"
                $shown = $true
            }
        }
    }
    if (-not $shown) { Write-Output "//   $($hit.Member): no XML documentation for it on this machine" }
}
Write-Output ''

# What the platform's own providers do with the number, which a const cannot say about itself: a
# const is inlined where it is used, so no field reference survives into the IL. Every method of
# every managed assembly above that loads a static *InvalidatedEvent field is printed, with the
# 32-bit integer literals its body loads (ldc.i4.s and ldc.i4), so the comparison a provider makes
# before it raises the bulk event instead of per-item ones is read, not supposed.
Write-Output '// ---- methods that load a static field named *InvalidatedEvent, with the integer literals they load'
$opLdsfld = 0x7E
$opLdcI4S = 0x1F
$opLdcI4 = 0x20
$methodFlags = [System.Reflection.BindingFlags]'Public,NonPublic,Static,Instance,DeclaredOnly'
$found = 0
foreach ($a in $assemblies) {
    foreach ($t in (Get-LoadableTypes $a)) {
        $methods = @()
        try { $methods = @($t.GetMethods($methodFlags)) + @($t.GetConstructors($methodFlags)) } catch { continue }
        foreach ($m in $methods) {
            $il = $null
            try { $body = $m.GetMethodBody(); if ($body) { $il = $body.GetILAsByteArray() } } catch { continue }
            if (-not $il) { continue }
            $events = @()
            $literals = @()
            # A byte scan, not a decoder: an operand byte can look like an opcode, so a hit is
            # resolved through the module and kept only if it really names such a field.
            for ($i = 0; $i -lt $il.Length; $i++) {
                if ($il[$i] -eq $opLdsfld -and $i + 4 -lt $il.Length) {
                    $token = [System.BitConverter]::ToInt32($il, $i + 1)
                    try {
                        $field = $m.Module.ResolveField($token)
                        if ($field.Name -like '*InvalidatedEvent') {
                            $events += "$($field.DeclaringType.Name).$($field.Name)"
                        }
                    } catch { }
                } elseif ($il[$i] -eq $opLdcI4S -and $i + 1 -lt $il.Length) {
                    # The operand is a signed byte; PowerShell will not cast 254 to [sbyte].
                    $b = [int]$il[$i + 1]
                    $literals += $(if ($b -ge 128) { $b - 256 } else { $b })
                } elseif ($il[$i] -eq $opLdcI4 -and $i + 4 -lt $il.Length) {
                    $literals += [System.BitConverter]::ToInt32($il, $i + 1)
                }
            }
            if ($events.Count -gt 0) {
                $found++
                Write-Output ("//   {0}::{1} (from {2}) loads {3}; integer literals loaded: [{4}]" -f `
                    $t.FullName, $m.Name, $a.GetName().Name, (($events | Select-Object -Unique) -join ', '),
                    (($literals | Select-Object -Unique) -join ', '))
            }
        }
    }
}
if ($found -eq 0) { Write-Output '//   none' }
Write-Output ''

# WPF's own peers raise through the System.Windows.Automation.Peers.AutomationEvents enumeration
# rather than a static field, and an enumerator is inlined like a const. So the enumeration is
# printed, and then every method in a Peers namespace whose name says it raises selection events,
# with the integer literals it loads: an InvalidateLimit comparison shows as its number beside the
# enumerator of the bulk event.
Write-Output '// ---- System.Windows.Automation.Peers.AutomationEvents, the enumerators whose name mentions Selection'
foreach ($a in $assemblies) {
    $enum = $a.GetType('System.Windows.Automation.Peers.AutomationEvents', $false)
    if (-not $enum) { continue }
    foreach ($f in $enum.GetFields([System.Reflection.BindingFlags]'Public,Static')) {
        if ($f.Name -notmatch 'Selection') { continue }
        Write-Output ("//   {0}.{1} = {2}  (from {3})" -f $enum.FullName, $f.Name, $f.GetRawConstantValue(), $a.GetName().Name)
    }
}
Write-Output ''
Write-Output '// ---- methods in a *.Automation.Peers namespace whose name matches Raise.*Selection|Selection.*Event, with the integer literals they load'
$found = 0
foreach ($a in $assemblies) {
    foreach ($t in (Get-LoadableTypes $a)) {
        if ("$($t.Namespace)" -notmatch 'Automation\.Peers') { continue }
        $methods = @()
        try { $methods = @($t.GetMethods($methodFlags)) } catch { continue }
        foreach ($m in $methods) {
            if ($m.Name -notmatch 'Raise.*Selection|Selection.*Event') { continue }
            $il = $null
            try { $body = $m.GetMethodBody(); if ($body) { $il = $body.GetILAsByteArray() } } catch { continue }
            if (-not $il) { continue }
            $literals = @()
            for ($i = 0; $i -lt $il.Length; $i++) {
                if ($il[$i] -eq $opLdcI4S -and $i + 1 -lt $il.Length) {
                    $b = [int]$il[$i + 1]
                    $literals += $(if ($b -ge 128) { $b - 256 } else { $b })
                } elseif ($il[$i] -eq $opLdcI4 -and $i + 4 -lt $il.Length) {
                    $literals += [System.BitConverter]::ToInt32($il, $i + 1)
                }
            }
            $found++
            Write-Output ("//   {0}::{1} (from {2}) IL {3} bytes; ldc.i4.s / ldc.i4 literals in order: [{4}]" -f `
                $t.FullName, $m.Name, $a.GetName().Name, $il.Length, ($literals -join ', '))
            # The bytes themselves, so the branch that follows a literal -- which says whether the
            # limit itself is still per-item or already bulk -- can be decoded by whoever reads
            # this, against ECMA-335 Partition III, rather than inferred from the literal alone.
            for ($row = 0; $row -lt $il.Length; $row += 32) {
                $end = [Math]::Min($row + 32, $il.Length) - 1
                Write-Output ("//     {0:X4}: {1}" -f $row, (($il[$row..$end] | ForEach-Object { $_.ToString('X2') }) -join ' '))
            }
        }
    }
}
if ($found -eq 0) { Write-Output '//   none' }
