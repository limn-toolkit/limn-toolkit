package limn.backend.lwjgl.a11y;

import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

/**
 * What the three platform bridges genuinely share: the snapshot they answer from, the host they
 * answer through, and what binding and unbinding do to both.
 *
 * <p><b>This is deliberately small, and the things that are not here are the point.</b> The three
 * bridges look alike from a distance and are not alike where it counts, which is ADR 039 §4's whole
 * subject — a neutral abstraction over them would force one of them to lie. So:
 *
 * <ul>
 *   <li><b>The event queue is not here.</b> Windows raises straight through, because a
 *       {@code UiaRaise*} call returns without waiting for a client. Linux hands the signal to a
 *       D-Bus writer that already bounds its own backlog. macOS queues for the frame, because
 *       {@code NSAccessibilityPostNotification} is a cross-process call and one frame's difference
 *       can be hundreds of nodes wide. Those are three measured properties of three APIs, not three
 *       implementations of one idea.</li>
 *   <li><b>The element registry is not here.</b> §3.4 assigns a different owner to each: Windows
 *       needs concurrent maps because UI Automation calls a provider from several threads while the
 *       user-interface thread sleeps; macOS gets a plain map with a thread assertion because every
 *       callback there <em>is</em> the user-interface thread; Linux addresses nodes by object path
 *       and vends no objects at all.</li>
 *   <li><b>The listening gate is not here.</b> Windows asks the platform, Linux knows whether it is
 *       embedded, and macOS's only honest answer is "someone has asked" — which is why it is also
 *       the only one that wants a priming publish.</li>
 * </ul>
 *
 * <p><b>The snapshot is {@code volatile} for all three, and that is a small concession by one of
 * them.</b> Windows and Linux need it: the platform reads the tree on threads of its own. macOS
 * does not, because there the reader and the writer are the same thread (Finding 4) — but a
 * volatile read on the thread that wrote it is not a cost anything here can measure, and the
 * alternative is a field whose safety depends on which subclass is looking at it.
 *
 * <p>Internal to the backend. Nothing above it names this type: an application sees
 * {@link AccessibilityBridge}, and the backend installs whichever of these its platform has.
 */
public abstract class PlatformBridge implements AccessibilityBridge {

    private volatile AccessibleTree published = AccessibleTree.EMPTY;
    private volatile Host host;

    /**
     * Stores the snapshot. A subclass that has more to do overrides this and calls {@code super}
     * first, so that everything it then does reads the tree it was just handed.
     *
     * @param tree      the window as it now stands
     * @param reentrant whether the platform is on the stack
     */
    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        this.published = tree;
    }

    /**
     * Takes the scene-side half, replacing any host already held.
     *
     * <p>A scene can be bound over a live window and the outgoing host never learns it was, so
     * everything the bridge vended for the old tree is invalidated here rather than left pointing
     * at a tree that no longer exists.
     *
     * @param newHost what the scene answers with
     */
    @Override
    public void attach(Host newHost) {
        invalidateEverythingVended();
        this.host = newHost;
    }

    /** The window is going away: drops the tree and the host, then lets the platform half go. */
    @Override
    public void detach() {
        this.host = null;
        this.published = AccessibleTree.EMPTY;
        invalidateEverythingVended();
        releasePlatformHalf();
    }

    /**
     * Releases every object this bridge has handed the platform for the current tree.
     *
     * <p>Called on a rebind and on a detach, and on nothing else — in particular never from inside
     * a reentrant publish, where the caller is standing on what would be released.
     */
    protected void invalidateEverythingVended() {
    }

    /** Undoes whatever binding to the platform the subclass did: a subclass, a socket, a provider. */
    protected void releasePlatformHalf() {
    }

    /** @return the snapshot every platform answer is read from; never {@code null}. */
    public final AccessibleTree tree() {
        return published;
    }

    /** @return what the scene answers with, or {@code null} between a detach and an attach. */
    public final Host host() {
        return host;
    }
}
