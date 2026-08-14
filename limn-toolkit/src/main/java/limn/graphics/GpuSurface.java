package limn.graphics;

/**
 * An offscreen GPU render target owned by the backend: a texture (with its own
 * depth buffer) that something can draw <em>into</em> off-screen and that the 2D
 * {@link Canvas} can then composite as one layer via
 * {@link Canvas#drawSurface}. This is how 3D content (or any non-2D rendering)
 * participates in the scene: it renders into a surface, and the surface is drawn
 * as an ordinary quad in the 2D paint order, so overlays, dialogs, tooltips and
 * clipping all apply to it automatically.
 *
 * <p>Neutral by design (no GL here): the concrete surface lives in the backend.
 * The 3D subsystem's {@code limn.render3d.RenderTarget} extends this. Sizes are in
 * <em>device</em> pixels.
 */
public interface GpuSurface {

    /** Current width in physical pixels. */
    int widthPx();

    /** Current height in physical pixels. */
    int heightPx();

    /**
     * Resizes the target if the size changed (no-op otherwise). UI thread, in a frame.
     *
     * <p>A surface whose size is dictated by its own content (a decoded picture, say) ignores
     * this entirely and says so on its own declaration. Scale such a surface by drawing it into
     * the rectangle you want instead.
     */
    void resize(int widthPx, int heightPx);

    /** Releases the GPU resources. UI thread, with the owning context current. */
    void dispose();
}
