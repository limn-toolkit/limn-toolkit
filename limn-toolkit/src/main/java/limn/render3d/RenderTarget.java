package limn.render3d;

import limn.backend.RenderStats;
import limn.graphics.GpuSurface;
import limn.graphics.ReadableSurface;

/**
 * An offscreen render target for 3D content: a color texture plus a depth
 * buffer, optionally multisampled (MSAA). Extends {@link GpuSurface} so it still
 * composites into the 2D scene via {@link limn.graphics.Canvas#drawSurface} and
 * is released via {@link limn.scene.Scene#disposeLater}. Created through
 * {@link Graphics3D}. Sizes are in <em>device</em> pixels.
 *
 * <p><b>Color space:</b> the target's contents are <em>linear light,
 * premultiplied alpha, scene-referred</em>, never display-referred. Every
 * program that writes color into one is held to that: authored sRGB values are
 * decoded to linear before they are written, so blending and the MSAA resolve
 * accumulate light rather than encoded pixels. The display transform
 * (exposure, ACES tonemap, sRGB encode) runs exactly once, in the 2D composite,
 * when the target is drawn via {@code drawSurface}.
 *
 * <p>That colour space is also why reading one back is two operations rather
 * than one: {@link ReadableSurface} is extended here (every render target can
 * be read), and its two methods return the scene-referred contents and the
 * display-referred picture respectively. {@link #exposure()} belongs to the
 * second and must not be applied to the first.
 */
public interface RenderTarget extends GpuSurface, ReadableSurface {

    /** MSAA sample count actually allocated (1 = no multisampling). */
    int samples();

    /**
     * The exposure the most recent pass into this target was given
     * ({@link RenderPass#exposure}), 1 before any pass ran. The 2D composite's
     * display transform applies it when this target is drawn; exposure belongs
     * to the display transform, not to the scene-referred pixels.
     */
    default float exposure() {
        return 1f;
    }

    /** GPU memory this target owns (color/depth attachments), for the perf monitor. */
    RenderStats stats();
}
