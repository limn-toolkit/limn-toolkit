# Walks the window LiveProbe opens, with a real UI Automation client, and prints what it finds.
#
# This is the one check no machine but a Windows one can make, and it is the check the whole
# module is built to pass: whether WM_GETOBJECT reaches the bridge, whether UI Automation accepts
# the provider, whether the slot order read off this guest is the order UI Automation calls, and
# whether what the bridge writes into a VARIANT arrives as what it meant.
#
# Run the probe first, from a task with /IT so it gets a desktop:
#   schtasks /Create /TN LimnProbe /TR C:\Users\<user>\probe.cmd /SC ONCE /ST 23:59 /IT /RU <user> /F
#   schtasks /Run /TN LimnProbe
# then this, the same way. A shell over SSH is session 0 and a window created there kills the JVM.
#
# What a pass looks like:
#   Window 'Limn accessibility probe' patterns=[Window,Transform] rid=[42,...] box=0;0;480;320
#     Button 'Save' help='Writes the file' patterns=[Invoke] rid=[42,...,4,0,1001] box=20;40;160;40
#     CheckBox 'Wrap lines' patterns=[Toggle] rid=[42,...,4,0,1002] box=20;100;160;24
#   TOGGLE state of 'Wrap lines' = On
#   INVOKE 'Save' supports Invoke = 1

$ErrorActionPreference = 'Stop'
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes') | Out-Null

$deadline = (Get-Date).AddSeconds(40)
$window = $null
while ((Get-Date) -lt $deadline -and -not $window) {
    $condition = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, 'Limn accessibility probe')
    $window = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
        [System.Windows.Automation.TreeScope]::Children, $condition)
    if (-not $window) { Start-Sleep -Milliseconds 500 }
}
if (-not $window) { Write-Output 'NOT FOUND'; exit 1 }

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Show($e, $depth) {
    $pad = ' ' * ($depth * 2)
    $patterns = ($e.GetSupportedPatterns() | ForEach-Object { $_.ProgrammaticName -replace 'PatternIdentifiers.Pattern','' }) -join ','
    $rid = ''
    try { $rid = ($e.GetRuntimeId() -join ',') } catch { $rid = 'err' }
    Write-Output ("{0}{1} '{2}' enabled={3} offscreen={4} help='{5}' patterns=[{6}] rid=[{7}] box={8}" -f `
        $pad, ($e.Current.ControlType.ProgrammaticName -replace 'ControlType.',''), $e.Current.Name,
        $e.Current.IsEnabled, $e.Current.IsOffscreen, $e.Current.HelpText, $patterns, $rid,
        $e.Current.BoundingRectangle)
    if ($depth -lt 6) {
        $c = $walker.GetFirstChild($e)
        while ($c) { Show $c ($depth + 1); $c = $walker.GetNextSibling($c) }
    }
}
Show $window 0

# And the two things a screen reader actually does with a control.
$byName = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty, 'Wrap lines')
$check = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $byName)
if ($check) {
    $toggle = $check.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    Write-Output ("TOGGLE state of 'Wrap lines' = {0}" -f $toggle.Current.ToggleState)
} else {
    Write-Output "TOGGLE: 'Wrap lines' not found by FindFirst"
}
$byName2 = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty, 'Save')
$save = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $byName2)
if ($save) {
    Write-Output ("INVOKE 'Save' supports Invoke = {0}" -f `
        ($save.GetSupportedPatterns() | Where-Object { $_.ProgrammaticName -like '*Invoke*' }).Count)
}
