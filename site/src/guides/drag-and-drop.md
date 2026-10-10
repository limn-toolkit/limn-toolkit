---
title: "Drag and drop, and the clipboard"
description: "Rows that rearrange, two lists that trade rows, a picture dragged to another program, files dropped on your window, and a copy that carries more than text."
---

A drag and a copy are the same thing seen twice: something leaves a widget in several forms at
once, and whoever takes it picks the form it understands. Limn has one type for that, a
`Transfer`, and it is what a drag carries and what the clipboard holds. This page starts with
the widgets that drag without any of it, since most screens need nothing else.

## A list the user rearranges

Turn dragging on and say how a move is made:

{% snippet guide:drag-reorder %}

That is the whole of it. The user presses a row and drags: the row lifts out of the list and
follows the pointer, the rows around open room where it would land, and a line marks the place.
The list scrolls when the pointer rests near its edge, Escape puts everything back, and with
several rows selected they all go together. The same handler serves the keyboard: Alt+Shift+Up
and Alt+Shift+Down move the selected rows one place.

Your handler moves the items in your own list and nothing more; the list then shows them, keeps
them selected and tells a screen reader where they went. The index you are given is counted
among the rows that stay, so "remove them, then insert at the index" is right wherever they came
from.

A row's own controls keep their gestures: a button in a row is still pressed, a field still
selects its text.

## Two lists that trade rows

The picker every business application has, the columns available on one side and the ones shown
on the other, is two such lists that also let rows leave and arrive:

{% shot picker "Two lists over two lists of the application's own. Rows are dragged within each and from one to the other." %}

{% snippet guide:drag-picker %}

`onMoveOut` is told that rows dragged out of this list were moved somewhere else, and removes
them. Setting it changes what a drag proposes: a list without it offers a copy of its rows, and
one with it offers a move, unless the user holds the key that asks for a copy (Ctrl on Windows
and Linux, Option on macOS).

`setDropFormats` names what the list takes and `onDrop` takes it, with the place between two
rows it was dropped at. `ListView.ITEMS` is the rows themselves, the very objects, and it only
ever travels inside your program. `event.action()` says whether the user asked for a copy or a
move, which matters when a copy has to be a new object and not the same one twice.

A list can take what is not a row at all. Name `DataFormat.TEXT` among the formats and a
paragraph dragged out of an editor arrives as text, for you to make rows of; name
`DataFormat.FILES` and it takes files.

What the rows carry to *another program* is their text, one line a row. To send something
else, a file for each row, a picture, a format of your own, give the list a function with
`setDragContent`.

## An outline that rearranges

A `Tree` does the same with one more dimension:

{% snippet guide:drag-tree %}

Dropped on a node, what is dragged goes into it; near the node's upper or lower edge, before or
after it; and never into itself. A closed folder the pointer rests on opens after a moment, so
a drop can go as deep as it needs to. The handler is given the new parent, `null` for the top
level, and the place among its children.

By keyboard, Alt+Shift+Up and Down move the selected node among its siblings, Alt+Shift+Left
takes it out one level, and Alt+Shift+Right puts it into the sibling before it. `onMoveOut`,
`onDrop`, `setDropFormats` and `setDragContent` are the list's, with the parent added.

## A picture that leaves its view

{% snippet guide:drag-image %}

An `ImageView` with dragging on offers its picture, with its own transparency, to whatever will
take one: a drop area of your program, an image editor, a document. What follows the pointer is
the picture as the view shows it.

Some targets take a picture only as a **file**: the desktop and a folder in a file manager are
the common ones. `setDragFileName` offers the picture that way too, as a PNG written to the
temporary directory and deleted when your program ends. It is off unless you ask, because it
writes to disk: on Windows and Linux the file is written when a target asks for it, and on
macOS, where the system asks as the drag begins, at every drag.

The view offers a copy and allows a move. It keeps its picture whatever the target says it
did; the move is allowed because some programs on Windows take a picture or a file only from a
source that allows one. If you build such a drag yourself, allow both.

## Columns the user arranges

A table's columns are moved by dragging a header, or with Alt+Shift+Left and Right, which move
the column the keyboard is on. Nothing turns that on, and
[Tables](/docs/lists-and-scrolling/#columns-the-user-arranges) has what there is to say about
saving the layout and keeping the first columns in place.

## By keyboard, and what a reader hears

Every drag on this page can be made without a pointer. Ctrl+Shift+D (Cmd+Shift+D on macOS)
picks up what the focused widget would drag; Tab and Shift+Tab walk the widgets that would take
it, and only those; the arrow keys choose the place inside one; Enter or Space drops, and
Escape calls it off. `scene.setDragKey` changes the key, for an application that already uses
that one.

A screen reader is told each step, in the language in effect: what was picked up and where it
stands, each place as the arrows move, and where it went. None of it is yours to write for the
ready-made widgets. For a widget of your own it is two calls, both below.

Alt+Shift with an arrow is the shorter road for the common case, a row moved within its own
list, and needs no picking up.

## Your own widget as a source

A widget is asked `onDragStart` when a press on it has travelled a few points, or when the drag
key is pressed while it has the focus. Answering a `Drag` starts one; answering `null` leaves
the gesture to the ordinary mouse events.

{% snippet guide:drag-format %}

{% snippet guide:drag-source %}

A `Transfer` holds the same thing in several formats, and a good source offers more than one:
its own for the widgets that know what an order is, and text for everything else, so the same
card dropped on an editor becomes a line of text.

- `DataFormat.TEXT`, `HTML`, `IMAGE` and `FILES` are understood by other programs.
- `DataFormat.inProcess(name, type)` carries the object itself and never leaves your program.
- `DataFormat.of(name, encode, decode)` names a format of your own and says how it becomes
  bytes, so that two copies of your application, or two of your applications, can exchange it.

`allow` says what a target may do with it, the action you would rather have first; a drag that
says nothing allows a copy. `describedAs` is what a screen reader says was picked up. `onEnd`
runs once, with what a target did, or `null` when the drag was cancelled or nothing took it:
remove the original there when the answer is a move, and nowhere else.

What follows the pointer is the widget as it is painted. When that is wrong, a row of a long
list, a card that should keep its rounded corners over another program, `preview(x, y, width,
height)` shows a part of the widget and `preview(width, height, holdX, holdY, painter)` has you
paint the dragged thing alone on a transparent surface. `withinWindow()` keeps a drag from
ever being offered to another program, which is right for something that means nothing outside
its window.

## Your own widget as a target

{% snippet guide:drag-target %}

`onDragOver` is asked again on every move, so the answer can depend on the place: accept over
the upper half and not the lower, if that is what the widget means. A widget that says nothing
passes the question to its parent, which is how a whole panel takes a drop that none of its
children wants. Mark where the drop would land, and unmark it in both `onDragExit` and `onDrop`,
since no exit follows a drop.

`event.accept()` takes what the user is asking for; `event.accept(DropAction.COPY)` insists on
one action among those the source allows, for a target that only ever copies.

Reading is asynchronous, because what is dragged may belong to another program that has not
produced it yet: `read` answers a `CompletionStage` that completes on the UI thread.
`event.reject()` refuses a drop after all, and the source is then told nothing was done.

In a keyboard drag there is no pointer. `event.isKeyboard()` is true, each arrow key arrives as
`event.stepX()` or `stepY()` for the widget to move a place of its own by, and
`event.describePlace("Before Order 42")` is what a screen reader says of it.

## Files dropped on your window

Files dragged from the desktop or a file manager are a drop like any other, carrying
`DataFormat.FILES`:

{% snippet guide:drag-files %}

## The clipboard

`scene.clipboard()`, or `clipboard()` inside a widget, is the system's clipboard. Text has two
direct calls, `text()` and `setText(…)`, which is all a text control uses. Anything richer is a
`Transfer` going in:

{% snippet guide:clipboard-copy %}

and an offer coming out, which lists its formats at once, so a menu can grey its Paste item
without waiting, and reads a value when asked:

{% snippet guide:clipboard-paste %}

A value that is expensive to make can wait until somebody wants it:

{% snippet guide:clipboard-lazy %}

`onChange` hears the clipboard change, whoever changed it, where the system tells.

All of it reaches other programs, and comes from them, on Windows, macOS, X11 and Wayland: a
picture from a screenshot tool, files copied in a file manager, a table pasted into a
spreadsheet as HTML. On Windows and macOS what you copied stays on the clipboard after your
application closes; on Linux a clipboard lives in the program that owns it, and what survives
depends on the desktop's clipboard manager.

## The edges

- **Linking** is not offered under Wayland, whose protocol has copy and move only.
- **On X11 the pointer does not change shape** to show whether a target would take the drop. The
  drop itself is answered correctly.
- **At a fractional display scale under Wayland**, the picture that follows the pointer is
  rounded to a whole scale and may look slightly soft.
- **Several files copied at once on macOS** have been seen to arrive in another program as the
  first one only.
- **A list or a tree as a target takes the action the source proposes.** It can read which one
  it was and refuse the drop, and a widget of your own can insist on one with
  `accept(DropAction)`.
- **In a test**, the headless window has a clipboard of its own and a drag stays inside its
  scene, so a press, a few moves and a release from the [test driver](/docs/testing/) exercise
  both without a display.
