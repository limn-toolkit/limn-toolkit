package limn.testing.a11y;

import limn.scene.Widget;

/**
 * A widget of the {@code POPUP_OWNER} shape as the popup-owner contract sees it:
 * something that opens a popup, which the contract can build fresh, closed, and read the open
 * state of through the widget's own API.
 */
public interface PopupOwnerSubject {

    /** Builds a fresh widget, closed, and returns the root to bind. */
    Widget<?> build();

    /** @return the widget {@link #build()} made last: the owner itself */
    Widget<?> widget();

    /** @return whether the API says the popup is open now */
    boolean isOpen();

    /** Closes the popup through the API, as an application would. */
    void close();
}
