package limn.backend.lwjgl;

import limn.backend.RenderStats;
import limn.render3d.GpuTexture;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A {@link GpuTexture}: one RGBA8 GL texture with its {@link Sampler} state baked
 * in (no separate sampler objects yet). Stored as plain RGBA8; sRGB decode happens
 * in the fragment shader (GL sRGB formats are ruled out for GLSL-ES portability), so
 * mip generation runs in the encoded space, a known minor inaccuracy acceptable
 * for UI-scale color maps. Per-GL-context; owned by a {@link Gl3DContext}.
 */
final class GlTexture implements GpuTexture {

    private final Gl3DContext owner;
    private final int width;
    private final int height;
    private final boolean mipmapped;
    private int id;

    GlTexture(Gl3DContext owner, TextureData data, Sampler sampler) {
        this.owner = owner;
        this.width = data.width();
        this.height = data.height();
        this.mipmapped = sampler.mipmaps();

        id = Gl.glGenTextures();
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, id);
        Gl.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
        ByteBuffer pixels = OffHeap.copyOf(data.rgba8());
        try {
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, wrap(sampler.wrapS()));
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, wrap(sampler.wrapT()));
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, magFilter(sampler.magFilter()));
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER,
                minFilter(sampler.minFilter(), mipmapped));
        if (mipmapped) {
            Gl.glGenerateMipmap(GL33C.GL_TEXTURE_2D);
        }
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
    }

    int id() {
        return id;
    }

    private static int wrap(Sampler.Wrap w) {
        return switch (w) {
            case REPEAT -> GL33C.GL_REPEAT;
            case CLAMP_TO_EDGE -> GL33C.GL_CLAMP_TO_EDGE;
            case MIRRORED_REPEAT -> GL33C.GL_MIRRORED_REPEAT;
        };
    }

    private static int magFilter(Sampler.Filter f) {
        return f == Sampler.Filter.NEAREST ? GL33C.GL_NEAREST : GL33C.GL_LINEAR;
    }

    private static int minFilter(Sampler.Filter f, boolean mips) {
        if (!mips) {
            return f == Sampler.Filter.NEAREST ? GL33C.GL_NEAREST : GL33C.GL_LINEAR;
        }
        return f == Sampler.Filter.NEAREST
                ? GL33C.GL_NEAREST_MIPMAP_NEAREST
                : GL33C.GL_LINEAR_MIPMAP_LINEAR;
    }

    @Override
    public int widthPx() {
        return width;
    }

    @Override
    public int heightPx() {
        return height;
    }

    RenderStats stats() {
        long base = (long) width * height * 4;
        long bytes = mipmapped ? base * 4 / 3 : base; // mip chain adds ≈ 1/3
        return new RenderStats(1, bytes);
    }

    @Override
    public void dispose() {
        if (id != 0) {
            Gl.glDeleteTextures(id);
            id = 0;
        }
        owner.forget(this);
    }
}
