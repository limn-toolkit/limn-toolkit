package limn.graphics.internal;

import limn.graphics.Icon;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The way {@link Icon#paint} learns the frame of the moment of an animated icon: from whoever is
 * painting, which {@code limn.scene} names around each widget's paint and plays for it.
 *
 * <p>The painter is this thread's, as the language of a paint is: a widget opens its turn around
 * its own paint and gives the enclosing one back after. With no painter, a paint from outside any
 * widget's or one a menu makes still, an icon shows its first frame.
 *
 * <p>The scene installs the player from its own static initializer, once; a second install is
 * refused, so the hook cannot be replaced by whoever loads first.
 */
public final class IconPlayer {

    /** What plays icons for a painter: the scene's clocks. */
    public interface Player {
        /**
         * @param painter whoever is painting, as {@link #enter} was given it
         * @param icon    an animated icon being painted
         * @param x       the left of its box, in the painter's coordinates
         * @param y       the top of its box
         * @param size    the side of its box
         * @return the frame to draw now
         */
        int frameOf(Object painter, Icon icon, float x, float y, float size);
    }

    private static final ThreadLocal<@Nullable Object> PAINTER = new ThreadLocal<>();
    private static volatile @Nullable Player player;

    private IconPlayer() {
    }

    /**
     * Installs the player.
     *
     * @param installed the scene's
     * @throws IllegalStateException when one is installed already
     */
    public static synchronized void install(Player installed) {
        Objects.requireNonNull(installed, "installed");
        if (player != null) {
            throw new IllegalStateException("the icon player is installed already");
        }
        player = installed;
    }

    /**
     * Opens a painter's turn on this thread.
     *
     * @param painter who paints now, or {@code null} for icons to show their first frame
     * @return the painter of before, which {@link #leave} must be given back
     */
    public static @Nullable Object enter(@Nullable Object painter) {
        Object enclosing = PAINTER.get();
        if (enclosing != painter) {
            PAINTER.set(painter);
        }
        return enclosing;
    }

    /**
     * Closes a painter's turn. Always in a {@code finally}.
     *
     * @param enclosing what {@link #enter} returned
     */
    public static void leave(@Nullable Object enclosing) {
        if (PAINTER.get() != enclosing) {
            PAINTER.set(enclosing);
        }
    }

    /**
     * @param icon an animated icon being painted
     * @param x    the left of its box, in the painter's coordinates
     * @param y    the top of its box
     * @param size the side of its box
     * @return the frame to draw now: 0 with no painter
     */
    public static int frameOf(Icon icon, float x, float y, float size) {
        Object painter = PAINTER.get();
        Player playing = player;
        if (painter == null || playing == null) {
            return 0;
        }
        int frame = playing.frameOf(painter, icon, x, y, size);
        return frame >= 0 && frame < icon.frameCount() ? frame : 0;
    }
}
