package limn.graphics;

/**
 * A {@link GpuSurface} whose pixels can be brought back to the CPU. Rendering into a surface and
 * compositing it into the scene needs none of this; reading one is what an export, a thumbnail or a
 * reference image for a rendering test needs, and it is a capability rather than a promise of
 * {@code GpuSurface}; a surface that wraps memory it does not own (a decoder's picture, say) can
 * composite without being able to hand its pixels over.
 *
 * <p><b>Which of the two reads you want is a colour-space decision, and it is the one thing here
 * that is easy to get silently wrong.</b> An offscreen target holds scene-referred linear light; the
 * display transform (exposure, tonemap, sRGB encode) runs later, when the surface is composited.
 * So the numbers in the surface and the picture a person saw are two different images, and the
 * methods are named apart rather than separated by a flag so that neither can be reached without
 * choosing it:
 *
 * <ul>
 *   <li>{@link #readDisplayReferred()}: what was on screen. Exposure, tonemap and sRGB encode
 *       applied, alpha un-premultiplied, 8 bits per channel. For anything a person will look at.</li>
 *   <li>{@link #readSceneReferred()}: what the renderer wrote. Linear, premultiplied, float, no
 *       transform. For further processing, or for a test that asserts on the pass's own output.</li>
 * </ul>
 *
 * <p>Both are <b>synchronous GPU reads</b>: they stall the pipeline until everything queued has
 * finished, which on a frame path shows up as a frame-time cliff that profiles as "the renderer got
 * slow" rather than as this call. Read outside the frames that matter, or accept the cost
 * knowingly; the encode that usually follows can move off the UI thread ({@link Images#saveAsync}),
 * but the read itself cannot.
 *
 * <p>Both must be called on the UI thread with the owning window's GL context current, i.e. from
 * inside a frame, like every other {@code GpuSurface} operation.
 *
 * <p>Coordinates are <b>device pixels with the origin at the top-left</b>, matching {@link Image}
 * and the rest of this package, not the bottom-up convention of the underlying graphics API. The
 * returned rows are top-down for the same reason. Whatever flip that costs belongs here: an encoder
 * takes {@code Image} as it finds it, and a second flip at encode time would cancel this one for
 * one path and not another.
 */
public interface ReadableSurface extends GpuSurface {

    /**
     * Reads a rectangle of this surface as the display saw it: the surface's display transform
     * (exposure, {@code RenderTarget.exposure()} where the surface has one, then tonemap, then
     * sRGB encode) applied once, alpha divided back out to straight, quantized to 8 bits per
     * channel.
     *
     * <p>Applied <em>once</em> is the contract, and the mirror-image mistake is worth naming: a
     * surface whose contents are already display-referred must return them unchanged rather than
     * transforming them again. Reading a window's framebuffer, which has already been through the
     * composite, is not this method; it is {@code GpuRenderer.captureFramebuffer}.
     *
     * <p>Where alpha is 0 the result is a fully transparent pixel with all colour channels 0.
     * Un-premultiplying is a division by alpha, so it loses precision as alpha approaches 0 and has
     * no answer at all when alpha is exactly 0; this picks the one answer that composites back to
     * the same picture.
     *
     * @param x       left edge in device pixels, 0 at the left of the surface
     * @param y       top edge in device pixels, 0 at the <em>top</em> of the surface
     * @param widthPx rectangle width, at least 1
     * @param heightPx rectangle height, at least 1
     * @return a new image of exactly {@code widthPx * heightPx}, straight alpha, top-down
     * @throws IllegalArgumentException if the rectangle is empty or reaches outside the surface
     * @throws IllegalStateException    if the surface has been disposed
     */
    Image readDisplayReferred(int x, int y, int widthPx, int heightPx);

    /** Reads the whole surface as the display saw it. See {@link #readDisplayReferred(int, int, int, int)}. */
    default Image readDisplayReferred() {
        return readDisplayReferred(0, 0, widthPx(), heightPx());
    }

    /**
     * Reads a rectangle of this surface exactly as the renderer wrote it: linear light,
     * premultiplied, float, no exposure and no tonemap. Values above 1 survive, which is why this
     * does not return an {@link Image}.
     *
     * <p>A multisampled surface is resolved first; one sample per pixel is not what the surface
     * shows, and a read that quietly returned sample 0 would differ from the composite by exactly
     * the antialiasing.
     *
     * @param x       left edge in device pixels, 0 at the left of the surface
     * @param y       top edge in device pixels, 0 at the <em>top</em> of the surface
     * @param widthPx rectangle width, at least 1
     * @param heightPx rectangle height, at least 1
     * @return new pixels of exactly {@code widthPx * heightPx}, premultiplied, top-down
     * @throws IllegalArgumentException if the rectangle is empty or reaches outside the surface
     * @throws IllegalStateException    if the surface has been disposed
     */
    ScenePixels readSceneReferred(int x, int y, int widthPx, int heightPx);

    /** Reads the whole surface as the renderer wrote it. See {@link #readSceneReferred(int, int, int, int)}. */
    default ScenePixels readSceneReferred() {
        return readSceneReferred(0, 0, widthPx(), heightPx());
    }
}
