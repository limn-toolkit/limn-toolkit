package limn.backend.lwjgl;

import limn.graphics.Image;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.glGenerateMipmap;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;

/**
 * Per-window (per-GL-context) cache of RGBA8 textures for {@link Image}s,
 * uploaded lazily on first draw and keyed by image identity. Contexts are not
 * shared between windows, so each window's {@link GlCanvas} owns one of these.
 * Entries unused for {@link #EVICT_AFTER_FRAMES} rendered frames are deleted at
 * the frame boundary, so runtime-generated images (thumbnails, re-rasterized
 * SVGs) don't pin GPU + heap memory for the life of the window.
 */
final class ImageTextureCache implements AutoCloseable {

    /**
     * Rendered frames of disuse after which an image's texture is deleted, and
     * the {@link Image} released with it (the map key is the only backend
     * reference, so app-dropped images become collectable). Counted in frames,
     * not time, on purpose: the loop is event-driven, so an idle window evicts
     * nothing, and re-drawing an evicted image just re-uploads it lazily.
     */
    private static final int EVICT_AFTER_FRAMES = 300;

    private static final class Entry {
        final int texture;
        int lastUsedFrame;
        boolean pixelated; // current GL filter params (uploads start SMOOTH)

        Entry(int texture, int frame) {
            this.texture = texture;
            this.lastUsedFrame = frame;
        }
    }

    private final Map<Image, Entry> textures = new IdentityHashMap<>();
    private int frame;

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
        Entry existing = textures.get(image);
        if (existing != null) {
            existing.lastUsedFrame = frame;
            return existing.texture;
        }
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
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
        textures.put(image, new Entry(texture, frame));
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
        Entry entry = textures.get(image);
        return entry != null && entry.pixelated != pixelated;
    }

    /** Rebinds {@code image}'s texture and switches its filter params. */
    void applySampling(Image image, boolean pixelated) {
        Entry entry = textures.get(image);
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
     * Advances the frame counter and deletes textures unused for
     * {@link #EVICT_AFTER_FRAMES} rendered frames. Called at the frame
     * boundary, where no pending geometry references image textures.
     *
     * @return {@code true} if anything was deleted; the batch must then reset
     *         its bound-texture tracking, since GL recycles deleted ids
     */
    boolean beginFrameAndEvict() {
        frame++;
        if (textures.isEmpty()) {
            return false;
        }
        boolean evicted = false;
        for (Iterator<Entry> it = textures.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (frame - entry.lastUsedFrame > EVICT_AFTER_FRAMES) {
                glDeleteTextures(entry.texture);
                it.remove();
                evicted = true;
            }
        }
        return evicted;
    }

    /** @return cached image textures and their bytes (RGBA8: 4 bytes/texel). */
    limn.backend.RenderStats stats() {
        long bytes = 0;
        for (Image image : textures.keySet()) {
            bytes += (long) image.width() * image.height() * 4;
        }
        return new limn.backend.RenderStats(textures.size(), bytes);
    }

    @Override
    public void close() {
        for (Entry entry : textures.values()) {
            glDeleteTextures(entry.texture);
        }
        textures.clear();
    }
}
