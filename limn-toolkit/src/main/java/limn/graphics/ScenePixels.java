package limn.graphics;

import java.util.Objects;

/**
 * Scene-referred pixels read back from a {@link ReadableSurface}: RGBA floats, <em>linear light</em>,
 * <em>premultiplied</em> alpha, row-major, row 0 at the top. These are the numbers the renderer
 * wrote, with no display transform applied (no exposure, no tonemap, no sRGB encode), and channels
 * may exceed 1.0.
 *
 * <p>Deliberately not an {@link Image}, and this is the whole point of the type. {@code Image} is
 * eight bits per channel of display-encoded colour; putting scene-referred light into one would
 * clip every highlight and mislabel what survived, and the clip would be invisible until someone
 * looked at a washed-out sky and blamed a shader. Because this is a distinct type, no encoder
 * accepts it: there is no path from here to a PNG that silently loses the range. Use
 * {@link ReadableSurface#readDisplayReferred()} for anything a person will look at.
 *
 * <p>What this is for: comparing a render against a reference with a tolerance, feeding pixels to
 * something that applies its own display transform, or asserting in a test what a pass actually
 * wrote.
 *
 * <p>Alpha is premultiplied, exactly as the target stores it, so no channel is divided and the
 * question of what {@code alpha == 0} means never arises here: it means the pixel contributes
 * nothing, and RGB may still be non-zero (additive radiance over a transparent background).
 *
 * <p>Four floats per pixel: a 4K frame is about 132 MB. Read a sub-rectangle when a sub-rectangle
 * is what you need.
 */
public final class ScenePixels {

    private final int width;
    private final int height;
    private final float[] rgba;

    /**
     * @param width  pixels
     * @param height pixels
     * @param rgba   {@code width*height*4} floats, linear light, premultiplied, top-down
     */
    public ScenePixels(int width, int height, float[] rgba) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("size must be positive, got " + width + "x" + height);
        }
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != (long) width * height * 4) {
            throw new IllegalArgumentException(
                    "rgba length " + rgba.length + " != " + width + "x" + height + "x4");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    /** @return width in pixels */
    public int width() {
        return width;
    }

    /** @return height in pixels */
    public int height() {
        return height;
    }

    /** @return the raw float channels, four per pixel (do not mutate) */
    public float[] channels() {
        return rgba;
    }

    /**
     * @param x      column, 0 at the left
     * @param y      row, 0 at the top
     * @param channel 0 = red, 1 = green, 2 = blue, 3 = alpha
     * @return the linear, premultiplied channel value; may exceed 1
     * @throws IndexOutOfBoundsException if the coordinates or channel are outside the buffer
     */
    public float channel(int x, int y, int channel) {
        if (x < 0 || x >= width || y < 0 || y >= height || channel < 0 || channel > 3) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") channel " + channel
                    + " outside " + width + "x" + height);
        }
        return rgba[(y * width + x) * 4 + channel];
    }
}
