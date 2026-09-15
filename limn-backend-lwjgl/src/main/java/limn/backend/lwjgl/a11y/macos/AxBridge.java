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
import java.util.function.Consumer;

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
 * the end of the next frame, which the scene has already asked for and which pays it whether or not
 * that frame publishes.
 *
 * <p><b>Notifications are posted when the frame ends</b> ({@link #frameEnded()}), after the scene has
 * emitted everything that frame had to say. They were once posted at the top of the next publish,
 * and a scene publishes only when its tree changed, so every notification waited for the next change
 * and the last one before a pause was never told at all (MACOS-NEW-8).
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
        return openedOrSaid(() -> {
            AxObjC objc = AxObjC.openOrNull();
            if (objc == null) return NONE;
            long contentView = ObjC.msg(nsWindow, "contentView");
            if (contentView == 0) return NONE;
            return new AxBridge(objc, contentView);
        });
    }

    /**
     * Runs the whole of an open, and says why when it fails.
     *
     * <p>Everything that can fail is inside: loading AppKit, the {@code contentView} message and the
     * constructor, and every {@link Throwable}, an {@link Error} included. {@code Bridges.openFor}
     * catches whatever escapes and answers {@code NONE} for every platform in silence, so a failure
     * that reached it — a linkage error out of libffi, a class that would not initialise — left a
     * window with no accessibility and no word about it (MACOS-NEW-6). Answering {@code NONE} is
     * still right, since a window that cannot be made accessible is still a window; the silence was
     * not.
     *
     * @param open the open itself
     * @return what it answered, or {@code NONE} after an ERROR naming what it threw
     */
    static AccessibilityBridge openedOrSaid(java.util.function.Supplier<AccessibilityBridge> open) {
        try {
            return open.get();
        } catch (Throwable refused) {
            LOG.log(System.Logger.Level.ERROR, "macOS accessibility could not be opened for this "
                    + "window, which therefore has none", refused);
            return NONE;
        }
    }

    private static final System.Logger LOG = System.getLogger(AxBridge.class.getName());

    private final AxObjC objc;
    private final long contentView;
    private final AxElementClass elementClass;
    private final AxElements elements;
    /** A table's column elements, which stand for no node (M4). */
    private final AxColumns columns;
    /** Off AppKit, the numbers that stand for elements and columns alike, so that none is reused. */
    private long synthetic = 0x1000;
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
    /** The row and selection lookups, for deciding what a selection change is posted as. */
    private final AxGrid grid = new AxGrid(this);
    /**
     * Where this bridge's diagnostic lines go, or {@code null} for nowhere, which is the default and
     * the production state.
     *
     * <p>Gated rather than kept, like {@code UiaWindow.say} on the other platform, and gated before
     * the line is built: VoiceOver asks for the focused element continuously, and a bridge that kept
     * a string for every such ask, every emitted event and every post did so on the user-interface
     * thread for the life of the process (CRIT-5). With nothing attached nothing is allocated and
     * nothing is retained; the live probe attaches a consumer and prints what reached it.
     */
    private Consumer<String> trace;
    /**
     * What the last detach did to the platform's objects, in order. For the test that guards the
     * order, and bounded by what one detach releases: it is emptied when a detach starts and
     * written only while one runs, so a registry swept a thousand times in a session keeps nothing.
     */
    private final List<String> teardown = new ArrayList<>();
    private boolean detaching;
    /** Whether a detach has released the platform half, after which nothing is published here again. */
    private boolean released;

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
                Long nodeId = nodeIdByElement.remove(element);
                // The box goes with the element, or the record of boxes would keep one entry per
                // node that ever held an element, for the life of the process.
                if (nodeId != null) lastFrames.remove(nodeId);
                // Demoted before it is released, because AppKit hands a vended element to a client
                // by reference and our release is not the client's: whatever still holds it must
                // land on NSAccessibilityElement's own answers, not on a closure of ours that the
                // detach is about to free.
                if (elementClass != null) elementClass.demote(element);
                if (detaching) teardown.add("element demoted");
                if (objc != null) ObjC.msg(element, "release");
            }
        });
        this.columns = new AxColumns(new AxColumns.Factory() {
            @Override public long newColumn(long tableId, int column) {
                long element = elementClass == null ? (synthetic += 0x10) : elementClass.newColumnInstance();
                applyColumnFrame(element, tableId, column);
                return element;
            }

            @Override public void release(long element) {
                columnFrames.remove(element);
                // Demoted first, as a node's element is: a client may still hold it.
                if (elementClass != null) elementClass.demote(element);
                if (detaching) teardown.add("column demoted");
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
        // A bridge whose platform half was released takes nothing more: its element class and its
        // closures are freed, so a push would hand the content view elements of a freed class, and a
        // tree stored here would re-enter the open windows, where another window's focus ask would
        // mint through a freed registry (macos-B review). A window gets a new bridge; this one is done.
        if (released) return;
        AccessibleTree before = tree();
        super.publish(published, reentrant);
        openWindowsEpoch++;
        // Both kinds of publish: a count read off two snapshots touches no platform object, and the
        // post it owes waits for the frame's end like every other.
        noteRowCountChanges(before, published);
        if (published.nodeCount() > 0) open(this);
        if (reentrant) {
            // The store is the whole of it. Releasing, re-pushing or draining here would act on the
            // objects AppKit is standing on, and on this platform that is a crash rather than a
            // stale reading.
            obligationsDeferred = true;
            return;
        }
        obligationsDeferred = false;
        // No drain here: the events of this tree have not been emitted yet, and the ones queued
        // are the previous tree's, which the end of the previous frame already told. The drain is
        // the frame's end (frameEnded), after this publish's own events.
        // The push before the frames, because the push is what mints the root's children and a
        // node with no element has no box to set. The first run of this had them the other way
        // round and every element arrived as a zero-size rectangle at the origin -- which a walk
        // reads perfectly and a hit test cannot resolve at all, so it is exactly the defect §13.21
        // says only a live client finds.
        repushRootIfChanged();
        refreshFrames();
    }

    /**
     * Posts what this frame emitted, and pays whatever a reentrant publish deferred.
     *
     * <p>The re-push and the boxes before the drain, so that a notification naming a node the push
     * has just minted goes out on an element that exists. After a collapse the drain forgets what
     * was pushed, because the sweep may have released some of it; the root is pushed again at once
     * rather than on the next publish, which on a still window may be a long time coming.
     */
    @Override
    public void frameEnded() {
        if (obligationsDeferred) {
            obligationsDeferred = false;
            repushRootIfChanged();
            refreshFrames();
        }
        // A quiet frame costs one comparison and allocates nothing: AccessibleIdleCostTest's frame
        // with a live bridge and a clean tree goes through here.
        if (events.size() == 0 && !events.willCollapse() && recountedContainers.isEmpty()) return;
        if (drain()) repushRootIfChanged();
    }

    @Override
    public void emit(AccessibleEvent event) {
        // Enqueue, never post: every post is a cross-process call, and a difference between two
        // frames can be hundreds of nodes wide.
        Consumer<String> to = trace;
        if (to != null) to.accept("emitted " + event.type() + "#" + event.nodeId());
        events.add(event);
    }

    @Override
    protected void invalidateEverythingVended() {
        // Every element at once, and the record of what was pushed with them: an earlier draft
        // released nothing on a rebind at all (§1.10, §5.3), and the pushed array would otherwise
        // name objects that no longer exist.
        elements.empty();
        columns.empty();
        pushed = new long[0];
        obligationsDeferred = false;
    }

    @Override
    public void detach() {
        close(this);
        teardown.clear();
        detaching = true;
        try {
            super.detach();
        } finally {
            detaching = false;
        }
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
        released = true;
    }

    // ---- what an implementation asks ------------------------------------------------------------

    @Override
    public void entered() {
        listening = true;
    }

    @Override
    public boolean perform(long nodeId, Accessible.Action action) {
        return perform(nodeId, action, Accessible.Argument.NONE);
    }

    @Override
    public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument argument) {
        Host current = host();
        // Between a detach and an attach there is nobody to ask, and refusing is the only honest
        // answer: the scene that owned the widget is gone.
        return current != null && current.perform(nodeId, action, argument);
    }

    /**
     * Where the user is (semantics 4; decision 1): the tree's {@linkplain AccessibleTree#effectiveFocus()
     * effective focus}, the cursor item under the focused widget when there is one, and never the
     * widget that merely holds the keyboard around it. VoiceOver is told the focus moved and then asks
     * this, so an answer of the widget is a reader standing on the table while the user walks its cells.
     *
     * <p><b>The node may be another window's</b> (decision 5): a focused field whose cursor is in the
     * native popup it opened. Its element is then that window's bridge's, minted in that bridge's
     * registry, because an element belongs to the window whose tree it stands for. And the other way
     * round: a popup window with nothing of its own focused, asked by AppKit, answers the node another
     * window's cursor is on inside it, so both windows' views agree on one element.
     */
    @Override
    public long focusedElement() {
        AccessibleTree tree = tree();
        long effective = tree.effectiveFocus();
        Consumer<String> to = trace;
        if (effective != 0 && tree.indexOf(effective) < 0) {
            AxBridge holder = openBridgeHolding(effective);
            if (holder != null) {
                long element = holder.elements.elementFor(effective);
                if (to != null) {
                    to.accept("focused " + effective + "=" + holder.tree().find(effective).role() + "@"
                            + Long.toHexString(element) + " in another window");
                }
                return element;
            }
            effective = 0;
        }
        if (effective == 0) effective = cursorFromAnotherWindow();
        AccessibleNode node = effective == 0 ? null : tree.find(effective);
        if (node == null) {
            if (to != null) to.accept("focused none");
            return 0;
        }
        long element = elements.elementFor(effective);
        if (to != null) {
            to.accept("focused " + effective + "=" + node.role() + "@" + Long.toHexString(element));
        }
        return element;
    }

    /**
     * {@code isAccessibilityFocused}, agreeing with {@link #focusedElement()}: the node where the user
     * is, in this window or as the cursor another window's focused node resolved into this one. The
     * widget around a cursor item does not answer true; the item does.
     */
    @Override
    public boolean isFocused(AccessibleNode node) {
        // No lookup of the node: the closure resolved it from this tree a moment ago, and identifiers
        // are process-wide, so an equal identifier is this node. AppKit sends this to every element a
        // client walks, and a linear search of the tree here — and of another window's — made reading
        // a large table cost the product of its elements and its nodes.
        long id = node.id();
        return tree().effectiveFocus() == id || cursorFromAnotherWindow() == id;
    }

    // ---- the process's open windows (decision 5) ----------------------------------------------

    /**
     * Every bridge holding a published tree in this process, so that a cursor resolved into a native
     * popup's tree is answered with the element of the window that holds it. Copied on write and read
     * as an array, because it is read on every focus ask VoiceOver sends and an iterator would be an
     * allocation on each; written on a publish that finds this bridge absent, and on a detach.
     */
    private static volatile AxBridge[] openBridges = new AxBridge[0];

    private static synchronized void open(AxBridge bridge) {
        AxBridge[] now = openBridges;
        for (AxBridge open : now) {
            if (open == bridge) return;
        }
        AxBridge[] grown = Arrays.copyOf(now, now.length + 1);
        grown[now.length] = bridge;
        openBridges = grown;
        openWindowsEpoch++;
    }

    private static synchronized void close(AxBridge bridge) {
        AxBridge[] now = openBridges;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != bridge) continue;
            AxBridge[] shrunk = new AxBridge[now.length - 1];
            System.arraycopy(now, 0, shrunk, 0, i);
            System.arraycopy(now, i + 1, shrunk, i, now.length - i - 1);
            openBridges = shrunk;
            openWindowsEpoch++;
            return;
        }
    }

    /** @return how many bridges the process's set of open windows holds. For tests. */
    static int openBridgeCount() {
        return openBridges.length;
    }

    /**
     * @return whether this bridge is in the process's set of open windows: from its first publish of
     *         a tree until its detach, which drops it so that a closed window is never held for the
     *         life of the process by the set that answers other windows' cursors; a detached bridge
     *         never re-enters it
     */
    boolean isOpen() {
        for (AxBridge open : openBridges) {
            if (open == this) return true;
        }
        return false;
    }

    /** @return another open bridge whose published tree holds the node, or {@code null} */
    private AxBridge openBridgeHolding(long nodeId) {
        for (AxBridge other : openBridges) {
            if (other != this && other.tree().indexOf(nodeId) >= 0) return other;
        }
        return null;
    }

    /**
     * @return the node of this tree another open window's effective focus names, or {@code 0}: the
     *         cursor a focused field in another window has inside this, its native popup
     */
    private long cursorFromAnotherWindow() {
        AxBridge[] open = openBridges;
        // One window has nobody else's cursor to hold, which is the common case and costs nothing.
        if (open.length < 2) return 0;
        // Resolved once per change of any open window's tree, not once per ask: the answer moves only
        // when a tree is published or a window opens or closes, and each of those moves the epoch.
        long epoch = openWindowsEpoch;
        if (epoch == foreignCursorEpoch) return foreignCursor;
        AccessibleTree mine = tree();
        long found = 0;
        for (AxBridge other : open) {
            if (other == this) continue;
            AccessibleTree theirs = other.tree();
            long cursor = theirs.effectiveFocus();
            if (cursor != 0 && theirs.indexOf(cursor) < 0 && mine.indexOf(cursor) >= 0) {
                found = cursor;
                break;
            }
        }
        foreignCursor = found;
        foreignCursorEpoch = epoch;
        return found;
    }

    /**
     * Moves on every publish of any bridge in the process and on every open and close of one, so that
     * an answer derived from the open windows' trees knows when it is stale. User-interface thread,
     * like every write here; volatile for the tests' readers only.
     */
    private static volatile long openWindowsEpoch;
    /** The epoch {@link #foreignCursor} was resolved at, or {@code -1} before the first resolution. */
    private long foreignCursorEpoch = -1;
    /** The node of this tree another window's effective focus named at that epoch, or {@code 0}. */
    private long foreignCursor;

    @Override
    public AccessibleNode nodeFor(long element) {
        Long nodeId = nodeIdByElement.get(element);
        if (nodeId == null) return null;
        return tree().find(nodeId);
    }

    @Override
    public long elementFor(long nodeId) {
        return elements.elementFor(nodeId);
    }

    @Override
    public long columnElementFor(AccessibleNode table, int column) {
        return columns.elementFor(table.id(), column);
    }

    @Override
    public long[] columnKeyOf(long element) {
        return columns.keyOf(element);
    }

    @Override
    public long[] childElementsOf(AccessibleNode node) {
        List<AccessibleNode> children = tree().children(node);
        long[] answer = new long[children.size()];
        for (int i = 0; i < answer.length; i++) answer[i] = elements.elementFor(children.get(i).id());
        return answer;
    }

    @Override
    public long parentElementOf(AccessibleNode node) {
        AccessibleNode live = tree().find(node.id());
        if (live == null) return contentView;
        int parent = live.parent();
        // A child of the elided window root answers with the content view, because that is the
        // object AppKit was handed and the one it expects to get back.
        if (parent == AccessibleNode.NONE || parent == 0) return contentView;
        return elements.elementFor(tree().node(parent).id());
    }

    @Override
    public long[] linkedElementsOf(AccessibleNode node) {
        AccessibleTree tree = tree();
        List<Long> linked = new ArrayList<>();
        for (var relation : node.relations()) {
            int index = tree.indexOf(relation.target());
            // The window root is not vended (§2.2), so a relation resolving to it has no element
            // of ours to name; §1.11 drops that case before it reaches a bridge, and this is the
            // same rule applied to a target that left the tree between publish and ask.
            if (index <= 0) continue;
            linked.add(elements.elementFor(relation.target()));
        }
        long[] answer = new long[linked.size()];
        for (int i = 0; i < answer.length; i++) answer[i] = linked.get(i);
        return answer;
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
        if (columns.size() == 0) return;
        for (long[] held : columns.held()) applyColumnFrame(held[0], held[1], (int) held[2]);
    }

    /**
     * Gives a column element its box in its table's space (M4): its header cell's left edge and width,
     * or with no header cell those of its first realized data cell, over the table's whole height — a
     * native table's column spans its header and its rows. A column whose table has left the tree, or
     * that has no cell to measure, keeps the box it had.
     */
    private void applyColumnFrame(long element, long tableId, int column) {
        AccessibleTree tree = tree();
        int table = tree.indexOf(tableId);
        if (table <= 0 || tree.node(table).table() == null) return;
        AccessibleNode tableNode = tree.node(table);
        int measured = AxGrid.headerCellInColumn(tree, table, column);
        if (measured == AccessibleNode.NONE) {
            for (int row = tableNode.firstChild(); row != AccessibleNode.NONE && measured == AccessibleNode.NONE;
                    row = tree.node(row).nextSibling()) {
                if (tree.node(row).role() != Accessible.Role.ROW) continue;
                for (int cell = tree.node(row).firstChild(); cell != AccessibleNode.NONE; cell = tree.node(cell).nextSibling()) {
                    if (AxGrid.isDataCell(tree.node(cell)) && tree.node(cell).cell().column() == column) {
                        measured = cell;
                        break;
                    }
                }
            }
        }
        if (measured == AccessibleNode.NONE) return;
        Rect cell = tree.node(measured).bounds();
        Rect box = tableNode.bounds();
        double[] parentSpace = AxFrames.inParentSpace(new Rect(cell.x(), box.y(), cell.width(), box.height()), box);
        if (objc != null) objc.msgRect(element, "setAccessibilityFrameInParentSpace:", parentSpace);
        columnFrames.put(element, parentSpace);
    }

    /** The last parent-space box computed for each held column element. For tests. */
    private final Map<Long, double[]> columnFrames = new HashMap<>();

    /** @return the parent-space box last computed for a column element, or {@code null} */
    double[] lastColumnFrameOf(long element) {
        return columnFrames.get(element);
    }

    /** @return how many column elements are alive. */
    int columnCount() {
        return columns.size();
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
     * <p>Only from the end of a frame: a post from inside an AX callback re-enters the platform
     * while it is standing on our objects (§3.2). The queue keeps a reentrant publish's events for
     * the end of the frame the scene has already asked for.
     *
     * <p>A collapse is why the sweep is here rather than only on {@code NODE_DESTROYED}: the burst
     * that overflowed the queue is exactly the one whose per-node destructions were dropped, so
     * after one there is no list of what died — only the tree, and whatever the registry still
     * holds (§13.9). The model's own {@code INVALIDATED} says the same of its publish and is swept
     * the same way (semantics 7).
     *
     * <p><b>Where the user is goes out once, and last.</b> A focus move and a cursor move are one
     * notification here (both are "the focused element changed"), so a publish that moved both posts
     * one; and after a sweep — the queue's collapse or the model's {@code INVALIDATED} — it is posted
     * whether or not an event said so, because the sweep may have released the element a reader
     * stood on and nothing else would send it back (semantics 4). Last, so that a reader told of a
     * selection or an expansion in the same frame lands on the cursor after hearing it.
     */
    private boolean drain() {
        // Timed, because §13.19's macOS half is "what does one frame's drain cost with a reader
        // attached", and the drain is the only part of a publish that is a cross-process call.
        // Two nanoTime reads per frame is the whole price of being able to answer that from the
        // probe's own log rather than by subtracting a quiet frame from a busy one.
        long started = System.nanoTime();
        int postedNow = 0;
        boolean collapsing = events.willCollapse();
        boolean swept = collapsing;
        boolean focusOwed = false;
        List<AccessibleEvent> drained = events.drain();
        for (int i = 0; i < drained.size(); i++) {
            AccessibleEvent event = drained.get(i);
            if (event.type() == AccessibleEvent.Type.SELECTION_CHANGED && elements.holds(event.nodeId())) {
                toldSelections.add(event.nodeId());
            }
        }
        for (AccessibleEvent event : drained) {
            if (event.type() == AccessibleEvent.Type.INVALIDATED) swept = true;
            if (event.type() == AccessibleEvent.Type.NODE_DESTROYED) {
                noteDestroyed(event.nodeId());
                continue;
            }
            AxNotifications.Posting posting = AxNotifications.of(event);
            // A null is a decision, not a gap: AppKit is already telling the client, or the event
            // names the window root this bridge elides.
            if (posting == null) continue;
            if (selectedToldOnItsContainer(event)) continue;
            if (event.type() == AccessibleEvent.Type.SELECTION_CHANGED) posting = selectionPosting(event);
            AccessibleNode opened = openedOutlineRow(event);
            if (opened != null) {
                posting = AxNotifications.disclosure(Boolean.TRUE.equals(event.newValue()));
                AccessibleNode outline = tree().node(opened.selectionContainer());
                if (!recountedContainers.contains(outline.id())) recountedContainers.add(outline.id());
            }
            if (posting.subject() == AxNotifications.Subject.APPLICATION) {
                focusOwed = true;
                continue;
            }
            long subject = elementForEvent(event);
            if (subject == 0) continue;
            post(subject, posting);
            postedNow++;
        }
        for (int i = 0; i < recountedContainers.size(); i++) {
            long container = recountedContainers.get(i);
            // Held and still in the tree now, at the frame's end: a container a later publish of the
            // same frame removed has nothing left to recount.
            if (!elements.holds(container) || tree().indexOf(container) == AccessibleNode.NONE) continue;
            post(elements.elementFor(container), AxNotifications.ROW_COUNT_CHANGED);
            postedNow++;
        }
        recountedContainers.clear();
        toldSelections.clear();
        releaseDestroyed();
        // A column whose table left the tree or no longer shows it goes when the frame ends, never from
        // a reentrant publish (§3.2), which drains nothing.
        columns.reconcile(tree());
        if (swept) {
            elements.reconcile(liveNodeIds());
            // The pushed array may name elements that were just released, and comparing it against
            // a fresh list would then hand AppKit a freed pointer. Forgetting it forces a re-push.
            pushed = new long[0];
            if (tree().effectiveFocus() != 0 || cursorFromAnotherWindow() != 0) {
                focusOwed = true;
            }
        }
        if (focusOwed) {
            post(applicationElement(), FOCUS_POSTING);
            postedNow++;
        }
        lastDrainNanos = System.nanoTime() - started;
        lastDrainDrained = drained.size();
        lastDrainPosted = postedNow;
        return swept;
    }

    /** The nodes this drain was told were destroyed, whose elements it releases after posting. */
    private long[] destroyed = new long[8];
    private int destroyedCount;

    private void noteDestroyed(long nodeId) {
        if (destroyedCount == destroyed.length) destroyed = Arrays.copyOf(destroyed, destroyedCount * 2);
        destroyed[destroyedCount++] = nodeId;
    }

    /**
     * Releases the element of every node this frame destroyed (MACOS-NEW-1; §2.2, §1.10), at the
     * frame's end and after the posts, so a notification about the node goes out on an element that
     * still exists; never from a reentrant publish, which drains nothing (§3.2). It posts nothing:
     * AppKit posts {@code AXUIElementDestroyed} itself when the element goes (§13.20).
     *
     * <p><b>Only a node still absent from the tree.</b> Identities keyed by a row come back — a list
     * row keyed by its index, a table row by its record — and an identifier destroyed and published
     * again within the frame is the same node (§1.3), whose element a client may be using: releasing
     * it would make AppKit tell that client the element was destroyed. The pushed array needs no
     * forgetting here: the root's children are pushed before the drain on every path that reaches it,
     * so a node absent from the tree is never among them.
     */
    private void releaseDestroyed() {
        AccessibleTree tree = tree();
        for (int i = 0; i < destroyedCount; i++) {
            long nodeId = destroyed[i];
            if (elements.holds(nodeId) && tree.indexOf(nodeId) == AccessibleNode.NONE) elements.forget(nodeId);
        }
        destroyedCount = 0;
    }

    /**
     * The row containers a drain owes a row-count change, once each: filled by a publish whose snapshot
     * counts a held container's rows differently from the one before, and by an outline row opening or
     * closing; emptied by the drain that posts them.
     */
    private final List<Long> recountedContainers = new ArrayList<>();

    /**
     * Queues a row-count change for every held table, outline or list whose row count differs between
     * two snapshots (M1 correction 2). An outline's rows change without any row's expanded state
     * flipping — a lazy load landing under a row already open, a refresh, a model adding roots — and a
     * list's and a table's with no expansion at all; a native outline posted {@code AXRowCountChanged}
     * on itself when its rows changed (read on the macOS 26.6.2 guest, 2026-09-15,
     * {@code scripts/a11y/macos/outline-probe.swift}, for a disclosure, the one trigger read). The count
     * is the model's, not the realized rows': a scroll changes which rows are realized and not how many
     * the widget has.
     */
    private void noteRowCountChanges(AccessibleTree before, AccessibleTree now) {
        if (before.nodeCount() == 0 || now.nodeCount() == 0) return;
        for (int i = 1; i < now.nodeCount(); i++) {
            AccessibleNode node = now.node(i);
            if (!grid.isRowContainer(node) || !elements.holds(node.id())) continue;
            int was = before.indexOf(node.id());
            if (was == AccessibleNode.NONE || rowCountOf(before, was) == rowCountOf(now, i)) continue;
            if (!recountedContainers.contains(node.id())) recountedContainers.add(node.id());
        }
    }

    /**
     * @return how many rows a row container has in one snapshot: a table's facet count; an outline's
     *         hierarchy row count and a list's set size, read off its first realized member among its
     *         children, which is where a tree's and a list's rows hang; zero with no member realized
     */
    private static int rowCountOf(AccessibleTree tree, int container) {
        AccessibleNode node = tree.node(container);
        if (node.table() != null) return node.table().rowCount();
        for (int child = node.firstChild(); child != AccessibleNode.NONE; child = tree.node(child).nextSibling()) {
            AccessibleNode member = tree.node(child);
            if (member.selectionContainer() != container) continue;
            if (member.hierarchy() != null) return member.hierarchy().rowCount();
            if (member.selectionItem() != null) return member.selectionItem().sizeOfSet();
        }
        return 0;
    }

    /** The containers a drain posts a selection change on; emptied by the drain that fills it. */
    private final List<Long> toldSelections = new ArrayList<>();

    /**
     * Whether an event is a member's selected state flipping while its container is posted the
     * selection change in the same drain (M3 correction f). The container's notification is the one a
     * reader re-reads the selection after, and {@code AXSelected} is read off the member when it does;
     * a value-changed on each member on top of it was two more posts per arrow in a tree — on the row
     * the selection left and on the row it reached — where a native outline posted only
     * {@code AXSelectedRowsChanged} (read on the macOS 26.6.2 guest, 2026-09-15,
     * {@code scripts/a11y/macos/outline-probe.swift}). A member whose container is not told — none, or
     * one no client holds — is still told on itself.
     */
    private boolean selectedToldOnItsContainer(AccessibleEvent event) {
        if (toldSelections.isEmpty() || event.type() != AccessibleEvent.Type.STATE_CHANGED
                || event.state() != Accessible.State.SELECTED) return false;
        AccessibleTree tree = tree();
        AccessibleNode member = tree.find(event.nodeId());
        if (member == null) return false;
        int at = member.selectionContainer();
        return at != AccessibleNode.NONE && at < tree.nodeCount() && toldSelections.contains(tree.node(at).id());
    }

    /**
     * @return the outline row an event says opened or closed, or {@code null}: a row of an outline
     *         whose expanded state flipped, which is told as a row expanded or collapsed on the row and
     *         a row-count change on the outline rather than as a value change
     */
    private AccessibleNode openedOutlineRow(AccessibleEvent event) {
        if (event.type() != AccessibleEvent.Type.STATE_CHANGED
                || event.state() != Accessible.State.EXPANDED) return null;
        AccessibleNode row = tree().find(event.nodeId());
        return row != null && grid.isOutlineRow(row) ? row : null;
    }

    /**
     * A selection change is posted on its container (decision 9) as the notification of the attribute
     * that container's selection is read from: rows changed for an outline, a list or a table of rows —
     * what a native NSOutlineView posts on itself when a row is selected (read on the macOS 26.6.2
     * guest, 2026-09-15, outline-probe.swift), with no selected-children change beside it — cells
     * changed for a grid of selectable cells, and selected children changed for anything else.
     */
    private AxNotifications.Posting selectionPosting(AccessibleEvent event) {
        AccessibleNode container = tree().find(event.nodeId());
        return AxNotifications.selection(container == null || container.selection() == null
                ? AxGrid.SelectionShape.CHILDREN : grid.selectionShape(container));
    }

    /** The one application-level notification: the focused element changed. */
    private static final AxNotifications.Posting FOCUS_POSTING =
            AxNotifications.of(AccessibleEvent.Type.FOCUS_CHANGED);

    private void post(long subject, AxNotifications.Posting posting) {
        Consumer<String> to = trace;
        if (to != null) to.accept("posted " + posting.notificationSymbol());
        if (objc != null) {
            objc.post(subject, posting.literal()
                    ? objc.string(posting.notificationSymbol())
                    : objc.constant(posting.notificationSymbol()));
        }
    }

    private long lastDrainNanos;
    private int lastDrainDrained;
    private int lastDrainPosted;

    /** @return how long the last frame end spent draining, in nanoseconds; the §13.19 cost. */
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

    /** @return whether a reentrant publish left work for the next frame to end. */
    boolean obligationsDeferred() {
        return obligationsDeferred;
    }

    /**
     * Sends this bridge's diagnostic lines somewhere, or stops sending them.
     *
     * <p>Each line is one of {@code emitted TYPE#id} (an event the scene handed over),
     * {@code posted SYMBOL} (a notification that reached AppKit), {@code focused id=ROLE@element} or
     * {@code focused none} (an answer to "where is the focus"). User-interface thread, like every
     * other call here; the consumer is called on it, inside whatever produced the line.
     *
     * @param to where the lines go, or {@code null} for nowhere
     */
    void trace(Consumer<String> to) {
        this.trace = to;
    }

    /** @return how many times AppKit asked one of our elements where the focus is (§13.22). */
    int focusedElementAsks() {
        return elementClass == null ? 0 : elementClass.focusedElementAsks();
    }

    /** @return how many times AppKit asked the content view where the focus is (§13.22). */
    int focusedElementAsksOnView() {
        return elementClass == null ? 0 : elementClass.focusedElementAsksOnView();
    }

    /** @return what the last detach did to the platform's objects, in the order it did it. */
    List<String> teardown() {
        return List.copyOf(teardown);
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
