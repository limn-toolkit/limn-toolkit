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

What each reader makes of that is its own, and it has been listened to rather than assumed: the
gallery's screens were driven a keystroke at a time under NVDA 2024.4.2 on Windows 11, VoiceOver
on macOS 26, and Orca 50.2 and 46.1 on Fedora 44 and Ubuntu 24.04. The quotations here are what
each reader said, with the application's language pinned to Portuguese for the run.

A cursor move is spoken by all three, and each of them describes a row in its own shape. NVDA
gives the level, the name, the state and the position — "nível 1", "Documents 2", "expandido",
"1 de 5" — and in a grid it adds the column, "coluna 4". Orca gives the name and the level,
"nível de árvore 1", and reads a table row out as its cells in order. VoiceOver gives the row, its
state and what is inside it, "Documents 2, expandido. 2 itens contidos., item de árvore", and in a
table it speaks the cell alone, "Caucasus, célula". A value change is spoken as the new value by
itself: arrowing a date field's year says "2027", and clearing a segment says "vazio", where
moving *onto* that segment says its name, its role and its value — "Dia", "botão de rotação",
"09".

Two things the readers do not agree about, and no toolkit can make them. **A selection change**:
VoiceOver announces one — "Nenhuma linha selecionada" when a row leaves the selection, "5 linhas
selecionadas" on select-all — where NVDA and Orca said nothing at either. **A position**: a node's
position, its level and the size of its set are published on all three platforms, and NVDA speaks
the position without being asked. Orca speaks it only where the user has turned on its own
`speak-position-in-set` option, which ships off in Orca 50.2 — turned on for one run, the same
screens said "1 de 5" and "15 de 30" — and the Ubuntu guest's Orca 46.1 never said it. VoiceOver
speaks neither a position nor a cell's column, and for a branch says how many rows it holds
instead. The toolkit publishes all three everywhere; a screen that only works if the position is
spoken works for one reader's users, so where a row's place in its set carries meaning, put it in
what the row draws as well.

One thing has been driven under no reader yet: a text field. The scripts above move through
lists, tables, trees, a calendar, two date presentations and an announcement, so what a reader says
about a caret move, or when reading a field character by character, is what the platform mappings
promise and not yet what anyone heard.

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
form.add(labelled(new I18nString("account.email", "Email"), email, email));
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
draws or, for rows the adapter has not built, by what `Adapter#rowName` answers.

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
scene where it does not. A window of its own is not a tree of its own as far as the reader is
concerned: the control that opened it points at it, and the cursor crosses into the popup's
items, so arrowing through a combo's list is read item by item without leaving the field.

That crossing was the part of this page most obviously owed a reader, and it now has one. On
Fedora and Ubuntu the demo's date picker publishes a single application with two frames — the
field's panel pointing at the calendar in the popup, the calendar pointing back at the field — and
Orca follows the cursor across and speaks the day standing under it, "9 de setembro de 2026,
hoje." VoiceOver does the same on macOS, speaking "popup, janela, 9 de setembro de 2026, hoje,
célula" for a window that is not the active one and then reading the arrow keys through the grid.
On Windows the field keeps the keyboard and the focused element is the popup's day, exactly as
described: NVDA announces the popup as "popup, janela" and then the calendar and the day the cursor
stands on, "17 de outubro de 2026, item de dados, selecionado", follows the arrow keys through the
grid and the month chooser, and on the way out says the field's group and its day segment again.
(Until 2026-09-22 closing that popup with NVDA attached crashed the demo, a defect of the Windows
bridge's teardown and not of the picker; it is fixed, and that run is the one quoted here.)

Where the platform has no second window the picker draws in the scene instead, and there the
question does not arise: a Wayland session gets the in-scene presentation, and the same run forced
through XWayland gets the real popup window and the crossing above.
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

The demo module carries a gallery of every component labelled the way this page describes,
and a test that holds every entry to four rules in both palettes: no node with an unknown role,
no focusable node without a name, no two nodes with one identity, and no node showing outside
what should clip it. The gallery is also a window you can open and read with a screen reader:

```
./gradlew :limn-demo:accessibilityGallery
```

The gallery also drives itself. `--reader <entry>` opens one entry alone in a window named for
the run and sends that entry's declared steps a few seconds apart, with the clock and the locale
pinned, so the same keystrokes reach the same screen on every machine and a recording of a screen
reader can be read against the step lines it prints:

```
./gradlew :limn-demo:accessibilityGallery --args="--reader tree-loading"
```

The transcript above is the other tool. It is what the demo's `Transcript` writes for a published
tree — one line per node, no rectangles — and the demo keeps four of them as golden files, so a
change that renames a control or drops a state fails a test before it reaches a reader. A
transcript is short enough to read aloud, and reading it aloud is the review that finds what no
rule names: a field named after its own value, a caption that captions nothing.

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
