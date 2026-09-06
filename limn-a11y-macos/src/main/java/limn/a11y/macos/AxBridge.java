package limn.a11y.macos;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

/**
 * The NSAccessibility bridge: what a macOS window gives a Limn scene so that VoiceOver can read it.
 *
 * <p><b>What exists here so far is the half that has no Objective-C in it</b> — the host, the
 * published snapshot, and the reentrancy bookkeeping §3.2 turns on. The element registry, the push
 * onto the content view, the attribute implementations and the notifications are the platform half
 * and land on top of this. {@link #isListening()} therefore answers {@code false} and this bridge
 * currently vends nothing; that is stated rather than hidden, because a bridge that claimed to be
 * listening while vending nothing would make a scene pay for a walk on every frame and hand the
 * result to no one.
 *
 * <p><b>The one rule to read before changing anything here.</b> On this platform every accessibility
 * callback arrives on the user-interface thread, inside the event pump (Finding 4). That is what
 * lets the registry be a plain map with a thread assertion instead of the concurrent structures
 * Windows needed — and it is exactly why reentrancy is the trap rather than a corner case. When the
 * platform asks a question that cannot wait for a frame, the scene rebuilds and publishes from
 * inside that callback, with AppKit standing on objects this bridge vended. A publish in that state
 * <b>releases nothing, re-pushes nothing and drains nothing</b> (§3.2): each of those would act on
 * the objects the caller is holding, and on macOS the consequence is a crash rather than a stale
 * reading. Everything deferred is owed by the next ordinary frame, which the scene has already asked
 * for.
 */
public final class AxBridge implements AccessibilityBridge {

    private Host host;
    private AccessibleTree tree = AccessibleTree.EMPTY;

    /**
     * Whether an obligation was deferred by a reentrant publish and is owed to the next ordinary
     * one. Read and written only on the user-interface thread, which on this platform is every
     * thread this class is ever touched from.
     */
    private boolean obligationsDeferred;

    @Override
    public boolean isListening() {
        // Not yet: nothing of ours has been handed to the platform, so nothing of ours can have
        // been asked. §6's honest gate on macOS is "someone has asked", and it opens in the
        // increment that adds the elements to ask about.
        return false;
    }

    @Override
    public boolean needsPrimingPublish() {
        // True, and macOS is the only platform where it is. The gate above cannot open until the
        // platform has been handed elements to ask about, which needs a published tree, which the
        // gate would otherwise be holding shut -- a cycle that never starts (§2.2). It is cut here,
        // at the cost of one tree walk per window on its first frame, for a window an assistive
        // technology may never touch.
        return true;
    }

    @Override
    public void publish(AccessibleTree published, boolean reentrant) {
        // The store is the whole of a reentrant publish. Everything else this method will grow --
        // releasing destroyed elements, re-pushing the root's children, draining the event queue --
        // is precisely what §3.2 forbids while the platform is on the stack.
        tree = published;
        if (reentrant) {
            obligationsDeferred = true;
            return;
        }
        obligationsDeferred = false;
    }

    @Override
    public void emit(AccessibleEvent event) {
        // Enqueue, never raise: a diff between two frames of a scrolling list can be hundreds of
        // nodes wide and every raise is a cross-process call. The queue and its collapse arrive
        // with the notifications; until then there is nothing to raise them to.
    }

    @Override
    public void attach(Host newHost) {
        host = newHost;
    }

    @Override
    public void detach() {
        host = null;
        tree = AccessibleTree.EMPTY;
        obligationsDeferred = false;
    }

    /** @return the snapshot every platform answer is read from; never {@code null}. */
    AccessibleTree tree() {
        return tree;
    }

    /** @return what the scene answers with, or {@code null} between a {@link #detach()} and an attach. */
    Host host() {
        return host;
    }

    /** @return whether a reentrant publish left work for the next ordinary frame. */
    boolean obligationsDeferred() {
        return obligationsDeferred;
    }
}
