# Reads the date widgets of the accessibility gallery through a real UI Automation client, at the
# steps the gallery's reader driver prints, and says what a client is told: for a calendar grid the
# Grid, Table and Selection patterns, a day cell's GridItem, TableItem and SelectionItem answers, its
# position in its month and whether a refused day is enabled; for a segmented field each segment's
# control type, RangeValue and Value answers; for a picker the field's ExpandCollapse state and the
# calendar in whatever window it opened; and everywhere, where the keyboard focus is. The Windows
# sibling of walk-the-tree.ps1 and walk-the-table.ps1, for ADR 042 §8 (H3 of the 2026-09-13 pass).
#
# It drives nothing. The gallery's driver does (`--reader <entry>`, one entry alone in a window
# titled "Limn accessibility gallery", its clock pinned to 2026-09-09 and its language to pt-BR
# unless `--locale` says otherwise); this waits for the driver's own "--- step N " line in its log
# before each snapshot, because a snapshot one step early reads the wrong day with complete
# confidence. Names are what the build publishes in the driver's language, so they are printed and
# never matched, except the window title.
#
# The entries and the steps each snapshot follows are the gallery lane's (2026-09-15):
#   calendar     "Calendar grid": September 2026, the 15th selected, week numbers, Sundays refused,
#                the 21st marked; 1 RIGHT the 16th, 4 LEFT the 21st, 5 LEFT Sunday the 20th (refused),
#                8 PAGE_DOWN October the 20th, 12 CMD+UP the months.
#   date-field   "Date field, segmented": 1 RIGHT the second segment, 9 '1' a second digit rolls on,
#                10 DELETE clears the segment, 14 TAB the empty due date, 15 UP fills it.
#   date-picker  "Date picker, closed", native popup by default: 1 ALT+DOWN opens the calendar,
#                2 RIGHT a day on, 5 ENTER picks and closes, 6 ALT+DOWN opens it again, 7 CMD+UP the
#                months, 9 ESCAPE closes it.
# A snapshot is also taken once the driver prints "--- focus", before step 1.
#
# Run it from a task with /IT after the demo is up (a shell over SSH is session 0, where no window
# is), with the demo's standard output going to the log named here:
#   schtasks /Create /TN LimnWalkDates /TR C:\Users\<user>\walkdates.cmd /SC ONCE /ST 23:59 /IT /RU <user> /F
#   schtasks /Run /TN LimnWalkDates
# A client pass never shares a reader pass (decision 42): asking for elements changes what the bridge
# raises.
#
# usage: walk-the-dates.ps1 <calendar|date-field|date-picker> [demo-log] [window-name]
# exit: 0 when every snapshot found what its entry publishes; 2 when an expected control type was
# absent from some snapshot; 1 when the window never appeared.
param([Parameter(Mandatory = $true)][ValidateSet('calendar', 'date-field', 'date-picker')][string]$Entry,
      [string]$Log = "$env:USERPROFILE\dates-reader.log",
      [string]$WindowName = 'Limn accessibility gallery')

$ErrorActionPreference = 'Continue'
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient') | Out-Null
[System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes') | Out-Null
$AE = [System.Windows.Automation.AutomationElement]
$Scope = [System.Windows.Automation.TreeScope]
$CT = [System.Windows.Automation.ControlType]
$script:missing = $false

function Wait-Line($pattern) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $Log) -and (Select-String -Path $Log -Pattern $pattern -SimpleMatch -Quiet)) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    # To the host and not the output: a string written here would make the caller's `if` true.
    Write-Host "'$pattern' never printed"
    return $false
}

function Kind($e) { $e.Current.ControlType.ProgrammaticName -replace 'ControlType.', '' }
function Patterns($e) {
    ($e.GetSupportedPatterns() | ForEach-Object {
        $_.ProgrammaticName -replace 'PatternIdentifiers.Pattern', '' }) -join ','
}
function Label($e) {
    if ($null -eq $e) { return 'null' }
    return ("{0} '{1}'" -f (Kind $e), $e.Current.Name)
}
function Pattern($e, $pattern) {
    $out = $null
    if ($e.TryGetCurrentPattern($pattern, [ref]$out)) { return $out }
    return $null
}
function Property($e, $property) {
    $v = $e.GetCurrentPropertyValue($property)
    if ($v -eq $AE::NotSupported) { return 'NotSupported' }
    return $v
}

# One element, with every answer this client asks of a date widget's part.
function Describe-Element($e, $indent) {
    $line = "{0}{1} patterns=[{2}] enabled={3} keyboard={4}" -f $indent, (Label $e), (Patterns $e),
        $e.Current.IsEnabled, $e.Current.HasKeyboardFocus
    # The managed client's public identifiers for both live on AutomationElementIdentifiers.
    $position = Property $e ([System.Windows.Automation.AutomationElementIdentifiers]::PositionInSetProperty)
    $size = Property $e ([System.Windows.Automation.AutomationElementIdentifiers]::SizeOfSetProperty)
    $line += " position={0} of {1}" -f $position, $size
    if ($e.Current.HelpText) { $line += " help='" + $e.Current.HelpText + "'" }
    if ($e.Current.ItemStatus) { $line += " status='" + $e.Current.ItemStatus + "'" }
    Write-Output $line
    $gi = Pattern $e ([System.Windows.Automation.GridItemPattern]::Pattern)
    if ($gi) {
        Write-Output ("{0}  GRIDITEM Row={1} Column={2} ContainingGrid={3}" -f $indent, $gi.Current.Row,
            $gi.Current.Column, (Label $gi.Current.ContainingGrid))
    }
    $ti = Pattern $e ([System.Windows.Automation.TableItemPattern]::Pattern)
    if ($ti) {
        Write-Output ("{0}  TABLEITEM ColumnHeaderItems=[{1}]" -f $indent,
            (($ti.Current.GetColumnHeaderItems() | ForEach-Object { $_.Current.Name }) -join ', '))
    }
    $si = Pattern $e ([System.Windows.Automation.SelectionItemPattern]::Pattern)
    if ($si) {
        Write-Output ("{0}  SELECTIONITEM IsSelected={1} SelectionContainer={2}" -f $indent,
            $si.Current.IsSelected, (Label $si.Current.SelectionContainer))
    }
    $ec = Pattern $e ([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
    if ($ec) { Write-Output ("{0}  EXPANDCOLLAPSE {1}" -f $indent, $ec.Current.ExpandCollapseState) }
    $rv = Pattern $e ([System.Windows.Automation.RangeValuePattern]::Pattern)
    if ($rv) {
        Write-Output ("{0}  RANGEVALUE Value={1} Minimum={2} Maximum={3} SmallChange={4} IsReadOnly={5}" -f $indent,
            $rv.Current.Value, $rv.Current.Minimum, $rv.Current.Maximum, $rv.Current.SmallChange, $rv.Current.IsReadOnly)
    }
    $vp = Pattern $e ([System.Windows.Automation.ValuePattern]::Pattern)
    if ($vp) { Write-Output ("{0}  VALUE '{1}' IsReadOnly={2}" -f $indent, $vp.Current.Value, $vp.Current.IsReadOnly) }
}

# The windows of the demo's process: the gallery window and any native popup it opened.
function Windows-Of-The-Demo() {
    $byName = New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $WindowName)
    $main = $AE::RootElement.FindFirst($Scope::Children, $byName)
    if (-not $main) { return @() }
    $byProcess = New-Object System.Windows.Automation.PropertyCondition($AE::ProcessIdProperty, $main.Current.ProcessId)
    return @($AE::RootElement.FindAll($Scope::Children, $byProcess))
}

function Find-All($root, $type) {
    $byType = New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, $type)
    return @($root.FindAll($Scope::Descendants, $byType))
}

function Describe-Grid($grid) {
    Write-Output ("GRID {0} patterns=[{1}] keyboard={2}" -f (Label $grid), (Patterns $grid), $grid.Current.HasKeyboardFocus)
    $gp = Pattern $grid ([System.Windows.Automation.GridPattern]::Pattern)
    if (-not $gp) { Write-Output '  no Grid pattern'; return }
    Write-Output ("  GRID RowCount={0} ColumnCount={1}" -f $gp.Current.RowCount, $gp.Current.ColumnCount)
    $tp = Pattern $grid ([System.Windows.Automation.TablePattern]::Pattern)
    if ($tp) {
        Write-Output ("  TABLE RowOrColumnMajor={0} ColumnHeaders=[{1}] RowHeaders count={2}" -f $tp.Current.RowOrColumnMajor,
            (($tp.Current.GetColumnHeaders() | ForEach-Object { $_.Current.Name }) -join ', '), @($tp.Current.GetRowHeaders()).Count)
    }
    $sp = Pattern $grid ([System.Windows.Automation.SelectionPattern]::Pattern)
    if ($sp) {
        Write-Output ("  SELECTION CanSelectMultiple={0} IsSelectionRequired={1} GetSelection=[{2}]" -f $sp.Current.CanSelectMultiple,
            $sp.Current.IsSelectionRequired, (($sp.Current.GetSelection() | ForEach-Object { $_.Current.Name }) -join ', '))
    } else {
        Write-Output '  no Selection pattern on the grid'
    }
    foreach ($rc in @(@(0, 0), @(2, 3))) {
        try {
            $item = $gp.GetItem($rc[0], $rc[1])
            if ($item) {
                Write-Output ("  GetItem({0},{1}):" -f $rc[0], $rc[1])
                Describe-Element $item '    '
            } else {
                Write-Output ("  GetItem({0},{1}) -> null" -f $rc[0], $rc[1])
            }
        } catch { Write-Output ("  GetItem({0},{1}) -> {2}" -f $rc[0], $rc[1], $_.Exception.GetType().Name) }
    }
    $refused = @(Find-All $grid $CT::DataItem | Where-Object { -not $_.Current.IsEnabled })
    Write-Output ("  not enabled: {0} items, first [{1}]" -f $refused.Count,
        (($refused | Select-Object -First 3 | ForEach-Object { $_.Current.Name }) -join ', '))
}

function Describe-Fields($root) {
    foreach ($group in (Find-All $root $CT::Group)) {
        $segments = @(Find-All $group $CT::Spinner)
        if ($segments.Count -eq 0) { continue }
        $labeledBy = $group.GetCurrentPropertyValue($AE::LabeledByProperty)
        Write-Output ("FIELD {0} patterns=[{1}] LabeledBy={2}" -f (Label $group), (Patterns $group), (Label $labeledBy))
        Describe-Element $group '  '
        foreach ($segment in $segments) { Describe-Element $segment '  ' }
    }
}

function Snap($label) {
    Write-Output "=== $Entry $label ==="
    $windows = Windows-Of-The-Demo
    if ($windows.Count -eq 0) { Write-Output 'NO WINDOW'; $script:missing = $true; return }
    foreach ($w in $windows) { Write-Output ("window {0} class '{1}'" -f (Label $w), $w.Current.ClassName) }
    $focused = $AE::FocusedElement
    if ($focused) {
        Write-Output 'FOCUSED:'
        Describe-Element $focused '  '
    }
    $grids = @(); $spinners = @()
    foreach ($w in $windows) {
        $grids += Find-All $w $CT::DataGrid
        $spinners += Find-All $w $CT::Spinner
    }
    foreach ($grid in $grids) { Describe-Grid $grid }
    foreach ($w in $windows) {
        Describe-Fields $w
        foreach ($button in (Find-All $w $CT::Button)) {
            if ((Pattern $button ([System.Windows.Automation.ExpandCollapsePattern]::Pattern)) -or
                (Pattern $button ([System.Windows.Automation.InvokePattern]::Pattern))) {
                Describe-Element $button 'BUTTON '
            }
        }
    }
    switch ($Entry) {
        'calendar' { if ($grids.Count -eq 0) { Write-Output 'NO DATAGRID: the calendar table is not reaching UI Automation'; $script:missing = $true } }
        default { if ($spinners.Count -eq 0) { Write-Output 'NO SPINNER: the segments are not reaching UI Automation'; $script:missing = $true } }
    }
}

$steps = switch ($Entry) {
    'calendar' { @(@(1, 'RIGHT: the 16th'), @(4, 'LEFT: the 21st, marked'), @(5, 'LEFT: Sunday the 20th, refused'),
                   @(8, 'PAGE_DOWN: October the 20th'), @(12, 'CMD+UP: the months')) }
    'date-field' { @(@(1, 'RIGHT: the second segment'), @(9, "'1': a second digit rolls on"),
                     @(10, 'DELETE: the segment cleared'), @(14, 'TAB: the empty due date'), @(15, 'UP: the empty segment filled')) }
    'date-picker' { @(@(1, 'ALT+DOWN: the calendar opens'), @(2, 'RIGHT: a day on'), @(5, 'ENTER: picked and closed'),
                      @(6, 'ALT+DOWN: open again'), @(7, 'CMD+UP: the months'), @(9, 'ESCAPE: closed')) }
}

if (-not (Wait-Line '--- focus ')) { exit 1 }
Start-Sleep -Seconds 1
Snap 'after the driver put the keyboard in, before step 1'
foreach ($step in $steps) {
    if (Wait-Line ("--- step {0} " -f $step[0])) {
        Start-Sleep -Seconds 1
        Snap ("after step {0} {1}" -f $step[0], $step[1])
    }
}
if ($script:missing) { exit 2 }
exit 0
