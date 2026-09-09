# Walks the demo's form scene (`--scene form`) through a real UI Automation client and asks a
# field what a reader asks about its relations: LabeledBy, DescribedBy and ControllerFor, each
# resolved to the element at the other end and read back, beside the HelpText the walk copied.
# The Windows sibling of scripts/a11y/linux/relations-check.py and
# scripts/a11y/macos/axrelations.swift.
#
# The managed client has no identifier for DescribedBy or ControllerFor, and its public
# GetCurrentPropertyValue refuses a property its schema does not know before the provider is ever
# asked ("unsupported property"), so those two are read the way the managed layer itself reads
# every property: through its internal UiaCoreApi.UiaGetPropertyValue on the element's node
# handle, which is the platform's own client API, with the node handles that come back wrapped
# into elements by the same internal Wrap the managed identifiers use. No vtable is spelled out
# here; the managed assembly carries them.
#
# Run it the way walk-the-table.ps1 is run: from a task with /IT, after the demo is up.
#
# usage: walk-the-relations.ps1 [window-name] [field-name]
param([string]$WindowName = 'Limn UI: Kitchen Sink', [string]$FieldName = 'Email')

$ErrorActionPreference = 'Stop'
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationProvider') | Out-Null

$deadline = (Get-Date).AddSeconds(60)
$window = $null
while ((Get-Date) -lt $deadline -and -not $window) {
    $condition = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, $WindowName)
    $window = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
        [System.Windows.Automation.TreeScope]::Children, $condition)
    if (-not $window) { Start-Sleep -Milliseconds 500 }
}
if (-not $window) { Write-Output 'NOT FOUND'; exit 1 }

function Kind($e) { $e.Current.ControlType.ProgrammaticName -replace 'ControlType.','' }
function Describe($e) {
    if ($null -eq $e) { return 'null' }
    if ($e -is [System.Windows.Automation.AutomationElement]) { return ((Kind $e) + " '" + $e.Current.Name + "'") }
    if ($e -is [System.Array]) { return '[' + (($e | ForEach-Object { Describe $_ }) -join ', ') + ']' }
    return ($e.GetType().FullName + ' ' + $e)
}

$byName = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty, $FieldName)
$byType = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Edit)
$both = New-Object System.Windows.Automation.AndCondition($byName, $byType)
$field = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $both)
if (-not $field) { Write-Output ("NO EDIT NAMED '" + $FieldName + "'"); exit 2 }
Write-Output ("{0} '{1}' HelpText='{2}'" -f (Kind $field), $field.Current.Name, $field.Current.HelpText)

$labeledBy = $field.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::LabeledByProperty)
Write-Output ("LabeledBy -> " + (Describe $labeledBy))

# DescribedBy and ControllerFor: the platform's ids, through the managed layer's own core call.
$flags = [System.Reflection.BindingFlags]'NonPublic,Public,Static,Instance'
$elementType = [System.Windows.Automation.AutomationElement]
$hnode = $elementType.GetField('_hnode', $flags).GetValue($field)
$coreApi = $elementType.Assembly.GetType('MS.Internal.Automation.UiaCoreApi')
# void UiaGetPropertyValue(SafeNodeHandle, int, out object) on the guest's 4.0 assembly.
$getValue = $coreApi.GetMethods($flags) | Where-Object { $_.Name -eq 'UiaGetPropertyValue' -and $_.GetParameters().Count -eq 3 } | Select-Object -First 1
$wrap = $elementType.GetMethods($flags) | Where-Object { $_.Name -eq 'Wrap' -and $_.GetParameters().Count -eq 1 } | Select-Object -First 1
function Resolve($raw) {
    if ($null -eq $raw) { return 'null' }
    if ($raw -eq [System.Windows.Automation.AutomationElement]::NotSupported) { return 'NotSupported (the platform''s empty default)' }
    if ($raw -is [System.Array]) { return '[' + (($raw | ForEach-Object { Resolve $_ }) -join ', ') + ']' }
    if ($raw -is [System.Windows.Automation.AutomationElement]) { return (Describe $raw) }
    if ($raw.GetType().Name -eq 'SafeNodeHandle') { return (Describe ($wrap.Invoke($null, @($raw)))) }
    if ($raw -is [long] -or $raw.GetType().FullName -eq 'System.__ComObject') {
        # What the core hands back for an element: a node handle as a 64-bit integer for a single
        # element (LabeledBy read raw is one), and for an array's elements an object that answers
        # neither the provider nor the dispatch interface. The managed layer turns either into a
        # node the way its own element-array converter does, through the core's own
        # UiaHUiaNodeFromVariant, and then Wrap makes it an element.
        $fromVariant = $coreApi.GetMethods($flags) | Where-Object { $_.Name -eq 'UiaHUiaNodeFromVariant' } | Select-Object -First 1
        if (-not $fromVariant) {
            return ($raw.GetType().FullName + '; UiaCoreApi has no UiaHUiaNodeFromVariant, only: ' +
                (($coreApi.GetMethods($flags) | Where-Object { $_.Name -like '*Node*' } | ForEach-Object { $_.Name }) -join ','))
        }
        # Unwrapped: an array element that went through a pipeline arrives as a PSObject.
        $bare = $raw.PSObject.BaseObject
        $node = $fromVariant.Invoke($null, [object[]]@($bare))
        return (Describe ($wrap.Invoke($null, [object[]]@($node))))
    }
    return ($raw.GetType().FullName + ' ' + $raw)
}
foreach ($pair in @(@(30105, 'DescribedBy'), @(30104, 'ControllerFor'))) {
    $id = $pair[0]; $name = $pair[1]
    try {
        $arguments = [object[]]@($hnode, [int]$id, $null)
        $getValue.Invoke($null, $arguments) | Out-Null
        $raw = $arguments[2]
        Write-Output ("{0} ({1}) -> {2}" -f $name, $id, (Resolve $raw))
    } catch {
        $inner = if ($_.Exception.InnerException) { $_.Exception.InnerException } else { $_.Exception }
        Write-Output ("{0} ({1}) -> {2}: {3}" -f $name, $id, $inner.GetType().Name, $inner.Message)
    }
}

# The mirror from the caption's side, and a field that declares nothing.
if ($labeledBy -is [System.Windows.Automation.AutomationElement]) {
    $back = $labeledBy.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::LabeledByProperty)
    Write-Output ("caption's own LabeledBy -> " + (Describe $back))
}
exit 0
