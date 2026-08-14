package limn.video;

import limn.graphics.Canvas;
import limn.graphics.GpuSurface;

/**
 * A device-side picture: the destination a {@link VideoFrame}'s planar samples are uploaded to,
 * converted to colour by the backend, and composited by {@link Canvas#drawSurface} as one quad in
 * the 2D paint order, so clipping, opacity, overlays and dialogs apply to a video exactly as they
 * apply to a rectangle.
 *
 * <p>One surface per stream, created once and reused for every picture. It is the opposite of
 * {@link limn.graphics.Image}, which is immutable and which a renderer is free to upload once and
 * cache by identity: handed a fresh image per picture, such a renderer either shows the first one
 * for the life of the stream or re-uploads a full RGBA copy sixty times a second. A surface's
 * texels are replaced in place instead, and from planar samples: a 4:2:0 picture crosses at a
 * byte and a half per pixel where RGBA would cost four.
 *
 * <p>Sizes are in <em>device</em> pixels, as {@link GpuSurface} defines them, and they are the
 * picture's own, not the rectangle it is drawn into. Scaling, letterboxing and aspect ratio are
 * the caller's, expressed in the rectangle passed to {@link Canvas#drawSurface}.
 *
 * <p>UI thread throughout, inside a frame: every method here touches GPU resources that belong to
 * the window currently rendering. A decode thread produces frames and hands them over; it never
 * calls anything on this type.
 */
public interface VideoSurface extends GpuSurface {

    /**
     * Replaces this surface's picture with {@code frame}'s, resizing it to the frame's dimensions
     * if they changed, and converting the samples to displayable colour on the device.
     *
     * <p>The frame is read and not retained: it may be released as soon as this returns, and it
     * must not be released before. In the steady state this allocates nothing at all: a plane the
     * producer bound as direct memory is read by the device where it lies, and a heap-backed plane
     * is staged through a buffer this surface owns and reuses. A picture whose geometry differs
     * from the one before it rebuilds the device resources, which is a stream's first picture and
     * a resolution change, not its every frame.
     *
     * <p>Plane geometry comes from {@link VideoFrame#format()} and the frame's own dimensions, and
     * row spacing from {@link VideoFrame#stride(int)}, which is in <em>bytes</em>: a device wants
     * its row length in samples, and for a two-byte sample the two differ. Each row is read to the
     * plane's byte width and no further, so a plane whose buffer ends at its last sample (the
     * shape {@link PixelFormat#minPlaneBytes} allows) is read whole and never past its end.
     *
     * <p>One upload per surface per frame. The composite samples a surface's texels when the
     * batched geometry is <em>drawn</em>, not when the quad is queued, and the caller does not
     * control where the batch breaks. So a surface uploaded again between two draws of it in the
     * same frame leaves the earlier draw showing whichever picture was resident when the batch
     * happened to flush.
     *
     * <p><b>The picture must be display-referred</b>: {@link VideoColor#isDisplayReferred()}. A
     * surface decodes the luma/chroma matrix and manages no colour, so samples that still have a
     * transfer function in front of them are refused rather than run through a matrix that would
     * leave them washed out with nothing anywhere saying why. A source that carries such a picture
     * is expected to refuse at open, so this is the second line of that rule rather than the first.
     *
     * <h4>A picture that is a handle rather than samples</h4>
     *
     * <p>A hardware decoder publishes {@link VideoFrame.Kind} handles: memory a device owns, in a
     * layout the CPU may not be able to address. An implementation that knows how to bind one does
     * so and copies nothing at all; one that does not <b>refuses by name</b> rather than binding a
     * handle it has misread, and the caller's answer is {@link VideoFrame#toPlanar()} followed by
     * another upload. Refusing is the honest failure here: the picture is already decoded, so the
     * alternative is a black rectangle.
     *
     * <p><b>The lifetime is different for such a picture and it is the caller who pays it, once.</b>
     * A copy makes the frame free the instant this returns; a binding does not, because the device
     * has only been <em>told</em> to read the decoder's memory and {@link VideoFrame#release()}
     * gives that memory back to be refilled. An implementation that binds must therefore ensure the
     * device has finished before this returns; the alternative, holding the picture until the
     * batch has drawn, pins a decoder's buffer for a whole frame, and on a discrete GPU that is
     * VRAM the decoder sized its pool on the assumption of getting back. So the rule above is
     * unchanged: the frame may be released as soon as this returns, and must not be released
     * before.
     *
     * @param frame the picture to show; never null, and still held (not released)
     * @throws IllegalStateException         if {@code frame} has been released, if the calling
     *                                       thread is not the UI thread, if no frame is being
     *                                       rendered, or if the surface has been disposed
     * @throws IllegalArgumentException      if the picture is larger than the device can hold
     * @throws UnsupportedOperationException if the picture is not display-referred, or is a device
     *                                       handle this surface cannot bind
     * @throws NullPointerException          if {@code frame} is null
     */
    void upload(VideoFrame frame);

    /**
     * @return whether a picture has been uploaded and this surface therefore has something to
     *         draw. False on a new surface and after {@link #dispose()}: drawing one then is a
     *         no-op, not a black rectangle, so a widget that paints before its first frame arrives
     *         shows whatever is behind it rather than a hole.
     */
    boolean hasPicture();

    /**
     * No effect: a video surface is the size of its picture, which {@link #upload} sets. Draw the
     * surface into a smaller or larger rectangle to scale it; the composite filters it there,
     * where the destination size is actually known.
     *
     * @param widthPx  ignored
     * @param heightPx ignored
     */
    @Override
    void resize(int widthPx, int heightPx);

    /**
     * Releases the device resources: the plane textures and the converted picture. UI thread, with
     * the owning window's GPU context current, which in practice means from inside a frame: a
     * widget releasing one as it is detached hands it to {@link limn.scene.Scene#disposeLater}
     * instead, exactly as it would a 3D target.
     *
     * <p>Idempotent, and the surface stays usable as a Java object afterwards: {@link #hasPicture()}
     * answers false, the sizes are 0, and drawing it does nothing. A further {@link #upload} throws
     * rather than quietly resurrecting a disposed surface.
     */
    @Override
    void dispose();
}
