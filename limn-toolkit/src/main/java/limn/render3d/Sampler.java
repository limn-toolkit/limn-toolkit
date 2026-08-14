package limn.render3d;

/**
 * How a texture is filtered and wrapped when sampled. Baked into the
 * {@link GpuTexture} at upload time (each texture object carries its own state;
 * no separate sampler objects yet). {@code mipmaps} builds and uses a mip chain
 * for minified sampling (trilinear when the min filter is {@link Filter#LINEAR}).
 */
public record Sampler(Filter minFilter, Filter magFilter, Wrap wrapS, Wrap wrapT, boolean mipmaps) {

    public enum Filter {NEAREST, LINEAR}

    public enum Wrap {REPEAT, CLAMP_TO_EDGE, MIRRORED_REPEAT}

    /** Trilinear, repeating: the sensible default for tiling color maps. */
    public static Sampler smooth() {
        return new Sampler(Filter.LINEAR, Filter.LINEAR, Wrap.REPEAT, Wrap.REPEAT, true);
    }

    /** Nearest, clamped, no mips: crisp texels for pixel-art / data textures. */
    public static Sampler pixelated() {
        return new Sampler(Filter.NEAREST, Filter.NEAREST, Wrap.CLAMP_TO_EDGE, Wrap.CLAMP_TO_EDGE, false);
    }
}
