package limn.transfer;

/**
 * What a drop does with what was dragged.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum DropAction {

    /** The target takes a copy and the source keeps its own. */
    COPY,

    /** The target takes it and the source lets it go. */
    MOVE,

    /** The target takes a reference to what stays with the source. */
    LINK
}
