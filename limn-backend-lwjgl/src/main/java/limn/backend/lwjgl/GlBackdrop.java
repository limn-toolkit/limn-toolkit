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
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glCopyTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, x, yBottomUp, x, yBottomUp, w, h);
        return texture;
    }

    private void ensureSized(int framebufferWidth, int framebufferHeight) {
        if (texture != 0 && width == framebufferWidth && height == framebufferHeight) {
            return;
        }
        dispose();
        width = Math.max(1, framebufferWidth);
        height = Math.max(1, framebufferHeight);
        texture = GL33C.glGenTextures();
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // LINEAR: a refracted sample lands between texels by construction. CLAMP_TO_EDGE is a
        // second line of defence behind the shader's own clamp to the copied region.
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
    }

    /** What the perf monitor should show for this canvas's backdrop copy (nothing until one). */
    RenderStats stats() {
        return texture == 0 ? RenderStats.EMPTY : new RenderStats(1, (long) width * height * 4);
    }

    void dispose() {
        if (texture != 0) {
            GL33C.glDeleteTextures(texture);
            texture = 0;
        }
    }
}
