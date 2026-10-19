package limn.scene;

/**
 * One of the six commands of an Edit menu, done by whichever widget has the keyboard.
 *
 * <p>A widget says which of them it can do now and does them, through
 * {@link Widget#onCanEdit} and {@link Widget#onEdit}; {@link Scene#canEdit} and
 * {@link Scene#edit} ask the focused widget and then its ancestors. A menu's item made for one,
 * {@code MenuItem.of(MenuItem.Role.COPY)}, is enabled and acts by them, so a Copy in a menu bar,
 * in a tool bar and on the keyboard are the same copy.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum EditCommand {
    /** Takes back the last edit. */
    UNDO,
    /** Does again the edit last taken back. */
    REDO,
    /** Moves the selection to the clipboard. */
    CUT,
    /** Copies the selection to the clipboard. */
    COPY,
    /** Puts the clipboard's content in, over the selection. */
    PASTE,
    /** Selects everything the widget holds. */
    SELECT_ALL
}
