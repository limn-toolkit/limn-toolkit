# ADR 059: On Linux a window comes in front with the proof that the user asked

- **Status:** Accepted and implemented, 2026-10-11, at the owner's word after ADR 058's
  measurement of the real Plasma session under X11.
- **Date:** 2026-10-11
- **Scope:** two things that left a window behind on a Linux desktop: a window of the
  application's asked in front from its tray icon, and a program the launcher starts or hands an
  address to. No public API: `toFront()`, `focus()` and `launcher()` do what they said.
- **Compatibility:** none to keep; behaviour only.
- **Closes:** what [ADR 056](056-an-application-hands-an-address-or-a-file-to-the-system-through-its-window.md)
  §5 declared for a program already running, and what
  [ADR 058](058-an-application-keeps-an-icon-in-the-systems-tray-and-the-system-draws-its-menu.md)
  §7 left to a follow-up.

---

## 0. What was wrong

A Linux desktop decides which window is in front, and lets a program change it only with proof
that the user asked. The proof has two spellings:

- **Under X11, a time.** A request to activate a window carries the time of the user's action,
  and a window manager that guards the focus compares it with the time the user last acted in the
  window that is active. The windowing library sends the request with no time.
- **Under Wayland, a token.** The compositor issues one for a user's action, and whoever holds
  it may ask for a surface to come in front. The windowing library asks for one of its own with
  nothing to vouch for it.

Measured on the four sessions the guests have, each with another program's window made the
active one first, and each with the proof turned off by a property and on:

| | a window of ours, from its tray icon | a browser already running, from the launcher |
|---|---|---|
| **GNOME, X11** | in front without the proof, and with it | in front without it, and with it |
| **KDE, Wayland** | in front without it, and with it | **behind without it**, in front with it |
| **KDE, X11** | **behind without it**, in front with it | **behind without it**, in front with it |
| **GNOME, Wayland** | **behind without it**, in front with it | **behind without it**, in front with it |

GNOME's window manager under X11 takes a request with no time as one made now, and KDE's
compositor grants the windowing library's own token for a window of the same client; the other
six cells needed the proof.

## 1. Where the proof comes from

- **From a panel**, for a click on the tray icon or on an item of its menu: both panels hand the
  item a token before the click (ADR 058 §3). Under Wayland it is the compositor's token. Under
  X11 it is a startup identifier that ends with the time of the click, where the panel gives one;
  where it gives none the server's time now is read, the user having acted a moment ago.
- **From the compositor**, for a press in a window of ours that starts a program: the backend
  asks for a token with the serial of the last key or button it was given in that window, which
  is the compositor's own record that the user acted there. Under X11 the backend writes a
  startup identifier of its own with the server's time.

## 2. Where it goes

- **A window of ours.** The tray tells the backend that the user acted, with the token, just
  before the application hears of the click. A window brought in front within five seconds of
  that does it with the proof: under Wayland the activation protocol's request with the token
  and the window's surface, once at once and once again when the window has drawn, a window
  shown a moment before having no picture yet; under X11 the request to activate with the time,
  and the window's own record of the user's time set beside it. The windowing library's own
  request is not made then: it is the one a guarded desktop answers by marking the window as
  wanting attention.
- **A program the launcher starts.** The token is asked for on the UI thread, in the event that
  asked, and read on the launcher's thread. It goes to the desktop portal as its activation
  token, to the file manager's interface as its startup identifier, and to `xdg-open` in the two
  environment variables a program reads the user's consent from, one for each windowing system.

## 3. How

- **Wayland.** The backend already keeps a connection of its own to the compositor, with a seat
  and the serial of the last input, for the clipboard and the drag. The activation protocol is
  bound there. The client library exports the interfaces of its own protocol and not this one's,
  so the two interfaces, the manager's and the token's, are laid out in memory as the library
  reads them: each request and event with its signature and the interfaces of its arguments.
- **X11.** The server's time is answered by no call. It is read off the event the server sends
  when a property changes, on a connection opened for that and closed after, so that no event of
  the windowing library's is read in its place.
- **`-Dlimn.linux.activationProof=false`** turns both off, which is how each cell of §0 was
  measured both ways. It is a lab switch and not a setting an application needs.

## 4. What is not done

- **A window brought in front with no action of the user's behind it**, a timer's or a network
  answer's. That is what the desktop's guard is for, and the request then goes as it always did:
  the window is marked as wanting attention where the desktop will not raise it.
- **A token passed to a program the application starts itself**, outside the launcher.

## 5. Tests

- The time a startup identifier names, and one that names none.
- The tray telling the backend of each click with the token handed over for it, once, before the
  host hears.
- The four sessions of §0, by the harness of the lab: each guest in each of its sessions, the
  other program's window clicked first, and what the application's window says of the keyboard
  after.
