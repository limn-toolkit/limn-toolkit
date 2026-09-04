package limn.scene;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A bridge that keeps what it is handed instead of talking to a platform: the double every
 * headless accessibility test is written against.
 *
 * <p>It keeps an element registry of its own — a set of node identifiers — because two of the
 * obligations the real bridges have are about exactly that and are otherwise untestable without a
 * platform: an event budget that collapses has to leave a bridge able to release everything that
 * went away, and a scene bound over a live window has to leave it holding nothing at all. A double
 * that only counted calls could not fail either.
 */
final class RecordingAccessibilityBridge implements AccessibilityBridge {

    /** What {@link #isListening()} answers; a test turns the platform on and off with it. */
    boolean listening;

    /** What {@link #needsPrimingPublish()} answers. */
    boolean needsPriming;

    /** Every tree handed over, newest last. */
    final List<AccessibleTree> published = new ArrayList<>();

    /** Whether each of those was handed over with the platform on the stack. */
    final List<Boolean> reentrant = new ArrayList<>();

    /** Every event handed over, in order. */
    final List<AccessibleEvent> events = new ArrayList<>();

    /** The identifiers this bridge would be holding platform objects for. */
    final Set<Long> elements = new LinkedHashSet<>();

    /** How many hosts it has been given, and the one it holds. */
    int attachments;
    Host host;

    @Override
    public boolean isListening() {
        return listening;
    }

    @Override
    public boolean needsPrimingPublish() {
        return needsPriming;
    }

    @Override
    public void publish(AccessibleTree tree, boolean isReentrant) {
        published.add(tree);
        reentrant.add(isReentrant);
        if (isReentrant) {
            // A reentrant publish stores the tree and answers from it. It must not touch the
            // registry: the platform is standing on the objects it would release.
            return;
        }
        for (int i = 0; i < tree.nodeCount(); i++) {
            elements.add(tree.node(i).id());
        }
    }

    @Override
    public void emit(AccessibleEvent event) {
        events.add(event);
        switch (event.type()) {
            case NODE_DESTROYED -> elements.remove(event.nodeId());
            case INVALIDATED -> reconcile();
            default -> { }
        }
    }

    /**
     * What a real bridge does when the difference was too wide to carry: sweep the registry against
     * the tree it was just handed rather than replaying anything. Stronger than the events that
     * were dropped, and the same operation a rebind needs.
     */
    private void reconcile() {
        AccessibleTree tree = published.isEmpty()
                ? AccessibleTree.EMPTY : published.get(published.size() - 1);
        elements.removeIf(id -> tree.indexOf(id) == AccessibleNode.NONE);
    }

    @Override
    public void attach(Host newHost) {
        attachments++;
        if (host != null) {
            // The bridge is the only object that knows there was an outgoing tree: the scene it
            // belonged to never learns it was replaced.
            events.add(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_CLOSED, 0));
            elements.clear();
            published.clear();
            reentrant.clear();
        }
        host = newHost;
        events.add(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_OPENED, 0));
    }

    @Override
    public void detach() {
        events.add(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_CLOSED, 0));
        elements.clear();
        host = null;
    }

    /** Forgets every tree and event recorded so far, so a test can assert on what follows. */
    void clear() {
        published.clear();
        reentrant.clear();
        events.clear();
    }

    /** @return the most recently published tree, or the empty one */
    AccessibleTree tree() {
        return published.isEmpty() ? AccessibleTree.EMPTY : published.get(published.size() - 1);
    }

    /**
     * @param type the kind to count
     * @return how many events of that kind have been handed over
     */
    long countOf(AccessibleEvent.Type type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    /**
     * @param type the kind to look for
     * @return the first event of that kind, or {@code null}
     */
    AccessibleEvent first(AccessibleEvent.Type type) {
        for (AccessibleEvent event : events) {
            if (event.type() == type) {
                return event;
            }
        }
        return null;
    }
}
