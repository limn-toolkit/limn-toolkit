---
title: "Lists and scrolling"
description: "Scroll views, virtualized lists, split panes and tabs: the widgets for content that does not fit."
---

## Scrolling anything

Wrap a widget in a `ScrollView` and it scrolls. That is the whole API for the common case:

```java
ScrollView scroller = new ScrollView(longColumn);
```

By default it scrolls vertically. Ask for both axes with the three-argument constructor, and
decide when the bars appear with `setScrollbarPolicy(…)`. Scrollbars are draggable, respond
to the wheel, and can either overlay the content or take a gutter of their own. The latter
is what you want when the content underneath is text the bar would otherwise sit on.

Scrolling is also automatic on focus: tab to a control that is out of view and the nearest
scrolling ancestor brings it into view, which means keyboard users never lose the cursor
inside a long form.

## Long lists

A `ScrollView` measures everything inside it. For a thousand rows that is a thousand
widgets, and it will show. `ListView` exists for that case: it only ever builds the rows
that are actually visible, so a list of a million rows costs what a list of twenty does.

The items are your own `List`, handed over with `setItems`, and a row's widget is a function of
its item:

{% snippet guide:list %}

The detail pane *watches* the selection rather than handling it: it must follow the selection
wherever it came from — a click, an arrow key, a `setSelectedIndex` from code, a refresh that
dropped the selected row — and `onSelect` is the application's response to the *user* choosing a
row, which runs for none of the others. `onActivate` is a handler and stays one: opening a record
is something the user asks for.

The cell function runs when a row comes into view, and again for the same item after it has
scrolled out and back. For a long list of one kind of row, `ListView.pooled` keeps the widgets
that scroll out and fills them with the next item instead of making new ones; when rows come in
several kinds, `new ListView<>(cellFor, recycle)` hands each widget back to you to pool as you like.

{% snippet guide:list-pooled %}

Rows the user rearranges by dragging, and two lists that trade rows, are a few lines more:
[Drag and drop](/docs/drag-and-drop/) has them.

### A button in a row

A button in a row acts on that row's item, and how it finds the item depends on where the row's
widget came from. A cell function that makes a fresh widget for each row — `new ListView<>(cellFor)`,
or a table's `Column.widget` — is handed the item it builds for, so the button holds on to it:
`item -> new Button("Remove").onAction(() -> remove(item))`. A pooled row shows another item after
it is recycled, so the row keeps the item it is showing, and the button, given its action once when
the row is made, reads it when pressed:

{% snippet guide:list-row-button %}

Nothing is registered again when a row is recycled. An action slot holds one handler and a second
registration throws, which is what makes re-registering it in the bind function the wrong place:
it would have to be cleared with `onAction(null)` first, every time.

`setSelectionMode` takes the `SelectionMode` that `Table` and `Tree` take, with the same
gestures. `SINGLE` is the default. With `MULTI`, the command modifier (Ctrl, or Cmd on macOS)
toggles a row, Shift selects a range, Space toggles the row under the keyboard, and Ctrl+A or
Cmd+A takes every row. `NONE` moves the keyboard without selecting anything. `selectedIndex()`
is the lead, the row selected last, which Ctrl+A or Cmd+A leaves where it was (the first row when
there was none), `selectedIndices()` and `selectedItems()` are all of them, and
`cursorIndex()` is the row the keyboard is on, which in `MULTI` need not be selected.

A list inside something that gives it no height of its own — a `Column`, a `ScrollView` — has to
choose a height, and it asks for room for eight rows, as `Table` and `Tree` do.
`setVisibleRows(n)` says how many you want instead, on all three. The height comes from the
theme's row height and not from an average of the rows built so far, so it does not change as you
scroll. And a wheel that reaches the end of a list's own scroll passes to the scroller around it,
so a list inside a page does not trap the wheel where its content stops.

Three things are the same in `ListView`, `Table` and `Tree`, which walk their rows with the
same code. A row brought into view from outside it, by a key, by `setSelectedIndex`, by a
refresh, comes in by the least scroll: it stops at the edge it came from, and a row already
in view moves nothing. Page Down and Page Up move the cursor a page and the view with it, so
the cursor keeps its place on screen and paging does not throw it between the top and the
foot. And a watcher hears a scroll: `observeChanges` is told a `VALUE` change whose origin says
who scrolled, `USER` for the wheel and the bar, `CODE` for `scrollBy`, `ADJUSTMENT` for a row
the widget brought into view itself. That is where to load more rows as the user nears the end,
or to keep two lists side by side on the same row.

`onSelect` runs when the *user* changes the selection, by a click or a key, and not when
your code does; it reads what it needs from the list. `onActivate` is the *open this* gesture,
which is Enter on the row under the keyboard or a double click, and it is handed that row's
index. `activate()` from code is a caller's verb: it tells whoever is watching that the
row was opened, and reaches no handler, so an application that wants its own open-the-row code
run calls that code.

:::tip[When the data changes]
Call `refresh()` after changing your list in place. The rows are rebuilt from the items, and
the scroll position and selection are kept; rows past a shorter list's end leave the selection.
A different list is `setItems`, which starts over with nothing selected. A selection
the refresh dropped is announced as the widget's own adjustment, so a detail pane that watches
the selection never keeps showing a record that is gone; `onSelect`, being the user's, stays
silent.
:::

### Setting the selection yourself

`ListView`, `ComboBox`, `SegmentedControl`, `TabbedPane` and `ButtonGroup` all answer this
the same way, so what you learn on one holds for the rest:

- An index you set has to exist. Out of range is a mistake in your code, not something to
  be quietly rounded into range, so it throws.
- `-1` is not a way to say "nothing". Where having nothing selected is a real state, there
  is a `clearSelection()` that names it, because a list of records need not have a current record.
  A tab strip, a combo box and a segmented control always have exactly one, so they offer
  none.
- Setting the selection reaches whoever is watching the widget, as a `SELECTION` change whose
  origin is `CODE`, and does not run `onSelect`: the handler is the application's response to
  the user, and a value the application wrote is not one. Anything that must describe the
  selection wherever it came from, a detail pane or a status line, watches instead, and so
  stays right without needing to know where the change came from.
- Setting the index that is already selected does nothing and announces nothing. Together with
  the first rule it is why two controls bound to each other through their handlers cannot
  bounce: a handler's write into the other control reaches no handler at all.

Arrow keys are not bound by the first rule: arrowing past either end lands on the end,
because that is what the key means.

## Tables

When every row has the same shape, a `Table` is a `ListView` with columns: a header that
sorts and resizes, cells that are values rather than widgets, and a selection by row. You
describe the columns once; the rows are your own `List`, held by reference and read only
when they are on screen, so a table over a million rows costs what one over twenty does.

{% snippet guide:table %}

A column names a title, how a row becomes a cell, and how wide it is. `Column.text` and
`Column.numeric` cover most of a business screen — a numeric column aligns to the reading
end and localizes its digits — `Column.currency` writes money the way the language in effect
writes it, symbol and side included, and `Column.of` takes any value with a formatter. Handed a
locale, as the size above is, the formatter gets the one the table writes numbers in,
`I18n.numberLocale()` under the table's language, so `String.format(locale, …)` writes the same
digits as the numeric columns beside it: Latin under a bare `ar`, Arabic-Indic under `ar-EG`. A
date column takes `DateFormats.date(FormatStyle.SHORT)` (or `time`, or `dateTime`), which writes
the language's date in those digits too, where the platform's own formatter writes ASCII digits
in every language. A numeric column takes any of `NumberFormats` for its cells and its
footer alike: `prefix("R$ ")` for a fixed prefix, `decimals(2)`, `unit(" kg")`, `compact()`. Widths are a preferred width, a minimum, and a weight: every
column gets its preferred width, and what is left of the viewport is shared among the
weighted ones. A column the user has dragged keeps that width and takes no share of what is
left until `resetWidth()`. A table wider than its viewport scrolls sideways, and the header scrolls with the
columns, because a title has to stay over the column it names. It is pinned vertically: the rows
scroll under it.

A click on a header sorts, cycling ascending, descending and your list's own order. The table
sorts through a permutation and never touches your list. Without a comparator of your own, a
column sorts by its values: numbers by exact value, text through the language's collator, and a
column that mixes kinds puts its numbers first, then its text. The selection is by **record**, not by
row number: a selected row stays selected through a sort, and through a `refresh()` that inserted
rows above it, because the table follows the record rather than the index it was at. Records that
are equal are matched by the order they occur in; where your records are mutable, or equal without
being the same thing, name a key with `rowKey(Order::id)` and the table follows that instead. A
record your list no longer holds leaves the selection, and a watcher hears it. For rows a server
orders, `onSortRequest` hands the click to you instead: reorder the list and call `refresh()` —
the selection survives that too.

The header is also a keyboard stop where sorting is on. Tab moves onto it, Left and Right move
along the columns, and Space sorts the column you are on, cycling the same three ways a click
does; Tab again returns to the rows. A screen reader's cursor follows it onto the column title
and back.

The table is published as a grid: rows of cells under column headers, with the sort direction on
the sorted header. Readers describe it in different shapes, one speaking a cell with its column,
one a whole row, one the cell, adding its column as the cursor moves along a row, and they
disagree about announcing a sort or a selection
change ([What readers say](/docs/accessibility/#what-readers-say)). Where a selection change is
the point of the screen, say it in the interface rather than leaving it to the reader to mention.

A column may also put something in the **footer**, a summary row pinned under the rows the
way the header is pinned over them: a text (`footer("Total")`), one of the aggregates a
numeric column offers (`footerSum()`, `footerAverage()`, `footerMin()`, `footerMax()`), the
row count any column can show (`footerCount()`), or a value you compute from all the rows
(`footer(rows -> …)`), formatted as that column's cells are. The row appears as soon as one
column has something for it, and it is recomputed on `setRows` and `refresh()`, never per frame.

Three selection modes: `NONE`, `SINGLE` and `MULTI`, where Shift selects a range, the
command modifier toggles one row and Ctrl+A or Cmd+A selects all. Whatever the mode, the arrow
keys move a focus cell, which is what a screen reader's cursor stands on; Enter or a double click
activates **the row the cursor is on**, which in `NONE` is the only thing that could be meant.
Page Down and Page Up move the cursor a page of rows and the view moves with it, so the cursor
keeps its place on screen; both clamp at the ends.

`Column.widget` is the escape hatch for a cell that is a control — a switch, a button — and those
cells are real children, mounted and released with their row, and published as cells of their row
so a reader finds them by row and column like any other. The factory is given the row, and a
button made there acts on it:

{% snippet guide:table-row-button %}

 A hidden column (`visible(false)`) builds
no widget and publishes nothing at all; show or hide one and the table lays out again, so ask for
a layout rather than a `refresh()` — the rows have not changed.

:::tip[When the data changes]
Call `refresh()` after your list's contents change, as with `ListView`: the sort is
re-applied, a selected row the list no longer has is dropped and a watcher of the selection
hears it, and the scroll position is kept.
:::

### Columns the user arranges

The user moves a column by dragging its header, the whole column following the pointer while
the others make room, or with Alt+Shift+Left and Right, which move the column the keyboard is
on. `setColumnReorderingAllowed(false)` turns that off, as resizing can be turned off.

{% snippet guide:table-columns %}

`setFrozenColumns(n)` keeps the first columns in place while the others scroll sideways under
them: the name beside a year of monthly figures. The user then moves a column only within its
group, the kept ones or the others, and where the kept columns would leave no room for the
rest, none is kept.

`onColumnsChange` runs when the *user* moved or resized a column, which is when to save the
layout: `columns()` answers the order as it stands, hidden columns included, and each column
its width. Putting a saved layout back is `moveColumn(column, index)` and the widths you saved,
and neither is reported back to you.

### Editing a record

There is no cell editing, and there will not be. Editing in place is a spreadsheet's
interaction and reads as one everywhere else: a field that appears where a value was, a save
on a keystroke the user did not mean as a save, a validation error with nowhere to stand, a
change to one field of a record whose other fields depend on it. A table is for reading,
comparing, sorting and choosing; a record is edited whole.

What to do instead, each with what the toolkit already has:

- **A dialog or a panel for the record.** On `onActivate` — Enter, a double click, or a button
  in a widget column — open a `Dialog`, or a form beside the table, showing the whole record
  with labelled and validated fields and an explicit Save. On save, change your list and
  call `refresh()`; the selection is by model row and stays put.
- **Master and detail.** Keep the form open in a `SplitPane` beside the table and bind it to
  the lead row by watching the table's `SELECTION`, as the example above does, so the user
  walks the records with the arrow keys and edits each in a form that never moves, and the
  form follows a selection your own code set just as well.
- **A control in a widget column** for one-gesture changes: a switch to flag a row, a button
  to open it, a checkbox to include it.
- **A bulk action** over a `MULTI` selection when the same change applies to many rows.

## Trees

When the rows are a hierarchy, a `Tree` is an outline over nodes you already have: an indent, a
disclosure triangle where a row can open, and your own widget beside it. The toolkit has no node
type to wrap yours in, so a tree over `Path`, over a record or over a live domain object is the
same tree, and a node stays the same node for as long as its own `equals` says so.

{% snippet guide:tree %}

The model answers the three questions only your data can: where the tree starts (`roots`), what is
under a node (`children`), and what draws one (`cellFor`). **A node whose children are not known
yet answers `null`**, which is a different answer from an empty list. An empty list is a leaf;
`null` is a promise. The row keeps its triangle, because a folder nobody has read is not a file,
and opening it sends the tree to `load`, whose `Work` reads the folder off the UI thread and
delivers on it. While it runs, the row shows a spinner where its triangle goes and a "Loading…"
line where its children will be; you draw none of that, and the arrows walk past the line. Close
the row before the job lands and the job is cancelled,
since a result nobody is looking at is one nobody should pay for; what does land is kept for
as long as the row is open and let go when it closes, so a tree over a whole disk holds what is
open and no more. Opening the row again loads again; a model that wants that to cost nothing
keeps what it loaded and answers it from `children`. A load that fails closes the row rather than leaving it open
and empty, which would say the folder has nothing in it. A load that returns *nothing* is a
different answer again: the row stays open over a discreet "Empty" line, in the "Loading…" line's
place, because a folder that has been read and is empty is not a folder nobody has read. An eager
branch with no children shows the same line.

**Somebody listening is told in words.** The spinner and the line are for eyes. The row also
publishes a busy state beside them, but no screen reader speaks one, and the "Loading…" line is
deliberately not a row the cursor can stand on, so a reader steps over that as well. The tree
announces the load instead, in three sentences named after the branch: "Loading *branch*" when a
lazy load begins, "*branch*, 3 items" when its children arrive, counted in the reader's language,
and "*branch* empty" when it lands on nothing. Each is polite, so NVDA and Orca say it when they
finish what they are saying; VoiceOver has no level that waits, so there it is posted high and
interrupts, since otherwise it is held behind VoiceOver's own hint for seconds. An eager branch
that runs no load announces nothing.

The example overrides `isLeaf` because an entry already knows whether it is a folder; left alone,
the tree reads `children` and calls a node a leaf only when its children are known and there are
none.

**A node appears once.** The tree's identity is the node's own `equals`, so the same node in two
places would share one selection, one expanded state and one accessible identity; building the
rows throws instead, naming the node, rather than letting a tree behave strangely in a way nobody
traces back. Where your data really repeats — the same file under two folders — give the model a
node that carries its path and not only its name.

Up and Down walk the rows that are showing. **Right opens a closed row and steps into an open one;
Left closes an open row and steps out to its parent.** On an open row with nothing under it Right
stays where it is, rather than stepping to a sibling or an uncle. In a right-to-left language the
two swap, because the indent grows from the right there and deeper is to the left.

**The cursor and the selection are two things.** The cursor is the row the keyboard is on, drawn
with a thin ring around its cell and published as where a screen reader stands; the selection is
what you have chosen. In `SINGLE` they move together. In `MULTI` — Shift for a range, the command
modifier (Cmd on macOS, Ctrl elsewhere) to toggle one row, Ctrl+A or Cmd+A for every open row —
the cursor can sit outside the selection, and `cursorNode()` answers it while `selectedNodes()`
answers the set. `onSelect` is the user's response slot and takes no node for that reason: read
`selectedNodes()` in it. `setSelectedNodes(…)`, `clearSelection()` and `selectAll()` are the
code-side writes, and they reach a watcher rather than `onSelect`. Enter and a double click
activate the cursor row, in every mode.

Selection is by node rather than by row number, so it survives an expansion that renumbers every
row below it, and a row hidden by a collapse is still selected when its parent opens again;
collapsing the branch the cursor is in moves the cursor up to the row you collapsed, which is
where you are looking.

Each reader speaks a row in its own shape, and not all of them say its position
([What readers say](/docs/accessibility/#what-readers-say)). Children that arrive from a `load`
are read like any other row once they land. A branch that opens on nothing is announced by the
sentence above, because the "Empty" line is not a row the cursor stops on.

Rows are realized where the viewport reaches, as a `ListView`'s are, in the order of a walk over
what is open, so a tree over a deep directory scrolls vertically the way a list does and costs what
a list over its visible rows does. A tree too deep for its viewport scrolls sideways; the indent is
never squeezed. Every level charges an indent and none gives it back, so past some depth a name
would begin beyond the edge of the box, and squeezing the indent to keep it in would draw level
twelve where level eight sits, flattening the very structure someone that deep is reading. The
content grows as wide as the deepest open row needs instead, and the box scrolls over it: by the
bar, by a sideways swipe on a trackpad, or by the wheel with Shift held. Moving the cursor onto a
deep row scrolls sideways too, by the least that brings the row's triangle and the start of its
cell into view, so End does not leave a name off the edge. How wide the deepest cell is allowed to
be is the model's to say: `maxCellWidth()` is a cap — never more than what the box gives a root
row — and left alone the tree guesses from a menu's minimum width.

That has a price you can see. **Widening the content moves where a cell ellipsizes**, from the
edge of the box to the edge of the content, so once a tree is deep enough to scroll sideways a long
name runs on until the content ends and is cut there, possibly out of view. A tree with nothing
that deep is exactly as wide as its box, shows no horizontal bar, and cuts its names where it
always did: the trade arrives only with the depth that needs it.

A cell is an ordinary widget. The tree keeps the indent and the triangle and hands the rest of the
row's width to whatever `cellFor` returned, so an icon before the text is a `Label` with an icon,
and a count or a button against the trailing edge is a `Row` whose text is `Expanded`. Nothing on
`Tree` configures either, and nothing needs to: a row is composed the way any other part of a
window is. What the tree does owe such a row is to keep the scroll bar off it, which
`setBarLayout(ScrollGutters.Layout.RESERVED)` asks for, as the scrollbar section below explains. A
control in a cell is a real child, mounted and released with its row, and `recycle` hands a cell
back when its row scrolls away, if you would rather pool the widget than build another.

## Splitting a window

`SplitPane` gives two children a draggable divider:

```java
SplitPane panes = SplitPane.horizontal(sidebar, content);
panes.setRatio(0.3f).setMinimums(140, 260);
```

The ratio is the fraction the first child gets, and the minimums are in points; below them
the divider stops rather than letting a pane collapse to nothing. `SplitPane.vertical(…)`
is the same thing stacked. `setDividerFocusable(true)` makes the divider a Tab stop, met between
the two panes; the arrows move it by a step, Shift with them by a point, and Home and End to
either minimum.

## Tabs

`TabbedPane` takes a title and a widget per tab:

```java
TabbedPane tabs = new TabbedPane();
tabs.addTab("Overview", overview);
tabs.addTab("Details", details);
```

When there are more tabs than fit, the strip scrolls, chevrons appear at the ends, and an
overflow button lists the rest, so you do not have to decide what happens at what width.

Tabs the user opens, documents in an editor, are tabs the user closes and moves:

```java
TabbedPane documents = new TabbedPane().setTabsClosable(true);
documents.onTabClose(request -> {
    if (isSaved(request.content())) {
        request.allow();
    } else {
        askToSave(request.content(), saved -> {
            if (saved) {
                request.allow();
            } else {
                request.refuse();
            }
        });
    }
});
documents.onTabMove((from, to) -> reorderDocuments(from, to));
```

`setTabsClosable(true)` makes every tab added after it closable, and `setTabClosable(index, …)`
one tab. A closable tab draws its close button while it is selected or under the pointer, and
closes by it, by a middle click, and by Ctrl+W (Cmd+W on macOS) while the keyboard is anywhere
in the pane. The handler is asked first and answers once, now or from a dialog later; while it
has not answered, the same tab is not asked again. Without a handler the tab closes. Closing the
selected tab selects the next one, or the one before when it was the last.

A tab is dragged along the bar to a new place, the others sliding out of its way, and moved from
the keyboard with Ctrl+Shift+Page Up and Page Down (Cmd on macOS), which a screen reader hears as
the tab's new position. `onTabMove` hears what the user moved; `removeTab`, `moveTab`,
`tabContent` and `indexOfTab` are the same operations for code, and reach no handler.

## Sections that open and close

An `Expander` is a header over content that slides out below it: a title, an arrow that turns,
an optional icon, and an optional summary at the end that says what is inside while it is
closed.

```java
Expander shipping = new Expander("Shipping", shippingForm).setSummary("Free");
Expander returns = new Expander("Returns", returnsText).setSummary("30 days");
new ExpanderGroup().add(shipping).add(returns).setExpandedIndex(0);
```

It opens and closes by a click, by Space or Enter on the header, and with `setExpanded`;
`onChange` hears the user. From the moment it starts to close, its content answers nothing while
it slides shut: it is drawn as it was, but a click, Tab and an access letter pass it by, and the
keyboard, if it was inside, goes to the header. Its controls keep the enabled state you gave
them. An `ExpanderGroup` keeps at most one of its sections open, an
accordion; a section needs no group. A screen reader hears the header as a button that is
expanded or collapsed and controls the content, and the summary as its description.

## The scrollbar

There is one, and every scroller uses it: `ListView`, `Table`, `Tree`, `TextArea` and `ScrollView`
all mount the same `ScrollBar`, so they behave alike without agreeing to. It reads the theme (the thumb is
`textMuted`, which is dark on a light palette and light on a dark one), it can be dragged by the
thumb or paged by clicking the track, and it is thin at rest and widens as the pointer
approaches, so it is thin to look at and thick to grab.

`setScrollbarPolicy` on `ListView`, `Table`, `Tree` or `ScrollView` decides when it shows:

| | |
| --- | --- |
| `HIDDEN` | never |
| `ALWAYS` | always, which is what `TextArea` uses |
| `ON_SCROLL` | while scrolling, then fades |
| `AUTO` | the same, plus while the pointer is over the host — `ListView`, `Table`, `Tree` and `ScrollView` |

By default they are overlays: a strip held open for a bar that is usually faded out is dead space.
Any of the five can give its bars strips of their own with
`setBarLayout(ScrollGutters.Layout.RESERVED)`, which is what a list of records, a table or a tree
usually wants, because the trailing edge of a row is where a count, a date or a status sits and a
thumb over it is a defect. The strip is held for as long as the content
overflows, not for as long as the bar is drawn, so a fading bar never makes the content reflow
under the pointer; pair it with `ALWAYS`, or the bar fades out of a strip that stays empty. A
`HIDDEN` bar reserves nothing.
