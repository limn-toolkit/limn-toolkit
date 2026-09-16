# Reads what the platform's own managed providers answer for IRawElementProviderFragment::SetFocus
# and IScrollItemProvider::ScrollIntoView on an element that cannot take them, off the machine this
# runs on, as instructions and not as prose.
#
# dump-uia-provider-conventions.ps1 read the order of refusals (ElementNotEnabledException first,
# InvalidOperationException after) for Invoke, Toggle, Expand/Collapse, the SelectionItem verbs, both
# SetValues, Scroll and SetScrollPercent. It read no SetFocus body, and its ScrollItem listing named a
# type that does not implement the interface. The Windows bridge answers both entry points with the
# same order, so both are read here: every managed type that implements either interface, every
# SetFocus / SetFocusCore / ScrollIntoView of the automation peers, proxies and accessible objects
# that stand behind them, and the peer's own SetFocus, each printed as IL by il-listing.ps1.
#
# Run it on a Windows machine, beside reading-environment.ps1 and il-listing.ps1:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-focus-and-scroll-item.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
. (Join-Path $PSScriptRoot 'il-listing.ps1')
Write-ReadingEnvironment

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

# Every non-interface type of every loaded assembly assignable to $interface, with the members the
# interface maps to that the type itself declares, as IL. Getters are skipped: the question is what
# a verb refuses with.
function Write-Implementations($interface) {
    Write-Output "// ---- every type implementing $($interface.FullName), and its verb members as IL"
    foreach ($a in $assemblies) {
        foreach ($t in $types[$a.GetName().Name]) {
            $assignable = $false
            try { $assignable = (-not $t.IsInterface) -and $interface.IsAssignableFrom($t) } catch { }
            if (-not $assignable) { continue }
            Write-Output "//   == $($t.FullName) (from $($a.GetName().Name))"
            $map = $null
            try { $map = $t.GetInterfaceMap($interface) } catch { Write-Output "//     (no interface map: $($_.Exception.Message))"; continue }
            for ($k = 0; $k -lt $map.InterfaceMethods.Length; $k++) {
                $name = $map.InterfaceMethods[$k].Name
                if ($name -like 'get_*') { continue }
                $target = $map.TargetMethods[$k]
                if ($target.DeclaringType -ne $t) {
                    Write-Output "//   $($name): inherited from $($target.DeclaringType.FullName)"
                    continue
                }
                Write-Method $target "implements $($interface.Name).$name"
            }
        }
    }
    Write-Output ''
}

# ---- 1. IRawElementProviderFragment::SetFocus, as each managed implementation answers it
Write-Implementations ([System.Windows.Automation.Provider.IRawElementProviderFragment])

# ---- 2. IScrollItemProvider::ScrollIntoView, as each managed implementation answers it
Write-Implementations ([System.Windows.Automation.Provider.IScrollItemProvider])

# ---- 3. what those delegate to: every SetFocus, SetFocusCore and ScrollIntoView declared by an
# automation peer, the WPF provider proxy and its dispatcher helper, a client-side proxy, or a
# WinForms accessible object (explicit implementations of its own interop interfaces included)
Write-Output '// ---- 3. every SetFocus / SetFocusCore / ScrollIntoView of the peers, proxies and accessible objects, as IL'
foreach ($a in $assemblies) {
    foreach ($t in $types[$a.GetName().Name]) {
        if ($t.FullName -notmatch 'AutomationPeer|ElementProxy|ElementUtil|AutomationProxies|AccessibleObject') { continue }
        $methods = @()
        try { $methods = @($t.GetMethods($flags)) } catch { continue }
        foreach ($m in $methods) {
            if ($m.Name -notmatch '(^|\.)(SetFocus|SetFocusCore|ScrollIntoView)$') { continue }
            Write-Method $m 'a focus or reveal member'
        }
    }
}
Write-Output ''

# ---- 4. the HRESULT of each exception those members may throw, constructed with no arguments
Write-Output '// ---- 4. HResult of each exception a focus or reveal member may throw'
foreach ($typeName in @('System.InvalidOperationException', 'System.NotSupportedException',
                        'System.Windows.Automation.ElementNotEnabledException',
                        'System.Windows.Automation.ElementNotAvailableException')) {
    $type = $null
    foreach ($a in @([object].Assembly) + $assemblies) {
        $type = $a.GetType($typeName, $false)
        if ($type) { break }
    }
    if (-not $type) { Write-Output "//   $typeName : not found"; continue }
    $e = [System.Activator]::CreateInstance($type)
    Write-Output ("//   {0} = 0x{1:X8} ({1})  from {2}" -f $typeName, $e.HResult, $type.Assembly.GetName().Name)
}
