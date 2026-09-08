# Presses a button of the window LiveProbe opens through a real UI Automation client, and prints
# what the provider answered.
#
# The Windows sibling of scripts/a11y/linux/press-the-probe.py: walk-the-probe.ps1 shows what a
# reader is TOLD, and this shows what a reader can DO. The probe prints "Save pressed, through the
# toolkit's own path" when the press reaches the widget, so the line here and that line in
# probe.log are the whole of the check.
#
# Run it the way walk-the-probe.ps1 is run: from a task with /IT, after the probe is up.
#
# usage: press-the-probe.ps1 [button-name] [times]
param([string]$Button = 'Save', [int]$Times = 3)

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
if (-not $window) { Write-Output 'NOT FOUND'; exit 2 }

$byName = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::NameProperty, $Button)
$target = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $byName)
if (-not $target) { Write-Output "no element named '$Button'"; exit 3 }
$patterns = ($target.GetSupportedPatterns() | ForEach-Object { $_.ProgrammaticName -replace 'PatternIdentifiers.Pattern','' }) -join ','
Write-Output ("{0} '{1}' patterns=[{2}]" -f ($target.Current.ControlType.ProgrammaticName -replace 'ControlType.',''), $Button, $patterns)

$invoke = $target.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
$done = 0
for ($i = 0; $i -lt $Times; $i++) {
    try {
        $invoke.Invoke()
        Write-Output 'Invoke -> ok'
        $done++
    } catch {
        Write-Output ("Invoke -> {0}" -f $_.Exception.Message)
    }
    Start-Sleep -Milliseconds 500
}
if ($done -eq $Times) { exit 0 } else { exit 1 }
