package limn.components;

import limn.graphics.Color;
import limn.graphics.Shadow;

import java.util.List;

/**
 * The shadow of one {@link Elevation} in a theme: two layers straight down, since the light is
 * above. The <em>key</em> layer is offset and wide, and says how far the surface is lifted; the
 * <em>ambient</em> layer is tight and sits under the surface's own edge, where it keeps the edge
 * when the key layer has faded.
 *
 * @param keyOffset      how far below the surface the key layer lies, in points
 * @param keyBlur        the key layer's soft edge
 * @param keyOpacity     how dark the key layer is, from 0 to 1
 * @param ambientBlur    the ambient layer's soft edge
 * @param ambientOpacity how dark the ambient layer is, from 0 to 1
 */
public record ElevationShadow(float keyOffset, float keyBlur, float keyOpacity,
                              float ambientBlur, float ambientOpacity) {

    /** No shadow: what {@link Elevation#FLAT} has in every theme. */
    public static final ElevationShadow NONE = new ElevationShadow(0, 0, 0, 0, 0);

    /** The most a number of a shadow may be, in points: past it a shadow is a fog. */
    public static final float MAX_POINTS = 96;

    public ElevationShadow {
        for (float points : new float[] {keyOffset, keyBlur, ambientBlur}) {
            if (!(points >= 0 && points <= MAX_POINTS)) {
                throw new IllegalArgumentException("a shadow's offset and blurs are between 0 and "
                        + MAX_POINTS + " points: " + points);
            }
        }
        for (float opacity : new float[] {keyOpacity, ambientOpacity}) {
            if (!(opacity >= 0 && opacity <= 1)) {
                throw new IllegalArgumentException("a shadow's opacity is between 0 and 1: " + opacity);
            }
        }
    }

    /** @return whether nothing is drawn for this */
    public boolean isNone() {
        return keyOpacity == 0 && ambientOpacity == 0;
    }

    /** @return the layers to draw, the wide one first; empty for none */
    public List<Shadow> layers() {
        if (isNone()) {
            return List.of();
        }
        return List.of(new Shadow(0, keyOffset, keyBlur, Color.BLACK.withAlpha(keyOpacity)),
                new Shadow(0, 0, ambientBlur, Color.BLACK.withAlpha(ambientOpacity)));
    }

    /** @return how far past the surface's box the shadow can leave ink */
    public float reach() {
        return isNone() ? 0 : Math.max(keyOffset + 1.5f * keyBlur, 1.5f * ambientBlur);
    }
}
