---
title: "Layout"
description: "Columns, rows, wraps, grids, stacks and the one marker that decides where leftover space goes, with a complete window built out of them."
---

Limn has six layout widgets and one marker. That is the whole vocabulary, and it is enough
to build the window at the bottom of this page.

| | |
| --- | --- |
| `Column` | stacks children top to bottom |
| `Row` | places children left to right |
| `Wrap` | places children left to right, and starts a new line when the width runs out |
| `Grid` | places children in columns, row by row, so that they line up both ways |
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

## Lines that wrap

A row that holds more than fits runs off its edge. When the number or the length of the
children is not yours to decide (tags, filters, the chips a user adds, buttons whose labels
are translated), use a `Wrap`: it places children along a line exactly as a row would, and
when the next child does not fit in what is left of the width, it starts a new line below.

{% snippet guide:layout-wrap %}

`gap` is the gutter between neighbours on a line, the same one a row has, and `runGap` the
space between lines. `mainAlignment` places each line on its own, so `END` packs every line
to the trailing edge and `SPACE_BETWEEN` spreads every line, the last one included; in a
right-to-left subtree each line starts at the right edge. `crossAlignment` places a child
within the height of its line, which is the height of the line's tallest child.

A wrap is as wide as its longest line and as tall as its lines. With nothing bounding the
width, which is what `scene.pack()` measures with, everything is one line, so to pack a window
around a wrap of a few lines, give the wrap a maximum width. A child wider than the wrap gets
a line of its own, at the wrap's width. There is no leftover space on a line to share, so
`Expanded` has no meaning in a wrap and is refused when added.

## Rows that share their columns

When children should line up both ways, a caption beside the field it names or a name beside
its value, use a `Grid`. You give it a number of columns, and the children fill it row by row
in the order they are added: in a grid of two columns, the first two children are the first
row, the next two the second.

{% snippet guide:layout-grid %}

Each column is as wide as its widest cell, and each row as tall as its tallest. `gap` is the
gutter between columns, the one a row has between neighbours, and `rowGap` the space between
rows. `expandedColumn` names the one column that takes whatever width the others leave, the
way `Expanded` does in a row, so the fields grow with the window and the captions keep their
width; when the window is too narrow for both, that column gives way first. `columnAlignment`
places the cells within a column: `START` by default, `END` for captions set against their
fields, `STRETCH` for fields that should all be as wide as the column. Within a row, the cells
line up on the baseline of their text unless you say otherwise, so a caption sits on the line
its field's text sits on; `crossAlignment` changes that with the words a row uses.

That is everything a grid does, on purpose. A cell cannot span two columns or be placed out
of order: an empty cell is a `SizedBox(0, 0)`, and a column of a fixed width is a `SizedBox`
around one of its cells. A hidden child keeps its cell, so hiding a caption does not move
every field after it into the wrong column, and a row whose cells are all hidden takes no
room. A screen reader and the Tab key go through the cells in the order they were added,
which is the order the rows are read in, and in a right-to-left window the first column is
the one on the right.

## Overlays

`Stack` draws its children in order against one shared box, which is what an overlay is:
the content, and then the thing on top of it.

{% snippet guide:layout-stack %}

Every child takes the stack's alignment, so to nudge one off a corner, wrap it in a
`Padding` rather than looking for a per-child offset.

## Tiles of equal width

A `Grid` makes each column as wide as its widest cell, which is right for a form and wrong for
tiles, which should split the width evenly whatever is in them. That is rows of `Expanded`
children, and writing it yourself takes ten lines and leaves you in charge of what a short
last line does. (When the tiles should keep their own width and flow onto as many lines as
the window allows, rather than fill a fixed number of columns, that is a `Wrap`.)

{% snippet guide:layout-tiles %}

Each tile on a line gets flex 1, so the line splits its width evenly however many tiles
there are. The second loop is the part worth copying: padding the last line with spacers is
what stops two leftover tiles from stretching to half the pane each.

## Cards

A `Card` holds one record: a customer, an invoice, a report to open. It is made of parts, and
every part is optional: a picture across the top (the *media*), a header of a title over a
subtitle with a part before them and a part after them, the content, and a footer of actions
at the trailing edge. A part that is not set, or is hidden, takes no room and leaves no gap.

{% snippet guide:card %}

The card pads its parts by the step's own padding and puts the step's gap between them, so a
card at a smaller control size is a smaller card. Given more height than it needs, as cards
side by side in a row with `CrossAlignment.STRETCH` are, it gives the extra to its content and
keeps its footer at the bottom, so the footers of a row of cards are on one line.

- **Media** is laid out at its own size (`MediaFit.NATURAL`, the default), stretched across the
  card inside its padding (`STRETCH`), or out to the card's edges with the card's own corners
  (`COVER`); `setMediaAspectRatio` says how tall a stretched picture is, 16 to 9 unless told. An
  `Icon` given as media is drawn by the card, on a tint of the accent when it stretches.
- **`Orientation.HORIZONTAL`** puts the media in a column at the leading side, beside the header
  and the content, with the footer across the whole card under both.
- **`Style.ELEVATED`** lifts the card by the theme's raised shadow (with a hairline round it in a
  dark palette, where a shadow does not show), `OUTLINED` draws a line round it, `FILLED` sets
  it apart from the page by tone alone, and `PLAIN` draws nothing of its own: its parts stand on
  the page, padded and laid out as on any card, and a plain card that is clicked still shows its
  hover, its press and its focus ring.

The title and the subtitle take one line each and end with an ellipsis where they do not fit.
`setTitleLines` and `setSubtitleLines` let them break across more lines, the ellipsis then at
the end of the last, so a long name reads whole on a narrow card; a screen reader hears the
whole title either way.

{% snippet guide:card-lines %}

A text content is a `Label` you make, and takes its own limit with `setMaxLines`: one line is
what a label does unless told, and `setWrap(true)` with no limit still takes as many lines as
the text needs.

A card can also be clicked. With `onActivate`, a click anywhere above its footer opens what the
card stands for, and so do Enter and Space while it holds the keyboard; the footer's actions are
clicked as the buttons they are, and a press between them does nothing. A card with no footer
is one click as a whole.

{% snippet guide:card-clicked %}

A card that is clicked holds no control above its footer: a button, a field or a check box put
in its media, its header or its content is refused with an `IllegalArgumentException`, since a
control inside a control is two clicks in one place, and one that arrives later is logged and
answers nothing. Actions go in the footer. A screen reader hears a clicked card as a button
named by its title and described by its subtitle, and nothing else inside it, so whatever else
the card shows that a listener needs, the amount and the date in that invoice, is said with
`setAccessibleDescription` on the card; [Accessibility](/docs/accessibility/) has the rest.

## Putting it together

Here is a real window: a toolbar that keeps its own height, a pair of panes that take
everything left over, and a status line at the bottom.

{% snippet guide:layout-shell %}

Note what is *not* there. No sizes, no percentages, no anchors. The middle child is the only
`Expanded` one, and that single fact is what routes the window's spare height into the
panes and nowhere else: resize the window and the toolbar and status line stay put while
the panes grow.

{% shot layout "The window that code produces, rendered by Limn." %}

Everything on that screen is on this page. The tiles are the rows of `Expanded` above; each
one is a column of a stacked swatch-and-badge and two labels; the filters under the header
are the `tags` wrap; the form under the collections is the `details` grid; the header row and
the status line are both `spread`. The collections are a `ListView` and the divider is a
`SplitPane`, both of which are covered in [Lists and scrolling](/docs/lists-and-scrolling/).

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

**A two-column form.** A `Grid` of two columns, the second one expanded and stretched, the
way `details` builds it: the captions take the width of the longest one, and no width is
guessed that a translation could outgrow.

**A footer pinned to the bottom.** A `Column` whose middle child is `Expanded`, the same
shape as the window above.
