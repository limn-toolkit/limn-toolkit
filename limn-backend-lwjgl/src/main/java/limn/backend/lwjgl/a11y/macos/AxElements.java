package limn.backend.lwjgl.a11y.macos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map from a node's identifier to the Objective-C object vended for it, and the four ways an
 * object goes away.
 *
 * <p><b>macOS is the one platform that hands out real objects the system retains</b>, so this is
 * where the design's lifetime rule lives. A client that walked the tree holds references; §1.3 says
 * an element for a given node identifier stays the same object across publishes, so a reference a
 * client kept is still the thing it thought it was. That is why this is a map and not a per-frame
 * allocation.
 *
 * <p><b>It is a plain {@link HashMap} with a thread assertion, and that is not laziness.</b> Every
 * accessibility callback on this platform arrives on the user-interface thread, inside the event
 * pump (Finding 4) — the platform's thread <em>is</em> ours. Windows needed concurrent maps because
 * UI Automation calls a provider from several threads of its own while that thread sleeps; here the
 * concurrency does not exist, and a concurrent map would buy nothing while hiding the day the
 * assumption stops being true. So the assumption is asserted instead: a call from anywhere else
 * fails loudly, on the spot, rather than corrupting a map quietly.
 *
 * <p><b>Allocation is lazy, with one exception the push forces.</b> An element exists only for a
 * node the platform has actually asked about — except the root's own children, which §2.2 pushes
 * onto the content view up front and which therefore must exist before anyone has asked.
 *
 * <p><b>Nothing here may be called from inside an accessibility callback.</b> §3.2: a publish with
 * the platform on the stack releases nothing, because the caller is standing on the objects. The
 * deferral is the bridge's, not this class's, and this class is what it defers.
 */
final class AxElements {

    /** How an element is made and unmade; the whole of this class's contact with Objective-C. */
    interface Factory {

        /**
         * @param nodeId the node to vend an object for
         * @return a new, retained {@code NSAccessibilityElement} subclass instance
         */
        long newElement(long nodeId);

        /**
         * Releases one. After this the pointer is dangling and must not be touched again — which is
         * why {@link AxElements} drops it from the map first.
         *
         * @param element the object to release
         */
        void release(long element);
    }

    private final Thread owner;
    private final Factory factory;
    private final Map<Long, Long> byNodeId = new HashMap<>();

    AxElements(Thread owner, Factory factory) {
        this.owner = owner;
        this.factory = factory;
    }

    private void assertOwnerThread(String what) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(what + " off the user-interface thread: this registry is "
                    + "unsynchronised because every accessibility callback on macOS arrives on that "
                    + "thread. Called from '" + Thread.currentThread().getName()
                    + "', owned by '" + owner.getName() + "'.");
        }
    }

    /**
     * The object for a node, minted on first ask and the same object on every ask after.
     *
     * @param nodeId the node
     * @return its element; never zero
     */
    long elementFor(long nodeId) {
        assertOwnerThread("elementFor");
        return byNodeId.computeIfAbsent(nodeId, factory::newElement);
    }

    /**
     * @param nodeId the node
     * @return whether an object has ever been vended for it
     */
    boolean holds(long nodeId) {
        assertOwnerThread("holds");
        return byNodeId.containsKey(nodeId);
    }

    /**
     * Forgets and releases one node's object: the {@code NODE_DESTROYED} path, which
     * {@code AxBridge}'s frame end takes for a destroyed node still absent from the tree.
     *
     * <p>It posts nothing. AppKit posts {@code AXUIElementDestroyed} itself when an element goes
     * away, and the phase 7 probe run measured that ours arrived on top of that once per client
     * registration (§13.20).
     *
     * @param nodeId the node whose object goes away
     * @return whether there was one
     */
    boolean forget(long nodeId) {
        assertOwnerThread("forget");
        Long element = byNodeId.remove(nodeId);
        if (element == null) return false;
        factory.release(element);
        return true;
    }

    /**
     * The reconciliation sweep: releases every object whose node is no longer in the tree.
     *
     * <p>This is what an event-queue collapse leaves behind. A collapse is exactly the burst in
     * which the per-node destructions were dropped, so after one there is no list of what died —
     * only the tree, and whatever this map still holds. §13.9 calls this the path most likely to be
     * written and never exercised, because reaching it needs a difference wider than the queue,
     * which is why it is a method with a test rather than a branch inside a publish.
     *
     * @param liveNodeIds the identifiers in the tree just published
     * @return how many objects were released
     */
    int reconcile(Set<Long> liveNodeIds) {
        assertOwnerThread("reconcile");
        List<Long> dead = new ArrayList<>();
        for (Long nodeId : byNodeId.keySet()) {
            if (!liveNodeIds.contains(nodeId)) dead.add(nodeId);
        }
        // Collected first and removed after: releasing inside the iteration would mutate the map
        // being walked, and doing it in two passes costs one list on a path that runs rarely.
        for (Long nodeId : dead) factory.release(byNodeId.remove(nodeId));
        return dead.size();
    }

    /**
     * Releases everything: a scene bound over a live window, or the window going away.
     *
     * <p>An earlier draft of this design released nothing on either, which left every object a
     * client held pointing at a tree that no longer existed (§1.10, §5.3).
     *
     * @return how many objects were released
     */
    int empty() {
        assertOwnerThread("empty");
        int released = byNodeId.size();
        for (Long element : byNodeId.values()) factory.release(element);
        byNodeId.clear();
        return released;
    }

    /** @return how many objects are alive. */
    int size() {
        assertOwnerThread("size");
        return byNodeId.size();
    }
}
