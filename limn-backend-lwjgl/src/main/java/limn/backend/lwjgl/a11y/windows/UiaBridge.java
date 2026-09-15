package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.StateNames;
import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.a11y.PlatformBridge;
import limn.concurrent.Threads;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The UI Automation bridge: what an application hands its window so that a screen reader on
 * Windows can read it.
 *
 * <p>An application installs it by handing it to the window and nothing else happens until a client
 * asks. {@link #openIfEnabled} answers {@link AccessibilityBridge#NONE} on a machine with no UI
 * Automation, which is every machine that is not Windows, so a cross-platform application can call
 * it unconditionally.
 *
 * <p><b>What lives here and which thread owns it</b>, from ADR&nbsp;039&nbsp;§3.4. The tree()
 * tree is a {@code volatile} field written by the user-interface thread and read by any RPC thread;
 * the registry is concurrent because an element is minted the first time a client navigates to a
 * node and up to three RPC threads may reach the same unvisited node at once; the listening gate is
 * no state at all, because UI Automation answers it. Nothing here holds a widget, and no widget can
 * reach anything here — a client may hold an element for minutes, and one that reached a widget
 * would pin a detached subtree for exactly that long.
 *
 * <p><b>What is not yet verified.</b> Everything above the platform calls is exercised by this
 * module's own tests on any machine. The calls into {@code uiautomationcore} and the window
 * procedure this attaches to one are not, and cannot be from here: they need a window, a client and
 * a screen reader. Phase 6 is finished when a client attaching to an idle window gets a tree and
 * NVDA reads the demo, and that run is the remaining work.
 */
public final class UiaBridge extends PlatformBridge {

    /** Written by the user-interface thread on publish, read by every RPC thread. */

    /** The host, for the verbs a client performs. Written on attach, read on RPC threads. */

    /** Node identifier to element, and interface pointer back to it. */
    private final UiaElements elements = new UiaElements();

    /** Interface pointer to the object behind it, for every pointer an element publishes. */
    private final Map<Long, UiaObject> objects = new ConcurrentHashMap<>();

    /** The window this tree is drawn in. */
    private final long hwnd;

    /** The last root provider handed to UI Automation, so detaching can disconnect it. */
    private volatile long rootProviderForDisconnect;

    /** The procedure this bridge put in front of the window's own, or {@code null}. */
    private volatile UiaWindow attachedWindow;

    /** What the user-interface thread has handed over and the drain thread has not yet raised. */
    private final UiaEvents events = new UiaEvents();

    /**
     * The thread that raises, or {@code null} while nothing has been emitted since the last stop.
     * Written by the user-interface thread only: started lazily by {@link #emit}, stopped and
     * joined by the whole-registry empty, which is the one order §3.4 allows.
     */
    private Thread drain;

    /** Set once the platform half is gone, so a late emit starts no thread nobody will stop. */
    private volatile boolean closed;

    /**
     * How many event subscriptions covering this window are standing: added minus removed. The
     * spike's reading for §13.5's second half; not yet a gate.
     */
    private final java.util.concurrent.atomic.AtomicInteger advised =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How long after a client's {@code WM_GETOBJECT} this window counts as read by someone who
     * never subscribed. Two seconds: an inspector or a test harness re-enters through the window
     * when it re-finds it, and a walk paid for two seconds after each of those is the price of
     * showing such a client a current tree rather than the one from its last ask.
     */
    static final long ASKED_WINDOW_NANOS = 2_000_000_000L;

    /** When a client last asked this window for its root, on {@link #clock}'s scale; never, at first. */
    private volatile long lastAskedNanos = Long.MIN_VALUE / 2;

    /**
     * Whether a client has asked and has not yet been told of a change. Set by every ask, cleared
     * by the first raise that reaches an element a client holds — so a reader that looked at this
     * window and then waited hears the next thing that happens in it, however long that takes,
     * and decides on that whether to subscribe. Measured on the guest: NVDA asks for the root
     * three times when a window appears, hears nothing if the gate closes before the focus first
     * moves, and subscribes once it has followed one focus event.
     */
    private volatile boolean owedAnEvent;

    private final java.util.function.LongSupplier clock;

    private UiaBridge(long hwnd, java.util.function.LongSupplier clock) {
        this.hwnd = hwnd;
        this.clock = clock;
    }

    /**
     * Opens a bridge if this machine has UI Automation, and otherwise nothing.
     *
     * <p>The gate is read before anything is allocated: a window on a machine with no screen reader
     * is meant to cost one call and never a registry, an element or a thread.
     *
     * @param hwnd the window handle to place the tree under, from the backend's native window
     * @return a bridge, or {@link AccessibilityBridge#NONE} on a machine with no UI Automation
     */
    public static AccessibilityBridge openIfEnabled(long hwnd) {
        if (!Uia.isAvailable() || !UiaWindow.isAvailable() || hwnd == 0) {
            return AccessibilityBridge.NONE;
        }
        UiaBridge bridge = new UiaBridge(hwnd, System::nanoTime);
        bridge.attachedWindow = UiaWindow.attach(hwnd, bridge);
        // A window whose procedure could not be replaced is a window no client can ask, so there
        // is nothing for this bridge to answer and no reason to make the scene walk for it.
        return bridge.attachedWindow == null ? AccessibilityBridge.NONE : bridge;
    }

    /**
     * The same bridge without the platform gate, so that everything above the platform calls can
     * be exercised on a machine that has none.
     *
     * <p>Package-private and used by this module's own tests only. It is not a way to install a
     * bridge on a machine with no UI Automation: the platform calls it makes are all no-ops there,
     * so what it serves is elements nobody can reach.
     *
     * @param hwnd a window handle, which may be anything on a machine with no windows
     * @return a bridge
     */
    static UiaBridge withoutTheGate(long hwnd) {
        return new UiaBridge(hwnd, System::nanoTime);
    }

    /** The same, on a clock a test moves. */
    static UiaBridge withoutTheGate(long hwnd, java.util.function.LongSupplier clock) {
        return new UiaBridge(hwnd, clock);
    }

    /** @return when a client last asked for the root, on the clock's scale; for tests. */
    long lastAskedForTests() {
        return lastAskedNanos;
    }

    /** @param nodeId a node from the tree() tree
     *  @return the object serving it, or {@code null} if the tree does not hold it. For tests. */
    UiaObject objectFor(long nodeId) {
        UiaElement element = elementOf(nodeId);
        return element == null ? null : objects.get(element.pointer());
    }

    /**
     * @return the context the provider slots read, so a test can ask it the same questions UI
     *         Automation does. It exists because the one defect this file has had since it was
     *         written was invisible to every test here: the context's own answers were reached
     *         only from a live client
     */
    UiaProvider.Context contextForTests() {
        return context;
    }

    /** @return how many nodes this bridge currently holds an element for */
    int elementCount() {
        return elements.size();
    }

    /** @return the drain thread while one is running, for tests. */
    Thread drainThreadForTests() {
        return drain;
    }

    /** @return how many times the queue has collapsed since this bridge opened (§13.19). */
    int collapses() {
        return events.collapses();
    }

    /** @return how many client event subscriptions cover this window right now. */
    int advisedEvents() {
        return advised.get();
    }

    /**
     * @param nodeId a node
     * @return whether a client has ever asked for it, so that an event on it reaches the platform
     *         rather than returning at the top of {@link #emit}. A peek and never a mint: this is
     *         what the live probe reads to tell a raise from a skip when it counts, and a probe
     *         that minted elements while counting would be counting its own
     */
    boolean holdsElementFor(long nodeId) {
        return elements.peek(nodeId) != null;
    }

    /**
     * <p>Asked once per frame on the user-interface thread, and answered <b>per window</b>.
     *
     * <p>{@code UiaClientsAreListening()} alone was the gate until 2026-09-07, and it is
     * process-wide: true once any client in the session holds any event handler, which on the
     * Windows 11 guest was seventeen processes with no reader among them — so every window paid
     * the walk on every damaged frame for nobody (ADR&nbsp;039 &sect;13.5). It keeps its honest
     * half here, the negative: when it is false nobody is listening to anything. When it is true,
     * what decides is this window's own two facts — whether a client's event subscription covers
     * it, which UI Automation says through {@code IRawElementProviderAdviseEvents} on the root and
     * says again when the subscription is withdrawn; and whether a client asked for the root through
     * {@code WM_GETOBJECT} within the last {@link #ASKED_WINDOW_NANOS}, which is the one thing a
     * client that reads without ever subscribing does. Measured on the guest: with no reader the
     * flag was true and neither fact held; with NVDA attached the subscription arrived before the
     * first frame.
     */
    @Override
    public boolean isListening() {
        return listening(Uia.clientsAreListening(), advised.get(), lastAskedNanos,
                clock.getAsLong(), owedAnEvent);
    }

    /**
     * The gate's decision, as a function of the four facts so that a machine with no UI
     * Automation can still pin it.
     *
     * @param anyoneInTheSession the process-wide flag, whose {@code false} is the reliable half
     * @param standing           how many client subscriptions cover this window
     * @param askedNanos         when a client last asked for the root
     * @param now                the clock
     * @return whether a frame of this window is worth a walk
     */
    static boolean listening(boolean anyoneInTheSession, int standing, long askedNanos, long now) {
        return listening(anyoneInTheSession, standing, askedNanos, now, false);
    }

    /**
     * @param owedAnEvent whether a client asked and has not yet been told of one change
     * @return whether a frame of this window is worth a walk
     */
    static boolean listening(boolean anyoneInTheSession, int standing, long askedNanos, long now,
                             boolean owedAnEvent) {
        if (!anyoneInTheSession) {
            return false;
        }
        return standing > 0 || owedAnEvent || now - askedNanos < ASKED_WINDOW_NANOS;
    }

    /**
     * <p>Yes: a client that attached while this window was idle asked for a tree and got the empty
     * one, and nothing else will prompt a walk until something moves.
     */
    @Override
    public boolean needsPrimingPublish() {
        return true;
    }

    /**
     * <p>The snapshot is swapped whole. A client reading on an RPC thread sees the previous tree or
     * the next one and never half of either, which is the property the whole record is built on.
     *
     * @param tree      the new snapshot
     * @param reentrant whether this publish happened inside a client's own call
     */
    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        super.publish(tree, reentrant);
    }

    /**
     * <p>Handed over, never raised here. This used to raise straight through on the thread the
     * scene drained on — the user-interface thread — on the belief that a raise returns without
     * waiting for a client, and the belief is false: measured with NVDA attached (ADR&nbsp;039
     * &sect;13.28), a property-changed raise waits for the reader's handler and its calls back into
     * this provider, 2.5&nbsp;ms median and one of 50&nbsp;ms, which is a frame's budget spent
     * inside the platform. So this offers to the bounded queue &sect;1.10 asks of every bridge and
     * returns, and {@link #raise} runs on a thread of this bridge's own, started the first time
     * there is something to raise — a window nobody reads starts no thread.
     *
     * @param event what moved
     */
    @Override
    public void emit(AccessibleEvent event) {
        if (closed) {
            return;
        }
        events.offer(event);
        if (drain == null) {
            drain = Threads.daemon("limn-a11y-uia-drain", this::drainLoop);
        }
    }

    /**
     * The drain thread's whole life: take, raise, until stopped. A collapse marker is a sweep of
     * the registry against the published tree — every element whose node has left is released,
     * which is what the swallowed {@code NODE_DESTROYED}s would have done one by one (§1.10) —
     * followed by one invalidate-everything raise on the root.
     */
    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                AccessibleEvent event = events.take();
                if (event == UiaEvents.COLLAPSE) {
                    sweepAndInvalidate();
                } else {
                    raise(event);
                }
            }
        } catch (InterruptedException stopped) {
            // The user-interface thread is emptying the registry and asked this thread to leave
            // first. Whatever is still queued is about a tree that is going away with it.
        }
    }

    /**
     * Stops and joins the drain thread, on the user-interface thread, before the registry is
     * touched by anyone else. A raise in flight is waited for: it holds an element the empty is
     * about to free, and the platform is inside it.
     */
    private void stopDrain() {
        Thread thread = drain;
        if (thread == null) {
            return;
        }
        drain = null;
        thread.interrupt();
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        UiaWindow.say("drain stopped");
    }

    private void sweepAndInvalidate() {
        AccessibleTree tree = tree();
        int swept = 0;
        for (Long id : elements.ids()) {
            if (tree.indexOf(id) < 0 && elements.remove(id)) {
                swept++;
            }
        }
        UiaWindow.say("collapse: swept " + swept + " elements");
        if (tree.nodeCount() > 0) {
            raise(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, tree.root().id()));
        }
    }

    /**
     * One event, raised on the drain thread.
     *
     * <p>{@code NODE_DESTROYED} raises nothing and releases this bridge's claim on the element: a
     * client still holding it gets {@code UIA_E_ELEMENTNOTAVAILABLE} from then on, which is what
     * §1.3 promises, and a client that never asked for it has nothing to release.
     *
     * @param event what moved
     */
    private void raise(AccessibleEvent event) {
        if (event.type() == AccessibleEvent.Type.NODE_DESTROYED) {
            if (elements.remove(event.nodeId())) {
                UiaWindow.say("released element for destroyed node " + event.nodeId());
            }
            return;
        }
        int eventId = switch (event.type()) {
            case FOCUS_CHANGED -> UiaIds.AUTOMATION_FOCUS_CHANGED;
            case INVOKED -> UiaIds.INVOKE_INVOKED;
            case SELECTION_CHANGED -> UiaIds.SELECTION_ITEM_ELEMENT_SELECTED;
            case STRUCTURE_CHANGED -> UiaIds.STRUCTURE_CHANGED;
            case ANNOUNCEMENT -> UiaIds.NOTIFICATION;
            case WINDOW_OPENED -> UiaIds.WINDOW_OPENED;
            case WINDOW_CLOSED -> UiaIds.WINDOW_CLOSED;
            case TEXT_CHANGED -> UiaIds.TEXT_CHANGED;
            case TEXT_SELECTION_CHANGED -> UiaIds.TEXT_SELECTION_CHANGED;
            case INVALIDATED -> UiaIds.LAYOUT_INVALIDATED;
            // The rest are property changes, which UI Automation takes through a call of its own.
            default -> 0;
        };
        UiaElement element = elements.peek(event.nodeId());
        if (element == null) {
            // Nothing has ever asked for this node, so no client is holding an element to be told
            // about. It will read whatever is current the first time it does ask.
            return;
        }
        int propertyId = 0;
        AccessibleNode node = null;
        if (eventId == 0) {
            AccessibleTree tree = tree();
            int index = tree.indexOf(event.nodeId());
            node = index < 0 ? null : tree.node(index);
            propertyId = changedProperty(event, node);
            if (propertyId == 0) {
                // Mapped to nothing on this platform: WINDOW_ACTIVATED and WINDOW_DEACTIVATED,
                // which since ADR 039 §1.10's amendment of 2026-09-14 name the window node every
                // client that asked holds, BOUNDS_CHANGED, a state with no property of its own.
                // Nothing is raised, so nothing pays the change a client that asked is owed:
                // only a raise that reached the client may clear the flag (WINDOWS-NEW-6; the
                // mappings themselves are phase 3's).
                UiaWindow.say("unmapped " + event.type() + " for node " + event.nodeId());
                return;
            }
        }
        long started = System.nanoTime();
        if (eventId != 0) {
            Uia.raiseAutomationEvent(element.pointer(), eventId);
        } else {
            raisePropertyChange(element, propertyId, event, node);
        }
        // The one change a client that asked was owed. From here it is its subscription, or a
        // fresh ask, that keeps this window read.
        owedAnEvent = false;
        UiaWindow.say("raised " + event.type() + " for node " + event.nodeId() + " in "
                    + (System.nanoTime() - started) / 1_000 + " us on "
                    + Thread.currentThread().getName());
    }

    /**
     * A property that moved, which UI Automation is told about with both values.
     *
     * <p><b>Which property depends on the node and not only on the event</b>: a value that moved is
     * a number on a slider and a string in a text field, and the two are different properties to a
     * client. A state that moved is whichever property carries that state — a check mark is the
     * toggle pattern's, an enabled flag is the element's own — so a state this bridge has no
     * property for is not raised rather than raised as something else.
     *
     * <p>Both values are written into {@code VARIANT}s allocated for the call and freed after it.
     * A string among them is a {@code BSTR} the callee reads and does not keep, which is the one
     * case where this bridge frees a string it allocated.
     *
     * @param element    the element a client holds for the node
     * @param propertyId the property {@link #changedProperty} chose; never {@code 0} here
     * @param event      what moved
     * @param node       the node it moved on, or {@code null} when it has left the tree
     */
    private void raisePropertyChange(UiaElement element, int propertyId, AccessibleEvent event,
                                     AccessibleNode node) {
        long before = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        long after = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        try {
            java.nio.ByteBuffer oldOne = MemoryUtil.memByteBuffer(before, UiaVariant.SIZE);
            java.nio.ByteBuffer newOne = MemoryUtil.memByteBuffer(after, UiaVariant.SIZE);
            write(oldOne, propertyId, changedValue(propertyId, event.oldValue(), node));
            write(newOne, propertyId, changedValue(propertyId, event.newValue(), node));
            int hresult = Uia.raisePropertyChangedEvent(element.pointer(), propertyId,
                    before, after);
            UiaWindow.say("property " + propertyId + " changed -> 0x"
                        + Integer.toHexString(hresult));
            freeIfString(oldOne);
            freeIfString(newOne);
        } finally {
            MemoryUtil.nmemFree(before);
            MemoryUtil.nmemFree(after);
        }
    }

    /**
     * The property a change raises, or {@code 0} for one this platform is not told about.
     *
     * @param event what changed
     * @param node  the node it changed on, or {@code null} when it is no longer in the tree
     */
    static int changedProperty(AccessibleEvent event, AccessibleNode node) {
        return switch (event.type()) {
            case NAME_CHANGED -> UiaIds.NAME;
            case DESCRIPTION_CHANGED -> UiaIds.HELP_TEXT;
            case VALUE_CHANGED -> node != null && node.value() != null
                    ? UiaIds.RANGE_VALUE_VALUE : UiaIds.VALUE_VALUE;
            case STATE_CHANGED -> switch (event.state()) {
                case CHECKED, MIXED -> UiaIds.TOGGLE_STATE;
                case ENABLED -> UiaIds.IS_ENABLED;
                case SELECTED -> UiaIds.SELECTION_ITEM_IS_SELECTED;
                case EXPANDED -> UiaIds.EXPAND_COLLAPSE_EXPAND_COLLAPSE_STATE;
                case READ_ONLY -> UiaIds.VALUE_IS_READ_ONLY;
                // The status string UiaProperties answers BUSY with; a client that caches it is
                // told when the item starts and stops working, or it would go on reading a folder
                // as loading after its children arrived.
                case BUSY -> UiaIds.ITEM_STATUS;
                default -> 0;
            };
            // A rectangle that moved is not raised: UI Automation watches an HWND's own bounds and
            // a client re-reads a fragment's when it needs them, so raising it per node would be a
            // storm of events during every drag for something nobody asked to be told.
            default -> 0;
        };
    }

    /**
     * A change's value as the property carries it, where that is not the model's own type: BUSY
     * moves as a boolean and ItemStatus is a string, the phrase while busy and empty after.
     *
     * @param propertyId the property {@link #changedProperty} chose
     * @param value      the event's old or new value
     * @param node       the node, for its locale, or {@code null} when it is gone
     */
    static Object changedValue(int propertyId, Object value, AccessibleNode node) {
        if (propertyId == UiaIds.ITEM_STATUS && value instanceof Boolean busy) {
            return busy ? StateNames.of(Accessible.State.BUSY,
                    node != null ? node.locale() : java.util.Locale.ENGLISH) : "";
        }
        return value;
    }

    /**
     * Writes a value into a variant <b>as the property's own type</b>, which is not always the
     * model's.
     *
     * <p>The toolkit says a check box is checked with a boolean, and UI Automation says it with an
     * integer: {@code ToggleState} is an enumeration, and so is {@code ExpandCollapseState}. A
     * boolean written where a client expects one of those is not a wrong value, it is a wrong
     * <em>type</em> — and a client reading it hears nothing rather than hearing the opposite, which
     * is why the first live run of this path announced the focus move and stayed silent about the
     * state that moved with it.
     */
    private void write(java.nio.ByteBuffer variant, int propertyId, Object value) {
        boolean anEnumeration = propertyId == UiaIds.TOGGLE_STATE
                || propertyId == UiaIds.EXPAND_COLLAPSE_EXPAND_COLLAPSE_STATE;
        if (value instanceof Boolean flag && anEnumeration) {
            UiaVariant.i4(variant, 0, flag ? 1 : 0);
        } else if (value instanceof Boolean flag) {
            UiaVariant.bool(variant, 0, flag);
        } else if (value instanceof Number number) {
            UiaVariant.r8(variant, 0, number.doubleValue());
        } else if (value instanceof String text) {
            UiaVariant.bstr(variant, 0, UiaStrings.system().allocate(text));
        } else {
            UiaVariant.empty(variant, 0);
        }
    }

    private void freeIfString(java.nio.ByteBuffer variant) {
        if (UiaVariant.tagOf(variant, 0) == UiaVariant.VT_BSTR) {
            UiaStrings.free(variant.getLong(UiaVariant.PAYLOAD));
        }
    }

    @Override
    protected void releasePlatformHalf() {
        closed = true;
        // The root provider was disconnected by the registry empty that ran before this, which is
        // the only order that works: see disconnectRootProvider. What is left is the window.
        UiaWindow window = attachedWindow;
        if (window != null) {
            window.detach();
            attachedWindow = null;
        }
    }

    /**
     * Tells UI Automation to drop the root provider, <b>while the object behind it is still
     * alive.</b>
     *
     * <p>{@code UiaDisconnectProvider} is not a note to a table: it releases every reference the
     * platform holds on the provider, and a release is a call through the object's own vtable —
     * {@code Release} is one of the closures this bridge made. Freeing the registry first and
     * disconnecting after therefore hands the platform a pointer whose closures are gone, and the
     * call lands in freed trampoline memory: an access violation in the message pump on every
     * window close, which is how every live run on the guest ended once a client had asked for
     * the root, benchmark included. The base class empties the registry before it releases the
     * platform half, so the disconnect belongs at the top of the empty and not in the release.
     *
     * <p>Our own root provider, and not the window's: the host provider is UI Automation's own
     * and every reference to it was handed over already.
     */
    private void disconnectRootProvider() {
        long provider = rootProviderForDisconnect;
        if (provider != 0) {
            rootProviderForDisconnect = 0;
            // Whether the registry still holds the object behind the pointer, read before the
            // call: the trace says so, and the test that pins this order reads the trace.
            boolean alive = objects.containsKey(provider);
            Uia.disconnectProvider(provider);
            UiaWindow.say("disconnected root provider 0x" + Long.toHexString(provider)
                        + " alive=" + alive);
        }
    }

    /**
     * The whole-registry empty of §3.4.
     *
     * <p><b>By identity, because one object answers to several pointers.</b> An element serving the
     * two interfaces every node has, plus one per pattern it vends, appears in the pointer map
     * three or four times — and freeing it once per pointer frees closures that are already gone.
     * That is a crash in this process rather than a leak, which is the better failure to have found
     * here.
     */
    /**
     * <p>Over a live host, the registry is emptied: the elements it holds stand for a tree that is
     * about to be replaced, and an element that outlived its host would answer about a node from
     * someone else's window.
     */
    @Override
    protected void invalidateEverythingVended() {
        // The drain thread first, stopped and joined: it is the only other remover, and a raise
        // in flight holds an element this is about to free (§3.4).
        stopDrain();
        // Then, while every closure the platform may call back through is still there.
        disconnectRootProvider();
        java.util.Set<UiaObject> distinct =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        distinct.addAll(objects.values());
        objects.clear();
        elements.empty();
        distinct.forEach(UiaObject::free);
        UiaWindow.say("freed " + distinct.size() + " objects");
    }

    /**
     * What a window procedure answers {@code WM_GETOBJECT} with.
     *
     * <p>The one place UI Automation is told this window has a provider at all. It is answered
     * synchronously, on the user-interface thread, inside the message — so the tree it hands over
     * is the one already tree(), and nothing walks here.
     *
     * @param wparam the message's own
     * @param lparam the same
     * @return the {@code LRESULT} for the window procedure to return
     */
    public long answerGetObject(long wparam, long lparam) {
        noteAsked();
        // And NOTHING is published or requested here. §3.1 first said to publish inside this
        // message, before the root is handed over; measured with NVDA on 2026-09-07, a publish
        // inside the ask — and, in a second run, one merely requested for the next frame — left
        // the reader announcing the window and never anything in it, while the same build that
        // handed over the tree it already had and let the gate do the rest read every value. The
        // priming publish is a truthful tree; the ask opens the gate (noteAsked), and the first
        // frame something moves publishes and raises it, outside anyone's call.
        long root = rootElement();
        return root == 0 ? 0 : Uia.returnRawElementProvider(hwnd, wparam, lparam, root);
    }

    /**
     * A client asked this window for something — any {@code WM_GETOBJECT}, whichever object it
     * named. For the next {@link #ASKED_WINDOW_NANOS} this window is read, even by a client that
     * never subscribes to anything. User-interface thread, inside the message.
     */
    public void noteAsked() {
        lastAskedNanos = clock.getAsLong();
        owedAnEvent = true;
    }

    /** @return whether a client asked and has not yet heard a change; for tests. */
    boolean owesAnEvent() {
        return owedAnEvent;
    }

    /** @return the root node's element, minting it if this is the first ask, or {@code 0} */
    private long rootElement() {
        AccessibleTree tree = tree();
        // The simple interface, because that is what UiaReturnRawElementProvider is declared to
        // take -- and referenced, because UI Automation keeps it.
        if (tree.nodeCount() == 0) {
            return 0;
        }
        long provider = handOver(tree.root().id(), UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        rootProviderForDisconnect = provider;
        return provider;
    }

    /**
     * Everything the slots read, in one place, so that no element holds anything but its own
     * identifier.
     */
    private final UiaProvider.Context context = new UiaProvider.Context() {

        @Override
        public AccessibleTree tree() {
            return UiaBridge.this.tree();
        }

        @Override
        public long patternProviderFor(long nodeId, int patternId) {
            return handOver(nodeId, UiaPatternProviders.interfaceFor(patternId));
        }

        /**
         * <p>Asked for afresh every time, and never cached.
         *
         * <p>{@code UiaHostProviderFromHwnd} answers with a reference the caller owns, and the
         * caller here is UI Automation, which releases it when it is done. Handing the same
         * pointer over twice hands over a reference we no longer have — the second client to ask
         * gets an object that has already been freed. That is not a subtle failure and it is not a
         * slow one: the live run crashed on the second {@code WM_GETOBJECT}, in native code, with
         * no Java frame to name.
         */
        @Override
        public long hostProvider() {
            return Uia.hostProviderFromHwnd(hwnd);
        }

        @Override
        public UiaStrings.Allocator strings() {
            return UiaStrings.system();
        }

        @Override
        public long int32Array(int[] values) {
            return UiaStrings.int32Array(values);
        }

        @Override
        public long unknownArray(long[] pointers) {
            return UiaStrings.unknownArray(pointers);
        }

        /**
         * <p><b>The fragment interface and not the object's primary one.</b> Every pointer a COM
         * method returns has a declared type, and a client walking slot 5 of what it believes is
         * {@code IRawElementProviderFragment} finds slot 5 of whatever vtable it was actually
         * handed. Answering navigation with the simple interface is the same misdispatch the slot
         * order was read to prevent, arriving through the other door -- and it is what the live run
         * caught: every call answered S_OK and UI Automation refused the provider anyway.
         */
        @Override
        public long elementFor(long nodeId) {
            return handOver(nodeId, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        }

        @Override
        public long simpleElementFor(long nodeId) {
            return handOver(nodeId, UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        }

        /** <p>And the fragment <em>root</em> interface here, for the same reason. */
        @Override
        public long rootElement() {
            AccessibleTree tree = tree();
            return tree.nodeCount() == 0 ? 0
                    : handOver(tree.root().id(), UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT);
        }

        @Override
        public boolean requestFocus(long nodeId) {
            return perform(nodeId, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        }

        @Override
        public void eventAdvised(int eventId, int[] propertyIds, boolean added) {
            int now = added ? advised.incrementAndGet() : advised.decrementAndGet();
            UiaWindow.say("advise " + (added ? "added" : "removed") + " event " + eventId
                        + " properties " + java.util.Arrays.toString(propertyIds)
                        + " standing=" + now + " on " + Thread.currentThread().getName());
        }

        @Override
        public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            Host current = host();
            return current != null && current.perform(nodeId, action, arg);
        }
    };

    /**
     * One interface pointer of a node's element, counted for the caller.
     *
     * <p>Every pointer this bridge returns from a COM method is a reference the caller owns and
     * will release, so it is counted here. Handing one over uncounted is an object freed while a
     * client still holds it.
     *
     * @param nodeId the node
     * @param iface  which of its interfaces, or {@code null} for none
     * @return the pointer, already referenced, or {@code 0}
     */
    private long handOver(long nodeId, UiaInterfaces.Vtable iface) {
        if (iface == null) {
            return 0;
        }
        UiaElement element = elementOf(nodeId);
        if (element == null) {
            return 0;
        }
        UiaObject object = objects.get(element.pointer());
        long pointer = object == null ? 0 : object.pointerFor(iface);
        if (pointer != 0) {
            object.addRef();
        }
        return pointer;
    }

    /**
     * The element for a node, made once however many RPC threads ask at once.
     *
     * <p>An element serves the two interfaces every node has, the fragment root's two where it is
     * the root, and one interface per pattern the node vends — all on one object, so a client that
     * queried for the toggle interface and one that queried for the fragment interface are holding
     * the same thing and can tell.
     *
     * <p><b>The pattern interfaces are the node's set as the snapshot has it at each ask, not at
     * the first one (W2, 2026-09-15).</b> They are the object's {@linkplain UiaObject.Varying
     * varying} interfaces: every query and every hand-over reads {@link UiaPatterns#supports}
     * against the tree of that moment, builds a pattern gained since in a field the object
     * reserved, and refuses one lost since. Before, the list was fixed when the element was
     * minted, so a Tree element minted before its cursor row existed answered {@code Invoke} with
     * a null for as long as the client held it, and a calendar cell first seen in the month
     * chooser never regained {@code SelectionItem} in the day view. <b>Nothing is re-minted</b>:
     * the registry entry, the identity pointer, the reference count and every pointer a client
     * holds stay what they were, the root's included, so {@link #disconnectRootProvider} still
     * disconnects the one root UI Automation was handed.
     *
     * @param nodeId a node from the tree() tree
     * @return its element, or {@code null} when the tree no longer holds that node
     */
    private UiaElement elementOf(long nodeId) {
        AccessibleTree tree = tree();
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return null;
        }
        boolean isRoot = tree.root().id() == nodeId;
        return elements.forNode(nodeId, id -> {
            List<UiaObject.Served> served = new ArrayList<>();
            served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                    UiaProvider.simpleSlots(id, context)));
            served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT,
                    UiaProvider.fragmentSlots(id, context)));
            if (isRoot) {
                served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                        UiaProvider.fragmentRootSlots(context)));
                // The root alone, because it is where UI Automation looks for it: a client's
                // subscription covers a window, and the window is what the root stands for.
                served.add(new UiaObject.Served(UiaInterfaces.ADVISE_EVENTS,
                        UiaProvider.adviseEventsSlots(context)));
            }
            UiaObject object = UiaObject.create(served, new PatternsOf(id), () -> { });
            object.pointers().forEach(pointer -> objects.put(pointer, object));
            return new Element(id, object);
        });
    }

    /**
     * One node's pattern interfaces as the snapshot of the moment decides them: what an element's
     * object asks on every query and hand-over. Holds the node's identifier and nothing else.
     */
    private final class PatternsOf implements UiaObject.Varying {

        private final long nodeId;

        PatternsOf(long nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public List<UiaInterfaces.Vtable> candidates() {
            return PATTERN_INTERFACES;
        }

        @Override
        public boolean servesNow(UiaInterfaces.Vtable iface) {
            AccessibleTree tree = tree();
            AccessibleNode node = tree.find(nodeId);
            return node != null && UiaPatterns.supports(tree, node, patternOf(iface));
        }

        @Override
        public Map<String, ? extends org.lwjgl.system.CallbackI> slotsFor(
                UiaInterfaces.Vtable iface) {
            return UiaPatternProviders.slotsFor(patternOf(iface), nodeId, context);
        }
    }

    /** The patterns a node may vend, asked in a fixed order so an element is built the same way. */
    private static final int[] PATTERNS = {
            UiaIds.INVOKE_PATTERN, UiaIds.TOGGLE_PATTERN, UiaIds.VALUE_PATTERN,
            UiaIds.RANGE_VALUE_PATTERN, UiaIds.EXPAND_COLLAPSE_PATTERN,
            UiaIds.SELECTION_ITEM_PATTERN, UiaIds.SCROLL_ITEM_PATTERN,
            UiaIds.GRID_PATTERN, UiaIds.TABLE_PATTERN, UiaIds.GRID_ITEM_PATTERN,
            UiaIds.TABLE_ITEM_PATTERN,
    };

    /** The interface each of {@link #PATTERNS} is served through, in the same order. */
    private static final List<UiaInterfaces.Vtable> PATTERN_INTERFACES =
            java.util.Arrays.stream(PATTERNS).mapToObj(UiaPatternProviders::interfaceFor).toList();

    /** @return the pattern an interface of {@link #PATTERN_INTERFACES} serves */
    private static int patternOf(UiaInterfaces.Vtable iface) {
        return PATTERNS[PATTERN_INTERFACES.indexOf(iface)];
    }

    /** One node's COM object, as the registry sees it. */
    private record Element(long nodeId, UiaObject object) implements UiaElement {

        @Override
        public long pointer() {
            return object.pointer();
        }

        @Override
        public void release() {
            // Removal is not release: the object survives until every client reference drops, and
            // the whole-registry empty is what frees it. See UiaElements.
        }
    }
}
