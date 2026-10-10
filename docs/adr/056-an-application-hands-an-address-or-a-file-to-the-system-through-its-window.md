# ADR 056: An application hands an address or a file to the system through its window

- **Status:** Accepted, 2026-10-10, with the owner's answers below; §7 is filled as it is built
  and measured.
- **Date:** 2026-10-10
- **Scope:** three things an application asks of the system it runs on: open an address in the
  program registered for it (a web page in the browser, a `mailto:` in the mail program), open a
  file in the program registered for its type, and show a file selected in the file manager. One
  public type, `limn.backend.Launcher`, reached from a window; one type a backend implements,
  `limn.backend.spi.LauncherPeer`.
- **Compatibility:** additive for an application. `NativeWindow` gains `launcher()` with no
  default, as `fileDialogs()` has none, so every implementation of a window says what it
  answers; the toolkit's own are updated here.
- **Relates to:** [ADR 046](046-the-public-api-ends-where-a-module-says-and-a-backend-owes-what-the-spi-says.md)
  (what a backend owes), and the native file choosers of theme 0.11, whose shape this follows.

---

## 0. What existed

Nothing. The demo's window has a button on each page of its gallery that opens the page of the
site documenting it, and it did that by starting `open`, `xdg-open` or `rundll32` as a process,
chosen by reading the platform's name. Every application with a link in an About box, a "Open
the exported report" or a "Show in folder" would write the same lines, each with the same
omissions: no owner window, nothing said when the command is missing, and whatever string it was
given handed to a shell.

The backend already speaks to each system by the door this needs: `ShellExecuteW` on Windows
(the driver dialog uses it), `NSWorkspace` on macOS (the appearance preferences read it), and the
desktop portal on Linux over the D-Bus client the accessibility bridge and the file chooser
share.

## 1. The owner's answers (2026-10-10)

1. **In the toolkit, and now**, ahead of the rest of desktop integration: the demo should not
   teach a workaround.
2. **All three**: an address, a file, and showing a file in its folder. The second and third are
   what an application that exports something offers next.
3. **`http`, `https` and `mailto` by default; anything else by a list the application
   declares.** A file is opened only by the call that takes a path.

## 2. The API

```java
Launcher launcher = window.launcher();

launcher.openAddress(URI.create("https://example.org/docs"));   // CompletionStage<Boolean>
launcher.openFile(report);                                       // a Path
launcher.showInFolder(report);

Launcher.allowScheme("tel");            // once, at start: this application's addresses may be tel:
Launcher.isAllowed(address);            // what openAddress will refuse, without asking
```

- **From the window**, as `fileDialogs()` is. The portal on Linux takes the window that asks,
  and on every system the program that opens comes to the front because a window with the
  keyboard asked; a call with no window would have to guess one.
- **Each call returns at once** and completes on the UI thread. The systems' own calls block
  for as long as a protocol handler takes to answer, which on Windows can be seconds, so a
  backend makes them off the UI thread.
- **`true` means the system took it**, not that a program opened or that the user saw anything:
  no system tells a caller that. **`false`** means it was not handed over: the scheme is not
  allowed, the file is not there, no program is registered, or the window has no desktop under
  it (headless, embedded). Each refusal is logged with its reason at `WARNING`, since `false`
  alone does not say which. A call never throws for what the address or the file is; it throws
  `NullPointerException` for a null and `IllegalStateException` off the UI thread, as the rest
  of the window's API does.
- **`Launcher.NONE`** answers `false` to everything, for a window with no desktop.

## 3. Which addresses

An address shown to the user often comes from data: a field of a record, a line of a file, a
message. Handed to the system as it is, such a string can start a program: a `file:` address of
an executable, or the scheme of whatever program registered one on that machine. So the toolkit
decides before any backend is asked:

- An address must be absolute and its scheme one of the allowed, compared without case:
  `http`, `https` and `mailto` to begin with.
- `http` and `https` must name a host.
- `Launcher.allowScheme(scheme)` adds one for the process. It is the application saying its own
  addresses may be of that kind (`tel`, `sms`, its own protocol), once, where it starts; it is
  not asked per call, because a per-call flag is what code copies along with the call.
- `file` is never allowed and `allowScheme("file")` throws: a file is opened by `openFile`,
  whose argument is a path the application built and not text it was given. `openFile` on a
  program file runs it, as a double click does; the name of the method is the warning.
- The check lives in `Launcher`, which is final, and not in the backend: a backend receives
  only what passed.

`openFile` and `showInFolder` take the path as given, made absolute; a path that does not exist
answers `false`.

## 4. How each system is asked

| | an address | a file | shown in its folder |
|---|---|---|---|
| **macOS** | `NSWorkspace openURL:` | the same, with a file URL | `activateFileViewerSelectingURLs:` |
| **Windows** | `ShellExecuteW`, verb `open`, owner the window | the same, with the path | `explorer.exe /select,"path"` through `ShellExecuteW` |
| **Linux** | the portal's `OpenURI`, parent the window; `xdg-open` where no portal answers | `xdg-open` | `org.freedesktop.FileManager1.ShowItems`; the folder itself by `xdg-open` where no file manager answers that name |

- **Linux, a file.** The portal's `OpenFile` takes a file descriptor passed over the bus, which
  the toolkit's D-Bus client does not do. `xdg-open` is what a desktop's own programs call, and
  inside a sandbox it is the portal's client. Where neither a portal nor `xdg-open` exists the
  answer is `false`.
- **Linux, the folder.** `FileManager1` is implemented by the file managers of the desktops this
  project measures on. Where none owns the name, the folder opens without the file selected,
  which is the nearest thing every desktop can do, and the answer is still `true`.
- **Windows, the folder.** The command line is the documented way that needs no item-id list
  and no COM object; the path is quoted, and a path holding a quote, which no Windows file name
  can, is refused.

## 5. What is not done

- **No "open with…"**, no choice of program, and no question whether a program is registered:
  each is a different call on each system, and none was asked for.
- **No address typed by this API into a field of another program**, and no reading of what the
  other program did.
- **Wayland.** A program asked to open by a client that cannot hand it an activation token may
  open behind the asking window, announced by the desktop rather than raised. GLFW gives no
  such token. Declared, and measured in §7.

## 6. Tests

- `Launcher`: what is refused and allowed (schemes, case, a missing host, a relative address,
  `file`, `allowScheme`), a missing path, the UI-thread rule, `NONE`.
- Each backend path that builds a string for the system: the Windows command line, the Linux
  portal call's arguments and the choice of fallback.
- Every implementation of `NativeWindow` in the tests answers `Launcher.NONE`.

## 7. As built and measured

Built as written, with one departure. §2 says a backend makes the system's call off the UI
thread "which on Windows can be seconds"; the first build made the macOS call on the main
thread, where AppKit is, and the toolkit's own budget for a UI task reported it: 171 ms to hand
an address to a browser that was already open. All three systems are asked on a thread of the
request's own; on macOS that thread makes and drains an autorelease pool for the objects the
request creates.

The public type is `limn.backend.Launcher`, final, over `limn.backend.spi.LauncherPeer`. The
demo's `--open <address or path>` and `--show <path>` make one request from a real window and
answer in the exit code; they are how each line below was read (2026-10-10).

| | an address | refused: `javascript:`, `file:` | shown in its folder | a text file | `mailto:` |
|---|---|---|---|---|---|
| **macOS 27** (guest) | the browser, in front | `false`, nothing started | the file manager's window, the file selected | the text editor, in front | the mail program, in front |
| **Fedora 44, KDE, Wayland** | the browser started, through the portal | `false`, nothing started | the file manager started, through `FileManager1` | the desktop's program for text started, by `xdg-open` | not asked |
| **Ubuntu 24.04, GNOME, X11** | the browser started, through the portal | `false`, nothing started | the file manager started, through `FileManager1` | the text editor started, by `xdg-open` | not asked |
| **Windows 11** (guest) | the browser, showing the page | `false`, nothing started | the file manager's window at the folder, the file selected (read from the shell's own list of its windows) | the text editor, in front | not asked |

On the two Linux guests what was read is the answer and the process that started, not the
screen: whether the program came in front of the asking window, which §5 declares open on
Wayland, was not looked at. Neither fallback (`xdg-open` for an address, the folder for a file
with no file manager on the bus) ran on a guest, since both desktops have the first choice; the
fallbacks are covered by reading, not by a run.
