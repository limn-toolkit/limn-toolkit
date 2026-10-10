package limn.scene;

import limn.graphics.Color;
import limn.graphics.Font;

/**
 * Appearance of the scene's hover tooltip panel. The toolkit renders tooltips but
 * has no theme of its own, so the components layer installs a supplier of this
 * (reading its {@code Theme} at paint time) via
 * {@link Scene#installTooltipStyle}. Without one, tooltips are not painted.
 *
 * <p>The box travels with the ink: a tooltip anchored to an XSMALL control gets an 11pt
 * label in a box padded for one, because leaving the padding pinned would put an 11pt label
 * inside a MEDIUM box, with padding at ~27% of the panel, the inverse of the whole design.
 *
 * @param fill   panel background
 * @param border panel outline
 * @param text   label color
 * @param font   label font
 * @param radius corner radius
 * @param padH   horizontal padding, per side
 * @param padV   vertical padding, per side
 * @param shadows the layers of the shadow the panel casts, the widest first; empty for none
 */
public record TooltipStyle(Color fill, Color border, Color text, Font font, float radius,
                           float padH, float padV, java.util.List<limn.graphics.Shadow> shadows) {

    public TooltipStyle {
        shadows = java.util.List.copyOf(shadows);
    }

    /** A tooltip that casts no shadow. */
    public TooltipStyle(Color fill, Color border, Color text, Font font, float radius,
                        float padH, float padV) {
        this(fill, border, text, font, radius, padH, padV, java.util.List.of());
    }

    /** @return how far past the panel its shadow can leave ink */
    public float shadowReach() {
        float reach = 0;
        for (limn.graphics.Shadow shadow : shadows) {
            reach = Math.max(reach, shadow.reach());
        }
        return reach;
    }
}
