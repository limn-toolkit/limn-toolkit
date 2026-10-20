package limn.backend.lwjgl.transfer;

import limn.backend.spi.DragRequest;
import limn.backend.spi.WindowInput;
import limn.concurrent.UiRuntime;
import limn.transfer.DropAction;
import limn.transfer.Offer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.nmemAlloc;
import static org.lwjgl.system.MemoryUtil.nmemFree;

/**
 * The system's drag and drop on X11, which is the XDND protocol (version 5): client messages
 * between a source and the window under the pointer, and the {@code XdndSelection} to hand the
 * data over. It is spoken on the clipboard's connection, by the clipboard's thread.
 *
 * <p><b>As a destination.</b> GLFW's window already answers XDND, for files and at the drop
 * alone, on a connection this class cannot read. Each window is therefore given an
 * {@code XdndProxy}: sources send what was meant for it to the clipboard's own hidden window,
 * where it is answered here for every kind of data and while the pointer hovers. The answer is
 * asked of the UI thread and sent when it comes; nothing waits on anything. A source that goes
 * round the proxy after its first position, to talk to GLFW's window itself, is followed by
 * reading the pointer ({@code X11DragFollow}); while a drag is over a window of ours the thread
 * turns sixty times a second for it.
 *
 * <p><b>As a source.</b> X has no drag of its own to hand a session to: the source follows the
 * pointer itself. While a drag of ours lasts the thread asks the server where the pointer is
 * sixty times a second, finds the window under it that speaks XDND, and tells it; the button
 * let go is the drop, and Escape calls it off. Our own windows are told the same way as any
 * other program's, through the server.
 */
public final class X11Drag {

    private static final System.Logger LOG = System.getLogger(X11Drag.class.getName());
    private static final boolean TRACE = Boolean.getBoolean("limn.drag.trace");
    private static final int VERSION = 5;
    private static final long XA_ATOM = 4;
    private static final long XA_WINDOW = 33;
    private static final int EVENT_BYTES = 192;
    private static final int CLIENT_MESSAGE = 33;
    private static final long BUTTON1 = 1L << 8;
    private static final long SHIFT = 1L;
    private static final long CONTROL = 1L << 2;
    private static final long FINISH_TIMEOUT_NANOS = 5_000_000_000L;

    private final X11Clipboard x;
    private final UiRuntime ui;
    private final Map<Long, Entry> windows = new ConcurrentHashMap<>();

    // The X thread's alone from here on.
    private boolean prepared;
    private long root;
    private long selection;
    private long aware;
    private long proxy;
    private long typeList;
    private long enter;
    private long position;
    private long status;
    private long leave;
    private long drop;
    private long finished;
    private long actionCopy;
    private long actionMove;
    private long actionLink;
    private long escapeKey;

    // A drag from another window, over one of ours.
    private long incomingSource;
    private @Nullable Entry incomingTarget;
    private List<String> incomingSpellings = List.of();
    private @Nullable Offer incomingOffer;
    private long incomingTime;
    /** The source said the drop: the answer is final, and nothing more is told it before it is finished. */
    private boolean incomingDropped;
    /** The drop was handed to the UI thread: the source is told it is finished once its data is read. */
    private boolean finishing;
    private boolean finishAccepted;
    private @Nullable DropAction finishAction;
    private long finishDeadline;
    /** The drag over a window of ours, followed by the pointer once its source went round the proxy. */
    private final X11DragFollow follow = new X11DragFollow();
    /** What this side last answered the source of the drag over a window of ours. */
    private @Nullable DropAction answered;
    /** What a followed drag offers: its files alone, which is what GLFW's drop delivers; or none. */
    private @Nullable Offer followOffer;

    // A drag of ours.
    private @Nullable DragRequest outgoing;
    private List<String> outgoingTypes = List.of();
    private Function<String, byte[]> outgoingBytes = type -> null;
    private long target;
    private long targetProxy;
    private int targetVersion;
    private boolean waitingStatus;
    private boolean positionPending;
    private boolean accepted;
    private long acceptedAction;
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private long lastAction;
    private boolean dropped;
    private long dropDeadline;
    /** The window that shows what is dragged under the pointer, with what it is drawn from; or none. */
    private long picture;
    private long picturePixmap;
    private long pictureColormap;
    private int pictureHoldX;
    private int pictureHoldY;
    /** The window a drag of ours began in, whose pointer says the target's answer while it lasts. */
    private @Nullable Entry outgoingFrom;
    /** The pointer shape that window wears now: one of {@link #shapes}' values, or none of ours. */
    private long shapeShown;
    /** The theme's pointer shapes by what they say; absent where the theme, or its library, has none. */
    private final java.util.Map<String, Long> shapes = new java.util.HashMap<>();
    private org.lwjgl.system.@Nullable SharedLibrary xcursor;
    private static org.lwjgl.system.@Nullable SharedLibrary xfixesLibrary;
    private boolean xcursorTried;

    /** One of this program's windows, as a destination. */
    private static final class Entry {
        final long xwindow;
        final Supplier<WindowInput> input;
        final DoubleUnaryOperator toLogical;
        final Runnable ownPointer;

        Entry(long xwindow, Supplier<WindowInput> input, DoubleUnaryOperator toLogical, Runnable ownPointer) {
            this.xwindow = xwindow;
            this.input = input;
            this.toLogical = toLogical;
            this.ownPointer = ownPointer;
        }
    }

    X11Drag(X11Clipboard x, UiRuntime ui) {
        this.x = x;
        this.ui = ui;
    }

    /**
     * Makes a window a source and a destination of the system's drags. UI thread.
     *
     * @param xwindow   the window's X id
     * @param input     the window's input, as it is at the time of each call; may answer null
     * @param toLogical from the server's pixels to the window's logical points
     * @param ownPointer puts the window's own pointer shape back, on the UI thread, after a drag
     *                   that began in it changed the shape to say what a target answered
     * @return the window's drag
     */
    public WindowDrag register(long xwindow, Supplier<WindowInput> input, DoubleUnaryOperator toLogical,
            Runnable ownPointer) {
        Entry entry = new Entry(xwindow, input, toLogical, ownPointer);
        windows.put(xwindow, entry);
        x.post(() -> {
            prepare();
            put(xwindow, proxy, XA_WINDOW, x.window());
        });
        return new WindowDrag() {
            @Override
            public boolean start(DragRequest request) {
                List<String> types = PlatformClipboard.types(request.content());
                Function<String, byte[]> bytes = PlatformClipboard.provider(request.content(), ui);
                x.post(() -> {
                    begin(request, types, bytes);
                    if (outgoing == request) {
                        outgoingFrom = entry;
                        showPicture(request, entry);
                    }
                });
                return true;
            }

            @Override
            public void answerChanged(@Nullable DropAction action) {
                x.post(() -> answerUnasked(entry, action));
            }

            @Override
            public void close() {
                windows.remove(xwindow);
            }
        };
    }

    private void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        root = x.x("XDefaultRootWindow", "p>p", x.display());
        selection = x.atom("XdndSelection");
        aware = x.atom("XdndAware");
        proxy = x.atom("XdndProxy");
        typeList = x.atom("XdndTypeList");
        enter = x.atom("XdndEnter");
        position = x.atom("XdndPosition");
        status = x.atom("XdndStatus");
        leave = x.atom("XdndLeave");
        drop = x.atom("XdndDrop");
        finished = x.atom("XdndFinished");
        actionCopy = x.atom("XdndActionCopy");
        actionMove = x.atom("XdndActionMove");
        actionLink = x.atom("XdndActionLink");
        escapeKey = x.x("XKeysymToKeycode", "pp>i", x.display(), 0xff1b) & 0xff;
        // The hidden window is where the messages for every window of ours arrive.
        put(x.window(), aware, XA_ATOM, VERSION);
        put(x.window(), proxy, XA_WINDOW, x.window());
    }

    private void put(long window, long property, long type, long value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long data = stack.ncalloc(8, 1, 8);
            memPutAddress(data, value);
            x.x("XChangeProperty", "ppppiipi>i", x.display(), window, property, type, 32, 0, data, 1);
        }
    }

    /** A property of another window, of 32-bit items, left where it is; empty when there is none. */
    private long[] longs(long window, long property) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 5, 8);
            int failed = (int) x.x("XGetWindowProperty", "pppppipppppp>i", x.display(), window, property, 0,
                    1024, 0, 0, out, out + 8, out + 16, out + 24, out + 32);
            long data = memGetAddress(out + 32);
            long[] values = new long[0];
            if (failed == 0 && data != NULL && memGetInt(out + 8) == 32) {
                values = new long[(int) memGetAddress(out + 16)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = memGetAddress(data + 8L * i);
                }
            }
            if (data != NULL) {
                x.x("XFree", "p>i", data);
            }
            return values;
        }
    }

    /**
     * Sends one of the protocol's messages.
     *
     * @param to    the window it is sent to: the other side's, or its proxy
     * @param about the window the event names: the other side's own
     * @param l0    this side's window: ours as a source, the target's as a destination
     */
    private void send(long to, long about, long message, long l0, long l1, long l2, long l3, long l4) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long event = stack.ncalloc(8, 1, EVENT_BYTES);
            memPutInt(event, CLIENT_MESSAGE);
            memPutAddress(event + 24, x.display());
            memPutAddress(event + 32, about);
            memPutAddress(event + 40, message);
            memPutInt(event + 48, 32);
            memPutAddress(event + 56, l0);
            memPutAddress(event + 64, l1);
            memPutAddress(event + 72, l2);
            memPutAddress(event + 80, l3);
            memPutAddress(event + 88, l4);
            x.x("XSendEvent", "ppipp>i", x.display(), to, 0, 0, event);
        }
    }

    private long atomOf(@Nullable DropAction action) {
        return action == null ? NULL : switch (action) {
            case COPY -> actionCopy;
            case MOVE -> actionMove;
            case LINK -> actionLink;
        };
    }

    private @Nullable DropAction actionOf(long atom) {
        return atom == actionMove ? DropAction.MOVE : atom == actionLink ? DropAction.LINK
                : atom == NULL ? null : DropAction.COPY;
    }

    // ---- the destination ------------------------------------------------------------------------------

    /** A client message on the hidden window. X thread. */
    void message(long event) {
        long type = memGetAddress(event + 40);
        long l0 = memGetAddress(event + 56);
        long l1 = memGetAddress(event + 64);
        long l2 = memGetAddress(event + 72);
        long l3 = memGetAddress(event + 80);
        long l4 = memGetAddress(event + 88);
        if (type == enter) {
            follow.entered(System.nanoTime());
            x.pollMillis = 16;
            incomingSource = l0;
            incomingTarget = windows.get(memGetAddress(event + 32));
            List<String> names = new ArrayList<>();
            if ((l1 & 1) != 0) {
                for (long atom : longs(l0, typeList)) {
                    names.add(x.name(atom));
                }
            } else {
                for (long atom : new long[] {l2, l3, l4}) {
                    if (atom != NULL) {
                        names.add(x.name(atom));
                    }
                }
            }
            incomingSpellings = List.copyOf(names);
            incomingDropped = false;
            List<String> spellings = incomingSpellings;
            incomingOffer = PlatformClipboard.offer(ClipboardTypes.canonical(names),
                    kind -> x.read(selection, kind, () -> spellings, incomingTime), ui);
            tracedPositions = 0;
            if (TRACE) {
                System.err.println("limn.drag(x11): enter from " + l0 + " types " + names);
            }
        } else if (type == position && l0 == incomingSource) {
            incomingTime = l3;
            lastIncomingAction = l4;
            int rootX = (int) (l2 >> 16) & 0xffff;
            int rootY = (int) l2 & 0xffff;
            if (TRACE && follow.active()) {
                System.err.println("limn.drag(x11): the source names a point again; the follow ends");
            }
            follow.heard(System.nanoTime());
            Entry over = entryAt(memGetAddress(event + 32), rootX, rootY);
            Offer offer = incomingOffer;
            if (TRACE && tracedPositions++ < 6) {
                System.err.println("limn.drag(x11): position " + rootX + "," + rootY + " for window "
                        + memGetAddress(event + 32) + ", " + (over == null ? "none of ours" : "ours")
                        + (offer == null ? ", no offer" : ", formats " + offer.formats()));
            }
            if (over == null || offer == null) {
                send(l0, l0, status, memGetAddress(event + 32), 0, 0, 0, NULL);
                return;
            }
            if (incomingTarget != null && incomingTarget != over) {
                Entry before = incomingTarget;
                ui.post(() -> left(before));
            }
            incomingTarget = over;
            float[] at = local(over, rootX, rootY);
            Set<DropAction> allowed = allowed(l4);
            long source = l0;
            ui.post(() -> {
                WindowInput in = over.input.get();
                DropAction action = in == null ? null : in.dragOver(offer, allowed, at[0], at[1]);
                if (TRACE && action != lastAnswered) {
                    System.err.println("limn.drag(x11): answers " + action + " at " + at[0] + "," + at[1]
                            + ", the source allowing " + allowed);
                }
                lastAnswered = action;
                x.post(() -> {
                    if (source == incomingSource) {
                        answer(source, over, action);
                    }
                });
            });
        } else if (type == leave && l0 == incomingSource) {
            Entry over = incomingTarget;
            X11DragFollow.Sample at = over == null ? null : sample(over);
            boolean kept = at != null && incomingOffer != null && follow.keepsAfterLeave(at,
                    ClipboardTypes.canonical(incomingSpellings), incomingDropped);
            if (TRACE) {
                System.err.println("limn.drag(x11): leave from " + l0 + (at == null ? ""
                        : ", the pointer at " + at.rootX() + "," + at.rootY() + (at.over() ? " inside" : " outside")
                        + (at.buttonHeld() ? ", a button held" : ", the buttons up")
                        + (at.escape() ? ", Escape held" : "")) + (kept ? ": kept" : ": taken"));
            }
            if (kept) {
                followFrom(over, "the source left for the window itself");
                return;
            }
            forgetIncoming();
            if (over != null) {
                ui.post(() -> left(over));
            }
        } else if (type == drop && l0 == incomingSource) {
            incomingTime = l2;
            incomingDropped = true;
            Entry over = incomingTarget;
            Offer offer = incomingOffer;
            if (TRACE) {
                System.err.println("limn.drag(x11): drop message, over " + (over == null ? "none of ours" : "a window of ours"));
            }
            if (over == null || offer == null) {
                send(l0, l0, finished, memGetAddress(event + 32), 0, NULL, 0, 0);
                forgetIncoming();
                return;
            }
            float[] at = pointerIn(over);
            Set<DropAction> allowed = allowed(lastIncomingAction);
            ui.post(() -> {
                WindowInput in = over.input.get();
                DropAction done = in == null ? null : in.dropped(offer, allowed, at[0], at[1]);
                if (TRACE) {
                    System.err.println("limn.drag(x11): dropped at " + at[0] + "," + at[1] + ", done " + done);
                }
                x.post(() -> {
                    // Told once what was dropped has been read, or the source lets go of it first.
                    finishing = true;
                    finishAccepted = done != null;
                    finishAction = done;
                    finishDeadline = System.nanoTime() + FINISH_TIMEOUT_NANOS;
                });
            });
        } else if (type == status && outgoing != null && l0 == target) {
            waitingStatus = false;
            accepted = (l1 & 1) != 0;
            acceptedAction = accepted ? l4 : NULL;
            if (positionPending) {
                positionPending = false;
                tellPosition();
            }
        } else if (type == finished && outgoing != null && dropped) {
            end(targetVersion >= 5 && (l1 & 1) == 0 ? null : actionOf(targetVersion >= 5 ? l2 : acceptedAction));
        }
    }

    /**
     * XdndStatus: whether the window of ours under the pointer takes the drag, and as what. Bit 1
     * with an empty rectangle asks for a position at every move.
     */
    private void answer(long source, Entry over, @Nullable DropAction action) {
        answered = action;
        follow.answered(System.nanoTime());
        send(source, source, status, over.xwindow, action == null ? 0 : 3, 0, 0, atomOf(action));
    }

    /**
     * Tells the source of the drag over a window of ours what that window answers now, though the
     * source asked nothing: the content scrolled under a pointer that stands still, and a source
     * sends XdndPosition only when the pointer moves. Released there, a source decides between
     * XdndDrop and XdndLeave by the last XdndStatus it had, so without this a drop area that
     * scrolled under the pointer took nothing until the pointer was wiggled. The protocol neither
     * describes nor forbids a status that answers no position; a source that reads XdndStatus
     * whenever it comes, as ours does, takes it as the target's answer for where the pointer is.
     * X thread.
     */
    private void answerUnasked(Entry over, @Nullable DropAction action) {
        if (incomingSource == NULL || incomingTarget != over || incomingDropped) {
            return;
        }
        if (TRACE) {
            System.err.println("limn.drag(x11): answers " + action + " unasked, the content having scrolled");
        }
        answer(incomingSource, over, action);
    }

    private long lastIncomingAction;
    /** What the UI thread last answered a drag over a window of ours, for the trace alone. */
    private @Nullable DropAction lastAnswered;
    private int tracedPositions;

    private static void left(Entry over) {
        WindowInput in = over.input.get();
        if (in != null) {
            in.dragLeft();
        }
    }

    private void forgetIncoming() {
        follow.stop();
        followOffer = null;
        x.pollMillis = outgoing != null ? 16 : 250;
        incomingDropped = false;
        incomingSource = NULL;
        incomingTarget = null;
        incomingOffer = null;
    }

    private Set<DropAction> allowed(long proposed) {
        Set<DropAction> actions = new LinkedHashSet<>();
        DropAction asked = actionOf(proposed);
        actions.add(asked == null ? DropAction.COPY : asked);
        return actions;
    }

    /** The window of ours a message is for: the one it names, or the one the point is in. */
    private @Nullable Entry entryAt(long named, int rootX, int rootY) {
        Entry entry = windows.get(named);
        if (entry != null) {
            return entry;
        }
        for (Entry each : windows.values()) {
            float[] at = pixelsIn(each, rootX, rootY);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = stack.ncalloc(8, 8, 8);
                // root, x, y, width, height, border, depth
                if (x.x("XGetGeometry", "ppppppppp>i", x.display(), each.xwindow, out, out + 8, out + 12, out + 16,
                        out + 20, out + 24, out + 28) != 0
                        && at[0] >= 0 && at[1] >= 0 && at[0] < memGetInt(out + 16) && at[1] < memGetInt(out + 20)) {
                    return each;
                }
            }
        }
        return null;
    }

    private float[] pixelsIn(Entry entry, int rootX, int rootY) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 3, 8);
            x.x("XTranslateCoordinates", "pppiippp>i", x.display(), root, entry.xwindow, rootX, rootY, out, out + 4,
                    out + 8);
            return new float[] {memGetInt(out), memGetInt(out + 4)};
        }
    }

    private float[] local(Entry entry, int rootX, int rootY) {
        float[] at = pixelsIn(entry, rootX, rootY);
        return new float[] {(float) entry.toLogical.applyAsDouble(at[0]), (float) entry.toLogical.applyAsDouble(at[1])};
    }

    private float[] pointerIn(Entry entry) {
        int[] pointer = pointer();
        return local(entry, pointer[0], pointer[1]);
    }

    /** Where the pointer is on the root window, and the buttons and modifier keys held. */
    private int[] pointer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 7, 8);
            x.x("XQueryPointer", "ppppppppp>i", x.display(), root, out, out + 8, out + 16, out + 20, out + 24,
                    out + 28, out + 32);
            return new int[] {memGetInt(out + 16), memGetInt(out + 20), memGetInt(out + 32)};
        }
    }

    /**
     * The source of the drag over a window of ours turned to the window itself, and the drag goes
     * on, followed by the pointer: what it offers is narrowed to what GLFW's drop delivers, and the
     * scene is told where the pointer is now. X thread.
     */
    private void followFrom(Entry over, String why) {
        List<String> spellings = incomingSpellings;
        answered = null; // until the scene answers the narrowed offer: never a move GLFW would not make
        followOffer = PlatformClipboard.offer(X11DragFollow.deliverable(ClipboardTypes.canonical(spellings)),
                kind -> x.read(selection, kind, () -> spellings, incomingTime), ui);
        x.pollMillis = 16;
        if (TRACE) {
            System.err.println("limn.drag(x11): " + why + "; the pointer is followed from " + follow.x() + ","
                    + follow.y());
        }
        followedOver(over);
    }

    /** Asks the scene about the point the drag followed is at, and answers the source. X thread. */
    private void followedOver(Entry over) {
        Offer offer = followOffer;
        if (offer == null) {
            return;
        }
        long source = incomingSource;
        float[] at = local(over, follow.x(), follow.y());
        ui.post(() -> {
            WindowInput in = over.input.get();
            DropAction action = in == null ? null : in.dragOver(offer, X11DragFollow.ALLOWED, at[0], at[1]);
            if (TRACE && action != lastAnswered) {
                System.err.println("limn.drag(x11): answers " + action + " at " + at[0] + "," + at[1]
                        + ", the pointer followed");
            }
            lastAnswered = action;
            x.post(() -> {
                if (source == incomingSource && follow.active()) {
                    answer(source, over, action);
                }
            });
        });
    }

    /**
     * One turn for the drag over a window of ours: whether its source fell silent, and while it is
     * followed, where the pointer is. X thread.
     */
    private void followTurn() {
        Entry over = incomingTarget;
        if (over == null || incomingSource == NULL || incomingDropped || finishing || incomingOffer == null) {
            return;
        }
        X11DragFollow.Sample at = sample(over);
        long now = System.nanoTime();
        if (!follow.active()) {
            boolean ours = incomingSource == x.window() || outgoing != null;
            if (follow.keepsAfterSilence(now, at, ClipboardTypes.canonical(incomingSpellings), false, ours)) {
                followFrom(over, "the source fell silent while the pointer moved over the window");
            } else if (follow.stale(now, at)) {
                if (TRACE) {
                    System.err.println("limn.drag(x11): the buttons are up and nothing was said; the drag is forgotten");
                }
                forgetIncoming();
                ui.post(() -> left(over));
            }
            return;
        }
        switch (follow.tick(at)) {
            case OVER -> followedOver(over);
            case SEAL -> answer(incomingSource, over, answered);
            case CALLED_OFF, LEFT -> {
                boolean escape = at.escape();
                if (TRACE) {
                    System.err.println("limn.drag(x11): the followed drag "
                            + (escape ? "was called off by Escape" : at.buttonHeld() ? "left the window" : "was let go"));
                }
                if (escape) {
                    answer(incomingSource, over, null); // a source that did not hear the key drops nothing here
                }
                forgetIncoming();
                ui.post(() -> left(over));
            }
            case NOTHING -> {
            }
        }
    }

    /**
     * The pointer as the follow reads it: where, what is held, and whether it is inside the window.
     * Inside is the window's rectangle, whatever is on top of it there: a source's drag picture may
     * be under the pointer and take drags itself, and a window found there is no sign of a leave.
     */
    private X11DragFollow.Sample sample(Entry over) {
        int[] pointer = pointer();
        float[] in = pixelsIn(over, pointer[0], pointer[1]);
        boolean inside = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 8, 8);
            // root, x, y, width, height, border, depth
            if (x.x("XGetGeometry", "ppppppppp>i", x.display(), over.xwindow, out, out + 8, out + 12, out + 16,
                    out + 20, out + 24, out + 28) != 0) {
                inside = in[0] >= 0 && in[1] >= 0 && in[0] < memGetInt(out + 16) && in[1] < memGetInt(out + 20);
            }
        }
        return new X11DragFollow.Sample(pointer[0], pointer[1], pointer[2], escapeHeld(), inside);
    }

    // ---- the source -----------------------------------------------------------------------------------

    /** Whether a selection is the one a drag of ours hands its data over by. X thread. */
    boolean owns(long asked) {
        return prepared && asked == selection && outgoing != null;
    }

    List<String> types() {
        return outgoingTypes;
    }

    Function<String, byte[]> bytes() {
        return outgoingBytes;
    }

    private void begin(DragRequest request, List<String> types, Function<String, byte[]> bytes) {
        prepare();
        if (outgoing != null) {
            ui.post(() -> request.ended().accept(null));
            return;
        }
        outgoing = request;
        outgoingTypes = types;
        outgoingBytes = bytes;
        target = NULL;
        waitingStatus = false;
        positionPending = false;
        accepted = false;
        dropped = false;
        lastX = Integer.MIN_VALUE;
        x.x("XSetSelectionOwner", "pppp>i", x.display(), selection, x.window(), 0);
        List<Long> atoms = spellings();
        long data = nmemAlloc(8L * Math.max(1, atoms.size()));
        for (int i = 0; i < atoms.size(); i++) {
            memPutAddress(data + 8L * i, atoms.get(i));
        }
        x.x("XChangeProperty", "ppppiipi>i", x.display(), x.window(), typeList, XA_ATOM, 32, 0, data, atoms.size());
        nmemFree(data);
        x.pollMillis = 16;
    }

    private List<Long> spellings() {
        List<Long> atoms = new ArrayList<>();
        for (String type : outgoingTypes) {
            for (String spelling : ClipboardTypes.spellings(type)) {
                atoms.add(x.atom(spelling));
            }
        }
        return atoms;
    }

    /** Each turn of the X thread: where the pointer is in a drag of ours, and what is owed a source. */
    void tick() {
        if (finishing && (!x.isReading() || System.nanoTime() - finishDeadline > 0)) {
            finishing = false;
            if (incomingSource != NULL) {
                Entry over = incomingTarget;
                send(incomingSource, incomingSource, finished, over == null ? NULL : over.xwindow,
                        finishAccepted ? 1 : 0, atomOf(finishAction), 0, 0);
            }
            forgetIncoming();
        }
        followTurn();
        DragRequest request = outgoing;
        if (request == null) {
            return;
        }
        if (dropped) {
            if (System.nanoTime() - dropDeadline > 0) {
                end(actionOf(acceptedAction)); // a target that never says it finished took it all the same
            }
            return;
        }
        int[] pointer = pointer();
        if (picture != NULL) {
            x.x("XMoveWindow", "ppii>i", x.display(), picture, pointer[0] - pictureHoldX, pointer[1] - pictureHoldY);
        }
        if (escapeHeld()) {
            if (target != NULL) {
                send(targetProxy, target, leave, x.window(), 0, 0, 0, 0);
            }
            end(null);
            return;
        }
        long asked = (pointer[2] & CONTROL) != 0 && (pointer[2] & SHIFT) != 0 ? actionLink
                : (pointer[2] & CONTROL) != 0 ? actionCopy : (pointer[2] & SHIFT) != 0 ? actionMove : NULL;
        if (asked == NULL || !request.actions().contains(actionOf(asked))) {
            asked = atomOf(request.actions().iterator().next());
        }
        if ((pointer[2] & BUTTON1) == 0) {
            if (target != NULL && accepted) {
                send(targetProxy, target, drop, x.window(), 0, 0, 0, 0);
                dropped = true;
                dropDeadline = System.nanoTime() + FINISH_TIMEOUT_NANOS;
            } else {
                if (target != NULL) {
                    send(targetProxy, target, leave, x.window(), 0, 0, 0, 0);
                }
                end(null);
            }
            return;
        }
        showAnswer();
        long[] under = awareAt(pointer[0], pointer[1]);
        if (under[0] != target) {
            if (target != NULL) {
                send(targetProxy, target, leave, x.window(), 0, 0, 0, 0);
            }
            target = under[0];
            targetProxy = under[1];
            targetVersion = (int) under[2];
            accepted = false;
            waitingStatus = false;
            positionPending = false;
            lastX = Integer.MIN_VALUE;
            if (target != NULL) {
                List<Long> atoms = spellings();
                send(targetProxy, target, enter, x.window(), ((long) Math.min(VERSION, targetVersion) << 24) | (atoms.size() > 3 ? 1 : 0),
                        atoms.size() > 0 ? atoms.get(0) : NULL, atoms.size() > 1 ? atoms.get(1) : NULL,
                        atoms.size() > 2 ? atoms.get(2) : NULL);
            }
        }
        if (target != NULL && (pointer[0] != lastX || pointer[1] != lastY || asked != lastAction)) {
            lastX = pointer[0];
            lastY = pointer[1];
            lastAction = asked;
            if (waitingStatus) {
                positionPending = true;
            } else {
                tellPosition();
            }
        }
    }

    private void tellPosition() {
        waitingStatus = true;
        send(targetProxy, target, position, x.window(), 0, ((long) lastX << 16) | (lastY & 0xffff), 0, lastAction);
    }

    private boolean escapeHeld() {
        if (escapeKey == 0) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long keys = stack.ncalloc(1, 32, 1);
            x.x("XQueryKeymap", "pp>i", x.display(), keys);
            return (org.lwjgl.system.MemoryUtil.memGetByte(keys + escapeKey / 8) & (1 << (escapeKey % 8))) != 0;
        }
    }

    /**
     * The window under a point that speaks XDND, found from the root down: itself, where its
     * messages are to be sent (itself, or its proxy), and the version it speaks; zeros with none.
     */
    private long[] awareAt(int rootX, int rootY) {
        long window = root;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 3, 8);
            for (int depth = 0; depth < 32; depth++) {
                memPutAddress(out + 8, NULL);
                if (x.x("XTranslateCoordinates", "pppiippp>i", x.display(), root, window, rootX, rootY, out, out + 4,
                        out + 8) == 0) {
                    break;
                }
                long child = memGetAddress(out + 8);
                if (child == NULL) {
                    break;
                }
                window = child;
                long[] version = longs(window, aware);
                if (version.length > 0) {
                    long[] proxied = longs(window, proxy);
                    long to = window;
                    if (proxied.length > 0) {
                        long[] own = longs(proxied[0], proxy); // a proxy names itself, or it is a stale id
                        if (own.length > 0 && own[0] == proxied[0]) {
                            to = proxied[0];
                        }
                    }
                    return new long[] {window, to, version[0]};
                }
            }
        }
        return new long[] {NULL, NULL, 0};
    }

    /**
     * Shows what is dragged under the pointer: a window of its own that no window manager frames,
     * with an alpha channel where the screen is composited, and with no input region, so that the
     * pointer is never over it and the window beneath is still the one found. Where the server
     * has no such visual the pointer alone says a drag is on.
     */
    private void showPicture(DragRequest request, Entry origin) {
        limn.graphics.Image image = request.image();
        if (image == null || image.width() <= 0 || image.height() <= 0 || request.width() <= 0) {
            return;
        }
        int w = image.width();
        int h = image.height();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long display = x.display();
            int screen = (int) x.x("XDefaultScreen", "p>i", display);
            long visual = stack.ncalloc(8, 1, 64); // XVisualInfo: the visual first
            if (x.x("XMatchVisualInfo", "piiip>i", display, screen, 32, 4, visual) == 0) {
                return;
            }
            pictureColormap = x.x("XCreateColormap", "pppi>p", display, root, memGetAddress(visual), 0);
            long attributes = stack.ncalloc(8, 1, 112); // XSetWindowAttributes
            memPutInt(attributes + 88, 1);                   // override_redirect
            memPutAddress(attributes + 96, pictureColormap);
            int[] pointer = pointer();
            float scale = w / request.width();
            long out = stack.ncalloc(8, 3, 8);
            x.x("XTranslateCoordinates", "pppiippp>i", display, origin.xwindow, root, 0, 0, out, out + 4, out + 8);
            pictureHoldX = Math.max(0, Math.min(w, pointer[0] - (memGetInt(out) + Math.round(request.x() * scale))));
            pictureHoldY = Math.max(0, Math.min(h, pointer[1] - (memGetInt(out + 4) + Math.round(request.y() * scale))));
            // CWBackPixel | CWBorderPixel | CWOverrideRedirect | CWColormap
            picture = x.x("XCreateWindow", "ppiiiiiiippp>p", display, root, pointer[0] - pictureHoldX,
                    pointer[1] - pictureHoldY, w, h, 0, 32, 1, memGetAddress(visual),
                    (1L << 1) | (1L << 3) | (1L << 9) | (1L << 13), attributes);
            if (picture == NULL) {
                return;
            }
            if (xfixesLibrary == null) {
                xfixesLibrary = org.lwjgl.system.APIUtil.apiCreateLibrary("libXfixes.so.3"); // once, not per drag
            }
            org.lwjgl.system.SharedLibrary xfixes = xfixesLibrary;
            long region = Ffi.call(org.lwjgl.system.APIUtil.apiGetFunctionAddress(xfixes, "XFixesCreateRegion"),
                    "ppi>p", display, NULL, 0);
            Ffi.call(org.lwjgl.system.APIUtil.apiGetFunctionAddress(xfixes, "XFixesSetWindowShapeRegion"),
                    "ppiiip>v", display, picture, 2, 0, 0, region); // ShapeInput: nowhere
            Ffi.call(org.lwjgl.system.APIUtil.apiGetFunctionAddress(xfixes, "XFixesDestroyRegion"), "pp>v",
                    display, region);
            // The picture, blue first and each colour already times its alpha, as the server wants it.
            byte[] rgba = image.pixels();
            long data = nmemAlloc((long) w * h * 4);
            java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(data, w * h * 4);
            for (int i = 0; i < w * h * 4; i += 4) {
                int alpha = rgba[i + 3] & 0xFF;
                pixels.put(i, (byte) ((rgba[i + 2] & 0xFF) * alpha / 255));
                pixels.put(i + 1, (byte) ((rgba[i + 1] & 0xFF) * alpha / 255));
                pixels.put(i + 2, (byte) ((rgba[i] & 0xFF) * alpha / 255));
                pixels.put(i + 3, (byte) alpha);
            }
            long ximage = x.x("XCreateImage", "ppiiipiiii>p", display, memGetAddress(visual), 32, 2, 0, data, w, h,
                    32, 0);
            picturePixmap = x.x("XCreatePixmap", "ppiii>p", display, picture, w, h, 32);
            long gc = x.x("XCreateGC", "pppp>p", display, picturePixmap, 0, NULL);
            if (ximage != NULL) {
                x.x("XPutImage", "ppppiiiiii>i", display, picturePixmap, gc, ximage, 0, 0, 0, 0, w, h);
                memPutAddress(ximage + 16, NULL); // the pixels are ours to free, not XFree's
                x.x("XFree", "p>i", ximage);
            }
            nmemFree(data);
            x.x("XFreeGC", "pp>i", display, gc);
            x.x("XSetWindowBackgroundPixmap", "ppp>i", display, picture, picturePixmap);
            x.x("XMapRaised", "pp>i", display, picture);
        } catch (RuntimeException | LinkageError unavailable) {
            LOG.log(System.Logger.Level.DEBUG, "no picture for the drag", unavailable);
        }
    }

    private void hidePicture() {
        if (picture != NULL) {
            x.x("XDestroyWindow", "pp>i", x.display(), picture);
            picture = NULL;
        }
        if (picturePixmap != NULL) {
            x.x("XFreePixmap", "pp>i", x.display(), picturePixmap);
            picturePixmap = NULL;
        }
        if (pictureColormap != NULL) {
            x.x("XFreeColormap", "pp>i", x.display(), pictureColormap);
            pictureColormap = NULL;
        }
    }

    /**
     * Has the pointer say what the target under it answered: a copy, a move, a link, or that it
     * takes nothing. X has no drag to give a pointer shape to; but the press that began the drag
     * grabbed the pointer for the window it began in, and while that lasts the server shows that
     * window's shape wherever the pointer goes, so the shape is set on that window. The shapes
     * are the theme's, by the names the desktops use; a theme or a machine without them leaves
     * the window's own shape, as before.
     */
    private void showAnswer() {
        Entry from = outgoingFrom;
        if (from == null) {
            return;
        }
        long shape;
        if (target == NULL || !accepted) {
            shape = shape("no-drop", "dnd-no-drop", "not-allowed", "dnd-none");
        } else if (acceptedAction == actionMove) {
            shape = shape("move", "dnd-move");
        } else if (acceptedAction == actionLink) {
            shape = shape("link", "alias", "dnd-link");
        } else {
            shape = shape("copy", "dnd-copy");
        }
        if (shape != NULL && shape != shapeShown) {
            shapeShown = shape;
            x.x("XDefineCursor", "ppp>i", x.display(), from.xwindow, shape);
            x.x("XFlush", "p>i", x.display());
        }
    }

    private long shape(String... names) {
        Long known = shapes.get(names[0]);
        if (known != null) {
            return known;
        }
        if (!xcursorTried) {
            xcursorTried = true;
            try {
                xcursor = org.lwjgl.system.APIUtil.apiCreateLibrary("libXcursor.so.1");
            } catch (RuntimeException | LinkageError absent) {
                xcursor = null;
            }
        }
        long found = NULL;
        org.lwjgl.system.SharedLibrary library = xcursor;
        if (library != null) {
            long load = org.lwjgl.system.APIUtil.apiGetFunctionAddress(library, "XcursorLibraryLoadCursor");
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                for (int i = 0; i < names.length && found == NULL; i++) {
                    found = Ffi.call(load, "pp>p", x.display(), org.lwjgl.system.MemoryUtil.memAddress(stack.ASCII(names[i])));
                }
            }
        }
        shapes.put(names[0], found);
        return found;
    }

    /** The window the drag began in wears its own pointer shape again. */
    private void ownPointerAgain() {
        Entry from = outgoingFrom;
        outgoingFrom = null;
        if (from != null && shapeShown != NULL) {
            shapeShown = NULL;
            x.x("XUndefineCursor", "pp>i", x.display(), from.xwindow);
            x.x("XFlush", "p>i", x.display());
            ui.post(from.ownPointer);
        }
    }

    private void end(@Nullable DropAction action) {
        DragRequest request = outgoing;
        outgoing = null;
        target = NULL;
        dropped = false;
        x.pollMillis = incomingSource != NULL ? 16 : 250;
        hidePicture();
        ownPointerAgain();
        if (TRACE) {
            System.err.println("limn.drag(x11): ended with " + action);
        }
        if (request != null) {
            ui.post(() -> request.ended().accept(action));
        }
    }
}
