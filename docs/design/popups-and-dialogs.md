# Popups and dialogs

Background for `Dialog`, `PopupMenu` and `ComboBox`: the three components that can
present themselves as a real OS window instead of as content inside one.

## Why an in-scene dialog is sometimes promoted to a window

Two floating windows stack, so a lower dialog stays on screen, dimmed and frozen,
underneath a newer one. An in-scene overlay cannot do that: it is drawn inside its host
window, so putting it in front means bringing that window forward, and the window
swallows any dialog that was floating over it. To the person using it, that looks
exactly like the first dialog being destroyed.

So an `IN_SCENE` dialog raised over a window a modal has *already* locked is presented
as a native window instead, with a warning naming the dialog. The application walks into
this by asking about unsaved work on the way out while a picker is open, not an exotic
case. `keepInScene()` declines the promotion, for an application that has looked at the
stacking or whose reason for the overlay was never the look but not spending an OS
window at all (embedded, kiosk).

## What an in-scene modal is allowed to lock

An overlay blocks the scene it is drawn in, which is only ever one window. Everything
else the application has open (other windows, dropdowns, menus) is outside the scene
and would stay live, so the backend registers a *scene modal* alongside the overlay to
lock the rest the way a native modal does. That modal names the host window as an
exception, because the host is the surface the dialog is answered on and locking it
would leave the dialog unreachable.

The exception has to cover **what the host owns**, not just the host. A dropdown or a
menu opened from the dialog's own content is a separate top-level window belonging to
the host, the card reaching outside its window to draw a list that must be allowed to
overflow the frame. Lock those and a `ComboBox` inside an in-scene dialog is dead: the
popup window is created, is never shown because it is blocked, and the control reports
itself open the whole time, so the failure looks like a click that did nothing.

Two consequences worth knowing before touching this:

- The exception is deliberately **not** applied to the anti-deadlock rule that keeps the
  top modal's surface interactive. A native dialog registers its window with its owner
  so it closes with it, so "owned by the host" includes dialog *windows*; widening that
  rule would leave a stale dialog answerable in front of the new one. Modals below the
  top are frozen before the exception is ever consulted, which is what keeps the two
  apart.
- For the same reason, the exception cannot tell a dropdown from a non-modal dialog: both
  arrive through the one "close with the owner" registration. A palette opened with
  `showNonModal` therefore stays clickable while an in-scene modal is up over the window
  that owns it, where a native modal would have frozen it. Separating the two would take
  a registration that distinguishes a transient popup from a window of its own.

## Why a native dialog re-measures after presentation

A window sized once at presentation is a card that can only ever hold what it opened
with. Content that changes size afterwards is ordinary: a colour picker's CMYK tab has
four channel rows where RGB has three, and that fourth row fell outside the window. The
in-scene presentation never had the bug, because its overlay re-measures the card on
every layout pass; the native path had to catch up.

Three details in `refitNativeWindow` are load-bearing and easy to undo by accident:

- It measures against the **loose** constraint presentation used, not the one arriving.
  A bound scene lays its root out tight to the window, so the incoming constraint is the
  window's own size and can never report that the content wants more.
- The resize is **posted**, because the method runs inside a layout pass and resizing
  re-enters layout. The size comparison is what stops the loop: after the resize the
  content measures to the size the window now has, and nothing is posted.
- The dialog keeps **its own** centre rather than being re-centred on the owner. The
  card can be dragged, and one that jumped back to the middle of the screen because a
  tab was clicked would be worse than one that clipped.

## What bounds a dialog, and why the buttons are outside the scroll

A card sized purely to its content is a card that can put its own buttons behind the dock. So
a dialog is measured against a bound: the **work area** of the display it opens on when it is a
native window, and the **host window** when it is an in-scene overlay, each inset by one
`spacingLarge` on every side. The two answers differ because the two things differ: a native
dialog is a window of its own and is allowed to be larger than the one that opened it, while an
overlay cannot leave its host by construction.

The bound is unbounded when the owner reports no display, which is every headless and embedded
backend. Substituting the owner window's own size there looks conservative and is not: it would
cap a dialog at a window it is expressly allowed to exceed, on exactly the backends with no way
to say otherwise.

Two things about the card's own structure follow from this and are easy to undo:

- **The body scrolls; the action row does not.** They are separate children of a purpose-built
  container rather than one column, because a card capped at the work area with its footer
  inside the viewport is a card whose only way out can be scrolled off the bottom.
- **That container cannot be a `Column`.** A `Flex` measures a non-flex child against an
  unbounded main axis, so a `ScrollView` inside one always answers with its content's full
  height and never learns a budget exists; give it a flex factor instead and it takes every
  point going, so a two-line dialog stands as tall as the screen. The container is the one place
  holding both numbers (what the body wants, and what the card may have), so it is the only one
  that can hand the body a height. It measures the **body**, not the scroll view wrapping it: a
  scroll view offered a bounded height answers with the height it was offered.

What this does **not** do is clamp a dragged card's position. The size is bounded; a card dragged
towards an edge can still be carried past it.

## Why a popup menu is a native window, and when it is not

A menu that cannot leave its owner window is not a menu: it has to overflow, flip and
clamp against the monitor's work area like a real OS menu. So the whole cascade lives in
one undecorated, transparent native window, sized to the open cascade's bounding box and
re-fit as submenus open and close.

Two platforms cannot hold one. macOS exclusive fullscreen has no room for a second
window: taking focus minimizes the fullscreen owner. And a window reporting no
`supportsAbsolutePositioning()` cannot say *where* the second window goes, so the menu
would open near the middle of the display wearing whatever frame the compositor puts on
toplevels; that is Wayland, where absolute position is absent from the protocol rather
than missing from an implementation. Both fall back to an in-scene overlay, with the same
cascade, flip, clamp and navigation. A platform fallback, not a public mode: the API stays
native and the application chooses nothing.

## What the in-scene fallback costs, and why it is not a second implementation

The overlay is clamped to the **owner window** where the native presentation clamps to the
**display work area**. A dropdown near the bottom of a small window therefore shows fewer
rows and starts scrolling sooner than the same dropdown on a platform that can place a
window. That is the whole of the difference, it is the platform's, and it is the reason
this is a fallback rather than the default everywhere.

What it is not is a second list. `ComboBox` puts the very same `PopupPanel` in both (a
window's scene root in one, an overlay's child in the other) and `PopupMenu` does the
same with its `MenuSurface`. Rows, keys, type-ahead, scrolling and the size step are one
implementation with two mountings, because two would drift and only one of them would be
looked at.

Three things the OS provides for free that the overlay has to arrange for itself, each of
which breaks the control in a way that reads as "the click did nothing":

- **The keyboard.** `pushOverlay` confines focus to the overlay, so the field underneath
  can no longer receive a key. The overlay is focusable and hands what it receives back to
  the combo, where the key and type-ahead behaviour lives.
- **Focus loss is not dismissal.** `ComboBox.onFocusLost` closes the popup, which is right
  when a popup *window* never takes focus and wrong the moment the overlay takes it: the
  list would close in the pass that opened it. The close is guarded on there being no
  in-scene popup.
- **The outside-press observer must not be registered.** The native path dismisses on any
  press whose target is not the combo, which is safe only because its list is in another
  scene. An overlay captures every press in this one, its own rows included, so that
  observer would fire on the press that was choosing an item. The overlay dismisses on a
  press that missed the list instead.

The panel also **paints opaque in the scene** where the window paints at 0.94 alpha. That
translucency composites over the desktop and reads as frosted glass; over the owner's own
content it reads as a list you can see the page through.

## One size step for the whole cascade

The step is resolved once when the menu opens, from an explicit `setControlSize`, else
through the anchor widget, else from the owner scene. Mixing steps across columns would
desynchronise three things at once: the submenu's y-alignment against its parent row,
the column overlap that hides the seam between two borders, and the shared border
weight.
