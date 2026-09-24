package limn.backend;

/**
 * The bounds a window's size is kept within while the user resizes it, in logical points. A bound
 * of {@code 0} is open: {@link #NONE} lets the window be any size, and {@code new SizeLimits(480,
 * 360, 0, 0)} only stops it shrinking below 480&times;360.
 *
 * <p>A negative bound is taken as open, as {@link NativeWindow#setSizeLimits} has always taken it.
 * A maximum below its minimum is refused: the desktop would have to break one of the two.
 *
 * @param minWidth  the narrowest the user may make the window, or {@code 0}
 * @param minHeight the shortest, or {@code 0}
 * @param maxWidth  the widest, or {@code 0}
 * @param maxHeight the tallest, or {@code 0}
 */
public record SizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {

    /** No bound on either axis. */
    public static final SizeLimits NONE = new SizeLimits(0, 0, 0, 0);

    /**
     * @throws IllegalArgumentException when a maximum is set below the minimum on its axis
     */
    public SizeLimits {
        minWidth = Math.max(0, minWidth);
        minHeight = Math.max(0, minHeight);
        maxWidth = Math.max(0, maxWidth);
        maxHeight = Math.max(0, maxHeight);
        if (maxWidth > 0 && maxWidth < minWidth) {
            throw new IllegalArgumentException("maximum width " + maxWidth
                    + " is below the minimum " + minWidth);
        }
        if (maxHeight > 0 && maxHeight < minHeight) {
            throw new IllegalArgumentException("maximum height " + maxHeight
                    + " is below the minimum " + minHeight);
        }
    }

    /**
     * @param width a width in logical points
     * @return it, brought within the width bounds
     */
    public float clampWidth(float width) {
        return clamp(width, minWidth, maxWidth);
    }

    /**
     * @param height a height in logical points
     * @return it, brought within the height bounds
     */
    public float clampHeight(float height) {
        return clamp(height, minHeight, maxHeight);
    }

    private static float clamp(float value, int min, int max) {
        float atLeast = Math.max(value, min);
        return max > 0 ? Math.min(atLeast, max) : atLeast;
    }
}
