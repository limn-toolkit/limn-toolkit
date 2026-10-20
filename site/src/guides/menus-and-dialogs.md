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
file.addCheck("Word wrap", true, editor::setSoftWrap);
file.addSubmenu("Recent", recentMenu);
```

An item can carry a picture before its label, `MenuItem.of("Preferences", this::preferences)
.setIcon(gear)`. A menu with an icon on any item keeps the same room on every item, so the
labels stay in line, and a tintable icon takes the label's colour.

**As a menu bar**, drawn at the top of your window like any other widget:

```java
MenuBar bar = new MenuBar();
bar.addMenu("File", file);
```

It takes the width it is given, so at the top of a `Column` it spans the window the way
a platform's menu bar does. Arrow keys move between menus, Down or Enter opens one.

On macOS the same bar is shown where a Mac user looks for it, in the system's menu bar at
the top of the screen, and takes no room in your window. You write nothing for that; [the
menu bar on macOS](#the-menu-bar-on-macos) says what the system does with it.

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

The widget that has the keyboard is asked first, and the menu bar only gets a shortcut it
did not use, so `Ctrl+C` in a text field copies from the field whatever your menus say.
**On macOS, with the bar in the system's, it is the other way around:** the system runs the
bar's item before your window sees the key. That is why the six editing commands are not
items you write an action for.

## Undo, cut, copy and paste

An Edit menu is six items the toolkit makes for you:

```java
bar.addMenu("Edit", new Menu()
        .add(MenuItem.of(MenuItem.Role.UNDO))
        .add(MenuItem.of(MenuItem.Role.REDO))
        .addSeparator()
        .add(MenuItem.of(MenuItem.Role.CUT))
        .add(MenuItem.of(MenuItem.Role.COPY))
        .add(MenuItem.of(MenuItem.Role.PASTE))
        .add(MenuItem.of(MenuItem.Role.SELECT_ALL)));
```

Each comes with its name in the user's language and the shortcut the platform gives it,
and has no action: it acts on **whatever widget has the keyboard**. Copy copies from the
focused text field, Select All selects every row of the focused list, and an item is greyed
while the focused widget cannot do it, with nothing selected or nothing to paste. The same
items work in a context menu and in a tool bar's menu.

For a button, `scene.canEdit(EditCommand.COPY)` and `scene.edit(EditCommand.COPY)` are the
same question and the same act. A widget of your own takes part by overriding `onCanEdit`
and `onEdit`.

What to avoid is an ordinary item on one of those shortcuts, a "Select None" on `Cmd+A`:
on macOS it takes the shortcut from every list and text field, and the toolkit logs a line
when it sees one.

## The menu bar on macOS

A `MenuBar` in a window's scene is shown in the system's menu bar while that window is the
one the user is working in. Switch to another window and the bar is that window's; a window
with no bar, a dialog, or no window at all shows a short bar with the application's own menu
and a Window menu. A palette that belongs to a window shows its owner's bar.

Three items live in the **application's menu** on a Mac, the one named after your
application, and in the File, Edit and Help menus everywhere else. Write them where they go
on Windows and Linux, and say what they are:

```java
help.add(MenuItem.of("About", this::showAbout).setRole(MenuItem.Role.ABOUT));
edit.add(MenuItem.of("Preferences…", this::showSettings).setRole(MenuItem.Role.SETTINGS));
file.add(MenuItem.of("Exit", backend::requestQuit).setRole(MenuItem.Role.QUIT));
```

On macOS they leave those menus. About and Settings appear in the application's menu under
the system's own wording, Settings on `Cmd+,`, and run your action. Quit is the system's:
it asks `backend.requestQuit()`, exactly as `Cmd+Q` and the Dock do, and your Exit item is
not shown, so whatever must happen before quitting belongs in `backend.setQuitHandler`,
where every way of quitting finds it.

Two menus are worth naming, because the system fills them:

```java
bar.setWindowMenu(windowMenu);   // gains the list of windows, tiling, Minimize All
bar.setHelpMenu(helpMenu);       // gains the search field
```

With no Window menu named you are given one, with Minimize and Zoom.

**The application's name** in that menu and in bold beside the Apple menu comes from the
application's package. Run from a jar it would read `java`, so say the name when you make
the backend, `new LwjglBackend("Orders")`, or pass `-Dlimn.application.name=Orders`; it
has to be known before the first window. What the system adds to your menus is in the
system's language once the application is packaged.

A few things are the system's and not the toolkit's:

- While a menu of the system's bar is open the window stands still: an animation or a
  video's picture waits for the menu to close. Sound goes on.
- A shortcut on a key that types a character, alone or with Option only, is not run by the
  system; the toolkit runs it, the focused widget first, as in a window.
- Under a fullscreen that takes the whole monitor the system's bar is covered, so the bar
  comes back into the window for as long as it lasts. The green button's fullscreen keeps
  the system's bar, which slides in at the top edge.
- An application packaged as an agent, with no icon in the Dock (`LSUIElement`), has no
  bar in the system's: its `MenuBar` is drawn in the window, as on Windows. A run started
  with `-Dlimn.macos.agent=true`, or with the environment variable `LIMN_MACOS_AGENT` set
  to `1` or `true`, is an agent too, whatever its package says: that is how a build runs
  its tests without their windows taking the keyboard. The variable is inherited, so an
  application launched from such a build or script starts with no Dock icon and no menu
  bar; unset it to run the application as itself.

To keep a bar in its window on macOS, for a tool window with commands of its own:
`bar.setPlacement(MenuBar.Placement.IN_WINDOW)`. `bar.isInSystemBar()` says where it is.

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

## Popovers

A `Popover` is a panel of controls that hangs from a button and closes when the user is done with
it: a filter, a column chooser, a short form. It is not modal; a press outside or Escape closes
it. So does its button going: taken out of the window, with a dialog it was in, say, or a popover
it was in, even to be put back elsewhere (a tool bar moving it into its overflow); or hidden, on
a tab page out of sight or in a folded section, or on the face of a card a `Transform` turns over
or scales to nothing. The keyboard, if it was in the popover, then goes
to nobody, as a focused widget's does when it is taken out. It takes the keyboard while it is
open, its first field focused and Tab cycling inside, and gives it back to the button when it
closes. Where the platform can place a window of its own it
opens in one, so it can pass the window's edge, and a field in it types as any field does, an
input method's composition included.

{% snippet guide:popover %}

`attachTo(button)` makes the button open and close it, and tells a screen reader that the button
opens a popup and whether it is open; `showFor(widget)` opens it from anything else. A button
opens one popover and a popover hangs from one button: attaching another popover to the button
detaches the first, attaching the popover to another button moves it, and `detach()` lets the
button go, a plain button again. Nothing opens a popover on hover: a surface that opens as the
pointer passes is a tooltip.

## Dialogs

A `Dialog` is a title, a message, some buttons, and optionally a widget of your own. It
resolves as a `CompletionStage` rather than blocking:

```java
new Dialog("Discard changes?", "Your edits will be lost.")
        .addPrimaryButton("Keep editing", "cancel")
        .addDestructiveButton("Discard", "discard")
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

A button that deletes or discards goes in with `addDestructiveButton`: drawn in the palette's
danger colour, and never the dialog's default, so Return gives the safe answer and a person
pressing it out of habit loses nothing. That holds whatever order the buttons go in and wherever
the focus is: the dialog opens with the focus on its default button (or on a field of your
content), Return on a focused destructive button still gives the default answer, and a dialog
whose only button is destructive does nothing on Return. Space, a click and a screen reader's
press are what press it.

{% snippet guide:dialog-destructive %}

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

By default a dialog is its own small window. `setDisplayMode(DisplayMode.IN_SCENE)`
draws it inside the owner window instead, as a scrim and a card, which is what you want on
a machine where an extra window would be intrusive, and what you want if you are drawing
something that must stay inside your own frame.

In-scene is a preference, not a guarantee. A dialog asked to draw inside a window that a
modal has already locked opens as a native window instead, because an overlay can only come
forward by raising its host, which would hide the dialog already floating there.
`displayMode()` answers how the dialog was actually presented, which is worth asking
before styling or positioning the card on the assumption that it is an overlay;
`keepInScene()` insists on the overlay and accepts the stacking that comes with it.

A window is not a guarantee either. Over a fullscreen window a modal dialog draws in scene
whatever it was asked; menus, combo box lists and date pickers do the same. A window made
fullscreen at a named mode, as a game asks with `enterFullscreen(Resolution)`, is minimized as
soon as another window takes the focus. One made fullscreen with `enterFullscreen()` is not, but
it already covers the screen, so nothing is lost by drawing inside it. And on a Mac without a
GPU, opening the colour picker as a window held the UI for 1.4 to 3.7 seconds, against 0.6 to
0.7 in scene. A non-modal dialog is the one exception and stays a window, since an overlay would
lock the very window it is meant to leave usable.

The choice is made when each one opens, and the window can go fullscreen afterwards: from your
code, or on macOS from the green button while a combo box list or a date picker is open. A menu,
a list or a date picker open as a window of its own then closes, as if dismissed, as the
platform's own menus do when their window changes state, since the change moves and resizes the
window under a popup placed against its old frame; nothing is chosen. A modal dialog open as a
window moves inside instead, still waiting for its answer, with what was typed in it and the
focus where it was. It stays a window only while another dialog is open over it. Leaving
fullscreen moves nothing back out.

Either way, clicking outside a modal dialog is ignored and beeps, exactly as the platform
does. `setDismissOnScrim(true)` turns the in-scene form into a light dismissable overlay
instead.

### Asking before a dialog is cancelled

A form in a dialog can ask before its changes are lost. `onCancel` is asked whenever the user
closes the dialog with nothing chosen: Escape, a click on the scrim of a dismissable one, a button
whose result is the cancel result, the system closing the dialog's own window, and a screen
reader's dismiss. The handler answers now or later, so the question opens over the form, which
stays open until it is answered:

```java
Dialog edit = new Dialog("Edit customer", "")
        .setContent(form)
        .addButton("Cancel", "cancel")
        .addPrimaryButton("Save", "save")
        .setCancelResult("cancel");
edit.onCancel(request -> {
    if (!form.isChanged()) {
        request.allow();                       // nothing to lose
        return;
    }
    new Dialog("Save changes?", "Your changes are lost if you don't save them.")
            .addButton("Don't save", "discard")
            .addButton("Cancel", "cancel")
            .addPrimaryButton("Save", "save")
            .setCancelResult("cancel")
            .show(scene)
            .thenAccept(answer -> {
                if ("cancel".equals(answer)) {
                    request.refuse();          // back to the form, as it was left
                } else {
                    request.dialog().dismiss(answer); // closes the form with that answer
                }
            });
});
```

`allow()` closes the form with its cancel result, the answer for "nothing to lose". Save and
Don't save are answers of their own, which the code that showed the form acts on, so they go
through `request.dialog().dismiss(answer)`: the form closes with that result, and the request is
settled by it.

Only the cancel asks. The default button, any other button and a destructive one are answers and
close the dialog at once; `dismiss` is your own close and asks nothing, and neither does the
dialog's window going away or the application ending. While a request is unanswered, a second
Escape asks nothing new, and the dialog's other buttons still answer it if nothing was opened over
it; an answer given that way settles the request, and a late `allow()` or `refuse()` then does
nothing. A handler that throws has refused, so the dialog stays open. It works the same in both
display modes and under less motion, being asked before anything fades.

## Closing a window, and ending the application

A window asks its close handler before it closes, and the handler may answer later, which
is what an "unsaved changes" dialog needs:

```java
window.setCloseRequestHandler(request -> {
    if (!document.isDirty()) {
        request.allow();
        return;
    }
    new Dialog("Save changes?", "Your edits will be lost otherwise.")
            .addButton("Cancel", "cancel")
            .addButton("Don't save", "discard")
            .addPrimaryButton("Save", "save")
            .setCancelResult("cancel")
            .show(scene)
            .thenAccept(result -> {
                if ("cancel".equals(result)) {
                    request.refuse();
                } else {
                    if ("save".equals(result)) {
                        document.save();
                    }
                    request.allow();
                }
            });
});
```

Answer each request once, with `allow()` or `refuse()`. While a request is unanswered, a
second click on the close button asks nothing new, so the user never sees two dialogs. A
window with no handler simply closes. Your own `window.requestClose()` asks nothing: once
your code has decided, the window closes.

What an allowed close does is the window's policy. `setClosePolicy(ClosePolicy.HIDE)`
keeps the window to show again, which suits a palette or a preferences window.
`ClosePolicy.END_APPLICATION` makes the close button end the whole application, which suits
a main window.

To end the application from a Quit item, call `backend.requestQuit()`, and give the item the
role `MenuItem.Role.QUIT` so that macOS shows its own Quit in its place. It asks every window,
the one the user worked in last first, and one at a time, so at most one question is on
screen. If any window refuses, nothing closes. Once all of them agree, every window closes
and `runEventLoop()` returns `LoopEnd.QUIT`. On macOS, Cmd-Q, the application menu and the
Dock's Quit make the same request for you. `backend.setQuitHandler(...)` is asked before
any window, for what belongs to no window, such as a download still running. A dialog or
menu that opened as a window of its own is not asked; its owner answers for it and it closes
with the owner.

By default the loop ends when the last window closes. An application that lives in the
tray keeps running with no window open after
`backend.setLoopLifetime(LoopLifetime.UNTIL_QUIT)`, and then ends only through
`requestQuit()` or `stop()`.

### Minimizing, maximizing and bringing in front

`window.minimize()`, `maximize()` and `restore()` do what the title bar's buttons do, and
`isMinimized()` and `isMaximized()` say where the window is. `window.toFront()` is the one a
"show the window" command calls: it shows a hidden window, restores a minimized one and gives
it the keyboard.

To act when any of it changes, whoever did it, observe the window's state:

```java
window.observeState(state -> {
    preferences.put("window", state.bounds());       // its place and size, to open there next time
    pause.setEnabled(!state.minimized());
});
```

The listener is told on the UI thread after the window is shown or hidden, minimized, maximized
or restored, enters or leaves fullscreen, is moved or changes size, with a `WindowState` of all
of it; `window.state()` and `window.bounds()` answer the same at any time. Under Wayland a
window is told neither that it is minimized nor where it is, so there `minimized` is `false`
and the place is the origin.

## Native file dialogs

`window.fileDialogs()` gives you the platform's own chooser, owned by that window: the real one,
not a reimplementation. On macOS it slides down as a sheet from the window, on Windows it is a
dialog the window owns, and on Linux it is the desktop's chooser through the desktop portal,
which also works inside a Flatpak or Snap sandbox.

```java
window.fileDialogs()
        .openFile("Open image", lastFolder, FileDialogs.Filter.of("Images", "*.png", "*.jpg"))
        .thenAccept(chosen -> chosen.ifPresent(this::load));
```

The call returns at once and the answer arrives later, on the UI thread, so your application
keeps drawing, animating and running its timers while the user browses. An empty answer means
cancelled. A window shows one chooser at a time; asking again while one is open answers empty.
On a headless machine there is no chooser, and every call resolves empty, exactly as a cancel
does, so your code does not need a special case.

## Opening a link, a file, or its folder

`window.launcher()` hands something to the system the window is on: an address to the browser
or the mail program, a file to the program registered for its type, or a file to the file
manager, shown selected in its folder. It is what an About box's link, an "Open the report" and
a "Show in folder" call.

{% snippet guide:launcher %}

Each call returns at once and answers later, on the UI thread. `true` means the system took
what it was handed, which is all a system tells a program; `false` means it did not (no program
is registered, the file is gone, or the window is headless), and the reason is in the log.

**Which addresses.** An address often comes from data, and text handed to a system as it is can
start a program. So `openAddress` takes `http`, `https` and `mailto` and refuses everything
else, answering `false`. If your application's own addresses are of another kind, say so once
where it starts:

```java
Launcher.allowScheme("tel");
```

A `file:` address is never taken. A file is opened by `openFile`, which takes a path your code
built; on a program file it does what a double click does, which is run it. `Launcher.isAllowed`
answers what `openAddress` would refuse, for a link you would rather disable than have do
nothing.

On Linux an address goes through the desktop portal, which also works inside a Flatpak or Snap
sandbox, and through `xdg-open` where no portal answers. What opens comes in front of your
window, a browser that was already running included: a Linux desktop lets a program in front only
with proof that the user asked, and the launcher hands that proof on, from the key or the button
that called it. Call it from the user's action, then, and not from a timer.

## An icon in the tray

A `Tray` keeps an icon in the place each system has for one: the notification area on Windows,
the menu bar on macOS, the panel's tray on a Linux desktop. It has a title, a tooltip, a menu
and, if you want one, a click that brings your window.

{% snippet guide:tray %}

**The system draws the menu**, from the same `Menu` a menu bar takes, so it looks like every
other menu of that system, light or dark as the system is. It is read each time it is about to
open: change the menu, an item's check mark or whether it is enabled as you would anywhere, and
the next opening shows it. Of an item the system shows its label, its icon, whether it is
enabled and checked, a separator, a submenu, and its accelerator: on macOS as the system's own
key equivalent, which also runs the item while the menu is open, and on Windows and KDE as the
words beside the label. GNOME's menu shows the icon and not the accelerator.

**What a click does.** With no `onActivate`, any click on the icon opens the menu. With one, the
click that means "this application" runs it and the other click opens the menu: the left click
on Windows, on macOS and on KDE, and a double click on GNOME, where a single one opens the
menu. A panel may never send an activation at all, so make what it does an item of the menu
too, as above.

**Two icons.** `setIcon` is shown as it is. `setMonochromeIcon` takes a shape, a tintable icon,
and shows it in the bar's own colour: macOS colours it as it does its own status items, Windows
shows it black on a light task bar and white on a dark one, and follows a change while you run.
A Linux panel does not say its colour, so there the icon of `setIcon` is used when you gave
one; the shape alone is coloured by the panel itself on KDE, is white on GNOME, whose top bar is
always dark, and a mid grey elsewhere.
Give both, and each system shows the better one. Icons are asked for at the size the system
draws them, so a vector icon is sharp at any scale.

**Asking for attention.** `setAttention(true)` shows the icon with a dot at its top trailing
corner, over the monochrome icon too, until you turn it off. `setAttentionIcon` shows an icon of
yours instead. On Linux a panel that tucks idle icons away may bring this one out.

**A desktop may have no tray.** Windows and macOS always have the place; a Linux desktop has it
when its panel does, and GNOME needs an extension for it, which several distributions ship
turned on. `isAvailable()` says whether the system took the icon, and `onAvailabilityChange`
tells you when that changes, which it does when the user turns the extension on while you run.
It says the icon was taken, not that it is seen: Windows starts a new icon among its hidden
ones, where the user promotes it.

### Closing to the tray

Closing the window while the application stays, with the icon as the way back, is three things
you already have: a window that hides when closed, a loop that outlives its windows, and the
icon's click. Where the desktop has no tray, a hidden window would be lost, so let the close
end the application there:

{% snippet guide:tray-close %}

### With no main window, and no tray

An application that is only an icon has nothing to fall back on where there is no tray. The
usual answer is a small window of its commands that stays minimized in the dock: the user
brings it back, picks a command, and it minimizes again. It is an entry of its own only while
it is the application's one window, since a desktop shows one entry for all of an application's
windows, and its picture is the one your installer's desktop file names.

{% snippet guide:tray-standin %}

## Files dropped onto your window

Files dragged from the desktop are a drop like any other. The widget under the pointer is asked
whether it takes what is offered, and its parents after it; the one that accepts is handed the
files:

```java
@Override
protected void onDragOver(DragEvent event) {
    if (event.offer().has(DataFormat.FILES)) {
        event.accept();
    }
}

@Override
protected void onDrop(DropEvent event) {
    event.offer().read(DataFormat.FILES).thenAccept(files -> files.forEach(this::open));
}
```

[Drag and drop](/docs/drag-and-drop/) has the rest: what else a drop can carry, and dragging
out of your window.

## Displays and resolutions

Screen information arrives as value types in `limn.backend`, so every class reads it the same way
instead of asking the platform its own way:

- `Resolution(width, height, refreshRate)` is a video mode, refresh `0` meaning unspecified, its
  size in screen coordinates like everything here: pixels on Windows and Linux, points on macOS,
  where a Retina display's modes are smaller than its panel (1728 × 1117 on a 3456 × 2234 one);
- `ScreenRect(x, y, width, height)` is a rectangle in screen coordinates;
- `Display` is a monitor: `id()` and `name()`, `isPrimary()`, `currentResolution()`,
  `availableResolutions()`, `bounds()`, `workArea()` — the monitor minus the taskbar or dock —
  and `contentScale()`.

`Backend.displays()` and `Backend.primaryDisplay()` enumerate them, and a window knows which one
it is on: `NativeWindow.display()` returns the monitor containing the window's centre, or
`null` once the window is closed. A `Display` whose monitor was unplugged answers what it last
read. Native popups clamp themselves to `display().workArea()`, which is why a menu near the
bottom of the screen does not open under the dock.

Fullscreen comes in two kinds, and which one a call makes depends on whether it names a mode.
`enterFullscreen()` names none and leaves the display as it is: on macOS it is the native
fullscreen of the green button, a Space of its own reached by an animation, with the menu bar a
reach of the pointer away; on Windows and X11 a borderless window over the monitor; on Wayland the
compositor's. `enterFullscreen(Resolution)`, or the integer form with a size, is the exclusive
fullscreen a game wants: the display is switched to that mode until `exitFullscreen()`. The two
also part when another window takes the focus: a window at a named mode is minimized, with the
display switched back until it is restored, and one made with `enterFullscreen()` stays fullscreen
behind the window that took the focus, as a browser's does. Ask a
Retina Mac for its `currentResolution()` and it switches anyway, because the mode it is in is not
one it lists; to keep the display as it is, name no mode. `isFullscreen()` answers for both, and
on macOS for the green button too.
