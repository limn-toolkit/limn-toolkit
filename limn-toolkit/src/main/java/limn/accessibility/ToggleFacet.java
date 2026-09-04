package limn.accessibility;

/**
 * A node that carries a checked state: a checkbox, a switch, a check menu item, a toggle button.
 *
 * <p>It is a facet rather than a flag because checked-ness has three homes across the three
 * platforms &mdash; a pattern object on one, a state bit plus an action row on another, a value
 * attribute on the third &mdash; and each of those views is derived from this one record.
 *
 * @param state whether the node is off, on, or neither
 */
public record ToggleFacet(ToggleFacet.State state) {

    /** What a toggle can be. */
    public enum State {
        /** Unchecked. */
        OFF,
        /** Checked. */
        ON,
        /** Neither: some of what this node stands for is checked and some is not. */
        MIXED
    }

    /** @throws NullPointerException if {@code state} is {@code null} */
    public ToggleFacet {
        java.util.Objects.requireNonNull(state, "state");
    }
}
