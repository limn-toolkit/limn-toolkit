package limn.components;

/**
 * How far a surface is lifted above what is around it. A widget says which of these it is, and
 * the theme says what that looks like: {@link Theme#elevation(Elevation)}.
 *
 * <p>Four, named for what the surface is and not numbered: a level that cannot be told from its
 * neighbour on a screen is a level to get wrong.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum Elevation {

    /** In the plane of what is around it: a pane, a window's content. Casts nothing. */
    FLAT,

    /** A part of the page set a little above it: a card with no edge, a bar that floats. */
    RAISED,

    /**
     * Covers what is under it for a while: a menu, the list of a combo box, a calendar, a
     * tooltip, a panel that slides in over content.
     */
    OVERLAY,

    /** Covers the window until it is answered: a dialog. */
    MODAL
}
