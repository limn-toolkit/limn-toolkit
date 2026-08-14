package limn.backend.lwjgl;

import limn.video.VideoSurface;
import limn.video.VideoSurfaces;

/**
 * Backend {@link VideoSurfaces.Provider}: a stateless router to the currently
 * rendering window's per-context {@link GlVideoContext}, the same shape as the
 * 3D provider. A surface is created inside a widget's paint, where exactly one
 * {@link GlCanvas} is {@linkplain GlCanvas#current() current}, so there is no
 * ambiguity about which context owns it.
 */
final class LwjglVideoSurfaces implements VideoSurfaces.Provider {

    @Override
    public VideoSurface createSurface() {
        GlCanvas canvas = GlCanvas.current();
        if (canvas == null) {
            throw new IllegalStateException(
                    "a video surface must be created during a frame (no current GL canvas)");
        }
        return canvas.glVideo().createSurface();
    }
}
