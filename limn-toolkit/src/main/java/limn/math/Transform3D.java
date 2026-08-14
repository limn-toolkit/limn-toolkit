package limn.math;

/**
 * A translation/rotation/scale transform, the human-friendly way to place a
 * node in 3D. {@link #toMatrix()} composes it into a model matrix (T·R·S).
 */
public record Transform3D(Vec3 translation, Quat rotation, Vec3 scale) {

    public static final Transform3D IDENTITY =
            new Transform3D(Vec3.ZERO, Quat.IDENTITY, Vec3.ONE);

    /** A transform with only a translation: no rotation, unit scale. */
    public static Transform3D at(Vec3 translation) {
        return new Transform3D(translation, Quat.IDENTITY, Vec3.ONE);
    }

    /** A copy with a different translation. */
    public Transform3D withTranslation(Vec3 t) {
        return new Transform3D(t, rotation, scale);
    }

    /** A copy with a different rotation. */
    public Transform3D withRotation(Quat r) {
        return new Transform3D(translation, r, scale);
    }

    /** A copy with a different scale. */
    public Transform3D withScale(Vec3 s) {
        return new Transform3D(translation, rotation, s);
    }

    /** The composed matrix, applied scale first, then rotation, then translation. */
    public Mat4 toMatrix() {
        return Mat4.trs(translation, rotation, scale);
    }
}
