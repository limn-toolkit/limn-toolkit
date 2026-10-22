package limn.print.internal;

/**
 * A plane's affine map, {@code x' = a x + c y + e} and {@code y' = b x + d y + f}, as PDF writes
 * one: what the recording canvas keeps to answer where its clip is, and what the PDF canvas
 * multiplies to place a pattern.
 */
record Affine(double a, double b, double c, double d, double e, double f) {

    static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

    /** @return this map followed by nothing, preceded by {@code inner}: {@code this ∘ inner} */
    Affine times(Affine inner) {
        return new Affine(
                a * inner.a + c * inner.b,
                b * inner.a + d * inner.b,
                a * inner.c + c * inner.d,
                b * inner.c + d * inner.d,
                a * inner.e + c * inner.f + e,
                b * inner.e + d * inner.f + f);
    }

    Affine translate(double dx, double dy) {
        return times(new Affine(1, 0, 0, 1, dx, dy));
    }

    Affine scale(double sx, double sy) {
        return times(new Affine(sx, 0, 0, sy, 0, 0));
    }

    /** Positive angles turn clockwise, y growing down, as {@code Canvas.rotate} does. */
    Affine rotate(double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return times(new Affine(cos, sin, -sin, cos, 0, 0));
    }

    double x(double px, double py) {
        return a * px + c * py + e;
    }

    double y(double px, double py) {
        return b * px + d * py + f;
    }

    /** Whether the map keeps the axes: no turn and no skew, so a rectangle stays one. */
    boolean axisAligned() {
        return Math.abs(b) < 1e-9 && Math.abs(c) < 1e-9;
    }

    /** @return the larger of the two axes' magnifications */
    double maxScale() {
        return Math.max(Math.hypot(a, b), Math.hypot(c, d));
    }
}
