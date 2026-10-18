package limn.scene;

import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.scene.internal.TransformAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A container for tests that turns its child for the pointer and the conversions only: through
 * the internal hook a transform container will set, it hands the scene a matrix its child is
 * drawn through, while its paint draws the child where layout put it, as no container yet can
 * draw it turned. What the seams must be right for, rotations and turns in 3D, can be proved
 * with it before anything draws them.
 *
 * <p>Laid out as the container a transform will be: one child, at its origin, with its size;
 * hit only where its child is, and {@code null} elsewhere.
 */
public final class Turned extends Widget<Turned> {

    private final Widget<?> child;

    /** @param child the widget turned */
    public Turned(Widget<?> child) {
        this.child = Objects.requireNonNull(child, "child");
        add(child);
    }

    /**
     * @param matrix what the child is drawn through, about this container's own origin, or null
     * @return this
     */
    public Turned setMatrix(@Nullable Mat4 matrix) {
        TransformAccess.setChildMatrix(this, matrix);
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = child.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        child.layoutBox(0, 0, width(), height());
    }

    @Override
    protected @Nullable Widget<?> onHitTest(float localX, float localY) {
        return hitTestChild(child, localX, localY); // the child or nothing: never this container
    }

    // ------------------------------------------------------------------ matrices

    /**
     * @param cx      the pivot's x
     * @param cy      the pivot's y
     * @param degrees clockwise on the screen
     * @return a rotation in the plane about the pivot
     */
    public static Mat4 rotation(float cx, float cy, float degrees) {
        return about(cx, cy, Mat4.rotation(Quat.fromAxisAngle(new Vec3(0, 0, 1),
                (float) Math.toRadians(degrees))));
    }

    /**
     * @param cx       the pivot's x
     * @param cy       the pivot's y
     * @param degrees  how far the plane turns about the vertical axis through the pivot
     * @param distance how far the eye is in front of the pivot
     * @return the turn, seen in perspective
     */
    public static Mat4 turn(float cx, float cy, float degrees, float distance) {
        Mat4 rotation = Mat4.rotation(Quat.fromAxisAngle(new Vec3(0, 1, 0), (float) Math.toRadians(degrees)));
        return about(cx, cy, Mat4.perspectiveAt(distance).multiply(rotation));
    }

    /**
     * @param cx the pivot's x
     * @param cy the pivot's y
     * @param sx the scale along x
     * @param sy the scale along y
     * @return the scale about the pivot
     */
    public static Mat4 scale(float cx, float cy, float sx, float sy) {
        return about(cx, cy, Mat4.scale(new Vec3(sx, sy, 1)));
    }

    /**
     * @param dx along x
     * @param dy along y
     * @return the translation
     */
    public static Mat4 translation(float dx, float dy) {
        return Mat4.translation(new Vec3(dx, dy, 0));
    }

    private static Mat4 about(float cx, float cy, Mat4 inner) {
        return Mat4.translation(new Vec3(cx, cy, 0)).multiply(inner).multiply(Mat4.translation(new Vec3(-cx, -cy, 0)));
    }

    /**
     * Where a matrix draws a point, computed here and not by the code under test: the point
     * through the matrix, divided by its {@code w}, or {@code NaN} behind the eye.
     *
     * @param matrix the matrix
     * @param x      the point's x
     * @param y      the point's y
     * @return the drawn point, {@code {x, y}}
     */
    public static float[] drawnAt(Mat4 matrix, float x, float y) {
        Vec4 p = matrix.transform(new Vec4(x, y, 0, 1));
        if (!(p.w() > 0)) {
            return new float[] {Float.NaN, Float.NaN};
        }
        return new float[] {p.x() / p.w(), p.y() / p.w()};
    }
}
