# ADR 060: On macOS a menu bar is shown in the system's bar

- **Status:** Proposed, 2026-10-11. The six decisions of §1 to §6 are the owner's, each taken
  after the measurement of §0; nothing is built yet.
- **Date:** 2026-10-11
- **Scope:** where a `MenuBar` is shown on macOS, the three items the system keeps in the
  application's own menu (About, Settings, Quit), the two menus the system fills (Window, Help),
  who runs an accelerator, and what the window does while a menu is open. New public API:
  `MenuBar.setPlacement`, `MenuItem.setRole`, `MenuBar.setWindowMenu`, `MenuBar.setHelpMenu`.
  Nothing changes on Windows or on Linux.
- **Compatibility:** behaviour changes on macOS for every application that has a `MenuBar`: the
  strip leaves the window and its menus are in the system's bar. One call puts it back (§2).
- **Builds on:** [ADR 058](058-an-application-keeps-an-icon-in-the-systems-tray-and-the-system-draws-its-menu.md),
  whose reading of a `Menu` by the backend and whose native menu on macOS are reused, and
  [ADR 048](048-a-window-closes-by-its-policy-and-an-application-ends-by-asking-once.md), which
  already routes the system's Quit command to `Backend.requestQuit()`.

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
| Accelerators | Cmd-N, Cmd-C, Cmd-comma and Shift-Cmd-Z pressed with the window focused each ran the bar's item. |
| The focused widget | A search field given "abc", Cmd-A, "x" held `x` without the test bar and `abcx` with a Cmd-A item in it: **the system gives the chord to the bar's item and the field never sees the key.** |
| The bold title | Stays `java` whatever title the first item carries. With the process's bundle given a name before the windowing library starts, it reads `Limn Showcase`. |
| Window menu | Declared to the system as the windows' menu, it gained Minimize All, Zoom All, Fill, Center, Enter Full Screen, the tiling items and the list of windows. **Those additions came in English**, the process having no bundle that carries a translation. |
| Help menu | Declared as the help menu, it gained the search field that looks through the bar's items. |
| File menu | The system added an alternate "Close All" under the item that closes the window. |
| Edit menu | The system added nothing. |

The test class, the scripts and the captures are kept outside the tree with the round's other
evidence.

## 1. The application declares its bar once, with `MenuBar`

There is no second way to declare menus. The `MenuBar` an application already adds to its scene
is what the system's bar shows on macOS; on Windows and on Linux it is drawn in the window as it
is today. An application that has a `MenuBar` gains the system's bar without a line changed.

- **In the system's bar the widget takes no room.** It measures to zero height, paints nothing,
  takes no focus and publishes no accessible node: the system's bar is read by the screen reader
  as every other application's is.
- **The bar follows the window.** The system's bar shows the `MenuBar` of the window that has the
  keyboard. A window with none shows the *minimal bar*: the application menu and the Window menu
  of §3 and §4. Two exceptions, both the convention of the system:
  - a window that is owned and not modal (a palette, an inspector) shows its owner's bar;
  - a modal window shows the minimal bar, its owner's commands being out of reach while it is up.
- **One bar a window.** A second `MenuBar` placed in the system's bar in the same window stays in
  the window, and the log says so once.
- **A backend with no system bar** (the one tests, captures and the site's films run on) draws
  the widget in the scene on every system, so no golden changes.

The backend's side is a peer of the window, as the tray's is of the backend: the toolkit hands it
the bar's menus as rows, already sorted into the application's menu, the plain menus, the Window
menu and the Help menu, and hears back which row was chosen. The sorting of §3 and §4 is the
toolkit's, in plain Java, and is tested without a system. The row the tray's peer reads is the
same record, renamed for both.

## 2. In the system's bar by default, in the window by one call

`MenuBar.setPlacement(Placement)`, with `Placement.SYSTEM` the default and `Placement.IN_WINDOW`
the other. `SYSTEM` is a preference, as `DisplayMode.NATIVE_WINDOW` is: where the system keeps no
bar for applications, which is everywhere but macOS, it is `IN_WINDOW`. `isInSystemBar()` answers
what happened.

A bar inside the window is the most visible mark of an application that does not belong on
macOS, and the toolkit's default is the application that does. What stays possible is the window
with a reason for a bar of its own: a tool window with its own commands, a kiosk in fullscreen.

`-Dlimn.macos.systemMenuBar=false` keeps every bar in the window, for a laboratory and for a
support call.

## 3. About, Settings and Quit are items with a role

`MenuItem.setRole(Role)`, with `Role.ABOUT`, `Role.SETTINGS`, `Role.QUIT` and `Role.NONE` the
default. The developer writes the item where it lives on Windows and on Linux (Help > About,
Edit > Preferences, File > Exit) and names its role. In the window nothing changes. In the
system's bar:

- the item leaves the menu it was written in and takes its place in the application's menu, with
  the label and the accelerator the system's convention gives it (Settings with Cmd-comma, Quit
  with Cmd-Q), naming the application; its action, its enabled state and nothing else are the
  developer's;
- a separator left at an end of the menu, or beside another, goes with it, and a menu left with
  no item is not shown;
- the application's menu is, in the system's order: About, Settings, Services, Hide, Hide Others,
  Show All, Quit. Services, the three of hiding and Quit are always there, the toolkit's;
- with no item of role `QUIT`, Quit asks `Backend.requestQuit()`, as the system's Quit command
  already does from the Dock and from another program (ADR 048). With one, the menu's Quit runs
  that item's action;
- a role given to two items in one bar is the first one's, and the log says so once.

**The application's name** in those labels and in the bar's bold title is
`Backend.setApplicationName`'s, or the name of the application's package where it is packaged.
An unpackaged process shows the launcher's own name unless the name reaches the system before the
windowing library starts (§0); how late it may be given is to be measured while building, and
what cannot be done is declared in the guide.

**The labels the toolkit writes** (About, Settings, Services, Hide, Hide Others, Show All, Quit,
Window, Minimize, Zoom) are translated in the languages the toolkit carries, with the wording the
system uses in each.

## 4. The Window and Help menus are named, and a Window menu is given where none is

`MenuBar.setWindowMenu(Menu)` and `MenuBar.setHelpMenu(Menu)` name one of the bar's menus as the
one of that kind. In the window they change nothing. In the system's bar the menu is handed to
the system as such, and the system adds its own items to the application's: the windows' list and
the commands over them, the search field.

An application that names no Window menu is given one in the system's bar, before the Help menu
or last: Minimize, Zoom, and what the system adds. The bar an application has today carries one,
and with it Cmd-M and the list of windows, which nobody should lose by updating. A Help menu is
only the application's.

What the system adds is in the system's language only for an application packaged with that
language's resources (§0); the guide says how a package declares them.

## 5. The system runs an accelerator before the focused widget

An accelerator of an item in the system's bar is the system's: it is written beside the item as
the system writes it, and pressing it runs the item, whatever has the keyboard. The toolkit's own
route for a bar's accelerators, which asks the focused widget first, does not run for a bar the
system holds, so no chord runs twice.

This is the order of the system and the reverse of the order in the window, and the difference
is the developer's to know: **an Edit menu with Copy on Cmd-C takes Cmd-C from every text field
on macOS** (§0), and its action is what must copy. The guide says so where it teaches
accelerators, and the toolkit logs once, at the level of information, a bar that claims one of
the chords a text field uses.

The owner chose this over two alternatives that were priced: the toolkit asking the focused
widget before the bar, which makes the three systems alike at the cost of intercepting the key
ahead of the system; and roles for the six editing commands that act on the focused widget on
every system, which is the complete design and a feature of its own size. The second stays open
for an ADR of its own.

- An accelerator the system cannot write is shown without one, as in the tray's menu.
- A mnemonic and F10 belong to the bar in the window; the system's bar has the system's own keys.
- A disabled item shows its accelerator and does not run; whether the key then reaches the
  focused widget is to be measured while building, and recorded.

## 6. The window waits while a menu is open

While a menu of the system's bar is open the system holds the thread the toolkit draws on, and
the window is not drawn again until the menu closes: an animation, a video's picture and a
progress bar stand still, sound goes on. It is the limit ADR 058 declared for the tray's menu.
A menu is read from the application each time it opens, and the choice reaches the toolkit after
the system is done with the menu.

Drawing the window from inside the system's loop was priced and not taken: it runs the
application's code in the middle of a menu, for a second or two of stillness in an application
of forms and tables.

## 7. What a menu shows

As the tray's menu on macOS: an item's label, its icon, its accelerator, enabled, checked, a
separator and a submenu. The bar's direction follows the application's. An item's mnemonic is
not shown, the system having none.

## 8. What is not in this ADR

- A menu bar outside the window on Windows or on Linux. Neither system has one in common use.
- The Dock's menu and the Dock icon's badge.
- Roles for the editing commands (§5).
- Drawing while a menu is open (§6).

## 9. To be seen before this is accepted as implemented

On the macOS guest, each by a capture or by the screen reader's own words:

1. the Showcase's bar in the system's bar and none in the window; `IN_WINDOW` and the property
   putting it back;
2. About, Settings and Quit in the application's menu under the application's name, gone from
   the menus they were written in, each running its action;
3. the Window menu with the system's additions and the Help menu with its search, and the Window
   menu given to an application that named none;
4. two windows with different bars, the bar changing with the focus; a modal dialog and an owned
   palette;
5. an accelerator running once; a disabled item's accelerator;
6. an item's icon, check and submenu; a right-to-left language;
7. the bar read by the screen reader;
8. fullscreen, where the system hides and shows its bar;
9. the name of an unpackaged process, given before and after the first window.
