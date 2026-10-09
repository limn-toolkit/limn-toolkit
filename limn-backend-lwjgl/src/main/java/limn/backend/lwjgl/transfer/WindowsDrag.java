package limn.backend.lwjgl.transfer;

import limn.backend.spi.DragRequest;
import limn.backend.spi.WindowInput;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.transfer.DropAction;
import limn.transfer.Offer;
import limn.transfer.TransferGoneException;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutShort;
import static org.lwjgl.system.MemoryUtil.nmemCalloc;

/**
 * The system's drag and drop on Windows, which is OLE's: a window is a destination once an
 * {@code IDropTarget} is registered for it, and a drag out of it is {@code DoDragDrop} with a
 * data object and an {@code IDropSource}.
 *
 * <p>The two objects are made here, each a table of callbacks; the data object is the shell's
 * ({@code SHCreateDataObject}), filled with what a copy would put on the clipboard, in the same
 * formats. What it carries is therefore made when the drag begins, lazy values included: OLE
 * offers no window message to render one later, as the clipboard has.
 *
 * <p><b>{@code DoDragDrop} does not return until the drag is over</b>, and runs a loop of its own
 * meanwhile: the application's loop does not turn. It is called from a task posted to the UI
 * thread, so that the scene has heard its drag was taken before the first answer is asked of it,
 * and the destination paints the window itself after each answer, as on macOS.
 *
 * <p>The modifier keys do not reach the window during that loop. They arrive with each call, and
 * choose among what the source allows as the platform has it: Control copies, Shift moves, both
 * or Alt link.
 */
public final class WindowsDrag {

    private static final System.Logger LOG = System.getLogger(WindowsDrag.class.getName());
    private static final boolean TRACE = Boolean.getBoolean("limn.drag.trace");

    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int DRAGDROP_S_DROP = 0x00040100;
    private static final int DRAGDROP_S_CANCEL = 0x00040101;
    private static final int DRAGDROP_S_USEDEFAULTCURSORS = 0x00040102;
    private static final int EFFECT_COPY = 1;
    private static final int EFFECT_MOVE = 2;
    private static final int EFFECT_LINK = 4;
    private static final int MK_LBUTTON = 0x01;
    private static final int MK_SHIFT = 0x04;
    private static final int MK_CONTROL = 0x08;
    private static final int MK_ALT = 0x20;
    private static final int CF_DIB = 8;
    private static final int CF_UNICODETEXT = 13;
    private static final int CF_HDROP = 15;
    private static final int TYMED_HGLOBAL = 1;
    private static final int GMEM_MOVEABLE = 2;
    // IDataObject and IEnumFORMATETC, by their places in the tables
    private static final int RELEASE = 2;
    private static final int GET_DATA = 3;
    private static final int SET_DATA = 7;
    private static final int ENUM_FORMAT_ETC = 8;
    private static final int ENUM_NEXT = 3;

    private static final SharedLibrary OLE32 = APIUtil.apiCreateLibrary("ole32");
    private static final SharedLibrary SHELL32 = APIUtil.apiCreateLibrary("shell32");
    private static final SharedLibrary USER32 = APIUtil.apiCreateLibrary("user32");
    private static final SharedLibrary KERNEL32 = APIUtil.apiCreateLibrary("kernel32");
    private static final SharedLibrary GDI32 = APIUtil.apiCreateLibrary("gdi32");
    private static final Map<String, Long> FUNCTIONS = new HashMap<>();
    private static final Map<String, Integer> NAMED = new HashMap<>();
    private static final List<Callback> KEPT = new ArrayList<>();
    private static int htmlFormat;
    private static int pngFormat;
    private static long sourceObject;
    private static boolean dragging;
    private static long lastFeedback = -1;

    private final long hwnd;
    private final UiRuntime ui;
    private final Supplier<WindowInput> input;
    private final Supplier<float[]> pointer;
    private final Runnable paint;
    private final BooleanSupplier animate;
    private long targetObject;
    /** The shell's helper that draws a drag's picture over this window, or NULL without one. */
    private long pictureHelper;
    private long heldData;
    private @Nullable Offer offer;

    private WindowsDrag(long hwnd, UiRuntime ui, Supplier<WindowInput> input, Supplier<float[]> pointer,
            Runnable paint, BooleanSupplier animate) {
        this.hwnd = hwnd;
        this.ui = ui;
        this.input = input;
        this.pointer = pointer;
        this.paint = paint;
        this.animate = animate;
    }

    /**
     * Makes a window a source and a destination of the system's drags. UI thread.
     *
     * @param hwnd    the window
     * @param ui      the UI runtime
     * @param input   the window's input, as it is at the time of each call; may answer null
     * @param pointer the pointer in the window's logical points, x then y
     * @param paint   paints the window now, from inside OLE's own loop
     * @param animate paints the frame the window is waiting for, if any, and says whether it now
     *                waits for another
     * @return the window's drag, or null where OLE would not have it, which is logged
     */
    public static @Nullable WindowsDrag install(long hwnd, UiRuntime ui, Supplier<WindowInput> input,
            Supplier<float[]> pointer, Runnable paint, BooleanSupplier animate) {
        try {
            int initialised = (int) call(OLE32, "OleInitialize", "p>i", NULL);
            if (initialised < 0) {
                LOG.log(System.Logger.Level.INFO, "no drag and drop with other programs: OleInitialize answered 0x"
                        + Integer.toHexString(initialised));
                return null;
            }
            if (htmlFormat == 0) {
                htmlFormat = register("HTML Format");
                pngFormat = register("PNG");
            }
            WindowsDrag drag = new WindowsDrag(hwnd, ui, input, pointer, paint, animate);
            drag.targetObject = object(
                    queryInterface(0x122), // IDropTarget
                    keep(Ffi.callback("p>i", a -> 1)),
                    keep(Ffi.callback("p>i", a -> 1)),
                    keep(Ffi.callback("ppipp>i", a -> drag.over(a[1], (int) a[2], a[3], a[4], true))),
                    keep(Ffi.callback("pipp>i", a -> drag.over(NULL, (int) a[1], a[2], a[3], false))),
                    keep(Ffi.callback("p>i", a -> drag.left())),
                    keep(Ffi.callback("ppipp>i", a -> drag.drop(a[1], (int) a[2], a[3], a[4]))));
            int registered = (int) call(OLE32, "RegisterDragDrop", "pp>i", hwnd, drag.targetObject);
            if (registered < 0) {
                LOG.log(System.Logger.Level.INFO, "no drag and drop with other programs: RegisterDragDrop answered 0x"
                        + Integer.toHexString(registered));
                return null;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // IID_IDropTargetHelper {4657278B-411B-11d2-839A-00C04FD918D0}: a drag's picture
                // is drawn over a window only if the window's destination passes each call on.
                ByteBuffer clsid = guid(stack, 0x4657278A, 0x411B, 0x11d2, 0x839A00C04FD918D0L);
                ByteBuffer iid = guid(stack, 0x4657278B, 0x411B, 0x11d2, 0x839A00C04FD918D0L);
                long out = stack.ncalloc(8, 8, 1);
                if ((int) call(OLE32, "CoCreateInstance", "ppipp>i", memAddress(clsid), NULL, 1, memAddress(iid),
                        out) >= 0) {
                    drag.pictureHelper = memGetAddress(out);
                }
            }
            if (sourceObject == NULL) {
                sourceObject = object(
                        queryInterface(0x121), // IDropSource
                        keep(Ffi.callback("p>i", a -> 1)),
                        keep(Ffi.callback("p>i", a -> 1)),
                        // Escape calls it off, the button let go drops, anything else goes on.
                        keep(Ffi.callback("pii>i", a -> a[1] != 0 ? DRAGDROP_S_CANCEL
                                : (a[2] & MK_LBUTTON) == 0 ? DRAGDROP_S_DROP : S_OK)),
                        keep(Ffi.callback("pi>i", a -> {
                            if (TRACE && a[1] != lastFeedback) {
                                lastFeedback = a[1];
                                System.err.println("limn.drag(windows): the target under the pointer answers effect "
                                        + a[1]);
                            }
                            return DRAGDROP_S_USEDEFAULTCURSORS;
                        })));
            }
            return drag;
        } catch (RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.INFO, "no drag and drop with other programs", unavailable);
            return null;
        }
    }

    /** Stops the window being a destination. UI thread. */
    public void close() {
        call(OLE32, "RevokeDragDrop", "p>i", hwnd);
        if (pictureHelper != NULL) {
            method(pictureHelper, RELEASE, ">i");
            pictureHelper = NULL;
        }
        releaseHeld();
    }

    // ---- plumbing -----------------------------------------------------------------------------------

    private static long call(SharedLibrary library, String name, String signature, long... args) {
        long function;
        synchronized (FUNCTIONS) {
            function = FUNCTIONS.computeIfAbsent(name, n -> APIUtil.apiGetFunctionAddress(library, n));
        }
        return Ffi.call(function, signature, args);
    }

    /** Calls a method of a COM object by its place in the table; the object is the first argument. */
    private static long method(long object, int slot, String signature, long... args) {
        long[] all = new long[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return Ffi.call(memGetAddress(memGetAddress(object) + 8L * slot), "p" + signature, all);
    }

    private static long keep(Callback callback) {
        KEPT.add(callback);
        return callback.address();
    }

    /** A COM object that is its table and nothing else: never freed, counted by nobody. */
    private static long object(long... methods) {
        long table = nmemCalloc(methods.length, 8);
        for (int i = 0; i < methods.length; i++) {
            memPutAddress(table + 8L * i, methods[i]);
        }
        long object = nmemCalloc(1, 8);
        memPutAddress(object, table);
        return object;
    }

    /**
     * Answers itself for IUnknown and for the one interface the object is, and refuses the rest:
     * OLE asks a drop target and a drop source for others (marshalling, notifications), and an
     * object that claimed to be those would be called through a table it does not have.
     *
     * @param own the first field of the interface's identifier, the rest being OLE's common tail
     */
    private static long queryInterface(int own) {
        return keep(Ffi.callback("ppp>i", a -> {
            if (a[2] == NULL) {
                return E_NOINTERFACE;
            }
            int first = a[1] == NULL ? -1 : memGetInt(a[1]);
            boolean oles = a[1] != NULL && memGetInt(a[1] + 4) == 0
                    && org.lwjgl.system.MemoryUtil.memGetLong(a[1] + 8) == 0x46000000000000C0L;
            if (oles && (first == 0 || first == own)) {
                memPutAddress(a[2], a[0]);
                return S_OK;
            }
            memPutAddress(a[2], NULL);
            return E_NOINTERFACE;
        }));
    }

    private static int register(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return (int) call(USER32, "RegisterClipboardFormatW", "p>i", memAddress(stack.UTF16(name)));
        }
    }

    private static synchronized int format(String type) {
        return switch (type) {
            case ClipboardTypes.TEXT -> CF_UNICODETEXT;
            case ClipboardTypes.HTML -> htmlFormat;
            case ClipboardTypes.IMAGE -> pngFormat;
            case ClipboardTypes.FILES -> CF_HDROP;
            default -> NAMED.computeIfAbsent(type, WindowsDrag::register);
        };
    }

    /** A FORMATETC for a format handed over in memory: the only way this toolkit hands one. */
    private static long formatEtc(MemoryStack stack, int format) {
        long etc = stack.ncalloc(8, 32, 1);
        memPutShort(etc, (short) format);
        memPutInt(etc + 16, 1); // DVASPECT_CONTENT
        memPutInt(etc + 20, -1);
        memPutInt(etc + 24, TYMED_HGLOBAL);
        return etc;
    }

    private static long global(byte[] bytes) {
        long handle = call(KERNEL32, "GlobalAlloc", "ip>p", GMEM_MOVEABLE, Math.max(1, bytes.length));
        if (handle != NULL) {
            long data = call(KERNEL32, "GlobalLock", "p>p", handle);
            memByteBuffer(data, Math.max(1, bytes.length)).put(bytes);
            call(KERNEL32, "GlobalUnlock", "p>i", handle);
        }
        return handle;
    }

    // ---- the destination ------------------------------------------------------------------------------

    /** The types a data object offers, as the clipboard names what it holds. */
    private static List<String> typesOf(long data) {
        List<String> types = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 8, 1);
            if ((int) method(data, ENUM_FORMAT_ETC, "ip>i", 1, out) < 0 || memGetAddress(out) == NULL) {
                return types;
            }
            long formats = memGetAddress(out);
            long etc = stack.ncalloc(8, 32, 1);
            ByteBuffer name = stack.calloc(512);
            while ((int) method(formats, ENUM_NEXT, "ipp>i", 1, etc, NULL) == S_OK) {
                int format = org.lwjgl.system.MemoryUtil.memGetShort(etc) & 0xFFFF;
                long device = memGetAddress(etc + 8);
                if (device != NULL) {
                    call(OLE32, "CoTaskMemFree", "p>v", device);
                }
                if ((memGetInt(etc + 24) & TYMED_HGLOBAL) == 0) {
                    continue;
                }
                String type = null;
                if (format == CF_UNICODETEXT) {
                    type = ClipboardTypes.TEXT;
                } else if (format == htmlFormat) {
                    type = ClipboardTypes.HTML;
                } else if (format == pngFormat || format == CF_DIB) {
                    type = ClipboardTypes.IMAGE;
                } else if (format == CF_HDROP) {
                    type = ClipboardTypes.FILES;
                } else if (format >= 0xC000) {
                    int length = (int) call(USER32, "GetClipboardFormatNameW", "ipi>i", format, memAddress(name), 255);
                    if (length > 0) {
                        byte[] chars = new byte[length * 2];
                        name.duplicate().get(chars);
                        type = new String(chars, StandardCharsets.UTF_16LE);
                        synchronized (WindowsDrag.class) {
                            NAMED.putIfAbsent(type, format);
                        }
                    }
                }
                if (type != null && !types.contains(type)) {
                    types.add(type);
                }
            }
            method(formats, RELEASE, ">i");
        }
        return types;
    }

    private static byte @Nullable [] block(long data, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long medium = stack.ncalloc(8, 24, 1);
            if ((int) method(data, GET_DATA, "pp>i", formatEtc(stack, format), medium) < 0) {
                return null;
            }
            long handle = memGetAddress(medium + 8);
            byte[] out = null;
            if (memGetInt(medium) == TYMED_HGLOBAL && handle != NULL) {
                long size = call(KERNEL32, "GlobalSize", "p>p", handle);
                long bytes = call(KERNEL32, "GlobalLock", "p>p", handle);
                if (bytes != NULL && size > 0 && size <= Integer.MAX_VALUE) {
                    out = new byte[(int) size];
                    memByteBuffer(bytes, out.length).get(out);
                    call(KERNEL32, "GlobalUnlock", "p>i", handle);
                }
            }
            call(OLE32, "ReleaseStgMedium", "p>v", medium);
            return out;
        }
    }

    private static byte @Nullable [] read(long data, String type) {
        switch (type) {
            case ClipboardTypes.TEXT: {
                byte[] block = block(data, CF_UNICODETEXT);
                return block == null ? null : WindowsFormats.fromUnicodeText(block);
            }
            case ClipboardTypes.HTML: {
                byte[] block = block(data, htmlFormat);
                return block == null ? null : WindowsFormats.fromCfHtml(block);
            }
            case ClipboardTypes.IMAGE: {
                byte[] png = block(data, pngFormat);
                if (png != null) {
                    return png;
                }
                byte[] dib = block(data, CF_DIB);
                return dib == null ? null : WindowsFormats.bmpFile(dib);
            }
            case ClipboardTypes.FILES: {
                byte[] block = block(data, CF_HDROP);
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
                return block(data, format(type));
        }
    }

    private void releaseHeld() {
        if (heldData != NULL) {
            method(heldData, RELEASE, ">i");
            heldData = NULL;
        }
        offer = null;
    }

    /** What the source allows with the keys as they are, the action a bare drop proposes first. */
    private static Set<DropAction> allowed(int effects, int keys) {
        boolean control = (keys & MK_CONTROL) != 0;
        boolean shift = (keys & MK_SHIFT) != 0;
        int asked = (control && shift) || (keys & MK_ALT) != 0 ? EFFECT_LINK
                : control ? EFFECT_COPY : shift ? EFFECT_MOVE : 0;
        if ((effects & asked) != 0) {
            effects = asked;
        }
        Set<DropAction> actions = new LinkedHashSet<>();
        if ((effects & EFFECT_COPY) != 0) {
            actions.add(DropAction.COPY);
        }
        if ((effects & EFFECT_MOVE) != 0) {
            actions.add(DropAction.MOVE);
        }
        if ((effects & EFFECT_LINK) != 0) {
            actions.add(DropAction.LINK);
        }
        return actions;
    }

    private static int effect(@Nullable DropAction action) {
        return action == null ? 0 : switch (action) {
            case COPY -> EFFECT_COPY;
            case MOVE -> EFFECT_MOVE;
            case LINK -> EFFECT_LINK;
        };
    }

    /** DragEnter, with the data object, and DragOver, without: who would take it where the pointer is. */
    private long over(long data, int keys, long point, long effects, boolean entered) {
        if (entered) {
            releaseHeld();
            if (data != NULL) {
                method(data, 1, ">i"); // AddRef: read from for as long as the offer is kept
                heldData = data;
                long held = data;
                offer = PlatformClipboard.offer(typesOf(data), type -> {
                    byte[] bytes = held == heldData ? read(held, type) : null;
                    return bytes == null
                            ? CompletableFuture.failedFuture(new TransferGoneException(type + " is not dragged"))
                            : CompletableFuture.completedFuture(bytes);
                }, ui);
            }
        }
        WindowInput in = input.get();
        Offer dragged = offer;
        DropAction action = null;
        if (in != null && dragged != null && effects != NULL) {
            float[] at = pointer.get();
            action = in.dragOver(dragged, allowed(memGetInt(effects), keys), at[0], at[1]);
        }
        if (effects != NULL) {
            memPutInt(effects, effect(action));
        }
        picture(entered ? 3 : 5, entered ? data : NULL, point, effect(action));
        paint.run();
        // OLE asks again while the pointer rests, which is what keeps a row sliding.
        animate.getAsBoolean();
        return S_OK;
    }

    /**
     * Passes a destination's call on to the shell's helper, which draws the picture the source
     * gave: DragEnter (3), DragOver (5) or Drop (6), with the point as OLE handed it, x in the
     * low half and y in the high.
     */
    private void picture(int slot, long data, long point, int effect) {
        if (pictureHelper == NULL) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long at = stack.ncalloc(8, 8, 1);
            org.lwjgl.system.MemoryUtil.memPutLong(at, point);
            if (slot == 3) {
                method(pictureHelper, slot, "pppi>i", hwnd, data, at, effect);
            } else if (slot == 5) {
                method(pictureHelper, slot, "pi>i", at, effect);
            } else {
                method(pictureHelper, slot, "ppi>i", data, at, effect);
            }
        }
    }

    private long left() {
        WindowInput in = input.get();
        if (in != null) {
            in.dragLeft();
        }
        if (pictureHelper != NULL) {
            method(pictureHelper, 4, ">i"); // DragLeave
        }
        releaseHeld();
        paint.run();
        return S_OK;
    }

    private long drop(long data, int keys, long point, long effects) {
        if (data != NULL && data != heldData) {
            over(data, keys, point, effects, true);
        }
        WindowInput in = input.get();
        Offer dropped = offer;
        DropAction done = null;
        if (in != null && dropped != null && effects != NULL) {
            float[] at = pointer.get();
            done = in.dropped(dropped, allowed(memGetInt(effects), keys), at[0], at[1]);
        }
        if (effects != NULL) {
            memPutInt(effects, effect(done));
        }
        picture(6, data, point, effect(done));
        // The data object stays held until the next drag arrives: what was dropped is read after
        // this returns, when the widget that took it asks.
        paint.run();
        return S_OK;
    }

    // ---- the source -----------------------------------------------------------------------------------

    /**
     * Begins a drag the system drives. UI thread.
     *
     * @param request what is dragged
     * @return whether it was taken: the drag itself runs from the UI thread's queue, and the
     *         request hears its end
     */
    public boolean start(DragRequest request) {
        if (dragging) {
            return false;
        }
        long data;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 8, 1);
            // IID_IDataObject {0000010e-0000-0000-C000-000000000046}
            ByteBuffer iid = stack.malloc(16);
            iid.putInt(0, 0x0000010e).putShort(4, (short) 0).putShort(6, (short) 0)
                    .put(8, (byte) 0xC0).put(9, (byte) 0).put(10, (byte) 0).put(11, (byte) 0)
                    .put(12, (byte) 0).put(13, (byte) 0).put(14, (byte) 0).put(15, (byte) 0x46);
            if ((int) call(SHELL32, "SHCreateDataObject", "pipppp>i", NULL, 0, NULL, NULL,
                    memAddress(iid), out) < 0 || memGetAddress(out) == NULL) {
                return false;
            }
            data = memGetAddress(out);
            List<String> types = PlatformClipboard.types(request.content());
            Function<String, byte[]> bytes = PlatformClipboard.provider(request.content(), ui);
            for (String type : types) {
                byte[] made = bytes.apply(type);
                if (made == null) {
                    continue;
                }
                int format = format(type);
                if (format == CF_UNICODETEXT) {
                    made = WindowsFormats.unicodeText(made);
                } else if (format == htmlFormat) {
                    made = WindowsFormats.cfHtml(made);
                } else if (format == CF_HDROP) {
                    List<String> paths = new ArrayList<>();
                    for (Path file : UriLists.decode(made)) {
                        paths.add(file.toString());
                    }
                    made = WindowsFormats.dropFiles(paths);
                }
                put(stack, data, format, made);
                if (type.equals(ClipboardTypes.IMAGE)) {
                    // The bitmap beside the PNG, made from the picture's pixels: what most programs take.
                    byte[] pixels = bytes.apply(ClipboardTypes.PIXELS);
                    if (pixels != null) {
                        put(stack, data, CF_DIB, WindowsFormats.dibFromPixels(pixels));
                    }
                }
            }
            showPicture(stack, data, request);
        }
        int effects = 0;
        for (DropAction action : request.actions()) {
            effects |= effect(action);
        }
        int allowedEffects = effects;
        dragging = true;
        // From the queue, not from here: the scene has to have heard the drag was taken before
        // OLE asks this window, as a destination, who would take it.
        ui.post(() -> {
            DropAction done = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long effect = stack.ncalloc(4, 4, 1);
                int result = (int) call(OLE32, "DoDragDrop", "ppip>i", data, sourceObject, allowedEffects, effect);
                int carried = memGetInt(effect);
                if (TRACE) {
                    System.err.println("limn.drag(windows): ended 0x" + Integer.toHexString(result) + " effect " + carried);
                }
                if (result == DRAGDROP_S_DROP) {
                    done = (carried & EFFECT_MOVE) != 0 ? DropAction.MOVE : (carried & EFFECT_COPY) != 0
                            ? DropAction.COPY : (carried & EFFECT_LINK) != 0 ? DropAction.LINK : null;
                }
            } finally {
                dragging = false;
                method(data, RELEASE, ">i");
                request.ended().accept(done);
            }
        });
        return true;
    }

    private static void put(MemoryStack stack, long data, int format, byte[] bytes) {
        long handle = global(bytes);
        if (handle == NULL) {
            return;
        }
        long medium = stack.ncalloc(8, 24, 1);
        memPutInt(medium, TYMED_HGLOBAL);
        memPutAddress(medium + 8, handle);
        if ((int) method(data, SET_DATA, "ppi>i", formatEtc(stack, format), medium, 1) < 0) {
            call(KERNEL32, "GlobalFree", "p>p", handle); // not taken: still ours to free
        }
    }

    /**
     * Gives the shell the picture to show under the pointer: a bitmap with its own transparency,
     * and where in it the pointer holds it. Without one the pointer alone says a drag is on.
     */
    private void showPicture(MemoryStack stack, long data, DragRequest request) {
        Image image = request.image();
        if (image == null || image.width() <= 0 || image.height() <= 0) {
            return;
        }
        try {
            int w = image.width();
            int h = image.height();
            long info = stack.ncalloc(8, 44, 1); // BITMAPINFOHEADER, 32 bits a pixel, top row first
            memPutInt(info, 40);
            memPutInt(info + 4, w);
            memPutInt(info + 8, -h);
            memPutShort(info + 12, (short) 1);
            memPutShort(info + 14, (short) 32);
            long bits = stack.ncalloc(8, 8, 1);
            long bitmap = call(GDI32, "CreateDIBSection", "ppippi>p", NULL, info, 0, bits, NULL, 0);
            if (bitmap == NULL || memGetAddress(bits) == NULL) {
                return;
            }
            byte[] rgba = image.pixels();
            ByteBuffer out = memByteBuffer(memGetAddress(bits), w * h * 4);
            for (int i = 0; i < w * h * 4; i += 4) {
                int alpha = rgba[i + 3] & 0xFF; // blue, green, red, each already times its alpha
                out.put(i, (byte) ((rgba[i + 2] & 0xFF) * alpha / 255));
                out.put(i + 1, (byte) ((rgba[i + 1] & 0xFF) * alpha / 255));
                out.put(i + 2, (byte) ((rgba[i] & 0xFF) * alpha / 255));
                out.put(i + 3, (byte) alpha);
            }
            float[] at = pointer.get();
            float scale = request.width() > 0 ? w / request.width() : 1;
            long shown = stack.ncalloc(8, 32, 1); // SHDRAGIMAGE
            memPutInt(shown, w);
            memPutInt(shown + 4, h);
            memPutInt(shown + 8, Math.max(0, Math.min(w, Math.round((at[0] - request.x()) * scale))));
            memPutInt(shown + 12, Math.max(0, Math.min(h, Math.round((at[1] - request.y()) * scale))));
            memPutAddress(shown + 16, bitmap);
            memPutInt(shown + 24, 0xFFFFFFFF); // CLR_NONE: the alpha is the bitmap's own
            // CLSID_DragDropHelper {4657278A-411B-11d2-839A-00C04FD918D0},
            // IID_IDragSourceHelper {DE5BF786-477A-11d2-839D-00C04FD918D0}
            ByteBuffer clsid = guid(stack, 0x4657278A, 0x411B, 0x11d2, 0x839A00C04FD918D0L);
            ByteBuffer iid = guid(stack, 0xDE5BF786, 0x477A, 0x11d2, 0x839D00C04FD918D0L);
            long out2 = stack.ncalloc(8, 8, 1);
            if ((int) call(OLE32, "CoCreateInstance", "ppipp>i", memAddress(clsid), NULL, 1, memAddress(iid), out2) < 0
                    || memGetAddress(out2) == NULL) {
                call(GDI32, "DeleteObject", "p>i", bitmap);
                return;
            }
            long helper = memGetAddress(out2);
            if ((int) method(helper, 3, "pp>i", shown, data) < 0) { // InitializeFromBitmap, which takes the bitmap
                call(GDI32, "DeleteObject", "p>i", bitmap);
            }
            method(helper, RELEASE, ">i");
        } catch (RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "no picture for the drag", unavailable);
        }
    }

    private static ByteBuffer guid(MemoryStack stack, int a, int b, int c, long rest) {
        ByteBuffer guid = stack.malloc(16);
        guid.putInt(0, a).putShort(4, (short) b).putShort(6, (short) c);
        for (int i = 0; i < 8; i++) {
            guid.put(8 + i, (byte) (rest >>> (56 - 8 * i)));
        }
        return guid;
    }
}
