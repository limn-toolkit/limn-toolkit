# ADR 058: An application keeps an icon in the system's tray, and the system draws its menu

- **Status:** Proposed, 2026-10-11, with the owner's answers in §1 and the measurements of §3.
  Nothing is built.
- **Date:** 2026-10-11
- **Scope:** an icon an application keeps in the place each system has for one (the notification
  area on Windows, the menu bar's status items on macOS, the panel's tray on a Linux desktop), with
  a tooltip, a menu the system draws, an activation, and a state that asks for attention. One
  public type, `limn.desktop.Tray`; one type a backend implements, `limn.backend.spi.TrayPeer`.
- **Compatibility:** additive. A backend gains one method of the service-provider interface, and
  the toolkit's two are updated here.
- **Relates to:** [ADR 048](048-a-window-closes-by-its-policy-and-an-application-ends-by-asking-once.md)
  (a window that hides and a loop that outlives its windows, which are what "close to the tray" is
  made of), [ADR 056](056-an-application-hands-an-address-or-a-file-to-the-system-through-its-window.md)
  (whose limit on Linux §7 here lifts), [ADR 046](046-the-public-api-ends-where-a-module-says-and-a-backend-owes-what-the-spi-says.md).
- **First of theme 0.14**, desktop integration.

---

## 0. What exists

Nothing of a tray. What the backend already speaks is each system's door to one:

- **Linux:** its own D-Bus client, which exports objects and answers calls on them (the
  accessibility bridge), and calls services (the portals).
- **Windows:** a hidden window of its own with a window procedure (the clipboard, the system's
  preferences), and calls into the shell.
- **macOS:** messages to AppKit's classes.

`limn.components.Menu` is already a model apart from any drawing: items with a label, enabled,
checked, a separator, a submenu.

## 1. The owner's answers (2026-10-11)

Asked one decision at a time, each with its scope, what it costs and a recommendation.

1. **The route:** code of our own on the three systems, with no library. On Linux, the desktop's
   D-Bus protocol for status items.
2. **The menu:** the system's, on the three. Content of the toolkit's own in its place was asked
   for first and withdrawn after §3 showed where it cannot open: *the toolkit offers what the
   systems have in common*.
3. **A click:** one optional `onActivate`. Without it any click opens the menu.
4. **A desktop with no tray:** `isAvailable()` with a notice when it changes, and an optional
   stand-in the application asks for (§6).
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

By two probes with no window and no library, on the guests (the record and the pictures are in
the lab's folder for this date).

**Fedora 44, KDE, Wayland.** The item registered with the watcher at the first try; the panel
showed the icon and the tooltip and drew the menu: a disabled item, a check mark, separators, a
submenu. A click on an item arrives as an event with the item's number. Left click is `Activate`,
middle `SecondaryActivate`, right `ContextMenu` when the item has no menu, each with the pointer's
real place on the screen. Before each, the panel hands the item an **activation token**.

**Ubuntu 24.04, GNOME 46, X11.** With the shell's extension off, as the guest was, registering
fails with a plain "no such service". Turned on, the watcher appears within seconds and the icon
shows; the shell draws the menu. **Any single click opens the menu; `Activate` comes on a double
click only. An item with no menu is not shown at all.** A token is handed over here too. The
attention picture is shown when the item's status asks for attention. The extension introspects
the item, which must answer.

**Windows 11.** The icon is added by a number and by a GUID alike, and its rectangle is answered
at once; a new icon starts in the overflow. The system's popup menu has the current look. **With
applications set to dark, the whole system told of it and the task bar dark, that menu is light**;
after the theme library's export by ordinal 135 with "allow dark", then 136, it is dark. Neither
export has a name or a page. The system keeps the user's "always show" choice per icon under the
executable's path with the number, or under the GUID with the path.

**Not measured:** macOS; a real click on the Windows icon; the stand-in of §6.

## 4. The API

```java
Tray tray = Tray.create(backend, "orders");        // UI thread; the identifier is the application's
tray.setIcon(icon);                                // a picture in colour
tray.setMonochromeIcon(mask);                      // optional: a shape that takes the panel's colour
tray.setTooltip("Orders: 3 waiting");
tray.setMenu(menu);                                // a limn.components.Menu
tray.onActivate(() -> window.show());              // optional

tray.setAttention(true);                           // and setAttentionIcon(picture), optional
tray.isAvailable();                                // and onAvailabilityChange(listener)
tray.setStandIn(true);                             // §6
tray.dispose();
```

- **`limn.desktop`, and not `limn.backend`**, which the owner's seventh answer named: `limn.backend`
  imports nothing of `limn.components`, and a tray that takes a `Menu` would be the first. `Tray`
  reads the `Menu` and hands the peer rows of its own (number, label, enabled, checked, kind,
  children). The native menu bar of this theme will need the same reading.
- **Every call and every callback on the UI thread.** A menu's item runs its action there, as in a
  window.
- **The menu is read when it opens**, on the three systems, so an application changes its `Menu`
  as it does today and nothing observes it.
- **What an item can be** is what the three draw: a label, enabled, checked, a separator, a
  submenu. An accelerator and a mnemonic are not shown: no key reaches a tray's menu but the
  system's own.
- **While the system's menu is open on Windows and macOS the UI thread is inside it.** Windows keep
  receiving their messages; the toolkit's frames wait. Declared.
- **With no desktop** (headless, embedded) `isAvailable()` is `false` and every setter is kept
  and does nothing.

## 5. How each system is asked

| | the icon | the menu | activation | where the identity goes |
|---|---|---|---|---|
| **Windows** | `Shell_NotifyIconW` on a hidden window of the backend's, version 4; added again when the shell restarts | `TrackPopupMenuEx` at the place the shell gives | the left click | a number derived from the identifier |
| **macOS** | `NSStatusItem` | `NSMenu`, the item's own | a left click when `onActivate` is set, the menu then on the other button | the item's autosave name |
| **Linux** | an exported object of the status-item interface, registered with the watcher | an exported object of the menu interface, drawn by the panel | `Activate` | the item's `Id` |

- **Windows, a number and not a GUID.** By the system's documentation, and not measured here, a
  GUID is tied to the executable's path and the icon is refused when the path changes, which a
  Java runtime's does on every update. A number
  derived from the identifier keeps two applications on one runtime apart, which is what the
  identifier is for there.
- **Windows, dark.** At the first tray, the backend looks the two exports up by ordinal and, when
  both are there, declares "allow dark". Absent, or with `-Dlimn.tray.darkMenus=false`, the menu is
  light as the system draws it. The declaration is the process's, so every native menu in it
  follows.
- **Linux, the picture** goes as pixels at three sizes, never as a name of an icon theme: an
  application's icon is not in the user's theme.
- **Linux, the menu always exists**, with one disabled row of the tooltip's text when the
  application set none: an item with no menu is not shown on GNOME.
- **macOS** is written from the system's documentation and seen on the guest before this is
  accepted.

## 6. A desktop with no tray

Windows and macOS always have the place. A Linux desktop has it when a watcher and a host are on
the bus; GNOME alone has neither, and an extension most distributions ship or offer brings both.

- `isAvailable()` is whether an icon is being shown to somebody. The backend watches the watcher's
  name: an item made before the host exists is registered when it appears, and
  `onAvailabilityChange` says so both ways.
- **The stand-in**, off unless asked: where no host exists, a small window of the toolkit's,
  minimized, whose entry in the desktop's dock or task bar is the icon. Activated, it shows the
  menu inside itself, drawn by the toolkit, and minimizes again when the menu closes. It follows
  an application the owner wrote this way, with one change: the menu is in the window, and not at
  the pointer, whose place a Wayland client is not told. **To be measured before it is built**:
  whether a client learns it was brought back, under X11 and under Wayland, on GNOME and on KDE.
  If it does not on some desktop, the stand-in is declared absent there and `isAvailable()`
  stays `false`.
- The guide says what an application does with `false`: minimize where it would hide, and keep the
  menu's commands in the window's own menu.

## 7. Activation, and what the token is for

- **`onActivate` is the click that means "this application".** Windows: the left click. KDE: the
  left click. GNOME's extension: a double click, the single one opening the menu. macOS: the left
  click, when set. Declared: there are panels that never send it, so what it does must also be an
  item of the menu.
- **The token.** On Linux the panel hands the item the user's consent to bring a window in front,
  before the activation and before a menu's item is chosen. The backend keeps the last one for
  the length of the callback, and a window shown or raised inside it uses it: under Wayland by
  the activation protocol, under X11 as the startup identifier, each to be seen bringing a window
  in front before it is relied on. This is what ADR 056 §5 declared
  missing; the same route, with a token the backend asks for from a window's own press, is that
  ADR's follow-up in this theme and is not decided here.

## 8. Monochrome, and attention

- **A monochrome icon** is a picture whose alpha is the shape. macOS takes it as a template and
  colours it. On Windows the backend tints it for the task bar, light or dark, from the system's
  preference it already reads, and again when that changes. On Linux the protocol does not say the
  panel's colour, so the icon in colour is shown; an application that sets only the monochrome
  one gets it in a mid grey there. Declared.
- **Attention** is one state. The picture is `setAttentionIcon`'s, or else the current icon with
  a dot at its top trailing corner, composed by the toolkit, over the tinted shape when the icon is
  monochrome, which an application could not compose since it does not know the tint. Linux: the
  item's status asks for attention with that picture, and a panel that hides idle items brings it
  out. Windows and macOS: the picture is swapped; an icon the user left in the overflow stays
  there.
- **Left out of the protocol:** the passive status and the overlay picture, which only Linux has.

## 9. What is not done

- **Content of the application's own in place of the menu.** Where it could open at the icon it
  would be a different thing on each system, and on two desktops it could not open there at all.
- **A balloon or a message from the icon.** That is this theme's notifications.
- **Raw clicks, the wheel, the middle button.**
- **An animated icon.** An application calls `setIcon` from a timer.
- **The X11 embedding protocol**, for a desktop with no status-item host.

## 10. Tests

- `Tray` over a recording peer: the rows a `Menu` becomes, that it is read when opened, an item's
  action on the UI thread, attention's composed picture over a coloured and a monochrome icon,
  availability both ways, the identifier's rules, `dispose`.
- The Linux item and menu objects against a bus in the test, as the accessibility bridge's are:
  the properties, the layout, an event that runs an action, the reply to introspection.
- The Windows structure's layout and the number derived from an identifier.
- Seen on the four guests, with a real click on each, before this is accepted.

## 11. Steps

1. `Tray`, `TrayPeer`, the rows, the composed attention picture, the recording peer and its tests.
2. Linux: the item, the menu, the watcher coming and going, the token kept.
3. Windows: the icon, the menu, the tint, the dark declaration.
4. macOS.
5. The stand-in: measured, then built or declared absent per desktop.
6. The demo's icon, the guide with the "close to the tray" recipe, the changelog.
