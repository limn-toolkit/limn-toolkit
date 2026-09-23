package limn.testfixtures;

import limn.scene.Widget;

/**
 * A widget that names itself as its own type, for a test that writes one inline: an anonymous
 * class cannot be its own type argument, so {@code new Widget() { ... }} has no type to give, and
 * {@code new PlainWidget() { ... }} does. Not API.
 */
public abstract class PlainWidget extends Widget<PlainWidget> {

    /** For subclasses, anonymous ones included. */
    protected PlainWidget() {
    }
}
