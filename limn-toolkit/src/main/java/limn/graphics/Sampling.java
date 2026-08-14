package limn.graphics;

/**
 * How {@link Canvas} image drawing samples texels when an {@link Image} is
 * drawn at a size other than 1:1. Part of the canvas state
 * ({@link Canvas#setSampling}), saved/restored with {@link Canvas#save()}.
 *
 * <p>Sampling is realized per GPU texture: drawing the same Image with both
 * modes in one frame reconfigures its texture between draws (a batch break
 * each time it flips), so pick one mode per image where possible.
 */
public enum Sampling {

    /** Bilinear filtering with mipmaps: photos, icons, scaled artwork. The default. */
    SMOOTH,

    /**
     * Nearest-neighbor: pixel art stays crisp when scaled up instead of
     * blurring; each source pixel becomes a sharp block.
     */
    PIXELATED
}
