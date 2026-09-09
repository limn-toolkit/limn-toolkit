# Walks the demo's table scene (`--scene table`) through a real UI Automation client and asks it
# what a table is asked: the Grid and Table patterns on the grid, the GridItem and TableItem
# patterns on a cell. The check ADR 041 §7 owes the Windows bridge, the way walk-the-probe.ps1 is
# the check ADR 039 owes it.
#
# Run it the way walk-the-probe.ps1 is run: from a task with /IT, after the demo is up:
#   schtasks /Create /TN LimnTable /TR C:\Users\<user>\table.cmd /SC ONCE /ST 23:59 /IT /RU <user> /F
#   schtasks /Run /TN LimnTable
# then this, the same way.
#
# usage: walk-the-table.ps1 [window-name]
param([string]$WindowName = 'Limn UI: Kitchen Sink')

$ErrorActionPreference = 'Stop'
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes') | Out-Null

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

function Patterns($e) {
    ($e.GetSupportedPatterns() | ForEach-Object { $_.ProgrammaticName -replace 'PatternIdentifiers.Pattern','' }) -join ','
}
function Kind($e) { $e.Current.ControlType.ProgrammaticName -replace 'ControlType.','' }

$byType = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::DataGrid)
$table = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $byType)
if (-not $table) { Write-Output 'NO DATAGRID'; exit 2 }
Write-Output ("{0} '{1}' patterns=[{2}]" -f (Kind $table), $table.Current.Name, (Patterns $table))

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
$child = $walker.GetFirstChild($table)
$shown = 0
while ($child -and $shown -lt 4) {
    Write-Output ("  {0} '{1}' patterns=[{2}]" -f (Kind $child), $child.Current.Name, (Patterns $child))
    $cell = $walker.GetFirstChild($child)
    while ($cell) {
        Write-Output ("    {0} '{1}' patterns=[{2}]" -f (Kind $cell), $cell.Current.Name, (Patterns $cell))
        $cell = $walker.GetNextSibling($cell)
    }
    $child = $walker.GetNextSibling($child)
    $shown++
}

$grid = $table.GetCurrentPattern([System.Windows.Automation.GridPattern]::Pattern)
Write-Output ("GRID RowCount={0} ColumnCount={1}" -f $grid.Current.RowCount, $grid.Current.ColumnCount)
$item = $grid.GetItem(1, 3)
Write-Output ("GRID GetItem(1,3) -> {0} '{1}'" -f (Kind $item), $item.Current.Name)
try {
    $far = $grid.GetItem(50000, 0)
    Write-Output ("GRID GetItem(50000,0) -> " + $(if ($far) { "'" + $far.Current.Name + "'" } else { 'null (unrealized row, as ADR 039 §4.1 accepts)' }))
} catch { Write-Output ("GRID GetItem(50000,0) -> " + $_.Exception.GetType().Name) }

$tp = $table.GetCurrentPattern([System.Windows.Automation.TablePattern]::Pattern)
Write-Output ("TABLE RowOrColumnMajor={0}" -f $tp.Current.RowOrColumnMajor)
$headers = $tp.Current.GetColumnHeaders()
Write-Output ("TABLE ColumnHeaders=[{0}]" -f (($headers | ForEach-Object { (Kind $_) + " '" + $_.Current.Name + "'" }) -join ', '))
Write-Output ("TABLE RowHeaders count={0}" -f @($tp.Current.GetRowHeaders()).Count)

$gi = $item.GetCurrentPattern([System.Windows.Automation.GridItemPattern]::Pattern)
Write-Output ("GRIDITEM Row={0} Column={1} RowSpan={2} ColumnSpan={3} ContainingGrid={4}" -f $gi.Current.Row, $gi.Current.Column, $gi.Current.RowSpan, $gi.Current.ColumnSpan, (Kind $gi.Current.ContainingGrid))
$ti = $item.GetCurrentPattern([System.Windows.Automation.TableItemPattern]::Pattern)
Write-Output ("TABLEITEM ColumnHeaderItems=[{0}] RowHeaderItems count={1}" -f (($ti.Current.GetColumnHeaderItems() | ForEach-Object { $_.Current.Name }) -join ', '), @($ti.Current.GetRowHeaderItems()).Count)
exit 0
