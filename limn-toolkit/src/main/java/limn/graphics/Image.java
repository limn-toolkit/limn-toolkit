package limn.graphics;

import java.util.Objects;

/**
 * An immutable, backend-independent bitmap: RGBA8 pixels, straight (non
 * premultiplied) alpha, row-major, row 0 at the <em>top</em>. Decoded once
 * (see {@link Images}) and drawn via {@link Canvas#drawImage}; the GPU texture
 * is created lazily by each window's renderer, so one {@code Image} works
 * across multiple windows (whose GL contexts are not shared).
 *
 * <p>Object identity is the texture-cache key; reuse the same instance rather
 * than decoding the same asset repeatedly.
 */
public final class Image {

    private final int width;
    private final int height;
    private final byte[] rgba;

    /**
     * @param width  pixels
     * @param height pixels
     * @param rgba   {@code width*height*4} bytes, straight alpha, top-down
     */
    public Image(int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image size must be positive, got " + width + "x" + height);
        }
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != width * height * 4) {
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

    /** @return the raw RGBA bytes (do not mutate) */
    public byte[] pixels() {
        return rgba;
    }
}
