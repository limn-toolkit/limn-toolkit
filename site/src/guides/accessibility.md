---
title: "Accessibility"
description: "What a screen reader says about a Limn window on Windows, macOS and Linux, the one call that names a control, and how to check a screen before anyone hears it."
---

## What you get without doing anything

A Limn window publishes itself to the platform's own accessibility API: UI Automation on
Windows, NSAccessibility on macOS and AT-SPI2 on Linux. NVDA, VoiceOver and Orca read it the
way they read a native application, and your program contains no platform code for that: the
backend opens the bridge the first time anything asks, and there is nothing to install or
register.

Every widget describes itself. A button is a button, a check box says whether it is checked, a
slider says its value and its range, a text field says what it holds and where the caret is, a
list says its true row count and which rows are selected, a dialog says it is modal and what it
blocks. Focus moves are published as they happen, and inside a list, a table, a tree, a menu or a
date field the *cursor* is published too, so a reader follows the arrow keys and not only the Tab
key.

What each reader makes of that is its own. The three describe the same row in different
shapes and disagree about what to say unprompted; [What readers say](#what-readers-say) lists the
differences worth designing around.

The names are the same `I18nString`s the interface draws, so a screen reader speaks your
application in the language it is displayed in. The word for each *role* — "button", "check
box", "slider" — is the toolkit's own, in every language it ships, because two of the three
platforms will not supply one for a process that is not theirs.

This is the form from the [forms guide](/docs/forms/), and beneath it what a screen reader
is told about it. Both were produced by the same build that rendered this page.

{% shot form "The form, in the light palette." %}

{% transcript form %}

Each line is one node in reading order: its role, its name in quotes and where the name came
from, the states a reader would mention, the value, and the verbs it offers.

## Name every control

A caption above a field is a layout fact. It names nothing, because proximity is an accident
of layout and a tree built from it says confident wrong things. One call makes the caption the
control's name:

{% snippet guide:a11y-labelled %}

```java
form.add(above(new I18nString("account.email", "Email"), email, email));
```

That is the whole idiom, and the form above uses it for every field: a screen reader says
"Email, text field" and not "text field". The caption is an `I18nString`, like every text a
component takes, and the name is read at publish: when the language changes, the caption and
the name it gives change together.

A control with no caption takes a name directly:

```java
Slider volume = new Slider(0, 100);
volume.setAccessibleName(new I18nString("player.volume", "Volume"));

ScrollView chapters = new ScrollView(list);
chapters.setAccessibleName(new I18nString("library.chapters", "Chapters"));
```

An icon-only button that has a tooltip is already named by it. A search field is named by its
placeholder. A dialog is named by its title, a tab by its label, a list row by what the row
draws or, for rows the list has not built, by what `setItemName` answers for the item.

Some things have a name and want a description as well, which a reader speaks after the name
when asked for more: a picture, a 3D viewport, a control whose purpose the name alone does not
carry.

{% snippet guide:a11y-image %}

The message beneath a field is a description too, and it is bound the way a caption is,
because a sentence that merely sits under a field explains nothing to a reader that cannot
see where it sits:

```java
message.setDescriptionFor(email);
```

The form above does that for its email error, which is why the transcript shows the field as
*described by* the message: a reader hears "Email, text field, invalid, enter an address
like…" and not an invalid field with the reason somewhere else on the screen.

A picture that the text beside it already describes is decoration. Mark it ignored, and it
leaves the tree entirely; leave it unmarked and it is published as an image with no name, on
purpose, so that a check like the one below finds it and the decision is yours.

## Dialogs, popups and long lists

A dialog is published as a dialog with its title as its name, and while a modal one is open
nothing behind it is operable: every control the modal blocks is published disabled, and the
reader is told which window is the modal. A native dialog blocks its owner the same way.

A popup — a combo box's list, a context menu, a menu bar's menu — is described where it
actually lives: a translucent window of its own where the platform has one, and inside the
scene where it does not. A combo's list in a window of its own is not a tree of its own as far as
the reader is concerned: it is published under the field that opened it, and the cursor crosses
into its items, so arrowing through the list is read item by item without leaving the field. A
menu is different, because its window takes the keyboard while it is open: it is a tree of its
own, as a native menu is, and the reader follows the focus into it and back out.

A date picker's calendar crosses the same way. It is published under the field, as a combo's
list is, so no second window is announced; a reader follows the cursor through the grid and the
month chooser, and is back on the field's day segment when it closes. The keyboard stays with
the field throughout; a pointer click in the calendar's window makes it the key window, and the
picker hands every key that reaches it on to the field, so the keys act on what was clicked: the
grid, a header arrow or the time row. Closing the popup gives the field's window the keyboard
back. Where the platform has no second window, as in a Wayland session, the picker draws in the
scene instead, and there is nothing to cross.

A list publishes its true row count and the rows it has realized, which is what makes a
million-row list cost what twenty do under a screen reader as well as on screen. The row the
keyboard is in stays realized even when a scroll takes it off screen, so a reader standing on it
is not dropped to the window, and a verb addressed to it is performed where it stands. A list's
rows carry `SELECT`; a tree's and a table's carry their own set by state — a tree's row: select,
add to or remove from the selection, expand or collapse, move the cursor; a table's the same set
without expand or collapse, which only a tree row has — so a reader addresses a particular row
rather than the container.

A control that is merely scrolled out of view keeps everything it offers: the scene reveals it
and performs. A control nobody can see — in an unselected tab, in a collapsed panel, behind a
modal — publishes no verb at all, which is how a reader is told "not now" instead of being told
yes and hearing nothing happen.

## Check a screen before anyone hears it

Four rules hold for any published tree, whatever is in it: no node has an unknown role, no
focusable node is without a name, no two nodes share one identity, and no node shows outside
what should clip it. `limn-test` has them as `AccessibleInvariants.violations(…)`, the same check
the toolkit holds every one of its own components to in both palettes, and the
[Testing guide](/docs/testing/#accessibility) shows the harness that hands a widget's tree to it.

Rules find what can be named. Reading the tree aloud finds the rest: a field named after its own
value, a caption that captions nothing. `AccessibleTrees.describe(…)` writes a tree as text, one
line per node with its role, name and states, which is short enough to read aloud, like the
transcript above.

## What readers say

NVDA on Windows, VoiceOver on macOS and Orca on Linux have each been driven over every
component on this site, a keystroke at a time. They agree on the essentials: a cursor move is
spoken, a value change is spoken as the new value alone, and moving onto a control says its name,
its role and its value. The differences below are the reader's and not the toolkit's. The toolkit
publishes the same facts on all three platforms, so a screen should not depend on any one of them.

- **A row.** NVDA speaks the level, the name, the state and the position, and in a grid the
  column. Orca speaks the name and the level, and reads a table row out as its cells in order.
  VoiceOver speaks the row, its state and how many rows it holds, and in a table the cell and,
  as the cursor moves along a row, its column.
- **A position in a set.** Published everywhere. NVDA speaks it unasked. Orca speaks it only
  when the user has turned on its own position option, which is off by default. VoiceOver never
  speaks it. Where a row's place carries meaning, draw it as well.
- **A selection change.** VoiceOver says a row was added to or removed from the selection and
  how many rows are selected, none and all included. Orca says a row was selected or unselected,
  and that all items were selected. NVDA says a list's row is not selected when it leaves, and
  nothing at a select-all or when Space takes a table's row out from one of its cells, as in
  Windows' own lists and grids. Where the change is the point of the screen, say it in the
  interface.
- **A sort.** NVDA and Orca speak the direction when a header sorts; VoiceOver is given it and
  does not read it out.
- **A busy state.** Published, and spoken by none of them, which is why a tree announces its
  loads in words ([Lists and scrolling](/docs/lists-and-scrolling/) has the three sentences).
- **Something out of reach.** A refused day or an unavailable action is said in each reader's
  own words. What the toolkit guarantees is the stop and the withheld verb; do not copy one
  reader's phrasing into your interface expecting it to match.

Four things follow the platform mappings but have not yet been checked with a reader: moving
the caret in a text field or reading it by character, a Japanese-calendar era segment, a
reader's own verbs on a tree row (expanding, collapsing or selecting one through the
accessibility action rather than the keyboard), and, under Orca on X11, a combo's list or a date
picker's calendar opened in a window of its own.

## Saying something, and popups of your own

`scene.announce(text, Accessible.Politeness.POLITE)` has the reader say a sentence that no focus
move would: a search's result count, a save that finished. `POLITE` waits for the reader to finish
what it is saying, and `ASSERTIVE` interrupts; on macOS both are posted at VoiceOver's high
priority, because at a lower one VoiceOver holds the sentence back behind its own hint. Announce
what changed, once, in the user's language; a sentence repeated on every frame of a change is
noise a reader cannot skip.

A popup of your own that belongs to a control, the way a combo's list belongs to its field, opens
with `scene.pushPopup(layer)` rather than `pushOverlay`: the page beneath it stays enabled for a
reader, as a native drop-down leaves its field enabled, where a dialog disables what is behind it.
A popup of your own drawn in a window of its own calls `scene.graftPopup(popupScene)` before its
scene is bound, with the popup's root naming its opener through `setInheritanceHost`: the reader
then reads it under the control that opened it and never leaves the main window. Graft only a
popup whose keys go on to that control, as the combo's list and the calendar hand theirs; a menu,
whose window takes the keyboard for itself, keeps a tree of its own.

## The edges

Text is read and set whole. A reader can hear a field's contents and its caret, move the caret
and set the text; review by character and braille cursor routing inside a field are not supported,
because no platform is given the rectangle of a range. A list publishes the rows it has realized
rather than all of them, so a reader's own list navigation stops at what is on screen. The system
accessibility settings — high contrast, reduced motion, a system text scale — are not surfaced to
the toolkit; a theme built for high contrast is [yours to make](/docs/theming/), and the toolkit
applies it.

Your application has a name on Linux, where the desktop lists applications and not windows.
`Backend#setApplicationName` sets it; without a call it is the first window's title, and a later
window never renames it.
