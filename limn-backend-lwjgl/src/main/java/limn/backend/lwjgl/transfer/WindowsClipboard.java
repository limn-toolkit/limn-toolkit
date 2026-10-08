package limn.backend.lwjgl.transfer;

import limn.graphics.Image;
import limn.graphics.Images;
import limn.transfer.TransferGoneException;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;

/**
 * The Windows clipboard, through a message-only window of our own.
 *
 * <p>Everything this process offers is rendered on demand: the formats are announced with no
 * data, and {@code WM_RENDERFORMAT} asks for one when a program pastes. The window's messages
 * arrive on the UI thread, inside GLFW's poll, so no thread of ours is involved in offering. A
 * read is the other way round: any thread may open the clipboard, and the copy out of it is made
 * on a short-lived one, so that a large picture is decoded off the UI thread.
 *
 * <p><b>What this process still owns when it closes is rendered then</b>, as every Windows
 * program does, so that what the user copied outlives the application.
 *
 * <p>A picture is offered twice, as the {@code PNG} format, which keeps its transparency, and as
 * {@code CF_DIB}, which every program reads. It is read from {@code PNG} where there is one.
 */
public final class WindowsClipboard implements NativeClipboard {

    private static final System.Logger LOG = System.getLogger(WindowsClipboard.class.getName());

    private static final int CF_DIB = 8;
    private static final int CF_UNICODETEXT = 13;
    private static final int CF_HDROP = 15;
    private static final int CF_DIBV5 = 17;
    private static final int WM_RENDERFORMAT = 0x0305;
    private static final int WM_RENDERALLFORMATS = 0x0306;
    private static final int WM_DESTROYCLIPBOARD = 0x0307;
    private static final int WM_CLIPBOARDUPDATE = 0x031D;
    private static final long HWND_MESSAGE = -3;
    private static final int GMEM_MOVEABLE = 2;
    private static final String CLASS_NAME = "LimnClipboard";

    private static WindowProc procedure;
    private static WindowsClipboard open;

    private final SharedLibrary kernel32 = APIUtil.apiCreateLibrary("kernel32");
    private final Map<String, Long> functions = new HashMap<>();
    private final long hwnd;
    private final int htmlFormat;
    private final int pngFormat;
    private final Map<String, Integer> namedFormats = new HashMap<>();

    private List<String> ownTypes = List.of();
    private Function<String, byte[]> ownBytes = type -> null;
    private volatile List<String> offered = List.of();
    private volatile Runnable changeListener = () -> { };
    private volatile boolean closed;

    /**
     * UI thread.
     *
     * @throws RuntimeException when the window cannot be made; the caller then keeps GLFW's text
     *                          clipboard
     */
    public WindowsClipboard() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer className = stack.UTF16(CLASS_NAME);
            if (procedure == null) {
                procedure = WindowProc.create(WindowsClipboard::procedure);
                WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                        .cbSize(WNDCLASSEX.SIZEOF)
                        .lpfnWndProc(procedure)
                        .hInstance(WindowsLibrary.HINSTANCE)
                        .lpszClassName(className);
                User32.RegisterClassEx(null, windowClass);
            }
            hwnd = User32.CreateWindowEx(null, 0, className, stack.UTF16(""), 0, 0, 0, 0, 0,
                    HWND_MESSAGE, NULL, WindowsLibrary.HINSTANCE, NULL);
        }
        if (hwnd == NULL) {
            throw new IllegalStateException("the clipboard's window could not be made");
        }
        htmlFormat = register("HTML Format");
        pngFormat = register("PNG");
        open = this;
        user("AddClipboardFormatListener", "p>i", hwnd);
        refresh();
    }

    private static long procedure(long window, int message, long wParam, long lParam) {
        WindowsClipboard clipboard = open;
        if (clipboard != null && window == clipboard.hwnd) {
            try {
                switch (message) {
                    case WM_CLIPBOARDUPDATE -> {
                        clipboard.refresh();
                        clipboard.changeListener.run();
                        return 0;
                    }
                    case WM_RENDERFORMAT -> {
                        clipboard.render((int) wParam);
                        return 0;
                    }
                    case WM_RENDERALLFORMATS -> {
                        clipboard.renderAll();
                        return 0;
                    }
                    case WM_DESTROYCLIPBOARD -> {
                        clipboard.ownTypes = List.of();
                        clipboard.ownBytes = type -> null;
                        return 0;
                    }
                    default -> { }
                }
            } catch (Throwable failed) {
                LOG.log(System.Logger.Level.WARNING, "a clipboard message could not be served", failed);
            }
        }
        return User32.DefWindowProc(window, message, wParam, lParam);
    }

    // ---- Win32 ------------------------------------------------------------------------------------

    private long user(String name, String signature, long... args) {
        long function = functions.computeIfAbsent(name,
                n -> APIUtil.apiGetFunctionAddress(User32.getLibrary(), n));
        return Ffi.call(function, signature, args);
    }

    private long kernel(String name, String signature, long... args) {
        long function = functions.computeIfAbsent(name, n -> APIUtil.apiGetFunctionAddress(kernel32, n));
        return Ffi.call(function, signature, args);
    }

    private int register(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return (int) user("RegisterClipboardFormatW", "p>i", memAddress(stack.UTF16(name)));
        }
    }

    private synchronized int format(String type) {
        return switch (type) {
            case ClipboardTypes.TEXT -> CF_UNICODETEXT;
            case ClipboardTypes.HTML -> htmlFormat;
            case ClipboardTypes.IMAGE -> pngFormat;
            case ClipboardTypes.FILES -> CF_HDROP;
            default -> namedFormats.computeIfAbsent(type, this::register);
        };
    }

    /**
     * One opening of the clipboard at a time in this process. Windows lets two threads of one
     * program both "open" it with no window, and the first to close it frees the handles the
     * other is still copying from: three reads at once ended the process on the Windows 11
     * guest, with no report (2026-10-08). The UI thread never waits long for it: a reader may be
     * inside {@code GetClipboardData} waiting for this very thread to render.
     */
    private final java.util.concurrent.locks.ReentrantLock opening = new java.util.concurrent.locks.ReentrantLock();

    /** Another program may hold the clipboard open for a moment: a few tries, then give up. */
    private boolean openClipboard(long owner) {
        try {
            if (owner == NULL) {
                opening.lockInterruptibly();
            } else if (!opening.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return false;
            }
            for (int attempt = 0; attempt < 10; attempt++) {
                if (user("OpenClipboard", "p>i", owner) != 0) {
                    return true;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (opening.isHeldByCurrentThread()) {
            opening.unlock();
        }
        return false;
    }

    private void closeClipboard() {
        user("CloseClipboard", ">i");
        opening.unlock();
    }

    private byte[] block(int format) {
        long handle = user("GetClipboardData", "i>p", format);
        if (handle == NULL) {
            return null;
        }
        long size = kernel("GlobalSize", "p>p", handle);
        long data = kernel("GlobalLock", "p>p", handle);
        if (data == NULL || size <= 0 || size > Integer.MAX_VALUE) {
            return null;
        }
        byte[] out = new byte[(int) size];
        memByteBuffer(data, out.length).get(out);
        kernel("GlobalUnlock", "p>i", handle);
        return out;
    }

    private void put(int format, byte[] bytes) {
        long handle = kernel("GlobalAlloc", "ip>p", GMEM_MOVEABLE, Math.max(1, bytes.length));
        if (handle == NULL) {
            return;
        }
        long data = kernel("GlobalLock", "p>p", handle);
        memByteBuffer(data, Math.max(1, bytes.length)).put(bytes);
        kernel("GlobalUnlock", "p>i", handle);
        if (user("SetClipboardData", "ip>p", format, handle) == NULL) {
            kernel("GlobalFree", "p>p", handle); // not taken: still ours to free
        }
    }

    // ---- what is there ------------------------------------------------------------------------------

    private void refresh() {
        List<String> types = new ArrayList<>();
        if (openClipboard(hwnd)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer name = stack.calloc(512);
                for (int format = (int) user("EnumClipboardFormats", "i>i", 0); format != 0;
                        format = (int) user("EnumClipboardFormats", "i>i", format)) {
                    String type = null;
                    if (format == CF_UNICODETEXT) {
                        type = ClipboardTypes.TEXT;
                    } else if (format == htmlFormat) {
                        type = ClipboardTypes.HTML;
                    } else if (format == pngFormat || format == CF_DIB || format == CF_DIBV5) {
                        type = ClipboardTypes.IMAGE;
                    } else if (format == CF_HDROP) {
                        type = ClipboardTypes.FILES;
                    } else if (format >= 0xC000) {
                        int length = (int) user("GetClipboardFormatNameW", "ipi>i", format, memAddress(name), 255);
                        if (length > 0) {
                            byte[] chars = new byte[length * 2];
                            name.duplicate().get(chars);
                            type = new String(chars, StandardCharsets.UTF_16LE);
                            synchronized (this) {
                                namedFormats.putIfAbsent(type, format);
                            }
                        }
                    }
                    if (type != null && !types.contains(type)) {
                        types.add(type);
                    }
                }
            } finally {
                closeClipboard();
            }
        }
        offered = List.copyOf(types);
    }

    @Override
    public boolean isOwner() {
        return !closed && user("GetClipboardOwner", ">p") == hwnd;
    }

    @Override
    public List<String> types() {
        return offered;
    }

    @Override
    public CompletableFuture<byte[]> read(String type) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                byte[] bytes = readNow(type);
                if (bytes == null) {
                    result.completeExceptionally(new TransferGoneException(type + " is not on the clipboard"));
                } else {
                    result.complete(bytes);
                }
            } catch (Throwable failed) {
                result.completeExceptionally(new TransferGoneException(type + " could not be read", failed));
            }
        }, "limn-windows-clipboard-read");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    private byte[] readNow(String type) {
        if (!openClipboard(NULL)) {
            return null;
        }
        try {
            switch (type) {
                case ClipboardTypes.TEXT: {
                    byte[] block = block(CF_UNICODETEXT);
                    return block == null ? null : WindowsFormats.fromUnicodeText(block);
                }
                case ClipboardTypes.HTML: {
                    byte[] block = block(htmlFormat);
                    return block == null ? null : WindowsFormats.fromCfHtml(block);
                }
                case ClipboardTypes.IMAGE: {
                    byte[] png = block(pngFormat);
                    if (png != null) {
                        return png;
                    }
                    byte[] dib = block(CF_DIB); // Windows makes one from whatever bitmap is there
                    return dib == null ? null : WindowsFormats.bmpFile(dib);
                }
                case ClipboardTypes.FILES: {
                    byte[] block = block(CF_HDROP);
                    if (block == null) {
                        return null;
                    }
                    List<Path> files = new ArrayList<>();
                    for (String path : WindowsFormats.fromDropFiles(block)) {
                        files.add(Path.of(path));
                    }
                    return UriLists.encode(files);
                }
                default:
                    return block(format(type));
            }
        } finally {
            closeClipboard();
        }
    }

    // ---- what we offer --------------------------------------------------------------------------------

    @Override
    public void offer(List<String> types, Function<String, byte[]> bytes) {
        if (closed || !openClipboard(hwnd)) {
            return;
        }
        try {
            user("EmptyClipboard", ">i"); // WM_DESTROYCLIPBOARD lets go of what was offered before
            ownTypes = List.copyOf(types);
            ownBytes = bytes;
            for (String type : ownTypes) {
                user("SetClipboardData", "ip>p", format(type), NULL); // promised, made when asked for
                if (type.equals(ClipboardTypes.IMAGE)) {
                    user("SetClipboardData", "ip>p", CF_DIB, NULL);
                }
            }
        } finally {
            closeClipboard();
        }
    }

    /** Hands the clipboard, which the asking program holds open, one format's data. */
    private void render(int format) {
        String type = null;
        for (String candidate : ownTypes) {
            if (format(candidate) == format || (format == CF_DIB && candidate.equals(ClipboardTypes.IMAGE))) {
                type = candidate;
            }
        }
        byte[] bytes = type == null ? null : ownBytes.apply(type);
        if (bytes == null) {
            return;
        }
        if (format == CF_UNICODETEXT) {
            bytes = WindowsFormats.unicodeText(bytes);
        } else if (format == htmlFormat) {
            bytes = WindowsFormats.cfHtml(bytes);
        } else if (format == CF_DIB) {
            Image image = Images.decode(bytes);
            bytes = WindowsFormats.dib(image.width(), image.height(), image.pixels());
        } else if (format == CF_HDROP) {
            List<String> paths = new ArrayList<>();
            for (Path file : UriLists.decode(bytes)) {
                paths.add(file.toString());
            }
            bytes = WindowsFormats.dropFiles(paths);
        }
        put(format, bytes);
    }

    private void renderAll() {
        if (ownTypes.isEmpty() || !openClipboard(hwnd)) {
            return;
        }
        try {
            if (user("GetClipboardOwner", ">p") == hwnd) {
                for (String type : ownTypes) {
                    render(format(type));
                    if (type.equals(ClipboardTypes.IMAGE)) {
                        render(CF_DIB);
                    }
                }
            }
        } finally {
            closeClipboard();
        }
    }

    @Override
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        renderAll(); // what the user copied here stays on the clipboard after the application
        closed = true;
        user("RemoveClipboardFormatListener", "p>i", hwnd);
        open = null;
        User32.DestroyWindow(null, hwnd);
    }
}
