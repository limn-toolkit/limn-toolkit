# ADR 058: An application keeps an icon in the system's tray, and the system draws its menu

- **Status:** Accepted and implemented, 2026-10-11, with the owner's answers in §1, the
  measurements of §3, the corrections of a second reading (§13), and §15: what was built, where it
  departs from the text above it, what was seen on the guests and what was not.
- **Date:** 2026-10-11
- **Scope:** an icon an application keeps in the place each system has for one (the notification
  area on Windows, the menu bar's status items on macOS, the panel's tray on a Linux desktop), with
  a title, a tooltip, a menu the system draws, an activation, and a state that asks for attention.
  One public type, `limn.desktop.Tray`; one type a backend implements, `limn.backend.spi.TrayPeer`.
- **Compatibility:** additive. A backend gains one method of the service-provider interface, and
  the toolkit's two are updated here.
- **Relates to:** [ADR 048](048-a-window-closes-by-its-policy-and-an-application-ends-by-asking-once.md)
  (a window that hides and a loop that outlives its windows, which are what "close to the tray" is
  made of), [ADR 056](056-an-application-hands-an-address-or-a-file-to-the-system-through-its-window.md)
  (whose limit on Linux §7 here bears on), [ADR 046](046-the-public-api-ends-where-a-module-says-and-a-backend-owes-what-the-spi-says.md).
- **First of theme 0.14**, desktop integration.

Each statement about a system below is one of three kinds, and says which where it matters:
**measured** on a guest (§3), **read** in the system's documentation or in the source of the
panel named, or **to be measured** (§12).

---

## 0. What exists

Nothing of a tray. What the backend already speaks is each system's door to one:

- **Linux:** its own D-Bus client, which exports objects and answers calls on them (the
  accessibility bridge), and calls services (the portals). Its handlers run on the connection's
  reader thread, and a connection with threads drops the signals it receives; the watches that
  need signals each keep a connection read by one thread.
- **Windows:** a hidden window with a window procedure (the clipboard, the system's preferences),
  calls into the shell, and a thread with a message pump of its own (the file dialogs).
- **macOS:** messages to AppKit's classes, with typed calls where an argument is not as wide as
  a pointer, and classes made at run time for a target and its action.

`limn.components.Menu` is a model apart from any drawing: items with a label, enabled, checked, a
separator, a submenu. What kind an item is, its submenu and running its action are readable inside
its package only.

## 1. The owner's answers (2026-10-11)

Asked one decision at a time, each with its scope, what it costs and a recommendation.

1. **The route:** code of our own on the three systems, with no library. On Linux, the desktop's
   D-Bus protocol for status items.
2. **The menu:** the system's, on the three. Content of the toolkit's own in its place was asked
   for first and withdrawn after §3 showed where it cannot open: *the toolkit offers what the
   systems have in common*.
3. **A click:** one optional `onActivate`. Without it any click opens the menu.
4. **A desktop with no tray:** `isAvailable()` with a notice when it changes. A stand-in, a
   minimized window whose dock entry is the icon, was asked for, measured (§3) and taken out of
   the toolkit: the guide carries it as a recipe for an application with no main window.
5. **"Close to the tray":** a recipe in the guide over ADR 048, no method of its own.
6. **Also in:** a monochrome icon that takes the panel's colour, more than one icon, and an
   attention state whose picture the toolkit composes.
7. **Where:** made from the backend with an identifier the application chooses.
8. **Windows, a dark system:** the process declares that it accepts dark menus by the call §3
   found, guarded, with a system property that turns the call off.

## 2. What others do

Read on 2026-10-11, named by kind.

- **A Java tray library** loads GTK 2 or 3 and an app-indicator library into the process and falls
  back to Swing; its own notes list a special case per distribution, version conflicts with other
  toolkits' GTK, and callbacks on a thread of its own. It brings AWT and a second event loop.
- **A small C tray library** uses GTK with an app-indicator library on Linux; a fork of it uses a
  large C++ toolkit there instead. Its interface is the useful part: an icon, a menu of items with
  checked, disabled and submenu, and nothing else.
- **A windowing library, a browser engine and a C++ toolkit** each moved to speaking the D-Bus
  protocol themselves, and the first two dropped GTK and the app-indicator library for it. The
  app-indicator libraries are marked obsolete by a major distribution, and GTK 4 has no status icon.
- **The JDK's own tray** uses the X11 embedding protocol, answers "not supported" on older GNOME,
  and has no place to embed under Wayland.

## 3. Measured

By three probes with no toolkit window, on the guests; the record and the pictures are in the
lab's folder for this date. Only these sessions were looked at: KDE under Wayland, GNOME under X11,
Windows 11. Not GNOME under Wayland, KDE under X11, another panel, Windows 10, or macOS.

**Fedora 44, KDE, Wayland.** The item registered with the watcher by its bus name at the first
try; the panel showed the icon and the tooltip and drew the menu: a disabled item, a check mark,
separators, a submenu. A click on the check item arrived as an event with the item's number, and
after the menu's "layout updated" signal the panel asked for the layout again. With the item
saying it is not only a menu: left click is `Activate`, middle `SecondaryActivate`, right
`ContextMenu` when the item has no menu, each with the pointer's real place on the screen. Before
each of those three, and before the click on the item, the panel handed the item an **activation
token**.

**Ubuntu 24.04, GNOME 46, X11.** With the shell's extension off, as the guest was, registering
fails with a plain "no such service". Turned on, the watcher appears within seconds and the icon
shows; the shell draws the menu. **A single click, left or right, opens the menu; `Activate`
comes on a double click only. An item with no menu is not shown at all.** A token was handed over
before the double click and before the middle click. The attention picture is shown when the
item starts with a status that asks for attention. The extension asked the item to introspect
itself; the probe did not answer and was shown all the same.

**Windows 11.** The icon is added by a number and by a GUID alike, and its rectangle is answered
at once; a new icon starts in the overflow, so the rectangle is the overflow's arrow. The system's
popup menu, opened by the probe at that place, has the current look. **With applications set to
dark, the whole system told of it and the task bar dark, that menu is light**; after the theme
library's export by ordinal 135 with "allow dark", then 136, it is dark. The system keeps the
user's "always show" choice per icon under the executable's path with the number, or under the GUID
with the path. No callback from the icon was received: nothing clicked it.

**The stand-in**, a small window that minimizes itself beside a main one, in one process:

| | GNOME, X11 | KDE, Wayland |
|---|---|---|
| alone, its dock entry clicked | told it was restored, and focused | told only that it has the focus |
| with the main window open | **one entry for the two**; a click opens the picker of the application's windows and tells the client nothing | **one entry for the two**; a click goes to the other window |
| the entry's picture | the desktop's generic one | the same |

So it is an icon only while it is the application's one window, where it is what a minimized main
window already is.

## 4. The API

```java
Tray tray = Tray.create(backend, "orders");        // UI thread; the identifier is the application's
tray.setTitle("Orders");                           // the name a panel and a reader say; the application's name by default
tray.setIcon(icon);                                // a limn.graphics.Icon, shown as it is
tray.setMonochromeIcon(shape);                     // optional: a tintable Icon that takes the panel's colour
tray.setTooltip("Orders: 3 waiting");              // a String or an I18nString
tray.setMenu(menu);                                // a limn.components.Menu
tray.onActivate(() -> window.show());              // optional

tray.setAttention(true);                           // and setAttentionIcon(icon), optional
tray.isAvailable();                                // and onAvailabilityChange(listener)
tray.dispose();
```

- **`limn.desktop`, and not `limn.backend`**, which the owner's seventh answer named: `limn.backend`
  imports nothing of `limn.components`, and a tray that takes a `Menu` would be the first. It is
  still made from the backend, with the identifier.
- **How `Tray` reads a `Menu`.** Through an accessor `limn.components` hands to a package of the
  module that is not exported; no public method is added to `Menu` or `MenuItem`. `Tray` turns the
  menu into **rows** of its own for the peer: a number, a label, enabled, checked, a kind,
  children. Reading, it refuses a menu that contains itself, gives an item that appears twice two
  numbers, drops a separator that is first, last or after another, and escapes what each system
  reads as a marker in a label (an underscore on Linux, an ampersand and a tab on Windows).
- **Every call on the UI thread, and every callback there.** A menu's item runs its action on the
  UI thread, as in a window.
- **The menu is read each time it is about to open**, on the UI thread, and the rows go to the
  system. An application changes its `Menu` as it does today and nothing observes it. How each
  system is made to ask again is in §5; on Linux it is **to be measured**, since both panels read
  keep the layout they were given.
- **What an item can be** is what the three draw: a label, enabled, checked, a separator, a
  submenu. An accelerator is not shown, a tray's menu having no window whose keys would run it. A
  mnemonic is passed where the system has one.
- **Text and direction.** A label or a tooltip from an `I18nString` is read in the current
  language each time; the menu is laid out in the application's direction where the system takes
  one.
- **`isAvailable()` means the system took the icon**, and not that somebody sees it: no system
  says that. On Windows a new icon starts in the overflow, and on Linux both panels read answer
  "a host is registered" with a constant. Its exact meaning per system is in §6.
- **A tray does not keep the loop alive.** With `LoopLifetime.UNTIL_LAST_WINDOW` an application
  with an icon and no window ends; the recipe sets `UNTIL_QUIT`. Closing the backend disposes
  every tray.
- **With no desktop** (headless, embedded) `isAvailable()` is `false` and every setter is kept
  and does nothing.

## 5. How each system is asked

### 5.1 Linux

- **One connection to the session bus per tray**, closed by `dispose`. The protocol has no call
  that takes an item away: both panels read drop an item when its bus name leaves. Two icons are
  two names. Each connection is a reader and a writer thread.
- **The item** is an object at the path the protocol names, registered with the watcher by the
  connection's name (measured). It serves the properties and sends the change signals for the icon,
  the attention icon, the tooltip, the title and the status; KDE's panel answers each signal by
  reading every property again (read), so a picture is marshalled as bytes and not item by item.
- **The picture** goes as pixels at 16, 22, 24, 32, 48 and 64, each asked of the `Icon`, never as
  a name: an icon by name needs a file where the panel can read it, which a sandboxed application
  cannot promise.
- **The menu** is an object of the menu interface. It always exists, with one disabled row of the
  title when the application set none: GNOME shows no item without one (measured). "About to
  show" hops to the UI thread, reads the `Menu` again, answers that the layout changed and bumps
  its revision; **to be measured** on both panels, at the top level and in a submenu, that a label
  and a check changed between two openings with no signal are shown.
- **The UI thread never makes a blocking call on a tray's connection**, since the reader may be
  waiting for the UI thread at that moment. What the tray sends from the UI thread it sends
  without waiting for an answer. The reader's wait for the UI thread is bounded; past it, the
  panel gets the last rows.
- **What a click means is said when the item registers.** Without `onActivate`, the item says it
  is a menu and its introspection lists no activation, and both panels open the menu on any
  click (read; to be measured). With it, the item lists `Activate`: KDE's left click activates and
  its right click opens the menu (measured); GNOME's extension waits the double-click time on
  every left click before opening the menu (read), and activates on the double click (measured).
  Setting or clearing `onActivate` on a shown tray registers the item again.
- **Not shown on GNOME's extension:** the tooltip (read).
- **A bus this client cannot reach** (an address that is not a socket's path) is no tray.

### 5.2 Windows

- **A thread of the backend's for the trays**, with a hidden top-level window per tray and a
  message pump, as the file dialogs have theirs. A window that only receives messages is not told
  when the shell restarts. The icon is added from that thread and added again on that broadcast.
- **The menu opens on that thread.** The UI thread reads the `Menu` into rows when the shell's
  callback asks for the menu, the tray's thread builds the system's popup from them and tracks it,
  and the number chosen is posted back to the UI thread. The toolkit's frames, timers and
  animations go on meanwhile. Before tracking, the hidden window is made the foreground one, and a
  null message follows, as the system's documentation requires for the menu to close on a click
  elsewhere; **to be measured**: whether the toolkit's own window, losing the foreground, closes
  its popups, and where the focus is afterwards.
- **The callbacks** are version 4's: the menu on the context-menu message, by pointer or
  keyboard; activation on the select messages, by pointer or keyboard. **To be measured**, none
  having been received yet.
- **Identity.** A number of sixteen bits derived from the identifier, which is what the callback
  can carry (read), and what the system keys the user's choice by, with the executable's path
  (measured). Two identifiers of one process that derive the same number take the next free one.
  Not a GUID: by the documentation a GUID is tied to the executable's path and the icon refused
  when the path changes, which a Java runtime's does on every update, and a GUID is one icon for
  the whole system, so a second instance of the application would be refused.
- **The picture** is asked of the `Icon` at the small icon's size for the task bar's monitor.
- **The tooltip** is cut at 127 characters, the structure's limit (read), and is also what a
  screen reader says of the icon; with none set, the title is used.
- **Dark menus.** Ordinal 135 of the theme library is one function before Windows 10's build
  18362 and another from it on (read). So the guard is the build number: from 18362 the backend
  calls it with "allow dark" and then 136; below, or with `-Dlimn.windows.darkMenus=false`,
  nothing is called and the menu is light as the system draws it. It is done when the backend
  starts, and not at the first tray, so that the process looks one way whether or not it has an
  icon. The declaration is the process's: **to be measured** what else it changes, on the file
  chooser, the driver dialog and a window's system menu, and that a switch between light and dark
  while running is followed.
- **A process that ends without disposing** leaves its icon until the pointer passes over it;
  the backend removes its icons when it closes, and a shutdown hook removes them when the virtual
  machine ends otherwise.

### 5.3 macOS

From the system's documentation and from memory, with nothing measured; every line here is in
§12.

- **`NSStatusItem`** with the identifier as its autosave name, made on the main thread, which is
  the UI thread.
- **The menu** is an `NSMenu` whose delegate reads the `Menu` when the menu is about to open.
  While it is open the main thread is inside the system's tracking loop, which every application's
  is; to be measured whether the toolkit's frames and posted tasks are served during it.
- **Activation.** An item with a menu opens it on any click, and an item with an action has no
  menu. With `onActivate`, the item has an action that looks at the click: the main button runs
  `onActivate`, the other one, or the main one with Control, opens the menu from the item's
  button. A reader's activation of the item runs the action, so with `onActivate` the menu must
  be reachable by a reader some other way, or `onActivate` is not offered on macOS; to be measured.
- **Calls with a float or a rectangle** go through typed calls, as the accessibility bridge's do.

## 6. What `isAvailable()` is, and a desktop with no tray

| | `true` when |
|---|---|
| **Windows** | the shell took the icon; `false` while there is no shell to take it (at logon, with a replaced shell), and tried again on the shell's broadcast |
| **macOS** | always, the system giving no answer |
| **Linux** | a watcher owns its name on the bus and took the registration |

- On Linux the backend watches the watcher's name: an item made before a watcher exists is
  registered when one appears, and `onAvailabilityChange` says so both ways (the appearing was
  measured on GNOME; the listener is to be built). A watcher with no panel showing items cannot be
  told from one with, on either panel read.
- The guide says what an application does with `false`: minimize where it would hide, and keep the
  menu's commands in the window's own menu.
- **For an application with no main window**, the guide has a recipe, compiled, for the stand-in
  the owner's own agent uses: one small window, minimized, that shows a menu when the desktop
  gives it the focus. It says what §3 found: it is an entry of its own only while it is the
  application's one window, and its picture is the installed desktop file's.

## 7. Activation, and the token

- **`onActivate` is the click that means "this application".** Windows: the select message. KDE:
  the left click. GNOME's extension: a double click. macOS: the main button, if §5.3 holds.
  Declared: there are panels that never send it, so what it does must also be an item of the menu.
- **The token.** On Linux both panels hand the item the user's consent to bring a window in
  front, before an activation and, on KDE under Wayland, before a menu's item is chosen (measured;
  GNOME before a menu's item, and KDE under X11, not). GLFW cannot use one: the backend would bind
  the Wayland activation protocol itself, whose interface the system's library does not export,
  and under X11 send the activation request with the token's time. A window shown in the callback
  is not on screen until after it returns, so a token would be kept until that window is. None
  of this is decided here: it is the follow-up of ADR 056 in this theme, which this ADR gives its
  first source of tokens. Until then a window shown from `onActivate` comes in front on Windows and
  macOS, and on Linux as the desktop allows. **To be measured** first: that a token nobody uses
  leaves no busy pointer on GNOME 46.

## 8. Monochrome, and attention

- **A monochrome icon** is a tintable `Icon`. macOS takes it as a template and colours it.
  On Windows the backend tints it light or dark for the task bar, from the *system's* light or
  dark setting, which is not the applications' one the backend reads today, and again when it
  changes; under high contrast it takes the system's text colour. On Linux the protocol does not
  say the panel's colour: the icon set by `setIcon` is shown, and an application that set only the
  monochrome one gets it in a mid grey. Declared.
- **Attention** is one state. The picture is `setAttentionIcon`'s, or else the current icon with
  a dot at its top trailing corner, composed by the toolkit. Over a monochrome icon the toolkit
  composes the tinted shape and the dot into one picture, which on macOS is then not a template
  and is tinted by the toolkit for the menu bar's appearance; to be measured. Linux: the item's
  status asks for attention with that picture (measured on GNOME, at start); whether a panel that
  hides idle items brings such an item out, and the state turned on and off while shown, are to
  be measured. Windows and macOS:
  the picture is swapped; an icon the user left in the overflow stays there.
- **Left out of the protocol:** the passive status and the overlay picture, which only Linux has.

## 9. Screen readers

The icon's name is its title, and its tooltip where the system reads that. The menu is the
system's and read by the system's reader as any menu of it. The toolkit's own accessibility bridge
has no part in a tray. **To be measured**: that each guest's reader says the name and reaches the
menu, by its keys.

## 10. What is not done

- **Content of the application's own in place of the menu.** Where it could open at the icon it
  would be a different thing on each system, and on two desktops it could not open there at all.
- **A stand-in for a desktop with no tray**, in the toolkit (§6).
- **A balloon or a message from the icon.** That is this theme's notifications.
- **Raw clicks, the wheel, the middle button.**
- **An animated icon.** An application calls `setIcon` from a timer, and each call sends the
  pictures again.
- **The X11 embedding protocol**, for a desktop with no status-item host.
- **A second instance of one application** is a second icon; an application that wants one icon
  keeps one instance.

## 11. Tests

- `Tray` over a recording peer: the rows a `Menu` becomes (a cycle refused, an item twice,
  separators collapsed, labels escaped per system), that it is read each time it opens, an item's
  action on the UI thread, attention's composed picture over a coloured and a monochrome icon,
  availability both ways, the identifier's rules, the title's default, `dispose`, the backend's
  close.
- The Linux item and menu objects against a bus in the test, as the accessibility bridge's are:
  the properties, the signals, the layout and its revision, "about to show", an event that runs an
  action, introspection with and without activation, two trays on two connections, `dispose`.
- The Windows structure's layout, the number derived from an identifier and a collision, the
  guard by build number.
- §12, on the guests.

## 12. Seen on a guest before this is accepted

1. **Linux, both panels:** a menu changed between two openings; two icons; `dispose` while
   running; the icon, tooltip and attention changed while shown; the click with and without
   `onActivate`, and GNOME's delay; a token left unused.
2. **Windows:** a real click and a key on the icon; a process killed with its icon shown; the menu from the tray's thread while the
   toolkit's window animates, and that window's popups and focus; the tint with a dark task bar
   and light applications; the dark declaration's other effects; the shell restarted.
3. **macOS:** everything in §5.3 and the composed attention picture.
4. **Readers:** the name and the menu, on the four.
5. **Sessions not yet looked at:** GNOME under Wayland, KDE under X11.

## 13. What a second reading changed (2026-10-11)

The proposal was read against the panels' sources and the systems' documentation by a reviewer
told to find what was wrong. What it changed: what `isAvailable()` means (§4, §6); a connection
per tray (§5.1); the menu's freshness as something to measure, with the threading rule (§5.1);
what a click means being said at registration, and GNOME's introspection, which the first text
had wrong (§3, §5.1); the Windows menu on a thread of its own, where the first text had the UI
thread wait (§5.2); sixteen bits of identity (§5.2); the dark guard by build number and at the
backend's start (§5.2); the tint from the system's setting (§8); the token's cost and life (§7);
macOS's activation as an open point (§5.3); how a `Menu` is read from another package, and what
reading it must guard (§4); a title, the tooltip's limits and readers (§4, §9); the stand-in
out of the toolkit (§6, with the owner's word after §3's measurement).

## 14. Steps

1. `Tray`, `TrayPeer`, the rows and the accessor, the composed attention picture, the recording
   peer and its tests.
2. Linux: the item, the menu, the signals, the watcher coming and going.
3. Windows: the thread, the icon, the menu, the tint, the dark declaration.
4. macOS, measured as it is built.
5. The demo's icon, the guide with the "close to the tray" recipe and the stand-in's, the
   changelog.

## 15. As built and measured (2026-10-11)

Built in the five steps of §14. Where what was built departs from the text above:

- **The title is the identifier until set** (§4): a backend has no reading of the application's
  name to default to.
- **`setLayoutDirection`** is on `Tray` (§4): a scene has a direction and a tray is in none, so
  the application says it.
- **Each backend escapes a label** for its system, not `Tray` (§4): the rows carry the text as
  written and the access letter beside it.
- **`BackendPeer.createTray` has a default**, the peer that shows nothing, which is the right
  answer of a backend with no desktop; only the toolkit's desktop backend overrides it.
- **Windows, a thread per tray** (§5.2), each with its window, and not one thread for all.
- **Linux, the sizes** are 16, 22, 24, 32, 44, 48, 64 and 96 (§5.1), after the owner asked that
  high-density screens be served: each is drawn for its size.
- **Linux, a monochrome icon alone follows the panel where there is a way** (§8). The owner
  asked that it go from black to white with the system; the desktop's light or dark setting
  turned out not to be the panel's colour (below). On GNOME, whose bar is dark in both settings,
  it is white. On KDE the panel colours it itself: the shape is written as a file in the
  session's runtime folder, a rectangle in the panel's text colour seen through the shape as a
  mask, and the item names it, with the attention dot a circle of its own colour over it. Not
  inside a sandbox, where the panel could not read the folder. Elsewhere it is the grey.
- **With no tray, the recipe lets the close end the application** (§6), where the text said
  "minimize": a window could not minimize itself. `NativeWindow.minimize()` was then added, with
  a default that does nothing, for the recipe of an application with no main window, which shows
  its commands in a small window and minimizes it again; that recipe needs no telling that the
  window was brought back.

**Seen on the guests, with a real click each:**

| | KDE, Wayland | GNOME, X11 | Windows 11 | macOS |
|---|---|---|---|---|
| the icon, the tooltip, the menu | yes | yes | yes | yes |
| an item chosen runs its action | yes | not clicked | yes | yes |
| the menu changed between two openings, no signal | yes | yes | yes | yes |
| with no `onActivate`, a left click opens the menu | yes | yes | yes | yes |
| with it, the click activates and the window comes in front | activates; in front not looked at | not clicked | yes, in front | activates; in front not looked at |
| with it, the other button opens the menu | measured by the probe | measured by the probe | not clicked | yes |
| the tray appearing and going while running | not tried | yes, both ways | the shell restarted: the icon is back | has none to lose |
| two icons, and one disposed | yes | yes | not tried | not tried |
| the monochrome icon | the panel's own white, coloured by the panel | white | black on a light bar, white on a dark one, changed while running | white over a dark picture, black over a light one |
| the attention dot | yes | yes | yes, over the tinted shape | yes, over the tinted shape, following the bar both ways |
| the scale | 100 %, and sharp at 200 % | 100 % | 250 %, sharp | 200 %, sharp |

- **Windows, the menu open:** a change scripted for the eighth second was applied with the menu
  open since the fifth, the tooltip showing the new text beside the open menu: the UI thread
  runs while the system tracks the menu.
- **Windows, dark:** with the system switched to dark while the application ran, the menu was
  dark and the monochrome icon white; back to light, both followed.
- **The panel's colour is not the desktop's setting.** On both Linux guests the system was set
  to light (the portal's colour scheme says so on KDE, and GNOME's setting is its default) and
  the panel was dark. So a monochrome icon cannot follow the setting there.
- **macOS, the bar's appearance arrives late.** A picture drawn by the toolkit when the item was
  made was black over a dark bar; the item's effective appearance is observed, and the picture
  drawn again when it changes.

**Seen after the first account of this section:**

- **The name a reader says** is there on three systems, read through each system's own
  accessibility interface and not by a reader's voice: on macOS the status item's description
  is the title and its help the tooltip; on Windows the task bar has a button named by the
  tooltip; on GNOME the shell has a button and a menu named by the title.
- **Windows, the icon invoked as a reader invokes it** opens the menu, with the access letters
  underlined as for a keyboard.
- **GNOME's wait:** with no activation the menu opened 56 ms after the click; with one, 458 ms,
  the desktop's double-click time being 400.
- **Windows, the process killed** with its icon shown: the icon was gone from the task bar two
  seconds later, with nothing passing over it.
- **macOS, the menu open:** a task due at the eighth second ran at the fourteenth, when the menu
  opened at the fifth was closed. The toolkit waits there, as §5.3 declared.

**Seen in a third pass:**

- **A reader's voice, on Windows.** With the task bar's button focused the reader said the
  title, "button, 1 of 1"; Enter opened the menu, announced as a context menu; the arrows read
  "Open the window", "Copy, unavailable", "Bold, checked", "Export, submenu", each with its
  access letter; and Escape put the focus back on the icon, which was said again.
- **The dark declaration's other effect.** On a dark system the window's own system menu, the
  one of its title bar, is dark with the declaration and light with the property turned off. So
  it does what it says for every menu the system draws for the process, which is the reason it
  is made at the backend's start.
- **A token nobody uses leaves no busy pointer** on GNOME 46 under X11: the pointer's shape,
  read from the server once a second for eight seconds after an activation, stayed the default.
- **An X11 client on KDE:** the toolkit run through XWayland on the KDE guest showed the icon,
  and its activation brought the window in front and made it the active one.

- **The file chooser under the declaration** on a dark system is the system's own dark one, as it
  is without it.

**Added at the owner's asking, after he saw the menus (2026-10-11):**

- **An item's icon.** `MenuItem.setIcon`, which the toolkit's own menus draw before the label
  and a tray's rows carry to the system: a picture beside the item on Windows, on macOS, where a
  tintable one is a template, and through the menu protocol on Linux, where a tintable one is
  the grey, the protocol saying a menu's colour no more than a panel's. Seen on the four guests;
  GNOME puts it at the trailing side, as it does its own.
- **An item's accelerator**, which §4 had left out for want of a window whose keys would run
  it. The owner showed a status menu of the macOS host with accelerators beside its items. A
  row now carries the key and its modifiers: on macOS they are the item's key equivalent, which
  the system writes and, while the menu is open, runs; on Windows they are the words in the
  menu's second column and on Linux the protocol's shortcut, both shown and neither run. Seen
  on macOS, Windows and KDE; GNOME's menu does not show it. That the key runs the item on macOS
  is read, not pressed.
- **A window's states.** `NativeWindow.maximize()`, `restore()`, `isMinimized()`,
  `isMaximized()` and `toFront()` beside `minimize()`, and `observeState`, which tells a
  listener each change with a `WindowState`: shown, minimized, maximized, fullscreen and the
  bounds, place and size as one rectangle. Each has a default; the recipes bring a window with
  `toFront()`. A window walked through maximize, restore, minimize, toFront, hide, toFront, a
  new size and a new place on the four guests, and the listener was told each: all of it on
  GNOME under X11, on Windows and on macOS; under Wayland on KDE neither the minimizing nor the
  place, which a client is not told there. Two reports were put right as they came: Windows
  gives a minimized window no size at a place far off the screen, and macOS says one is not
  shown, so a minimized window's state keeps the bounds it comes back to and is a shown one.
- **A reader's voice on macOS and on Linux.** VoiceOver, taken to the status menus, said
  "Limn Showcase, status menu", and with the item pressed through accessibility said "menu" and
  then, arrow by arrow, "Preferences" with its accelerator, "Copy, dimmed", "Bold" with its
  check and "Export, submenu". Orca said "Limn Showcase push button" with the icon focused and,
  with the menu open, "Open the window", "Preferences", "Bold, checked", "Export, collapsed",
  "Count this opening" and "Quit", passing over the disabled item as it does in any menu of the
  shell.

- **The driver dialog under the declaration** on a dark system, shown by its probe with the
  declaration made and without: the same light dialog both times. The declaration does not
  reach it.

**Plasma under X11, seen without logging anybody out.** On the KDE guest, on an X server and a
bus of their own beside the running session: the window manager for X11, the daemon that watches
status items and the Plasma shell with its panel, and the toolkit as their X11 client. The
watcher and a host were on that bus; the icon was in the panel with its attention dot, on a
light panel this time; the other button opened the menu, and the main one sent the activation
with the pointer's place, which the application heard. It is the desktop's own programs for
X11, not a login to its X11 session.

**Not seen, and still owed to §12:**

- A GNOME session under Wayland. It needs somebody to log in to it on the guest, which ends
  what runs in the session there now. Two ways round were tried and neither is a session: a
  shell nested in the X11 one, and a shell with no screen on a bus of its own. In both the
  shell came up with its watcher, and the toolkit never got as far as a tray: its windowing
  library did not return from starting, waiting in its first round trip for an answer the
  compositor never sent (read from the process's native stack). Why such a compositor does not
  answer was not found; a real session's does, as the KDE guest's does every day. What stands in meanwhile is the tray on GNOME under X11, which is the same bus and the
  same extension under either session.
