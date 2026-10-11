package limn.backend.lwjgl;

import limn.backend.lwjgl.transfer.Ffi;
import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayRow;
import limn.backend.spi.TrayState;
import limn.concurrent.internal.Threads;
import limn.graphics.Color;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.MSG;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A tray icon on Windows: an icon in the notification area, added through the shell on a hidden
 * window, with the system's popup menu.
 *
 * <p><b>A thread of its own</b>, with the hidden window and its message pump. The shell's
 * callbacks arrive there, and the popup menu is tracked there: the call that shows it returns
 * only when it closes, and on the UI thread that would hold every frame of the toolkit for as
 * long as a menu stays open. The UI thread hands this thread a state and posts it a message;
 * this thread asks the UI thread for the menu's rows, with a bounded wait, and posts it what the
 * user chose.
 *
 * <p>The window is a top-level one, never shown: a window that only receives messages is not
 * told when the shell restarts, which is when an icon has to be added again.
 */
final class WindowsTray implements TrayPeer {

    private static final System.Logger LOG = System.getLogger(WindowsTray.class.getName());

    private static final int WM_NULL = 0x0000;
    private static final int WM_SETTINGCHANGE = 0x001A;
    private static final int WM_CONTEXTMENU = 0x007B;
    private static final int WM_APP_ICON = 0x8000 + 1;
    private static final int WM_APP_UPDATE = 0x8000 + 2;
    private static final int WM_APP_END = 0x8000 + 3;
    private static final int NIN_SELECT = 0x0400;
    private static final int NIN_KEYSELECT = 0x0401;

    private static final int NIM_ADD = 0;
    private static final int NIM_MODIFY = 1;
    private static final int NIM_DELETE = 2;
    private static final int NIM_SETFOCUS = 3;
    private static final int NIM_SETVERSION = 4;
    private static final int NIF_MESSAGE = 0x1;
    private static final int NIF_ICON = 0x2;
    private static final int NIF_TIP = 0x4;
    private static final int NIF_SHOWTIP = 0x80;
    private static final int NOTIFYICON_VERSION_4 = 4;

    /** {@code NOTIFYICONDATAW} on 64-bit Windows, and where its members are. */
    static final int DATA_SIZE = 976;
    static final int DATA_HWND = 8;
    static final int DATA_ID = 16;
    static final int DATA_FLAGS = 20;
    static final int DATA_MESSAGE = 24;
    static final int DATA_ICON = 32;
    static final int DATA_TIP = 40;
    static final int DATA_VERSION = 816;
    /** The tooltip's room, in UTF-16 units, its end included. */
    static final int TIP_UNITS = 128;

    private static final int MF_GRAYED = 0x1;
    private static final int MF_CHECKED = 0x8;
    private static final int MF_POPUP = 0x10;
    private static final int MF_SEPARATOR = 0x800;
    private static final int TPM_RIGHTBUTTON = 0x2;
    private static final int TPM_RETURNCMD = 0x100;
    private static final int TPM_LAYOUTRTL = 0x8000;
    private static final int SM_CXSMICON = 49;
    private static final int COLOR_WINDOWTEXT = 8;
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_TOOLWINDOW = 0x80;
    private static final String CLASS_NAME = "LimnTray";

    /** How long this thread waits for the UI thread to read the menu before it opens the last rows. */
    private static final long MENU_WAIT_MILLIS = 400;
    /** Two key activations nearer than this are one: the shell sends Enter's twice. */
    private static final long KEY_REPEAT_NANOS = 300_000_000L;

    private static final Map<Long, WindowsTray> BY_WINDOW = new ConcurrentHashMap<>();
    private static final Set<Integer> NUMBERS = new HashSet<>();
    private static final Map<String, Long> FUNCTIONS = new ConcurrentHashMap<>();
    private static SharedLibrary shell32;
    private static SharedLibrary gdi32;
    private static WindowProc procedure;
    private static boolean hooked;

    private final TrayHost host;
    private final Executor ui;
    private final int number;
    private final Thread thread;

    private volatile @Nullable TrayState state;
    private volatile long hwnd;
    private volatile boolean available;
    private volatile boolean disposed;

    // The tray's own thread only.
    private long data;
    private long icon;
    private boolean added;
    private int taskbarCreated;
    private @Nullable TrayState applied;
    private List<TrayRow> rows = List.of();
    private long lastKey;

    WindowsTray(String id, TrayHost host, Executor ui) {
        this.host = host;
        this.ui = ui;
        this.number = claimNumber(id);
        this.thread = Threads.daemon("limn-tray-" + id, this::run);
    }

    /**
     * The sixteen bits the shell's callback carries and the system keys the user's choice for the
     * icon by, beside the executable's path: derived from the identifier, so the same on every
     * run, and the next free one when two identifiers of this process derive the same.
     */
    private static synchronized int claimNumber(String id) {
        int number = numberOf(id);
        while (!NUMBERS.add(number)) {
            number = number == 0xFFFF ? 1 : number + 1;
        }
        return number;
    }

    /** @return a number from 1 to 65535 derived from {@code id} */
    static int numberOf(String id) {
        int hash = id.hashCode();
        int folded = (hash ^ (hash >>> 16)) & 0xFFFF;
        return folded == 0 ? 1 : folded;
    }

    // ---- the UI thread ------------------------------------------------------------------------

    @Override
    public void update(TrayState next) {
        state = Objects.requireNonNull(next, "state");
        post(WM_APP_UPDATE);
    }

    @Override
    public boolean isAvailable() {
        return available && !disposed;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        available = false;
        post(WM_APP_END);
    }

    private void post(int message) {
        long window = hwnd;
        if (window != NULL) {
            user("PostMessageW", "pipp>i", window, message, 0, 0);
        }
    }

    // ---- the tray's thread ----------------------------------------------------------------------

    private void run() {
        try {
            long window = makeWindow();
            if (window == NULL) {
                LOG.log(System.Logger.Level.WARNING, "the tray's window could not be made");
                return;
            }
            BY_WINDOW.put(window, this);
            taskbarCreated = (int) user("RegisterWindowMessageW", "p>i", wide("TaskbarCreated"));
            data = MemoryUtil.nmemCalloc(1, DATA_SIZE);
            hwnd = window;
            apply();
            if (disposed) {
                user("PostMessageW", "pipp>i", window, WM_APP_END, 0, 0);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MSG msg = MSG.calloc(stack);
                while (User32.GetMessage(null, msg, NULL, 0, 0)) {
                    User32.TranslateMessage(msg);
                    User32.DispatchMessage(msg);
                }
            }
        } catch (Throwable failed) {
            LOG.log(System.Logger.Level.WARNING, "the tray's thread ended", failed);
        } finally {
            end();
        }
    }

    private static synchronized long makeWindow() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer name = stack.UTF16(CLASS_NAME);
            if (procedure == null) {
                procedure = WindowProc.create(WindowsTray::procedure);
                WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                        .cbSize(WNDCLASSEX.SIZEOF)
                        .lpfnWndProc(procedure)
                        .hInstance(WindowsLibrary.HINSTANCE)
                        .lpszClassName(name);
                User32.RegisterClassEx(null, windowClass);
            }
            if (!hooked) {
                hooked = true;
                // A process that ends with its icons shown leaves them until the pointer passes over.
                Runtime.getRuntime().addShutdownHook(new Thread(WindowsTray::removeAll, "limn-tray-exit"));
            }
            return User32.CreateWindowEx(null, WS_EX_TOOLWINDOW, name, stack.UTF16(""), WS_POPUP,
                    0, 0, 0, 0, NULL, NULL, WindowsLibrary.HINSTANCE, NULL);
        }
    }

    private static void removeAll() {
        for (WindowsTray tray : BY_WINDOW.values()) {
            long window = tray.hwnd;
            if (window != NULL) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long d = stack.ncalloc(8, DATA_SIZE, 1);
                    MemoryUtil.memPutInt(d, DATA_SIZE);
                    MemoryUtil.memPutLong(d + DATA_HWND, window);
                    MemoryUtil.memPutInt(d + DATA_ID, tray.number);
                    shell("Shell_NotifyIconW", "ip>i", NIM_DELETE, d);
                }
            }
        }
    }

    private void end() {
        long window = hwnd;
        hwnd = NULL;
        boolean was = available;
        available = false;
        if (window != NULL) {
            if (added) {
                shell("Shell_NotifyIconW", "ip>i", NIM_DELETE, data);
                added = false;
            }
            BY_WINDOW.remove(window);
            user("DestroyWindow", "p>i", window);
        }
        if (icon != NULL) {
            user("DestroyIcon", "p>i", icon);
            icon = NULL;
        }
        if (data != NULL) {
            MemoryUtil.nmemFree(data);
            data = NULL;
        }
        synchronized (WindowsTray.class) {
            NUMBERS.remove(number);
        }
        if (was && !disposed) {
            ui.execute(host::availabilityChanged);
        }
    }

    private static long procedure(long window, int message, long wParam, long lParam) {
        WindowsTray tray = BY_WINDOW.get(window);
        if (tray != null) {
            try {
                if (tray.handle(message, wParam, lParam)) {
                    return 0;
                }
            } catch (Throwable failed) {
                LOG.log(System.Logger.Level.WARNING, "a tray message could not be served", failed);
            }
        }
        return User32.DefWindowProc(window, message, wParam, lParam);
    }

    private boolean handle(int message, long wParam, long lParam) {
        switch (message) {
            case WM_APP_UPDATE -> {
                apply();
                return true;
            }
            case WM_APP_END -> {
                user("PostQuitMessage", "i>v", 0);
                return true;
            }
            case WM_APP_ICON -> {
                clicked((int) (lParam & 0xFFFF), (short) (wParam & 0xFFFF), (short) ((wParam >> 16) & 0xFFFF));
                return true;
            }
            case WM_SETTINGCHANGE -> {
                // The task bar may have gone light or dark, which a monochrome icon follows.
                WindowsDarkMenus.flush();
                applied = null;
                apply();
                return false;
            }
            default -> {
                if (message == taskbarCreated && taskbarCreated != 0) {
                    added = false;   // the shell began again, and knows no icon of ours
                    applied = null;
                    apply();
                    return true;
                }
                return false;
            }
        }
    }

    /** Adds the icon, or changes it to what the state says now. */
    private void apply() {
        TrayState now = state;
        if (now == null || disposed || data == NULL || now.equals(applied) && added) {
            return;
        }
        long next = icon(now);
        MemoryUtil.memSet(data, 0, DATA_SIZE);
        MemoryUtil.memPutInt(data, DATA_SIZE);
        MemoryUtil.memPutLong(data + DATA_HWND, hwnd);
        MemoryUtil.memPutInt(data + DATA_ID, number);
        MemoryUtil.memPutInt(data + DATA_FLAGS, NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP);
        MemoryUtil.memPutInt(data + DATA_MESSAGE, WM_APP_ICON);
        MemoryUtil.memPutLong(data + DATA_ICON, next);
        byte[] tip = tip(now.tooltip().isEmpty() ? now.title() : now.tooltip());
        for (int i = 0; i < tip.length; i++) {
            MemoryUtil.memPutByte(data + DATA_TIP + i, tip[i]);
        }
        MemoryUtil.memPutInt(data + DATA_VERSION, NOTIFYICON_VERSION_4);
        boolean taken;
        if (added) {
            taken = shell("Shell_NotifyIconW", "ip>i", NIM_MODIFY, data) != 0;
        } else {
            taken = shell("Shell_NotifyIconW", "ip>i", NIM_ADD, data) != 0;
            if (taken) {
                shell("Shell_NotifyIconW", "ip>i", NIM_SETVERSION, data);
            }
            added = taken;   // with no shell to take it, tried again when one says it started
        }
        if (icon != NULL) {
            user("DestroyIcon", "p>i", icon);
        }
        icon = next;
        applied = now;
        if (taken != available) {
            available = taken;
            ui.execute(host::availabilityChanged);
        }
    }

    /** The tooltip as the structure holds it: UTF-16, cut to its room, never through a pair. */
    static byte[] tip(String text) {
        String cut = text;
        if (cut.length() > TIP_UNITS - 1) {
            int end = TIP_UNITS - 1;
            if (Character.isHighSurrogate(cut.charAt(end - 1))) {
                end--;
            }
            cut = cut.substring(0, end);
        }
        return (cut + "\0").getBytes(StandardCharsets.UTF_16LE);
    }

    /** The picture at the small icon's size: the monochrome one in the task bar's colour when there is one. */
    private long icon(TrayState now) {
        int size = Math.max(16, (int) user("GetSystemMetrics", "i>i", SM_CXSMICON));
        boolean lightBar = WindowsSystemPreferences.systemSurfacesAreLight();
        TrayPicture shape = now.monochrome();
        Image image;
        if (shape != null) {
            Color tint;
            if (WindowsSystemPreferences.highContrastOn()) {
                int rgb = (int) user("GetSysColor", "i>i", COLOR_WINDOWTEXT);   // 0x00BBGGRR
                tint = Color.rgb((rgb & 0xFF) << 16 | rgb & 0xFF00 | rgb >> 16 & 0xFF);
            } else {
                tint = lightBar ? Color.rgb(0x000000) : Color.rgb(0xFFFFFF);
            }
            image = shape.render(size, tint, !lightBar);
        } else if (now.picture() != null) {
            image = now.picture().render(size, null, !lightBar);
        } else {
            return NULL;
        }
        return iconOf(image);
    }

    /** An icon handle from straight-alpha pixels, by a 32-bit colour bitmap and an empty mask. */
    private static long iconOf(Image image) {
        int w = image.width();
        int h = image.height();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long header = stack.ncalloc(8, 124, 1);                 // BITMAPV5HEADER
            MemoryUtil.memPutInt(header, 124);
            MemoryUtil.memPutInt(header + 4, w);
            MemoryUtil.memPutInt(header + 8, -h);                   // top-down
            MemoryUtil.memPutShort(header + 12, (short) 1);
            MemoryUtil.memPutShort(header + 14, (short) 32);
            MemoryUtil.memPutInt(header + 16, 3);                   // BI_BITFIELDS
            MemoryUtil.memPutInt(header + 40, 0x00FF0000);
            MemoryUtil.memPutInt(header + 44, 0x0000FF00);
            MemoryUtil.memPutInt(header + 48, 0x000000FF);
            MemoryUtil.memPutInt(header + 52, 0xFF000000);
            long bitsOut = stack.ncalloc(8, 8, 1);
            long dc = user("GetDC", "p>p", NULL);
            long color = gdi("CreateDIBSection", "ppippi>p", dc, header, 0, bitsOut, NULL, 0);
            user("ReleaseDC", "pp>i", NULL, dc);
            if (color == NULL) {
                return NULL;
            }
            long bits = MemoryUtil.memGetAddress(bitsOut);
            byte[] rgba = image.pixels();
            for (int i = 0; i < rgba.length; i += 4) {
                MemoryUtil.memPutByte(bits + i, rgba[i + 2]);
                MemoryUtil.memPutByte(bits + i + 1, rgba[i + 1]);
                MemoryUtil.memPutByte(bits + i + 2, rgba[i]);
                MemoryUtil.memPutByte(bits + i + 3, rgba[i + 3]);
            }
            long mask = gdi("CreateBitmap", "iiiip>p", w, h, 1, 1, NULL);
            long info = stack.ncalloc(8, 32, 1);                    // ICONINFO
            MemoryUtil.memPutInt(info, 1);
            MemoryUtil.memPutLong(info + 16, mask);
            MemoryUtil.memPutLong(info + 24, color);
            long handle = user("CreateIconIndirect", "p>p", info);
            gdi("DeleteObject", "p>i", color);
            gdi("DeleteObject", "p>i", mask);
            return handle;
        }
    }

    /** A callback of the icon's, as version 4 sends it: the event, and where on the screen. */
    private void clicked(int event, int x, int y) {
        TrayState now = state;
        if (now == null || disposed) {
            return;
        }
        switch (event) {
            case WM_CONTEXTMENU -> menu(now, x, y);
            case NIN_SELECT -> activateOrMenu(now, x, y);
            case NIN_KEYSELECT -> {
                long at = System.nanoTime();
                if (at - lastKey > KEY_REPEAT_NANOS) {
                    lastKey = at;
                    activateOrMenu(now, x, y);
                }
            }
            default -> {
                // The pointer moving over the icon, a button going down: nothing of ours.
            }
        }
    }

    private void activateOrMenu(TrayState now, int x, int y) {
        if (now.activates()) {
            ui.execute(host::activate);
        } else {
            menu(now, x, y);
        }
    }

    /** Opens the system's popup at the place the shell gave and tells the UI thread what was chosen. */
    private void menu(TrayState now, int x, int y) {
        CompletableFuture<List<TrayRow>> reading = new CompletableFuture<>();
        ui.execute(() -> {
            try {
                reading.complete(host.menu());
            } catch (RuntimeException | Error failed) {
                reading.completeExceptionally(failed);
            }
        });
        try {
            rows = reading.get(MENU_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "the UI thread did not read the tray's menu in time", e);
        }
        if (rows.isEmpty()) {
            return;
        }
        List<Long> texts = new ArrayList<>();
        long popup = build(rows, texts);
        try {
            // The system asks for both: the window in front for the menu to close on a click
            // elsewhere, and a message after it for the next one to open.
            user("SetForegroundWindow", "p>i", hwnd);
            int flags = TPM_RETURNCMD | TPM_RIGHTBUTTON | (now.rightToLeft() ? TPM_LAYOUTRTL : 0);
            int chosen = (int) user("TrackPopupMenuEx", "piiipp>i", popup, flags, x, y, hwnd, NULL);
            user("PostMessageW", "pipp>i", hwnd, WM_NULL, 0, 0);
            if (chosen != 0) {
                ui.execute(() -> host.choose(chosen));
            } else if (added) {
                shell("Shell_NotifyIconW", "ip>i", NIM_SETFOCUS, data);   // the keys go back to the icons
            }
        } finally {
            user("DestroyMenu", "p>i", popup);
            for (long text : texts) {
                MemoryUtil.nmemFree(text);
            }
        }
    }

    private static long build(List<TrayRow> rows, List<Long> texts) {
        long menu = user("CreatePopupMenu", ">p");
        for (TrayRow row : rows) {
            if (row.kind() == TrayRow.Kind.SEPARATOR) {
                user("AppendMenuW", "pipp>i", menu, MF_SEPARATOR, 0, NULL);
                continue;
            }
            long text = wide(label(row));
            texts.add(text);
            int flags = (row.enabled() ? 0 : MF_GRAYED) | (row.checked() ? MF_CHECKED : 0);
            if (row.kind() == TrayRow.Kind.SUBMENU && !row.children().isEmpty()) {
                user("AppendMenuW", "pipp>i", menu, flags | MF_POPUP, build(row.children(), texts), text);
            } else {
                user("AppendMenuW", "pipp>i", menu, flags, row.id(), text);
            }
        }
        return menu;
    }

    /**
     * A label as a Windows menu reads one: an ampersand marks the access key, so each of the
     * text's own is doubled and one goes before the row's access letter, and a tab, which would
     * start a second column, is a space.
     */
    static String label(TrayRow row) {
        String text = row.label();
        int at = -1;
        if (row.mnemonic() != 0) {
            at = text.toUpperCase(java.util.Locale.ROOT).indexOf(Character.toUpperCase(row.mnemonic()));
        }
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i == at) {
                out.append('&');
            }
            out.append(c == '\t' ? ' ' : c);
            if (c == '&') {
                out.append('&');
            }
        }
        return out.toString();
    }

    // ---- calls ----------------------------------------------------------------------------------

    private static long wide(String text) {
        byte[] bytes = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        long memory = MemoryUtil.nmemCalloc(1, bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            MemoryUtil.memPutByte(memory + i, bytes[i]);
        }
        return memory;
    }

    private static long user(String name, String signature, long... args) {
        long function = FUNCTIONS.computeIfAbsent(name, n -> APIUtil.apiGetFunctionAddress(User32.getLibrary(), n));
        return Ffi.call(function, signature, args);
    }

    private static long shell(String name, String signature, long... args) {
        long function = FUNCTIONS.computeIfAbsent(name, n -> {
            synchronized (WindowsTray.class) {
                if (shell32 == null) {
                    shell32 = APIUtil.apiCreateLibrary("shell32");
                }
            }
            return APIUtil.apiGetFunctionAddress(shell32, n);
        });
        return Ffi.call(function, signature, args);
    }

    private static long gdi(String name, String signature, long... args) {
        long function = FUNCTIONS.computeIfAbsent(name, n -> {
            synchronized (WindowsTray.class) {
                if (gdi32 == null) {
                    gdi32 = APIUtil.apiCreateLibrary("gdi32");
                }
            }
            return APIUtil.apiGetFunctionAddress(gdi32, n);
        });
        return Ffi.call(function, signature, args);
    }
}
