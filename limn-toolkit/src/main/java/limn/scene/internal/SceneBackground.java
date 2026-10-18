package limn.scene.internal;

import limn.concurrent.Subscription;
import limn.concurrent.internal.ChangeListeners;
import limn.graphics.Color;
import limn.scene.Widget;

/**
 * Not API. The colour a scene clears to when the application set none, the background of the
 * theme in effect for its root, and the word that the theme changed, which every scene answers by
 * repainting.
 *
 * <p>The colour is the theme's and the scene is this layer's, and {@code limn.scene} names no type
 * of the layer the theme lives in. So the theme installs a resolver here as it loads, as it does
 * for {@link LayoutOverflow}, and tells this class when the palette moves; a scene asks for the
 * colour on every frame it clears, and subscribes to the change as it subscribes to the four
 * measurement axes. When the scene copied the colour out of the palette as it was built, every
 * application wrote {@code setBackground(Theme.current().background())} by hand, and a switch
 * repainted only the scenes it was handed: a window opened after it, or never handed over, drew
 * the new palette's widgets on the old palette's canvas.
 *
 * <p>Until the theme loads, the answer is {@link #BEFORE_THEME}, and the theme refuses to load if
 * that colour is not the one its starting palette answers, so a scene cleared before any control
 * was built clears to what it clears to after.
 */
public final class SceneBackground {

    /**
     * What a scene clears to before the theme has installed its resolver: the background of the
     * dark palette the theme starts on. The theme checks as it loads that its own answer is this
     * colour.
     */
    public static final Color BEFORE_THEME = Color.rgb(0x14181F);

    /** The theme's answer for one scene. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param root the scene's root, whose subtree the theme is resolved for
         * @return the colour that scene clears to; asked on every frame, so it must not allocate
         */
        Color backgroundFor(Widget<?> root);
    }

    private static volatile Resolver resolver;

    private static final ChangeListeners LISTENERS = new ChangeListeners();

    private SceneBackground() {
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
     * The colour a scene whose root is {@code root} clears to when its application set none.
     *
     * @param root the scene's root
     * @return the background of the theme in effect for it
     */
    public static Color of(Widget<?> root) {
        Resolver installed = resolver;
        return installed == null ? BEFORE_THEME : installed.backgroundFor(root);
    }

    /**
     * Tells every scene that the theme changed: each repaints whole, since every widget in it, and
     * the colour it clears to unless its application chose one, read the palette as they paint.
     * Called by the theme when its palette moves.
     */
    public static void changed() {
        LISTENERS.fire();
    }

    /**
     * Subscribes to {@link #changed}: the scene's way to hear the theme without naming it.
     *
     * @param listener what to run when the theme changes
     * @return a handle that unsubscribes; cancelling it twice is a no-op
     */
    public static Subscription observeChanges(Runnable listener) {
        return LISTENERS.observe(listener);
    }
}
