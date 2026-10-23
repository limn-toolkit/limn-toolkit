package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.Images;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;
import static limn.backend.lwjgl.Gl.glGenerateMipmap;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static limn.backend.lwjgl.Gl.glBindTexture;
import static limn.backend.lwjgl.Gl.glDeleteTextures;
import static limn.backend.lwjgl.Gl.glGenTextures;
import static limn.backend.lwjgl.Gl.glPixelStorei;
import static limn.backend.lwjgl.Gl.glTexImage2D;
import static limn.backend.lwjgl.Gl.glTexParameteri;

/**
 * Per-window (per-GL-context) cache of RGBA8 textures for {@link Image}s,
 * uploaded lazily on first draw and keyed by image identity. Contexts are not
 * shared between windows, so each window's {@link GlCanvas} owns one of these.
 * At each frame boundary it deletes what {@link TextureResidency} says must go:
 * a texture no frame has drawn for {@link TextureResidency#EVICT_AFTER_FRAMES}
 * frames, and, while the window holds more than {@link Images#textureBudget()},
 * the least recently drawn of those the frame that just ended did not draw. So
 * runtime-generated images (thumbnails, re-rasterized SVGs) don't pin GPU and
 * heap memory for the life of the window, and a stream of large new pictures,
 * a photo browser's say, is held to the budget rather than to 300 frames' worth
 * of them: three hundred photographs of 1920 × 1080 are 3.1 GiB of textures
 * and 2.3 GiB of heap, more than the 2 GiB a JVM takes by default on a
 * machine with 8 GB.
 */
final class ImageTextureCache implements AutoCloseable {

    private static final class Entry {
        final int texture;
        boolean pixelated; // current GL filter params (uploads start SMOOTH)

        Entry(int texture) {
            this.texture = texture;
        }
    }

    private final TextureResidency<Image, Entry> residency = new TextureResidency<>();

    /**
     * The GL texture id for {@code image}, uploading it on first use. Requires
     * this window's GL context to be current.
     *
     * <p>No asynchronous form, and none is possible: nothing here reads a file
     * or decodes anything ({@code image} already holds decoded pixels), and
     * what this does do, premultiply into a scratch buffer and upload, ends in
     * GL calls bound to the thread holding the context. The decode that
     * produces an {@link Image} in the first place is where the blocking work
     * lives, and {@link limn.graphics.Images#loadShared} is its asynchronous
     * form: it reads and decodes on the {@code Ui} worker pool and completes on
     * the UI thread.
     */
    int textureFor(Image image) {
        Entry existing = residency.drawn(image);
        if (existing != null) {
            return existing.texture;
        }
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        Gl.sample2D(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        byte[] rgba = image.pixels();
        ByteBuffer buffer = MemoryUtil.memAlloc(rgba.length);
        try {
            // Premultiply at upload ({@link Image} itself stays straight-alpha):
            // the canvas shader samples images as associated alpha, so GL_LINEAR
            // and mipmap averaging cannot bleed the RGB of transparent texels
            // into edge samples (color halos: Noto emoji PNGs, for example,
            // carry a mid-green in their alpha=0 palette entry). Mipmaps kill
            // the shimmer of large strikes minified to text sizes.
            for (int i = 0; i < rgba.length; i += 4) {
                int a = rgba[i + 3] & 0xFF;
                if (a == 0xFF) {
                    buffer.put(rgba[i]).put(rgba[i + 1]).put(rgba[i + 2]);
                } else {
                    buffer.put((byte) (((rgba[i] & 0xFF) * a + 127) / 255));
                    buffer.put((byte) (((rgba[i + 1] & 0xFF) * a + 127) / 255));
                    buffer.put((byte) (((rgba[i + 2] & 0xFF) * a + 127) / 255));
                }
                buffer.put(rgba[i + 3]);
            }
            buffer.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.width(), image.height(), 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            glGenerateMipmap(GL_TEXTURE_2D);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        residency.admit(image, new Entry(texture),
                TextureResidency.mipmappedRgba8Bytes(image.width(), image.height()));
        return texture;
    }

    /**
     * Whether {@code image}'s resident texture is configured with a different
     * filter than requested. The caller must flush pending batched geometry
     * BEFORE {@link #applySampling}, since filter params apply at draw time to
     * every queued quad sampling this texture. No-op answer for a not-yet
     * uploaded image ({@code textureFor} uploads SMOOTH; a first PIXELATED draw
     * reports a needed switch on the fresh entry).
     */
    boolean samplingDiffers(Image image, boolean pixelated) {
        Entry entry = residency.peek(image);
        return entry != null && entry.pixelated != pixelated;
    }

    /** Rebinds {@code image}'s texture and switches its filter params. */
    void applySampling(Image image, boolean pixelated) {
        Entry entry = residency.peek(image);
        if (entry == null || entry.pixelated == pixelated) {
            return;
        }
        entry.pixelated = pixelated;
        glBindTexture(GL_TEXTURE_2D, entry.texture);
        if (pixelated) {
            // Nearest, no mip selection: pixel art blocks stay exact. The mip
            // chain built at upload is simply ignored while pixelated.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
    }

    /**
     * Ends the frame and deletes the textures {@link TextureResidency} drops,
     * by age and against {@link Images#textureBudget()}, which is read here at
     * every boundary so that a budget the application moves takes effect at
     * this window's next frame. Called at the frame boundary, where no pending
     * geometry references image textures.
     *
     * @return {@code true} if anything was deleted; the batch must then reset
     *         its bound-texture tracking, since GL recycles deleted ids
     */
    boolean beginFrameAndEvict() {
        List<Entry> evicted = residency.beginFrame(Images.textureBudget());
        for (Entry entry : evicted) {
            glDeleteTextures(entry.texture);
        }
        return !evicted.isEmpty();
    }

    /**
     * @return cached image textures and their bytes: RGBA8, four bytes a texel,
     *         and the mipmap chain every upload builds, about a third again
     */
    limn.backend.RenderStats stats() {
        return new limn.backend.RenderStats(residency.count(), residency.bytes());
    }

    @Override
    public void close() {
        for (Entry entry : residency.clear()) {
            glDeleteTextures(entry.texture);
        }
    }
}
