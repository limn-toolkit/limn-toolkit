package limn.backend.lwjgl;

import limn.backend.RenderStats;
import limn.render3d.ColorSpace;
import limn.render3d.RenderTarget;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;

/**
 * A {@link RenderTarget} backed by FBOs. When {@code samples > 1} it renders into
 * a multisample FBO (color + depth renderbuffers) and resolves into a
 * single-sample color texture via {@code glBlitFramebuffer}; otherwise it renders
 * straight into that texture's FBO. {@link GlCanvas#drawSurface} samples the
 * (resolved) color texture. Per-GL-context; owned by a {@link Gl3DContext}.
 */
final class GlRenderTarget implements RenderTarget {

    private final Gl3DContext owner;
    private int width;
    private int height;
    private int samples;
    private float exposure = 1f; // recorded by the pass, read by the composite (ADR 004 §3.4)

    // Single-sample resolve target: the texture the 2D pipeline composites.
    private int resolveFbo;
    private int colorTex;
    private int resolveDepthRbo; // only when samples == 1 (depth lives on the resolve FBO)

    // Multisample render target, only when samples > 1.
    private int msaaFbo;
    private int msaaColorRbo;
    private int msaaDepthRbo;

    // Bloom chain (ADR 005): two half-res RGBA16F ping-pong targets, the
    // target's own (sized from it, dying with it), allocated lazily on first
    // use with bloom enabled so a target that never blooms never pays.
    private int bloomFboA;
    private int bloomTexA;
    private int bloomFboB;
    private int bloomTexB;

    GlRenderTarget(Gl3DContext owner, int width, int height, int samples) {
        this.owner = owner;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.samples = Math.max(1, samples);
        allocate();
    }

    private void allocate() {
        resolveFbo = GL33C.glGenFramebuffers();
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, resolveFbo);
        colorTex = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, colorTex);
        // RGBA16F: the target holds linear scene-referred light (ADR 004), and
        // 8 bits of linear crush the darks and clip everything over 1. The type
        // is HALF_FLOAT for the null upload; it must still be legal for the
        // internal format.
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA16F, width, height, 0,
                GL33C.GL_RGBA, GL33C.GL_HALF_FLOAT, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, colorTex, 0);

        if (samples > 1) {
            msaaFbo = GL33C.glGenFramebuffers();
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, msaaFbo);
            msaaColorRbo = GL33C.glGenRenderbuffers();
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, msaaColorRbo);
            GL33C.glRenderbufferStorageMultisample(GL33C.GL_RENDERBUFFER, samples, GL33C.GL_RGBA16F, width, height);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_RENDERBUFFER, msaaColorRbo);
            msaaDepthRbo = GL33C.glGenRenderbuffers();
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, msaaDepthRbo);
            GL33C.glRenderbufferStorageMultisample(GL33C.GL_RENDERBUFFER, samples, GL33C.GL_DEPTH_COMPONENT24, width, height);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, msaaDepthRbo);
            requireComplete("msaa");
        } else {
            resolveDepthRbo = GL33C.glGenRenderbuffers();
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, resolveDepthRbo);
            GL33C.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH_COMPONENT24, width, height);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, resolveDepthRbo);
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, resolveFbo);
        requireComplete("resolve");
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
    }

    private void requireComplete(String which) {
        int status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            deleteGl();
            throw new IllegalStateException("3D " + which + " FBO incomplete: 0x" + Integer.toHexString(status));
        }
    }

    /** The FBO 3D content renders into (multisample when MSAA is on). */
    int renderFramebuffer() {
        return samples > 1 ? msaaFbo : resolveFbo;
    }

    /** Resolves the multisample color into the single-sample texture (no-op without MSAA). */
    void resolve() {
        if (samples <= 1) {
            return;
        }
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, msaaFbo);
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, resolveFbo);
        GL33C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL33C.GL_COLOR_BUFFER_BIT, GL33C.GL_NEAREST);
    }

    int colorTexture() {
        return colorTex;
    }

    /**
     * The single-sample FBO whose color attachment is {@link #colorTexture()}: the combine's
     * destination.
     */
    int resolveFramebuffer() {
        return resolveFbo;
    }

    // ------------------------------------------------------------------ bloom

    int bloomWidthPx() {
        return Math.max(1, width / 2);
    }

    int bloomHeightPx() {
        return Math.max(1, height / 2);
    }

    boolean bloomAllocated() {
        return bloomFboA != 0;
    }

    int bloomFramebufferA() {
        return bloomFboA;
    }

    int bloomTextureA() {
        return bloomTexA;
    }

    int bloomFramebufferB() {
        return bloomFboB;
    }

    int bloomTextureB() {
        return bloomTexB;
    }

    /**
     * Allocates the half-res ping-pong pair on first bloomy frame (leaves the
     * FRAMEBUFFER binding changed; the bloom chain binds its own next).
     * Half-res RGBA16F like the main texture: the chain carries premultiplied
     * linear scene-referred RGBA, alpha included (ADR 005 finding 3).
     */
    void ensureBloomTargets() {
        if (bloomFboA != 0) {
            return;
        }
        int[] fboTex = createBloomAttachment();
        bloomFboA = fboTex[0];
        bloomTexA = fboTex[1];
        fboTex = createBloomAttachment();
        bloomFboB = fboTex[0];
        bloomTexB = fboTex[1];
    }

    private int[] createBloomAttachment() {
        int fbo = GL33C.glGenFramebuffers();
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        int tex = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, tex);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA16F, bloomWidthPx(), bloomHeightPx(),
                0, GL33C.GL_RGBA, GL33C.GL_HALF_FLOAT, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, tex, 0);
        requireComplete("bloom");
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        return new int[]{fbo, tex};
    }

    // -------------------------------------------------------------- readback

    @Override
    public limn.graphics.Image readDisplayReferred(int x, int y, int w, int h) {
        float[] scene = readFloats(x, y, w, h);
        byte[] rgba = new byte[w * h * 4];
        float e = exposure;
        for (int i = 0, n = w * h; i < n; i++) {
            float a = scene[i * 4 + 3];
            if (a <= 0f) {
                continue; // straight alpha has no colour to recover here; leave the pixel clear
            }
            // Un-premultiply before the transform, exactly as the composite does: ACES is
            // non-linear, so tonemapping colour that has already been scaled by alpha maps a
            // half-transparent surface differently from an opaque one of the same colour.
            for (int c = 0; c < 3; c++) {
                float straight = scene[i * 4 + c] / a;
                rgba[i * 4 + c] = quantize(ColorSpace.displayTransform(straight, e));
            }
            rgba[i * 4 + 3] = quantize(a);
        }
        return new limn.graphics.Image(w, h, rgba);
    }

    @Override
    public limn.graphics.ScenePixels readSceneReferred(int x, int y, int w, int h) {
        return new limn.graphics.ScenePixels(w, h, readFloats(x, y, w, h));
    }

    private static byte quantize(float unitValue) {
        return (byte) Math.round(Math.min(1f, Math.max(0f, unitValue)) * 255f);
    }

    /**
     * The rectangle's RGBA as stored (linear, premultiplied), rearranged from GL's bottom-up rows
     * into the top-down order every pixel type in the toolkit uses.
     */
    private float[] readFloats(int x, int y, int w, int h) {
        if (colorTex == 0) {
            throw new IllegalStateException("render target has been disposed");
        }
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > width || y + h > height) {
            throw new IllegalArgumentException("read rectangle " + x + "," + y + " " + w + "x" + h
                    + " is not inside " + width + "x" + height);
        }
        // Both bindings are saved because resolve() below changes both, and a read must leave the
        // GL state exactly as it found it; it can be called from anywhere inside a frame.
        int previousDraw = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        resolve(); // MSAA: the composite shows the resolved image, so a read must too
        java.nio.FloatBuffer buffer = org.lwjgl.system.MemoryUtil.memAllocFloat(w * h * 4);
        try {
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, resolveFbo);
            GL33C.glReadBuffer(GL33C.GL_COLOR_ATTACHMENT0);
            // GL's origin is bottom-left: the top-down rectangle's first row is the last one here.
            GL33C.glReadPixels(x, height - (y + h), w, h, GL33C.GL_RGBA, GL33C.GL_FLOAT, buffer);
            float[] pixels = new float[w * h * 4];
            for (int row = 0; row < h; row++) {
                buffer.position((h - 1 - row) * w * 4);
                buffer.get(pixels, row * w * 4, w * 4);
            }
            return pixels;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, previousRead);
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    /** Set by the pass at draw time; {@link GlCanvas#drawSurface} feeds it to the display transform. */
    void setExposure(float exposure) {
        this.exposure = exposure;
    }

    @Override
    public float exposure() {
        return exposure;
    }

    @Override
    public int widthPx() {
        return width;
    }

    @Override
    public int heightPx() {
        return height;
    }

    @Override
    public int samples() {
        return samples;
    }

    @Override
    public void resize(int widthPx, int heightPx) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        if (w == width && h == height) {
            return;
        }
        deleteGl();
        width = w;
        height = h;
        allocate();
    }

    @Override
    public RenderStats stats() {
        long px = (long) width * height;
        long bytes = px * 8; // resolve color texture (RGBA16F)
        int textures = 1;
        if (samples > 1) {
            bytes += px * samples * (8L + 4L); // msaa color (RGBA16F) + depth24 renderbuffers
            textures += 2;
        } else {
            bytes += px * 4; // depth24 renderbuffer (~4 bytes/px)
            textures += 1;
        }
        if (bloomFboA != 0) {
            // The bloom chain's half-res pair (ADR 005 §2.2): the perf monitor
            // must show what bloom costs, or the cost is invisible exactly when
            // someone is looking for it.
            bytes += 2L * bloomWidthPx() * bloomHeightPx() * 8; // 2 × RGBA16F
            textures += 2;
        }
        return new RenderStats(textures, bytes);
    }

    @Override
    public void dispose() {
        deleteGl();
        owner.forget(this);
    }

    private void deleteGl() {
        GL33C.glDeleteFramebuffers(resolveFbo);
        GL33C.glDeleteTextures(colorTex);
        colorTex = 0; // readback's disposed check; allocate() reassigns it on the resize path
        if (resolveDepthRbo != 0) {
            GL33C.glDeleteRenderbuffers(resolveDepthRbo);
        }
        if (samples > 1) {
            GL33C.glDeleteFramebuffers(msaaFbo);
            GL33C.glDeleteRenderbuffers(msaaColorRbo);
            GL33C.glDeleteRenderbuffers(msaaDepthRbo);
        }
        if (bloomFboA != 0) {
            // Zeroed, unlike the main attachments: resize() re-runs allocate()
            // immediately, but the bloom pair stays lazy until the next bloomy
            // frame asks for it (at the new size).
            GL33C.glDeleteFramebuffers(bloomFboA);
            GL33C.glDeleteFramebuffers(bloomFboB);
            GL33C.glDeleteTextures(bloomTexA);
            GL33C.glDeleteTextures(bloomTexB);
            bloomFboA = 0;
            bloomFboB = 0;
            bloomTexA = 0;
            bloomTexB = 0;
        }
    }
}
