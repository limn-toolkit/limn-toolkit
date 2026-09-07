# Reads the IID and the vtable slot of every member of the provider interfaces this bridge
# implements, off the machine this runs on.
#
# ADR 039 §12.3's rule applied to the shape of a call rather than to a number. A hand-written
# vtable is an array of function pointers, and getting the ORDER wrong does not fail to compile,
# does not throw, and does not log: the client calls what it thinks is get_ProviderOptions and
# reaches Navigate, with the arguments of the one it meant. Reading the slots is the only way to
# know, and .NET answers exactly this question -- GetComSlotForMethodInfo is the runtime's own
# map from a member to its position, IUnknown's three slots included.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-interfaces.ps1

$ErrorActionPreference = 'Stop'

$types = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes')
$provider = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationProvider')
if (-not $types -or -not $provider) { throw 'the UI Automation interop assemblies are not installed' }

Write-Output "// Read from $($types.Location)"
Write-Output "// and from $($provider.Location)"
Write-Output "// on $([System.Environment]::OSVersion.VersionString), $env:PROCESSOR_ARCHITECTURE"
Write-Output ''

# The interfaces the bridge serves. Anything not here is a pattern the toolkit has no facet for.
$wanted = @(
    'IRawElementProviderSimple',
    'IRawElementProviderFragment',
    'IRawElementProviderFragmentRoot',
    'IInvokeProvider',
    'IToggleProvider',
    'IRangeValueProvider',
    'IValueProvider',
    'ISelectionProvider',
    'ISelectionItemProvider',
    'IExpandCollapseProvider',
    'IScrollProvider',
    'IScrollItemProvider',
    'IWindowProvider',
    'ITransformProvider',
    'IRawElementProviderAdviseEvents'
)

foreach ($name in $wanted) {
    $iface = $null
    foreach ($asm in @($types, $provider)) {
        foreach ($ty in $asm.GetTypes()) {
            if ($ty.IsInterface -and $ty.Name -eq $name) { $iface = $ty }
        }
    }
    if (-not $iface) { Write-Output "// $name : NOT FOUND"; continue }

    Write-Output ("// ---- {0}  {{{1}}}" -f $iface.Name, $iface.GUID)
    $rows = @()
    foreach ($m in $iface.GetMethods()) {
        $slot = -1
        try { $slot = [System.Runtime.InteropServices.Marshal]::GetComSlotForMethodInfo($m) } catch { }
        $rows += [pscustomobject]@{ Slot = $slot; Name = $m.Name }
    }
    foreach ($row in ($rows | Sort-Object Slot)) {
        Write-Output ("//   slot {0,2}  {1}" -f $row.Slot, $row.Name)
    }
    Write-Output ''
}
