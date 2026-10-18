package limn.scene.internal;

import limn.scene.Widget;

/**
 * Not API. How long a fade takes when the caller names no length: the theme's window animation,
 * the one its popups, menus and dialogs fade in and out with, for the widget that asks.
 *
 * <p>The number is the theme's and {@code Opacity.fadeTo(float)} is this layer's, and
 * {@code limn.scene} names no type of the layer the theme lives in. So the theme installs a
 * resolver here as it loads, as it does for {@link LayoutOverflow}, and a widget asks this class,
 * handing itself over so that a theme resolved per subtree is followed the day there is one.
 *
 * <p>Until the theme loads, the answer is {@link #BEFORE_THEME}, and the theme refuses to load if
 * that number is not the one it answers itself, so a fade asked for before any control was built
 * takes as long as one asked for after.
 */
public final class WindowAnimation {

    /**
     * What a fade takes before the theme has installed its resolver: 0.16 seconds, the theme's
     * window animation. The theme checks as it loads that its own answer is this number.
     */
    public static final double BEFORE_THEME = 0.16;

    /** The theme's answer for one widget. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param widget the widget about to fade, whose subtree the theme is resolved for
         * @return how long a fade takes there, in seconds; finite and not negative
         */
        double secondsFor(Widget<?> widget);
    }

    private static volatile Resolver resolver;

    private WindowAnimation() {
    }

    /**
     * Installs the theme's resolver. Called by the theme as it loads; a second call replaces the
     * first.
     *
     * @param themed the resolver
     */
    public static void install(Resolver themed) {
        resolver = java.util.Objects.requireNonNull(themed, "themed");
    }

    /**
     * How long a fade of {@code widget} takes, by the theme in effect for it, before the system's
     * wish for less motion is applied.
     *
     * @param widget the widget about to fade
     * @return the length in seconds
     */
    public static double of(Widget<?> widget) {
        Resolver installed = resolver;
        return installed == null ? BEFORE_THEME : installed.secondsFor(widget);
    }
}
