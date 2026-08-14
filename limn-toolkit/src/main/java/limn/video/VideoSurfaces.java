package limn.video;

import limn.concurrent.Ui;

import java.util.Objects;

/**
 * Video-surface facade + install-at-startup SPI (the same inversion as the image, audio and 3D
 * facades): a neutral entry point here, the device implementation installed by the backend. One
 * provider, unlike {@link Videos}'s ordered list of decoders: a surface is created by whichever
 * backend owns the window being rendered, and there is nothing for a second one to claim.
 *
 * <pre>{@code
 * VideoSurface surface = VideoSurfaces.create();   // once, while the widget is alive
 * // ... per picture, inside the frame that draws it:
 * surface.upload(frame);
 * canvas.drawSurface(surface, x, y, width, height);
 * }</pre>
 *
 * <p>{@link #create()} must run on the UI thread inside a frame, because the surface belongs to the
 * GL context of the window rendering it. Installation and the availability probe are safe from any
 * thread.
 */
public final class VideoSurfaces {

    /** Backend-supplied factory, installed at startup. */
    public interface Provider {

        /**
         * Creates a surface belonging to the window currently rendering. Called on the UI thread,
         * inside a frame. The facade checks the thread, and an implementation whose surfaces
         * belong to a rendering context must refuse when there is no frame, because a surface
         * created against the wrong context draws from another window's textures.
         *
         * @return the new surface, sized 0×0 until its first {@link VideoSurface#upload}; never null
         * @throws IllegalStateException if no frame is being rendered
         */
        VideoSurface createSurface();
    }

    private static volatile Provider provider;

    private VideoSurfaces() {
    }

    /** Installs the backend's provider. Called once at startup, before any surface is created. */
    public static void install(Provider newProvider) {
        provider = Objects.requireNonNull(newProvider, "newProvider");
    }

    /** Removes the provider if it is still {@code expected}, so a late teardown cannot clear a newer one. */
    public static void uninstall(Provider expected) {
        if (provider == expected) {
            provider = null;
        }
    }

    /** @return whether a backend provider is installed (false when running headless) */
    public static boolean isAvailable() {
        return provider != null;
    }

    /**
     * Creates a surface for the window being rendered. The caller owns it and must release it: a
     * surface holds a picture's worth of device memory, and a stream that opens one per playback
     * and never releases it leaks that much per playback. Release it from inside a frame with
     * {@link VideoSurface#dispose()}, or from a widget being detached with
     * {@link limn.scene.Scene#disposeLater}, which defers it to one.
     *
     * @throws IllegalStateException if no backend provider is installed, if the calling thread is
     *                               not the UI thread, or if no frame is being rendered
     */
    public static VideoSurface create() {
        Ui.checkUiThread();
        Provider active = provider;
        if (active == null) {
            throw new IllegalStateException("no VideoSurfaces provider. Is the backend started?");
        }
        return active.createSurface();
    }
}
