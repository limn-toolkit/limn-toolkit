package limn.backend.lwjgl;

import limn.backend.RenderStats;
import limn.graphics.BlendMode;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Camera;
import limn.render3d.ColorSpace;
import limn.render3d.Environment;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.IrradianceSh;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.MeshUsage;
import limn.render3d.Render3DStats;
import limn.render3d.RenderPass;
import limn.render3d.RenderTarget;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.VertexAttribute;
import limn.render3d.shader.StandardSurface;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Per-GL-context owner of 3D resources, created lazily by a window's
 * {@link GlCanvas} on first 3D use and disposed with that context (in
 * {@link GlCanvas#dispose()}). Owns the material programs, uploaded meshes and
 * render targets created in this context, so nothing outlives its context
 * (contexts are not shared between Limn windows).
 */
final class Gl3DContext {

    /** Max analytic lights the PBR pass reads (must match {@code MAX_LIGHTS} in mesh_pbr.frag). */
    static final int MAX_LIGHTS = 8;

    // std140 UBO binding points + sizes (bytes): must match the PBR shader's blocks.
    private static final int FRAME_BINDING = 0;
    private static final int LIGHTS_BINDING = 1;
    private static final int MATERIAL_BINDING = 2;
    private static final int FRAME_SIZE = 80;                    // mat4(64) + vec4(16)
    private static final int LIGHTS_SIZE = 32 + MAX_LIGHTS * 64; // ambient(16)+count(16)+8×[4×vec4]
    private static final int MATERIAL_SIZE = 48;                 // 3×vec4
    private static final int SHADOW_SIZE = 2048;                 // shadow map resolution

    private final Set<GlRenderTarget> targets =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GlMesh> meshes =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<GlTexture> textures =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Built-in material programs (lazy; need the context current). Raw programs keyed by material.
    private ShaderProgram unlitProgram;
    private int uUnlitMvp;
    private int uUnlitColor;
    private ShaderProgram litProgram;
    private int uLitMvp;
    private int uLitNormal;
    private int uLitColor;
    private int uLitLightDir;
    private int uLitLightColor;
    private int uLitAmbient;
    private final Map<Material.Raw, RawProgram> rawPrograms = new IdentityHashMap<>();

    /**
     * One compiled surface program and every location the pass or a draw needs from
     * it.
     *
     * <p>This record exists because of one GL fact: a uniform <em>block</em> binds to a
     * binding point, which is global, but every loose {@code glUniform*} (including
     * the sampler-unit ones) is <b>program</b> state. So the moment a pass draws with
     * more than one surface program, everything that used to be issued once per pass
     * has to be issued once per pass <em>per program</em>, or the second program's
     * samplers silently read unit 0 and its shadow map is whatever was left there.
     * That warning has been in this file since there was only one program; this is it
     * coming true.
     */
    private record SurfaceProgram(ShaderProgram program, int model, int normalMatrix,
                                  int baseColorTex, int normalTex,
                                  int shadowVp, int shadowMap, int shadowStrength,
                                  int sh, int skyColor, int horizonColor, int groundColor,
                                  int iblIntensity, int iblEnabled,
                                  int[] samplers, int[] values) {
    }

    /** The reserved key the built-in metallic-roughness surface is cached under. */
    private static final String PBR_KEY = "limn.pbr";

    /**
     * The first texture unit an application-supplied surface may use: 0 is the base
     * colour, 1 the shadow map and 2 the normal map, all bound by this file.
     */
    private static final int APP_TEXTURE_UNIT0 = 3;

    // Surface programs + std140 UBOs (lazy). A 1×1 white texture keeps a sampler
    // complete when a material has no texture for it.
    private final Map<String, SurfaceProgram> surfacePrograms = new HashMap<>();
    private SurfaceProgram pbrProgram;
    /** Distinct programs used by the pass being prepared; reused so a pass allocates nothing. */
    private final List<SurfaceProgram> passPrograms = new ArrayList<>();
    private int frameUbo;
    private int lightsUbo;
    private int materialUbo;
    private Std140Buffer frameData;
    private Std140Buffer lightsData;
    private Std140Buffer materialData;
    // Reused upload scratch: the draw loop must not allocate per draw.
    private final float[] scratch16 = new float[16];
    private final float[] scratch9 = new float[9];
    private final float[] scratch27 = new float[27];
    private int whiteTex;
    /** 1×1 (0.5, 0.5, 1), the "no perturbation" normal map bound when a material has none. */
    private int flatNormalTex;

    // Directional shadow map + depth-only program (lazy).
    private GlShadowMap shadowMap;
    private ShaderProgram depthProgram;
    private int uDepthMvp;
    // Last depth-pass inputs: when identical (a static scene, whose cached world
    // matrices are the same instances, and the same light fit), the map is
    // still exact and the depth pass is skipped for the frame.
    private final List<GlRenderPass.Draw> lastShadowDraws = new ArrayList<>();
    private final List<GlRenderPass.Draw> lastShadowOnlyDraws = new ArrayList<>();
    /** Per-draw mesh revisions at the last depth pass (see {@code sameDraws}). */
    private int[] lastShadowRevisions = new int[0];
    private int[] lastShadowOnlyRevisions = new int[0];
    private Vec3 lastShadowDir;
    private Vec3 lastShadowCenter;
    private float lastShadowRadius;
    private Mat4 lastShadowVp;

    // Skybox (lazy): a fullscreen triangle sampling the procedural sky.
    private ShaderProgram skyboxProgram;
    private int skyboxVao;
    private int uSkyInvViewProj;
    private int uSkyCamPos;
    private int uSkySkyColor;
    private int uSkyHorizonColor;
    private int uSkyGroundColor;
    private int uSkyIblIntensity;

    // Last-frame 3D workload (for diagnostics); accumulated during a render pass.
    private int lastDrawCalls;
    private long lastTriangles;
    private int passDrawCalls;
    private long passTriangles;

    // Debug-line program + streaming buffer (lazy; per-frame immediate geometry).
    private ShaderProgram lineProgram;
    private int lineVao;
    private int lineVbo;
    private int uLineViewProjection;

    // Bloom chain programs (lazy; ADR 005). One empty VAO drives the
    // fullscreen triangles, like the skybox's.
    private ShaderProgram bloomBrightProgram;
    private int uBrightSrc;
    private int uBrightThreshold;
    private ShaderProgram bloomBlurProgram;
    private int uBlurSrc;
    private int uBlurStep;
    private ShaderProgram bloomCombineProgram;
    private int uCombineSrc;
    private int uCombineIntensity;
    private int bloomVao;

    // Demo cube program + geometry (lazy; needs the context current on first render).
    private ShaderProgram cubeProgram;
    private int cubeVao;
    private int cubeVbo;
    private int uMvp;
    private int uModel;

    private record RawProgram(ShaderProgram program, int mvp, int model, int normalMatrix) {
    }

    RenderTarget createTarget(int widthPx, int heightPx, int samples) {
        int maxSamples = GL33C.glGetInteger(GL33C.GL_MAX_SAMPLES);
        int clamped = Math.max(1, Math.min(samples, Math.max(1, maxSamples)));
        GlRenderTarget target = new GlRenderTarget(this, widthPx, heightPx, clamped);
        targets.add(target);
        return target;
    }

    GpuMesh upload(MeshData mesh, MeshUsage usage) {
        GlMesh glMesh = new GlMesh(this, mesh, usage);
        meshes.add(glMesh);
        return glMesh;
    }

    GpuTexture upload(TextureData texture, Sampler sampler) {
        GlTexture glTexture = new GlTexture(this, texture, sampler);
        textures.add(glTexture);
        return glTexture;
    }

    void forget(GlRenderTarget target) {
        targets.remove(target);
    }

    void forget(GlMesh mesh) {
        meshes.remove(mesh);
    }

    void forget(GlTexture texture) {
        textures.remove(texture);
    }

    // ---------------------------------------------------------------- render

    void render(RenderTarget targetHandle, Camera camera, Consumer<RenderPass> body,
                float pixelsPerPoint) {
        if (!(targetHandle instanceof GlRenderTarget target)) {
            return;
        }
        int prevFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, prevViewport);

        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
            GL33C.glViewport(0, 0, target.widthPx(), target.heightPx());
            GL33C.glEnable(GL33C.GL_DEPTH_TEST);
            GL33C.glDepthFunc(GL33C.GL_LESS);
            GL33C.glDisable(GL33C.GL_BLEND);
            GL33C.glDisable(GL33C.GL_CULL_FACE);
            // The 2D batch may have a damage scissor active (window space);
            // it must not clip this offscreen pass (the 2D flush re-arms it).
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST);

            float aspect = (float) target.widthPx() / target.heightPx();
            Mat4 viewProjection = camera.projection(aspect).multiply(camera.view());
            GlRenderPass pass = new GlRenderPass(this, viewProjection, camera.eye());
            body.accept(pass); // clears immediately, buffers draws, records lights/shadow

            if (pass.environment() != null) {
                // The sky replaces the background in COLOR only: with no scene
                // background nothing cleared DEPTH this frame, and geometry
                // would depth-test against the previous frame's buffer
                // (redundant-but-harmless when a background already cleared).
                GL33C.glClear(GL33C.GL_DEPTH_BUFFER_BIT);
                GL33C.glDisable(GL33C.GL_DEPTH_TEST); // sky fills the background, writes no depth
                renderSkybox(pass);
                GL33C.glEnable(GL33C.GL_DEPTH_TEST);
            }

            if (pass.shadowEnabled() && !pass.draws().isEmpty()) {
                if (shadowInputsUnchanged(pass)) {
                    pass.setShadowResult(lastShadowVp, shadowMap.depthTexture()); // map still exact
                } else {
                    renderShadowMap(target, pass); // depth-only pass; restores the target FBO + viewport
                    rememberShadowInputs(pass);
                }
            }

            passDrawCalls = 0;
            passTriangles = 0;
            prepareSurfacePass(pass); // frame/lights UBOs once, shadow/IBL once per program
            List<GlRenderPass.Draw> draws = pass.draws();
            for (int i = 0; i < draws.size(); i++) {
                GlRenderPass.Draw d = draws.get(i);
                drawMesh(d.mesh(), d.material(), d.model(), pass);
            }
            drawBlended(pass);
            drawDebugLines(pass); // after the meshes: depth bucket occludes, overlay on top
            lastDrawCalls = passDrawCalls;
            lastTriangles = passTriangles;

            // The composite's display transform needs the exposure this pass was
            // given; it travels on the target (ADR 004 §3.4).
            target.setExposure(pass.exposureValue());
            target.resolve();

            // Bloom (ADR 005) runs on the resolved linear scene-referred light,
            // after blending and before the display transform, still inside
            // this try so the finally restores state if a bloom pass throws.
            // The shouldRun branch is the whole cost when bloom is off.
            if (BloomMath.shouldRun(pass.bloomIntensity(), pass.bloomRadius(), pixelsPerPoint)) {
                renderBloom(target, pass, pixelsPerPoint);
            }
        } finally {
            // Restore only what the 2D flush won't (framebuffer, viewport, depth
            // test). In a finally: user code runs inside the pass (the body, lazy
            // Raw-material compiles) and may throw; leaving the offscreen FBO
            // bound would send this and every later frame's 2D batch into it.
            GL33C.glDisable(GL33C.GL_DEPTH_TEST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, prevFbo);
            GL33C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }

    private static final String BLOOM_VERT = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Fullscreen triangle.
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // Package-private: ShaderColorSpaceTest locks the color-space contract onto it.
    static final String BLOOM_BRIGHT_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            // Bright pass (ADR 005): input and output are premultiplied linear
            // scene-referred RGBA. The threshold scales by alpha: rgb is already
            // alpha-scaled, so comparing it against a straight threshold would
            // brighten translucent surfaces on their coverage, not their color.
            // The output is the bright portion as its own premultiplied layer:
            // the same un-premultiplied color, coverage scaled to the surviving
            // fraction of light. Alpha must track surviving light (finding 3).
            // Carrying it where nothing survived would spread a dark veil over
            // the UI behind the viewport; dropping it where light did survive
            // would erase the glow at the edge of the drawn content.
            in vec2 v_uv;
            uniform sampler2D u_src;
            uniform float u_threshold;
            out vec4 o_color;
            void main() {
                vec4 texel = texture(u_src, v_uv);
                vec3 bright = max(texel.rgb - u_threshold * texel.a, vec3(0.0));
                // Coverage cap: the composite un-premultiplies, tonemaps to <= 1
                // and re-scales by alpha, so a texel can never DISPLAY more than
                // ~alpha worth of energy; glow may not out-shine its source.
                // Additive blending deposits rgb at alpha 0 over a transparent
                // background (its alpha factors are ZERO, ONE), and without the
                // cap that invisible light would pass any threshold (t * 0) and
                // halo onto covered neighbours. 16 sits far past the tonemap's
                // shoulder: real content is untouched, and the cap fades in
                // continuously as coverage vanishes instead of popping at zero.
                bright = min(bright, vec3(16.0) * texel.a);
                float kept = max(max(bright.r, bright.g), bright.b);
                float had = max(max(max(texel.r, texel.g), texel.b), 1e-4);
                o_color = vec4(bright, texel.a * min(kept / had, 1.0));
            }
            """;

    // Package-private: ShaderColorSpaceTest locks the color-space contract onto it.
    static final String BLOOM_BLUR_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            // One direction of the separable Gaussian (ADR 005): u_step is the
            // tap spacing in UV: direction × pair spacing / texture size, from
            // the caller. 9 taps of sigma 2 (BloomMath.SIGMA_PER_PAIR) at unit
            // spacing; wider pairs scale the spacing and sigmas accumulate in
            // quadrature. The weights sum to 1, so the convex combination keeps
            // premultiplied RGBA premultiplied. Alpha blurs with the color,
            // which is the point: the glow's coverage genuinely grows
            // (finding 3), rather than light leaking outside its alpha.
            in vec2 v_uv;
            uniform sampler2D u_src;
            uniform vec2 u_step;
            out vec4 o_color;
            void main() {
                vec4 sum = texture(u_src, v_uv) * 0.20417;
                sum += (texture(u_src, v_uv + u_step) + texture(u_src, v_uv - u_step)) * 0.18018;
                sum += (texture(u_src, v_uv + u_step * 2.0) + texture(u_src, v_uv - u_step * 2.0)) * 0.12382;
                sum += (texture(u_src, v_uv + u_step * 3.0) + texture(u_src, v_uv - u_step * 3.0)) * 0.06629;
                sum += (texture(u_src, v_uv + u_step * 4.0) + texture(u_src, v_uv - u_step * 4.0)) * 0.02762;
                o_color = sum;
            }
            """;

    // Package-private: ShaderColorSpaceTest locks the color-space contract onto it.
    static final String BLOOM_COMBINE_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            // Bloom combine (ADR 005, finding 3): adds the blurred bright layer
            // into the resolved target, RGB and A both. RGB-only would add light
            // where alpha is 0: the composite un-premultiplies and re-scales by
            // alpha, so that light would simply vanish at the edge of the drawn
            // content. Alpha is clamped to 1 here because the blender cannot
            // saturate on a float target; the blend then unions coverage
            // (ONE, ONE_MINUS_SRC_ALPHA) while light adds (ONE, ONE).
            in vec2 v_uv;
            uniform sampler2D u_src;
            uniform float u_intensity;
            out vec4 o_color;
            void main() {
                vec4 glow = texture(u_src, v_uv);
                o_color = vec4(glow.rgb * u_intensity, clamp(glow.a * u_intensity, 0.0, 1.0));
            }
            """;

    /**
     * The bloom chain (ADR 005): bright pass into the target's half-res A,
     * separable blur A ⇄ B, additive combine back into the resolved colour
     * texture. Runs after {@link GlRenderTarget#resolve()} (on linear
     * scene-referred light, after blending, before the display transform)
     * and only when {@link BloomMath#shouldRun} said yes, so the off path
     * costs one branch. Depth stays off throughout; the caller's finally
     * restores the framebuffer and viewport.
     */
    private void renderBloom(GlRenderTarget target, GlRenderPass pass, float pixelsPerPoint) {
        ensureBloom();
        target.ensureBloomTargets();
        int halfW = target.bloomWidthPx();
        int halfH = target.bloomHeightPx();
        GL33C.glDisable(GL33C.GL_DEPTH_TEST);
        GL33C.glDisable(GL33C.GL_BLEND);

        // Bright pass: resolved color → half-res A. Reads the texture the
        // resolve just wrote and writes elsewhere: no feedback (ADR 005 §2.3).
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.bloomFramebufferA());
        GL33C.glViewport(0, 0, halfW, halfH);
        bloomBrightProgram.use();
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, target.colorTexture());
        GL33C.glUniform1i(uBrightSrc, 0);
        GL33C.glUniform1f(uBrightThreshold, Math.max(0f, pass.bloomThreshold()));
        GL33C.glBindVertexArray(bloomVao);
        GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);

        // Separable blur, A ⇄ B: pair j samples at spacing j+1, so each pair
        // widens the Gaussian without more taps and N pairs reach
        // BloomMath.accumulatedSigma(N). Ends in A after every pair.
        int pairs = BloomMath.blurPairs(
                BloomMath.sigmaTexels(pass.bloomRadius(), pixelsPerPoint));
        bloomBlurProgram.use();
        GL33C.glUniform1i(uBlurSrc, 0);
        for (int pair = 0; pair < pairs; pair++) {
            float spacing = pair + 1;
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.bloomFramebufferB());
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, target.bloomTextureA());
            GL33C.glUniform2f(uBlurStep, spacing / halfW, 0f);
            GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.bloomFramebufferA());
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, target.bloomTextureB());
            GL33C.glUniform2f(uBlurStep, 0f, spacing / halfH);
            GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
        }

        // Combine (finding 3): the blurred bright layer adds back into the
        // resolved texture; this is the only pass that writes it, reading only
        // bloom A, so there is no sample-while-rendering feedback. Light adds
        // (ONE, ONE); alpha unions coverage (ONE, ONE_MINUS_SRC_ALPHA): the
        // glow spreading over transparent pixels genuinely grows the content,
        // and the union is how "clamped at 1" is spelled on a float target,
        // where an additive alpha would sail past 1 and flip the composite's
        // (1 − a) negative.
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.resolveFramebuffer());
        GL33C.glViewport(0, 0, target.widthPx(), target.heightPx());
        GL33C.glEnable(GL33C.GL_BLEND);
        GL33C.glBlendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ONE,
                GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
        bloomCombineProgram.use();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, target.bloomTextureA());
        GL33C.glUniform1i(uCombineSrc, 0);
        GL33C.glUniform1f(uCombineIntensity, pass.bloomIntensity());
        GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
        GL33C.glBindVertexArray(0);
        GL33C.glDisable(GL33C.GL_BLEND);
    }

    private void ensureBloom() {
        if (bloomBrightProgram != null) {
            return;
        }
        bloomBrightProgram = ShaderProgram.fromSources(BLOOM_VERT, BLOOM_BRIGHT_FRAG);
        uBrightSrc = bloomBrightProgram.uniformLocation("u_src");
        uBrightThreshold = bloomBrightProgram.uniformLocation("u_threshold");
        bloomBlurProgram = ShaderProgram.fromSources(BLOOM_VERT, BLOOM_BLUR_FRAG);
        uBlurSrc = bloomBlurProgram.uniformLocation("u_src");
        uBlurStep = bloomBlurProgram.uniformLocation("u_step");
        bloomCombineProgram = ShaderProgram.fromSources(BLOOM_VERT, BLOOM_COMBINE_FRAG);
        uCombineSrc = bloomCombineProgram.uniformLocation("u_src");
        uCombineIntensity = bloomCombineProgram.uniformLocation("u_intensity");
        bloomVao = GL33C.glGenVertexArrays();
    }

    /**
     * The transparent bucket: every blended draw, in submission order, after every
     * opaque one. Depth testing stays on so a particle behind a wall is hidden, but
     * depth WRITES are off, or the nearest transparent surface would occlude the
     * ones behind it instead of letting them show through.
     *
     * <p>Leaves blending disabled and the depth mask restored. That is not tidiness:
     * {@link #drawDebugLines} sets no blend state of its own and has worked so far
     * only because the whole pass ran with {@code GL_BLEND} off.
     */
    private void drawBlended(GlRenderPass pass) {
        List<GlRenderPass.Draw> blended = pass.blendedDraws();
        if (blended.isEmpty()) {
            return;
        }
        GL33C.glEnable(GL33C.GL_BLEND);
        GL33C.glDepthMask(false);
        BlendMode active = null;
        for (int i = 0; i < blended.size(); i++) {
            GlRenderPass.Draw d = blended.get(i);
            BlendMode mode = d.material().blend();
            if (mode != active) {
                GlBlend.Factors f = GlBlend.of(mode);
                GL33C.glBlendFuncSeparate(f.srcRgb(), f.dstRgb(), f.srcAlpha(), f.dstAlpha());
                active = mode;
            }
            drawMesh(d.mesh(), d.material(), d.model(), pass);
        }
        GL33C.glDepthMask(true);
        GL33C.glDisable(GL33C.GL_BLEND);
    }

    /** Selects the material's program, binds its uniforms/UBOs, and draws the mesh. */
    void drawMesh(GlMesh mesh, Material material, Mat4 model, GlRenderPass pass) {
        if (material instanceof Material.Unlit unlit) {
            ensureUnlit();
            unlitProgram.use();
            Mat4.multiplyInto(pass.viewProjection(), model, scratch16);
            GL33C.glUniformMatrix4fv(uUnlitMvp, false, scratch16);
            GL33C.glUniform4f(uUnlitColor, unlit.color().x(), unlit.color().y(),
                    unlit.color().z(), unlit.color().w());
        } else if (material instanceof Material.SimpleLit lit) {
            ensureLit();
            litProgram.use();
            Mat4.multiplyInto(pass.viewProjection(), model, scratch16);
            GL33C.glUniformMatrix4fv(uLitMvp, false, scratch16);
            Mat4.normalMatrixInto(model, scratch9);
            GL33C.glUniformMatrix3fv(uLitNormal, false, scratch9);
            GL33C.glUniform4f(uLitColor, lit.baseColor().x(), lit.baseColor().y(),
                    lit.baseColor().z(), lit.baseColor().w());
            Vec3 lightDir = pass.lightDir();
            Vec3 lightColor = pass.lightColor();
            GL33C.glUniform3f(uLitLightDir, lightDir.x(), lightDir.y(), lightDir.z());
            GL33C.glUniform3f(uLitLightColor, lightColor.x(), lightColor.y(), lightColor.z());
            GL33C.glUniform1f(uLitAmbient, pass.simpleAmbient());
        } else if (material instanceof Material.Pbr pbr) {
            bindPbr(pbr, model);
        } else if (material instanceof Material.Surface surface) {
            bindSurface(surface, model);
        } else if (material instanceof Material.Raw raw) {
            RawProgram rp = ensureRaw(raw);
            rp.program().use();
            if (rp.mvp() >= 0) {
                Mat4.multiplyInto(pass.viewProjection(), model, scratch16);
                GL33C.glUniformMatrix4fv(rp.mvp(), false, scratch16);
            }
            if (rp.model() >= 0) {
                model.toArray(scratch16);
                GL33C.glUniformMatrix4fv(rp.model(), false, scratch16);
            }
            if (rp.normalMatrix() >= 0) {
                Mat4.normalMatrixInto(model, scratch9);
                GL33C.glUniformMatrix3fv(rp.normalMatrix(), false, scratch9);
            }
        }
        passDrawCalls++;
        passTriangles += mesh.triangleCount();
        mesh.drawTriangles();
    }

    // ------------------------------------------------------------------ PBR

    private static final int LIGHT_DIRECTIONAL = 0;
    private static final int LIGHT_POINT = 1;
    private static final int LIGHT_SPOT = 2;

    /**
     * Uploads everything constant across one pass's lit draws: Frame and Lights UBOs
     * once, then the shadow matrix/map and IBL uniforms once <b>per surface program</b>
     * the pass actually uses. Runs before the draw loop; the per-draw binders then
     * touch only per-draw state. No-op when the pass has no lit draw at all (nothing is
     * compiled).
     */
    private void prepareSurfacePass(GlRenderPass pass) {
        passPrograms.clear();
        collectPrograms(pass.draws());
        collectPrograms(pass.blendedDraws());
        if (passPrograms.isEmpty()) {
            return;
        }

        // Frame UBO (binding 0): view-projection + camera position. The w slot held
        // exposure until ADR 004 moved the display transform to the composite; the
        // std140 layout keeps the vec4, the shader no longer reads w. A uniform block
        // binds to a binding point, so this reaches every program at once.
        Vec3 eye = pass.cameraPos();
        pass.viewProjection().toArray(scratch16);
        frameData.reset()
                .putMat4(scratch16)
                .putVec4(eye.x(), eye.y(), eye.z(), 0f);
        uploadUbo(frameUbo, frameData);

        // Lights UBO (binding 1): ambient + count + up to MAX_LIGHTS encoded lights.
        fillLights(pass);
        uploadUbo(lightsUbo, lightsData);

        for (int i = 0; i < passPrograms.size(); i++) {
            prepareProgram(passPrograms.get(i), pass);
        }
    }

    /** Adds each draw's surface program to {@link #passPrograms}, without duplicates. */
    private void collectPrograms(List<GlRenderPass.Draw> draws) {
        for (int i = 0; i < draws.size(); i++) {
            Material material = draws.get(i).material();
            SurfaceProgram program;
            if (material instanceof Material.Pbr) {
                program = ensurePbr();
            } else if (material instanceof Material.Surface surface) {
                program = ensureSurface(surface);
            } else {
                continue;
            }
            if (!passPrograms.contains(program)) {
                passPrograms.add(program);
            }
        }
    }

    /** The shadow and IBL uniforms, which are program state and so are issued per program. */
    private void prepareProgram(SurfaceProgram sp, GlRenderPass pass) {
        sp.program().use();
        if (pass.shadowEnabled() && pass.shadowTexture() != 0) {
            if (sp.shadowVp() >= 0) {
                pass.shadowVp().toArray(scratch16);
                GL33C.glUniformMatrix4fv(sp.shadowVp(), false, scratch16);
            }
            GL33C.glActiveTexture(GL33C.GL_TEXTURE1);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, pass.shadowTexture());
            if (sp.shadowMap() >= 0) {
                GL33C.glUniform1i(sp.shadowMap(), 1);
            }
            if (sp.shadowStrength() >= 0) {
                GL33C.glUniform1f(sp.shadowStrength(), 1f);
            }
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        } else if (sp.shadowStrength() >= 0) {
            GL33C.glUniform1f(sp.shadowStrength(), 0f);
        }

        Environment env = pass.environment();
        if (env != null && sp.iblEnabled() >= 0) {
            if (sp.sh() >= 0) {
                Vec3[] sh = pass.irradianceSh().coefficients();
                for (int i = 0; i < 9; i++) {
                    scratch27[i * 3] = sh[i].x();
                    scratch27[i * 3 + 1] = sh[i].y();
                    scratch27[i * 3 + 2] = sh[i].z();
                }
                GL33C.glUniform3fv(sp.sh(), scratch27);
            }
            uniform3(sp.skyColor(), env.sky());
            uniform3(sp.horizonColor(), env.horizon());
            uniform3(sp.groundColor(), env.ground());
            if (sp.iblIntensity() >= 0) {
                GL33C.glUniform1f(sp.iblIntensity(), env.intensity());
            }
            GL33C.glUniform1f(sp.iblEnabled(), 1f);
        } else if (sp.iblEnabled() >= 0) {
            GL33C.glUniform1f(sp.iblEnabled(), 0f);
        }
    }

    /** Per-draw PBR state: material UBO, model/normal matrices, base-color texture. */
    private void bindPbr(Material.Pbr pbr, Mat4 model) {
        SurfaceProgram sp = ensurePbr();
        // Material UBO (binding 2).
        Vec4 base = pbr.baseColor();
        Vec3 emissive = pbr.emissive();
        boolean hasTexture = pbr.baseColorTexture() instanceof GlTexture;
        Material.NormalMap normalMap = pbr.normalMap();
        boolean hasNormal = normalMap != null && normalMap.texture() instanceof GlTexture;
        // u_mr.z is the normal scale, and zero is how "no normal map" is spelled:
        // the surface flattens the sample to (0,0,1) and the framework's frame maps
        // that back to the geometric normal exactly, with no branch in the shader
        // and no second program. u_mr.w stays free.
        float normalScale = hasNormal ? normalMap.scale() : 0f;
        materialData.reset()
                .putVec4(base.x(), base.y(), base.z(), base.w())
                .putVec4(emissive.x(), emissive.y(), emissive.z(), hasTexture ? 1f : 0f)
                .putVec4(pbr.metallic(), pbr.roughness(), normalScale, 0f);
        uploadUbo(materialUbo, materialData);

        sp.program().use();
        bindModel(sp, model);
        // Unit 2: 0 is the base colour and 1 is the shadow map, which the pass
        // preparation binds once; taking unit 1 here would clobber it from the first
        // normal-mapped draw onward and the shadows would sample this.
        GL33C.glActiveTexture(GL33C.GL_TEXTURE2);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,
                hasNormal ? ((GlTexture) normalMap.texture()).id() : flatNormalTex);
        GL33C.glUniform1i(sp.normalTex(), 2);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        int texture = hasTexture ? ((GlTexture) pbr.baseColorTexture()).id() : whiteTex;
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glUniform1i(sp.baseColorTex(), 0);
    }

    /**
     * Per-draw state for an application-supplied surface: its own samplers and values,
     * plus the model matrices.
     *
     * <p>The Material UBO is uploaded with neutral contents rather than skipped. The
     * framework declares that block for every program, so leaving it holding the last
     * PBR draw's numbers would make a custom surface that happens to reference
     * {@code u_baseColor} depend on what was drawn before it, which is the kind of
     * bug that only appears in a scene that mixes materials.
     */
    private void bindSurface(Material.Surface surface, Mat4 model) {
        SurfaceProgram sp = ensureSurface(surface);
        materialData.reset()
                .putVec4(1f, 1f, 1f, 1f)
                .putVec4(0f, 0f, 0f, 0f)
                .putVec4(0f, 1f, 0f, 0f);
        uploadUbo(materialUbo, materialData);

        sp.program().use();
        bindModel(sp, model);

        List<Material.Surface.Texture> textures = surface.textures();
        for (int i = 0; i < textures.size(); i++) {
            if (sp.samplers()[i] < 0) {
                continue; // declared but never referenced, so the linker dropped it
            }
            int unit = APP_TEXTURE_UNIT0 + i;
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
            GpuTexture texture = textures.get(i).texture();
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,
                    texture instanceof GlTexture gl ? gl.id() : whiteTex);
            GL33C.glUniform1i(sp.samplers()[i], unit);
        }
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);

        List<Material.Surface.Value> values = surface.values();
        for (int i = 0; i < values.size(); i++) {
            if (sp.values()[i] < 0) {
                continue;
            }
            Vec4 v = values.get(i).value();
            GL33C.glUniform4f(sp.values()[i], v.x(), v.y(), v.z(), v.w());
        }
    }

    private void bindModel(SurfaceProgram sp, Mat4 model) {
        model.toArray(scratch16);
        GL33C.glUniformMatrix4fv(sp.model(), false, scratch16);
        Mat4.normalMatrixInto(model, scratch9);
        GL33C.glUniformMatrix3fv(sp.normalMatrix(), false, scratch9);
    }

    private static void uniform3(int location, Vec3 v) {
        if (location >= 0) {
            GL33C.glUniform3f(location, v.x(), v.y(), v.z());
        }
    }

    private void fillLights(GlRenderPass pass) {
        Vec3 ambient = pass.ambientColor();
        List<Light> lights = pass.lights();
        int count = Math.min(lights.size(), MAX_LIGHTS);
        lightsData.reset()
                .putVec4(ambient.x(), ambient.y(), ambient.z(), 0f)
                .putIVec4(count, 0, 0, 0);
        for (int i = 0; i < count; i++) {
            encodeLight(lights.get(i));
        }
        // Elements [count, MAX_LIGHTS) are left as-is; the shader only reads [0, count).
    }

    /** Packs one light into the std140 struct {@code {vec4 posRange; vec4 dirType; vec4 colorIntensity; vec4 spot;}}. */
    private void encodeLight(Light light) {
        lightsData.alignElement();
        if (light instanceof Light.Directional d) {
            Vec3 dir = d.direction().normalize();
            Vec3 c = d.color();
            lightsData.putVec4(0f, 0f, 0f, 0f)
                    .putVec4(dir.x(), dir.y(), dir.z(), LIGHT_DIRECTIONAL)
                    .putVec4(c.x(), c.y(), c.z(), d.intensity())
                    .putVec4(0f, 0f, 0f, 0f);
        } else if (light instanceof Light.Point p) {
            Vec3 pos = p.position();
            Vec3 c = p.color();
            lightsData.putVec4(pos.x(), pos.y(), pos.z(), p.range())
                    .putVec4(0f, 0f, 0f, LIGHT_POINT)
                    .putVec4(c.x(), c.y(), c.z(), p.intensity())
                    .putVec4(0f, 0f, 0f, 0f);
        } else if (light instanceof Light.Spot s) {
            Vec3 pos = s.position();
            Vec3 dir = s.direction().normalize();
            Vec3 c = s.color();
            float cosInner = (float) Math.cos(s.innerAngleRadians());
            float cosOuter = (float) Math.cos(s.outerAngleRadians());
            lightsData.putVec4(pos.x(), pos.y(), pos.z(), s.range())
                    .putVec4(dir.x(), dir.y(), dir.z(), LIGHT_SPOT)
                    .putVec4(c.x(), c.y(), c.z(), s.intensity())
                    .putVec4(cosInner, cosOuter, 0f, 0f);
        }
    }

    private void uploadUbo(int ubo, Std140Buffer data) {
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, ubo);
        GL33C.glBufferSubData(GL33C.GL_UNIFORM_BUFFER, 0L, data.buffer());
    }

    private SurfaceProgram ensurePbr() {
        if (pbrProgram == null) {
            ensureSharedSurfaceState();
            // The PBR shader is generated from the neutral IR (it was hand-written GLSL
            // by hand). GlslCodegen reproduces that shader; verified by golden +
            // pixel tests.
            pbrProgram = link(GlslCodegen.generate(
                    StandardSurface.metallicRoughness(), TargetProfile.GLSL_330),
                    List.of(), List.of());
            surfacePrograms.put(PBR_KEY, pbrProgram);
        }
        return pbrProgram;
    }

    /**
     * The program for one application-supplied surface, compiled once and cached under
     * the material's own key.
     *
     * <p>Keyed by a string and deliberately not by the material's identity: a record is
     * cheap to rebuild, applications do rebuild them per frame, and an identity-keyed
     * cache would therefore link a fresh program every frame. {@link Material.Raw} does
     * exactly that and it is a trap, not a precedent.
     */
    private SurfaceProgram ensureSurface(Material.Surface surface) {
        SurfaceProgram existing = surfacePrograms.get(surface.key());
        if (existing != null) {
            return existing;
        }
        if (PBR_KEY.equals(surface.key())) {
            throw new IllegalArgumentException("\"" + PBR_KEY + "\" is reserved for the built-in surface");
        }
        ensureSharedSurfaceState();
        List<String> samplerNames = new ArrayList<>();
        for (Material.Surface.Texture texture : surface.textures()) {
            samplerNames.add(texture.name());
        }
        List<String> valueNames = new ArrayList<>();
        for (Material.Surface.Value value : surface.values()) {
            valueNames.add(value.name());
        }
        SurfaceProgram created = link(GlslCodegen.generate(surface.outputs(),
                declarations(samplerNames, valueNames), TargetProfile.GLSL_330),
                samplerNames, valueNames);
        surfacePrograms.put(surface.key(), created);
        return created;
    }

    /** The GLSL declarations for a surface's own named inputs. */
    private static String declarations(List<String> samplers, List<String> values) {
        StringBuilder sb = new StringBuilder("\n// Declared by the application's surface.\n");
        for (String name : samplers) {
            sb.append("uniform sampler2D ").append(name).append(";\n");
        }
        for (String name : values) {
            sb.append("uniform vec4 ").append(name).append(";\n");
        }
        return sb.toString();
    }

    /** Compiles, links, binds the shared uniform blocks and looks every location up. */
    private SurfaceProgram link(GlslCodegen.Generated generated,
                                List<String> samplerNames, List<String> valueNames) {
        ShaderProgram program = ShaderProgram.fromSources(
                generated.vertexSource(), generated.fragmentSource());
        program.bindUniformBlock("Frame", FRAME_BINDING);
        program.bindUniformBlock("Lights", LIGHTS_BINDING);
        program.bindUniformBlock("Material", MATERIAL_BINDING);
        int[] samplers = new int[samplerNames.size()];
        for (int i = 0; i < samplers.length; i++) {
            // Optional: a declared input the surface never references is dropped by the
            // linker, which is legal and not worth failing over.
            samplers[i] = program.optionalUniformLocation(samplerNames.get(i));
        }
        int[] values = new int[valueNames.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = program.optionalUniformLocation(valueNames.get(i));
        }
        return new SurfaceProgram(program,
                program.uniformLocation("u_model"),
                program.uniformLocation("u_normalMatrix"),
                program.optionalUniformLocation("u_baseColorTex"),
                program.optionalUniformLocation("u_normalTex"),
                program.optionalUniformLocation("u_shadowVp"),
                program.optionalUniformLocation("u_shadowMap"),
                program.optionalUniformLocation("u_shadowStrength"),
                program.optionalUniformLocation("u_sh[0]"),
                program.optionalUniformLocation("u_skyColor"),
                program.optionalUniformLocation("u_horizonColor"),
                program.optionalUniformLocation("u_groundColor"),
                program.optionalUniformLocation("u_iblIntensity"),
                program.optionalUniformLocation("u_iblEnabled"),
                samplers, values);
    }

    /** The UBOs, fallback textures and disabled-array defaults every surface program shares. */
    private void ensureSharedSurfaceState() {
        if (frameData != null) {
            return;
        }
        frameUbo = createUbo(FRAME_SIZE, FRAME_BINDING);
        lightsUbo = createUbo(LIGHTS_SIZE, LIGHTS_BINDING);
        materialUbo = createUbo(MATERIAL_SIZE, MATERIAL_BINDING);
        frameData = new Std140Buffer(FRAME_SIZE);
        lightsData = new Std140Buffer(LIGHTS_SIZE);
        materialData = new Std140Buffer(MATERIAL_SIZE);
        whiteTex = createWhiteTexture();
        flatNormalTex = createFlatNormalTexture();
        // The defaults for the attributes most meshes do not carry. This is CONTEXT
        // state, not VAO state (GL reads the current generic value whenever an
        // attribute array is disabled), so once is enough. Without the first, every
        // mesh lacking COLOR would multiply its base colour by (0, 0, 0, 1) and render
        // black; the other three are zero because zero is what a surface is asked to
        // make its identity.
        GL33C.glVertexAttrib4f(VertexAttribute.COLOR.location, 1f, 1f, 1f, 1f);
        GL33C.glVertexAttrib2f(VertexAttribute.UV1.location, 0f, 0f);
        GL33C.glVertexAttrib4f(VertexAttribute.PARAMS.location, 0f, 0f, 0f, 0f);
        GL33C.glVertexAttrib4f(VertexAttribute.PARAMS1.location, 0f, 0f, 0f, 0f);
    }

    private static int createUbo(int sizeBytes, int binding) {
        int ubo = GL33C.glGenBuffers();
        GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, ubo);
        GL33C.glBufferData(GL33C.GL_UNIFORM_BUFFER, sizeBytes, GL33C.GL_DYNAMIC_DRAW);
        GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, binding, ubo);
        return ubo;
    }

    private static int createWhiteTexture() {
        int texture = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer white = MemoryUtil.memAlloc(4);
        try {
            white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, 1, 1, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, white);
        } finally {
            MemoryUtil.memFree(white);
        }
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        return texture;
    }

    /**
     * The sampler-completeness twin of {@code whiteTex}, for the normal map: one
     * texel of (128, 128, 255), which decodes to (0.0039, 0.0039, 1) rather than an
     * exact (0, 0, 1), the usual off-by-half-a-texel of 8-bit normal encoding, and
     * harmless after normalize. It never actually tilts anything, because a material
     * with no map also carries a normal scale of 0.
     */
    private static int createFlatNormalTexture() {
        int texture = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer flat = MemoryUtil.memAlloc(4);
        try {
            flat.put((byte) 0x80).put((byte) 0x80).put((byte) 0xFF).put((byte) 0xFF).flip();
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, 1, 1, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, flat);
        } finally {
            MemoryUtil.memFree(flat);
        }
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        return texture;
    }

    // --------------------------------------------------------------- shadows

    private static final String DEPTH_VERT = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            layout(location = 0) in vec3 a_pos;
            uniform mat4 u_depthMvp;
            void main() {
                gl_Position = u_depthMvp * vec4(a_pos, 1.0);
            }
            """;

    private static final String DEPTH_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Depth-only.
            void main() {
            }
            """;

    /** Renders scene depth from the light into the shadow map; leaves the target FBO bound. */
    private void renderShadowMap(GlRenderTarget target, GlRenderPass pass) {
        ensureShadow();
        Vec3 dir = pass.shadowDirection().normalize();
        Vec3 center = pass.shadowCenter();
        float r = Math.max(0.05f, pass.shadowRadius());
        Vec3 up = Math.abs(dir.y()) > 0.99f ? Vec3.UNIT_Z : Vec3.UNIT_Y;
        Vec3 lightPos = center.add(dir.mul(r * 2f));
        Mat4 view = Mat4.lookAt(lightPos, center, up);
        Mat4 proj = Mat4.orthographic(-r, r, -r, r, r * 0.5f, r * 3.5f);
        Mat4 shadowVp = proj.multiply(view);

        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, shadowMap.framebuffer());
        GL33C.glViewport(0, 0, shadowMap.size(), shadowMap.size());
        GL33C.glClear(GL33C.GL_DEPTH_BUFFER_BIT);
        GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL); // reduce shadow acne
        GL33C.glPolygonOffset(2.2f, 4f);
        depthProgram.use();
        depthDraws(pass.draws(), shadowVp);
        depthDraws(pass.shadowOnlyDraws(), shadowVp); // camera-culled casters still occlude
        GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_FILL);

        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
        GL33C.glViewport(0, 0, target.widthPx(), target.heightPx());
        pass.setShadowResult(shadowVp, shadowMap.depthTexture());
    }

    private void depthDraws(List<GlRenderPass.Draw> draws, Mat4 shadowVp) {
        for (int i = 0; i < draws.size(); i++) {
            GlRenderPass.Draw d = draws.get(i);
            Mat4.multiplyInto(shadowVp, d.model(), scratch16);
            GL33C.glUniformMatrix4fv(uDepthMvp, false, scratch16);
            d.mesh().drawTriangles();
        }
    }

    private void ensureShadow() {
        if (shadowMap != null) {
            return;
        }
        depthProgram = ShaderProgram.fromSources(DEPTH_VERT, DEPTH_FRAG);
        uDepthMvp = depthProgram.uniformLocation("u_depthMvp");
        shadowMap = new GlShadowMap(SHADOW_SIZE);
    }

    private boolean shadowInputsUnchanged(GlRenderPass pass) {
        if (shadowMap == null || lastShadowVp == null) {
            return false;
        }
        if (!pass.shadowDirection().equals(lastShadowDir)
                || !pass.shadowCenter().equals(lastShadowCenter)
                || pass.shadowRadius() != lastShadowRadius) {
            return false;
        }
        return sameDraws(pass.draws(), lastShadowDraws, lastShadowRevisions)
                && sameDraws(pass.shadowOnlyDraws(), lastShadowOnlyDraws, lastShadowOnlyRevisions);
    }

    private static boolean sameDraws(List<GlRenderPass.Draw> now, List<GlRenderPass.Draw> before,
                                     int[] revisions) {
        if (now.size() != before.size()) {
            return false;
        }
        for (int i = 0; i < now.size(); i++) {
            GlRenderPass.Draw a = now.get(i);
            GlRenderPass.Draw b = before.get(i);
            // Compared by identity: Scene3D caches world matrices, so a static
            // scene presents the same Mat4 instances; any recompute (or a
            // different scene sharing this context) forces a fresh depth pass.
            if (a.mesh() != b.mesh() || a.model() != b.model()) {
                return false;
            }
            // Identity is not enough for a DYNAMIC mesh: it is rewritten in place,
            // so the same GlMesh and the same (usually cached) Mat4 come back every
            // frame while the geometry moves. Without the revision the map would
            // never be redrawn again: a stale shadow, with nothing to see in the
            // picture that says so.
            if (a.mesh().revision() != revisions[i]) {
                return false;
            }
        }
        return true;
    }

    /** Snapshots the revision of every draw's mesh, so the next frame can spot a rewrite. */
    private static int[] revisionsOf(List<GlRenderPass.Draw> draws) {
        int[] revisions = new int[draws.size()];
        for (int i = 0; i < draws.size(); i++) {
            revisions[i] = draws.get(i).mesh().revision();
        }
        return revisions;
    }

    private void rememberShadowInputs(GlRenderPass pass) {
        lastShadowDir = pass.shadowDirection();
        lastShadowCenter = pass.shadowCenter();
        lastShadowRadius = pass.shadowRadius();
        lastShadowVp = pass.shadowVp();
        lastShadowDraws.clear();
        lastShadowDraws.addAll(pass.draws());
        lastShadowOnlyDraws.clear();
        lastShadowOnlyDraws.addAll(pass.shadowOnlyDraws());
        lastShadowRevisions = revisionsOf(lastShadowDraws);
        lastShadowOnlyRevisions = revisionsOf(lastShadowOnlyDraws);
    }

    // --------------------------------------------------------------- skybox

    private static final String SKYBOX_VERT = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Fullscreen triangle.
            out vec2 v_ndc;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_ndc = p * 2.0 - 1.0;
                gl_Position = vec4(v_ndc, 1.0, 1.0);
            }
            """;

    // Package-private: ShaderColorSpaceTest locks the color-space contract onto it.
    static final String SKYBOX_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Procedural sky.
            // Writes linear scene-referred radiance (opaque, so trivially premultiplied);
            // the display transform runs in the 2D composite (ADR 004).
            in vec2 v_ndc;
            uniform mat4 u_invViewProj;
            uniform vec3 u_camPos;
            uniform vec3 u_skyColor;
            uniform vec3 u_horizonColor;
            uniform vec3 u_groundColor;
            uniform float u_iblIntensity;
            out vec4 o_color;

            vec3 skyRadiance(vec3 dir) {
                float t = dir.y;
                vec3 c = t >= 0.0 ? mix(u_horizonColor, u_skyColor, t) : mix(u_horizonColor, u_groundColor, -t);
                return c * u_iblIntensity;
            }
            void main() {
                vec4 world = u_invViewProj * vec4(v_ndc, 1.0, 1.0);
                vec3 dir = normalize(world.xyz / world.w - u_camPos);
                o_color = vec4(skyRadiance(dir), 1.0);
            }
            """;

    /** Draws the procedural sky as a fullscreen triangle (depth test disabled by the caller). */
    private void renderSkybox(GlRenderPass pass) {
        ensureSkybox();
        Environment env = pass.environment();
        skyboxProgram.use();
        // invert() still allocates (once per frame with a skybox, which is fine); the
        // copy into the reused scratch avoids the extra toArray() clone.
        pass.viewProjection().invert().toArray(scratch16);
        GL33C.glUniformMatrix4fv(uSkyInvViewProj, false, scratch16);
        Vec3 cam = pass.cameraPos();
        GL33C.glUniform3f(uSkyCamPos, cam.x(), cam.y(), cam.z());
        uniform3(uSkySkyColor, env.sky());
        uniform3(uSkyHorizonColor, env.horizon());
        uniform3(uSkyGroundColor, env.ground());
        GL33C.glUniform1f(uSkyIblIntensity, env.intensity());
        GL33C.glBindVertexArray(skyboxVao);
        GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
        GL33C.glBindVertexArray(0);
    }

    private void ensureSkybox() {
        if (skyboxProgram != null) {
            return;
        }
        skyboxProgram = ShaderProgram.fromSources(SKYBOX_VERT, SKYBOX_FRAG);
        uSkyInvViewProj = skyboxProgram.uniformLocation("u_invViewProj");
        uSkyCamPos = skyboxProgram.uniformLocation("u_camPos");
        uSkySkyColor = skyboxProgram.uniformLocation("u_skyColor");
        uSkyHorizonColor = skyboxProgram.uniformLocation("u_horizonColor");
        uSkyGroundColor = skyboxProgram.uniformLocation("u_groundColor");
        uSkyIblIntensity = skyboxProgram.uniformLocation("u_iblIntensity");
        skyboxVao = GL33C.glGenVertexArrays();
    }

    private void ensureUnlit() {
        if (unlitProgram != null) {
            return;
        }
        unlitProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/mesh_unlit.vert",
                "/limn/backend/lwjgl/shaders/mesh_unlit.frag");
        uUnlitMvp = unlitProgram.uniformLocation("u_mvp");
        uUnlitColor = unlitProgram.uniformLocation("u_color");
    }

    private void ensureLit() {
        if (litProgram != null) {
            return;
        }
        litProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/mesh_lit.vert",
                "/limn/backend/lwjgl/shaders/mesh_lit.frag");
        uLitMvp = litProgram.uniformLocation("u_mvp");
        uLitNormal = litProgram.uniformLocation("u_normalMatrix");
        uLitColor = litProgram.uniformLocation("u_color");
        uLitLightDir = litProgram.uniformLocation("u_lightDir");
        uLitLightColor = litProgram.uniformLocation("u_lightColor");
        uLitAmbient = litProgram.uniformLocation("u_ambient");
    }

    private RawProgram ensureRaw(Material.Raw raw) {
        RawProgram existing = rawPrograms.get(raw);
        if (existing != null) {
            return existing;
        }
        ShaderProgram program = ShaderProgram.fromSources(raw.vertexSource(), raw.fragmentSource());
        RawProgram rp = new RawProgram(program,
                program.optionalUniformLocation("u_mvp"),
                program.optionalUniformLocation("u_model"),
                program.optionalUniformLocation("u_normalMatrix"));
        rawPrograms.put(raw, rp);
        return rp;
    }

    RenderStats stats() {
        RenderStats total = RenderStats.EMPTY;
        for (GlRenderTarget target : targets) {
            total = total.plus(target.stats());
        }
        for (GlTexture texture : textures) {
            total = total.plus(texture.stats());
        }
        if (shadowMap != null) {
            total = total.plus(shadowMap.stats());
        }
        if (cubeProgram != null) {
            total = total.plus(new RenderStats(0, 36L * 9 * Float.BYTES)); // demo VBO
        }
        return total;
    }

    /** 3D-specific footprint + last-frame workload, broken out for the perf footer. */
    Render3DStats render3DStats() {
        long bytes = 0;
        for (GlRenderTarget target : targets) {
            bytes += target.stats().textureBytes();
        }
        for (GlTexture texture : textures) {
            bytes += texture.stats().textureBytes();
        }
        for (GlMesh mesh : meshes) {
            bytes += mesh.gpuBytes();
        }
        if (frameData != null) {
            bytes += FRAME_SIZE + LIGHTS_SIZE + MATERIAL_SIZE; // std140 UBOs
        }
        if (shadowMap != null) {
            bytes += shadowMap.stats().textureBytes();
        }
        return new Render3DStats(meshes.size(), textures.size(), targets.size(),
                bytes, lastDrawCalls, lastTriangles);
    }

    void dispose() {
        for (GlRenderTarget target : new ArrayList<>(targets)) {
            target.dispose(); // removes itself from `targets`
        }
        targets.clear();
        for (GlMesh mesh : new ArrayList<>(meshes)) {
            mesh.dispose(); // removes itself from `meshes`
        }
        meshes.clear();
        for (GlTexture texture : new ArrayList<>(textures)) {
            texture.dispose(); // removes itself from `textures`
        }
        textures.clear();
        if (unlitProgram != null) {
            unlitProgram.close();
            unlitProgram = null;
        }
        if (litProgram != null) {
            litProgram.close();
            litProgram = null;
        }
        for (RawProgram rp : rawPrograms.values()) {
            rp.program().close();
        }
        rawPrograms.clear();
        for (SurfaceProgram sp : surfacePrograms.values()) {
            sp.program().close();
        }
        surfacePrograms.clear();
        pbrProgram = null;
        passPrograms.clear();
        if (frameData != null) {
            frameData = null;
            GL33C.glDeleteBuffers(frameUbo);
            GL33C.glDeleteBuffers(lightsUbo);
            GL33C.glDeleteBuffers(materialUbo);
            if (whiteTex != 0) {
                GL33C.glDeleteTextures(whiteTex);
                whiteTex = 0;
            }
            if (flatNormalTex != 0) {
                GL33C.glDeleteTextures(flatNormalTex);
                flatNormalTex = 0;
            }
        }
        if (depthProgram != null) {
            depthProgram.close();
            depthProgram = null;
        }
        if (shadowMap != null) {
            shadowMap.dispose();
            shadowMap = null;
        }
        if (skyboxProgram != null) {
            skyboxProgram.close();
            skyboxProgram = null;
            GL33C.glDeleteVertexArrays(skyboxVao);
        }
        if (bloomBrightProgram != null) {
            bloomBrightProgram.close();
            bloomBrightProgram = null;
            bloomBlurProgram.close();
            bloomBlurProgram = null;
            bloomCombineProgram.close();
            bloomCombineProgram = null;
            GL33C.glDeleteVertexArrays(bloomVao);
        }
        if (cubeProgram != null) {
            GL33C.glDeleteVertexArrays(cubeVao);
            GL33C.glDeleteBuffers(cubeVbo);
            cubeProgram.close();
            cubeProgram = null;
        }
        if (lineProgram != null) {
            GL33C.glDeleteVertexArrays(lineVao);
            GL33C.glDeleteBuffers(lineVbo);
            lineProgram.close();
            lineProgram = null;
        }
    }

    // ------------------------------------------------------------ debug lines

    private static final String LINE_VERT = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            layout(location = 0) in vec3 a_pos;
            layout(location = 3) in vec4 a_color;
            uniform mat4 u_viewProjection;
            out vec4 v_color;
            void main() {
                v_color = a_color;
                gl_Position = u_viewProjection * vec4(a_pos, 1.0);
            }
            """;

    // Package-private: ShaderColorSpaceTest locks the color-space contract onto it.
    static final String LINE_FRAG = """
            #version 330 core
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            // The target is linear (ADR 004): decode the authored-sRGB color. RGB
            // only: debug colors stay STRAIGHT alpha (drawDebugLines sets its own
            // SRC_ALPHA blend), so alpha is untouched and nothing premultiplies.
            in vec4 v_color;
            out vec4 fragColor;
            vec3 srgbToLinear(vec3 c) {
                vec3 lo = c / 12.92;
                vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
                return mix(lo, hi, step(vec3(0.04045), c));
            }
            void main() {
                fragColor = vec4(srgbToLinear(v_color.rgb), v_color.a);
            }
            """;

    /**
     * Draws the pass's immediate debug-line batches: depth-tested batches first
     * (occluded by the meshes just drawn), then overlay batches with depth off.
     * World-space vertices, one streaming VBO re-uploaded per batch (GlBatch's
     * model); 1 px GL_LINES (the MSAA target antialiases them).
     *
     * <p>Sets its own blend state rather than inheriting the pass's. Debug colours
     * are authored STRAIGHT (plain rgba, unlike the premultiplied output of the mesh
     * shaders), so they need {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA}, and a
     * translucent gizmo used to be silently opaque, because the pass ran with
     * blending off entirely.
     */
    private void drawDebugLines(GlRenderPass pass) {
        List<GlRenderPass.DebugBatch> batches = pass.debugBatches();
        if (batches.isEmpty()) {
            return;
        }
        ensureLineResources();
        GL33C.glEnable(GL33C.GL_BLEND);
        GL33C.glBlendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA,
                GL33C.GL_ZERO, GL33C.GL_ONE);
        lineProgram.use();
        pass.viewProjection().toArray(scratch16);
        GL33C.glUniformMatrix4fv(uLineViewProjection, false, scratch16);
        GL33C.glBindVertexArray(lineVao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, lineVbo);
        boolean depthOff = false;
        // Two passes over the list keep submission order within each bucket.
        for (int wantDepth = 1; wantDepth >= 0; wantDepth--) {
            for (int i = 0; i < batches.size(); i++) {
                GlRenderPass.DebugBatch batch = batches.get(i);
                if (batch.depthTested() != (wantDepth == 1)) {
                    continue;
                }
                if (!batch.depthTested() && !depthOff) {
                    GL33C.glDisable(GL33C.GL_DEPTH_TEST);
                    depthOff = true;
                }
                // Whole-array upload (orphaning); only vertexCount vertices are drawn.
                GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, batch.vertices(), GL33C.GL_STREAM_DRAW);
                GL33C.glDrawArrays(GL33C.GL_LINES, 0, batch.vertexCount());
                passDrawCalls++;
            }
        }
        if (depthOff) {
            GL33C.glEnable(GL33C.GL_DEPTH_TEST);
        }
        GL33C.glDisable(GL33C.GL_BLEND);
        GL33C.glBindVertexArray(0);
    }

    private void ensureLineResources() {
        if (lineProgram != null) {
            return;
        }
        lineProgram = ShaderProgram.fromSources(LINE_VERT, LINE_FRAG);
        uLineViewProjection = lineProgram.uniformLocation("u_viewProjection");
        lineVao = GL33C.glGenVertexArrays();
        lineVbo = GL33C.glGenBuffers();
        GL33C.glBindVertexArray(lineVao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, lineVbo);
        int stride = limn.render3d.DebugDraw.FLOATS_PER_VERTEX * Float.BYTES;
        GL33C.glEnableVertexAttribArray(0); // position
        GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, stride, 0);
        GL33C.glEnableVertexAttribArray(3); // color
        GL33C.glVertexAttribPointer(3, 4, GL33C.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL33C.glBindVertexArray(0);
    }

    // ------------------------------------------------------------- demo cube

    void renderDemoScene(RenderTarget targetHandle, double timeSeconds) {
        if (!(targetHandle instanceof GlRenderTarget target)) {
            return;
        }
        ensureCubeGl();

        int prevFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, prevViewport);

        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
            GL33C.glViewport(0, 0, target.widthPx(), target.heightPx());
            GL33C.glEnable(GL33C.GL_DEPTH_TEST);
            GL33C.glDepthFunc(GL33C.GL_LESS);
            GL33C.glDisable(GL33C.GL_BLEND);
            GL33C.glDisable(GL33C.GL_CULL_FACE);
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST); // 2D damage scissor must not clip this pass
            // Authored sRGB, decoded on write: the target is linear (ADR 004).
            GL33C.glClearColor(ColorSpace.srgbToLinear(0.13f), ColorSpace.srgbToLinear(0.15f),
                    ColorSpace.srgbToLinear(0.20f), 1.0f);
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT | GL33C.GL_DEPTH_BUFFER_BIT);

            float aspect = (float) target.widthPx() / target.heightPx();
            Mat4 proj = Mat4.perspective((float) Math.toRadians(42), aspect, 0.1f, 20f);
            Mat4 view = Mat4.translation(new Vec3(0, 0, -3.6f));
            float t = (float) timeSeconds;
            Mat4 model = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Y, t * 0.9f))
                    .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_X, t * 0.55f)));
            Mat4 mvp = proj.multiply(view).multiply(model);

            cubeProgram.use();
            GL33C.glUniformMatrix4fv(uMvp, false, mvp.toArray());
            GL33C.glUniformMatrix4fv(uModel, false, model.toArray());
            GL33C.glBindVertexArray(cubeVao);
            GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 36);
            GL33C.glBindVertexArray(0);
            lastDrawCalls = 1;
            lastTriangles = 12; // 36 vertices / 3

            target.setExposure(1f); // no pass ran; the composite must not reuse a stale exposure
            target.resolve(); // MSAA → single-sample color texture
        } finally {
            // Restore only what the 2D flush won't (framebuffer, viewport, depth
            // test); in a finally, like render(), so a throw cannot leave the
            // offscreen FBO bound for the 2D pass.
            GL33C.glDisable(GL33C.GL_DEPTH_TEST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, prevFbo);
            GL33C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }

    private void ensureCubeGl() {
        if (cubeProgram != null) {
            return;
        }
        cubeProgram = ShaderProgram.fromResources(
                "/limn/backend/lwjgl/shaders/cube.vert",
                "/limn/backend/lwjgl/shaders/cube.frag");
        uMvp = cubeProgram.uniformLocation("u_mvp");
        uModel = cubeProgram.uniformLocation("u_model");

        float[] vertices = cubeVertices();
        cubeVao = GL33C.glGenVertexArrays();
        cubeVbo = GL33C.glGenBuffers();
        GL33C.glBindVertexArray(cubeVao);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, cubeVbo);
        GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, vertices, GL33C.GL_STATIC_DRAW);
        int stride = 9 * Float.BYTES;
        GL33C.glVertexAttribPointer(0, 3, GL33C.GL_FLOAT, false, stride, 0);
        GL33C.glEnableVertexAttribArray(0);
        GL33C.glVertexAttribPointer(1, 3, GL33C.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL33C.glEnableVertexAttribArray(1);
        GL33C.glVertexAttribPointer(2, 3, GL33C.GL_FLOAT, false, stride, 6L * Float.BYTES);
        GL33C.glEnableVertexAttribArray(2);
        GL33C.glBindVertexArray(0);
        GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, 0);
    }

    private static float[] cubeVertices() {
        float h = 0.8f;
        float[] v = new float[36 * 9];
        int[] w = {0};
        face(v, w, 1, 0, 0, 0.90f, 0.32f, 0.38f,
                h, -h, h, h, -h, -h, h, h, -h, h, h, h);
        face(v, w, -1, 0, 0, 0.36f, 0.80f, 0.48f,
                -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h);
        face(v, w, 0, 1, 0, 0.32f, 0.58f, 0.96f,
                -h, h, h, h, h, h, h, h, -h, -h, h, -h);
        face(v, w, 0, -1, 0, 0.96f, 0.78f, 0.32f,
                -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h);
        face(v, w, 0, 0, 1, 0.66f, 0.46f, 0.96f,
                -h, -h, h, h, -h, h, h, h, h, -h, h, h);
        face(v, w, 0, 0, -1, 0.30f, 0.82f, 0.86f,
                h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h);
        return v;
    }

    private static void face(float[] v, int[] w, float nx, float ny, float nz,
                             float r, float g, float b,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3) {
        vertex(v, w, x0, y0, z0, nx, ny, nz, r, g, b);
        vertex(v, w, x1, y1, z1, nx, ny, nz, r, g, b);
        vertex(v, w, x2, y2, z2, nx, ny, nz, r, g, b);
        vertex(v, w, x0, y0, z0, nx, ny, nz, r, g, b);
        vertex(v, w, x2, y2, z2, nx, ny, nz, r, g, b);
        vertex(v, w, x3, y3, z3, nx, ny, nz, r, g, b);
    }

    private static void vertex(float[] v, int[] w, float x, float y, float z,
                               float nx, float ny, float nz, float r, float g, float b) {
        int i = w[0];
        v[i] = x; v[i + 1] = y; v[i + 2] = z;
        v[i + 3] = nx; v[i + 4] = ny; v[i + 5] = nz;
        v[i + 6] = r; v[i + 7] = g; v[i + 8] = b;
        w[0] = i + 9;
    }
}
