package limn.a11y.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * The map from a node identifier to the COM object that stands for it, and back from the interface
 * pointer a call arrives on.
 *
 * <p><b>This is the piece of the Windows bridge that an unassigned owner does not survive</b>
 * (ADR&nbsp;039&nbsp;§3.4). The provider is entered from up to three UI Automation RPC threads at
 * once with no lock taken on our behalf, and an element is created the first time a client
 * navigates to a node — so two threads reaching the same unvisited node would, under a plain
 * {@code HashMap}, create two objects for one identifier, hand each client a different element for
 * the same node, and race a resize in the map itself. Both maps are therefore concurrent, and
 * minting is {@link ConcurrentHashMap#computeIfAbsent} so that the second thread waits for the
 * first's object rather than building its own.
 *
 * <p><b>Who removes, and the one rule that makes it answerable at every call site.</b> The drain
 * thread removes an entry as it announces that node's destruction and as it sweeps a collapse; the
 * user-interface thread empties the whole registry when a bridge attaches over a live host and when
 * it detaches, and it does that only after the drain thread has been stopped and joined, so the two
 * never race. What decides who releases is not an ordering argument but the return of
 * {@link Map#remove}: whoever gets the non-null value releases, so a double removal is a removal
 * and nothing has to be re-derived where it happens.
 *
 * <p><b>Removal is not release.</b> The COM object survives until a client's own reference drops
 * and answers {@code UIA_E_ELEMENTNOTAVAILABLE} meanwhile, which is what §1.3 promises to a client
 * holding an element for a node that has left the tree. What removal ends is <em>this bridge's</em>
 * claim on it.
 */
final class UiaElements {

    /**
     * Node identifier to element. Written by whichever RPC thread first navigates to a node, and by
     * the drain thread and the user-interface thread as they remove.
     */
    private final Map<Long, UiaElement> byNode = new ConcurrentHashMap<>();

    /**
     * Interface pointer to element, which is how a call arriving on an RPC thread recovers the Java
     * object behind the pointer it was invoked on. The Windows spike used exactly this shape.
     */
    private final Map<Long, UiaElement> byPointer = new ConcurrentHashMap<>();

    /**
     * The element for {@code nodeId}, made once however many threads ask at once.
     *
     * <p>{@code mint} runs at most once per identifier and runs inside the map's own computation,
     * so it must not touch this registry again and must not block: a second thread asking for the
     * same identifier is parked on it. What it produces is registered under both keys before any
     * caller sees it, so an element is never reachable by node and unreachable by pointer.
     *
     * @param nodeId a node identifier from the published tree
     * @param mint   builds the COM object for a node that has none yet
     * @return the one element for that node
     */
    UiaElement forNode(long nodeId, LongFunction<UiaElement> mint) {
        return byNode.computeIfAbsent(nodeId, id -> {
            UiaElement made = mint.apply(id);
            byPointer.put(made.pointer(), made);
            return made;
        });
    }

    /**
     * @param pointer the interface pointer a call arrived on
     * @return the element behind it, or {@code null} if it has been removed — which is not an
     *         error and is how a call on a stale element is recognised
     */
    UiaElement byPointer(long pointer) {
        return byPointer.get(pointer);
    }

    /**
     * @param nodeId a node identifier
     * @return its element, or {@code null} if this bridge holds none for it
     */
    UiaElement peek(long nodeId) {
        return byNode.get(nodeId);
    }

    /**
     * Gives up this bridge's claim on one node's element, on the drain thread.
     *
     * <p>Idempotent by the rule above: the caller that gets {@code true} is the one that released,
     * and a second call for the same node is a no-op rather than a second release.
     *
     * @param nodeId a node identifier
     * @return whether this call was the one that removed and released it
     */
    boolean remove(long nodeId) {
        UiaElement gone = byNode.remove(nodeId);
        if (gone == null) {
            return false;
        }
        byPointer.remove(gone.pointer());
        gone.release();
        return true;
    }

    /**
     * Empties the whole registry, on the user-interface thread with the drain stopped and joined.
     *
     * <p>The two callers are a bridge attaching over a live host and a bridge detaching, and both
     * have already stopped the drain — which is what makes it safe for this to walk the map while
     * the only other remover is not running. Each element is released exactly once, by the same
     * rule the single-node removal uses.
     */
    void empty() {
        List<UiaElement> taken = new ArrayList<>(byNode.size());
        for (Long id : List.copyOf(byNode.keySet())) {
            UiaElement gone = byNode.remove(id);
            if (gone != null) {
                taken.add(gone);
            }
        }
        byPointer.clear();
        for (UiaElement element : taken) {
            element.release();
        }
    }

    /** @return how many nodes this bridge currently holds an element for */
    int size() {
        return byNode.size();
    }
}
