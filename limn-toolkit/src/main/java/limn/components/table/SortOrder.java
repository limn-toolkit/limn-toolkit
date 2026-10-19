package limn.components.table;

/**
 * How a {@link Table} is ordered on one of its columns.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum SortOrder {
    /** The model's own order. */
    NONE,
    /** Smallest first. */
    ASCENDING,
    /** Largest first. */
    DESCENDING;

    /**
     * What a click on a header cycles to: ascending, then descending, then the model's order.
     *
     * @return the next order in that cycle
     */
    public SortOrder next() {
        return switch (this) {
            case NONE -> ASCENDING;
            case ASCENDING -> DESCENDING;
            case DESCENDING -> NONE;
        };
    }
}
