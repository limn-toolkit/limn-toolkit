---
title: "Menus and dialogs"
description: "Menu bars, context menus, dialogs and the native file chooser: the parts of a desktop application that leave the window."
---

## Menus

A `Menu` is a model, not a widget: items, checkable items, submenus and separators. Build
one and then decide how it is presented.

```java
Menu file = new Menu();
file.addItem("New", this::newDocument);
file.addItem("Open…", this::open);
file.addSeparator();
file.addCheck("Word wrap", true, editor::setWrap);
file.addSubmenu("Recent", recentMenu);
```

**As a menu bar**, drawn at the top of your window like any other widget:

```java
MenuBar bar = new MenuBar();
bar.addMenu("File", file);
```

Arrow keys move between menus, Down or Enter opens one.

**As a dropdown**, with `PopupMenu`:

```java
PopupMenu popup = new PopupMenu(menu);
popup.showAnchored(button, 0, 0, button.width(), button.height());
```

A popup is a real window, so it can extend past the edge of yours the way an operating
system menu does. It flips and clamps against the work area of whichever monitor the
pointer is on, so a menu near the bottom of the screen opens upward instead of being cut
off, so you do not position it yourself. While it is open it takes keyboard focus: arrows
navigate, Enter picks, Escape closes, and clicking back on your window dismisses it.

## Keyboard shortcuts

An item can carry the shortcut that runs it, and the shortcut then works from anywhere in
the window, not only while the menu is open:

```java
MenuItem.of("Save", this::save).setAccelerator(Accelerator.command(Keys.S));
MenuItem.of("Save As…", this::saveAs)
        .setAccelerator(Accelerator.command(Keys.S, Keys.MOD_SHIFT));
MenuItem.of("Refresh", this::refresh).setAccelerator(Accelerator.of(Keys.F5));
```

`Accelerator.command` is the one to reach for: it means the Command key on macOS and
Control everywhere else, which is the difference between a shortcut that feels native on
both and one that fights the platform's own bindings. The menu draws it the way that
platform writes it (`⇧⌘S` on a Mac, `Ctrl+Shift+S` elsewhere), so you write the shortcut
once and it reads correctly in both places.

The modifiers have to match exactly, so `Ctrl+S` and `Ctrl+Shift+S` can be two different
commands. A disabled item still shows its shortcut and refuses to run it: a hint that
disappears whenever the command is unavailable reads as a shortcut that does not exist.

## Context menus

Give any widget a menu on right-click by wrapping it:

```java
column.add(ContextMenus.attach(fileList, () -> new Menu()
        .addItem("Rename…", this::rename)
        .addItem("Delete", this::delete)));
```

`attach` returns the widget to put in your tree; the wrapper is invisible and changes
nothing about how the content measures or paints. The menu is built on demand, so it can
reflect whatever is selected at the moment it opens.

Asking for a context menu is more than the right button: a keyboard user asks with the Menu
key or Shift+F10 and never touches the mouse. Going through `ContextMenus` gets both,
which a hand-rolled right-click check does not.

Text fields and text areas already have one, with cut, copy, paste and select-all in the
reader's own language. You get it by using the widget; there is nothing to switch on.

## Dialogs

A `Dialog` is a title, a message, some buttons, and optionally a widget of your own. It
resolves as a `CompletionStage` rather than blocking:

```java
new Dialog("Discard changes?", "Your edits will be lost.")
        .addButton("Cancel", "cancel")
        .addPrimaryButton("Discard", "discard")
        .setCancelResult("cancel")
        .show(scene)
        .thenAccept(result -> {
            if ("discard".equals(result)) {
                document.revert();
            }
        });
```

The string you pass with each button is what comes back, so there is no result enum to
define. `setCancelResult` is what Escape and the close button resolve to.

`setContent(widget)` replaces the message with anything you like: a form, a list, a
picture. The buttons stay.

### Which kind of modal

| Call | Blocks |
| --- | --- |
| `show(owner)` | the owning window |
| `showToolkitModal(owner)` | every window in the application |
| `showNonModal(owner)` | nothing, so it is a floating panel |

The other direction matters too: a non-modal panel is a window its owner opened, so a
modal freezes it along with the rest of what that owner owns. What stays live under a
modal is the other kind of popup: a dropdown or a menu is a window's own content reaching
outside its frame, not a window in its own right, which is why a combo box inside the
dialog still opens. Do not design a floating palette around staying usable while a modal
is up; it will not be.

By default a dialog is its own small window. `setDisplayMode(Dialog.DisplayMode.IN_SCENE)`
draws it inside the owner window instead, as a scrim and a card, which is what you want on
a machine where an extra window would be intrusive, and what you want if you are drawing
something that must stay inside your own frame.

In-scene is a preference, not a guarantee. A dialog asked to draw inside a window that a
modal has already locked opens as a native window instead, because an overlay can only come
forward by raising its host, which would hide the dialog already floating there.
`displayMode()` answers how the dialog was actually presented, which is worth asking
before styling or positioning the card on the assumption that it is an overlay;
`keepInScene()` insists on the overlay and accepts the stacking that comes with it.

Either way, clicking outside a modal dialog is ignored and beeps, exactly as the platform
does. `setDismissOnScrim(true)` turns the in-scene form into a light dismissable overlay
instead.

## Native file dialogs

`Backend.fileDialogs()` gives you the platform's own chooser: the real one, not a
reimplementation:

```java
Optional<Path> chosen = backend.fileDialogs().openFile(
        "Open image", lastFolder, FileDialogs.Filter.of("Images", "*.png", "*.jpg"));
```

These block the UI thread while they are open, which is what every native application does
and what users expect. On a headless machine there is no chooser, and every call resolves
empty, exactly as a cancel does, so your code does not need a special case.

## Files dropped onto your window

Files dragged from the desktop arrive as an event on the widget under the pointer, bubbling
up like any other. Override `onFileDrop` on the widget you want to be a drop target and
consume the event:

```java
@Override
protected void onFileDrop(FileDropEvent event) {
    for (Path path : event.paths()) {
        open(path);
    }
    event.consume();
}
```

## Displays and resolutions

Screen information arrives as value types in `limn.backend`, so every class reads it the same way
instead of asking the platform its own way:

- `Resolution(width, height, refreshRate)` is a video mode, refresh `0` meaning unspecified;
- `ScreenRect(x, y, width, height)` is a rectangle in screen coordinates;
- `Display` is a monitor: `id()` and `name()`, `isPrimary()`, `currentResolution()`,
  `availableResolutions()`, `bounds()`, `workArea()` — the monitor minus the taskbar or dock —
  and `contentScale()`.

`Backend.displays()` and `Backend.primaryDisplay()` enumerate them, and a window knows which one
it is on: `NativeWindow.display()` returns the monitor containing the window's centre. Fullscreen
takes either form, `enterFullscreen(Resolution)` or the integer one, and native popups clamp
themselves to `display().workArea()`, which is why a menu near the bottom of the screen does not
open under the dock.
