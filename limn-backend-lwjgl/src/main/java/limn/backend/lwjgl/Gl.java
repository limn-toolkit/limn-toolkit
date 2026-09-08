package limn.backend.lwjgl;

import org.lwjgl.opengl.GL33C;

/**
 * The two GL idioms every texture and framebuffer in this backend repeats: the four sampling
 * parameters of a 2D texture, and the completeness check after attaching to a framebuffer.
 *
 * <p>Eight textures set MIN and MAG filter and WRAP_S and WRAP_T in the same four lines, and
 * three framebuffers read {@code glCheckFramebufferStatus}, tore down and threw the same
 * "FBO incomplete: 0x" line. What differs is a filter, a wrap mode, a name and what to tear
 * down, and those are the parameters here.
 */
final class Gl {

    private Gl() {
    }

    /**
     * Sets how the 2D texture bound to the current unit is sampled: one filter for both
     * minification and magnification, one wrap mode for both axes.
     *
     * @param filter {@code GL_NEAREST} or {@code GL_LINEAR}
     * @param wrap   {@code GL_CLAMP_TO_EDGE} or {@code GL_REPEAT}
     */
    static void sample2D(int filter, int wrap) {
        sample2D(filter, filter, wrap);
    }

    /**
     * Sets how the 2D texture bound to the current unit is sampled.
     *
     * @param minFilter the minification filter, a mipmapped one included
     * @param magFilter the magnification filter
     * @param wrap      the wrap mode, for both axes
     */
    static void sample2D(int minFilter, int magFilter, int wrap) {
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, minFilter);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, magFilter);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, wrap);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, wrap);
    }

    /**
     * Refuses an incomplete framebuffer: the one bound to {@code GL_FRAMEBUFFER} is checked, and
     * if it is not complete {@code cleanup} runs and the status is thrown.
     *
     * @param what    which framebuffer, for the message: "shadow", "3D resolve"
     * @param cleanup what to tear down before throwing, so nothing half-built is left behind
     * @throws IllegalStateException if the framebuffer is incomplete
     */
    static void requireFramebufferComplete(String what, Runnable cleanup) {
        requireFramebufferComplete(GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), what, cleanup);
    }

    /**
     * {@link #requireFramebufferComplete(String, Runnable)} for a status the caller already read,
     * for the caller that has to restore a binding between reading it and reacting to it.
     *
     * @param status  what {@code glCheckFramebufferStatus} answered
     * @param what    which framebuffer, for the message
     * @param cleanup what to tear down before throwing
     * @throws IllegalStateException if the status is not complete
     */
    static void requireFramebufferComplete(int status, String what, Runnable cleanup) {
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            cleanup.run();
            throw new IllegalStateException(what + " FBO incomplete: 0x" + Integer.toHexString(status));
        }
    }
}
