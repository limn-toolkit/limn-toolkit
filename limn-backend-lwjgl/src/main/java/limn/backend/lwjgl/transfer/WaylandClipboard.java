package limn.backend.lwjgl.transfer;

import limn.transfer.TransferGoneException;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The Wayland clipboard: a {@code wl_data_device} of our own on the display GLFW opened.
 *
 * <p>GLFW has one too and keeps it; a client may have several, and each is told of the selection.
 * Ours needs three things GLFW does not give away. A {@code wl_seat} bound again from a registry
 * of ours, since GLFW's proxy is private. The serial of the last key or button, which
 * {@code set_selection} must quote and GLFW does not expose: a {@code wl_keyboard} and a
 * {@code wl_pointer} of ours hear the same input for that number alone. And <b>an event queue of
 * our own, dispatched by a thread of ours</b>: GLFW reads what another client offers with a
 * blocking {@code read()} on the UI thread, so when this process is the one offering, the request
 * to send can only be served from another thread. On the default queue that is a deadlock,
 * measured on KWin on 2026-10-08, and with the queue it is not.
 *
 * <p>Bytes cross through pipes, read and written on short-lived threads so that neither the UI
 * thread nor the dispatch thread ever waits on another program. A file descriptor is opened as a
 * stream through {@code /proc/self/fd}, which is what spares this class a binding of
 * {@code read} and {@code write}.
 */
public final class WaylandClipboard implements NativeClipboard {

    private static final System.Logger LOG = System.getLogger(WaylandClipboard.class.getName());

    /** A selection larger than this is refused: nothing sane is, and a hostile source is unbounded. */
    private static final int MAX_BYTES = 512 << 20;

    private static final int SEAT_VERSION = 5;
    private static final int MANAGER_VERSION = 3;
    private static final int CAPABILITY_POINTER = 1;
    private static final int CAPABILITY_KEYBOARD = 2;

    private final Wl wl;
    private final long display;
    private final long queue;
    private final long wrapper;
    private final long registry;
    private final Thread dispatcher;

    private final long offerListener;
    private final long sourceListener;
    private final long keyboardListener;
    private final long pointerListener;

    /** Guards every proxy below against being ended on one thread while another sends on it. */
    private final Object lock = new Object();
    private long seat;
    private long manager;
    private long device;
    private long keyboard;
    private long pointer;
    private final Map<Long, List<String>> offers = new HashMap<>();
    private long selection;
    /** The drag (its offer's data) the last answer to a motion went to. Under {@link #lock}. */
    private final AnsweredDrag dragAnswered = new AnsweredDrag();
    private long dragOffer;
    private long ownSource;
    private final Map<Long, Function<String, byte[]>> sources = new HashMap<>();

    private volatile List<String> offered = List.of();
    private volatile boolean owner;
    private volatile int serial;
    private volatile Runnable changeListener = () -> { };
    private volatile boolean closing;

    // ---- drags (the dispatch thread's, under the lock, but where said) ----------------------------
    private static final boolean TRACE = Boolean.getBoolean("limn.drag.trace");
    private static final int ACTION_COPY = 1;
    private static final int ACTION_MOVE = 2;
    /** What the UI thread last answered a drag over a window of ours, for the trace alone. */
    private limn.transfer.@org.jspecify.annotations.Nullable DropAction lastAnswered;
    /** For how long the files the window library read of a drop stand for that drop's files. */
    private static final long FILES_ELSEWHERE_NANOS = 5_000_000_000L;
    private volatile @org.jspecify.annotations.Nullable List<java.nio.file.Path> filesReadElsewhere;
    private volatile long filesReadElsewhereAt;
    private long compositor;
    private long shm;
    /** The serial of the last button pressed: what the compositor asks a drag to begin with. */
    private volatile int pressSerial;
    /** This program's windows, by their surfaces, as destinations. */
    private final Map<Long, Window> windows = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, int[]> offerActions = new HashMap<>();
    private Window dragOver;
    private int dragSerial;
    private float dragX;
    private float dragY;
    private limn.transfer.Offer dragData;
    private int dragReads;
    private boolean dragDropped;
    private boolean dragTaken;
    /** A drag of ours: its source, who hears its end, and what the compositor last said it would do. */
    private long dragSource;
    private limn.backend.spi.DragRequest dragRequest;
    private int dragAction;
    private boolean dragPerformed;
    private long iconSurface;
    private long iconBuffer;

    /** One of this program's windows, as a destination. */
    private static final class Window {
        final long surface;
        final java.util.function.Supplier<limn.backend.spi.WindowInput> input;
        final java.util.function.DoubleUnaryOperator toLogical;
        final limn.concurrent.UiRuntime ui;

        Window(long surface, java.util.function.Supplier<limn.backend.spi.WindowInput> input,
                java.util.function.DoubleUnaryOperator toLogical, limn.concurrent.UiRuntime ui) {
            this.surface = surface;
            this.input = input;
            this.toLogical = toLogical;
            this.ui = ui;
        }
    }

    /**
     * @param display GLFW's {@code wl_display}
     * @throws RuntimeException when the library is absent or the compositor has no seat or no
     *                          data device manager; the caller then keeps GLFW's text clipboard
     */
    public WaylandClipboard(long display) {
        this.display = display;
        wl = new Wl();
        queue = JNI.invokePP(display, wl.function("wl_display_create_queue"));
        wrapper = JNI.invokePP(display, wl.function("wl_proxy_create_wrapper"));
        JNI.invokePPV(wrapper, queue, wl.function("wl_proxy_set_queue"));

        offerListener = wl.table(
                wl.on("ppp", a -> offerType(a[1], a[2])),
                wl.on("ppi", a -> offerActions(a[1], (int) a[2], -1)),  // source_actions
                wl.on("ppi", a -> offerActions(a[1], -1, (int) a[2])));  // action
        sourceListener = wl.table(
                wl.ignore("ppp"),                                     // target
                wl.on("pppi", a -> send(a[1], memUTF8(a[2]), (int) a[3])),
                wl.on("pp", a -> cancelled(a[1])),
                wl.on("pp", a -> dragPerformed |= a[1] == dragSource), // dnd_drop_performed
                wl.on("pp", a -> dragFinished(a[1])),                 // dnd_finished
                wl.on("ppi", a -> {                                   // action
                    if (a[1] == dragSource) {
                        dragAction = (int) a[2];
                    }
                }));
        keyboardListener = wl.table(
                wl.on("ppiii", a -> wl.close((int) a[3])),            // keymap: the fd is ours to close
                wl.on("ppipp", a -> serial = (int) a[2]),             // enter
                wl.ignore("ppip"),                                    // leave
                wl.on("ppiiii", a -> serial = (int) a[2]),            // key
                wl.ignore("ppiiiii"),                                 // modifiers
                wl.ignore("ppii"));                                   // repeat_info
        pointerListener = wl.table(
                wl.ignore("ppipii"),                                  // enter
                wl.ignore("ppip"),                                    // leave
                wl.on("ppiii", a -> {                                 // motion
                    pointerX = (int) a[3] / 256f;
                    pointerY = (int) a[4] / 256f;
                }),
                wl.on("ppiiii", a -> {                                // button
                    serial = (int) a[2];
                    if (a[5] != 0) {
                        pressSerial = (int) a[2];
                    }
                }),
                wl.ignore("ppiii"),                                   // axis
                wl.ignore("pp"),                                      // frame
                wl.ignore("ppi"),                                     // axis_source
                wl.ignore("ppii"),                                    // axis_stop
                wl.ignore("ppii"));                                   // axis_discrete

        int[] seatGlobal = new int[2];
        int[] managerGlobal = new int[2];
        int[] compositorGlobal = new int[2];
        int[] shmGlobal = new int[2];
        int[] activationGlobal = new int[2];
        registry = wl.request(wrapper, 1, wl.function("wl_registry_interface"), 0, 0L);
        wl.listen(registry, wl.table(
                wl.on("ppipi", a -> {
                    String name = memUTF8(a[3]);
                    if (name.equals("wl_seat") && seatGlobal[1] == 0) {
                        seatGlobal[0] = (int) a[2];
                        seatGlobal[1] = (int) a[4];
                    } else if (name.equals("wl_data_device_manager") && managerGlobal[1] == 0) {
                        managerGlobal[0] = (int) a[2];
                        managerGlobal[1] = (int) a[4];
                    } else if (name.equals("wl_compositor") && compositorGlobal[1] == 0) {
                        compositorGlobal[0] = (int) a[2];
                        compositorGlobal[1] = (int) a[4];
                    } else if (name.equals("wl_shm") && shmGlobal[1] == 0) {
                        shmGlobal[0] = (int) a[2];
                        shmGlobal[1] = (int) a[4];
                    } else if (name.equals(ACTIVATION) && activationGlobal[1] == 0) {
                        activationGlobal[0] = (int) a[2];
                        activationGlobal[1] = (int) a[4];
                    }
                }),
                wl.ignore("ppi")));
        roundtrip();
        if (seatGlobal[1] == 0 || managerGlobal[1] == 0) {
            wl.free();
            throw new IllegalStateException("the compositor offers no "
                    + (seatGlobal[1] == 0 ? "wl_seat" : "wl_data_device_manager"));
        }
        seat = wl.bind(registry, seatGlobal[0], wl.function("wl_seat_interface"), "wl_seat",
                Math.min(SEAT_VERSION, seatGlobal[1]));
        manager = wl.bind(registry, managerGlobal[0], wl.function("wl_data_device_manager_interface"),
                "wl_data_device_manager", Math.min(MANAGER_VERSION, managerGlobal[1]));
        if (activationGlobal[1] != 0) {
            long[] interfaces = activationInterfaces();
            tokenInterface = interfaces[1];
            activation = wl.bind(registry, activationGlobal[0], interfaces[0], ACTIVATION, 1);
        }
        if (compositorGlobal[1] != 0 && shmGlobal[1] != 0) {
            // For the picture a drag shows: a surface of ours and the memory it is drawn from.
            compositor = wl.bind(registry, compositorGlobal[0], wl.function("wl_compositor_interface"),
                    "wl_compositor", Math.min(4, compositorGlobal[1]));
            shm = wl.bind(registry, shmGlobal[0], wl.function("wl_shm_interface"), "wl_shm", 1);
        }
        wl.listen(seat, wl.table(
                wl.on("ppi", a -> capabilities((int) a[2])),
                wl.ignore("ppp")));                                   // name
        device = wl.request(manager, 1, wl.function("wl_data_device_interface"), 0, 0L, seat);
        wl.listen(device, wl.table(
                wl.on("ppp", a -> newOffer(a[2])),                    // data_offer
                wl.on("ppipiip", a -> dragEntered((int) a[2], a[3], (int) a[4], (int) a[5], a[6])), // enter
                wl.on("pp", a -> dragLeft()),                         // leave
                wl.on("ppiii", a -> dragMoved((int) a[3], (int) a[4])), // motion
                wl.on("pp", a -> dragDrop()),                         // drop
                wl.on("ppp", a -> selected(a[2]))));                  // selection
        roundtrip(); // the seat's capabilities
        roundtrip(); // the keyboard's first events, and the selection if this client has the focus

        long dispatch = wl.function("wl_display_dispatch_queue");
        dispatcher = new Thread(() -> {
            while (!closing && JNI.invokePPI(display, queue, dispatch) >= 0) {
                // every event of our proxies is handled here
            }
        }, "limn-wayland-data");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    private void roundtrip() {
        JNI.invokePPI(display, queue, wl.function("wl_display_roundtrip_queue"));
    }

    // ---- the user's consent to come in front ----------------------------------------------------

    private static final String ACTIVATION = "xdg_activation_v1";
    /** The activation manager, or {@code NULL} on a compositor that has none. */
    private long activation;
    private long tokenInterface;

    /**
     * The two interfaces of the activation protocol, laid out as the client library reads them.
     * It exports its own protocol's and not this one's, and what is sent through a proxy is
     * marshalled from these tables: a name, a version, and each request and event with its
     * signature and the interfaces of its object arguments.
     *
     * @return the manager's interface and the token's
     */
    private long[] activationInterfaces() {
        long surface = wl.function("wl_surface_interface");
        long seatInterface = wl.function("wl_seat_interface");
        long token = org.lwjgl.system.MemoryUtil.nmemCalloc(1, 40);
        long manager = org.lwjgl.system.MemoryUtil.nmemCalloc(1, 40);
        long tokenRequests = messages(
                message("set_serial", "uo", NULL, seatInterface),
                message("set_app_id", "s", NULL),
                message("set_surface", "o", surface),
                message("commit", ""),
                message("destroy", ""));
        long tokenEvents = messages(message("done", "s", NULL));
        fillInterface(token, "xdg_activation_token_v1", 5, tokenRequests, 1, tokenEvents);
        long managerRequests = messages(
                message("destroy", ""),
                message("get_activation_token", "n", token),
                message("activate", "so", NULL, surface));
        fillInterface(manager, ACTIVATION, 3, managerRequests, 0, NULL);
        return new long[] {manager, token};
    }

    /** One message: its name, its signature and the interface of each argument, kept for good. */
    private static long[] message(String name, String signature, long... types) {
        long typeTable = org.lwjgl.system.MemoryUtil.nmemCalloc(Math.max(1, types.length), 8);
        for (int i = 0; i < types.length; i++) {
            org.lwjgl.system.MemoryUtil.memPutAddress(typeTable + 8L * i, types[i]);
        }
        return new long[] {cString(name), cString(signature), typeTable};
    }

    private static long messages(long[]... each) {
        long table = org.lwjgl.system.MemoryUtil.nmemCalloc(each.length, 24);
        for (int i = 0; i < each.length; i++) {
            for (int j = 0; j < 3; j++) {
                org.lwjgl.system.MemoryUtil.memPutAddress(table + 24L * i + 8L * j, each[i][j]);
            }
        }
        return table;
    }

    private static void fillInterface(long at, String name, int requests, long requestTable, int events,
            long eventTable) {
        org.lwjgl.system.MemoryUtil.memPutAddress(at, cString(name));
        org.lwjgl.system.MemoryUtil.memPutInt(at + 8, 1);
        org.lwjgl.system.MemoryUtil.memPutInt(at + 12, requests);
        org.lwjgl.system.MemoryUtil.memPutAddress(at + 16, requestTable);
        org.lwjgl.system.MemoryUtil.memPutInt(at + 24, events);
        org.lwjgl.system.MemoryUtil.memPutAddress(at + 32, eventTable);
    }

    private static long cString(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long memory = org.lwjgl.system.MemoryUtil.nmemCalloc(1, bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            org.lwjgl.system.MemoryUtil.memPutByte(memory + i, bytes[i]);
        }
        return memory;
    }

    /** @return whether this compositor can be shown a token */
    public boolean canActivate() {
        return activation != NULL;
    }

    /**
     * Asks the compositor to bring a surface in front, with a token that says the user asked.
     * Any thread.
     *
     * @param token   the token, from a panel or from {@link #requestToken}
     * @param surface the window's {@code wl_surface}
     * @return whether the request was sent; what the compositor does with it is its own
     */
    public boolean activate(String token, long surface) {
        if (activation == NULL || surface == NULL || closing) {
            return false;
        }
        long text = cString(token);
        try {
            wl.request(activation, 2, NULL, 0, text, surface);
            wl.flush(display);
            return true;
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(text);
        }
    }

    /** The requests for a token that have not been answered, by their proxy. */
    private final java.util.Map<Long, CompletableFuture<String>> tokens = new java.util.HashMap<>();
    private long tokenListener;

    /**
     * Asks the compositor for a token that says the user is acting in a surface of ours now: the
     * last key or button it was given, and the surface. A program handed the token may come in
     * front of that surface. Any thread.
     *
     * @param surface the {@code wl_surface} the user is acting in
     * @return the token, or an empty string where the compositor gives none; completed on the
     *         thread that hears the compositor, or at once
     */
    public CompletableFuture<String> requestToken(long surface) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        if (activation == NULL || surface == NULL || closing) {
            answer.complete("");
            return answer;
        }
        long request = wl.request(activation, 1, tokenInterface, 0, 0L);
        if (request == NULL) {
            answer.complete("");
            return answer;
        }
        synchronized (tokens) {
            if (tokenListener == NULL) {
                // One listener for every request, which answers the request the event names: a
                // listener made per request was a callback and a table kept until the clipboard
                // closed, one for each program or address the application ever opened.
                tokenListener = wl.table(wl.on("ppp", a -> {
                    CompletableFuture<String> waiting;
                    synchronized (tokens) {
                        waiting = tokens.remove(a[1]);
                    }
                    if (waiting != null) {
                        waiting.complete(a[2] == NULL ? "" : memUTF8(a[2]));
                    }
                    wl.request(a[1], 4, NULL, Wl.DESTROY);   // done is the token's one event
                }));
            }
            tokens.put(request, answer);
        }
        wl.listen(request, tokenListener);
        wl.request(request, 0, NULL, 0, Integer.toUnsignedLong(serial), seat);
        wl.request(request, 2, NULL, 0, surface);
        wl.request(request, 3, NULL, 0);
        wl.flush(display);
        return answer;
    }

    // ---- events, on the dispatch thread (or inside a roundtrip of the constructor) --------------

    private void capabilities(int caps) {
        synchronized (lock) {
            if ((caps & CAPABILITY_KEYBOARD) != 0 && keyboard == NULL) {
                keyboard = wl.request(seat, 1, wl.function("wl_keyboard_interface"), 0, 0L);
                wl.listen(keyboard, keyboardListener);
            } else if ((caps & CAPABILITY_KEYBOARD) == 0 && keyboard != NULL) {
                wl.request(keyboard, 0, NULL, Wl.DESTROY);            // release
                keyboard = NULL;
            }
            if ((caps & CAPABILITY_POINTER) != 0 && pointer == NULL) {
                pointer = wl.request(seat, 0, wl.function("wl_pointer_interface"), 0, 0L);
                wl.listen(pointer, pointerListener);
            } else if ((caps & CAPABILITY_POINTER) == 0 && pointer != NULL) {
                wl.request(pointer, 1, NULL, Wl.DESTROY);             // release
                pointer = NULL;
            }
        }
    }

    private void newOffer(long offer) {
        synchronized (lock) {
            offers.put(offer, new ArrayList<>());
            wl.listen(offer, offerListener);
        }
    }

    private void offerType(long offer, long mime) {
        synchronized (lock) {
            List<String> types = offers.get(offer);
            if (types != null) {
                types.add(memUTF8(mime));
            }
        }
    }

    private void selected(long offer) {
        synchronized (lock) {
            if (selection != NULL && selection != offer) {
                destroyOffer(selection);
            }
            selection = offer;
            offered = offer == NULL ? List.of() : List.copyOf(offers.getOrDefault(offer, List.of()));
        }
        changeListener.run();
    }

    // ---- a drag over one of our windows ---------------------------------------------------------

    private void offerActions(long offer, int source, int chosen) {
        synchronized (lock) {
            int[] actions = offerActions.computeIfAbsent(offer, o -> new int[2]);
            if (source >= 0) {
                actions[0] = source;
            }
            if (chosen >= 0) {
                actions[1] = chosen;
            }
        }
    }

    private void dragEntered(int enterSerial, long surface, int x, int y, long offer) {
        synchronized (lock) {
            if (dragOffer != NULL && dragOffer != offer && !dragDropped) {
                destroyOffer(dragOffer);
            }
            dragOffer = offer;
            dragSerial = enterSerial;
            dragOver = windows.get(surface);
            dragDropped = false;
            dragTaken = false;
            dragReads = 0;
            dragData = null;
            if (offer != NULL && dragOver != null) {
                List<String> mimes = List.copyOf(offers.getOrDefault(offer, List.of()));
                dragData = PlatformClipboard.offer(ClipboardTypes.canonical(mimes),
                        type -> receive(offer, ClipboardTypes.spellingIn(type, mimes), type, true), dragOver.ui);
                if (TRACE) {
                    System.err.println("limn.drag(wayland): enter, types " + mimes);
                }
            }
        }
        dragMoved(x, y);
    }

    /** The pointer over one of our windows, in a drag: who would take it there, asked of the UI thread. */
    private void dragMoved(int fixedX, int fixedY) {
        Window over;
        limn.transfer.Offer data;
        long offer;
        java.util.Set<limn.transfer.DropAction> allowed;
        synchronized (lock) {
            over = dragOver;
            data = dragData;
            offer = dragOffer;
            if (over == null || data == null || offer == NULL) {
                return;
            }
            dragX = (float) over.toLogical.applyAsDouble(fixedX / 256.0);
            dragY = (float) over.toLogical.applyAsDouble(fixedY / 256.0);
            allowed = allowedBy(offer);
        }
        float x = dragX;
        float y = dragY;
        over.ui.post(() -> {
            limn.backend.spi.WindowInput in = over.input.get();
            limn.transfer.DropAction action = in == null ? null : in.dragOver(data, allowed, x, y);
            if (TRACE && action != lastAnswered) {
                System.err.println("limn.drag(wayland): answers " + action + " at " + x + "," + y
                        + ", the source allowing " + allowed);
            }
            lastAnswered = action;
            synchronized (lock) {
                // To the drag it was computed for: a native address may be reused by the next one.
                if (offer != dragOffer || data != dragData || dragDropped || closing) {
                    return;
                }
                answer(offer, action);
            }
        });
    }

    /**
     * Tells the compositor what the window of ours under the drag would do with it: the action,
     * and the type it would be taken as, or none for a place that refuses. The compositor holds
     * the last of these for the drop. Under {@link #lock}.
     */
    private void answer(long offer, limn.transfer.@org.jspecify.annotations.Nullable DropAction action) {
        int wanted = action == limn.transfer.DropAction.MOVE ? ACTION_MOVE
                : action == null ? 0 : ACTION_COPY;
        if (wl.version(offer) >= 3) {
            wl.request(offer, 4, NULL, 0, Integer.toUnsignedLong(wanted), Integer.toUnsignedLong(wanted)); // set_actions
        }
        List<String> mimes = offers.getOrDefault(offer, List.of());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // accept: the type it would be taken as, or none for a place that refuses
            wl.request(offer, 0, NULL, 0, Integer.toUnsignedLong(dragSerial),
                    action == null || mimes.isEmpty() ? NULL : memAddress(stack.UTF8(mimes.get(0))));
        }
        dragTaken = action != null;
        dragAnswered.answered(dragData);
        wl.flush(display);
    }

    /**
     * What a window of ours answers the drag over it now, though no motion asked: the content
     * scrolled under a pointer that stands still. Motion is the only thing that asks, and the
     * compositor decides a drop by the last accept and action it had, so without this a drop area
     * that scrolled under the pointer took nothing until the pointer was wiggled. The protocol ties
     * neither request to a motion: set_actions may be sent many times, "typically" after an enter or
     * a motion, and accept is the offer's answer until the drop. Only to the drag the last answer
     * to a motion went to: one that entered since is not told the answer computed for the one
     * before it. UI thread.
     */
    private void answerUnasked(Window window, limn.transfer.@org.jspecify.annotations.Nullable DropAction action) {
        synchronized (lock) {
            long offer = dragOffer;
            if (dragOver != window || offer == NULL || dragData == null || dragDropped || closing
                    || !dragAnswered.isAnswered(dragData)) {
                return;
            }
            if (TRACE) {
                System.err.println("limn.drag(wayland): answers " + action + " unasked, the content having scrolled");
            }
            answer(offer, action);
        }
    }

    /** What the source allows, the action the compositor has chosen for the keys held first. */
    private java.util.Set<limn.transfer.DropAction> allowedBy(long offer) {
        int[] actions = offerActions.getOrDefault(offer, new int[] {ACTION_COPY, 0});
        java.util.Set<limn.transfer.DropAction> allowed = new java.util.LinkedHashSet<>();
        if (actions[1] == ACTION_MOVE) {
            allowed.add(limn.transfer.DropAction.MOVE);
        } else if (actions[1] == ACTION_COPY) {
            allowed.add(limn.transfer.DropAction.COPY);
        }
        if ((actions[0] & ACTION_COPY) != 0 || actions[0] == 0) {
            allowed.add(limn.transfer.DropAction.COPY);
        }
        if ((actions[0] & ACTION_MOVE) != 0) {
            allowed.add(limn.transfer.DropAction.MOVE);
        }
        return allowed;
    }

    private void dragLeft() {
        Window over;
        synchronized (lock) {
            over = dragOver;
            dragOver = null;
            if (dragOffer != NULL && !dragDropped) {
                destroyOffer(dragOffer);
                dragOffer = NULL;
                dragData = null;
            }
        }
        if (over != null) {
            over.ui.post(() -> {
                limn.backend.spi.WindowInput in = over.input.get();
                if (in != null) {
                    in.dragLeft();
                }
            });
        }
    }

    private void dragDrop() {
        Window over;
        limn.transfer.Offer data;
        long offer;
        java.util.Set<limn.transfer.DropAction> allowed;
        float x;
        float y;
        synchronized (lock) {
            over = dragOver;
            data = dragData;
            offer = dragOffer;
            if (TRACE) {
                System.err.println("limn.drag(wayland): drop, " + (over == null ? "over no window of ours"
                        : data == null || offer == NULL ? "with nothing offered" : "taken=" + dragTaken));
            }
            if (over == null || data == null || offer == NULL) {
                return;
            }
            dragDropped = true;
            dragReads++; // held until the widget that takes it has been asked
            allowed = allowedBy(offer);
            x = dragX;
            y = dragY;
        }
        over.ui.post(() -> {
            limn.backend.spi.WindowInput in = over.input.get();
            limn.transfer.DropAction done = in == null ? null : in.dropped(data, allowed, x, y);
            if (TRACE) {
                System.err.println("limn.drag(wayland): dropped at " + x + "," + y + ", done " + done);
            }
            synchronized (lock) {
                dragTaken = done != null;
            }
            dragRead(offer);
        });
    }

    /** One of the reads of what was dropped is over: with the last, the source is told it is finished. */
    private void dragRead(long offer) {
        synchronized (lock) {
            if (offer != dragOffer || --dragReads > 0 || !dragDropped || closing) {
                return;
            }
            if (dragTaken && wl.version(offer) >= 3) {
                wl.request(offer, 3, NULL, 0); // finish
            }
            destroyOffer(offer);
            dragOffer = NULL;
            dragData = null;
            dragOver = null;
            wl.flush(display);
        }
    }

    /** Asks a source for one type of what it offers, over a pipe read on a thread of its own. */
    private CompletableFuture<byte[]> receive(long offer, String mime, String type, boolean ofDrag) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        int readEnd;
        synchronized (lock) {
            if (offer == NULL || mime == null || closing || (ofDrag && offer != dragOffer)) {
                result.completeExceptionally(new TransferGoneException(type + " is not there to read"));
                return result;
            }
            int[] fds = wl.pipe();
            readEnd = fds[0];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                wl.request(offer, 1, NULL, 0, memAddress(stack.UTF8(mime)), fds[1]);
            }
            wl.flush(display);
            wl.close(fds[1]);
            if (ofDrag) {
                dragReads++;
            }
        }
        Thread reader = new Thread(() -> {
            try (FileInputStream in = new FileInputStream("/proc/self/fd/" + readEnd)) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] chunk = new byte[1 << 16];
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    if (all.size() + n > MAX_BYTES) {
                        throw new IOException("more than " + MAX_BYTES + " bytes");
                    }
                    all.write(chunk, 0, n);
                }
                if (TRACE && ofDrag) {
                    System.err.println("limn.drag(wayland): read " + all.size() + " bytes of " + mime);
                }
                byte[] read = all.toByteArray();
                List<java.nio.file.Path> elsewhere = filesReadElsewhere;
                if (ofDrag && read.length == 0 && ClipboardTypes.FILES.equals(type) && elsewhere != null
                        && System.nanoTime() - filesReadElsewhereAt < FILES_ELSEWHERE_NANOS) {
                    // The window library has a data device of its own on this connection and reads
                    // the files of a drop itself; once it has let go of its offer the source
                    // gives this one nothing. What it read is what was dropped.
                    read = UriLists.encode(elsewhere);
                }
                result.complete(read);
            } catch (IOException failed) {
                if (TRACE && ofDrag) {
                    System.err.println("limn.drag(wayland): " + mime + " could not be read: " + failed);
                }
                result.completeExceptionally(new TransferGoneException(type + " could not be read", failed));
            } finally {
                wl.close(readEnd);
                if (ofDrag) {
                    dragRead(offer);
                }
            }
        }, "limn-wayland-receive");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    // ---- a drag of ours ----------------------------------------------------------------------------

    /**
     * The files the window library read of a drop on one of this program's windows. It keeps a
     * data device of its own and takes a drop of files itself, after which the source gives the
     * offer made to this one nothing: these are then what a read of that drop's files answers.
     *
     * @param files the files it read
     */
    public void filesOfADrop(List<java.nio.file.Path> files) {
        filesReadElsewhereAt = System.nanoTime();
        filesReadElsewhere = List.copyOf(files);
    }

    /**
     * Makes a window a source and a destination of the system's drags. UI thread.
     *
     * @param surface   the window's {@code wl_surface}
     * @param input     the window's input, as it is at the time of each call; may answer null
     * @param toLogical from the surface's coordinates to the window's logical points
     * @param ui        the UI runtime
     * @return the window's drag
     */
    public WindowDrag register(long surface, java.util.function.Supplier<limn.backend.spi.WindowInput> input,
            java.util.function.DoubleUnaryOperator toLogical, limn.concurrent.UiRuntime ui) {
        Window window = new Window(surface, input, toLogical, ui);
        windows.put(surface, window);
        return new WindowDrag() {
            @Override
            public boolean start(limn.backend.spi.DragRequest request) {
                return startDrag(surface, request, ui);
            }

            @Override
            public void answerChanged(limn.transfer.@org.jspecify.annotations.Nullable DropAction action) {
                answerUnasked(window, action);
            }

            @Override
            public void close() {
                windows.remove(surface);
            }
        };
    }

    private boolean startDrag(long origin, limn.backend.spi.DragRequest request, limn.concurrent.UiRuntime ui) {
        List<String> types = PlatformClipboard.types(request.content());
        Function<String, byte[]> bytes = PlatformClipboard.provider(request.content(), ui);
        if (request.content().needsUiThread(limn.transfer.DataFormat.FILES)) {
            // Made now, on this thread, and not when asked for. GLFW answers a drop of files on
            // one of its windows by reading them at once, on the UI thread, which then waits for
            // this very program to send them: a value only the UI thread can make would never be
            // made, and the window stood still until the read gave up, twenty seconds later (the
            // owner's Fedora guest, a picture with its file dropped on the window it came from).
            bytes.apply(ClipboardTypes.FILES);
        }
        synchronized (lock) {
            if (closing || dragSource != NULL) {
                return false;
            }
            long source = wl.request(manager, 0, wl.function("wl_data_source_interface"), 0, 0L);
            wl.listen(source, sourceListener);
            sources.put(source, bytes);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (String type : types) {
                    for (String spelling : ClipboardTypes.spellings(type)) {
                        wl.request(source, 0, NULL, 0, memAddress(stack.UTF8(spelling)));
                    }
                }
                if (types.isEmpty()) {
                    // Something only this program reads: a drag with no type is one nothing accepts.
                    wl.request(source, 0, NULL, 0, memAddress(stack.UTF8("application/x-limn-in-process")));
                }
            }
            int actions = 0;
            for (limn.transfer.DropAction action : request.actions()) {
                actions |= action == limn.transfer.DropAction.MOVE ? ACTION_MOVE : ACTION_COPY;
            }
            if (wl.version(source) >= 3) {
                wl.request(source, 2, NULL, 0, Integer.toUnsignedLong(actions)); // set_actions
            }
            long icon = icon(request);
            dragSource = source;
            dragRequest = request;
            dragAction = 0;
            dragPerformed = false;
            // start_drag: from the surface the button was pressed on, with that press's serial
            wl.request(device, 0, NULL, 0, source, origin, icon, Integer.toUnsignedLong(pressSerial));
            if (icon != NULL) {
                // Drawn once it is the drag's icon, and not before: the compositor puts an icon's
                // corner at the pointer and moves it only by what an attach says from then on,
                // so the offset that holds it where the pointer pressed it has to come with this
                // attach. Attached before, the offset was dropped, and the picture hung from its
                // corner where the other systems hold it by the point pressed (the owner saw it).
                wl.request(icon, 1, NULL, 0, iconBuffer,
                        Integer.toUnsignedLong(-Math.round(Math.max(0, dragHoldX(request)))),
                        Integer.toUnsignedLong(-Math.round(Math.max(0, dragHoldY(request)))));  // attach
                wl.request(icon, 2, NULL, 0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);      // damage
                wl.request(icon, 6, NULL, 0);                                                  // commit
            }
            wl.flush(display);
        }
        return true;
    }

    /**
     * The picture a drag shows: a surface of ours, drawn once from memory shared with the
     * compositor, which moves it with the pointer. NULL where it cannot be made, and the pointer
     * alone then says a drag is on.
     */
    private long icon(limn.backend.spi.DragRequest request) {
        limn.graphics.Image image = request.image();
        if (image == null || compositor == NULL || shm == NULL || request.width() <= 0) {
            return NULL;
        }
        int w = image.width();
        int h = image.height();
        int size = w * h * 4;
        int fd = -1;
        try {
            org.lwjgl.system.SharedLibrary c = org.lwjgl.system.APIUtil.apiCreateLibrary("libc.so.6");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                fd = (int) Ffi.call(org.lwjgl.system.APIUtil.apiGetFunctionAddress(c, "memfd_create"), "pi>i",
                        memAddress(stack.UTF8("limn-drag")), 0);
            }
            if (fd < 0) {
                return NULL;
            }
            byte[] rgba = image.pixels();
            byte[] bgra = new byte[size]; // blue first, each colour already times its alpha
            for (int i = 0; i < size; i += 4) {
                int alpha = rgba[i + 3] & 0xFF;
                bgra[i] = (byte) ((rgba[i + 2] & 0xFF) * alpha / 255);
                bgra[i + 1] = (byte) ((rgba[i + 1] & 0xFF) * alpha / 255);
                bgra[i + 2] = (byte) ((rgba[i] & 0xFF) * alpha / 255);
                bgra[i + 3] = (byte) alpha;
            }
            try (FileOutputStream out = new FileOutputStream("/proc/self/fd/" + fd)) {
                out.write(bgra);
            }
            long pool = wl.request(shm, 0, wl.function("wl_shm_pool_interface"), 0, 0L, fd, size);
            iconBuffer = wl.request(pool, 0, wl.function("wl_buffer_interface"), 0, 0L, 0, w, h, w * 4, 0);
            wl.request(pool, 1, NULL, Wl.DESTROY);
            iconSurface = wl.request(compositor, 0, wl.function("wl_surface_interface"), 0, 0L);
            int scale = Math.max(1, Math.round(w / request.width()));
            if (wl.version(iconSurface) >= 3) {
                wl.request(iconSurface, 8, NULL, 0, scale); // set_buffer_scale
            }
            return iconSurface;
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "no picture for the drag", unavailable);
            return NULL;
        } finally {
            if (fd >= 0) {
                wl.close(fd);
            }
        }
    }

    /** Where in its picture the pointer holds a drag, in the surface's coordinates. */
    private volatile float pointerX;
    private volatile float pointerY;

    private float dragHoldX(limn.backend.spi.DragRequest request) {
        return pointerX - request.x();
    }

    private float dragHoldY(limn.backend.spi.DragRequest request) {
        return pointerY - request.y();
    }

    private void dragFinished(long source) {
        if (source == dragSource) {
            endDrag(dragAction == ACTION_MOVE ? limn.transfer.DropAction.MOVE
                    : dragAction == ACTION_COPY ? limn.transfer.DropAction.COPY : null);
        }
    }

    /** A drag of ours is over: its source and its picture are let go, and who began it is told. */
    private void endDrag(limn.transfer.DropAction action) {
        limn.backend.spi.DragRequest request;
        synchronized (lock) {
            request = dragRequest;
            if (dragSource != NULL) {
                sources.remove(dragSource);
                wl.request(dragSource, 1, NULL, Wl.DESTROY);
            }
            dragSource = NULL;
            dragRequest = null;
            if (iconSurface != NULL) {
                wl.request(iconSurface, 0, NULL, Wl.DESTROY);
                iconSurface = NULL;
            }
            if (iconBuffer != NULL) {
                wl.request(iconBuffer, 0, NULL, Wl.DESTROY);
                iconBuffer = NULL;
            }
            wl.flush(display);
        }
        if (TRACE) {
            System.err.println("limn.drag(wayland): ended with " + action);
        }
        if (request != null) {
            Window any = windows.values().stream().findFirst().orElse(null);
            if (any != null) {
                any.ui.post(() -> request.ended().accept(action));
            }
        }
    }

    private void destroyOffer(long offer) {
        offers.remove(offer);
        offerActions.remove(offer);
        wl.request(offer, 2, NULL, Wl.DESTROY);
    }

    private void send(long source, String mime, int fd) {
        Function<String, byte[]> bytes;
        synchronized (lock) {
            bytes = sources.get(source);
        }
        Thread writer = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream("/proc/self/fd/" + fd)) {
                byte[] data = bytes == null ? null : bytes.apply(ClipboardTypes.canonical(mime));
                if (data != null) {
                    out.write(data);
                }
            } catch (IOException gone) {
                // The program that asked stopped reading: its business.
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.WARNING, "the clipboard's " + mime + " could not be sent", failed);
            } finally {
                wl.close(fd);
            }
        }, "limn-wayland-send");
        writer.setDaemon(true);
        writer.start();
    }

    private void cancelled(long source) {
        if (source == dragSource) {
            endDrag(null); // dropped where nothing took it, or called off
            return;
        }
        boolean wasOwner;
        synchronized (lock) {
            sources.remove(source);
            wl.request(source, 1, NULL, Wl.DESTROY);
            wasOwner = source == ownSource;
            if (wasOwner) {
                ownSource = NULL;
                owner = false;
            }
        }
        if (wasOwner) {
            changeListener.run();
        }
    }

    // ---- NativeClipboard --------------------------------------------------------------------------

    @Override
    public boolean isOwner() {
        return owner;
    }

    @Override
    public List<String> types() {
        return ClipboardTypes.canonical(offered);
    }

    @Override
    public CompletableFuture<byte[]> read(String type) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        int readEnd;
        synchronized (lock) {
            String mime = ClipboardTypes.spellingIn(type, offered);
            if (selection == NULL || mime == null || closing) {
                result.completeExceptionally(new TransferGoneException(type + " is not on the clipboard"));
                return result;
            }
            int[] fds = wl.pipe();
            readEnd = fds[0];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                wl.request(selection, 1, NULL, 0, memAddress(stack.UTF8(mime)), fds[1]);
            }
            wl.flush(display);
            wl.close(fds[1]);
        }
        Thread reader = new Thread(() -> {
            try (FileInputStream in = new FileInputStream("/proc/self/fd/" + readEnd)) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] chunk = new byte[1 << 16];
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    if (all.size() + n > MAX_BYTES) {
                        throw new IOException("more than " + MAX_BYTES + " bytes");
                    }
                    all.write(chunk, 0, n);
                }
                result.complete(all.toByteArray());
            } catch (IOException failed) {
                result.completeExceptionally(new TransferGoneException(type + " could not be read", failed));
            } finally {
                wl.close(readEnd);
            }
        }, "limn-wayland-receive");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    @Override
    public void offer(List<String> types, Function<String, byte[]> bytes) {
        synchronized (lock) {
            if (closing) {
                return;
            }
            long source = NULL;
            if (!types.isEmpty()) {
                source = wl.request(manager, 0, wl.function("wl_data_source_interface"), 0, 0L);
                wl.listen(source, sourceListener);
                sources.put(source, bytes);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    for (String type : types) {
                        for (String spelling : ClipboardTypes.spellings(type)) {
                            wl.request(source, 0, NULL, 0, memAddress(stack.UTF8(spelling)));
                        }
                    }
                }
            }
            // The source before this one is told it was cancelled, and is let go there.
            wl.request(device, 1, NULL, 0, source, Integer.toUnsignedLong(serial));
            wl.flush(display);
            ownSource = source;
            owner = source != NULL;
        }
        // No word to the listener from here: the compositor answers a selection it accepted
        // with a selection event, and that is the change.
    }

    @Override
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            // Something for the dispatch thread to wake on: the answer to a sync on our queue.
            wl.request(wrapper, 0, wl.function("wl_callback_interface"), 0, 0L);
            wl.flush(display);
        }
        try {
            dispatcher.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!dispatcher.isAlive()) {
            wl.free();
        }
    }
}
