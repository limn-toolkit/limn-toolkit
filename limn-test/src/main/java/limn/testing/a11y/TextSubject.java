package limn.testing.a11y;

import limn.scene.Widget;

/**
 * A widget of the {@code TEXT} shape (ADR 045 §1) as the text contract sees it: an editable
 * text the contract can build fresh, holding a short string, and read back through the widget's
 * own API.
 */
public interface TextSubject {

    /** Builds a fresh widget holding {@link #initialText()} and returns the root to bind. */
    Widget<?> build();

    /** @return the widget {@link #build()} made last: the text itself */
    Widget<?> widget();

    /** @return the string the widget was built with: at least four characters, one line */
    String initialText();

    /** @return the string the API holds now */
    String text();
}
