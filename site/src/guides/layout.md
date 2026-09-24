---
title: "Layout"
description: "Columns, rows, stacks and the one marker that decides where leftover space goes, with a complete window built out of them."
---

Limn has four layout widgets and one marker. That is the whole vocabulary, and it is enough
to build the window at the bottom of this page.

| | |
| --- | --- |
| `Column` | stacks children top to bottom |
| `Row` | places children left to right |
| `Stack` | draws children on top of each other |
| `Padding` | insets one child |
| `Expanded` | marks the child that absorbs leftover space |

There is no constraint solver to configure and no layout manager to install. Layout runs
top-down: a parent hands each child the space it may occupy, the child answers with the
size it wants, and the parent places it.

## Columns and rows

Both take a **gap** (the gutter between children, applied between them and never before
the first or after the last) and a **cross alignment**, which decides what happens on the
other axis.

{% snippet guide:layout-column %}

`CrossAlignment.STRETCH` is the one you will reach for most in a form: it makes every child
as wide as the column. The default, `START`, leaves each child at its own natural width,
which is what you want for a row of buttons and almost never what you want for a stack of
text fields.

`CrossAlignment.BASELINE` lines up the text instead of the boxes, for a row that mixes control
sizes and carries text; on a `Column` it has no shared baseline to use and is ignored.

For the main axis there is `MainAlignment` (`START`, `CENTER`, `END`, `SPACE_BETWEEN`),
which decides where the children sit when there is more room than they need. `START` and `END`
follow the reading direction; `LEFT` and `RIGHT` stay put in a right-to-left subtree.

## Deciding who gets the leftover space

Wrap a child in `Expanded` and it takes what is left over. Give two children different flex
values and they share it in that proportion. An `Expanded` with nothing in it is a spacer,
and it is the tidiest way to push a group to the far edge:

{% snippet guide:layout-row %}

This is the mechanism behind every "toolbar with actions on the right" and every "sidebar
beside a content pane that grows".

A share can be bounded. `Expanded.of(child).atLeast(120)` is never given less than 120 points
along the row or column, and `atMost(320)` never more: the row freezes a child at its bound and
shares what is left among the others, floors first. When every flexible child is held at its
ceiling, what remains is placed by the row's main alignment, as if none of them were flexible.

## Overlays

`Stack` draws its children in order against one shared box, which is what an overlay is:
the content, and then the thing on top of it.

{% snippet guide:layout-stack %}

Every child takes the stack's alignment, so to nudge one off a corner, wrap it in a
`Padding` rather than looking for a per-child offset.

## A grid, without a grid widget

There is no grid layout, and there does not need to be one: a grid is rows of `Expanded`
children, and writing it yourself takes ten lines and leaves you in charge of what a short
last line does.

{% snippet guide:layout-grid %}

Each tile on a line gets flex 1, so the line splits its width evenly however many tiles
there are. The second loop is the part worth copying: padding the last line with spacers is
what stops two leftover tiles from stretching to half the pane each.

## Putting it together

Here is a real window: a toolbar that keeps its own height, a pair of panes that take
everything left over, and a status line at the bottom.

{% snippet guide:layout-shell %}

Note what is *not* there. No sizes, no percentages, no anchors. The middle child is the only
`Expanded` one, and that single fact is what routes the window's spare height into the
panes and nowhere else: resize the window and the toolbar and status line stay put while
the panes grow.

{% shot layout "The window that code produces, rendered by Limn." %}

Everything on that screen is on this page. The tiles are the grid above; each one is a
column of a stacked swatch-and-badge and two labels; the header row and the status line are
both `spread`. The sidebar is a `ListView` and the divider is a `SplitPane`, both of which
are covered in [Lists and scrolling](/docs/lists-and-scrolling/).

:::tip[Why the last row's tiles are not wider]
Look at the bottom line: two tiles, each the same width as the tiles above them, with the
third slot empty. That is the spacer loop doing its job. Delete those two lines and the
same picture puts two half-pane-wide tiles at the bottom.
:::

## Sizing a widget explicitly

Every widget takes a minimum and a maximum on each axis, and keeps its natural size within
them: a paragraph that must not run the width of a wide window, a button that should not be
narrower than its neighbours.

```java
new Label(terms).setWrap(true).setMaxWidth(560);
new Button("Save").setMinWidth(96);
```

**The parent wins.** A bound narrows what the parent allows and never overrides it. It acts
where the parent leaves the size to the child: across a column (whose children start at their
own width), along a row for a child that is not flexible, inside a stack. Where the parent fixes
the size, the bound has no say: the scene's root is the window's size, a `STRETCH` column gives
every child its width, and a flexible child's share is the row's to decide, which is what
`atLeast` and `atMost` on `Expanded` bound instead. To centre a form of at most 560 points in a
wider window, put it in a `Stack` aligned to the centre.

When a widget genuinely has a fixed size (a thumbnail, a fixed-width sidebar), wrap it:

```java
new SizedBox(280, SizedBox.UNSET, textArea)
```

`UNSET` on an axis means "leave that one alone". Use it sparingly: a layout built out of
fixed sizes is a layout that breaks at the first long translation, where a maximum lets the
text reflow.

## The window's size

The scene gives its root exactly the window's size, whatever the content asks for: nothing in the
content holds the window open. A window made smaller than its content keeps the part that fits
and cuts off the rest, and only a `ScrollView` scrolls to it. So where a layout stops being usable
below some size, say so on the window, where it is made:

```java
backend.createWindow(WindowConfig.of("Invoices", 900, 640)
        .minSize(640, 420)
        .maxSize(1600, 1200));
```

The limits bound what the user can do with the window's edges; `window.setSizeLimits(…)` changes
them later, and `window.sizeLimits()` reads them. A size set from code is not clamped.

To size a window to its content instead of guessing a number, bind the scene and call
`scene.pack()`, before showing the window if it was made hidden. It measures the root with nothing
bounding it, settles the width first and then measures the height at that width, so a paragraph
that wraps gets its lines, and keeps the result within the window's limits and its display's work
area. A dialog or a form packs well. Content that fills whatever it is given does not ask for
much, so a list or a table packs to little more than its header, and text that wraps asks for its
whole length on one line, which the work area then caps. Bounds on the content are what a packed
window follows: a paragraph with `setMaxWidth(560)` packs to that width, and a list in an
`Expanded` with `atLeast(240)` packs to that height.

## Common shapes

**A centred card.** A `Row` with `MainAlignment.CENTER` and `CrossAlignment.CENTER`, one
child, and a `SizedBox` to cap its width.

**A two-column form.** A `Column` with `STRETCH`, where each row is a `Row` of a fixed-width
`Label` and an `Expanded` field.

**A footer pinned to the bottom.** A `Column` whose middle child is `Expanded`, the same
shape as the window above.
