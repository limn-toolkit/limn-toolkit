package limn.scene.internal;

import limn.math.Mat4;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

/**
 * Not API. The way {@link limn.scene.layout.Transform}, a layout in another package, reaches the
 * matrix a widget draws its children through, and the values and motions that make it: where its
 * child is drawn ({@link Widget#paintWidget}), where the pointer finds it ({@link Widget#hitTest}
 * through {@code hitTestChild}), where a point of it is in the scene and back
 * ({@link Widget#localToScene(float, float)}, {@link Widget#sceneToLocal(float, float)},
 * {@link Widget#localToSceneBounds(float, float, float, float)}), what a change damages, what
 * {@link Widget#isShowing()} says and the bounds the accessible tree publishes, all follow it. Kept
 * off {@link Widget}'s public face so that only a container offers a transform, as
 * {@link OpacityAccess} keeps the opacity.
 *
 * <p>The matrix maps a point {@code (x, y)} of the widget's own space, a child's position
 * included, to the matrix times {@code (x, y, 0, 1)} divided by its {@code w}: the children lie in
 * the plane {@code z = 0}, the axes are the canvas's (x to the right, y down, z toward the viewer),
 * and a matrix with a perspective puts the eye where {@code w} reaches 0. A widget whose matrix is
 * not the identity also lets the pointer through its ancestors' boxes to where it draws its
 * children, unless an ancestor clips them, and a repaint pass reaches them there.
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

        /**
         * Makes {@code widget} a transform container: from now on its values set the matrix it
         * draws its children through, they settle as it leaves its scene, and a watcher hears
         * them on it.
         *
         * @param widget the container
         * @return its values, to keep
         */
        Values makeContainer(Widget<?> widget);

        /**
         * @param widget a widget
         * @return how far it, or what it draws its children through, paints past its box: its
         *         children's own reach, carried through its matrix
         */
        float paintOutsetOf(Widget<?> widget);
    }

    /**
     * The values one transform container draws its child with, and the motions that move them,
     * by the rules {@link limn.scene.layout.Transform} states. Each setter checks the thread and the
     * paint, refuses what is not a finite number, supersedes the motion of its own value and
     * announces what it changed; the container adds nothing but the fluent return.
     */
    public interface Values {
        /** @return the translation's x, toward the end of the layout direction */
        float translationX();

        /** @return the translation's y, down */
        float translationY();

        /** @return the scale along x */
        float scaleX();

        /** @return the scale along y */
        float scaleY();

        /** @return the origin's x, a fraction of the box from its start edge */
        float originX();

        /** @return the origin's y, a fraction of the box from its top */
        float originY();

        /** @return the rotation, clockwise on the screen, in degrees */
        float rotation();

        /** @return the general matrix, the identity when there is none */
        Mat4 matrix();

        /** @param degrees clockwise on the screen */
        void setRotation(float degrees);

        /** @param matrix the general matrix, applied first, affine on the plane */
        void setMatrix(Mat4 matrix);

        /**
         * @param x toward the end
         * @param y down
         */
        void setTranslation(float x, float y);

        /**
         * @param sx along x
         * @param sy along y
         */
        void setScale(float sx, float sy);

        /**
         * @param fractionX from the start edge
         * @param fractionY from the top
         */
        void setOrigin(float fractionX, float fractionY);

        /** Every value and every motion back at rest, as one change. */
        void reset();

        /**
         * @param x        toward the end
         * @param y        down
         * @param seconds  how long
         * @param onArrive run when it arrives, or null
         */
        void slideTo(float x, float y, double seconds, @Nullable Runnable onArrive);

        /**
         * @param sx       along x
         * @param sy       along y
         * @param seconds  how long
         * @param onArrive run when it arrives, or null
         */
        void scaleTo(float sx, float sy, double seconds, @Nullable Runnable onArrive);

        /**
         * @param degrees  clockwise on the screen
         * @param seconds  how long
         * @param onArrive run when it arrives, or null
         */
        void rotateTo(float degrees, double seconds, @Nullable Runnable onArrive);

        /**
         * @param aboutVertical whether the turn is about the vertical axis (forward and backward)
         *                      or the horizontal one (up and down)
         * @param reverse       backward rather than forward, or down rather than up
         * @param seconds       how long
         * @param atEdge        run with the child edge-on
         * @param onArrive      run when it arrives, or null
         */
        void flipTo(boolean aboutVertical, boolean reverse, double seconds, Runnable atEdge,
                    @Nullable Runnable onArrive);

        /** The container was laid out: the origin's place, and the direction, may have moved. */
        void laidOut();
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

    /**
     * Makes {@code widget} a transform container; see {@link Hook#makeContainer}.
     *
     * @param widget the container
     * @return its values
     */
    public static Values makeContainer(Widget<?> widget) {
        return hook.makeContainer(widget);
    }

    /**
     * @param widget a widget
     * @return how far it paints past its box, its children carried through its matrix
     */
    public static float paintOutsetOf(Widget<?> widget) {
        return hook.paintOutsetOf(widget);
    }
}
