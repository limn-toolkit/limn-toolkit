package limn.render3d;

/**
 * CPU-side pixels for a 2D texture: tightly packed 8-bit RGBA, row-major from the
 * top-left, tagged with the {@link ColorSpace} the values are authored in. Upload
 * once with {@code Graphics3D.uploadTexture(data, sampler)} to get a
 * {@link GpuTexture}. Base-color maps are normally {@link ColorSpace#SRGB} (the
 * shader linearizes them); data maps (normal/metallic/roughness, added later) are
 * {@link ColorSpace#LINEAR}.
 */
public record TextureData(int width, int height, byte[] rgba8, ColorSpace colorSpace) {

    public TextureData {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture size must be positive: " + width + "x" + height);
        }
        if (rgba8.length != width * height * 4) {
            throw new IllegalArgumentException("rgba8 length " + rgba8.length
                    + " != " + width + "×" + height + "×4 (" + (width * height * 4) + ")");
        }
    }

    /** Convenience for a solid or generated buffer already in RGBA byte order. */
    public static TextureData rgba(int width, int height, byte[] rgba8, ColorSpace colorSpace) {
        return new TextureData(width, height, rgba8, colorSpace);
    }

    /**
     * A tangent-space normal map: rgb encoding {@code n × 0.5 + 0.5}, tagged
     * {@link ColorSpace#LINEAR} so nothing gamma-decodes it. Because the pipeline
     * decodes sRGB in the shader and never uses a GL sRGB internal format, that
     * tag is the only thing separating a data map from a colour map,
     * which is why it is worth a named factory rather than a fourth argument
     * somebody gets wrong.
     */
    public static TextureData normalMap(int width, int height, byte[] rgba8) {
        return new TextureData(width, height, rgba8, ColorSpace.LINEAR);
    }
}
