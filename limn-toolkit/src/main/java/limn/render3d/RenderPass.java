package limn.render3d;

import limn.math.Mat4;
import limn.math.Vec3;

/**
 * The draw interface handed to a render callback (see
 * {@code Graphics3D.render(target, camera, body)}). The target is already bound
 * with depth testing on and the camera's view/projection set; issue
 * {@link #clear} then {@link #draw} calls. Low-level and explicit: you place
 * every mesh yourself.
 */
public interface RenderPass {

    /**
     * Clears the color and depth of the target. The color is authored sRGB with
     * straight alpha, like every other authored color; the backend decodes it to
     * linear and premultiplies on write, per the target's color space.
     */
    RenderPass clear(float r, float g, float b, float a);

    /**
     * Sets the single directional light used by {@link Material.SimpleLit}
     * (direction points <em>toward</em> the light; {@code ambient} in [0,1]).
     * Ignored by unlit and PBR materials.
     */
    RenderPass light(Vec3 direction, Vec3 color, float ambient);

    /**
     * Adds an analytic light for {@link Material.Pbr} (up to 8; extras are
     * ignored). Independent of {@link #light}: SimpleLit reads that one, PBR reads
     * these. Colors are linear.
     */
    RenderPass addLight(Light light);

    /** Flat ambient/environment term for PBR (linear color; a stand-in for IBL). */
    RenderPass ambient(Vec3 color);

    /**
     * Exposure multiplier for this pass's output (default 1). Part of the display
     * transform, not of the scene-referred pixels: it is recorded on the target
     * and applied (before the tonemap) when the 2D composite draws it.
     */
    RenderPass exposure(float exposure);

    /**
     * Adds a bright-pass glow to this pass's result, in linear light, before
     * the display transform: pixels brighter than {@code threshold}
     * spread by a blur of {@code radius}, and {@code intensity} of the result
     * is added back into the target during the pass's own teardown. Bloom is
     * scene-side; {@link #exposure} is display-side. They compose in that
     * order and do not interact.
     *
     * @param threshold linear scene-referred light above which a pixel
     *                  contributes; 1 means "brighter than a fully-lit white
     *                  surface", the useful floor
     * @param intensity how much of the blurred result is added; 0 (the
     *                  default) disables bloom entirely: no allocation, no pass
     * @param radius    the blur's standard deviation in <em>points</em>, the
     *                  toolkit's logical unit (the backend converts to texels),
     *                  so a glow keeps its size across display scales and
     *                  viewport resizes. Below a small minimum the backend
     *                  refuses to run rather than answer with a glow wider
     *                  than asked; its half-res chain cannot blur narrower.
     *                  Default: ignored (headless/test passes).
     */
    default RenderPass bloom(float threshold, float intensity, float radius) {
        return this;
    }

    /**
     * Enables a directional shadow map for the PBR pass: the backend renders scene
     * depth from {@code direction} (pointing toward the light) into an offscreen
     * depth target fitted to a sphere of {@code radius} around {@code center}, and
     * shadows the directional lights with PCF. Call once per pass; last wins.
     */
    RenderPass shadow(Vec3 direction, Vec3 center, float radius);

    /**
     * Sets the image-based lighting environment for the PBR pass: diffuse from the
     * baked {@code irradiance} (SH), specular from the environment's analytic sky,
     * plus a skybox background. Pass {@code null} to disable (flat ambient is used).
     */
    RenderPass environment(Environment environment, IrradianceSh irradiance);

    /** Draws {@code mesh} with {@code material} at the world transform {@code model}. */
    void draw(GpuMesh mesh, Material material, Mat4 model);

    /**
     * Buffers {@code mesh} for the shadow depth pass <em>only</em>: a caster
     * outside the camera frustum must still darken visible receivers, so
     * retained scenes route their camera-culled meshes here when shadows are
     * on. No color-pass cost. Default: ignored (passes without shadow support).
     */
    default void drawShadowOnly(GpuMesh mesh, Mat4 model) {
    }

    /**
     * Submits immediate debug lines, usually via {@link DebugDraw#flush}, not
     * directly. {@code vertices} holds {@code vertexCount} world-space vertices
     * interleaved as [x, y, z, r, g, b, a] (see
     * {@link DebugDraw#FLOATS_PER_VERTEX}), two per line segment; the array is
     * read before the pass's render call returns and may be longer than the
     * used prefix. Debug lines never cast shadows. {@code depthTested} selects
     * occlusion by scene geometry versus drawing on top ("X-ray"). Default:
     * ignored (headless/test passes).
     */
    default void debugLines(float[] vertices, int vertexCount, boolean depthTested) {
    }
}
