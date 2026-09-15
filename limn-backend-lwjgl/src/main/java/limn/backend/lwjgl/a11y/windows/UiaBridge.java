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

    /**
     * Interface pointer to the object behind it, for the pointers an element's object built when
     * it was minted: its identity pointer, which every reader here goes by (a hand-over, the
     * disconnect's liveness check, the whole-registry empty, which frees by object identity), and
     * the fixed and pattern interfaces built with it. A pattern interface built later, when the
     * snapshot first serves it (W2), is not entered: nothing looks an object up by that pointer.
     */
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
     * Every bridge holding a published tree in this process, so that a cursor resolved into a
     * native popup's tree (decision 5: {@link AccessibleTree#activeDescendant()} may name a node
     * another window minted) is answered and raised through the provider of the window that holds
     * it. Added on a publish, removed on detach; read by drain and RPC threads.
     */
    private static final java.util.Set<UiaBridge> OPEN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Held by the whole-registry empty while it frees this bridge's elements, and by every entry
     * that reaches one of them <b>from another window</b>: that window's drain thread raising a
     * focus change or a {@code HasKeyboardFocus} change here ({@link #raiseOnElement}), and that
     * window's RPC thread
     * handing this window's element to UI Automation from its own {@code GetFocus}
     * ({@link #handOverFromAnotherWindow}). So the empty never frees an element such a raise or
     * hand-over is standing on, and neither mints into a registry the empty is clearing. This
     * bridge's own drain needs no such guard, because the empty stops and joins it first. Its own
     * RPC calls do not take it either: they arrive through its own provider, whose root the empty
     * disconnects first, and a call arriving through another window's provider, still connected,
     * has not even that.
     */
    private final Object vendGuard = new Object();

    /**
     * One focus change raised: the bridge whose element it was raised on (another window's, for a
     * cursor in a native popup) and the node.
     *
     * @param owner  the bridge holding the element; compared by identity
     * @param nodeId the node
     */
    private record Announced(UiaBridge owner, long nodeId) {
    }

    /**
     * The last effective focus announced in this process, by whichever bridge's drain raised it
     * (semantics 4: each bridge remembers the last effective focus it announced; one memory for
     * the process, because UI Automation has one focus and a raise in another window moves it).
     * A focus event that names it again is not raised again; a re-announcement after a collapse
     * or the model's INVALIDATED is, whatever it names. Forgotten when nothing in a window is
     * focused, when a window is deactivated, and when the bridge that owns it empties.
     */
    private static final java.util.concurrent.atomic.AtomicReference<Announced> ANNOUNCED =
            new java.util.concurrent.atomic.AtomicReference<>();

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

    /**
     * The node the event just drained raised {@code CARET_MOVED} on, or {@code 0}: a
     * {@code TEXT_SELECTION_CHANGED} on the same node right behind it is the same
     * {@code Text_TextSelectionChanged} and is not raised twice. Drain thread only.
     */
    private long caretJustRaised;

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
        if (!closed && tree.nodeCount() > 0) {
            OPEN.add(this);
        }
    }

    /** <p>And leaves the process's set of open bridges before the tree is dropped. */
    @Override
    public void detach() {
        OPEN.remove(this);
        super.detach();
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
                long caret = caretJustRaised;
                caretJustRaised = 0;
                if (caret != 0 && event.type() == AccessibleEvent.Type.TEXT_SELECTION_CHANGED
                        && event.nodeId() == caret) {
                    // The model names a caret move and a selection move of one field one after
                    // the other; UI Automation has one event for both (ADR 039 §2.4).
                    UiaWindow.say("TEXT_SELECTION_CHANGED for node " + caret
                            + " raised with its CARET_MOVED");
                    continue;
                }
                if (event == UiaEvents.COLLAPSE) {
                    sweepAndInvalidate();
                    raiseFocus("after this bridge's queue collapsed", true);
                } else if (event.type() == AccessibleEvent.Type.INVALIDATED
                        && event.nodeId() == 0) {
                    // The model's own collapse (§1.10): a publish wider than its budget, whose
                    // per-node events -- NODE_DESTROYED among them -- became this one event. The
                    // same sweep as this queue's, and the same re-announcement; the root-targeted
                    // INVALIDATED the sweep raises names the root and comes back through raise().
                    sweepAndInvalidate();
                    raiseFocus("after the model's INVALIDATED", true);
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
        if (event.type() == AccessibleEvent.Type.FOCUS_CHANGED
                || event.type() == AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED
                || event.type() == AccessibleEvent.Type.WINDOW_ACTIVATED) {
            // The last of the three is ADR 039 §2.4's "focus change into the window": what the
            // user returns to is the window's effective focus, raised unless it is the one
            // already announced, which a deactivation forgot.
            raiseFocus(event.type().name(), false);
            return;
        }
        if (event.type() == AccessibleEvent.Type.WINDOW_DEACTIVATED) {
            // Nothing is raised: UI Automation follows the focus into whatever window took it.
            // What changes is the memory, so the return to this window is heard however little
            // moved while it was away; and nothing raised is nothing paid (WINDOWS-NEW-6).
            ANNOUNCED.set(null);
            UiaWindow.say("WINDOW_DEACTIVATED for node " + event.nodeId()
                    + " raises nothing and forgets the announced focus");
            return;
        }
        if (event.type() == AccessibleEvent.Type.SELECTION_CHANGED) {
            raiseSelection(event);
            return;
        }
        if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
            raiseAnnouncement(event);
            return;
        }
        if (event.type() == AccessibleEvent.Type.STRUCTURE_CHANGED) {
            raiseStructure(event);
            return;
        }
        if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
            raiseValue(event);
            return;
        }
        int eventId = switch (event.type()) {
            case INVOKED -> UiaIds.INVOKE_INVOKED;
            case WINDOW_OPENED -> UiaIds.WINDOW_OPENED;
            case WINDOW_CLOSED -> UiaIds.WINDOW_CLOSED;
            case TEXT_CHANGED -> UiaIds.TEXT_CHANGED;
            // A caret move is a selection move of no length to UI Automation, and both are the
            // one event (§2.4; settled unmapped-and-window-level-events: handled together).
            case CARET_MOVED, TEXT_SELECTION_CHANGED -> UiaIds.TEXT_SELECTION_CHANGED;
            case INVALIDATED -> UiaIds.LAYOUT_INVALIDATED;
            // The rest are property changes, which UI Automation takes through a call of its own.
            default -> 0;
        };
        if (event.type() == AccessibleEvent.Type.BOUNDS_CHANGED) {
            // Not raised, per node or in bulk (node 0), and said so for either rather than dropped
            // silently at node 0. UI Automation watches an HWND's own bounds and a client re-reads
            // a fragment's when it needs them; NVDA 2024.4.2 subscribes to no BoundingRectangle
            // change (readings/nvda-2024.4.2-uia.md §3) and handles LayoutInvalidated only for
            // Windows search suggestions (§6), while every raise waits for the reader's handler
            // (§13.28) -- during a scroll or a drag, one per frame for nobody.
            UiaWindow.say("unmapped " + event.type() + " for node " + event.nodeId());
            return;
        }
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
                // Mapped to nothing on this platform: a state with no property of its own. (The
                // window's activation, BOUNDS_CHANGED and CARET_MOVED are handled above since
                // 2026-09-15.) Nothing is
                // raised, so nothing pays the change a client that asked is owed: only a raise
                // that reached the client may clear the flag (WINDOWS-NEW-6).
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
        if (event.type() == AccessibleEvent.Type.CARET_MOVED) {
            caretJustRaised = event.nodeId();
        }
        UiaWindow.say("raised " + event.type() + " for node " + event.nodeId() + " in "
                    + (System.nanoTime() - started) / 1_000 + " us on "
                    + Thread.currentThread().getName());
    }

    /**
     * Where the user is, raised: {@code AutomationFocusChanged} on the element of the tree's
     * {@linkplain AccessibleTree#effectiveFocus() effective focus} as the snapshot has it now
     * (decision 1; semantics 4; W3, WINDOWS-NEW-4, WINDOWS-NEW-2), then, when the focus moved, a
     * {@code HasKeyboardFocus} property change on the element it left and on the one it reached
     * (ADR 039 §2.4's {@code FOCUS_CHANGED} row).
     *
     * <p><b>On the effective focus, not on the event's node</b>, and read off the current tree:
     * NVDA 2024.4.2 queues the focus only if the sender answers {@code HasKeyboardFocus} true when
     * it reads it, live, after the event (readings/nvda-2024.4.2-uia.md §1), and that property is
     * answered from this same tree, so the raise and the answer always agree. A focused list, tree,
     * table or calendar is the widget; the effective focus is its cursor item, which is the only
     * thing NVDA 2024.4.2 speaks when a cursor moves (ElementSelected on an item is silent without
     * ControllerFor, §2 of that reading).
     *
     * <p><b>Minted if nobody holds it.</b> Every other event stays raised only for an element a
     * client asked for, because a client that never asked has nothing to be told about; a focus
     * subscriber hears focus anywhere in the window, and the row a cursor has just reached is
     * exactly the element no client has navigated to yet.
     *
     * <p><b>In the window that holds it.</b> A cursor resolved into a native popup's tree names a
     * node this tree does not hold, and its element is that window's: the raise goes through the
     * popup bridge's own element, under that bridge's guard.
     *
     * <p><b>Remembered, and not raised twice in a row (semantics 4).</b> One publish can name the
     * same element several times: a focus arriving on a table with a cursor is a
     * {@code FOCUS_CHANGED} and an {@code ACTIVE_DESCENDANT_CHANGED}, and a publish past the model's
     * budget is an {@code INVALIDATED} followed by both. Each raise waits for the reader's handler
     * (ADR 039 §13.28: 2.5&nbsp;ms median, up to 50 on this thread), and NVDA drops the repeat
     * anyway. So a focus event naming the element last announced in this process is skipped, while
     * a {@code reannouncement} -- after the collapse and the sweep, which a client may have
     * answered by re-reading -- raises whatever it names. The memory is the process's, so a raise
     * in another window makes the return here heard; a deactivation forgets it, so the focus change
     * into a window that is activated again is raised.
     *
     * @param cause          what prompted it, for the trace: the event type, or the collapse it
     *                       follows
     * @param reannouncement whether this follows a collapse or the model's INVALIDATED, and is
     *                       raised even on the element already announced
     */
    private void raiseFocus(String cause, boolean reannouncement) {
        AccessibleTree tree = tree();
        long target = tree.effectiveFocus();
        if (target == 0) {
            ANNOUNCED.set(null);
            UiaWindow.say("no focus to raise for " + cause);
            return;
        }
        UiaBridge owner = tree.indexOf(target) >= 0 ? this : openBridgeHolding(target);
        if (owner == null) {
            ANNOUNCED.set(null);
            UiaWindow.say("focus on node " + target + " is in no open window for " + cause);
            return;
        }
        Announced now = new Announced(owner, target);
        Announced last = ANNOUNCED.get();
        if (!reannouncement && now.equals(last)) {
            UiaWindow.say("focus on node " + target + " already announced, not raised again for "
                    + cause);
            return;
        }
        long started = System.nanoTime();
        boolean raised = owner.raiseOnElement(target, true, owner != this, element ->
                Uia.raiseAutomationEvent(element.pointer(), UiaIds.AUTOMATION_FOCUS_CHANGED));
        if (!raised) {
            UiaWindow.say("focus on node " + target + " has no element for " + cause);
            return;
        }
        ANNOUNCED.set(now);
        // A raise that reached the platform: the one change a client that asked was owed.
        owedAnEvent = false;
        UiaWindow.say("raised " + cause + " for node " + target
                + (owner != this ? " in another window" : "") + " in "
                + (System.nanoTime() - started) / 1_000 + " us on "
                + Thread.currentThread().getName());
        if (!now.equals(last)) {
            if (last != null) {
                raiseKeyboardFocus(last, false);
            }
            raiseKeyboardFocus(now, true);
        }
    }

    /**
     * The {@code HasKeyboardFocus} property change on one side of a focus move, raised only on an
     * element a client holds (the arriving one always is: the focus change just minted it) and
     * whose node is still in its tree.
     */
    private void raiseKeyboardFocus(Announced side, boolean has) {
        UiaBridge owner = side.owner();
        boolean raised = owner.raiseOnElement(side.nodeId(), false, owner != this, element ->
                raisePropertyChange(element, UiaIds.HAS_KEYBOARD_FOCUS, !has, has, null));
        if (raised) {
            UiaWindow.say("raised HasKeyboardFocus " + has + " for node " + side.nodeId());
        }
    }

    /**
     * Runs a raise on this bridge's element for a node.
     *
     * @param nodeId            a node of this bridge's tree
     * @param mint              whether to mint the element when no client holds it
     * @param fromAnotherWindow whether another window's drain thread is the caller, which takes
     *                          this bridge's guard so the whole-registry empty waits for it
     * @param raise             the platform call
     * @return whether it ran: {@code false} when the node has left, the element is not held and
     *         was not to be minted, or (from another window) this bridge is closing
     */
    private boolean raiseOnElement(long nodeId, boolean mint, boolean fromAnotherWindow,
                                   java.util.function.Consumer<UiaElement> raise) {
        if (!fromAnotherWindow) {
            return raiseOnElementUnguarded(nodeId, mint, raise);
        }
        synchronized (vendGuard) {
            return !closed && OPEN.contains(this) && raiseOnElementUnguarded(nodeId, mint, raise);
        }
    }

    private boolean raiseOnElementUnguarded(long nodeId, boolean mint,
                                            java.util.function.Consumer<UiaElement> raise) {
        if (tree().indexOf(nodeId) < 0) {
            return false;
        }
        UiaElement element = mint ? elementOf(nodeId) : elements.peek(nodeId);
        if (element == null) {
            return false;
        }
        raise.accept(element);
        owedAnEvent = false;
        return true;
    }

    /**
     * What one container's {@code SELECTION_CHANGED} raises, as {@code {eventId, nodeId}} pairs in
     * order (decision 9; semantics 1): {@code Selection_Invalidated} on the container when more
     * than {@link UiaIds#INVALIDATE_LIMIT} members entered and left it; otherwise, in a container
     * that selects one, {@code ElementSelected} on the member that entered, and
     * {@code ElementRemovedFromSelection} on each that left when none entered; in a container that
     * selects many, {@code ElementAddedToSelection} on each member that entered and
     * {@code ElementRemovedFromSelection} on each that left. Replaces the single
     * {@code ElementSelected} this bridge raised on the container itself, which is an item event
     * raised on something that is not an item (WINDOWS-NEW-5).
     *
     * <p>A decision only: which of them reaches a client is {@link #raiseSelection}'s held-element
     * gate. NVDA 2024.4.2 speaks none of them for a generic item (readings/nvda-2024.4.2-uia.md §2:
     * ElementSelected needs the focus's ControllerFor, the other two map to a state change on a
     * non-focus object); what it speaks as the cursor moves is the focus change of item 3. The
     * events are raised for the clients that do subscribe to selection.
     *
     * @param event a {@code SELECTION_CHANGED}
     * @return the raises, container first when it is bulk
     */
    static List<long[]> selectionRaises(AccessibleEvent event) {
        List<Long> added = event.addedMembers();
        List<Long> removed = event.removedMembers();
        List<long[]> raises = new ArrayList<>();
        if (added.size() + removed.size() > UiaIds.INVALIDATE_LIMIT) {
            raises.add(new long[] {UiaIds.SELECTION_INVALIDATED, event.nodeId()});
            return raises;
        }
        if (!event.multiSelectable() && added.size() == 1) {
            raises.add(new long[] {UiaIds.SELECTION_ITEM_ELEMENT_SELECTED, added.get(0)});
            return raises;
        }
        for (long member : added) {
            raises.add(new long[] {UiaIds.SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION, member});
        }
        for (long member : removed) {
            raises.add(new long[] {UiaIds.SELECTION_ITEM_ELEMENT_REMOVED_FROM_SELECTION, member});
        }
        return raises;
    }

    /**
     * Raises {@link #selectionRaises} for the elements a client holds, and nothing for the rest:
     * the member that just entered a selection is not minted for it, because a client that never
     * asked for it reads its selected state when it does (§13.28's cost argument), and a member
     * that left the tree has no element left to raise on.
     */
    private void raiseSelection(AccessibleEvent event) {
        boolean anything = false;
        for (long[] raise : selectionRaises(event)) {
            UiaElement element = elements.peek(raise[1]);
            if (element == null) {
                continue;
            }
            long started = System.nanoTime();
            Uia.raiseAutomationEvent(element.pointer(), (int) raise[0]);
            anything = true;
            UiaWindow.say("raised SELECTION_CHANGED as event " + raise[0] + " for node " + raise[1]
                    + " of container " + event.nodeId() + " in "
                    + (System.nanoTime() - started) / 1_000 + " us on "
                    + Thread.currentThread().getName());
        }
        if (anything) {
            owedAnEvent = false;
        } else {
            UiaWindow.say("SELECTION_CHANGED of container " + event.nodeId()
                    + " reached no held element");
        }
    }

    /**
     * {@code {kind, processing}} for an announcement (WINDOWS-NEW-1): kind {@code Other}, because
     * the model says nothing of what an announcement is about; processing by politeness.
     * {@code ASSERTIVE} interrupts, which is {@code ImportantMostRecent}: NVDA 2024.4.2 cancels its
     * speech first for {@code MostRecent} and {@code ImportantMostRecent} and queues the others
     * (readings/nvda-2024.4.2-uia.md §4), and an interruption is important. {@code POLITE} waits,
     * which is {@code All}: queued, none dropped for a later one. The enumerators were read on the
     * guest 2026-09-13 ({@link UiaIds#NOTIFICATION_KIND_OTHER}).
     *
     * @param politeness the announcement's
     * @return the kind and the processing, in that order
     */
    static int[] notificationFor(Accessible.Politeness politeness) {
        return new int[] {UiaIds.NOTIFICATION_KIND_OTHER, switch (politeness) {
            case POLITE -> UiaIds.NOTIFICATION_PROCESSING_ALL;
            case ASSERTIVE -> UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT;
        }};
    }

    /**
     * An announcement, raised with {@code UiaRaiseNotificationEvent} on the root's element, minted if
     * no client holds it (WINDOWS-NEW-1). It was mapped to the notification event id and then
     * dropped, because the model names no node for it (node {@code 0}) and nothing held one; and
     * {@code UiaRaiseAutomationEvent}, which it would have gone through, carries no text.
     *
     * <p>On the root because an announcement belongs to the window, and NVDA 2024.4.2 takes a
     * notification from any element that resolves to a window handle, speaking it while its focus
     * is in this process (readings/nvda-2024.4.2-uia.md §4); the root is the element whose host
     * provider is the HWND's. The activity id is empty, as WinForms' own
     * {@code AccessibleObject.RaiseAutomationNotification} passes it (read as IL 2026-09-15,
     * readings/windows-dump-uia-provider-conventions.txt §4).
     */
    private void raiseAnnouncement(AccessibleEvent event) {
        AccessibleTree tree = tree();
        if (tree.nodeCount() == 0) {
            UiaWindow.say("ANNOUNCEMENT with no tree to raise it on");
            return;
        }
        if (!Uia.canRaiseNotifications() && Uia.isAvailable()) {
            UiaWindow.say("ANNOUNCEMENT not raised: this UI Automation has no notification event");
            return;
        }
        UiaElement root = elementOf(tree.root().id());
        if (root == null) {
            return;
        }
        String text = event.newValue() instanceof String said ? said : "";
        int[] how = notificationFor(event.politeness());
        long display = UiaStrings.system().allocate(text);
        long activity = UiaStrings.system().allocate("");
        long started = System.nanoTime();
        int hresult;
        try {
            hresult = Uia.raiseNotificationEvent(root.pointer(), how[0], how[1], display, activity);
        } finally {
            UiaStrings.free(display);
            UiaStrings.free(activity);
        }
        owedAnEvent = false;
        UiaWindow.say("raised ANNOUNCEMENT kind " + how[0] + " processing " + how[1]
                + " on the root " + tree.root().id() + " -> 0x" + Integer.toHexString(hresult)
                + " in " + (System.nanoTime() - started) / 1_000 + " us on "
                + Thread.currentThread().getName());
    }

    /**
     * What one parent's {@code STRUCTURE_CHANGED} raises, as {@code {type, raisedOn, runtimeIdOf}}
     * node triples in order (WINDOWS-NEW-3), the shape the platform's own
     * {@code AutomationPeer.UpdateChildrenInternal} raises, read as IL on the guest 2026-09-15
     * (readings/windows-dump-uia-provider-conventions.txt §3): when more children entered and left
     * than the limit, one change on the parent with the parent's runtime id, {@code
     * ChildrenBulkRemoved} when none entered, {@code ChildrenBulkAdded} when none left,
     * {@code ChildrenInvalidated} when both; otherwise {@code ChildRemoved} on the parent with each
     * removed child's runtime id, then {@code ChildAdded} on each added child with its own. The limit
     * is the platform's: {@link UiaIds#ITEMS_INVALIDATE_LIMIT} for a container of items (a list,
     * tree, table or grid: a node with a selection or a table facet), which is what
     * {@code ItemsControlAutomationPeer} passes, else {@link UiaIds#INVALIDATE_LIMIT}. A publish
     * that moved surviving children adds one {@code ChildrenReordered} on the parent with the
     * parent's own runtime id. {@code UpdateChildrenInternal} has no case for a move; the shape is
     * the one the platform's client-side proxies raise it in, read in the same listing (§3):
     * {@code EventManager.HandleStructureChangedEventWindow} raises {@code ChildrenReordered} (5)
     * on its element with that element's own {@code MakeRuntimeId()}, for WinEvent 32772, and
     * {@code MSAAEventDispatcher.MaybeFireStructureChangeEvent}'s default branch raises it on the
     * provider made for the event's object with that provider's own runtime id. That the element
     * such a proxy raises it on is the container whose children moved rests on 32772 being the
     * Win32 reorder event, a header name not read on the guest.
     *
     * <p>The per-child raises up to the limit depart from the Windows brief's wording
     * ("ChildrenBulkAdded/Removed for coalesced"): the model coalesces per parent per publish, and
     * the platform's own peer raises a coalesced change of few children as single
     * {@code ChildAdded}/{@code ChildRemoved} events, so this follows the peer.
     *
     * @param event          a {@code STRUCTURE_CHANGED}
     * @param itemsContainer whether its parent is a container of items
     * @return the raises
     */
    static List<long[]> structureRaises(AccessibleEvent event, boolean itemsContainer) {
        List<AccessibleEvent.Child> added = event.addedChildren();
        List<AccessibleEvent.Child> removed = event.removedChildren();
        long parent = event.nodeId();
        List<long[]> raises = new ArrayList<>();
        int limit = itemsContainer ? UiaIds.ITEMS_INVALIDATE_LIMIT : UiaIds.INVALIDATE_LIMIT;
        if (added.size() + removed.size() > limit) {
            int type = added.isEmpty() ? UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED
                    : removed.isEmpty() ? UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_ADDED
                    : UiaIds.STRUCTURE_CHANGE_CHILDREN_INVALIDATED;
            raises.add(new long[] {type, parent, parent});
        } else {
            for (AccessibleEvent.Child child : removed) {
                raises.add(new long[] {UiaIds.STRUCTURE_CHANGE_CHILD_REMOVED, parent, child.id()});
            }
            for (AccessibleEvent.Child child : added) {
                raises.add(new long[] {UiaIds.STRUCTURE_CHANGE_CHILD_ADDED, child.id(), child.id()});
            }
        }
        if (!event.reorderedChildren().isEmpty()) {
            raises.add(new long[] {UiaIds.STRUCTURE_CHANGE_CHILDREN_REORDERED, parent, parent});
        }
        return raises;
    }

    /**
     * Raises {@link #structureRaises} through {@code UiaRaiseStructureChangedEvent}, when a client
     * holds the parent's element: a client that never asked for the parent holds nothing its
     * children could have changed under, and reads them when it does ask (§13.28's cost argument).
     * With the parent held, an added child's element is minted for its {@code ChildAdded}, as the
     * platform's peer raises it on the child's own provider; a removed child's runtime id is still
     * its identifier's. It was {@code UiaRaiseAutomationEvent} with the structure-changed id before,
     * which carries neither a type nor a runtime id, and dropped whenever the parent was unheld.
     * NVDA 2024.4.2 subscribes to no structure change (readings/nvda-2024.4.2-uia.md §5); the event
     * is for the clients that do.
     */
    private void raiseStructure(AccessibleEvent event) {
        UiaElement parent = elements.peek(event.nodeId());
        if (parent == null) {
            UiaWindow.say("STRUCTURE_CHANGED of node " + event.nodeId() + " reached no held element");
            return;
        }
        AccessibleTree tree = tree();
        AccessibleNode parentNode = tree.find(event.nodeId());
        boolean items = parentNode != null
                && (parentNode.selection() != null || parentNode.table() != null);
        long runtimeId = MemoryUtil.nmemAllocChecked(3L * Integer.BYTES);
        try {
            for (long[] raise : structureRaises(event, items)) {
                UiaElement on = raise[1] == event.nodeId() ? parent : elementOf(raise[1]);
                if (on == null) {
                    continue;
                }
                int[] id = UiaFragment.runtimeId(raise[2]);
                for (int i = 0; i < id.length; i++) {
                    MemoryUtil.memPutInt(runtimeId + (long) i * Integer.BYTES, id[i]);
                }
                long started = System.nanoTime();
                int hresult = Uia.raiseStructureChangedEvent(on.pointer(), (int) raise[0],
                        runtimeId, id.length);
                // Paid before the trace says so, as every other raise here pays it: a reader of
                // the trace (a test on another thread) must not find the line with the debt open.
                owedAnEvent = false;
                UiaWindow.say("raised STRUCTURE_CHANGED as type " + raise[0] + " on node " + raise[1]
                        + " with the runtime id of node " + raise[2] + " -> 0x"
                        + Integer.toHexString(hresult) + " in "
                        + (System.nanoTime() - started) / 1_000 + " us on "
                        + Thread.currentThread().getName());
            }
        } finally {
            MemoryUtil.nmemFree(runtimeId);
        }
    }

    /**
     * This bridge's fragment pointer for a node, handed to another window's {@code GetFocus} on
     * that window's RPC thread (decision 5), under this bridge's guard: the element is minted,
     * found and referenced while the whole-registry empty cannot run, and not at all once this
     * bridge has left the open set, which its detach does before it empties.
     *
     * @param nodeId a node of this bridge's tree
     * @return the pointer, referenced for the caller, or {@code 0} once this bridge is closing or
     *         the node has left
     */
    private long handOverFromAnotherWindow(long nodeId) {
        synchronized (vendGuard) {
            if (closed || !OPEN.contains(this)) {
                return 0;
            }
            return handOver(nodeId, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        }
    }

    /** @return the guard another window's entries take; for the test that pins that they do */
    Object vendGuardForTests() {
        return vendGuard;
    }

    /**
     * @param nodeId a node identifier
     * @return another open bridge whose published tree holds it, or {@code null}
     */
    private UiaBridge openBridgeHolding(long nodeId) {
        for (UiaBridge other : OPEN) {
            if (other != this && other.tree().indexOf(nodeId) >= 0) {
                return other;
            }
        }
        return null;
    }

    /**
     * Whether a node of this tree is where the user is: this tree's effective focus, or the cursor
     * another open window's focused node resolved into this tree (decision 5). What
     * {@code HasKeyboardFocus} answers, so that it agrees with {@link #raiseFocus}.
     *
     * @param nodeId a node of this bridge's tree
     * @return whether it has the keyboard, as UI Automation means it
     */
    private boolean hasKeyboardFocus(long nodeId) {
        AccessibleTree tree = tree();
        if (nodeId == 0 || tree.indexOf(nodeId) < 0) {
            return false;
        }
        if (tree.effectiveFocus() == nodeId) {
            return true;
        }
        for (UiaBridge other : OPEN) {
            if (other != this && other.cursorInto(tree) == nodeId) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the first node of this tree another open window's effective focus names, or
     *         {@code 0}; what this window's {@code GetFocus} answers when nothing of its own is
     *         focused (decision 5)
     */
    private long cursorFromAnotherWindow() {
        AccessibleTree tree = tree();
        for (UiaBridge other : OPEN) {
            long cursor = other == this ? 0 : other.cursorInto(tree);
            if (cursor != 0) {
                return cursor;
            }
        }
        return 0;
    }

    /**
     * @param theirs another window's published tree
     * @return this bridge's effective focus when it lives in that tree and not in this one, else
     *         {@code 0}
     */
    private long cursorInto(AccessibleTree theirs) {
        AccessibleTree mine = tree();
        long cursor = mine.effectiveFocus();
        return cursor != 0 && mine.indexOf(cursor) < 0 && theirs.indexOf(cursor) >= 0 ? cursor : 0;
    }

    /** @return the node the last focus change in this process was raised on, or 0; for tests */
    static long announcedFocusForTests() {
        Announced last = ANNOUNCED.get();
        return last == null ? 0 : last.nodeId();
    }

    /**
     * A value that moved, raised as the property of each pattern the node vends that it moved on
     * (the settled value-text-event item; CRIT-4's Windows half), only for an element a client holds.
     *
     * <p>Until 2026-09-15 one property was raised, {@code RangeValue.Value} whenever the node had a
     * number, so a node vending both patterns -- a spinner's "07:30", a date segment's "empty" --
     * never told a client its {@code Value} string moved, and a change of the text alone (a segment
     * filled with its minimum) was raised as a number that had not moved. NVDA 2024.4.2 reads a
     * control's value from {@code Value} when it vends both and maps both properties to its
     * {@code valueChange} (readings/nvda-2024.4.2-uia.md §3).
     *
     * <p>The old string goes as an empty variant: the model's event carries the two numbers and
     * not the text, and a COM client's {@code HandlePropertyChangedEvent} receives the new value
     * alone (sender, propertyId, newValue: UIAutomationCore.dll's type library, read 2026-09-13,
     * readings/windows-dump-uia-typelib-all-members.txt).
     *
     * @param event the {@code VALUE_CHANGED}
     */
    private void raiseValue(AccessibleEvent event) {
        UiaElement element = elements.peek(event.nodeId());
        if (element == null) {
            // As every property change: nothing asked for this node, so nothing is told.
            return;
        }
        AccessibleTree tree = tree();
        int index = tree.indexOf(event.nodeId());
        AccessibleNode node = index < 0 ? null : tree.node(index);
        int[] properties = valueRaises(event, tree, node);
        if (properties.length == 0) {
            // Nothing a vended pattern carries moved (a bare number's emptiness), or the node has
            // gone: nothing raised, so nothing pays the event an ask is owed (WINDOWS-NEW-6).
            UiaWindow.say("unmapped " + event.type() + " for node " + event.nodeId()
                    + ": no vended pattern's property moved");
            return;
        }
        long started = System.nanoTime();
        StringBuilder raised = new StringBuilder();
        for (int propertyId : properties) {
            if (propertyId == UiaIds.VALUE_VALUE) {
                raisePropertyChange(element, propertyId, null, valueString(node), node);
            } else {
                raisePropertyChange(element, propertyId, event.oldValue(), event.newValue(), node);
            }
            raised.append(raised.length() == 0 ? "" : ", ").append(propertyId);
        }
        owedAnEvent = false;
        UiaWindow.say("raised " + event.type() + " for node " + event.nodeId() + " as [" + raised
                + "] in " + (System.nanoTime() - started) / 1_000 + " us on "
                + Thread.currentThread().getName());
    }

    /**
     * The properties a {@code VALUE_CHANGED} raises, in order: {@code RangeValue.Value} where the
     * node vends RangeValue and the event's number moved, then {@code Value.Value} wherever the node
     * vends Value.
     *
     * <p>A number that did not move means the text or the emptiness did (the model raises the event
     * for nothing else, ADR 039 §1.10's 2026-09-14 amendment), so RangeValue, which carries only the
     * number, is not raised. The Value string is raised even when the number moved and the text
     * happened not to, because the event carries no text to compare: a Value vended from a value
     * facet is the number's spoken form, which moves with it.
     *
     * @param event the {@code VALUE_CHANGED}
     * @param tree  the tree the node is read from
     * @param node  the node, or {@code null} when it has left the tree
     * @return the property ids, empty when nothing is raised
     */
    static int[] valueRaises(AccessibleEvent event, AccessibleTree tree, AccessibleNode node) {
        if (node == null) {
            return new int[0];
        }
        boolean numberMoved = !(event.oldValue() instanceof Number before
                && event.newValue() instanceof Number after
                && Double.compare(before.doubleValue(), after.doubleValue()) == 0);
        boolean range = numberMoved && UiaPatterns.supports(tree, node, UiaIds.RANGE_VALUE_PATTERN);
        boolean string = UiaPatterns.supports(tree, node, UiaIds.VALUE_PATTERN);
        int[] properties = new int[(range ? 1 : 0) + (string ? 1 : 0)];
        int at = 0;
        if (range) {
            properties[at++] = UiaIds.RANGE_VALUE_VALUE;
        }
        if (string) {
            properties[at] = UiaIds.VALUE_VALUE;
        }
        return properties;
    }

    /** @return what {@code Value.get_Value} answers for the node now */
    private static String valueString(AccessibleNode node) {
        String value = node.text() != null ? node.text().text()
                : node.value() != null ? node.value().text() : null;
        return value == null ? "" : value;
    }

    /**
     * A property that moved, which UI Automation is told about with both values.
     *
     * <p><b>Which property depends on the node and not only on the event</b>: a state that moved is
     * whichever property carries that state — a check mark is the toggle pattern's, an enabled flag
     * is the element's own — so a state this bridge has no property for is not raised rather than
     * raised as something else. (A value that moved is {@link #raiseValue}'s, since 2026-09-15.)
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
        raisePropertyChange(element, propertyId, event.oldValue(), event.newValue(), node);
    }

    /** The same, with the two values given. */
    private void raisePropertyChange(UiaElement element, int propertyId, Object oldValue,
                                     Object newValue, AccessibleNode node) {
        long before = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        long after = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        try {
            java.nio.ByteBuffer oldOne = MemoryUtil.memByteBuffer(before, UiaVariant.SIZE);
            java.nio.ByteBuffer newOne = MemoryUtil.memByteBuffer(after, UiaVariant.SIZE);
            write(oldOne, propertyId, changedValue(propertyId, oldValue, node));
            write(newOne, propertyId, changedValue(propertyId, newValue, node));
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
            // VALUE_CHANGED raises up to two properties and never comes here (raiseValue).
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
            // A rectangle that moved is not raised (raise() says why, before it gets here).
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
        java.util.Set<UiaObject> distinct =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // And any other window's drain thread raising on one of these elements, or RPC thread
        // handing one over, finishes first (raiseOnElement, handOverFromAnotherWindow).
        synchronized (vendGuard) {
            // Then, while every closure the platform may call back through is still there.
            disconnectRootProvider();
            distinct.addAll(objects.values());
            objects.clear();
            elements.empty();
            distinct.forEach(UiaObject::free);
            ANNOUNCED.updateAndGet(last -> last != null && last.owner() == this ? null : last);
        }
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
        public boolean hasKeyboardFocus(long nodeId) {
            return UiaBridge.this.hasKeyboardFocus(nodeId);
        }

        /**
         * <p>The popup window's own fragment pointer, handed over by the bridge that holds it,
         * under that bridge's guard, and referenced there: the element UI Automation raised the
         * focus change on.
         */
        @Override
        public long elementInAnotherWindowFor(long nodeId) {
            UiaBridge holder = openBridgeHolding(nodeId);
            return holder == null ? 0 : holder.handOverFromAnotherWindow(nodeId);
        }

        @Override
        public long cursorFromAnotherWindow() {
            return UiaBridge.this.cursorFromAnotherWindow();
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

        /**
         * The node as the last snapshot asked about has it, so that the thirteen candidates a mint
         * asks, and every later query against the same snapshot, find the node once rather than
         * once per candidate: {@link AccessibleTree#find} is linear in the tree. Replaced whole
         * when the snapshot is a different one; any RPC thread may write it, and a lost write
         * costs one more search.
         */
        private volatile Resolved resolved;

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
            Resolved last = resolved;
            if (last == null || last.tree() != tree) {
                last = new Resolved(tree, tree.find(nodeId));
                resolved = last;
            }
            return last.node() != null && UiaPatterns.supports(tree, last.node(), patternOf(iface));
        }

        @Override
        public Map<String, ? extends org.lwjgl.system.CallbackI> slotsFor(
                UiaInterfaces.Vtable iface) {
            return UiaPatternProviders.slotsFor(patternOf(iface), nodeId, context);
        }
    }

    /** One node found in one snapshot, or not found there ({@code node} null). */
    private record Resolved(AccessibleTree tree, AccessibleNode node) {
    }

    /** The patterns a node may vend, asked in a fixed order so an element is built the same way. */
    private static final int[] PATTERNS = {
            UiaIds.INVOKE_PATTERN, UiaIds.TOGGLE_PATTERN, UiaIds.VALUE_PATTERN,
            UiaIds.RANGE_VALUE_PATTERN, UiaIds.EXPAND_COLLAPSE_PATTERN,
            UiaIds.SELECTION_PATTERN, UiaIds.SELECTION_ITEM_PATTERN, UiaIds.SCROLL_PATTERN,
            UiaIds.SCROLL_ITEM_PATTERN,
            UiaIds.GRID_PATTERN, UiaIds.TABLE_PATTERN, UiaIds.GRID_ITEM_PATTERN,
            UiaIds.TABLE_ITEM_PATTERN,
    };

    /** The interface each of {@link #PATTERNS} is served through, in the same order. */
    private static final List<UiaInterfaces.Vtable> PATTERN_INTERFACES =
            java.util.Arrays.stream(PATTERNS).mapToObj(UiaPatternProviders::interfaceFor).toList();

    /** @return the pattern an interface of {@link #PATTERN_INTERFACES} serves */
    private static int patternOf(UiaInterfaces.Vtable iface) {
        // By identity: the tables are constants, and a record's equals compares the slot lists.
        for (int i = 0; i < PATTERNS.length; i++) {
            if (PATTERN_INTERFACES.get(i) == iface) {
                return PATTERNS[i];
            }
        }
        throw new IllegalArgumentException(iface.name() + " serves no pattern of this bridge");
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
