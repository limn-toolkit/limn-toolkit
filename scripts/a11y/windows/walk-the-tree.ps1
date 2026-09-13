# Reads the demo's tree (`--scene tree-reader`) through a real UI Automation client, at the steps
# the demo prints, and says what a client is told: the Tree control type, each realized item's
# control type and patterns, its ExpandCollapse state and SelectionItem state, and where the
# keyboard focus is. The check ADR 044 §4 owes this bridge, the way walk-the-table.ps1 is ADR 041's.
#
# The snapshots wait for the demo's own "--- step N" line in its log rather than sleeping, because
# a snapshot one step early reads the wrong row with complete confidence.
#
# Run it from a task with /IT after the demo is up (a shell over SSH is session 0):
#   schtasks /Create /TN LimnWalkTree /TR C:\Users\<user>\walktree.cmd /SC ONCE /ST 23:59 /IT /RU <user> /F
#   schtasks /Run /TN LimnWalkTree
#
# usage: walk-the-tree.ps1 [window-name] [demo-log]
param([string]$WindowName = 'Limn UI: Kitchen Sink',
      [string]$Log = "$env:USERPROFILE\tree-reader.log")

$ErrorActionPreference = 'Continue'
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes') | Out-Null
$AE = [System.Windows.Automation.AutomationElement]
$Scope = [System.Windows.Automation.TreeScope]

function Wait-Step($n) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $Log) -and
            (Select-String -Path $Log -Pattern ("--- step {0} " -f $n) -SimpleMatch -Quiet)) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Output "step $n never printed"
    return $false
}

function Kind($e) { $e.Current.ControlType.ProgrammaticName -replace 'ControlType.', '' }
function Patterns($e) {
    ($e.GetSupportedPatterns() | ForEach-Object {
        $_.ProgrammaticName -replace 'PatternIdentifiers.Pattern', '' }) -join ','
}
function Text-Of($e) {
    if ($e.Current.Name) { return $e.Current.Name }
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $child = $walker.GetFirstChild($e)
    while ($child) {
        if ($child.Current.Name) { return $child.Current.Name }
        $child = $walker.GetNextSibling($child)
    }
    return ''
}

function Snap($label) {
    Write-Output "=== $label ==="
    $byName = New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $WindowName)
    $window = $AE::RootElement.FindFirst($Scope::Children, $byName)
    if (-not $window) { Write-Output 'NO WINDOW'; return }
    $byType = New-Object System.Windows.Automation.PropertyCondition(
        $AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Tree)
    $tree = $window.FindFirst($Scope::Descendants, $byType)
    if (-not $tree) { Write-Output 'NO TREE CONTROL: the role is not reaching UI Automation'; return }
    Write-Output ("{0} patterns=[{1}]" -f (Kind $tree), (Patterns $tree))
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $item = $walker.GetFirstChild($tree)
    $shown = 0
    while ($item -and $shown -lt 12) {
        $line = "  {0} '{1}' patterns=[{2}]" -f (Kind $item), (Text-Of $item), (Patterns $item)
        try {
            $ec = $item.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
            $line += " expand=" + $ec.Current.ExpandCollapseState
        } catch { }
        try {
            $si = $item.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            $line += " selected=" + $si.Current.IsSelected
        } catch { }
        if ($item.Current.HasKeyboardFocus) { $line += ' FOCUS' }
        Write-Output $line
        $item = $walker.GetNextSibling($item)
        $shown++
    }
    $focused = $AE::FocusedElement
    if ($focused) { Write-Output ("focused: {0} '{1}'" -f (Kind $focused), (Text-Of $focused)) }
}

if (Wait-Step 1) { Start-Sleep -Seconds 1; Snap 'after step 1 DOWN: lands on Documents' }
if (Wait-Step 3) { Start-Sleep -Seconds 1; Snap 'after step 3 LEFT: Reports closed' }
if (Wait-Step 4) { Start-Sleep -Seconds 1; Snap 'after step 4 RIGHT: Reports open again' }
if (Wait-Step 14) { Start-Sleep -Seconds 2; Snap 'after step 14 RIGHT: Remote opened and loaded' }
exit 0
