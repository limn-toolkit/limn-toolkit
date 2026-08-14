package limn.backend.lwjgl;

import limn.backend.RenderStats;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Per-GL-context home of the video path: the YCbCr→RGBA program, the empty
 * vertex array that drives its fullscreen triangle, and every
 * {@link GlVideoSurface} created in this context. Owned by one {@link GlCanvas}
 * and disposed with it; contexts are not shared between Limn windows, so a
 * texture created here is meaningless in any other.
 *
 * <p>The program is compiled on the first upload, not at construction: a window
 * that never plays video pays nothing for the video path existing.
 *
 * <p>The conversion runs in a program of its own rather than as another branch
 * of the canvas shader, because the canvas program binds exactly one texture and
 * a converter needs three: a branch there would bind three units for every glyph
 * in the toolkit. Keeping it out here costs one offscreen draw per uploaded
 * picture, and the composited picture then costs the batch a texture switch,
 * which is what any image costs it.
 *
 * <p><b>There are two of that program and not one.</b> Planes uploaded into this
 * surface's own textures are {@code sampler2D}; planes that <em>are</em> a
 * decoder's IOSurface are {@code sampler2DRect}, because that is the only target
 * {@code CGLTexImageIOSurface2D} accepts. The two differ by a sampler keyword and
 * by {@code texelFetch}'s argument count, neither of which a preprocessor branch
 * could hold in one file that ES 3.0 must still compile, so the second one is a
 * second source, compiled only where an IOSurface is bound.
 */
final class GlVideoContext {

    private final GlCanvas owner;
    private final Set<GlVideoSurface> surfaces = Collections.newSetFromMap(new IdentityHashMap<>());
    // Reused across conversions, and read through the array form of the query:
    // this runs once per uploaded picture, and the scalar glGetInteger allocates
    // a stack frame's worth of bookkeeping every time it is called.
    private final int[] previousViewport = new int[4];
    private final int[] previousFramebuffer = new int[1];

    private Conversion uploaded;
    private Conversion rectangle;
    private int vao;

    GlVideoContext(GlCanvas owner) {
        this.owner = owner;
    }

    GlVideoSurface createSurface() {
        GlVideoSurface surface = new GlVideoSurface(this);
        surfaces.add(surface);
        return surface;
    }

    void forget(GlVideoSurface surface) {
        surfaces.remove(surface);
    }

    /** Draws pending 2D geometry before a surface frees a texture it may sample. */
    void flushBeforeDeletingTexture() {
        owner.flushBeforeDeletingTexture();
    }

    /**
     * Fails unless the window that owns this context is the one rendering right
     * now. Uploading into a surface while another window's context is current
     * writes into whatever texture ids happen to match there: a picture
     * appearing in the wrong window, or nothing at all, with no GL error.
     *
     * @throws IllegalStateException when no frame is being rendered, or another
     *                               window's frame is
     */
    void checkCurrent() {
        if (GlCanvas.current() != owner) {
            throw new IllegalStateException(
                    "a video surface is only usable inside its own window's frame callback");
        }
    }

    /**
     * One compiled conversion and the uniform locations that go with it. The two
     * instances read the same uniform names from two fragment sources, which is
     * what lets {@link #convert} treat them alike and what
     * {@code VideoShaderTest} asserts about the sources.
     */
    private static final class Conversion {

        private final ShaderProgram program;
        private final int uLuma;
        private final int uCb;
        private final int uCr;
        private final int uInterleaved;
        private final int uHeight;
        private final int uChromaShift;
        private final int uSampleScale;
        private final int uMaxCode;
        private final int uYScale;
        private final int uYOffset;
        private final int uChromaNeutral;
        private final int uCrToR;
        private final int uCbToG;
        private final int uCrToG;
        private final int uCbToB;

        Conversion(String fragment) {
            program = ShaderProgram.fromResources(
                    "/limn/backend/lwjgl/shaders/video_convert.vert", fragment);
            uLuma = program.uniformLocation("u_luma");
            uCb = program.uniformLocation("u_cb");
            uCr = program.uniformLocation("u_cr");
            uInterleaved = program.uniformLocation("u_interleaved");
            uHeight = program.uniformLocation("u_height");
            uChromaShift = program.uniformLocation("u_chromaShift");
            uSampleScale = program.uniformLocation("u_sampleScale");
            uMaxCode = program.uniformLocation("u_maxCode");
            uYScale = program.uniformLocation("u_yScale");
            uYOffset = program.uniformLocation("u_yOffset");
            uChromaNeutral = program.uniformLocation("u_chromaNeutral");
            uCrToR = program.uniformLocation("u_crToR");
            uCbToG = program.uniformLocation("u_cbToG");
            uCrToG = program.uniformLocation("u_crToG");
            uCbToB = program.uniformLocation("u_cbToB");
        }
    }

    /**
     * Compiles the conversion for {@code target} on first use. A window that
     * plays no video compiles neither; a window that never meets a hardware
     * decoder never compiles the rectangle one, which is the branch that cannot
     * exist at all under ES 3.0.
     */
    private Conversion ensureProgram(boolean rectangleTextures) {
        if (rectangleTextures) {
            if (rectangle == null) {
                rectangle = new Conversion("/limn/backend/lwjgl/shaders/video_convert_rect.frag");
                ensureVao();
            }
            return rectangle;
        }
        if (uploaded == null) {
            uploaded = new Conversion("/limn/backend/lwjgl/shaders/video_convert.frag");
            ensureVao();
        }
        return uploaded;
    }

    private void ensureVao() {
        if (vao == 0) {
            // Empty vertex array: the triangle comes from gl_VertexID, but a core
            // profile still refuses to draw with no array object bound.
            vao = GL33C.glGenVertexArrays();
        }
    }

    /**
     * Converts the plane textures {@code surface} has just filled into its RGBA
     * picture. Binds the surface's framebuffer, draws one triangle over it and
     * restores the framebuffer, viewport and active texture unit; everything
     * else (blend, depth, scissor) is re-established by the 2D batch at its next
     * flush, which is why this does not save it.
     *
     * <p>The colour numbers all arrive here from {@link limn.video.VideoColor}'s
     * accessors, never retyped: one table, two consumers.
     */
    void convert(GlVideoSurface surface, limn.video.VideoColor color, limn.video.PixelFormat format,
                 boolean rectangleTextures) {
        Conversion conversion = ensureProgram(rectangleTextures);
        int target = rectangleTextures ? IoSurfaces.GL_TEXTURE_RECTANGLE : GL33C.GL_TEXTURE_2D;
        ShaderProgram active = conversion.program;
        GL33C.glGetIntegerv(GL33C.GL_FRAMEBUFFER_BINDING, previousFramebuffer);
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, surface.framebuffer());
            GL33C.glViewport(0, 0, surface.widthPx(), surface.heightPx());
            GL33C.glDisable(GL33C.GL_DEPTH_TEST);
            GL33C.glDisable(GL33C.GL_BLEND);
            GL33C.glDisable(GL33C.GL_CULL_FACE);
            // The 2D batch may have armed a damage scissor in window space; it
            // must not clip this offscreen pass (the next flush re-arms it).
            GL33C.glDisable(GL33C.GL_SCISSOR_TEST);

            active.use();
            boolean interleaved = format.planeCount() == 2;
            bindPlane(GL33C.GL_TEXTURE0, target, conversion.uLuma, 0, surface.planeTexture(0));
            bindPlane(GL33C.GL_TEXTURE1, target, conversion.uCb, 1, surface.planeTexture(1));
            // A sampler must name a complete texture even where the program
            // never reads it: for two-plane formats the Cr unit gets the
            // interleaved plane, which the branch then ignores.
            bindPlane(GL33C.GL_TEXTURE2, target, conversion.uCr, 2,
                    surface.planeTexture(interleaved ? 1 : 2));
            GL33C.glUniform1i(conversion.uInterleaved, interleaved ? 1 : 0);
            GL33C.glUniform1i(conversion.uHeight, surface.heightPx());
            GL33C.glUniform2i(conversion.uChromaShift,
                    format.chromaShiftX(), format.chromaShiftY());
            int bitDepth = format.bitDepth();
            GL33C.glUniform1f(conversion.uSampleScale, sampleScale(format));
            GL33C.glUniform1f(conversion.uMaxCode, format.maxCode());
            GL33C.glUniform1f(conversion.uYScale, (float) color.yScale(bitDepth));
            GL33C.glUniform1f(conversion.uYOffset, color.yOffset(bitDepth));
            GL33C.glUniform1f(conversion.uChromaNeutral, color.chromaNeutral(bitDepth));
            GL33C.glUniform1f(conversion.uCrToR, (float) color.crToR(bitDepth));
            GL33C.glUniform1f(conversion.uCbToG, (float) color.cbToG(bitDepth));
            GL33C.glUniform1f(conversion.uCrToG, (float) color.crToG(bitDepth));
            GL33C.glUniform1f(conversion.uCbToB, (float) color.cbToB(bitDepth));

            GL33C.glBindVertexArray(vao);
            GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
            GL33C.glBindVertexArray(0);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFramebuffer[0]);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            // Back to unit 0: the glyph atlas and the image cache bind and
            // upload without selecting a unit, so leaving unit 2 selected would
            // send their uploads somewhere else.
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        }
    }

    private static void bindPlane(int unit, int target, int uniform, int unitIndex, int texture) {
        GL33C.glActiveTexture(unit);
        GL33C.glBindTexture(target, texture);
        GL33C.glUniform1i(uniform, unitIndex);
    }

    /**
     * What a normalized sample must be multiplied by to become a code, which is
     * the space the coefficients live in.
     *
     * <p>The sampler normalizes by the <b>texel's</b> width and the coefficients
     * are per <b>code</b>, and the two agree only where the code fills the texel.
     * An 8-bit sample in an {@code R8} texture: 255, and they agree. A 10-bit
     * code right-justified in an {@code R16} texture: 65535, because the code
     * space ends at 1023 while the texel normalizes over 65535, a factor of 64.
     * <b>P010's code is left-justified</b>, so the same 16-bit texel carries it
     * 64 times higher and the scale is 65535/64 instead. Getting that one wrong
     * is a picture 64 times too bright or too dark; getting it right needed no
     * new uniform, which is why nothing structural guards it and a test has to.
     */
    private static float sampleScale(limn.video.PixelFormat format) {
        int storageBits = 8 * ((format.bitDepth() + 7) / 8);
        return ((1 << storageBits) - 1) / (float) (1 << format.codeShift());
    }

    /**
     * Waits until the device has finished reading the textures bound a moment
     * ago, the whole of the lifetime rule for a picture this surface does not
     * own.
     *
     * <p>An uploaded picture is copied into textures of this surface's, so the
     * frame may be released the instant {@code upload} returns and ADR 007 §7's
     * only sequencing problem is a texture being <em>deleted</em> under a queued
     * quad. A picture bound from an IOSurface is the opposite: the conversion
     * reads the decoder's own memory, {@code glDrawArrays} only <em>queues</em>
     * that read, and {@link limn.video.VideoFrame#release()} hands the buffer
     * straight back to the decoder's pool. The decoder then writes the next
     * picture into it, over memory a pending draw is about to sample, and the
     * corruption surfaces frames later somewhere else entirely.
     *
     * <p>There were two ways out and this is the one that was taken. <b>Holding
     * the picture</b> until the batch that used it has been drawn keeps the
     * decoder's buffer pinned for a whole frame, which on unified memory is
     * merely a slot out of a small pool, and on a discrete GPU is VRAM the
     * decoder needs back, for a pool sized on the assumption that it gets it.
     * <b>Draining</b> costs a synchronisation per picture and pins nothing. The
     * measurement in ADR 014 §7 was taken on unified memory and is route A's
     * best case, so the choice is deliberately made against the hardware it was
     * <em>not</em> measured on: drain, do not extend the possession.
     *
     * <p>A fence rather than {@code glFinish}, because what has to complete is
     * the work issued up to here and not whatever a later window queues.
     */
    void awaitDeviceRead() {
        long fence = GL33C.glFenceSync(GL33C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == 0L) {
            // No fence means no way to know, and guessing here is the corruption
            // this method exists to prevent.
            GL33C.glFinish();
            return;
        }
        try {
            // The flush bit is what stops this waiting on commands the driver has
            // buffered and not yet submitted, which would be a deadlock rather
            // than a delay. A second of patience is far beyond any real
            // conversion; reaching it means the device is wedged, and finishing
            // is then the only honest thing left to try.
            int status = GL33C.glClientWaitSync(fence, GL33C.GL_SYNC_FLUSH_COMMANDS_BIT,
                    1_000_000_000L);
            if (status == GL33C.GL_TIMEOUT_EXPIRED) {
                GL33C.glFinish();
            }
        } finally {
            GL33C.glDeleteSync(fence);
        }
    }

    /** @return resident video textures and their bytes, across every surface here */
    RenderStats stats() {
        RenderStats total = RenderStats.EMPTY;
        for (GlVideoSurface surface : surfaces) {
            total = total.plus(surface.stats());
        }
        return total;
    }

    /** Releases every surface and the shared program. The context must be current. */
    void dispose() {
        for (GlVideoSurface surface : new ArrayList<>(surfaces)) {
            surface.dispose(); // removes itself from `surfaces`
        }
        surfaces.clear();
        if (uploaded != null) {
            uploaded.program.close();
            uploaded = null;
        }
        if (rectangle != null) {
            rectangle.program.close();
            rectangle = null;
        }
        if (vao != 0) {
            GL33C.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
