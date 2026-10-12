# ADR 060: On macOS a menu bar is shown in the system's bar

- **Status:** Proposed, 2026-10-11; revised the same day after an adversarial review by a second
  reader (§11 says what it changed). The decisions of §1 to §6 are the owner's, each taken after
  the measurement of §0; nothing is built yet.
- **Date:** 2026-10-11
- **Scope:** where a `MenuBar` is shown on macOS, the three items the system keeps in the
  application's own menu (About, Settings, Quit), the two menus the system fills (Window, Help),
  who runs an accelerator, the six editing commands as items that act on the focused widget on
  every system, and what the window does while a menu is open. New public API:
  `MenuBar.setPlacement`, `MenuItem.setRole` and `MenuItem.of(Role)`, `MenuBar.setWindowMenu`,
  `MenuBar.setHelpMenu`, `EditCommand` with `Scene.canEdit` and `Scene.edit` and a widget's two
  hooks for them, and the application's name given where the backend is made.
- **Compatibility:** behaviour changes on macOS for every application. One with a `MenuBar`
  loses the strip in the window and has its menus in the system's bar, where an accelerator of
  the bar runs before the focused widget sees the key; one call puts the bar back (§2). One with
  no `MenuBar` has the windowing library's default bar replaced by the toolkit's minimal one. On
  Windows and on Linux an application changes only if it uses the new roles. The record the
  backend reads a menu's item through is renamed, on all three backends.
- **Builds on:** [ADR 058](058-an-application-keeps-an-icon-in-the-systems-tray-and-the-system-draws-its-menu.md),
  whose reading of a `Menu` by the backend and whose native menu on macOS are reused, and
  [ADR 048](048-a-window-closes-by-its-policy-and-an-application-ends-by-asking-once.md), whose
  promise that every Quit of the system is one `Backend.requestQuit()` is kept.

---

## 0. What was measured

On the macOS guest, the Showcase running unpackaged, the system in Brazilian Portuguese.

**The bar an application gets today**, read through the accessibility interface:

- an application menu titled `java`, in English: About, Services, Hide, Hide Others, Show All,
  Quit. No Settings;
- a Window menu. No File, Edit, View or Help;
- the application's `MenuBar` drawn inside the window, under the title bar.

**A bar of the conventional shape put in its place** by a test class in the running process (an
application menu with About, Settings, Services, Hide and Quit; File, Edit, View, Window, Help):

| | what was seen |
|---|---|
| The bar | Replacing the main menu works with nothing else changed in the backend. |
| Accelerators with Command | Cmd-N, Cmd-C, Cmd-comma, Shift-Cmd-Z and Cmd-Backspace pressed with the window focused each ran the bar's item. |
| Accelerators without it | A bare F5 and Control-K ran the bar's item. **Option-J and a bare letter did not**: the focused field received the character. |
| The focused widget | A search field given "abc", Cmd-A, "x" held `x` without the test bar and `abcx` with a Cmd-A item in it: **the system gives the chord to the bar's item and the field never sees the key.** |
| A disabled item | With the Cmd-A item disabled the field held `x`: **a disabled item does not take its chord, and the key reaches the focused widget.** |
| Before a chord is matched | The system asks every menu of the bar to bring itself up to date, opened or not, on each chord pressed, and on no plain key. |
| The bold title | Stays `java` whatever title the first item carries. With the process's bundle given a name **before** the windowing library starts, it reads `Limn Showcase`; given after, it stays `java`. |
| Window menu | Declared to the system as the windows' menu, it gained Minimize All, Zoom All, Fill, Center, Enter Full Screen, the tiling items and the list of windows. **Those additions came in English**, the process having no bundle that carries a translation. |
| Help menu | Declared as the help menu, it gained the search field that looks through the bar's items. |
| File menu | The system added an alternate "Close All" under the item that closes the window. |
| Edit menu | The system added nothing. |
| An agent | Packaged with the key that makes an application an agent, with no icon in the Dock and no bar of its own (`LSUIElement`), the Showcase was listed in the Dock all the same: **the windowing library makes every application an ordinary one as it starts.** |

Asking the menu's delegate whether it holds a chord, in place of bringing the menu up to date,
was tried and is not used: an answer of no still ran the item.

The test class, the scripts and the captures are kept outside the tree with the round's other
evidence.

## 1. The application declares its bar once, with `MenuBar`

There is no second way to declare menus. The `MenuBar` an application already adds to its scene
is what the system's bar shows on macOS; on Windows and on Linux it is drawn in the window as it
is today.

### 1.1 The widget in the system's bar

It is laid out as a child that is not there, as an invisible one is: no width, no height and no
gap beside it. It paints nothing, is no stop of the Tab key, and publishes no accessible node,
the system's bar being read by the screen reader as every other application's is. Its hold on
Alt, F10 and the mnemonics is let go, `isOpen()` answers false and no expansion is announced.

### 1.2 Which bar the system shows

The bar is the application's, one for the process, and the backend's to keep; each window
contributes its `MenuBar`. What is shown follows **the window the user is working in**, found
from the toolkit's own state:

| The window with the keyboard | The bar shown |
|---|---|
| has a `MenuBar` placed in the system's bar | that bar |
| is a popup of the toolkit's (a dropdown, a context menu, a tooltip) | its owner's, found again by this table |
| is owned and not modal, with no bar of its own (a palette, an inspector) | its owner's |
| is modal, or is blocked by a modal window, or has a modal dialog drawn in its scene, or a file panel of the system's over it | the *minimal bar* |
| has no bar | the minimal bar |
| there is none: every window hidden, closed or minimized | the minimal bar |

The **minimal bar** is the application's menu of §3 and the Window menu of §4, and nothing of
any window's. While it is shown no accelerator of a window's bar runs, which is what the bar in
the window does under a modal dialog today.

A window that is not shown, or was never given the keyboard, never takes the bar. When the
window whose bar is shown is hidden or closed, or its `MenuBar` leaves the scene, or the scene
leaves the window, the table is read again.

### 1.3 More than one

A second `MenuBar` placed in the system's bar in the same window stays in the window, and the log
says so once. Which is the first is the order they were attached in.

### 1.4 Where there is no system bar

The backend tests run on has none, and draws the widget in the scene on every system: no unit
test and no accessibility contract changes. The galleries that capture the site's pictures and
the screen reader's subjects run on the real backend, so they ask for `IN_WINDOW`, and their
pictures stay the same on a Mac as on the Linux machines that publish them.

### 1.5 The backend's side

The toolkit hands the backend the bar as rows, already sorted into the application's menu, the
plain menus, the Window menu and the Help menu, and hears back which row was chosen. The sorting
of §3 and §4 is the toolkit's, in plain Java, tested without a system, and never changes the
`Menu` it reads, which a tray or a context menu may be showing too. The row is the record the
tray's peer reads, renamed for both and given a role.

**When it is read.** The whole bar, top level and application's menu included, is read from the
application whenever the system asks a menu to bring itself up to date: when one opens, and
before a chord is matched (§0). One reading numbers the rows of the whole bar, and a choice is
answered against the reading it came from. A reading costs what its menus cost to walk; the
pictures of the items' icons are kept between readings.

## 2. In the system's bar by default, in the window by one call

`MenuBar.setPlacement(Placement)` and `placement()`, with `MenuBar.Placement.SYSTEM` the default
and `IN_WINDOW` the other. `SYSTEM` is a preference, as `DisplayMode.NATIVE_WINDOW` is.
`isInSystemBar()` answers what happened, and what happened can change while the application
runs, the widget being laid out again when it does:

- where the system keeps no bar for applications, which is everywhere but macOS, the bar is in
  the window;
- **in a fullscreen on the monitor**, which covers the system's bar, the bar is in the window
  for as long as it lasts. In the system's own fullscreen the system hides its bar and shows it
  at the top edge, and the bar stays there;
- **an application the system runs as an agent** has no bar in the system's: its package says
  so (`LSUIElement`), for an application that lives in the tray with no icon in the Dock. The
  toolkit leaves it the agent its package says it is, where the windowing library made it an
  ordinary application (§0); it builds no bar for it, the minimal one included; a `MenuBar` is
  in the window, and the roles of §3 change nothing, as on Windows and on Linux. The editing
  roles act as everywhere;
- a bar that is not visible, or that §1.3 left out, is not in the system's bar.

A bar inside the window is the most visible mark of an application that does not belong on
macOS, and the toolkit's default is the application that does. What stays possible is the window
with a reason for a bar of its own: a tool window with its own commands, a kiosk.

`-Dlimn.macos.systemMenuBar=false` keeps every bar in the window, and leaves the windowing
library's default bar where it is, for a laboratory and for a support call.

## 3. About, Settings and Quit are items with a role

`MenuItem.setRole(Role)` and `role()`, with `MenuItem.Role.ABOUT`, `SETTINGS`, `QUIT`, the six of
§5 and `NONE` the default. The developer writes the item where it lives on Windows and on Linux
(Help > About, Edit > Preferences, File > Exit) and names its role. In the window a role of
these three changes nothing, on any system. In the system's bar:

- the item leaves the menu it was written in. A separator left at an end of that menu, or beside
  another, goes with it; a menu or a submenu left with no item is not shown, except the one
  named as the Help menu, which stays for its search;
- the application's menu is, in the system's order: About, Settings, Services, Hide, Hide
  Others, Show All, Quit. Services, the three of hiding and Quit are always there, the
  toolkit's. About and Settings are there when an item has the role, with the system's label
  and, for Settings, the system's accelerator; they run the item's action and are enabled as
  the item is. The item's own label, icon and accelerator are not used there;
- **Quit always asks `Backend.requestQuit()`**, as the Dock's Quit and a quit from another
  program do, so ADR 048's one question stays one. The role `QUIT` says only that this is the
  item the system's Quit stands in for: in the system's bar it is not shown and its action is
  not run, and whether it is enabled does not reach the system's Quit. An application whose Exit
  item does more than ask to quit moves that to the quit handler, where the Dock's Quit finds it
  too; the guide says so;
- **the roles are the application's, not a window's.** The application's menu shows the About
  and Settings of the bar that last declared them, whichever window has the keyboard and where
  none has, so a dialog does not take Settings away. The same holds under the minimal bar and
  for a bar kept `IN_WINDOW`;
- a role given to two items of one bar is the first one's, and the log says so once;
- a role is refused, as an accelerator is, on a separator, on a submenu and on an item that
  checks. An item with a role in a tray's menu or a context menu is an ordinary item there.

**What the toolkit's own items claim.** Cmd-comma, Cmd-H, Option-Cmd-H, Cmd-Q and Cmd-M are the
system's for Settings, Hide, Hide Others, Quit and Minimize. An item of the application's with
one of those chords and no role keeps its place in its menu and loses the chord to the system's
item, and the log says so once.

**The application's name** in those labels and in the bar's bold title is the name of the
application's package where it is packaged. An unpackaged process shows the launcher's own name
unless the name reaches the system before the windowing library starts, and after that it is
too late (§0). So the name is given where the backend is made: an argument of the backend's
construction, and the property `limn.application.name` for a launch nobody can change.
`Backend.setApplicationName`, which runs later, goes on naming the application for the Linux
desktop and for the screen readers and names the toolkit's labels here; its documentation says
what it cannot reach.

**The labels the toolkit writes** (About, Settings, Services, Hide, Hide Others, Show All, Quit,
Window, Minimize, Zoom) follow the application's language, as its other strings do, in the
languages the toolkit carries, with the wording the system uses in each. What the system adds is
in the system's language, and only for an application packaged with that language's resources.

## 4. The Window and Help menus are named, and a Window menu is given where none is

`MenuBar.setWindowMenu(Menu)` and `MenuBar.setHelpMenu(Menu)` name one of the bar's menus as the
one of that kind; `null` names none. The menu need not be in the bar yet, and one that never is
names nothing. In the window they change nothing. In the system's bar the menu is handed to the
system as such, and the system adds its own items to the application's: the windows' list and
the commands over them, the search field.

An application that names no Window menu is given one in the system's bar, before the Help menu
or last: Minimize, Zoom, and what the system adds. The bar an application has today carries one,
and with it Cmd-M and the list of windows, which nobody should lose by updating. An application
with a menu of its own for its windows names it, or has two; the guide says so. A Help menu is
only the application's.

## 5. The system runs an accelerator first, and the editing commands are roles

**The owner's order.** An accelerator of an item in the system's bar is the system's: it is
written beside the item as the system writes it, and pressing it runs the item, whatever has the
keyboard. This is the order of the system and the reverse of the order in the window, where the
focused widget is asked first.

Left alone, that order takes Cmd-C from every text field under an Edit menu whose Copy is on
Cmd-C (§0), and Cmd-A from every list, table and tree under a Select All, and the application
has no way to hand the command back: no widget offers copying, pasting or undoing to its caller.
So the editing commands stop being the application's to write.

### 5.1 An editing command

`EditCommand` is `UNDO`, `REDO`, `CUT`, `COPY`, `PASTE` and `SELECT_ALL`.

- **A widget answers two questions**, hooks it overrides as it overrides its handling of a key:
  whether it can do a command now, and to do it. Each widget answers for the commands it already
  takes from the keyboard and for no other: the text field and the text area and what is built
  on them (the password field, which refuses to cut and to copy; the search field; the spinner;
  the editable combo box; the date field) for what they edit; the list, the table, the tree and
  the combo box that selects several for `SELECT_ALL`. What a widget does for the key and what
  it does for the command are one piece of code, with the user as the origin of the change.
  A widget of the developer's own takes part by the same two hooks.
- **`Scene.canEdit(EditCommand)` and `Scene.edit(EditCommand)`** ask the widget that has the
  keyboard in that scene, then each of its ancestors, and stop at the first that can. They are
  public, so a tool bar's Copy button is one line.

### 5.2 An item with an editing role

`MenuItem.of(Role)` makes the item of an editing role whole: the label in the application's
language, the accelerator the platform gives the command (`Accelerator.command` on the letter,
and for `REDO` the platform's own, Shift-Cmd-Z on macOS and Ctrl-Y elsewhere), and no action,
there being none to write. The developer may change the label and the accelerator. `setRole`
with an editing role on an item that has an action is refused: the role is the action.

Wherever the item is shown, on every system:

- **it is enabled when the scene can do the command now**, read each time the menu is. In a bar
  and in a context menu that is the scene of the window the menu belongs to, asked about the
  widget that had the keyboard before the menu took it. In a tray's menu it is disabled;
- **choosing it does the command** in that scene.

The text widgets' own context menu becomes four such items.

### 5.3 What that makes of the order

In the window nothing changes: the focused widget takes its chord before the bar is asked, and
the bar's item is reached only by the pointer.

In the system's bar the system runs the item, and the item does in the focused widget what the
widget would have done for the key. Where the focused widget cannot do the command the item is
disabled, and a disabled item does not take its chord (§0), so the key goes on to the widget as
if there were no bar. The menus are brought up to date before each chord is matched (§0), so the
item's state at that moment is the true one.

A window showing its owner's bar (§1.2) is asked through its own scene: a field in a palette is
copied from, not one in the owner.

### 5.4 What is still the developer's to know

An item with **no** editing role on a chord a focused widget takes still wins on macOS: a
"Select None" on Cmd-A takes Cmd-A from every list. The guide says so where it teaches
accelerators, and the toolkit logs once, at the level of information, a bar that carries such an
item on one of the six commands' chords.

### 5.5 The rest of an accelerator

- **Every key the toolkit names has its spelling for the system**: the letters and digits, the
  function keys, Delete and Backspace, Enter, Tab, Space, Escape, the arrows, Home, End and the
  page keys, the punctuation.
- **What the system would not run stays the toolkit's.** A chord of Option alone or of no
  modifier on a key that types a character is shown beside its item and is not handed to the
  system, which would give the key to the focused widget (§0); the toolkit's own route runs it,
  the focused widget first, as in the window. Every other accelerator is the system's alone, so
  no chord runs twice.
- **A held chord** runs an editing command again, as it does in a text field today, and no other
  item, as the bar in the window refuses to.
- **The layout of the keyboard.** The system matches an accelerator by the character the key
  types; the toolkit matches a letter or a digit by the character and every other key by its
  place. On a layout where they differ, an accelerator on a punctuation key is where the system
  puts it in the system's bar and where the toolkit puts it in the window. Declared in the
  guide.
- A mnemonic and F10 belong to the bar in the window; the system's bar has the system's own keys.
- A key's release that follows a chord the system took reaches the scene with no press before
  it, and is dropped.

## 6. The window waits while a menu is open

While a menu of the system's bar is open the system holds the thread the toolkit draws on, and
the window is not drawn again until the menu closes: an animation, a video's picture and a
progress bar stand still, sound goes on. It is the limit ADR 058 declared for the tray's menu.
The choice reaches the toolkit after the system is done with the menu, so an action that closes
a window or ends the application runs outside the system's loop.

Drawing the window from inside the system's loop was priced and not taken: it runs the
application's code in the middle of a menu, for a second or two of stillness in an application
of forms and tables.

## 7. What a menu shows

As the tray's menu on macOS: an item's label, its icon, its accelerator, enabled, checked, a
separator and a submenu. The bar's direction follows the application's. An item's mnemonic is
not shown, the system having none.

## 8. What changes in what is already written

- `MenuBar`'s documentation, which says the bar never takes a keystroke from a text field, the
  scene's documentation of the order of shortcuts, and the guide's two sentences on it: each
  says what holds in the window and what holds in the system's bar.
- `Backend.setApplicationName`'s documentation (§3).
- The guide's page on menus gains the system's bar, the roles, the two named menus, the editing
  commands, how a package gives the application its name and its languages, and the Exit item's
  move to the quit handler.
- The Showcase names its application, gives its items their roles and names its Help menu.

## 9. What is not in this ADR

- A menu bar outside the window on Windows or on Linux. Neither system has one in common use.
- The Dock's menu and the Dock icon's badge.
- New commands for a widget: a table does not learn to copy here.
- Drawing while a menu is open (§6).

## 10. To be seen before this is accepted as implemented

On the macOS guest, each by a capture, by the application's own trace or by the screen reader's
words.

**The bar.**
1. The Showcase's bar in the system's bar and none in the window, with no gap where it was;
   `IN_WINDOW` and the property putting it back.
2. Two windows with different bars, the bar changing with the focus; a window with none.
3. A dropdown, a context menu and a tooltip open: the bar does not change.
4. A modal dialog as a window and one drawn in the scene; a palette that is owned; a file panel
   of the system's: the minimal bar under the three that block, the owner's under the palette.
5. Every window hidden with the application kept running, and every window minimized.
6. The window that owns the bar closed and hidden; the bar detached and attached again; the
   scene changed on the window.
7. A fullscreen on the monitor and the system's own.

**The application's menu and the named ones.**
8. About and Settings under the application's name, gone from the menus they were written in,
   each running its action; Settings disabled; a Help menu left with About alone.
9. Quit from the menu, by Cmd-Q and from the Dock, each asking the quit handler once.
10. The name of an unpackaged process given at the backend's making and by the property.
11. The Window menu with the system's additions and what the system lists in it (a popup must
    not be there); the Help menu's search running an item of a menu never opened; the Window
    menu given to an application that named none.

**Accelerators and editing.**
12. An accelerator running once; one held; a disabled item's; a bare function key, Control
    alone, Option alone and a bare key.
13. Copy, cut, paste, undo, redo and select all, each by its chord and from the menu, in a text
    field, a text area, a password field, a date field and a list; each item enabled and
    disabled as the focus moves; the same in a palette under its owner's bar; paste and select
    all in a file panel's name field.
14. The same six from the bar in the window on Windows and on Linux, and from a text field's
    context menu.
15. A chord while an input method is composing.
16. A layout that is not the American one: a letter, a digit, a punctuation key, and a layout of
    another script.
17. The time a chord takes to be matched in a bar of the Showcase's size, icons included.

**The rest.**
18. An item's icon, check and submenu; a right-to-left language.
19. The bar read by the screen reader.
20. The tray's menu opened after the bar's and before it.
21. An action of the bar that closes its window, and one that ends the application.
22. An application packaged as an agent: no icon in the Dock, no bar of its own in the
    system's, its `MenuBar` in the window with every item where it was written, the editing
    items acting, its tray icon and its menu.

## 11. What the review changed

A second reader was given the record and the code and asked to break it. What it found and what
was done:

- **The order of §5 took the editing chords from every widget, with no way back.** The owner
  chose the editing roles for this ADR, which is §5.1 to §5.3.
- **The name could not be given late**, and the method the record named always runs late.
  Measured, and §3 gives the name where the backend is made.
- **Keys the tray's menu does not spell would have run nowhere**, and a chord without Command
  was unmeasured. Measured, and §5.5.
- **Quit running the item's action broke ADR 048's promise.** §3: Quit always asks once.
- **The rule of windows left out** the popups, the dialog drawn in the scene, the file panel and
  the application with no window, and made the application's menu change with the window. §1.2
  and §3.
- **"Read each time it opens"** covered neither the top level nor a chord pressed with no menu
  opened. Measured, and §1.5.
- **A fullscreen on the monitor** left the application with no menu. §2.
- **"No golden changes"** was false for the galleries on a Mac. §1.4.
- Smaller: the gap a widget of no height still cost, a held chord, the keyboard's layout, the
  chords of the toolkit's own items, a role on an item that cannot carry one, the two named
  menus given a menu the bar does not have, the stray release of a key.
