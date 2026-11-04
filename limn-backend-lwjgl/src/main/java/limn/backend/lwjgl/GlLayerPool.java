package limn.backend.lwjgl;

import limn.backend.RenderStats;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The offscreen pictures one window's canvas draws its layers into: RGBA8
 * textures, premultiplied as the window's own framebuffer is, each with the framebuffer that draws
 * into it, and a stencil buffer once a fill inside the layer has needed one ({@link #stencil}).
 *
 * <p><b>Taken and given back.</b> A layer takes a picture when it opens and gives it back when it
 * closes, so siblings share one and what is held at once is one per level of nesting on the path
 * being drawn. A picture may be larger than the layer that took it, which draws into the part at
 * its bottom-left corner: sizes are rounded up to {@link #GRAIN} pixels, so a fade whose layer
 * grows or shrinks by a pixel from one frame to the next takes the same picture again.
 *
 * <p><b>Kept only while frames use it.</b> At the end of a frame every picture that frame did not
 * take goes back to the driver, so a fade of twelve frames makes its picture once and the frame
 * after it frees it, and a frame that opens no layer holds nothing after it. A framebuffer that
 * shrinks lets go of the pictures it no longer fits, and a closed window frees them all, as it frees
 * everything else it held.
 * There is no budget and no knob: the most a window holds is bounded by its own size and the depth
 * of its nesting, and nothing is held once the frames stop using it. A picture that a composite
 * minified past two to one also holds its mipmap chain, a third more, made when that composite
 * first needed it and freed with the picture.
 *
 * <p>Single-threaded: the canvas that owns it, with its context current.
 */
final class GlLayerPool {

    /** The step picture sizes are rounded up to, in device pixels. */
    static final int GRAIN = 64;

    /** One picture: a texture, the framebuffer drawing into it, and its full size. */
    static final class Picture {
        final int texture;
        final int framebuffer;
        final int width;
        final int height;
        /**
         * How many mipmap levels past its own the texture holds: none until a composite first needs
         * them, which the canvas makes then and counts here. The driver keeps them until the picture
         * is deleted, whichever layer takes it after.
         */
        int levels;
        /**
         * The stencil buffer a fill through the stencil uses inside this picture's layer: none
         * until one is first drawn there, then kept with the picture, at zero between fills.
         */
        private int stencil;
        /** Whether the driver refused a stencil attachment, so none is asked again. */
        private boolean stencilRefused;
        private long usedInFrame;
        private boolean taken;

        private Picture(int texture, int framebuffer, int width, int height) {
            this.texture = texture;
            this.framebuffer = framebuffer;
            this.width = width;
            this.height = height;
        }

        long bytes() {
            long bytes = 0;
            for (int level = 0; level <= levels; level++) {
                bytes += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
            }
            if (stencil != 0) {
                bytes += (long) width * height * 4; // depth and stencil packed in four bytes
            }
            return bytes;
        }
    }

    private final List<Picture> pictures = new ArrayList<>();
    private long frame;

    /**
     * A frame starts: what a framebuffer of this size can no longer hold is let go, since a layer is
     * never larger than the window it is drawn in.
     *
     * @return whether a texture was deleted, so the caller's batch must forget the one it tracks
     */
    boolean beginFrame(int framebufferWidth, int framebufferHeight) {
        frame++;
        boolean deleted = false;
        for (int i = pictures.size() - 1; i >= 0; i--) {
            Picture p = pictures.get(i);
            if (!p.taken && (p.width > roundUp(framebufferWidth) || p.height > roundUp(framebufferHeight))) {
                delete(p);
                pictures.remove(i);
                deleted = true;
            }
        }
        return deleted;
    }

    /**
     * Takes the smallest free picture at least {@code width} by {@code height}, or makes one.
     *
     * @return the picture, or {@code null} where the driver could not make a complete framebuffer,
     *         which the caller answers by drawing the layer per draw
     */
    Picture take(int width, int height, int framebufferWidth, int framebufferHeight) {
        Picture best = null;
        for (Picture p : pictures) {
            if (!p.taken && p.width >= width && p.height >= height
                    && (best == null || p.bytes() < best.bytes())) {
                best = p;
            }
        }
        if (best == null) {
            best = make(Math.min(Math.max(width, roundUp(width)), Math.max(width, framebufferWidth)),
                    Math.min(Math.max(height, roundUp(height)), Math.max(height, framebufferHeight)));
            if (best == null) {
                return null;
            }
            pictures.add(best);
        }
        best.taken = true;
        best.usedInFrame = frame;
        return best;
    }

    /**
     * Gives a picture a stencil buffer, which a fill through the stencil needs inside its layer:
     * made the first time one is drawn there, cleared once, and kept with the picture, every fill
     * leaving it at zero. Packed with a depth buffer, the one stencil format every driver of both
     * profiles attaches. Binds the picture's framebuffer, which is the one bound when a layer asks.
     *
     * @return whether the picture has one; false where the driver would not attach it, which the
     *         caller answers by filling each contour on its own
     */
    boolean stencil(Picture picture) {
        if (picture.stencil != 0) {
            return true;
        }
        if (picture.stencilRefused) {
            return false;
        }
        int buffer = Gl.glGenRenderbuffers();
        Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, buffer);
        Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, picture.width, picture.height);
        Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, picture.framebuffer);
        Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                GL33C.GL_RENDERBUFFER, buffer);
        if (Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, 0);
            Gl.glDeleteRenderbuffers(buffer);
            picture.stencilRefused = true;
            return false;
        }
        picture.stencil = buffer;
        // A new buffer holds anything; the whole of it, whatever damage scissor is in force.
        Gl.glDisable(GL33C.GL_SCISSOR_TEST);
        Gl.glStencilMask(0xff);
        Gl.glClear(GL33C.GL_STENCIL_BUFFER_BIT);
        return true;
    }

    /** Gives a picture back for the next layer of this frame, or of the next. */
    void give(Picture picture) {
        picture.taken = false;
    }

    /**
     * The frame ended: every picture it did not take goes back to the driver. The caller has
     * flushed, so no queued composite samples one of them.
     *
     * @return whether a texture was deleted
     */
    boolean endFrame() {
        boolean deleted = false;
        for (int i = pictures.size() - 1; i >= 0; i--) {
            Picture p = pictures.get(i);
            if (p.taken) {
                p.taken = false; // a layer the frame left open: the canvas has unwound it
            }
            if (p.usedInFrame != frame) {
                delete(p);
                pictures.remove(i);
                deleted = true;
            }
        }
        return deleted;
    }

    /** What the performance monitor shows for the pictures held now. */
    RenderStats stats() {
        if (pictures.isEmpty()) {
            return RenderStats.EMPTY;
        }
        long bytes = 0;
        for (Picture p : pictures) {
            bytes += p.bytes();
        }
        return new RenderStats(pictures.size(), bytes);
    }

    /** @return the names of the textures held now; for tests that check a close freed them */
    int[] textures() {
        int[] names = new int[pictures.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = pictures.get(i).texture;
        }
        return names;
    }

    /** Frees everything; the window is going away, with its context current. */
    void dispose() {
        for (Picture p : pictures) {
            delete(p);
        }
        pictures.clear();
    }

    private static int roundUp(int size) {
        return (Math.max(1, size) + GRAIN - 1) / GRAIN * GRAIN;
    }

    private static Picture make(int width, int height) {
        int texture = Gl.glGenTextures();
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // NEAREST: the composite lays the picture back on the pixels it was drawn on, one texel to
        // one pixel, and nothing should be filtered on the way.
        Gl.sample2D(GL33C.GL_NEAREST, GL33C.GL_CLAMP_TO_EDGE);
        int framebuffer = Gl.glGenFramebuffers();
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
        Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        if (Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER) != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            Gl.glDeleteFramebuffers(framebuffer);
            Gl.glDeleteTextures(texture);
            return null;
        }
        return new Picture(texture, framebuffer, width, height);
    }

    private static void delete(Picture p) {
        Gl.glDeleteFramebuffers(p.framebuffer);
        Gl.glDeleteTextures(p.texture);
        if (p.stencil != 0) {
            Gl.glDeleteRenderbuffers(p.stencil);
        }
    }
}
