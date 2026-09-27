package limn.scene.internal;

import limn.scene.Widget;

/**
 * Not API. How far a layout declares its children may paint past it when the application set no
 * number: the reach of the focus ring in the theme in effect for that layout.
 *
 * <p>The number is the theme's and the layouts are this layer's, and {@code limn.scene} names no
 * type of the layer the theme lives in. So the theme installs a resolver here as it loads, and a
 * layout asks this class, handing itself over so that a theme resolved per subtree is followed the
 * day there is one. What the layouts do with the answer is {@code paintOverflow} on each of them.
 *
 * <p>Until the theme loads, the answer is {@link #BEFORE_THEME}, and the theme refuses to load if
 * that number is not the one it answers itself. Without that a layout would declare one reach
 * before any control had been built and another after, and a test that never touched the theme
 * would measure different damage from one that ran after a test that did.
 */
public final class LayoutOverflow {

    /**
     * What a layout declares before the theme has installed its resolver: 3 points, the reach of
     * the focus ring the toolkit's controls draw outside their box. The theme checks as it loads
     * that its own answer is this number.
     */
    public static final float BEFORE_THEME = 3;

    /** The theme's answer for one layout. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param layout the layout asking, whose subtree the theme is resolved for
         * @return how far that layout's children may paint past it, in logical points; finite
         *         and not negative, and it must not allocate, because a repaint pass asks it of
         *         every layout it considers
         */
        float reachFor(Widget<?> layout);
    }

    private static volatile Resolver resolver;

    private LayoutOverflow() {
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
     * How far {@code layout}'s children may paint past it, by the theme in effect for it. O(1)
     * and allocation-free: one read of the installed resolver and one call to it.
     *
     * @param layout the layout asking
     * @return the reach in logical points
     */
    public static float of(Widget<?> layout) {
        Resolver installed = resolver;
        return installed == null ? BEFORE_THEME : installed.reachFor(layout);
    }
}
