package limn.graphics;

import java.util.Objects;

/**
 * An ink that is always one paint, equal to another of the same paint: so that two styles built
 * alike compare equal, and a widget given equal styled text keeps its layout.
 *
 * @param paint the paint
 */
record FixedInk(Paint paint) implements Ink {
    FixedInk {
        Objects.requireNonNull(paint, "paint");
    }
}
