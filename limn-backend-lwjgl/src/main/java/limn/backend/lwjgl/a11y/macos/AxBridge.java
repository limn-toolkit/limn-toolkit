package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.lwjgl.ObjC;
import limn.backend.lwjgl.a11y.PlatformBridge;
import limn.graphics.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The NSAccessibility bridge: what a macOS window gives a Limn scene so that VoiceOver can read it.
 *
 * <p><b>The top of the tree is pushed and everything below it is pulled.</b> On the scene's first
 * frame the root's children are handed to the content view with {@code setAccessibilityChildren:},
 * which is enough to place them under the window a reader walks; every level below that is answered
 * by {@link AxElementClass}'s own {@code accessibilityChildren}, and the first such call is the
 * listening gate. The pushed array is a snapshot AppKit holds and nothing re-derives, so it is
 * pushed again whenever the root's children change — the phase 7 probe run measured that without
 * that re-push a modal opening into the scene is invisible to an attached client, which is the one
 * change a reader's user most needs to be told about (§13.21).
 *
 * <p><b>The window root is not vended at all.</b> AppKit already offers the window, with its title,
 * its close and zoom buttons and an {@code AXRaise}; hanging ours off the content view would put a
 * window inside a window and VoiceOver would announce it twice (§2.2).
 *
 * <p><b>The rule that crashes rather than degrades.</b> Every accessibility callback here arrives on
 * the user-interface thread inside the event pump (Finding 4), which is what lets the registry be a
 * plain map — and is exactly why reentrancy is the trap. When the platform asks something that
 * cannot wait for a frame, the scene rebuilds and publishes from inside that callback with AppKit
 * standing on objects this bridge vended. Such a publish <b>releases nothing, re-pushes nothing and
 * drains nothing</b> (§3.2): each would act on what the caller is holding. What it defers is owed to
 * the next ordinary frame, which the scene has already asked for.
 */
public final class AxBridge extends PlatformBridge implements AxElementClass.Source {

    /**
     * Opens a bridge for a window, or answers {@link AccessibilityBridge#NONE} where AppKit is not
     * the windowing system.
     *
     * @param nsWindow the window's own handle, from {@code NativeWindow#nativeHandle()}
     * @return a bridge, or {@code NONE} — never null, so a caller never branches on this
     */
    public static AccessibilityBridge openIfEnabled(long nsWindow) {
        if (nsWindow == 0) return NONE;
        AxObjC objc = AxObjC.openOrNull();
        if (objc == null) return NONE;
        long contentView = ObjC.msg(nsWindow, "contentView");
        if (contentView == 0) return NONE;
        return new AxBridge(objc, contentView);
    }

    private final AxObjC objc;
    private final long contentView;
    private final AxElementClass elementClass;
    private final AxElements elements;
    /** element pointer to node id: the recovery every implementation starts with. */
    private final Map<Long, Long> nodeIdByElement = new HashMap<>();

    private boolean listening;
    private boolean obligationsDeferred;
    private long[] pushed = new long[0];
    /** How many times the root's children have been handed to the content view. For tests. */
    private int pushes;
    /** The last parent-space box computed for each held node. For tests. */
    private final Map<Long, double[]> lastFrames = new HashMap<>();
    private final AxEvents events = new AxEvents();
    /** Every notification posted since this bridge opened. For tests and for the probe's log. */
    private final List<String> posted = new ArrayList<>();
    /** What each ask for the focused element was answered with. For the live run's log. */
    private final List<String> focusedAnswers = new ArrayList<>();
    /** Every event the scene handed this bridge. For the live run's log. */
    private final List<String> emitted = new ArrayList<>();
    /** What a detach did to the platform's objects, in order. For the test that guards the order. */
    private final List<String> teardown = new ArrayList<>();

    private AxBridge(AxObjC objc, long contentView) {
        this.objc = objc;
        this.contentView = contentView;
        // One class per bridge, named per instance: objc_allocateClassPair refuses a name already
        // registered, and a process can open several windows.
        this.elementClass = objc == null ? null : new AxElementClass(objc, this,
                "LimnAccessibleElement_" + Long.toHexString(contentView));
        if (elementClass != null) {
            elementClass.installFocusedElementOnView(contentView,
                    "LimnAXContentView_" + Long.toHexString(contentView));
        }
        this.elements = new AxElements(Thread.currentThread(), new AxElements.Factory() {
            private long synthetic = 0x1000;

            @Override public long newElement(long nodeId) {
                // Off AppKit there is no object to make, and a distinct number stands in for one:
                // everything above the platform calls -- the registry, the links, the push
                // comparison -- is bookkeeping over pointers it never dereferences.
                long element = elementClass == null ? (synthetic += 0x10) : elementClass.newInstance();
                nodeIdByElement.put(element, nodeId);
                // Its box, now rather than on the next publish. An element minted by a client's
                // pull would otherwise be a zero-size rectangle for as long as it took the next
                // frame to arrive, and the client asking is precisely the moment it is read.
                applyFrame(nodeId, element);
                return element;
            }

            @Override public void release(long element) {
                nodeIdByElement.remove(element);
                // Demoted before it is released, because AppKit hands a vended element to a client
                // by reference and our release is not the client's: whatever still holds it must
                // land on NSAccessibilityElement's own answers, not on a closure of ours that the
                // detach is about to free.
                if (elementClass != null) elementClass.demote(element);
                teardown.add("element demoted");
                if (objc != null) ObjC.msg(element, "release");
            }
        });
    }

    /**
     * The same bridge without the platform, so that everything above the Objective-C calls can be
     * exercised on a machine that has no AppKit — which is most of them, and is where this module's
     * tests run.
     *
     * <p>Package-private, and not a way to install a bridge anywhere: what it serves is elements
     * that are numbers, under a content view that does not exist.
     *
     * @return a bridge whose platform calls are all skipped
     */
    static AxBridge withoutThePlatform() {
        return new AxBridge(null, 0);
    }

    @Override
    public boolean isListening() {
        // §6's honest gate here is "someone has asked", because there is no
        // UiaClientsAreListening on this platform and no registry to consult. It cannot open before
        // the platform has been handed elements to ask about, which is what needsPrimingPublish is.
        return listening;
    }

    @Override
    public boolean needsPrimingPublish() {
        return true;
    }

    @Override
    public void publish(AccessibleTree published, boolean reentrant) {
        super.publish(published, reentrant);
        if (reentrant) {
            // The store is the whole of it. Releasing, re-pushing or draining here would act on the
            // objects AppKit is standing on, and on this platform that is a crash rather than a
            // stale reading.
            obligationsDeferred = true;
            return;
        }
        obligationsDeferred = false;
        // The drain before the push and the frames, so that what a client is told to re-read is
        // already there when it asks.
        drain();
        // The push before the frames, because the push is what mints the root's children and a
        // node with no element has no box to set. The first run of this had them the other way
        // round and every element arrived as a zero-size rectangle at the origin -- which a walk
        // reads perfectly and a hit test cannot resolve at all, so it is exactly the defect §13.21
        // says only a live client finds.
        repushRootIfChanged();
        refreshFrames();
    }

    @Override
    public void emit(AccessibleEvent event) {
        // Enqueue, never post: every post is a cross-process call, and a difference between two
        // frames can be hundreds of nodes wide.
        emitted.add(event.type() + "#" + event.nodeId());
        events.add(event);
    }

    @Override
    protected void invalidateEverythingVended() {
        // Every element at once, and the record of what was pushed with them: an earlier draft
        // released nothing on a rebind at all (§1.10, §5.3), and the pushed array would otherwise
        // name objects that no longer exist.
        elements.empty();
        pushed = new long[0];
        obligationsDeferred = false;
    }

    @Override
    protected void releasePlatformHalf() {
        // The content view is not ours and outlives this bridge, so what was pushed onto it is
        // taken back rather than left pointing at released objects -- its children, and its class.
        // The order is the point: every element has been demoted by now (invalidateEverythingVended
        // runs first), the view goes back to GLFW's class here, and only then may the closures go.
        // VoiceOver asks the view for its focused element while glfwDestroyWindow pumps the run
        // loop, after this bridge has detached; with the closures freed first that ask was a
        // SIGSEGV inside liblwjgl, seen at the end of the first scroll run on the guest.
        if (objc != null) ObjC.msgVoid(contentView, "setAccessibilityChildren:", 0);
        teardown.add("children taken back");
        listening = false;
        if (elementClass != null) elementClass.restoreView();
        teardown.add("view restored");
        if (elementClass != null) elementClass.free();
        teardown.add("closures freed");
    }

    // ---- what an implementation asks ------------------------------------------------------------

    @Override
    public void entered() {
        listening = true;
    }

    @Override
    public boolean perform(long nodeId, Accessible.Action action) {
        Host current = host();
        // Between a detach and an attach there is nobody to ask, and refusing is the only honest
        // answer: the scene that owned the widget is gone.
        return current != null
                && current.perform(nodeId, action, Accessible.Argument.NONE);
    }

    @Override
    public long focusedElement() {
        long focused = tree().focused();
        if (focused == 0 || tree().indexOf(focused) < 0) {
            focusedAnswers.add("none");
            return 0;
        }
        long element = elements.elementFor(focused);
        focusedAnswers.add(focused + "=" + tree().find(focused).role()
                + "@" + Long.toHexString(element));
        return element;
    }

    @Override
    public AccessibleNode nodeFor(long element) {
        Long nodeId = nodeIdByElement.get(element);
        if (nodeId == null) return null;
        return tree().find(nodeId);
    }

    @Override
    public long[] childElementsOf(AccessibleNode node) {
        int index = tree().indexOf(node.id());
        if (index < 0) return new long[0];
        List<Long> children = new ArrayList<>();
        for (int child = tree().node(index).firstChild(); child != AccessibleNode.NONE;
                child = tree().node(child).nextSibling()) {
            children.add(elements.elementFor(tree().node(child).id()));
        }
        long[] answer = new long[children.size()];
        for (int i = 0; i < answer.length; i++) answer[i] = children.get(i);
        return answer;
    }

    @Override
    public long parentElementOf(AccessibleNode node) {
        int index = tree().indexOf(node.id());
        if (index < 0) return contentView;
        int parent = tree().node(index).parent();
        // A child of the elided window root answers with the content view, because that is the
        // object AppKit was handed and the one it expects to get back.
        if (parent == AccessibleNode.NONE || parent == 0) return contentView;
        return elements.elementFor(tree().node(parent).id());
    }

    // ---- the publish path ------------------------------------------------------------------------

    /**
     * Re-states every held element's box.
     *
     * <p>The frame is pushed rather than pulled, which is the one attribute that is. AppKit stores
     * what {@code setAccessibilityFrameInParentSpace:} is given, so a box that moved has to be
     * given again; the alternative is a libffi closure returning a struct by value, which buys
     * nothing here because the set of elements to touch is only what a client has already asked
     * about.
     *
     * <p>Parent space is the <b>parent element's</b> space, with y measured up from its bottom —
     * measured through three levels in the phase 7 probe run, and not the content view's space,
     * which §2.2 said until that run.
     */
    private void refreshFrames() {
        for (int index = 1; index < tree().nodeCount(); index++) {
            AccessibleNode node = tree().node(index);
            if (!elements.holds(node.id())) continue;
            applyFrame(node.id(), elements.elementFor(node.id()));
        }
    }

    /**
     * Gives one element its box in its parent's space.
     *
     * <p>Parent space is the <b>parent element's</b> space, with y measured up from that parent's
     * bottom edge — measured through three levels in the phase 7 probe run, and not the content
     * view's space, which §2.2 said until that run. A child of the elided window root is measured
     * against the whole scene, because the content view is what it hangs from.
     */
    private void applyFrame(long nodeId, long element) {
        int index = tree().indexOf(nodeId);
        if (index <= 0) return;
        AccessibleNode node = tree().node(index);
        int parent = node.parent();
        Rect parentBounds = parent == AccessibleNode.NONE
                ? new Rect(0, 0, tree().sceneWidth(), tree().sceneHeight())
                : tree().node(parent).bounds();
        double[] parentSpace = AxFrames.inParentSpace(node.bounds(), parentBounds);
        if (objc != null) {
            objc.msgRect(element, "setAccessibilityFrameInParentSpace:", parentSpace);
        }
        lastFrames.put(nodeId, parentSpace);
    }

    /**
     * Posts one frame's worth of events, and reconciles the registry when the queue collapsed.
     *
     * <p>Never from a reentrant publish: a post from inside an AX callback re-enters the platform
     * while it is standing on our objects (§3.2). The queue keeps them for the ordinary frame the
     * scene has already asked for.
     *
     * <p>A collapse is why the sweep is here rather than only on {@code NODE_DESTROYED}: the burst
     * that overflowed the queue is exactly the one whose per-node destructions were dropped, so
     * after one there is no list of what died — only the tree, and whatever the registry still
     * holds (§13.9).
     */
    private void drain() {
        // Timed, because §13.19's macOS half is "what does one frame's drain cost with a reader
        // attached", and the drain is the only part of a publish that is a cross-process call.
        // Two nanoTime reads per frame is the whole price of being able to answer that from the
        // probe's own log rather than by subtracting a quiet frame from a busy one.
        long started = System.nanoTime();
        int postedNow = 0;
        boolean collapsing = events.willCollapse();
        List<AccessibleEvent> drained = events.drain();
        for (AccessibleEvent event : drained) {
            AxNotifications.Posting posting = AxNotifications.of(event.type());
            // A null is a decision, not a gap: AppKit is already telling the client, or the event
            // names the window root this bridge elides.
            if (posting == null) continue;
            long subject = posting.subject() == AxNotifications.Subject.APPLICATION
                    ? applicationElement()
                    : elementForEvent(event);
            if (subject == 0) continue;
            posted.add(posting.notificationSymbol());
            postedNow++;
            if (objc != null) objc.post(subject, objc.constant(posting.notificationSymbol()));
        }
        if (collapsing) {
            elements.reconcile(liveNodeIds());
            // The pushed array may name elements that were just released, and comparing it against
            // a fresh list would then hand AppKit a freed pointer. Forgetting it forces a re-push.
            pushed = new long[0];
        }
        lastDrainNanos = System.nanoTime() - started;
        lastDrainDrained = drained.size();
        lastDrainPosted = postedNow;
    }

    private long lastDrainNanos;
    private int lastDrainDrained;
    private int lastDrainPosted;

    /** @return how long the last ordinary publish spent draining, in nanoseconds; the §13.19 cost. */
    long lastDrainNanos() {
        return lastDrainNanos;
    }

    /** @return how many events the last drain took off the queue, one when it had collapsed. */
    int lastDrainDrained() {
        return lastDrainDrained;
    }

    /** @return how many of those reached the platform as a notification. */
    int lastDrainPosted() {
        return lastDrainPosted;
    }

    /** @return how many times the queue has collapsed since this bridge opened (§13.19's signal). */
    int collapses() {
        return events.collapses();
    }

    /**
     * The element a per-node notification is posted on, or zero when there is none.
     *
     * <p>Zero for a node no client has ever asked about, which is the common case and is right: a
     * notification about an object the platform has never seen is one no client is registered for.
     */
    private long elementForEvent(AccessibleEvent event) {
        return elements.holds(event.nodeId()) ? elements.elementFor(event.nodeId()) : 0;
    }

    /**
     * The process's application element, which is the only registration a focus change reaches —
     * measured in the spike, where an observer on the element itself received nothing.
     */
    private long applicationElement() {
        // Off AppKit, a number that stands for it, the way a synthetic element stands for an
        // object: what is being exercised there is which subject an event chooses, and that is
        // bookkeeping over a pointer nothing dereferences.
        if (objc == null) return SYNTHETIC_APPLICATION;
        return ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
    }

    private static final long SYNTHETIC_APPLICATION = 0x1;

    /** §2.2's re-push: the root's children onto the content view, and only when they changed. */
    private void repushRootIfChanged() {
        if (tree().nodeCount() == 0) return;
        long[] now = childElementsOf(tree().root());
        if (Arrays.equals(now, pushed)) return;
        if (objc != null) {
            long array = objc.mutableArray();
            for (long element : now) objc.addObject(array, element);
            ObjC.msgVoid(contentView, "setAccessibilityChildren:", array);
        }
        pushed = now;
        pushes++;
    }

    /** @return whether a reentrant publish left work for the next ordinary frame. */
    boolean obligationsDeferred() {
        return obligationsDeferred;
    }

    /** @return every event the scene emitted, in order. */
    List<String> emittedEvents() {
        return List.copyOf(emitted);
    }

    /** @return what each ask for the focused element was answered with, in order. */
    List<String> focusedAnswers() {
        return List.copyOf(focusedAnswers);
    }

    /** @return how many times AppKit asked one of our elements where the focus is (§13.22). */
    int focusedElementAsks() {
        return elementClass == null ? 0 : elementClass.focusedElementAsks();
    }

    /** @return how many times AppKit asked the content view where the focus is (§13.22). */
    int focusedElementAsksOnView() {
        return elementClass == null ? 0 : elementClass.focusedElementAsksOnView();
    }

    /** @return what detaching did to the platform's objects, in the order it did it. */
    List<String> teardown() {
        return List.copyOf(teardown);
    }

    /** @return the notification symbols posted so far, in order. */
    List<String> postedNotifications() {
        return List.copyOf(posted);
    }

    /** @return how many events are waiting for the next frame. */
    int queuedEvents() {
        return events.size();
    }

    /** @return how many times the root's children have been pushed onto the content view. */
    int pushes() {
        return pushes;
    }

    /** @return the elements last pushed, in order. */
    long[] pushedElements() {
        return pushed.clone();
    }

    /** @return the parent-space box last computed for a node, or {@code null}. */
    double[] lastFrameOf(long nodeId) {
        return lastFrames.get(nodeId);
    }

    /** @return how many elements are alive. */
    int elementCount() {
        return elements.size();
    }

    /** @return the node identifiers currently alive in the tree, for the reconciliation sweep. */
    Set<Long> liveNodeIds() {
        Set<Long> live = new HashSet<>();
        for (int i = 0; i < tree().nodeCount(); i++) live.add(tree().node(i).id());
        return live;
    }
}
