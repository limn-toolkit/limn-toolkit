package limn.backend.lwjgl.transfer;

import limn.backend.lwjgl.internal.ObjC;
import limn.backend.spi.DragRequest;
import limn.backend.spi.WindowInput;
import limn.concurrent.UiRuntime;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.transfer.DropAction;
import limn.transfer.Offer;
import limn.transfer.TransferGoneException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * A window's part in the system's drag and drop on macOS: it begins an {@code NSDraggingSession}
 * from the window's view, with the pasteboard items a copy would write and the picture the scene
 * cut for it, and it answers as a dragging destination for whatever is dragged over the view,
 * from this program or another.
 *
 * <p>GLFW's content view already is a destination, for file URLs alone and at the drop alone.
 * Its four destination methods are replaced on the class, once, with ours, which ask the window
 * the view belongs to; the view is registered for every kind of data, not only for files.
 *
 * <p>Everything here runs on the main thread, which is the UI thread. <b>AppKit tracks a drag
 * in a loop of its own, inside the event GLFW was dispatching</b>, as it does a window being
 * resized: the application's loop does not turn until the drag is over, and no frame is produced
 * by it. The destination therefore paints the window itself after each answer, or the widget
 * that accepted would mark where the drop lands and nobody would see it (the owner: the
 * insertion line was gone once the system drove the drag).
 *
 * <p>Over a place that refuses, the pointer is made the "not allowed" one. AppKit shows nothing
 * for a refusal; the scene's own drag does, and the owner asked for the same here.
 */
public final class MacDrag {

    private static final System.Logger LOG = System.getLogger(MacDrag.class.getName());

    // NSDragOperation
    private static final long OP_COPY = 1;
    private static final long OP_LINK = 2;
    private static final long OP_GENERIC = 4;
    private static final long OP_MOVE = 16;

    /** {@code -Dlimn.drag.trace=true}: what each drag is begun with, on standard error. */
    private static final boolean TRACE = Boolean.getBoolean("limn.drag.trace");

    private static final Map<Long, MacDrag> BY_VIEW = new ConcurrentHashMap<>();
    private static boolean classPrepared;
    private static long sourceObject;
    private static long providerObject;
    private static Callback[] kept;
    private static FFICIF frameCif;
    /** The drag this process handed to the system and has not heard the end of. */
    private static DragRequest outgoing;
    private static Function<String, byte[]> outgoingBytes = type -> null;
    private static List<String> outgoingTypes = List.of();

    private final long view;
    private final UiRuntime ui;
    private final Supplier<WindowInput> input;
    private final Supplier<float[]> pointer;
    private final float toScreen;
    private final Runnable paint;
    private final java.util.function.BooleanSupplier animate;
    private final Runnable ownCursor;
    private Offer offer;
    private long offerSequence = -1;

    private MacDrag(long view, UiRuntime ui, Supplier<WindowInput> input, Supplier<float[]> pointer,
            float toScreen, Runnable paint, java.util.function.BooleanSupplier animate, Runnable ownCursor) {
        this.paint = paint;
        this.animate = animate;
        this.ownCursor = ownCursor;
        this.view = view;
        this.ui = ui;
        this.input = input;
        this.pointer = pointer;
        this.toScreen = toScreen;
    }

    /**
     * Makes a window's view a source and a destination of the system's drags. Main thread.
     *
     * @param view     the window's content view
     * @param ui       the UI runtime
     * @param input    the window's input, as it is at the time of each call; may answer null
     * @param pointer  the pointer in the window's logical points, x then y
     * @param toScreen how many of AppKit's points one logical point is
     * @param paint     paints the window now, from inside AppKit's own loop
     * @param animate   paints the frame the window is waiting for, if any, and says whether it
     *                  now waits for another
     * @param ownCursor puts the window's own pointer back, once a drag is no longer over it
     * @return the window's drag, or null where AppKit would not have it, which is logged
     */
    public static MacDrag install(long view, UiRuntime ui, Supplier<WindowInput> input,
            Supplier<float[]> pointer, float toScreen, Runnable paint, java.util.function.BooleanSupplier animate,
            Runnable ownCursor) {
        try {
            prepareClasses(view);
            MacDrag drag = new MacDrag(view, ui, input, pointer, toScreen, paint, animate, ownCursor);
            BY_VIEW.put(view, drag);
            long pool = MacClipboard.pool();
            try {
                // Everything a pasteboard can hold conforms to one of these.
                long types = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
                for (String type : List.of("public.data", "public.item", "public.content", "public.url",
                        "public.file-url", "public.utf8-plain-text", "public.html", "public.png", "public.tiff")) {
                    ObjC.msgVoid(types, "addObject:", MacClipboard.nsString(type));
                }
                ObjC.msgVoid(view, "registerForDraggedTypes:", types);
            } finally {
                MacClipboard.drain(pool);
            }
            return drag;
        } catch (RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.WARNING, "drags stay inside the window: AppKit's side did not open",
                    unavailable);
            return null;
        }
    }

    /** Forgets a view that is going away. */
    public void close() {
        BY_VIEW.remove(view);
    }

    private static synchronized void prepareClasses(long view) {
        if (classPrepared) {
            return;
        }
        // The destination: GLFW's view class, its four methods ours from now on.
        long viewClass = ObjC.msg(view, "class");
        Callback entered = Ffi.callback("ppp>p", a -> over(a[0], a[2]));
        Callback exited = Ffi.callback("ppp>v", a -> {
            MacDrag drag = BY_VIEW.get(a[0]);
            WindowInput in = drag == null ? null : drag.input.get();
            if (drag != null) {
                drag.offer = null;
                drag.offerSequence = -1;
            }
            if (in != null) {
                in.dragLeft();
            }
            if (drag != null) {
                drag.ownCursor.run(); // the pointer this destination set is not left behind
                drag.paint.run();
            }
            return 0;
        });
        Callback perform = Ffi.callback("ppp>i", a -> drop(a[0], a[2]));
        ObjCRuntime.class_replaceMethod(viewClass, ObjC.sel("draggingEntered:"), entered.address(), "Q@:@");
        ObjCRuntime.class_replaceMethod(viewClass, ObjC.sel("draggingUpdated:"), entered.address(), "Q@:@");
        ObjCRuntime.class_replaceMethod(viewClass, ObjC.sel("draggingExited:"), exited.address(), "v@:@");
        ObjCRuntime.class_replaceMethod(viewClass, ObjC.sel("performDragOperation:"), perform.address(), "c@:@");

        // The source: an object that says what may be done and hears the end.
        long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), "LimnDraggingSource", 0);
        if (made == NULL) {
            throw new IllegalStateException("could not make LimnDraggingSource");
        }
        Callback mask = Ffi.callback("pppp>p", a -> {
            DragRequest request = outgoing;
            long allowed = 0;
            if (request != null) {
                for (DropAction action : request.actions()) {
                    allowed |= operation(action);
                }
                // With Command held AppKit keeps only the generic operation of this mask, which
                // is how a move is asked for there: a source that allows a move and did not say
                // generic was left with nothing, and the pointer said "not allowed" everywhere.
                if (request.actions().contains(DropAction.MOVE)) {
                    allowed |= OP_GENERIC;
                }
            }
            return allowed;
        });
        // draggingSession:endedAtPoint:operation: takes a point, two doubles, between the session
        // and the operation. They travel in floating-point registers on both architectures this
        // runs on, so the operation is the fourth integer argument whether they are declared or not.
        Callback ended = Ffi.callback("pppp>v", a -> {
            DragRequest request = outgoing;
            DropAction generic = genericMeans();
            outgoing = null;
            outgoingBytes = type -> null;
            outgoingTypes = List.of();
            if (request != null) {
                DropAction done = action(a[3]);
                if (done == null && (a[3] & OP_GENERIC) != 0) {
                    done = generic;
                }
                request.ended().accept(done);
            }
            for (MacDrag drag : BY_VIEW.values()) {
                drag.ownCursor.run(); // wherever it ended, no window keeps a drag's pointer
            }
            return 0;
        });
        ObjCRuntime.class_addMethod(made, ObjC.sel("draggingSession:sourceOperationMaskForDraggingContext:"),
                mask.address(), "Q@:@q");
        ObjCRuntime.class_addMethod(made, ObjC.sel("draggingSession:endedAtPoint:operation:"),
                ended.address(), "v@:@{CGPoint=dd}Q");
        long protocol = ObjCRuntime.objc_getProtocol("NSDraggingSource");
        if (protocol != NULL) {
            ObjCRuntime.class_addProtocol(made, protocol);
        }
        ObjCRuntime.objc_registerClassPair(made);
        sourceObject = ObjC.msg(ObjC.msg(made, "alloc"), "init");
        providerObject = MacClipboard.newProvider((item, identifier) -> {
            for (String type : outgoingTypes) {
                if (MacClipboard.itemType(type).equals(identifier)) {
                    byte[] data = outgoingBytes.apply(type);
                    if (data != null) {
                        ObjC.msg(item, "setData:forType:", MacClipboard.nsData(data),
                                MacClipboard.nsString(identifier));
                    }
                    return;
                }
            }
        });
        // void objc_msgSend(id, SEL, NSRect, id): setDraggingFrame:contents:
        frameCif = APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                rect(), LibFFI.ffi_type_pointer);
        kept = new Callback[] {entered, exited, perform, mask, ended};
        classPrepared = true;
    }

    private static FFIType rect() {
        PointerBuffer elements = org.lwjgl.system.MemoryUtil.memAllocPointer(5);
        for (int i = 0; i < 4; i++) {
            elements.put(i, LibFFI.ffi_type_double.address());
        }
        elements.put(4, NULL);
        FFIType type = FFIType.calloc();
        type.type(LibFFI.FFI_TYPE_STRUCT);
        type.elements(elements);
        return type;
    }

    private static long operation(DropAction action) {
        return switch (action) {
            case COPY -> OP_COPY;
            case MOVE -> OP_MOVE;
            case LINK -> OP_LINK;
        };
    }

    /**
     * What the generic operation stands for: a move when the drag is this program's own and its
     * source allows one, which is what Command asks for; a copy from a program that says no more.
     */
    private static DropAction genericMeans() {
        DragRequest request = outgoing;
        return request != null && request.actions().contains(DropAction.MOVE) ? DropAction.MOVE : DropAction.COPY;
    }

    // ---- frames while AppKit drives -----------------------------------------------------------------

    private static final long BEFORE_WAITING = 1 << 5; // kCFRunLoopBeforeWaiting
    private static long frameObserver;
    private static long mainLoop;
    private static long wakeUp;
    private static Callback frameCallback;

    /**
     * Has the windows painted while a drag of ours lasts. AppKit's loop runs in place of the
     * application's then, so nothing asks for the frames an animation needs: a row sliding to
     * make room stood still until the pointer moved. An observer of the main run loop, in the
     * modes AppKit tracks a drag in, paints each window that waits for a frame whenever the loop
     * is about to sleep, and wakes it again for as long as one of them waits for another; a
     * frame is paced by its own presentation.
     */
    private static void watchFrames() {
        if (frameObserver != NULL) {
            return;
        }
        try {
            org.lwjgl.system.SharedLibrary cf = APIUtil.apiCreateLibrary(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            long modes = org.lwjgl.system.MemoryUtil.memGetAddress(cf.getFunctionAddress("kCFRunLoopCommonModes"));
            mainLoop = Ffi.call(cf.getFunctionAddress("CFRunLoopGetMain"), ">p");
            wakeUp = cf.getFunctionAddress("CFRunLoopWakeUp");
            frameCallback = Ffi.callback("ppp>v", a -> {
                boolean more = false;
                if (outgoing != null) {
                    for (MacDrag drag : BY_VIEW.values()) {
                        more |= drag.animate.getAsBoolean();
                    }
                }
                if (more) {
                    Ffi.call(wakeUp, "p>v", mainLoop);
                }
                return 0;
            });
            frameObserver = Ffi.call(cf.getFunctionAddress("CFRunLoopObserverCreate"), "pppppp>p",
                    NULL, BEFORE_WAITING, 1, 0, frameCallback.address(), NULL);
            Ffi.call(cf.getFunctionAddress("CFRunLoopAddObserver"), "ppp>v", mainLoop, frameObserver, modes);
        } catch (RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "no frames while the system drives a drag", unavailable);
        }
    }

    private static DropAction action(long operation) {
        return (operation & OP_MOVE) != 0 ? DropAction.MOVE : (operation & OP_COPY) != 0 ? DropAction.COPY
                : (operation & OP_LINK) != 0 ? DropAction.LINK : null;
    }

    // ---- the source ---------------------------------------------------------------------------------

    /**
     * Begins the system's drag of what the scene asks. Main thread, while a mouse button is down.
     *
     * @param request what is dragged
     * @return whether AppKit took it
     */
    public boolean start(DragRequest request) {
        long pool = MacClipboard.pool();
        try {
            long event = ObjC.msg(ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication"), "currentEvent");
            long type = event == NULL ? 0 : ObjC.msg(event, "type");
            if (TRACE) {
                limn.graphics.Image image = request.image();
                int seen = 0;
                if (image != null) {
                    byte[] rgba = image.pixels();
                    for (int i = 3; i < rgba.length; i += 4) {
                        seen += rgba[i] != 0 ? 1 : 0;
                    }
                }
                System.err.println("limn.drag: asked, picture="
                        + (image == null ? "none" : image.width() + "x" + image.height() + " px, " + seen
                                + " not transparent")
                        + " at " + request.x() + "," + request.y() + " " + request.width() + "x" + request.height()
                        + " event type " + type);
            }
            if (type != 1 && type != 6) { // a left mouse down, or a left mouse dragged
                if (TRACE) {
                    System.err.println("limn.drag: not begun, the current event is of type " + type);
                }
                return false;
            }
            List<String> types = PlatformClipboard.types(request.content());
            Function<String, byte[]> bytes = PlatformClipboard.provider(request.content(), ui);
            long items = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
            long first = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSPasteboardItem"), "alloc"), "init"), "autorelease");
            long promised = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
            addDraggingItem(items, first, request, true);
            for (String kind : types) {
                if (kind.equals(ClipboardTypes.FILES)) {
                    byte[] list = bytes.apply(kind);
                    long item = first;
                    for (Path file : list == null ? List.<Path>of() : UriLists.decode(list)) {
                        long url = ObjC.msg(ObjC.cls("NSURL"), "fileURLWithPath:", MacClipboard.nsString(file.toString()));
                        if (item == NULL) {
                            item = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSPasteboardItem"), "alloc"), "init"),
                                    "autorelease");
                            addDraggingItem(items, item, request, false);
                        }
                        ObjC.msg(item, "setString:forType:", ObjC.msg(url, "absoluteString"),
                                MacClipboard.nsString("public.file-url"));
                        item = NULL; // one file an item
                    }
                } else {
                    ObjC.msgVoid(promised, "addObject:", MacClipboard.nsString(MacClipboard.itemType(kind)));
                }
            }
            if (ObjC.msg(promised, "count") > 0) {
                ObjC.msg(first, "setDataProvider:forTypes:", providerObject, promised);
            }
            outgoing = request;
            outgoingTypes = types;
            outgoingBytes = bytes;
            watchFrames();
            long session = ObjC.msg(view, "beginDraggingSessionWithItems:event:source:", items, event, sourceObject);
            if (session == NULL) {
                outgoing = null;
                return false;
            }
            return true;
        } catch (RuntimeException failed) {
            outgoing = null;
            LOG.log(System.Logger.Level.WARNING, "the system's drag did not begin", failed);
            return false;
        } finally {
            MacClipboard.drain(pool);
        }
    }

    /** Wraps a pasteboard item as a dragging item at the picture's place, the first one with the picture. */
    private void addDraggingItem(long items, long pasteboardItem, DragRequest request, boolean withPicture) {
        long dragging = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSDraggingItem"), "alloc"),
                "initWithPasteboardWriter:", pasteboardItem), "autorelease");
        long picture = NULL;
        if (withPicture && request.image() != null) {
            byte[] png = Images.encode(request.image(), ImageFormat.PNG);
            picture = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSImage"), "alloc"), "initWithData:",
                    MacClipboard.nsData(png)), "autorelease");
        }
        // The view's coordinates start at its bottom left; the scene's at its top left.
        float viewHeight = pointer.get()[2];
        double width = Math.max(1, request.width()) * toScreen;
        double height = Math.max(1, request.height()) * toScreen;
        double x = request.x() * toScreen;
        double y = (viewHeight - request.y() - Math.max(1, request.height())) * toScreen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer storage = stack.malloc(8 + 8 + 32 + 8);
            storage.putLong(0, dragging).putLong(8, ObjC.sel("setDraggingFrame:contents:"))
                    .putDouble(16, x).putDouble(24, y).putDouble(32, width).putDouble(40, height)
                    .putLong(48, picture);
            long base = memAddress(storage);
            PointerBuffer values = stack.mallocPointer(4);
            values.put(0, base).put(1, base + 8).put(2, base + 16).put(3, base + 48);
            LibFFI.ffi_call(frameCif, ObjC.msgSend(), stack.malloc(8), values);
        }
        ObjC.msgVoid(items, "addObject:", dragging);
    }

    // ---- the destination ------------------------------------------------------------------------------

    /** What the source allows with the modifier keys as they are, the action a bare drop proposes first. */
    private static Set<DropAction> allowed(long sender) {
        long mask = ObjC.msg(sender, "draggingSourceOperationMask");
        Set<DropAction> actions = new LinkedHashSet<>();
        // Generic alone is what Command leaves of the mask: the move, for a drag of our own.
        boolean genericOnly = (mask & OP_GENERIC) != 0 && (mask & (OP_COPY | OP_MOVE)) == 0;
        if ((mask & OP_COPY) != 0 || (genericOnly && genericMeans() == DropAction.COPY)) {
            actions.add(DropAction.COPY);
        }
        if ((mask & OP_MOVE) != 0 || (genericOnly && genericMeans() == DropAction.MOVE)) {
            actions.add(DropAction.MOVE);
        }
        if ((mask & OP_LINK) != 0) {
            actions.add(DropAction.LINK);
        }
        return actions;
    }

    /** The offer of the drag over this view: one for the whole of a drag, read from its pasteboard. */
    private Offer offerOf(long sender) {
        long sequence = ObjC.msg(sender, "draggingSequenceNumber");
        if (offer == null || sequence != offerSequence) {
            long pasteboard = ObjC.msg(sender, "draggingPasteboard");
            ObjC.msg(pasteboard, "retain"); // read from for as long as the offer is kept
            List<String> types = MacClipboard.typesOf(pasteboard);
            offer = PlatformClipboard.offer(types, type -> {
                long pool = MacClipboard.pool();
                try {
                    byte[] bytes = MacClipboard.readNow(pasteboard, type);
                    return bytes == null
                            ? CompletableFuture.failedFuture(new TransferGoneException(type + " is not dragged"))
                            : CompletableFuture.completedFuture(bytes);
                } finally {
                    MacClipboard.drain(pool);
                }
            }, ui);
            offerSequence = sequence;
        }
        return offer;
    }

    private static long over(long view, long sender) {
        MacDrag drag = BY_VIEW.get(view);
        WindowInput in = drag == null ? null : drag.input.get();
        if (drag == null || in == null) {
            return 0;
        }
        float[] at = drag.pointer.get();
        DropAction action = in.dragOver(drag.offerOf(sender), allowed(sender), at[0], at[1]);
        drag.paint.run();
        // The pointer for the answer, each time: AppKit leaves the last one set in place, so a
        // "not allowed" stayed after the pointer had moved on to a place that accepts.
        String cursor = action == null ? "operationNotAllowedCursor"
                : action == DropAction.COPY ? "dragCopyCursor"
                : action == DropAction.LINK ? "dragLinkCursor" : "arrowCursor";
        ObjC.msgVoid(ObjC.msg(ObjC.cls("NSCursor"), cursor), "set");
        if (action == null) {
            return 0;
        }
        // Answered in the terms the mask is in: an operation outside it is no operation to AppKit.
        long mask = ObjC.msg(sender, "draggingSourceOperationMask");
        long answer = operation(action);
        return (mask & answer) != 0 ? answer : (mask & OP_GENERIC) != 0 ? OP_GENERIC : answer;
    }

    private static long drop(long view, long sender) {
        MacDrag drag = BY_VIEW.get(view);
        WindowInput in = drag == null ? null : drag.input.get();
        if (drag == null || in == null) {
            return 0;
        }
        float[] at = drag.pointer.get();
        Offer dropped = drag.offerOf(sender);
        drag.offer = null;
        drag.offerSequence = -1;
        boolean taken = in.dropped(dropped, allowed(sender), at[0], at[1]) != null;
        drag.ownCursor.run();
        drag.paint.run();
        return taken ? 1 : 0;
    }
}
