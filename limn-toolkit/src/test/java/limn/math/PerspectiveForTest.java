package limn.math;

/**
 * The one matrix the public factories do not make and the turned-plane tests need: a perspective
 * seen from a distance along z, toward the viewer, which divides a point by {@code 1 - z / d}. A
 * point at {@code z = d} is at the eye and one beyond it is behind. Built here, beside
 * {@link Mat4}'s package-private constructor, rather than added to the public API for a test.
 */
public final class PerspectiveForTest {

    private PerspectiveForTest() {
    }

    /**
     * @param distance how far the eye is from the plane {@code z = 0}, in points
     * @return the perspective
     */
    public static Mat4 eyeAt(float distance) {
        float[] m = new float[16];
        m[0] = 1;
        m[5] = 1;
        m[10] = 1;
        m[15] = 1;
        m[11] = -1f / distance; // column 2, row 3: w gains -z / d
        return new Mat4(m);
    }
}
