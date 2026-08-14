package limn.render3d;

import limn.graphics.BlendMode;
import limn.math.Vec3;
import limn.math.Vec4;

/**
 * How a surface is shaded. Ships built-in {@link Unlit}, {@link SimpleLit} and
 * physically-based {@link Pbr} models, generated from the neutral shader IR, plus
 * two ways for an application to shade something the built-ins cannot express.
 *
 * <p>The two are not interchangeable and the choice is usually easy.
 * {@link Surface} supplies the four surface expressions in the same neutral IR the
 * built-ins are written in: it is <b>lit by the engine</b> (every light on the
 * pass, the shadow map, the environment), and it compiles to every
 * {@link limn.render3d.shader.TargetProfile} the toolkit supports. {@link Raw} is
 * hand-written GLSL that replaces the whole program: it sees no lights and no
 * textures, and it is, by definition, non-portable.
 *
 * <p>Reach for {@code Surface} for a material; reach for {@code Raw} for something
 * that is not one.
 */
public sealed interface Material
        permits Material.Unlit, Material.SimpleLit, Material.Pbr, Material.Surface, Material.Raw {

    /**
     * How this surface composites with what is already in the target, or
     * {@code null} (the default) for an <b>opaque</b> one.
     *
     * <p>Opaque draws are issued first, in submission order; they write depth and
     * cast shadows. Blended draws follow, also in submission order, and they test
     * depth but do not write it, so overlapping transparent surfaces all show
     * instead of the nearest one hiding the rest. Nothing sorts them: a pass
     * places every mesh exactly where the caller put it, and back-to-front order
     * (where it matters, which is {@link BlendMode#NORMAL} and not
     * {@link BlendMode#ADDITIVE}) is the caller's to arrange, inside its own
     * geometry as much as between draws.
     *
     * <p>The modes and their equations are the 2D canvas's ({@link BlendMode}):
     * an additive 3D surface and an additive 2D sprite composite identically,
     * because both pipelines are premultiplied.
     *
     * <p>There is deliberately no alpha-test mode. Cutout foliage stays opaque
     * (depth-writing and shadow-casting) and discards in its surface function.
     */
    default BlendMode blend() {
        return null;
    }

    /** Whether this surface writes depth and casts shadows, i.e. {@link #blend()} is unset. */
    default boolean isOpaque() {
        return blend() == null;
    }

    /** A flat/vertex color, unaffected by lights. */
    record Unlit(Vec4 color, BlendMode blend) implements Material {

        /** An opaque flat color. */
        public static Unlit of(float r, float g, float b) {
            return new Unlit(new Vec4(r, g, b, 1), null);
        }

        /** {@code null} restores opaque. */
        public Unlit blend(BlendMode blend) {
            return new Unlit(color, blend);
        }
    }

    /** Lambert diffuse from the pass's single directional light, over a base color. */
    record SimpleLit(Vec4 baseColor) implements Material {
        /** A flat-lit material of the given sRGB colour. */
        public static SimpleLit of(float r, float g, float b) {
            return new SimpleLit(new Vec4(r, g, b, 1));
        }
    }

    /**
     * A tangent-space normal map and how far to lean on it. {@code scale} without a
     * texture would mean nothing, so they travel together; 1 is full strength
     * (glTF's {@code normalTexture.scale} default), 0 is the geometric normal, and
     * values above 1 exaggerate.
     *
     * <p>The texture must be uploaded from {@link ColorSpace#LINEAR} data (see
     * {@link TextureData#normalMap}) because the shader does not gamma-decode it.
     * Feed it an sRGB-tagged buffer and the surface tilts subtly and consistently
     * wrong, which looks like a lighting bug rather than an authoring one.
     *
     * <p><b>Orientation: green points up.</b> The map is tangent-space with +X toward
     * the right of the image and <b>+Y toward the top of the image</b>, the OpenGL
     * convention, which is what glTF mandates and what art tools export under that
     * name. A map exported as "DirectX" has its green channel inverted and must be
     * flipped before it gets here.
     *
     * <p>This is worth stating because the frame the shader builds does not enforce
     * it by itself. There is no TANGENT attribute; the frame comes from screen-space
     * derivatives, whose bitangent naturally runs along <i>increasing v</i>, which is
     * <i>down</i> an image. The shader negates it for exactly this reason. Getting the
     * sign wrong lights every surface from the wrong vertical direction and looks like
     * a misplaced light rather than a texture problem, so it is expensive to find.
     */
    record NormalMap(GpuTexture texture, float scale) {
        public NormalMap {
            java.util.Objects.requireNonNull(texture, "texture");
        }

        /** Full strength. */
        public static NormalMap of(GpuTexture texture) {
            return new NormalMap(texture, 1f);
        }
    }

    /**
     * Metallic-roughness PBR (Cook-Torrance GGX), lit by every {@link Light} on the
     * pass plus a flat ambient term. {@code baseColor} is authored in sRGB (the
     * shader linearizes it) and, when {@code baseColorTexture} is set, modulated by
     * that texture, including its alpha. {@code metallic}/{@code roughness} are in
     * [0,1]; {@code emissive} is added after lighting (linear). A {@code normalMap}
     * perturbs the shading normal in tangent space; {@code null} shades with the
     * interpolated geometric normal.
     *
     * <p>A mesh carrying {@link VertexAttribute#COLOR} modulates base colour
     * <em>and</em> emissive per vertex, linear and unclamped; see
     * {@link limn.render3d.shader.StandardSurface}.
     */
    record Pbr(Vec4 baseColor, float metallic, float roughness, Vec3 emissive,
               GpuTexture baseColorTexture, NormalMap normalMap, BlendMode blend)
            implements Material {

        /** An opaque dielectric of the given sRGB color (roughness 0.5, no texture). */
        public static Pbr of(float r, float g, float b) {
            return new Pbr(new Vec4(r, g, b, 1), 0f, 0.5f, Vec3.ZERO, null, null, null);
        }

        /** A copy with a different metallic value, {@code 0} dielectric to {@code 1} metal. */
        public Pbr metallic(float metallic) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }

        /** A copy with a different roughness, {@code 0} mirror to {@code 1} fully diffuse. */
        public Pbr roughness(float roughness) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }

        /** A copy that also emits light, in linear scene-referred units, unaffected by lighting. */
        public Pbr emissive(Vec3 emissive) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }

        /** A copy with a different base colour; its w is opacity. */
        public Pbr baseColor(Vec4 baseColor) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }

        /** A copy sampling {@code texture} for base colour, modulated by the base colour value. */
        public Pbr textured(GpuTexture texture) {
            return new Pbr(baseColor, metallic, roughness, emissive, texture, normalMap, blend);
        }

        /** {@code null} clears the map and shades with the geometric normal. */
        public Pbr normalMapped(NormalMap normalMap) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }

        /** {@code null} restores opaque. */
        public Pbr blend(BlendMode blend) {
            return new Pbr(baseColor, metallic, roughness, emissive, baseColorTexture,
                    normalMap, blend);
        }
    }

    /**
     * A surface the application builds itself, out of the same neutral shader IR the
     * built-in materials are generated from, and lit by the same core.
     *
     * <p><b>This is the difference from {@link Raw}, and it is the whole point.</b>
     * Raw GLSL is a shortcut past the engine: it sees no lights, no shadow map, no
     * environment, and it is one shading language forever. A {@code Surface} is
     * spliced into the same {@code main()} the built-in metallic-roughness material
     * is, so it reaches every light on the pass, the shadow map and the IBL, and it
     * compiles to every {@link limn.render3d.shader.TargetProfile} the toolkit
     * supports. What it replaces is only the four surface expressions: base colour,
     * metallic, roughness and emissive, plus the shading normal.
     *
     * <p>Reach for it when the built-in material cannot express what a surface *is*:
     * a sprite sheet cross-fading between two cells, an erosion driven by a mask, a
     * scrolling flow map. Not for a different lighting model: the BRDF belongs to the
     * engine, and a material that wanted its own would be describing a different
     * renderer.
     *
     * <p><b>Inputs are named, and the names are the contract.</b> Each
     * {@link Texture} and {@link Value} is declared to the generated shader under its
     * own name, and the surface's IR reaches it with an {@link limn.render3d.shader.Expr.Ref}
     * carrying that name and the matching type. A name that is declared and never
     * referenced is harmless; a name referenced and never declared fails to compile,
     * which is the right moment to find out.
     *
     * <p><b>{@code key} identifies the program, not the material.</b> Two surfaces
     * with the same key must generate the same shader: the key is what the compiled
     * program is cached under, and the values and textures are free to differ per
     * draw. Getting this wrong shows up as a material silently rendering with another
     * one's shader. Note it is deliberately <em>not</em> the record's identity:
     * {@link Raw} caches by identity, so an application that rebuilds its material
     * record every frame links a fresh program every frame, and this must not repeat
     * that.
     *
     * <p>Per-vertex data beyond position, normal, UV and colour reaches a surface
     * through {@link VertexAttribute#UV1}, {@link VertexAttribute#PARAMS} and
     * {@link VertexAttribute#PARAMS1}, as {@code v_uv1}, {@code v_params} and
     * {@code v_params1}. The framework also declares {@code v_worldPos},
     * {@code v_normal}, {@code v_uv} and {@code v_color}. Fill {@code PARAMS} before
     * reaching for {@code PARAMS1}: two half-used streams cost bandwidth one full one
     * does not.
     *
     * @param key      the program cache key; equal keys must mean equal {@code outputs}
     * @param outputs  the surface expressions, in the neutral IR
     * @param textures named samplers the IR may reference, bound in order
     * @param values   named {@code vec4} uniforms the IR may reference
     * @param blend    {@code null} for an opaque surface
     */
    record Surface(String key, limn.render3d.shader.SurfaceOutputs outputs,
                   java.util.List<Texture> textures, java.util.List<Value> values,
                   BlendMode blend) implements Material {

        public Surface {
            java.util.Objects.requireNonNull(key, "key");
            java.util.Objects.requireNonNull(outputs, "outputs");
            textures = java.util.List.copyOf(textures);
            values = java.util.List.copyOf(values);
        }

        /** An opaque surface with no inputs of its own. */
        public static Surface of(String key, limn.render3d.shader.SurfaceOutputs outputs) {
            return new Surface(key, outputs, java.util.List.of(), java.util.List.of(), null);
        }

        /** A copy with different inputs; the key and therefore the program are unchanged. */
        public Surface with(java.util.List<Texture> textures, java.util.List<Value> values) {
            return new Surface(key, outputs, textures, values, blend);
        }

        /** {@code null} restores opaque. */
        public Surface blend(BlendMode blend) {
            return new Surface(key, outputs, textures, values, blend);
        }

        /**
         * A named sampler. The texture may be null, in which case the backend binds
         * its 1×1 white default so the sampler stays complete; a surface that samples
         * an unbound unit is undefined behaviour on some drivers and black on others.
         */
        public record Texture(String name, GpuTexture texture) {
            public Texture {
                java.util.Objects.requireNonNull(name, "name");
            }
        }

        /** A named {@code vec4} uniform. */
        public record Value(String name, Vec4 value) {
            public Value {
                java.util.Objects.requireNonNull(name, "name");
                java.util.Objects.requireNonNull(value, "value");
            }

            /** A scalar in x, the rest zero: the usual shape for a lone knob. */
            public static Value of(String name, float x) {
                return new Value(name, new Vec4(x, 0, 0, 0));
            }
        }
    }

    /**
     * Custom GLSL. The backend provides the standard uniforms {@code u_mvp}
     * (mat4), {@code u_model} (mat4) and {@code u_normalMatrix} (mat3) and the
     * vertex attributes at their fixed locations ({@link VertexAttribute}).
     * <b>Non-portable</b>: opts out of the shader-IR's multi-target guarantees.
     *
     * <p>A shortcut, not a back door: the pass's Frame and Lights uniform blocks
     * are bound to the built-in PBR program only, so a raw shader cannot see the
     * lights, the shadow map or the environment at all. Reach for it for effects
     * that do not need them.
     *
     * <p><b>Color space is part of this contract:</b> the fragment
     * writes into a {@link RenderTarget}, whose contents are linear light,
     * premultiplied, scene-referred. Write linear: decode any authored sRGB
     * constant (see {@link ColorSpace}) and premultiply by alpha. A raw shader
     * that writes display-referred sRGB has those numbers read as linear light
     * and tonemapped a second time by the composite; nothing detects that, the
     * image is just visibly wrong.
     */
    record Raw(String vertexSource, String fragmentSource) implements Material {
    }
}
