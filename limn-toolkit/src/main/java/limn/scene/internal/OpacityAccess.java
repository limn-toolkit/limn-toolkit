package limn.scene.internal;

import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

/**
 * Not API. The way from {@link limn.scene.layout.Opacity} to the group opacity a widget's paint
 * reads: the container is a layout and lives in {@code limn.scene.layout}, and the value, its
 * damage, its fade and the fade's settling as the widget leaves its scene are {@code limn.scene}'s,
 * kept off {@link Widget}'s public face so that only the container offers them.
 *
 * <p>The widget installs the hook from its own static initializer, and only the widget can: an
 * install from any other class is refused, so the hook cannot be replaced by whoever loads first.
 */
public final class OpacityAccess {

    /** What the widget lends this class. */
    public interface Hook {
        /**
         * Makes {@code widget} a group: from now on its paint draws it and its subtree as one
         * picture, at the opacity this class sets, and under a blend mode other than normal at 1
         * too.
         *
         * @param widget the container
         */
        void makeGroup(Widget<?> widget);

        /**
         * @param widget a group
         * @return its opacity
         */
        float opacity(Widget<?> widget);

        /**
         * @param widget  a group
         * @param opacity the opacity to set
         */
        void set(Widget<?> widget, float opacity);

        /**
         * @param widget   a group
         * @param target   the opacity to arrive at
         * @param seconds  how long the fade takes
         * @param onArrive run when it arrives, or null
         */
        void fadeTo(Widget<?> widget, float target, double seconds, @Nullable Runnable onArrive);

        /**
         * @param child a widget
         * @return how far it declares it paints past its box, its protected {@code paintOutset()}
         */
        float paintOutsetOf(Widget<?> child);
    }

    private static volatile Hook hook;

    private OpacityAccess() {
    }

    /**
     * Installs the widget's hook. Called by {@link Widget}'s static initializer.
     *
     * @param widgetHook the widget's hook
     * @throws IllegalStateException when called by any other class, or twice
     */
    public static void install(Hook widgetHook) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (caller != Widget.class || hook != null) {
            throw new IllegalStateException("only Widget installs its opacity hook, once");
        }
        hook = widgetHook;
    }

    /**
     * Makes {@code widget} a group; see {@link Hook#makeGroup}.
     *
     * @param widget the container
     */
    public static void makeGroup(Widget<?> widget) {
        hook.makeGroup(widget);
    }

    /**
     * @param widget a group
     * @return its opacity, 0 to 1
     */
    public static float opacity(Widget<?> widget) {
        return hook.opacity(widget);
    }

    /**
     * Sets a group's opacity, by the rules {@link limn.scene.layout.Opacity#setOpacity} states.
     *
     * @param widget  a group
     * @param opacity the opacity
     */
    public static void set(Widget<?> widget, float opacity) {
        hook.set(widget, opacity);
    }

    /**
     * Fades a group's opacity, by the rules {@link limn.scene.layout.Opacity#fadeTo(float, double,
     * Runnable)} states.
     *
     * @param widget   a group
     * @param target   the opacity to arrive at
     * @param seconds  how long the fade takes
     * @param onArrive run when it arrives, or null
     */
    public static void fadeTo(Widget<?> widget, float target, double seconds, @Nullable Runnable onArrive) {
        hook.fadeTo(widget, target, seconds, onArrive);
    }

    /**
     * @param child a widget
     * @return how far it declares it paints past its box
     */
    public static float paintOutsetOf(Widget<?> child) {
        return hook.paintOutsetOf(child);
    }
}
