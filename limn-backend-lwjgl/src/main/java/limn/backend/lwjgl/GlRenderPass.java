package limn.backend.lwjgl;

import limn.math.Mat4;
import limn.math.Vec3;
import limn.render3d.ColorSpace;
import limn.render3d.Environment;
import limn.render3d.GpuMesh;
import limn.render3d.IrradianceSh;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.RenderPass;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayList;
import java.util.List;

/**
 * The GL implementation of {@link RenderPass}. Its target is already bound (depth
 * on) by {@link Gl3DContext#render}. It accumulates the pass's lighting/exposure/
 * shadow state and <em>buffers</em> the draw calls, so the backend can render them
 * twice: once depth-only from the light (the shadow map) and once with the PBR
 * shader. SimpleLit reads the single legacy {@link #light} directional; PBR reads
 * {@link #addLight} + {@link #ambient} (+ {@link #shadow}).
 */
final class GlRenderPass implements RenderPass {

    /** One buffered draw, replayed by the shadow and color passes. */
    record Draw(GlMesh mesh, Material material, Mat4 model) {
    }

    private final Gl3DContext context;
    private final Mat4 viewProjection;
    private final Vec3 cameraPos;
    private final List<Draw> draws = new ArrayList<>();
    /**
     * Blended draws, kept apart from the opaque ones at submission. They go after
     * every opaque draw and never reach the shadow depth pass (a transparent
     * surface casting a solid shadow is worse than no shadow), which is why the
     * split lives here rather than in a filter at the draw loop.
     */
    private final List<Draw> blended = new ArrayList<>();
    private final List<Draw> shadowOnly = new ArrayList<>();

    // Legacy single directional (Material.SimpleLit).
    private Vec3 lightDir = new Vec3(0.4f, 0.85f, 0.55f);
    private Vec3 lightColor = new Vec3(1, 1, 1);
    private float simpleAmbient = 0.25f;

    // PBR pass state (Material.Pbr).
    private final List<Light> lights = new ArrayList<>();
    private Vec3 ambientColor = new Vec3(0.03f, 0.03f, 0.03f);
    private float exposureValue = 1f;

    // Bloom (ADR 005): applied by the backend after the resolve; intensity 0 = off.
    private float bloomThreshold;
    private float bloomIntensity;
    private float bloomRadius;

    // Shadow config (set by the body) + result (set by the backend after the depth pass).
    private boolean shadowEnabled;
    private Vec3 shadowDirection;
    private Vec3 shadowCenter;
    private float shadowRadius;
    private Mat4 shadowVp;
    private int shadowTexture;

    // IBL environment (optional).
    private Environment environment;
    private IrradianceSh irradianceSh;

    GlRenderPass(Gl3DContext context, Mat4 viewProjection, Vec3 cameraPos) {
        this.context = context;
        this.viewProjection = viewProjection;
        this.cameraPos = cameraPos;
    }

    @Override
    public RenderPass clear(float r, float g, float b, float a) {
        // The clear is a color writer like the six programs: the target is linear
        // premultiplied (ADR 004), the caller's color is authored sRGB with
        // straight alpha, so decode and premultiply on the way in.
        GL33C.glClearColor(ColorSpace.srgbToLinear(r) * a, ColorSpace.srgbToLinear(g) * a,
                ColorSpace.srgbToLinear(b) * a, a);
        GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT | GL33C.GL_DEPTH_BUFFER_BIT);
        return this;
    }

    @Override
    public RenderPass light(Vec3 direction, Vec3 color, float ambientAmount) {
        this.lightDir = direction;
        this.lightColor = color;
        this.simpleAmbient = ambientAmount;
        return this;
    }

    @Override
    public RenderPass addLight(Light light) {
        if (lights.size() < Gl3DContext.MAX_LIGHTS) {
            lights.add(light);
        }
        return this;
    }

    @Override
    public RenderPass ambient(Vec3 color) {
        this.ambientColor = color;
        return this;
    }

    @Override
    public RenderPass exposure(float exposure) {
        this.exposureValue = exposure;
        return this;
    }

    @Override
    public RenderPass bloom(float threshold, float intensity, float radius) {
        this.bloomThreshold = threshold;
        this.bloomIntensity = intensity;
        this.bloomRadius = radius;
        return this;
    }

    @Override
    public RenderPass shadow(Vec3 direction, Vec3 center, float radius) {
        this.shadowEnabled = true;
        this.shadowDirection = direction;
        this.shadowCenter = center;
        this.shadowRadius = radius;
        return this;
    }

    @Override
    public RenderPass environment(Environment environment, IrradianceSh irradiance) {
        this.environment = environment;
        this.irradianceSh = irradiance;
        return this;
    }

    @Override
    public void draw(GpuMesh mesh, Material material, Mat4 model) {
        if (mesh instanceof GlMesh glMesh) {
            (material.isOpaque() ? draws : blended).add(new Draw(glMesh, material, model));
        }
    }

    @Override
    public void drawShadowOnly(GpuMesh mesh, Mat4 model) {
        if (mesh instanceof GlMesh glMesh) {
            shadowOnly.add(new Draw(glMesh, null, model)); // material unused by the depth pass
        }
    }

    /** One submitted debug-line batch (a bucket of a {@code DebugDraw} flush). */
    record DebugBatch(float[] vertices, int vertexCount, boolean depthTested) {
    }

    // Deliberately NOT in draws(): debug lines must not reach the shadow depth
    // pass, and fresh entries here must not defeat the identity-based
    // shadow-map reuse (sameDraws) that compares the mesh draw lists.
    private final List<DebugBatch> debugBatches = new ArrayList<>();

    @Override
    public void debugLines(float[] vertices, int vertexCount, boolean depthTested) {
        if (vertexCount > 0) {
            debugBatches.add(new DebugBatch(vertices, vertexCount, depthTested));
        }
    }

    // ------------------------------------------------------- package accessors

    /** Opaque draws: the depth-writing, shadow-casting ones. */
    List<Draw> draws() {
        return draws;
    }

    /** Blended draws, in submission order; drawn after {@link #draws()}, never depth-written. */
    List<Draw> blendedDraws() {
        return blended;
    }

    List<DebugBatch> debugBatches() {
        return debugBatches;
    }

    /** Casters outside the camera frustum: depth pass only, never the color pass. */
    List<Draw> shadowOnlyDraws() {
        return shadowOnly;
    }

    Mat4 viewProjection() {
        return viewProjection;
    }

    Vec3 cameraPos() {
        return cameraPos;
    }

    Vec3 lightDir() {
        return lightDir;
    }

    Vec3 lightColor() {
        return lightColor;
    }

    float simpleAmbient() {
        return simpleAmbient;
    }

    List<Light> lights() {
        return lights;
    }

    Vec3 ambientColor() {
        return ambientColor;
    }

    float exposureValue() {
        return exposureValue;
    }

    float bloomThreshold() {
        return bloomThreshold;
    }

    float bloomIntensity() {
        return bloomIntensity;
    }

    float bloomRadius() {
        return bloomRadius;
    }

    boolean shadowEnabled() {
        return shadowEnabled;
    }

    Vec3 shadowDirection() {
        return shadowDirection;
    }

    Vec3 shadowCenter() {
        return shadowCenter;
    }

    float shadowRadius() {
        return shadowRadius;
    }

    void setShadowResult(Mat4 shadowVp, int shadowTexture) {
        this.shadowVp = shadowVp;
        this.shadowTexture = shadowTexture;
    }

    Mat4 shadowVp() {
        return shadowVp;
    }

    int shadowTexture() {
        return shadowTexture;
    }

    Environment environment() {
        return environment;
    }

    IrradianceSh irradianceSh() {
        return irradianceSh;
    }
}
