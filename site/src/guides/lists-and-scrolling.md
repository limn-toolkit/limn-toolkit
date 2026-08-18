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
list.onSelect(index -> detail.show(people.get(index)));
list.onActivate(index -> open(people.get(index)));
```

`rowAt` is called on demand and may be called again for the same row after it has scrolled
out and back. Build the widget there; do not cache one per data item, or you have rebuilt
the thing `ListView` exists to avoid.

`onSelect` fires whenever the selection moves: a click, or the arrow keys. `onActivate` is
the *open this* gesture, which is Enter on the selected row; call `activate()` yourself to
fire it from anywhere else.

:::tip[When the data changes]
Call `refresh()` after your backing list changes. The rows are rebuilt from the adapter,
and the scroll position and selection are kept, clamped if the list got shorter, and your
listener hears about it, so a detail pane bound to the selection never keeps showing a
record that is gone.
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
- Setting the selection fires the same listener a click fires. A listener describes the
  selection, not the mouse, so anything bound to it stays right without needing to know
  where the change came from.
- Setting the index that is already selected does nothing and fires nothing, which is what
  lets two controls bound to each other settle instead of bouncing.

Arrow keys are not bound by the first rule: arrowing past either end lands on the end,
because that is what the key means.

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

There is one, and every scroller uses it: `ListView`, `TextArea` and `ScrollView` all mount the
same `ScrollBar`, so they behave alike without agreeing to. It reads the theme (the thumb is
`textMuted`, which is dark on a light palette and light on a dark one), it can be dragged by the
thumb or paged by clicking the track, and it is thin at rest and widens as the pointer
approaches, so it is thin to look at and thick to grab.

`setPolicy` decides when it shows:

| | |
| --- | --- |
| `HIDDEN` | never |
| `ALWAYS` | always, which is what `TextArea` uses |
| `ON_SCROLL` | while scrolling, then fades |
| `AUTO` | the same, plus while the pointer is over the host — `ListView` and `ScrollView` |

They are overlays. A bar that reserved a lane would take width from the content and give it back
when it hid, and a list that reflows because you scrolled it is a list nobody trusts.
