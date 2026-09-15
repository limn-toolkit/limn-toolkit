# Reads what the platform's own managed UI Automation providers do where the interface contract
# leaves a choice to the provider, off the machine this runs on, as instructions and not as prose.
#
# ADR 039 §12.3: a recalled constant is a defect that compiles, and so is a recalled convention. The
# Windows bridge serves IScrollProvider, raises UiaRaiseStructureChangedEvent and
# UiaRaiseNotificationEvent, and answers Level, PositionInSet and SizeOfSet. Each has a question the
# enumerators alone do not answer -- what a percent getter says for an axis that cannot scroll, what
# a view size is then, what SetScrollPercent and Scroll do with NoScroll / NoAmount and with an axis
# that cannot move, which runtime id a structure change carries and on which element it is raised,
# what a notification passes, and from which base a level counts. So every method below is printed
# as its IL, decoded by il-listing.ps1 with every token resolved, from every managed assembly that
# carries one: the answer is read here and nowhere else.
#
# Run it on a Windows machine, beside reading-environment.ps1 and il-listing.ps1:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-provider-conventions.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
. (Join-Path $PSScriptRoot 'il-listing.ps1')
Write-ReadingEnvironment

# LoadWithPartialName, not Add-Type -AssemblyName: the latter resolves against the reference
# assemblies of an installed SDK, which a machine need not have.
$names = @('UIAutomationProvider', 'UIAutomationTypes', 'UIAutomationClient',
           'UIAutomationClientSideProviders', 'PresentationCore', 'PresentationFramework',
           'WindowsBase', 'System.Windows.Forms')
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

$flags = [System.Reflection.BindingFlags]'Public,NonPublic,Static,Instance,DeclaredOnly'
$types = @{}
foreach ($a in $assemblies) { $types[$a.GetName().Name] = @(Get-LoadableTypes $a) }

function Write-Method($m, $why) {
    Write-Output ("//   {0}::{1}({2}) -> {3}   [{4}; from {5}]" -f $m.DeclaringType.FullName, $m.Name,
        ((@($m.GetParameters()) | ForEach-Object { "$($_.ParameterType.Name) $($_.Name)" }) -join ', '),
        $(if ($m -is [System.Reflection.MethodInfo]) { $m.ReturnType.Name } else { 'ctor' }),
        $why, $m.Module.Assembly.GetName().Name)
    Write-ILListing $m
    Write-Output ''
}

# Every method of every loaded assembly whose IL references a member whose resolved name matches
# $pattern (a call, callvirt, newobj, ldftn, ldsfld or stsfld target), found by decoding every body
# with the same opcode table il-listing.ps1 uses. The scan is compiled C#, because a framework
# assembly holds hundreds of thousands of method bodies and a PowerShell loop over their bytes does
# not finish; the listing of what it finds stays in il-listing.ps1.
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Reflection;
using System.Reflection.Emit;
using System.Text.RegularExpressions;

public static class LimnIlScan {
    static readonly Dictionary<int, OpCode> One = new Dictionary<int, OpCode>();
    static readonly Dictionary<int, OpCode> Two = new Dictionary<int, OpCode>();

    static LimnIlScan() {
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static)) {
            OpCode op = (OpCode) f.GetValue(null);
            int value = op.Value & 0xFFFF;
            if (op.Size == 1) One[value] = op; else Two[value & 0xFF] = op;
        }
    }

    public static MethodBase[] FindReferencing(Assembly[] assemblies, Type[][] types, string pattern, int limit) {
        Regex regex = new Regex(pattern);
        List<MethodBase> hits = new List<MethodBase>();
        BindingFlags flags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static
            | BindingFlags.Instance | BindingFlags.DeclaredOnly;
        for (int a = 0; a < types.Length; a++) {
            Dictionary<int, bool> verdicts = new Dictionary<int, bool>();
            foreach (Type t in types[a]) {
                List<MethodBase> methods = new List<MethodBase>();
                try { methods.AddRange(t.GetMethods(flags)); methods.AddRange(t.GetConstructors(flags)); } catch { continue; }
                foreach (MethodBase m in methods) {
                    byte[] il = null;
                    try { MethodBody body = m.GetMethodBody(); if (body != null) il = body.GetILAsByteArray(); } catch { }
                    if (il == null) continue;
                    bool generic = false;
                    try { generic = m.DeclaringType.IsGenericType || m.IsGenericMethod; } catch { }
                    if (References(m, il, regex, generic ? null : verdicts)) {
                        hits.Add(m);
                        if (hits.Count >= limit) return hits.ToArray();
                    }
                }
            }
        }
        return hits.ToArray();
    }

    static bool References(MethodBase m, byte[] il, Regex regex, Dictionary<int, bool> verdicts) {
        int i = 0;
        while (i < il.Length) {
            int code = il[i++];
            OpCode op;
            if (code == 0xFE) { if (i >= il.Length || !Two.TryGetValue(il[i++], out op)) return false; }
            else if (!One.TryGetValue(code, out op)) return false;
            switch (op.OperandType) {
                case OperandType.InlineNone: break;
                case OperandType.ShortInlineBrTarget: case OperandType.ShortInlineI: case OperandType.ShortInlineVar: i += 1; break;
                case OperandType.InlineVar: i += 2; break;
                case OperandType.InlineI8: case OperandType.InlineR: i += 8; break;
                case OperandType.InlineSwitch: i += 4 + 4 * BitConverter.ToInt32(il, i); break;
                case OperandType.InlineMethod: case OperandType.InlineField: {
                    int token = BitConverter.ToInt32(il, i);
                    bool verdict;
                    if (verdicts == null || !verdicts.TryGetValue(token, out verdict)) {
                        verdict = regex.IsMatch(NameOf(m, token));
                        if (verdicts != null) verdicts[token] = verdict;
                    }
                    if (verdict) return true;
                    i += 4;
                    break;
                }
                default: i += 4; break;
            }
        }
        return false;
    }

    static string NameOf(MethodBase m, int token) {
        try {
            Type[] typeArgs = m.DeclaringType.IsGenericType ? m.DeclaringType.GetGenericArguments() : null;
            Type[] methodArgs = m.IsGenericMethod ? m.GetGenericArguments() : null;
            MemberInfo member = m.Module.ResolveMember(token, typeArgs, methodArgs);
            return member.DeclaringType.FullName + "::" + member.Name;
        } catch {
            return "";
        }
    }
}
"@

function Find-Referencing([string]$pattern, [int]$limit) {
    $typeArrays = New-Object 'System.Type[][]' $assemblies.Count
    for ($k = 0; $k -lt $assemblies.Count; $k++) { $typeArrays[$k] = [System.Type[]]$types[$assemblies[$k].GetName().Name] }
    return [LimnIlScan]::FindReferencing([System.Reflection.Assembly[]]$assemblies, $typeArrays, $pattern, $limit)
}

# ---- 1. IScrollProvider, as each managed implementation answers it
Write-Output '// ---- 1. every type implementing System.Windows.Automation.Provider.IScrollProvider, and its members as IL'
$scrollInterface = [System.Windows.Automation.Provider.IScrollProvider]
foreach ($a in $assemblies) {
    foreach ($t in $types[$a.GetName().Name]) {
        if ($t.IsInterface -or -not $scrollInterface.IsAssignableFrom($t)) { continue }
        Write-Output "//   == $($t.FullName) (from $($a.GetName().Name))"
        $map = $null
        try { $map = $t.GetInterfaceMap($scrollInterface) } catch { Write-Output "//     (no interface map: $($_.Exception.Message))"; continue }
        for ($k = 0; $k -lt $map.InterfaceMethods.Length; $k++) {
            $target = $map.TargetMethods[$k]
            if ($target.DeclaringType -ne $t) {
                Write-Output "//   $($map.InterfaceMethods[$k].Name): inherited from $($target.DeclaringType.FullName)"
                continue
            }
            Write-Method $target "implements IScrollProvider.$($map.InterfaceMethods[$k].Name)"
        }
    }
}
Write-Output '// ---- the static fields of System.Windows.Automation.ScrollPatternIdentifiers'
foreach ($f in [System.Windows.Automation.ScrollPatternIdentifiers].GetFields([System.Reflection.BindingFlags]'Public,Static')) {
    $v = $f.GetValue($null)
    if ($v -is [double]) { $v = $v.ToString('R', [System.Globalization.CultureInfo]::InvariantCulture) }
    Write-Output "//   ScrollPatternIdentifiers.$($f.Name) : $($f.FieldType.Name) = $v"
}
Write-Output ''

# ---- 2. the exceptions those members throw, as the HRESULT a native caller receives
Write-Output '// ---- 2. HResult of each exception a provider member may throw, constructed with no arguments'
foreach ($typeName in @('System.ArgumentOutOfRangeException', 'System.ArgumentException',
                        'System.InvalidOperationException', 'System.NotSupportedException',
                        'System.Windows.Automation.ElementNotEnabledException')) {
    $type = $null
    foreach ($a in @([object].Assembly) + $assemblies) {
        $type = $a.GetType($typeName, $false)
        if ($type) { break }
    }
    if (-not $type) { Write-Output "//   $typeName : not found"; continue }
    $e = [System.Activator]::CreateInstance($type)
    Write-Output ("//   {0} = 0x{1:X8} ({1})  from {2}" -f $typeName, $e.HResult, $type.Assembly.GetName().Name)
}
Write-Output ''

# ---- 3. structure changes: who raises them, with which type, which runtime id, on which element
Write-Output '// ---- 3. every method that raises a structure change or builds its arguments, as IL'
foreach ($m in (Find-Referencing 'StructureChangedEventArgs::\.ctor|::RaiseStructureChangedEvent|::UiaRaiseStructureChangedEvent' 40)) {
    Write-Method $m 'references a structure-change raise'
}
Write-Output '// ---- every method that calls AutomationPeer.UpdateChildrenInternal, as IL: which limit each passes'
foreach ($m in (Find-Referencing '::UpdateChildrenInternal$' 20)) {
    Write-Method $m 'calls UpdateChildrenInternal'
}
Write-Output '// ---- System.Windows.Automation.StructureChangedEventArgs, its constructors and properties as IL'
$eventArgs = [System.Windows.Automation.StructureChangedEventArgs]
foreach ($c in $eventArgs.GetConstructors($flags)) { Write-Method $c 'constructor' }
foreach ($p in $eventArgs.GetProperties($flags)) { if ($p.GetGetMethod($true)) { Write-Method $p.GetGetMethod($true) "get_$($p.Name)" } }

# ---- 4. notifications: what the managed path passes to UiaRaiseNotificationEvent
Write-Output '// ---- 4. every method that raises a notification, as IL'
foreach ($m in (Find-Referencing '::RaiseNotificationEvent|::UiaRaiseNotificationEvent' 20)) {
    Write-Method $m 'references a notification raise'
}
Write-Output '// ---- the declaration of each UiaRaiseNotificationEvent P/Invoke, with its marshalling'
foreach ($a in $assemblies) {
    foreach ($t in $types[$a.GetName().Name]) {
        $methods = @()
        try { $methods = @($t.GetMethods($flags)) } catch { continue }
        foreach ($m in $methods) {
            if ($m.Name -ne 'UiaRaiseNotificationEvent') { continue }
            $dll = $m.GetCustomAttributes([System.Runtime.InteropServices.DllImportAttribute], $false)
            Write-Output ("//   {0}::{1} pinvoke={2} charset={3}" -f $t.FullName, $m.Name,
                ([bool]($m.Attributes -band [System.Reflection.MethodAttributes]::PinvokeImpl)),
                $(if ($dll.Length -gt 0) { $dll[0].CharSet } else { '(attribute not surfaced)' }))
            foreach ($p in $m.GetParameters()) {
                $marshal = $p.GetCustomAttributes([System.Runtime.InteropServices.MarshalAsAttribute], $false)
                Write-Output ("//     {0} {1}  MarshalAs={2}" -f $p.ParameterType.FullName, $p.Name,
                    $(if ($marshal.Length -gt 0) { $marshal[0].Value } else { '(none)' }))
            }
        }
    }
}
Write-Output ''

# ---- 5. level, position in set, size of set: where a platform provider computes them
Write-Output '// ---- 5. every GetLevelCore / GetPositionInSetCore / GetSizeOfSetCore body, as IL'
foreach ($a in $assemblies) {
    foreach ($t in $types[$a.GetName().Name]) {
        $methods = @()
        try { $methods = @($t.GetMethods($flags)) } catch { continue }
        foreach ($m in $methods) {
            if ($m.Name -notmatch '^(GetLevelCore|GetPositionInSetCore|GetSizeOfSetCore|GetLevel|GetPositionInSet|GetSizeOfSet)$') { continue }
            Write-Method $m 'level or position'
        }
    }
}
Write-Output '// ---- static fields named *PositionInSet*, *SizeOfSet*, *Level* on AutomationProperties, with values'
foreach ($a in $assemblies) {
    $ap = $a.GetType('System.Windows.Automation.AutomationProperties', $false)
    if (-not $ap) { continue }
    foreach ($f in $ap.GetFields([System.Reflection.BindingFlags]'Public,NonPublic,Static')) {
        if ($f.Name -notmatch 'PositionInSet|SizeOfSet|Level') { continue }
        $v = $null
        try { if ($f.IsLiteral) { $v = $f.GetRawConstantValue() } else { $v = $f.GetValue($null) } } catch { $v = '(unreadable)' }
        Write-Output "//   $($ap.FullName).$($f.Name) : $($f.FieldType.Name) = $v  (from $($a.GetName().Name))"
    }
}
