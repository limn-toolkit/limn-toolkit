package limn.backend.spi;

import limn.graphics.Canvas;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Draws into a picture of its own, off any window, and hands the picture back as an {@link Image}:
 * what {@link limn.graphics.Images#render} answers, and what a printed page asks for where PDF has no
 * operator for what was drawn, a layer drawn under a Porter-Duff rule, which reaches the page as that
 * image at the page's resolution.
 *
 * <p>The backend that draws windows implements it with the same canvas, so the image is what a
 * window would show. Optional: a backend that has none leaves it out, and a document then writes such
 * a layer as normal painting, with a warning.
 *
 * <p><b>UI thread only</b>: the canvas is the backend's. Inside a frame too, between two draws of
 * it: what the frame queued is drawn first, and the frame goes on where it was.
 */
public interface OffscreenPainter {

    /**
     * Paints {@code painter} into a transparent picture {@code width} by {@code height} pixels, on a
     * canvas of {@code scale} pixels to its unit, and answers it.
     *
     * @param width   the picture's width in pixels, at least 1
     * @param height  its height
     * @param scale   the canvas's content scale: its width is {@code width / scale} units
     * @param painter draws it; the canvas is valid only during the call
     * @return the picture, straight alpha, or null when none could be made: no window whose context
     *         can draw, or a size the device refuses
     */
    @Nullable Image paint(int width, int height, float scale, Consumer<Canvas> painter);

    /**
     * Installs a backend's painter where a document reads it; called by
     * {@link BackendServices#install()}.
     *
     * @param painter the backend's painter
     */
    static void install(OffscreenPainter painter) {
        limn.graphics.internal.Offscreen.install(painter);
    }

    /**
     * Takes back a painter installed by {@link #install}, at the backend's shutdown; another one
     * installed since stays.
     *
     * @param painter the painter that was installed
     */
    static void uninstall(OffscreenPainter painter) {
        limn.graphics.internal.Offscreen.uninstall(painter);
    }

    /** A painter that makes no picture. */
    OffscreenPainter NONE = (width, height, scale, painter) -> null;
}
