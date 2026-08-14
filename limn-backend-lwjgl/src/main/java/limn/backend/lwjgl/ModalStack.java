package limn.backend.lwjgl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Pure modality bookkeeping (no GL): a stack of active modals and the decision
 * of whether a given window's input is blocked. Windows are opaque identities;
 * the parent→owned relationship is supplied as a predicate, so this class is
 * unit-testable with plain tokens.
 *
 * <p>Rules:
 * <ul>
 *   <li>The <b>topmost</b> modal's {@linkplain Modal#surface() surface} is
 *       <b>never</b> blocked (see below). Every modal under it is.</li>
 *   <li>A non-modal window is blocked iff some active modal locks it: a
 *       <b>window-modal</b> (non-null parent) locks only its parent (and the
 *       parent's owned popups); a <b>toolkit-modal</b> (null parent) locks
 *       every non-modal window.</li>
 *   <li>A modal may name an <b>owner-exception</b> window it never blocks even
 *       when its scope would; in-scene (overlay) modals use it, and their host
 *       window stays interactive because the overlay blocks its own content.
 *       The exception covers <b>what that host owns</b> as well: a dropdown or a
 *       menu is the overlay's own content reaching outside the window to draw
 *       itself, not a sibling window the modal is meant to lock. Modals still
 *       below the top are unaffected; they are frozen before the exception is
 *       consulted.</li>
 * </ul>
 *
 * <p><b>The top modal is answerable by construction.</b> A modal's surface is the
 * window the user has to reach to dismiss it: itself for a native modal, its host
 * for an in-scene overlay. Exempting that surface is not a convenience; without
 * it the stack can deadlock, and did: a native dialog locks its parent window, the
 * app then raises an in-scene dialog <em>on that parent</em> (an unsaved-changes
 * prompt on the way out is the obvious way in), and now the older modal blocks the
 * newer one's only surface while the newer one blocks the older window. Two open
 * dialogs, neither reachable, and the only way out is killing the process.
 */
final class ModalStack {

    private record Modal(Object window, Object parent, Object ownerException) {
        /**
         * The window the user must reach to dismiss this modal: the modal window
         * itself, or (for an in-scene overlay, which has no window of its own)
         * the host that draws it.
         */
        Object surface() {
            return ownerException != null ? ownerException : window;
        }

        /**
         * Whether this modal deliberately leaves {@code candidate} interactive: the
         * host of an in-scene overlay, and anything that host owns. A popup of the
         * exempt surface is that surface drawing outside its own frame, so locking
         * it would make the overlay's own dropdowns unusable.
         */
        boolean exempts(Object candidate, BiPredicate<Object, Object> ownsTransient) {
            return ownerException != null
                    && (candidate == ownerException || ownsTransient.test(ownerException, candidate));
        }
    }

    private final List<Modal> stack = new ArrayList<>();

    /** Pushes a modal locking {@code parent} (null = toolkit-modal). */
    void push(Object window, Object parent) {
        push(window, parent, null);
    }

    /**
     * Pushes a modal locking {@code parent} (null = toolkit-modal) but never
     * locking {@code ownerException} nor any window it owns (null = none). This is
     * the shape of an in-scene modal, whose host keeps drawing its own dropdowns
     * and menus as separate windows while the overlay is up.
     */
    void push(Object window, Object parent, Object ownerException) {
        Objects.requireNonNull(window, "window");
        // A window already on the stack is a programming error, not a re-raise. The old
        // behaviour moved it to the top AND adopted the new parent and exception, silently:
        // the caller believed it had pushed a second modal, the stack held one, and popping
        // either released the only entry. No shipped caller ever wanted it.
        for (Modal m : stack) {
            if (m.window == window) {
                throw new IllegalStateException("window is already modal: " + window);
            }
        }
        stack.add(new Modal(window, parent, ownerException));
    }

    /** Removes {@code window} from the stack. */
    void pop(Object window) {
        stack.removeIf(m -> m.window == window);
    }

    /**
     * Removes every modal whose {@linkplain #push(Object, Object, Object)
     * owner-exception} is {@code owner}. It is called when that host window closes,
     * since an in-scene modal has no window of its own to close.
     *
     * @return whether anything was removed
     */
    boolean removeOwnedBy(Object owner) {
        return owner != null && stack.removeIf(m -> m.ownerException == owner);
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    /** @return the topmost (interactive) modal window, or {@code null} if none */
    Object topModal() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1).window;
    }

    /** @return whether {@code window} is one of the active modals */
    boolean isModal(Object window) {
        for (Modal m : stack) {
            if (m.window == window) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param window        the window whose input we are deciding on
     * @param locks         {@code locks.test(parent, w)} is true iff {@code w} is
     *                      {@code parent} or one of its owned popups: the SCOPE a
     *                      window-modal locks, which covers everything the parent owns
     * @param ownsTransient {@code ownsTransient.test(host, w)} is true iff {@code w} is a
     *                      popup {@code host} registered as its own content. Narrower than
     *                      {@code locks} on purpose: it is the EXEMPTION, and exempting
     *                      everything an in-scene modal's host owns left a non-modal dialog
     *                      clickable underneath the modal.
     *
     *                      <p><b>Neither predicate is transitive, and that is a decision.</b>
     *                      A popup of a popup is not owned by the grandparent. Menus keep a
     *                      whole cascade inside one window, so the shape does not arise;
     *                      {@code ModalStackTest} asserts it rather than leaving it to be
     *                      discovered by the first component that spends a window per level.
     * @return whether {@code window}'s input is currently blocked by a modal
     */
    boolean isBlocked(Object window, BiPredicate<Object, Object> locks,
                      BiPredicate<Object, Object> ownsTransient) {
        if (stack.isEmpty()) {
            return false;
        }
        // The top modal's surface is always interactive, whatever is below says.
        // This is the anti-deadlock rule; see the class docs. Identity only: the
        // surface's owned windows must NOT be exempted here, because a dialog
        // registers its window with its owner (to close with it) and this test
        // runs before the one below that freezes every modal under the top;
        // widening it would leave a stale dialog answerable in front of the new one.
        if (window == stack.get(stack.size() - 1).surface()) {
            return false;
        }
        // Every other modal window is below the top, so it is blocked.
        if (isModal(window)) {
            return true;
        }
        // A non-modal window is blocked if any modal locks it.
        for (Modal m : stack) {
            if (m.exempts(window, ownsTransient)) {
                continue; // this modal keeps its host window, and its host's popups, interactive
            }
            if (m.parent == null) {
                return true; // toolkit-modal locks every non-modal window
            }
            if (locks.test(m.parent, window)) {
                return true;
            }
        }
        return false;
    }
}
