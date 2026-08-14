package limn.render3d.shader;

/**
 * The value types the neutral shader IR works with: the GLSL scalar/vector types
 * plus {@code sampler2D}. Backends map these onto their own type systems; the
 * {@link #glsl} name is what {@code GlslCodegen} emits.
 */
public enum ShaderType {
    FLOAT("float", 1),
    VEC2("vec2", 2),
    VEC3("vec3", 3),
    VEC4("vec4", 4),
    SAMPLER2D("sampler2D", 0);

    /** The GLSL type keyword. */
    public final String glsl;
    /** Scalar component count (0 for opaque types like samplers). */
    public final int components;

    ShaderType(String glsl, int components) {
        this.glsl = glsl;
        this.components = components;
    }

    /** The float/vector type with {@code n} components (1→float … 4→vec4). */
    public static ShaderType ofComponents(int n) {
        return switch (n) {
            case 1 -> FLOAT;
            case 2 -> VEC2;
            case 3 -> VEC3;
            case 4 -> VEC4;
            default -> throw new IllegalArgumentException("no float type with " + n + " components");
        };
    }
}
