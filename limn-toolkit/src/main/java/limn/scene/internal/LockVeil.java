package limn.scene.internal;

/**
 * Not API. A canvas that can lay the veil a window locked by a modal is dimmed with, in a blend of
 * its own that no {@link limn.graphics.BlendMode} offers: a pixel the window leaves clear stays
 * clear, an opaque one darkens by the veil's alpha, and a partly covered one darkens and gains
 * coverage, so that the desktop seen through a translucent window darkens with it.
 *
 * <p>For a pixel of premultiplied colour {@code C} and coverage {@code A} under a veil of alpha
 * {@code a}: {@code C' = C(1 - a)} and {@code A' = min(1, A(1 + a))}. Painted over, black at
 * {@code a} put coverage where the window had none; multiplied, it left a translucent pixel's
 * coverage alone, and the desktop through it was not dimmed at all. A canvas that is not one of
 * these is veiled with {@link limn.graphics.BlendMode#MULTIPLY}.
 */
public interface LockVeil {

    /**
     * Veils the whole canvas, every pixel already painted in this pass.
     *
     * @param alpha the veil's alpha, in (0, 1]
     */
    void fillLockVeil(float alpha);
}
