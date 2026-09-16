package limn.backend;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;

/**
 * What a backend gives a window so that a platform's assistive technology can read it.
 *
 * <p><b>The whole seam is twelve members and one direction each.</b> Outbound, the scene tells a
 * bridge things and asks it three questions, and nothing in the toolkit calls a bridge in any other
 * way. Inbound, everything a platform asks of the toolkit arrives through the four members of
 * {@link Host}. Nothing in a bridge ever touches a widget, a scene or a window: it is handed an
 * immutable tree and it answers from that.
 *
 * <p>That is not tidiness, it is what the three platforms measured out. On one of them the platform
 * calls a provider from several threads of its own while the user-interface thread sleeps, holding
 * no lock on anyone's behalf. On another the platform never calls at all and wants a whole tree in
 * one message. On the third the platform's thread <em>is</em> the user-interface thread. A snapshot
 * published through a single volatile write is the only shape that serves all three, and a bridge
 * that read live state off a foreign thread would get a silent data race rather than an exception:
 * the toolkit's thread confinement covers property setters, tree mutation, focus and overlays, and
 * not the getters, the layout, the damage or the observer registrars that a bridge is tempted by.
 *
 * <p>Every member has a default, and {@link #NONE} takes all of them. A window with no
 * accessibility — a test double, an embedded surface, a backend on its first day — gets exactly
 * zero cost by implementing nothing.
 */
public interface AccessibilityBridge {

    /** No accessibility: every question answered with a constant, every statement discarded. */
    AccessibilityBridge NONE = new AccessibilityBridge() {
    };

    /**
     * Whether any assistive technology is listening.
     *
     * <p>Asked once per frame on the publish path, and on the two rare paths that have to know
     * whether spending a frame is worth it at all. It must therefore be cheap: one platform call,
     * or a flag the bridge already keeps.
     *
     * <p><b>It is never "did a client ask us something recently."</b> A gate of that shape inverts
     * the contract on all three platforms, because a platform event is a push a client waits on:
     * one of the three screen readers registers for a focus change and then calls nothing at all
     * until it fires. Tab twice quickly with such a gate and the toolkit goes silent — the client
     * only asks after an event, and the event only fires after the client asks.
     *
     * @return whether anything is reading this window
     */
    default boolean isListening() {
        return false;
    }

    /**
     * Whether this bridge needs one tree on the scene's first frame even though nothing is
     * listening yet, because its own listening gate cannot open until it has elements to offer.
     *
     * <p>Asked once, when a scene binds. True where the honest gate is "someone has asked", which
     * cannot open before the platform has been handed something to ask about: macOS, which has no
     * equivalent of "is anyone listening" at all, and Windows, whose process-wide flag is only
     * reliable in the negative and whose per-window half needs an element for a client to reach.
     * False on Linux, which can ask its desktop directly and so pays no walk for a window an
     * assistive technology never touches.
     *
     * @return whether this bridge is owed one tree on the first frame regardless
     */
    default boolean needsPrimingPublish() {
        return false;
    }

    /**
     * Whether this bridge is owed the window's own node the moment a scene binds, because it can be
     * asked whether this window has accessibility at all before the first frame has run.
     *
     * <p>Asked once, when a scene binds, and true on one platform. There a client asks the window
     * itself, in a message, and a window that answers "nothing here" is not asked again: the client
     * subscribes to no events, and every event raised for the rest of that window's life is
     * delivered to nobody while the window keeps answering every question it is asked. Measured on
     * the Windows 11 guest, 2026-09-16: the reader's first ask reaches the window 36-116 ms after
     * the bridge goes in front of the window procedure and is answered 50-141 ms in, its second
     * follows within milliseconds, and the first frame publishes 305-523 ms later — so ten runs of
     * one build split four spoken and six silent on nothing but whether a second ask happened to
     * arrive after that first publish (it did in two runs, ~200 ms late, and in two more the reader
     * simply never asked twice). One {@code 0} answer before the first real one and the reader
     * speaks; two and it is silent, in ten runs out of ten
     * (`.claude/pending/2026-09-13/readings/phase5-windows-diagnosis/evidence-table.txt`).
     *
     * <p><b>What it is owed is the window node alone, and not the scene.</b> No layout has run at a
     * bind, so every widget is a zero-size rectangle at the origin and a tree of those is worse than
     * none — it reads perfectly and hit-tests nowhere (ADR 039 §5.2, and §2.2 for the platform that
     * measured it). The window's role, its title and the size it already has are true at that
     * instant, and the identifier they are published under is the one every later walk reuses, so
     * the element a client subscribes to here is the element the contents arrive under.
     *
     * @return whether this bridge is owed the window's own node the moment a scene binds
     */
    default boolean needsRootBeforeTheFirstFrame() {
        return false;
    }

    /**
     * Hands over a fresh whole-window snapshot. User-interface thread.
     *
     * <p>{@code reentrant} is true when the scene built this tree from inside the platform's own
     * callback, with the platform on the stack holding elements this bridge vended. A reentrant
     * publish <b>stores the tree and answers from it, and does nothing else</b>: it must not
     * destroy or release anything in its element registry, must not re-push the top of its tree,
     * and must not drain its event queue — each of those would act on the objects the caller is
     * standing on. Everything it defers is owed by the next ordinary frame, which the scene has
     * already asked for.
     *
     * @param tree      the window as it now stands
     * @param reentrant whether the platform is on the stack
     */
    default void publish(AccessibleTree tree, boolean reentrant) {
    }

    /**
     * One coalesced event, naming a node in the tree just published. User-interface thread.
     *
     * <p><b>Enqueue it; do not raise it.</b> A difference between two frames of a scrolling list or
     * a dragged slider can be hundreds of nodes wide, and every raise is a cross-process call: a
     * bridge that raised them here would spend the frame budget inside the platform, which is
     * precisely the stall that makes a window unreadable to the user it is being read to.
     *
     * @param event what happened
     */
    default void emit(AccessibleEvent event) {
    }

    /**
     * The frame's accessibility step is over: everything this frame had to say has been
     * {@linkplain #emit emitted}. User-interface thread, once per frame, whether or not the frame
     * published — a frame that only drained announcements, or one that follows a
     * {@linkplain #publish reentrant publish} and finds nothing left to walk, ends too.
     *
     * <p><b>It is the one moment a bridge that posts on the user-interface thread can post.</b>
     * A queue drained at the top of the next publish instead holds every event of a change until
     * the tree changes again: on a still window the last thing that happened is never told at all,
     * and everything else is told one change late, about a tree that has already moved on
     * (MACOS-NEW-8). The obligations a reentrant publish deferred are owed here too, because the
     * frame that reentrant publish asked for need not publish anything.
     *
     * <p>Never called from inside a reentrant publish, nor from anywhere the platform is on the
     * stack: it runs at the end of the frame step, where the ordinary publish runs.
     *
     * <p><b>It is also the publish boundary a bridge that raises elsewhere reads</b> (amended
     * 2026-09-16): a bridge draining on a thread of its own cannot see where one frame's events
     * end and the next frame's begin, and the order it owes an assistive technology after a
     * collapse — the shape of the tree first, then where the user is — is exactly that boundary.
     * Such a bridge hands the marker to its own thread rather than acting here. The no-op default
     * is for a bridge with neither obligation.
     */
    default void frameEnded() {
    }

    /**
     * Hands the bridge the scene-side half, replacing any host it already held.
     *
     * <p>A scene can be bound over a live window and the outgoing one never learns it was, so the
     * bridge is the only object that knows there was a tree to close. That is why the window pair
     * is raised here rather than by the caller: an implementation empties its element registry and
     * raises a window-closed for the host it is replacing, if there was one, then raises a
     * window-opened for the incoming one. On a first attach only the second is raised.
     *
     * <p>It builds and pushes no tree, on any platform. At this moment the scene has never laid
     * out, so every box in it would be a zero-size rectangle at the origin.
     *
     * @param host what the scene answers with
     */
    default void attach(Host host) {
    }

    /** The window is going away: raises a window-closed, then empties whatever it was holding. */
    default void detach() {
    }

    /**
     * What the scene gives a bridge: the three ways to ask for a tree, and the one way to act.
     *
     * <p>Inbound calls go platform to bridge to host; outbound calls go scene to bridge. A bridge
     * holds exactly one object, and it is the host it was handed.
     */
    interface Host {

        /**
         * Asks for a fresh tree on the next frame, and buys the frame. Safe from any thread; for a
         * client attaching while the scene is idle.
         */
        void requestRepublish();

        /**
         * Asks for the published tree to be re-stamped with the window's current origin, scale and
         * positioning support, and buys the frame. Safe from any thread.
         *
         * <p>Two methods and not one, because they set different flags and the difference is the
         * whole saving: a republish costs a walk and a comparison, a re-stamp costs four numbers.
         * Every node's box is scene-local, so a window that moved has not moved one of them, and a
         * drag that asked for a republish would re-walk the entire tree on every callback the
         * compositor sends to discover exactly that.
         */
        void requestRestamp();

        /**
         * Rebuilds and publishes now, and returns the tree.
         *
         * <p>User-interface thread only, and only from inside the platform's own pump: the two
         * points where a platform asks a question that cannot wait for a frame. It walks and
         * describes; it never lays out and never renders, so it is safe from inside a native
         * callback.
         *
         * <p>It publishes {@linkplain #publish reentrantly}, so the bridge defers every registry
         * obligation — and it therefore <b>asks for a frame whenever it published anything</b>,
         * whatever the layout was doing, because a deferred obligation needs a frame that is going
         * to happen. The only call that asks for nothing is the one that published nothing because
         * nothing was dirty, and that call defers nothing either.
         *
         * <p>On a scene that has never laid out it publishes nothing and asks for a frame, rather
         * than describing geometry that does not exist: a window whose every control is a zero-size
         * rectangle in the corner is worse than a window with nothing in it, because it looks like
         * an answer. What it returns there is whatever is currently published, which is the empty
         * tree — or, for a bridge that answered {@link #needsRootBeforeTheFirstFrame}, the window's
         * own node, which is the one thing about a window that is true before it lays out.
         *
         * @return the currently published tree
         */
        AccessibleTree republishNow();

        /**
         * Performs one action on one node. Safe from any thread, and the only path from a platform
         * into toolkit state.
         *
         * <p>It resolves nothing itself. It checks that the identifier is in the currently
         * published tree — an immutable read, safe from anywhere, and the one refusal that can
         * honestly be immediate — posts the identifier, and returns. The map from an identifier to
         * the widget that owns it is the scene's and is read only on the user-interface thread,
         * which is also where every real precondition is re-checked: that the node still exists,
         * that its widget is still attached, enabled with every ancestor, showing, and not behind
         * a modal.
         *
         * <p>There is no bounded wait anywhere here, and that is a decision rather than caution. On
         * one platform the calling thread <em>is</em> the user-interface thread, so a wait would be
         * an instant self-deadlock; and nothing could bound such a wait honestly in any case, since
         * that thread parks with no drain for the whole life of a native file chooser.
         *
         * @param nodeId the node to act on
         * @param action what to do
         * @param arg    the argument, or {@link Accessible.Argument#NONE}
         * @return whether the action was <b>accepted</b> — never whether it is done
         */
        boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg);
    }
}
