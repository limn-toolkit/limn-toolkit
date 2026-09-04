package limn.accessibility;

/**
 * A node that is a real top-level window.
 *
 * <p>Only a node that <em>is</em> a window carries this. A dialog rendered inside another window's
 * scene is not one: it cannot be closed, maximized or minimized by the desktop, and publishing
 * this facet on it would advertise four operations it does not have. Such a dialog carries
 * {@link Accessible.State#MODAL} and its platform's own is-a-dialog property instead.
 *
 * @param modal       whether this window blocks input to the windows it owns
 * @param canMaximize whether the desktop offers a maximize action
 * @param canMinimize whether it offers a minimize action
 * @param state       whether the window is currently normal, minimized or maximized
 */
public record WindowFacet(boolean modal, boolean canMaximize, boolean canMinimize,
                          WindowFacet.State state) {

    /** How a window is currently presented. */
    public enum State {
        /** Ordinary. */
        NORMAL,
        /** Minimized to whatever the desktop minimizes to. */
        MINIMIZED,
        /** Filling its work area. */
        MAXIMIZED
    }

    /** @throws NullPointerException if {@code state} is {@code null} */
    public WindowFacet {
        java.util.Objects.requireNonNull(state, "state");
    }
}
