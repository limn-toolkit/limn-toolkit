package limn.scene.internal;

import limn.math.Mat4;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

/**
 * Not API. The way a container in another package sets the matrix a widget draws its children
 * through: where the pointer finds them ({@link Widget#hitTest} through {@code hitTestChild}),
 * where a point of theirs is in the scene and back ({@link Widget#localToScene(float, float)},
 * {@link Widget#sceneToLocal(float, float)},
 * {@link Widget#localToSceneBounds(float, float, float, float)}), and the bounds the accessible
 * tree publishes and a repaint culls by, all follow it. Kept off {@link Widget}'s public face so
 * that only a container offers a transform, as {@link OpacityAccess} keeps the opacity.
 *
 * <p>The matrix maps a point {@code (x, y)} of the widget's own space, a child's position
 * included, to the matrix times {@code (x, y, 0, 1)} divided by its {@code w}: the children lie in
 * the plane {@code z = 0}, the axes are the canvas's (x to the right, y down, z toward the viewer),
 * and a matrix with a perspective puts the eye where {@code w} reaches 0. A widget whose matrix is
 * not the identity also lets the pointer through its ancestors' boxes to where it draws its
 * children, unless an ancestor clips them.
 *
 * <p>Not yet followed by the paint, the damage or {@code isShowing()}: in this build only tests
 * set a matrix, to prove the conversions and the hit test at turned points before anything draws
 * turned.
 *
 * <p>The widget installs the hook from its own static initializer, and only the widget can: an
 * install from any other class is refused, so the hook cannot be replaced by whoever loads first.
 */
public final class TransformAccess {

    /** What the widget lends this class. */
    public interface Hook {
        /**
         * @param widget the widget whose children the matrix moves
         * @param matrix the matrix, or null (as the identity) to draw the children where layout
         *               put them
         */
        void setChildMatrix(Widget<?> widget, @Nullable Mat4 matrix);

        /**
         * @param widget a widget
         * @return the matrix it draws its children through, or null while it is at rest
         */
        @Nullable Mat4 childMatrix(Widget<?> widget);
    }

    private static volatile Hook hook;

    private TransformAccess() {
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
            throw new IllegalStateException("only Widget installs its transform hook, once");
        }
        hook = widgetHook;
    }

    /**
     * Sets the matrix {@code widget} draws its children through. UI thread only.
     *
     * @param widget the widget
     * @param matrix the matrix, or null for none; the identity counts as none
     */
    public static void setChildMatrix(Widget<?> widget, @Nullable Mat4 matrix) {
        hook.setChildMatrix(widget, matrix);
    }

    /**
     * @param widget a widget
     * @return the matrix it draws its children through, or null while it is at rest
     */
    public static @Nullable Mat4 childMatrix(Widget<?> widget) {
        return hook.childMatrix(widget);
    }
}
