package limn.graphics;

import java.util.Objects;

/**
 * What a piece of styled text is drawn in, asked for on every paint: a {@link Color}, which is
 * its own ink, or a colour said by meaning that the palette in force answers, as the components'
 * {@code Tone} is. Text styled {@code DANGER} is therefore drawn in whatever red the palette of the
 * moment calls danger, and follows the system turning dark, where a {@code Color} read off the
 * theme while the text was built would stay the colour of that moment.
 *
 * <p>An application can implement it for an ink of its own (a brand colour per tenant, say). It
 * is called on the UI thread, once per piece per paint, and should answer quickly.
 */
@FunctionalInterface
public interface Ink {

    /** @return what to draw with now */
    Paint paint();

    /**
     * An ink that is always {@code paint}: a gradient, or a colour by the same route as
     * {@code Color} itself.
     *
     * @param paint the paint to draw with
     * @return the ink
     */
    static Ink of(Paint paint) {
        Objects.requireNonNull(paint, "paint");
        return paint instanceof Color color ? color : () -> paint;
    }
}
