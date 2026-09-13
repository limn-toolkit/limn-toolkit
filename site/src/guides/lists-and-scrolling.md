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

You supply the rows through an adapter:

```java
ListView list = new ListView(new ListView.Adapter() {
    @Override
    public int rowCount() {
        return people.size();
    }

    @Override
    public Widget rowAt(int index) {
        return new Padding(Insets.symmetric(9, 14), new Label(people.get(index).name()));
    }
});
list.observeChanges((widget, change) -> {
    if (change.aspect() == Change.Aspect.SELECTION) {
        detail.show(list.selectedIndex() < 0 ? null : people.get(list.selectedIndex()));
    }
});
list.onActivate(index -> open(people.get(index)));
```

The detail pane *watches* the selection rather than handling it: it must follow the selection
wherever it came from — a click, an arrow key, a `setSelectedIndex` from code, a refresh that
dropped the selected row — and `onSelect` is the application's response to the *user* choosing a
row, which runs for none of the others. `onActivate` is a handler and stays one: opening a record
is something the user asks for.

`rowAt` is called on demand and may be called again for the same row after it has scrolled
out and back. Build the widget there; do not cache one per data item, or you have rebuilt
the thing `ListView` exists to avoid.

`onSelect` runs when the *user* moves the selection, by a click or the arrow keys, and not when
your code does. `onActivate` is the *open this* gesture, which is Enter on the selected row or
a double click. `activate()` from code is a caller's verb: it tells whoever is watching that the
row was opened, and reaches no handler, so an application that wants its own open-the-row code
run calls that code.

:::tip[When the data changes]
Call `refresh()` after your backing list changes. The rows are rebuilt from the adapter,
and the scroll position and selection are kept, clamped if the list got shorter. A selection
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
writes it, symbol and side included, and `Column.of` takes any value with a formatter that is
handed the table's locale. A numeric column takes any of `NumberFormats` for its cells and its
footer alike: `prefix("R$ ")` for a fixed prefix, `decimals(2)`, `unit(" kg")`, `compact()`. Widths are a preferred width, a minimum, and a weight: every
column gets its preferred width, and what is left of the viewport is shared among the
weighted ones. A table wider than its viewport scrolls sideways; the header stays put.

A click on a header sorts, cycling ascending, descending and your list's own order. The
table sorts through a permutation and never touches your list, which is why the selection
— a set of *model* rows — survives a sort. For rows a server orders, `onSortRequest` hands
the click to you instead: reorder the list and call `refresh()`.

A column may also put something in the **footer**, a summary row pinned under the rows the
way the header is pinned over them: a text (`footer("Total")`), one of the aggregates a
numeric column offers (`footerSum()`, `footerAverage()`, `footerMin()`, `footerMax()`), the
row count any column can show (`footerCount()`), or a value you compute from all the rows
(`footer(rows -> …)`), formatted as that column's cells are. The row appears as soon as one
column has something for it, and it is recomputed on `setRows` and `refresh()`, never per frame.

Three selection modes: `NONE`, `SINGLE` and `MULTI`, where Shift selects a range, the
command modifier toggles one row and Ctrl+A or Cmd+A selects all. Whatever the mode, the
arrow keys move a focus cell, which is what a screen reader's cursor stands on; Enter or a
double click activates the lead row. `Column.widget` is the escape hatch for a cell that is
a control — a switch, a button — and those cells are real children, mounted and released
with their row.

:::tip[When the data changes]
Call `refresh()` after your list's contents change, as with `ListView`: the sort is
re-applied, a selected row the list no longer has is dropped and a watcher of the selection
hears it, and the scroll position is kept.
:::

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

The model answers the three questions only your data can: where the tree starts (`roots`), what
is under a node (`children`), and what draws one (`cellFor`). **A node whose children are not
known yet answers `null`**, which is a different answer from an empty list. An empty list is a
leaf; `null` is a promise. The row keeps its triangle, because a folder nobody has read is not a
file, and opening it sends the tree to `load`, whose `Work` reads the folder off the UI thread and
delivers on it. Close the row before the job lands and the job is cancelled, since a result nobody
is looking at is one nobody should pay for; what does land is kept, so opening the row again costs
nothing. A load that fails closes the row rather than leaving it open and empty, which would say
the folder has nothing in it. The example overrides `isLeaf` because an entry already knows
whether it is a folder; left alone, the tree reads `children` and calls a node a leaf only when
its children are known and there are none.

Up and Down walk the rows that are showing. **Right opens a closed row and steps into an open one;
Left closes an open row and steps out to its parent.** In a right-to-left language the two swap,
because the indent grows from the right there and deeper is to the left. Selection is by node
rather than by row number, so it survives an expansion that renumbers every row below it, and a row
hidden by a collapse is still selected when its parent opens again.

Rows are realized where the viewport reaches, as a `ListView`'s are, in the order of a walk over
what is open, so a tree over a deep directory scrolls vertically the way a list does and costs what
a list over its visible rows does. A tree too deep for its viewport scrolls sideways; the indent is
never squeezed. Every level charges an indent and none gives it back, so past some depth a name
would begin beyond the edge of the box, and squeezing the indent to keep it in would draw level
twelve where level eight sits, flattening the very structure someone that deep is reading. The
content grows as wide as the deepest open row needs instead, and the box scrolls over it: by the
bar, by a sideways swipe on a trackpad, or by the wheel with Shift held.

That has a price you can see. **Widening the content moves where a cell ellipsizes**, from the
edge of the box to the edge of the content, so once a tree is deep enough to scroll sideways a long
name runs on until the content ends and is cut there, possibly out of view. A tree with nothing
that deep is exactly as wide as its box, shows no horizontal bar, and cuts its names where it
always did: the trade arrives only with the depth that needs it.

A cell is an ordinary widget. The tree keeps the indent and the triangle and hands the rest of the
row's width to whatever `cellFor` returned, so an icon before the text is a `Label` with an icon,
and a count or a button against the trailing edge is a `Row` whose text is `Expanded`. Nothing on
`Tree` configures either, and nothing needs to: a row is composed the way any other part of a
window is. A control in a cell is a real child, mounted and released with its row, and `recycle`
hands a cell back when its row scrolls away, if you would rather pool the widget than build another.

## Splitting a window

`SplitPane` gives two children a draggable divider:

```java
SplitPane panes = SplitPane.horizontal(sidebar, content);
panes.setRatio(0.3f).setMinimums(140, 260);
```

The ratio is the fraction the first child gets, and the minimums are in points; below them
the divider stops rather than letting a pane collapse to nothing. `SplitPane.vertical(…)`
is the same thing stacked.

## Tabs

`TabbedPane` takes a title and a widget per tab:

```java
TabbedPane tabs = new TabbedPane();
tabs.addTab("Overview", overview);
tabs.addTab("Details", details);
```

When there are more tabs than fit, the strip scrolls, chevrons appear at the ends, and an
overflow button lists the rest, so you do not have to decide what happens at what width.

## The scrollbar

There is one, and every scroller uses it: `ListView`, `Table`, `Tree`, `TextArea` and `ScrollView`
all mount the same `ScrollBar`, so they behave alike without agreeing to. It reads the theme (the thumb is
`textMuted`, which is dark on a light palette and light on a dark one), it can be dragged by the
thumb or paged by clicking the track, and it is thin at rest and widens as the pointer
approaches, so it is thin to look at and thick to grab.

`setPolicy` decides when it shows:

| | |
| --- | --- |
| `HIDDEN` | never |
| `ALWAYS` | always, which is what `TextArea` uses |
| `ON_SCROLL` | while scrolling, then fades |
| `AUTO` | the same, plus while the pointer is over the host — `ListView`, `Table`, `Tree` and `ScrollView` |

By default they are overlays, and in a `Table` or a `Tree` they have no other mode: a strip held
open for a bar that is usually faded out is dead space. `ListView`, `ScrollView` and `TextArea` can
give the bar a strip of its own with `setBarLayout(ScrollGutters.Layout.RESERVED)`, which is what a
list of records usually wants, because the trailing edge of a row is where a count, a date or a
status sits and a thumb over it is a defect. The strip is held for as long as the content
overflows, not for as long as the bar is drawn, so a fading bar never makes the content reflow
under the pointer; pair it with `ALWAYS`, or the bar fades out of a strip that stays empty. A
`HIDDEN` bar reserves nothing.
