package limn.scene.internal;

import limn.graphics.Color;

/**
 * Not API. A canvas that can lay a modal's veil in a blend of its own that no
 * {@link limn.graphics.BlendMode} offers: a pixel the window leaves clear stays clear, an opaque one
 * is exactly what painting the veil over it gives, and a partly covered one darkens and gains
 * coverage, so that the desktop seen through a translucent window darkens with it. The veil of a
 * window a modal locks, and the veil an in-scene dialog lays over its scene, are laid this way.
 *
 * <p>For a pixel of premultiplied colour {@code C} and coverage {@code A} under a veil of colour
 * {@code S} and alpha {@code a}: {@code C' = S·a·A + C(1 - a)} and {@code A' = min(1, A(1 + a))}.
 * Painted over, the veil put coverage where the window had none: a transparent window's corners,
 * round a rounded card, turned into dark squares. Multiplied, it left a translucent pixel's
 * coverage alone, and the desktop through it was not dimmed at all.
 */
public interface VeilCanvas {

    /**
     * Veils a rectangle, in current coordinates, over every pixel already painted in this pass.
     *
     * @param x      left edge
     * @param y      top edge
     * @param width  width
     * @param height height
     * @param veil   the veil's colour and alpha
     */
    void fillVeil(float x, float y, float width, float height, Color veil);
}
