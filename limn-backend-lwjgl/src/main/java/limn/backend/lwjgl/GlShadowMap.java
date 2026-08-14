package limn.backend.lwjgl;

import limn.backend.RenderStats;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;

/**
 * A depth-only framebuffer for directional shadow mapping: a square
 * {@code DEPTH_COMPONENT24} texture with no color attachment. The scene is
 * rendered into it from the light, and the PBR shader samples it (as a plain
 * {@code sampler2D}, comparing depths with PCF). Per-GL-context; owned by a
 * {@link Gl3DContext}.
 */
final class GlShadowMap {

    private final int size;
    private int fbo;
    private int depthTexture;

    GlShadowMap(int size) {
        this.size = size;
        depthTexture = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, depthTexture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_DEPTH_COMPONENT24, size, size, 0,
                GL33C.GL_DEPTH_COMPONENT, GL33C.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);

        fbo = GL33C.glGenFramebuffers();
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT,
                GL33C.GL_TEXTURE_2D, depthTexture, 0);
        GL33C.glDrawBuffer(GL33C.GL_NONE); // depth-only: no color buffer
        GL33C.glReadBuffer(GL33C.GL_NONE);
        int status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            dispose();
            throw new IllegalStateException("shadow FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
    }

    int framebuffer() {
        return fbo;
    }

    int depthTexture() {
        return depthTexture;
    }

    int size() {
        return size;
    }

    RenderStats stats() {
        return new RenderStats(1, (long) size * size * 3); // depth24 ≈ 3 bytes/texel
    }

    void dispose() {
        GL33C.glDeleteFramebuffers(fbo);
        GL33C.glDeleteTextures(depthTexture);
    }
}
