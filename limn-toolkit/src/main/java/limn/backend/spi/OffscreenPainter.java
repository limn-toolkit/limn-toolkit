package limn.backend.spi;

import limn.graphics.Canvas;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Draws into a picture of its own, off any window, and hands the picture back as an {@link Image}:
 * what a printed page asks for where PDF has no operator for what was drawn, a layer drawn under a
 * Porter-Duff rule, which reaches the page as that image at the page's resolution.
 *
 * <p>The backend that draws windows implements it with the same canvas, so the image is what a
 * window would show. Optional: a backend that has none leaves it out, and a document then writes such
 * a layer as normal painting, with a warning.
 *
 * <p><b>UI thread only</b>, and between frames: the canvas is the backend's.
 */
public interface OffscreenPainter {

    /**
     * Paints {@code painter} into a transparent picture {@code width} by {@code height} pixels, one
     * unit of the canvas to one pixel, and answers it.
     *
     * @param width   the picture's width in pixels, at least 1
     * @param height  its height
     * @param painter draws it; the canvas is valid only during the call
     * @return the picture, straight alpha, or null when none could be made: no window whose context
     *         can draw, a size the device refuses, or a call made inside a frame
     */
    @Nullable Image paint(int width, int height, Consumer<Canvas> painter);

    /**
     * Installs a backend's painter where a document reads it; called by
     * {@link BackendServices#install()}.
     *
     * @param painter the backend's painter
     */
    static void install(OffscreenPainter painter) {
        limn.print.internal.Offscreen.install(painter);
    }

    /**
     * Takes back a painter installed by {@link #install}, at the backend's shutdown; another one
     * installed since stays.
     *
     * @param painter the painter that was installed
     */
    static void uninstall(OffscreenPainter painter) {
        limn.print.internal.Offscreen.uninstall(painter);
    }

    /** A painter that makes no picture. */
    OffscreenPainter NONE = (width, height, painter) -> null;
}
