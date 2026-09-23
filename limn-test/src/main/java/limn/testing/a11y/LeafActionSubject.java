package limn.testing.a11y;

import limn.scene.Widget;

/**
 * A widget of the {@code LEAF_ACTION} shape (ADR 045 §1) as the leaf contract sees it: a
 * pressable leaf the contract can build fresh and count the presses of through the widget's
 * own handler.
 */
public interface LeafActionSubject {

    /** Builds a fresh widget that accepts a press and returns the root to bind. */
    Widget<?> build();

    /** @return the widget {@link #build()} made last: the leaf itself */
    Widget<?> widget();

    /** @return how many times the widget's own action handler fired since {@link #build()} */
    int presses();
}
