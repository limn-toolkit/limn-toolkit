package limn.backend.lwjgl;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL33C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL33C.GL_MAX_RENDERBUFFER_SIZE;
import static org.lwjgl.opengl.GL33C.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;

/**
 * A picture drawn off any window, with a canvas of its own in the context current now, so that a
 * window's canvas, its layers and its atlas are left as they were: what the backend's
 * {@link limn.backend.spi.OffscreenPainter} draws with. Inside a frame the frame's canvas draws what
 * it queued first, and afterwards finds its framebuffer, its viewport and itself as the current
 * canvas again; each of its draws sets the rest of the state it draws under.
 */
final class GlOffscreen {

    private GlOffscreen() {
    }

    /**
     * Draws {@code painter} into a transparent framebuffer {@code width} by {@code height} pixels,
     * on a canvas of {@code scale} pixels to its unit, reads it back and answers it with straight
     * alpha, top-down; null when the device refuses the size.
     */
    static @Nullable Image paint(FontStore fonts, int width, int height, float scale, Consumer<Canvas> painter) {
        GlCanvas once = new GlCanvas(fonts);
        try {
            return paint(once, width, height, scale, painter);
        } finally {
            once.dispose();
        }
    }

    /**
     * Inside a frame, in the frame's own context with its canvas's {@linkplain GlCanvas#offscreen()
     * offscreen canvas}; outside one, with a canvas made for the call in the context current now.
     */
    static @Nullable Image paintHere(FontStore fonts, int width, int height, float scale, Consumer<Canvas> painter) {
        GlCanvas frame = GlCanvas.current();
        return frame != null ? paint(frame.offscreen(), width, height, scale, painter)
                : paint(fonts, width, height, scale, painter);
    }

    /**
     * {@link #paint(FontStore, int, int, float, Consumer)} with {@code canvas}, a canvas of this
     * context kept for it ({@link GlCanvas#offscreen()}), which is left as it is for the next.
     */
    static @Nullable Image paint(GlCanvas canvas, int width, int height, float scale, Consumer<Canvas> painter) {
        if (width <= 0 || height <= 0 || !(scale > 0)) {
            return null;
        }
        GlCanvas outer = GlCanvas.current();
        if (outer != null) {
            outer.flushForOffscreen(); // what the frame queued is drawn into its own target first
        }
        int drawFbo = Gl.glGetInteger(org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo = Gl.glGetInteger(org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        Gl.glGetIntegerv(org.lwjgl.opengl.GL33C.GL_VIEWPORT, viewport);
        try {
            return draw(canvas, width, height, scale, painter);
        } finally {
            Gl.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER, drawFbo);
            Gl.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER, readFbo);
            Gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GlCanvas.resumeCurrent(outer);
        }
    }

    private static @Nullable Image draw(GlCanvas canvas, int width, int height, float scale,
                                        Consumer<Canvas> painter) {
        int largest = Math.min(Gl.glGetInteger(GL_MAX_TEXTURE_SIZE), Gl.glGetInteger(GL_MAX_RENDERBUFFER_SIZE));
        if (width > largest || height > largest) {
            return null;
        }
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = Gl.glGenRenderbuffers();
        ByteBuffer pixels = null;
        try {
            Gl.glActiveTexture(GL_TEXTURE0);
            Gl.glBindTexture(GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            Gl.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            Gl.glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
            Gl.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
            Gl.glBindRenderbuffer(GL_RENDERBUFFER, 0);
            Gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
            if (Gl.glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                return null;
            }
            Gl.glViewport(0, 0, width, height);
            canvas.beginFrame(width, height, scale);
            canvas.clear(Color.TRANSPARENT);
            try {
                painter.accept(canvas);
            } finally {
                canvas.endFrame();
            }
            pixels = MemoryUtil.memAlloc(width * height * 4);
            Gl.glPixelStorei(GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            // Read bottom-up and premultiplied; an image is top-down with straight alpha.
            byte[] rgba = new byte[width * height * 4];
            int row = width * 4;
            for (int y = 0; y < height; y++) {
                int from = (height - 1 - y) * row;
                int to = y * row;
                for (int i = 0; i < row; i += 4) {
                    int a = pixels.get(from + i + 3) & 0xff;
                    for (int c = 0; c < 3; c++) {
                        int v = pixels.get(from + i + c) & 0xff;
                        rgba[to + i + c] = (byte) (a == 0 ? 0 : Math.min(255, (v * 255 + a / 2) / a));
                    }
                    rgba[to + i + 3] = (byte) a;
                }
            }
            return new Image(width, height, rgba);
        } finally {
            if (pixels != null) {
                MemoryUtil.memFree(pixels);
            }
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            Gl.glDeleteRenderbuffers(depthStencil);
        }
    }
}
