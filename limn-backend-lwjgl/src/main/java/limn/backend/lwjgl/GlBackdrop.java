package limn.backend.lwjgl;

import limn.backend.RenderStats;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;

/**
 * The copy of the window's framebuffer that a backdrop effect samples. One texture per canvas,
 * the size of the framebuffer, allocated the first time an effect is drawn and never by a window
 * that draws none.
 *
 * <p>Framebuffer-sized rather than region-sized on purpose: the copy then lands at the same
 * coordinates it occupies on screen, so a fragment finds itself in the texture with
 * {@code gl_FragCoord.xy / u_viewport} and no rectangle has to be plumbed through to map it. Only
 * the requested region is copied; the rest of the texture holds whatever the last effect left
 * there, which is why the shader clamps its sampling to the region it was told about.
 */
final class GlBackdrop {

    private int texture;
    private int framebuffer; // lazy: only a glass inside a layer draws into the copy
    private int warpedTexture; // lazy: only a glass inside a rotated or skewed layer reads one
    private int warpedFramebuffer;
    private int width;
    private int height;

    /**
     * Copies {@code w × h} pixels of the current read buffer, at ({@code x}, {@code yBottomUp}) in
     * GL's bottom-up coordinates, into the same place in the backdrop texture.
     *
     * <p>The caller must have flushed pending geometry first: this reads the framebuffer, and
     * anything still sitting in the batch is not in it yet. That flush is the whole cost of the
     * feature and it belongs to the caller, which knows whether it has already paid it.
     *
     * @return the texture to sample, ready to be bound
     */
    int capture(int framebufferWidth, int framebufferHeight, int x, int yBottomUp, int w, int h) {
        ensureSized(framebufferWidth, framebufferHeight);
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glCopyTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, x, yBottomUp, x, yBottomUp, w, h);
        return texture;
    }

    /**
     * A framebuffer drawing into the copy, for a glass inside a layer: the layers open around it are
     * laid over the copy before the glass reads it. Made the first time one is asked
     * for, after {@link #capture} has sized the copy; the caller binds it.
     *
     * @return the framebuffer
     */
    int framebuffer() {
        if (framebuffer == 0) {
            framebuffer = Gl.glGenFramebuffers();
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            int fbo = framebuffer;
            Gl.requireFramebufferComplete("backdrop copy", () -> Gl.glDeleteFramebuffers(fbo));
        }
        return framebuffer;
    }

    /** @return the copy {@link #capture} last wrote, to sample */
    int texture() {
        return texture;
    }

    /**
     * A second copy the size of the first, for a glass inside a rotated or skewed layer: the first
     * is read, warped into the glass's own space, and drawn into this one, which the glass then
     * samples at its own pixels. Made the first time one is asked for, and kept with the first.
     *
     * @return the second copy's texture
     */
    int warped(int framebufferWidth, int framebufferHeight) {
        ensureSized(framebufferWidth, framebufferHeight);
        if (warpedTexture == 0) {
            warpedTexture = Gl.glGenTextures();
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, warpedTexture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.sample2D(GL33C.GL_LINEAR, GL33C.GL_CLAMP_TO_EDGE);
            warpedFramebuffer = Gl.glGenFramebuffers();
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, warpedFramebuffer);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, warpedTexture, 0);
            int fbo = warpedFramebuffer;
            Gl.requireFramebufferComplete("warped backdrop copy", () -> Gl.glDeleteFramebuffers(fbo));
        }
        return warpedTexture;
    }

    /** @return the framebuffer drawing into the second copy; {@link #warped} made it */
    int warpedFramebuffer() {
        return warpedFramebuffer;
    }

    private void ensureSized(int framebufferWidth, int framebufferHeight) {
        if (texture != 0 && width == framebufferWidth && height == framebufferHeight) {
            return;
        }
        dispose();
        width = Math.max(1, framebufferWidth);
        height = Math.max(1, framebufferHeight);
        texture = Gl.glGenTextures();
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // LINEAR: a refracted sample lands between texels by construction. CLAMP_TO_EDGE is a
        // second line of defence behind the shader's own clamp to the copied region.
        Gl.sample2D(GL33C.GL_LINEAR, GL33C.GL_CLAMP_TO_EDGE);
    }

    /** What the perf monitor should show for this canvas's backdrop copy (nothing until one). */
    RenderStats stats() {
        if (texture == 0) {
            return RenderStats.EMPTY;
        }
        int textures = warpedTexture == 0 ? 1 : 2;
        return new RenderStats(textures, (long) textures * width * height * 4);
    }

    void dispose() {
        if (warpedFramebuffer != 0) {
            Gl.glDeleteFramebuffers(warpedFramebuffer);
            warpedFramebuffer = 0;
        }
        if (warpedTexture != 0) {
            Gl.glDeleteTextures(warpedTexture);
            warpedTexture = 0;
        }
        if (framebuffer != 0) {
            Gl.glDeleteFramebuffers(framebuffer); // attached to the texture below, so it goes first
            framebuffer = 0;
        }
        if (texture != 0) {
            Gl.glDeleteTextures(texture);
            texture = 0;
        }
    }
}
