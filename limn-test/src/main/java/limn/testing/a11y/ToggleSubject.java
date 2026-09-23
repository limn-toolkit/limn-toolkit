package limn.testing.a11y;

import limn.scene.Widget;

/**
 * A widget of the {@code TOGGLE} shape (ADR 045 §1) as the toggle contract sees it: something
 * that publishes one node that is on or off, which the contract can build fresh, read the state
 * of through the widget's own API, and ask whether the flip is on offer.
 */
public interface ToggleSubject {

    /** Builds a fresh widget, off, and returns the root to bind. */
    Widget<?> build();

    /** @return the widget {@link #build()} made last, whose node or descendant carries the toggle */
    Widget<?> widget();

    /** @return whether the API says it is on now */
    boolean isOn();

    /** @return whether the flip is on offer at all (a chart's legend can be inert) */
    boolean operable();

    /** @return how many times the widget's own change listener fired since {@link #build()} */
    int changes();
}
