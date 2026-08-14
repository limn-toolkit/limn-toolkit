package limn.render3d;

/**
 * A vertex attribute semantic and its component count. The fixed
 * {@link #location} pairs each attribute with a shader input
 * ({@code layout(location = ...)}), so meshes and material shaders agree without
 * per-material wiring.
 */
public enum VertexAttribute {
    POSITION(0, 3),
    NORMAL(1, 3),
    UV0(2, 2),
    /**
     * Per-vertex tint in <b>linear</b> RGBA (not sRGB), with glTF {@code COLOR_0}
     * semantics, multiplied into the material's base colour, its alpha, and its
     * emissive. Not clamped: a component above 1 is a legitimate over-range tint,
     * and routing it through emissive is what lets one draw call carry a different
     * HDR colour per vertex. A mesh without this attribute shades as if every
     * vertex were opaque white.
     */
    COLOR(3, 4),
    /**
     * A second texture coordinate set: glTF {@code TEXCOORD_1}. Reaches the surface
     * function as {@code v_uv1}; the built-in materials ignore it, so it costs a mesh
     * that carries it nothing but the bandwidth.
     *
     * <p>What it is for is anything that samples one texture at two places and combines
     * the results: a lightmap beside a detail map, a terrain blend, or the cross-fade
     * between two cells of a sprite sheet. A mesh without it reads {@code (0, 0)}.
     */
    UV1(4, 2),
    /**
     * Four per-vertex floats with <b>no fixed meaning</b>, reaching the surface
     * function as {@code v_params}. The built-in materials never read them.
     *
     * <p>This is the custom vertex stream every renderer eventually grows, and it
     * exists because the alternative is worse: an application that needs one more
     * number per vertex otherwise smuggles it through a channel that already means
     * something (a colour component, an unused UV), and every later reader has to
     * know. A mesh without it reads {@code (0, 0, 0, 0)}, so zero should be the
     * identity of whatever a surface decides these mean.
     *
     * <p>Only useful together with {@link limn.render3d.Material.Surface}: nothing
     * else can read them.
     */
    PARAMS(5, 4),
    /**
     * Four more of the same, reaching the surface function as {@code v_params1}.
     *
     * <p><b>A second stream rather than a wider first one</b>, which is the whole
     * decision here. Widening {@link #PARAMS} to eight floats would cost every mesh
     * that carries it four floats per vertex it does not use, and a vertex attribute
     * is at most four components in the shading languages this compiles to; eight
     * would be two inputs whatever the enum said, so the only question is whether an
     * application can decline the second one. It can, this way.
     *
     * <p>It exists because {@link #PARAMS}'s own argument does not stop at four. An
     * application that runs out smuggles the fifth number through a channel that
     * already means something, and every later reader has to know; that is exactly as
     * true of the fifth as it was of the first. A mesh without this reads
     * {@code (0, 0, 0, 0)}, so zero should again be the identity.
     *
     * <p>Reach for {@link #PARAMS} first and fill it. Two half-used streams cost
     * bandwidth that one full one does not.
     */
    PARAMS1(6, 4);

    /** Shader input location (matches {@code layout(location = ...)}). */
    public final int location;
    /** Number of float components. */
    public final int components;

    VertexAttribute(int location, int components) {
        this.location = location;
        this.components = components;
    }
}
