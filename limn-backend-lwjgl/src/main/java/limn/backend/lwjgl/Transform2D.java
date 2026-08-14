package limn.backend.lwjgl;

/**
 * Mutable 2D affine transform mapping user (logical) coordinates to device
 * pixels: {@code devX = m00*x + m01*y + tx}, {@code devY = m10*x + m11*y + ty}.
 * Mutable on purpose: canvas state lives on the render hot path and must not
 * allocate per operation.
 */
final class Transform2D {

    private static final float AXIS_EPSILON = 1e-6f;

    float m00 = 1, m01 = 0, m10 = 0, m11 = 1, tx = 0, ty = 0;

    void setScale(float scale) {
        m00 = scale;
        m11 = scale;
        m01 = 0;
        m10 = 0;
        tx = 0;
        ty = 0;
    }

    void copyFrom(Transform2D other) {
        m00 = other.m00;
        m01 = other.m01;
        m10 = other.m10;
        m11 = other.m11;
        tx = other.tx;
        ty = other.ty;
    }

    void translate(float dx, float dy) {
        tx += m00 * dx + m01 * dy;
        ty += m10 * dx + m11 * dy;
    }

    void scale(float sx, float sy) {
        m00 *= sx;
        m10 *= sx;
        m01 *= sy;
        m11 *= sy;
    }

    /** Post-multiplies a rotation (positive = clockwise in y-down space). */
    void rotate(float angleRadians) {
        float c = (float) Math.cos(angleRadians);
        float s = (float) Math.sin(angleRadians);
        float n00 = m00 * c + m01 * s;
        float n10 = m10 * c + m11 * s;
        float n01 = -m00 * s + m01 * c;
        float n11 = -m10 * s + m11 * c;
        m00 = n00;
        m10 = n10;
        m01 = n01;
        m11 = n11;
    }

    float x(float px, float py) {
        return m00 * px + m01 * py + tx;
    }

    float y(float px, float py) {
        return m10 * px + m11 * py + ty;
    }

    /**
     * @return whether this transform maps axis-aligned rects to axis-aligned
     *         rects without flipping, the precondition for pixel snapping
     */
    boolean isAxisAligned() {
        return Math.abs(m01) < AXIS_EPSILON && Math.abs(m10) < AXIS_EPSILON && m00 > 0 && m11 > 0;
    }

    /**
     * @return axis-aligned AND uniformly scaled: the precondition for stroke
     *         snapping, whose parity rule assumes one device width per stroke
     */
    boolean isUniformAxisAligned() {
        return isAxisAligned() && Math.abs(m00 - m11) < AXIS_EPSILON * Math.max(m00, m11) + AXIS_EPSILON;
    }

    /** @return a conservative device-pixels-per-user-unit factor (max column norm) */
    float approxScale() {
        float s0 = (float) Math.hypot(m00, m10);
        float s1 = (float) Math.hypot(m01, m11);
        return Math.max(Math.max(s0, s1), 1e-6f);
    }

    /** @return the smallest device-pixels-per-user-unit factor (min column norm) */
    float minScale() {
        float s0 = (float) Math.hypot(m00, m10);
        float s1 = (float) Math.hypot(m01, m11);
        return Math.max(Math.min(s0, s1), 1e-6f);
    }
}
