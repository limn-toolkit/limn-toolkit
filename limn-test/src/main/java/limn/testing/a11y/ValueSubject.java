package limn.testing.a11y;

import limn.scene.Widget;

/**
 * A widget of the {@code VALUE} shape (ADR 045 §1) as the value contract sees it: something that
 * publishes one number in a range, which the contract can build fresh, read the number of
 * through the widget's own API, and ask whether the number can be set at all.
 *
 * <p>The node under contract is the one carrying the value facet below the root, which may be
 * the widget itself (a slider) or a child of it (a split pane's divider).
 */
public interface ValueSubject {

    /** Builds a fresh widget at a value away from both bounds and returns the root to bind. */
    Widget<?> build();

    /** @return the widget {@link #build()} made last, whose node or descendant carries the value */
    Widget<?> widget();

    /** @return the number the widget's API says it holds now */
    double value();

    /** @return the least value the API allows */
    double min();

    /** @return the greatest value the API allows */
    double max();

    /** @return what one {@code INCREMENT} moves the API's number by */
    double step();

    /** @return true where the number is shown and never set (a progress bar) */
    boolean readOnly();

    /** Sets the number through the API, as an application would; unused where read-only. */
    void set(double value);

    /** @return how many times the widget's own change listener fired since {@link #build()} */
    int changes();
}
