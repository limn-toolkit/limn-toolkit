# ADR 053: What is copied or dragged is a transfer, and the clipboard carries more than text

- **Status:** Proposed, 2026-10-08, with the owner's answers below. Nothing is built.
- **Date:** 2026-10-08
- **Scope:** a new package `limn.transfer` (`DataFormat`, `Transfer`, `Offer`) that says what a
  copy or a drag carries; `limn.backend.Clipboard` reshaped around it; text, image, HTML, files and
  a format the application names, read and written on Windows, macOS, X11 and Wayland.
- **Compatibility:** breaking for `Clipboard` (`get()`/`set(String)` become `text()`/`setText`),
  an interface of the layer [ADR 046](046-the-public-api-ends-where-a-module-says-and-a-backend-owes-what-the-spi-says.md)
  §12 freezes at 1.0, which is why this lands before it; free before 1.0 (no consumers yet), the
  migration inside the repository is part of the change.
- **Companion:** [ADR 054](054-a-drag-carries-a-transfer-between-widgets-windows-and-applications.md)
  drags the same `Transfer`.

---

## 0. What existed

`Clipboard` was `String get()` and `void set(String)` over `glfwGet/SetClipboardString`: UTF-8
text, synchronous, UI thread only. GLFW offers nothing else on any platform. A business
application meets the limit early: pasting a screenshot into an attachment field, copying a chart
as a picture, pasting files copied in the file manager, copying table rows to a spreadsheet with
their formatting, copying one of its own records and pasting it in another screen.

## 1. The owner's decisions (2026-10-08)

1. **Formats:** text, image, HTML and files, read and written, **and a format the application
   names** for its own objects.
2. Stated and not vetoed: reading is asynchronous, and plain text keeps a direct path.

## 2. The shape

```java
// limn.transfer
public final class DataFormat<T> {
    public static final DataFormat<String> TEXT;        // plain text
    public static final DataFormat<String> HTML;        // an HTML fragment
    public static final DataFormat<Image> IMAGE;        // limn.graphics.Image
    public static final DataFormat<List<Path>> FILES;   // files and directories

    // The application's own bytes, under a name other programs could also ask for.
    public static <T> DataFormat<T> of(String name, Function<T, byte[]> encode, Function<byte[], T> decode);
    // The object itself: never leaves this process, costs no encoding.
    public static <T> DataFormat<T> inProcess(String name, Class<T> type);
}

public final class Transfer {                            // immutable; what is offered
    public static <T> Transfer of(DataFormat<T> format, T value);
    public <T> Transfer with(DataFormat<T> format, T value);
    public <T> Transfer withLazy(DataFormat<T> format, Supplier<T> value);   // made when asked for
    public Set<DataFormat<?>> formats();
}

public interface Offer {                                 // what is there to take
    Set<DataFormat<?>> formats();                        // the four, and any the application declared
    boolean has(DataFormat<?> format);
    <T> CompletionStage<T> read(DataFormat<T> format);   // completes on the UI thread
}

// limn.backend
public interface Clipboard {
    String text();                                       // "" when it holds no text
    void setText(@Nullable String text);
    void set(Transfer content);
    Offer content();                                     // a snapshot of what it holds now
    Registration onChange(Runnable listener);
}
```

- **One value type for both features.** A copy and a drag offer the same thing: several
  representations of one item, of which the taker picks the best it understands. `Transfer` is
  that; a table row copied is `TEXT` (tab-separated), `HTML` (a `<table>`) and the application's
  own format at once.
- **`name` is a MIME-like string** (`application/x-acme-order`). It is the type on Wayland and
  X11 as written, a registered clipboard format on Windows, a dynamic pasteboard type on macOS.
  A format the application has not declared in this run is not listed by `formats()`: the toolkit
  cannot name it. `DataFormat.of` declares it.
- **An in-process format** carries the reference. On the clipboard it is announced under a type
  private to the process (its name and a token made at start), so another instance of the same
  application does not see it, and it is gone when the process ends.
- **Lazy values** are asked for on the UI thread the first time a taker wants that format, and at
  most once. They are what makes copying a large picture free until somebody pastes it. A lazy
  value nobody asked for is dropped when the application ends: nothing is rendered at exit.
- **`text()` stays synchronous.** Every text control pastes inside a key handler, and a context
  menu greys Paste by asking; both want an answer now. Where the platform's read is a pipe (the
  two Linux systems) it is bounded by a timeout of one second and answers `""` past it.
- **`content().formats()` is synchronous and may be a moment old**: it is answered from what the
  platform last told us (§3), so a menu can enable "Paste image" without a round trip. A `read`
  of something that has gone meanwhile completes exceptionally with `TransferGoneException`.
- **Reading an image decodes it** with the decoders the toolkit has, under the same size ceiling
  as `Images.decode`. Writing one encodes PNG, lazily.
- **A password field** keeps refusing copy and cut; nothing here changes that.

## 3. What each system is told

Every format is one or more native types; the first is written, all are read.

| | Windows | macOS | X11 and Wayland |
|---|---|---|---|
| `TEXT` | `CF_UNICODETEXT` | `public.utf8-plain-text` | `text/plain;charset=utf-8`, `UTF8_STRING`, `text/plain` |
| `HTML` | `HTML Format` (CF_HTML, with its offsets header) | `public.html` | `text/html` |
| `IMAGE` | `PNG`, `CF_DIBV5` | `public.png`, `public.tiff` (read) | `image/png`; read also `image/bmp`, `image/jpeg` |
| `FILES` | `CF_HDROP` | `public.file-url` items | `text/uri-list` |
| named | `RegisterClipboardFormat(name)` | the name as a pasteboard type | the name |

- **Windows:** `OpenClipboard` with the window, delayed rendering for lazy values
  (`WM_RENDERFORMAT`), `AddClipboardFormatListener` for `onChange` and the format cache.
- **macOS:** `NSPasteboard.generalPasteboard`, a data provider for lazy values; the pasteboard has
  no change notification, so `changeCount` is compared when the application becomes active and on
  each `content()`.
- **X11:** a connection and a hidden window of our own, on a thread of ours, own and convert the
  `CLIPBOARD` selection, with `INCR` both ways for what exceeds a request; XFixes tells of a new
  owner. GLFW's connection is left alone, and GLFW asking us for text is served by that thread.
- **Wayland:** a second `wl_data_device` on GLFW's `wl_display`, with a `wl_seat`, `wl_keyboard`
  and `wl_pointer` of ours for the serials GLFW does not expose. **Its proxies live on an event
  queue of ours, dispatched by a thread of ours.** Measured on KWin (Fedora 44, 2026-10-08): the
  second device reads and writes every type; and GLFW reads an offer with a blocking `read()` on
  the UI thread, so when this process is the source the `send` request can only be served by
  another thread. Without the queue the process hung, twice, by construction; with it both
  cases pass. Once this lands, text goes through our device too and GLFW's clipboard calls are
  no longer made on Wayland.
- **Headless** (`limn-test`): the backend holds the `Transfer` in memory, one for all its windows.

No shim: Win32 and COM through the calls the UIA bridge already makes, Objective-C through
`objc_msgSend`, Xlib and libwayland-client through LWJGL's JNI and libffi, as the probe did.

## 4. What is left out

- The X11 and Wayland primary selection (the middle-click paste): text controls do not have it
  today and it is additive.
- Marking a copy as concealed from clipboard managers; rich text (RTF); a clipboard history.
- Mutter (GNOME on Wayland) is not measured yet: the lab has no such session. It is owed before
  this record is called implemented.

## 5. Verification

- Unit: `Transfer` and `DataFormat` (laziness asked once, in-process identity, declared names);
  the CF_HTML header; `text/uri-list` both ways with spaces and non-ASCII names; the headless
  clipboard through `TextField` cut, copy and paste.
- Live, on the four guests, both directions against a native program for each format: an image
  (hash compared), HTML, a file list, text, and the application's format between two instances.
