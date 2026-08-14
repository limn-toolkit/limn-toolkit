package limn.backend.lwjgl;

import limn.backend.RenderStats;
import limn.concurrent.Ui;
import limn.video.PixelFormat;
import limn.video.VideoFrame;
import limn.video.VideoSurface;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * A {@link VideoSurface} backed by one single-channel texture per plane (two
 * channels for an interleaved chroma plane) and a normalized RGBA texture with
 * its own FBO holding the converted picture. {@link GlCanvas#drawSurface}
 * composites that texture through the ordinary image path: its texels are
 * display-referred and gamma-encoded, unlike a 3D target's linear
 * scene-referred light, and running the display transform over them would
 * darken and desaturate every picture in a way that reads as a colour-matrix
 * bug.
 *
 * <p>Planes are uploaded, not converted on the way in: a 4:2:0 picture is 1.5
 * bytes per pixel across the bus instead of the 4 an RGBA conversion would
 * cost, and the matrix runs once per output pixel on the device that is about
 * to draw it.
 *
 * <p><b>The depth follows the picture on both sides.</b> An 8-bit plane is an
 * {@code R8} texture and the converted picture is {@code RGBA8}; a 10-bit plane
 * is {@code R16} and the converted picture is {@code RGB10_A2}, which is still
 * four bytes a pixel, still normalized, and still an ordinary texture to the
 * composite, so ten bits survive the conversion instead of being quantized
 * back to eight by the target they land in, and the composite needs no branch
 * at all. What the depth does <em>not</em> change is which composite path the
 * picture takes, because that is decided by the transfer function and not by
 * the number of bits.
 *
 * <p><b>A picture is not always uploaded.</b> A hardware decoder hands over an
 * IOSurface rather than samples, and the plane textures are then pointed at that
 * memory instead of filled from it: the same conversion over the same geometry,
 * with nothing crossing the bus. What that costs is a second set of textures
 * ({@code GL_TEXTURE_RECTANGLE}, which is the only target the binding accepts), a
 * second fragment program to sample them, and a synchronisation before the frame
 * may be released, because the picture belongs to the decoder and not to this
 * surface. See {@link GlVideoContext#awaitDeviceRead()}, which is where that last
 * one is argued.
 *
 * <p>Per-GL-context, like every texture here; owned by a {@link GlVideoContext}.
 */
final class GlVideoSurface implements VideoSurface {

    private static final int MAX_PLANES = 3;

    private final GlVideoContext owner;
    private final int[] planeTextures = new int[MAX_PLANES];

    private PixelFormat format;
    private int width;
    private int height;
    private int colorTexture;
    private int framebuffer;
    private boolean picture;
    private boolean disposed;

    /**
     * Whether the plane textures are rectangle textures pointed at a decoder's
     * IOSurface rather than 2D textures this surface filled. It is part of the
     * geometry for reallocation purposes: the two kinds of texture are not
     * interchangeable, so a stream that changed shape mid-flight (hardware
     * decode failing over to software) must rebuild them rather than upload
     * bytes into a rectangle texture bound to memory it no longer owns.
     */
    private boolean deviceBacked;

    /**
     * Reusable staging memory for planes the device cannot read where they lie,
     * since a heap-backed plane has no address at all. Allocated on the first
     * such plane, grown when a bigger picture arrives, and never per frame: at
     * 30 pictures a second a per-frame allocation of a plane is tens of
     * megabytes a second of garbage.
     */
    private ByteBuffer staging;

    GlVideoSurface(GlVideoContext owner) {
        this.owner = owner;
    }

    @Override
    public void upload(VideoFrame frame) {
        Objects.requireNonNull(frame, "frame");
        Ui.checkUiThread();
        owner.checkCurrent();
        if (disposed) {
            throw new IllegalStateException("this video surface has been disposed");
        }
        // Liveness gate before anything on the device changes: a released
        // frame's memory belongs to its producer again, and asking for it is
        // what says so. Which question that is depends on the shape: a
        // handle-backed picture has no plane 0 to ask about.
        boolean device = frame.kind() != VideoFrame.Kind.PLANAR;
        if (device) {
            frame.handle();
            if (!IoSurfaces.isAvailable() || frame.kind() != VideoFrame.Kind.IO_SURFACE) {
                // Refused by name rather than bound as something else. The picture is already
                // decoded and the alternative to saying so is a black rectangle, so the message
                // names the kind and the way out: VideoFrame.toPlanar() reads it back.
                throw new UnsupportedOperationException(
                        "this surface cannot bind a " + frame.kind() + " picture"
                                + (IoSurfaces.isAvailable() ? ""
                                        : " (" + IoSurfaces.unavailableReason() + ")")
                                + "; a consumer without the interop calls VideoFrame.toPlanar()"
                                + " and uploads the samples instead");
            }
        } else {
            frame.plane(0);
        }
        if (!frame.color().isDisplayReferred()) {
            // The conversion program decodes a matrix and manages no colour, so
            // a picture that still has a transfer function in front of it would
            // come out of it washed out with nothing anywhere saying why.
            throw new UnsupportedOperationException(
                    "this surface shows display-referred pictures, and " + frame.color()
                            + " carries a " + frame.color().transfer()
                            + " transfer function that has to be inverted first");
        }

        PixelFormat pictureFormat = frame.format();
        if (pictureFormat != format || frame.width() != width || frame.height() != height
                || device != deviceBacked) {
            allocate(pictureFormat, frame.width(), frame.height(), device);
        }
        if (device) {
            bindPlanes(frame.handle(), pictureFormat);
        } else {
            for (int plane = 0; plane < pictureFormat.planeCount(); plane++) {
                uploadPlane(frame, pictureFormat, plane);
            }
        }
        owner.convert(this, frame.color(), pictureFormat, device);
        if (device) {
            // The caller is entitled to release the frame the moment this returns, and for a
            // borrowed IOSurface that is the moment the decoder may start overwriting it. Nothing
            // above this line has waited for the device to actually read it.
            owner.awaitDeviceRead();
        }
        picture = true;
    }

    @Override
    public boolean hasPicture() {
        return picture;
    }

    @Override
    public int widthPx() {
        return width;
    }

    @Override
    public int heightPx() {
        return height;
    }

    @Override
    public void resize(int widthPx, int heightPx) {
        // Deliberately nothing: the picture's own size governs (see VideoSurface).
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        deleteGl();
        if (staging != null) {
            // Outlives a geometry change (a bigger picture grows it rather than
            // re-allocating it), so only a disposal frees it.
            MemoryUtil.memFree(staging);
            staging = null;
        }
        disposed = true;
        owner.forget(this);
    }

    /** The RGBA8 texture holding the converted picture, or 0 before the first upload. */
    int colorTexture() {
        return colorTexture;
    }

    /** The FBO the conversion draws into. */
    int framebuffer() {
        return framebuffer;
    }

    /**
     * The layout of the picture currently resident, or null before the first upload. That layout
     * is what says whether {@link #colorTexture()} is RGBA8 or RGB10_A2, and therefore how it may
     * be read back.
     */
    PixelFormat pictureFormat() {
        return format;
    }

    /** The texture of one plane of the current picture; 0 before the first upload. */
    int planeTexture(int plane) {
        return planeTextures[plane];
    }

    /**
     * @return whether {@code context} is the one that created this surface.
     *         Texture ids are per context and every context numbers from 1, so a
     *         surface drawn by the wrong window samples an unrelated texture of
     *         the same number rather than failing.
     */
    boolean ownedBy(GlVideoContext context) {
        return owner == context;
    }

    /**
     * @return this surface's textures and their bytes (planes + the converted
     *         picture). A device-backed plane texture is counted as a texture
     *         and as <b>no bytes</b>: its samples are the decoder's allocation,
     *         which this surface neither made nor holds, and counting them here
     *         would report the same picture's memory twice.
     */
    RenderStats stats() {
        if (colorTexture == 0) {
            return RenderStats.EMPTY;
        }
        long bytes = (long) width * height * 4;
        int textures = 1;
        for (int plane = 0; plane < format.planeCount(); plane++) {
            if (!deviceBacked) {
                bytes += (long) format.planeByteWidth(plane, width)
                        * format.planeHeight(plane, height);
            }
            textures++;
        }
        return new RenderStats(textures, bytes);
    }

    /**
     * Rebuilds every texture for a new geometry. Called when the format or
     * either dimension changes, which for one stream is once; a resolution
     * switch mid-stream is the only other time.
     */
    private void allocate(PixelFormat newFormat, int newWidth, int newHeight, boolean device) {
        // Refuse before destroying: a picture this device cannot hold must not
        // also cost the caller the picture already on screen. A rectangle
        // texture has a limit of its own and it is not GL_MAX_TEXTURE_SIZE;
        // asking the wrong one either refuses a picture that would have worked
        // or admits one that fails to bind with no GL error at all.
        int maxTexture = GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_SIZE);
        int maxPlane = device
                ? GL33C.glGetInteger(IoSurfaces.GL_MAX_RECTANGLE_TEXTURE_SIZE) : maxTexture;
        if (newWidth > maxTexture || newHeight > maxTexture) {
            throw new IllegalArgumentException(
                    "picture " + newWidth + "x" + newHeight + " exceeds this GPU's "
                            + maxTexture + "px texture limit");
        }
        if (newWidth > maxPlane || newHeight > maxPlane) {
            throw new IllegalArgumentException(
                    "picture " + newWidth + "x" + newHeight + " exceeds this GPU's "
                            + maxPlane + "px rectangle texture limit");
        }
        deleteGl();
        format = newFormat;
        width = newWidth;
        height = newHeight;
        deviceBacked = device;

        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        for (int plane = 0; plane < newFormat.planeCount(); plane++) {
            planeTextures[plane] = device
                    ? createRectangleTexture() : createPlaneTexture(newFormat, plane);
        }

        colorTexture = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, colorTexture);
        // RGB10_A2 for a 10-bit picture: the same four bytes a pixel as RGBA8,
        // colour-renderable in GL 3.3 core and in ES 3.0 core, and normalized,
        // so the composite samples it exactly as it samples any other image and
        // gains no branch. RGBA8 here would quantize the conversion's own output
        // back to eight bits and make the whole depth exercise decorative.
        boolean deep = newFormat.bitDepth() > 8;
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0,
                deep ? GL33C.GL_RGB10_A2 : GL33C.GL_RGBA8, width, height, 0,
                GL33C.GL_RGBA,
                deep ? GL33C.GL_UNSIGNED_INT_2_10_10_10_REV : GL33C.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        // LINEAR without mipmaps: a picture is normally drawn near its own size
        // and is replaced every frame, so a mip chain would be rebuilt sixty
        // times a second to be sampled at level 0 anyway. The accepted cost is
        // that heavily minified video aliases where a still image would not.
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);

        framebuffer = GL33C.glGenFramebuffers();
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, colorTexture, 0);
        int status = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
        // Restore before reacting: the caller is mid-frame, and an exception
        // thrown with this surface's FBO still bound would send the rest of the
        // window's 2D geometry into it.
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            deleteGl();
            throw new IllegalStateException(
                    "video FBO incomplete: 0x" + Integer.toHexString(status));
        }
    }

    /**
     * One plane's texture: single-channel, except an interleaved chroma plane,
     * whose sample is the byte pair (Cb, Cr) and which is therefore a
     * two-channel texture rather than a wider single-channel one.
     *
     * <p>Filtering is NEAREST on every plane. For chroma that is the contract:
     * the reference conversion replicates a chroma sample across the pixels it
     * covers, and any interpolation stops matching it. For luma it is the
     * honest statement that the conversion samples texel centres one-to-one and
     * never asks for a value between two of them.
     *
     * <p>A 10-bit plane is {@code R16}, which GL 3.3 core requires and which
     * <b>GL ES 3.0 core does not have</b>; there it needs
     * {@code EXT_texture_norm16}, and a port that finds the extension absent has
     * to move the planes to {@code R16UI} and the sampler to {@code usampler2D}
     * rather than falling back to eight bits. There is deliberately no fallback
     * here: a second sampler path nothing on this desktop exercises would rot,
     * and dropping to eight bits silently is the failure this whole depth
     * exercise exists to remove.
     */
    private int createPlaneTexture(PixelFormat pictureFormat, int plane) {
        int texture = GL33C.glGenTextures();
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        boolean twoComponent = pictureFormat.componentsPerSample(plane) == 2;
        boolean deep = pictureFormat.bitDepth() > 8;
        int internalFormat = twoComponent
                ? (deep ? GL33C.GL_RG16 : GL33C.GL_RG8)
                : (deep ? GL33C.GL_R16 : GL33C.GL_R8);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, internalFormat,
                pictureFormat.planeWidth(plane, width), pictureFormat.planeHeight(plane, height),
                0, twoComponent ? GL33C.GL_RG : GL33C.GL_RED,
                deep ? GL33C.GL_UNSIGNED_SHORT : GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
        return texture;
    }

    /**
     * A texture name with no storage of its own, for a plane that will be
     * pointed at a decoder's memory. {@code CGLTexImageIOSurface2D} defines the
     * storage on every bind, so calling {@code glTexImage2D} here would allocate
     * a picture's worth of texture that the first bind throws away.
     *
     * <p>Filtering is NEAREST for the reason the uploaded path gives: the
     * conversion fetches texel centres one to one and replicates chroma exactly
     * as the reference converter does. A rectangle texture's default minification
     * filter is LINEAR and it has no mip levels, so leaving it alone is not
     * "unfiltered", it is a filter nobody chose.
     */
    private static int createRectangleTexture() {
        int texture = GL33C.glGenTextures();
        GL33C.glBindTexture(IoSurfaces.GL_TEXTURE_RECTANGLE, texture);
        GL33C.glTexParameteri(IoSurfaces.GL_TEXTURE_RECTANGLE,
                GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(IoSurfaces.GL_TEXTURE_RECTANGLE,
                GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        // CLAMP_TO_EDGE is the only wrap a rectangle texture takes besides a
        // border colour; REPEAT is not legal on one at all.
        GL33C.glTexParameteri(IoSurfaces.GL_TEXTURE_RECTANGLE,
                GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
        GL33C.glTexParameteri(IoSurfaces.GL_TEXTURE_RECTANGLE,
                GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
        return texture;
    }

    /**
     * Points every plane texture at {@code ioSurface}'s corresponding plane:
     * the zero-copy path, where the whole of "uploading" a picture is two calls
     * that move no samples at all.
     *
     * <p>The internal formats are the uploaded path's, and they have to be: an
     * IOSurface from VideoToolbox is NV12 or P010, so plane 0 is one component
     * and plane 1 is two, at one byte each or two. What differs is only that
     * these describe memory that already exists rather than memory to allocate.
     *
     * <p>A failure here is loud. The picture is decoded and the surface has just
     * thrown away whatever it held, so a silent failure is a black rectangle
     * with a CGL error nobody reads.
     */
    private void bindPlanes(long ioSurface, PixelFormat pictureFormat) {
        boolean deep = pictureFormat.bitDepth() > 8;
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        for (int plane = 0; plane < pictureFormat.planeCount(); plane++) {
            boolean twoComponent = pictureFormat.componentsPerSample(plane) == 2;
            int internalFormat = twoComponent
                    ? (deep ? GL33C.GL_RG16 : GL33C.GL_RG8)
                    : (deep ? GL33C.GL_R16 : GL33C.GL_R8);
            GL33C.glBindTexture(IoSurfaces.GL_TEXTURE_RECTANGLE, planeTextures[plane]);
            int error = IoSurfaces.bindPlane(internalFormat,
                    pictureFormat.planeWidth(plane, width),
                    pictureFormat.planeHeight(plane, height),
                    twoComponent ? GL33C.GL_RG : GL33C.GL_RED,
                    deep ? GL33C.GL_UNSIGNED_SHORT : GL33C.GL_UNSIGNED_BYTE,
                    ioSurface, plane);
            if (error != 0) {
                throw new IllegalStateException(
                        "cannot bind plane " + plane + " of a " + pictureFormat
                                + " IOSurface as a texture: " + IoSurfaces.errorText(error));
            }
        }
    }

    /**
     * Uploads one plane of {@code frame} into its texture.
     *
     * <p>A row length bounds the read to the last sample of the last row, not
     * to the end of that row's padding, so a plane that stops at its final
     * sample, which is all the layout requires it to have, uploads whole and
     * without a copy. The corollary is the trap: a size check written as
     * {@code stride × rows} rejects a buffer this reads perfectly well.
     */
    private void uploadPlane(VideoFrame frame, PixelFormat pictureFormat, int plane) {
        int columns = pictureFormat.planeWidth(plane, width);
        int rows = pictureFormat.planeHeight(plane, height);
        int bytesPerSample = pictureFormat.bytesPerSample(plane);
        int byteWidth = pictureFormat.planeByteWidth(plane, width);
        int stride = frame.stride(plane);
        ByteBuffer source = frame.plane(plane);
        // COMPONENTS decide the channel count and the BYTES decide the type, and
        // the two stopped agreeing the moment a sample could be two bytes wide:
        // a 10-bit luma plane is two bytes per sample and still one channel,
        // where NV12's chroma is two bytes per sample and two channels.
        int glFormat = pictureFormat.componentsPerSample(plane) == 2 ? GL33C.GL_RG : GL33C.GL_RED;
        int glType = pictureFormat.bitDepth() > 8 ? GL33C.GL_UNSIGNED_SHORT : GL33C.GL_UNSIGNED_BYTE;

        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, planeTextures[plane]);
        // Rows start at arbitrary byte counts. The default four-byte unpack
        // alignment reads every plane whose byte width is not a multiple of
        // four (which is most odd widths) one skewed row at a time, and the
        // result looks exactly like a stride bug.
        GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
        try {
            // GL_UNPACK_ROW_LENGTH counts SAMPLES; a stride counts bytes. They
            // coincide only for a one-byte sample (NV12's interleaved chroma
            // and every 10-bit plane are two), and a stride that is not a whole
            // number of samples cannot be expressed as a row length at all.
            // Such a plane is staged tight instead.
            if (source.isDirect() && stride % bytesPerSample == 0) {
                GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, stride / bytesPerSample);
                // Addressed from byte 0 rather than from the buffer's position:
                // the planes of a pooled frame are shared state, and another
                // consumer may have left a position behind.
                GL33C.nglTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, columns, rows,
                        glFormat, glType, MemoryUtil.memAddress(source, 0));
            } else {
                GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
                GL33C.glTexSubImage2D(GL33C.GL_TEXTURE_2D, 0, 0, 0, columns, rows,
                        glFormat, glType, stage(source, stride, byteWidth, rows));
            }
        } finally {
            // Unpack state is global, and every other uploader in this backend
            // assumes the row length is 0.
            GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
        }
    }

    /**
     * Copies a plane the device cannot read in place into tightly packed
     * staging memory, row by row so no padding is copied and nothing past the
     * last sample is touched.
     *
     * <p>The source is read by absolute index, so its position and limit come
     * back exactly as they were: they are shared state on a pooled frame, and
     * another consumer may be reading the same plane relatively.
     */
    private ByteBuffer stage(ByteBuffer source, int stride, int byteWidth, int rows) {
        int needed = byteWidth * rows;
        if (staging == null) {
            staging = MemoryUtil.memAlloc(needed);
        } else if (staging.capacity() < needed) {
            staging = MemoryUtil.memRealloc(staging, needed);
        }
        // Open the whole buffer up first. An absolute put is bounded by the
        // LIMIT, so a buffer left narrowed to the last plane staged (chroma, a
        // quarter of the size) rejects the next picture's luma rather than
        // growing for it, on the second staged picture and never the first.
        staging.clear();
        for (int row = 0; row < rows; row++) {
            staging.put(row * byteWidth, source, row * stride, byteWidth);
        }
        staging.limit(needed);
        return staging;
    }

    private void deleteGl() {
        if (colorTexture != 0) {
            // This picture may already be queued in the frame being drawn: a
            // resolution change and a disposal both land mid-frame. Draw what is
            // pending while the texture is still alive.
            owner.flushBeforeDeletingTexture();
        }
        for (int plane = 0; plane < MAX_PLANES; plane++) {
            if (planeTextures[plane] != 0) {
                GL33C.glDeleteTextures(planeTextures[plane]);
                planeTextures[plane] = 0;
            }
        }
        if (colorTexture != 0) {
            GL33C.glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (framebuffer != 0) {
            GL33C.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        format = null;
        width = 0;
        height = 0;
        deviceBacked = false;
        picture = false;
    }
}
