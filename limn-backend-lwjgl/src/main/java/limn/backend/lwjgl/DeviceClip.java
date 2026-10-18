package limn.backend.lwjgl;

/**
 * The clip one canvas state carries, in device pixels, kept as two things rather than one: the
 * rectangle every clip so far has narrowed, and the rounded rectangle at its own size.
 *
 * <p><b>Why two.</b> The fragment shader rounds a clip's corners from a rectangle and a radius, and
 * the rectangle it used to be handed was the intersection of every clip, rectangular ones
 * included. So a rounded clip was rounded at the corners of whatever box was left once everything
 * else had cut it, and not at its own corners. A partial repaint clips each pass to a rectangle
 * before anything paints, which made every pass that cut a rounded surface grow rounded corners
 * where it cut: the pass clears its region first, and the cleared background showed through those
 * false corners as dark arcs inside the surface. A widget clipping a rectangle of its own inside a
 * rounded clip drew the same arcs in a whole frame. The shader takes the rectangle's coverage,
 * antialiased over half a pixel either side of its edges and so hard at a pixel-aligned one, and
 * intersects it with the rounded rectangle's own, so a rectangular clip, a pass's or a widget's,
 * cuts straight and never changes a pixel inside itself.
 *
 * <p><b>The rectangle</b> ({@link #x0} to {@link #y1}) is every clip's device bounds intersected,
 * the rounded ones' bounds as well: it is what a draw is culled against, what
 * {@code Canvas.clipBounds()} maps back to current coordinates, and the hard edge the shader applies.
 *
 * <p><b>The rounded rectangle</b> ({@link #roundX0} to {@link #roundY1}, {@link #radius}) is the
 * most recent rounded clip's own device bounds and radius. One rounded rectangle is all the state
 * tracks, so a second one nested in the first intersects the two rectangles and rounds what they
 * leave by its own radius alone, which is exact whenever the second lies inside the first (a field
 * inside a card) and was the whole model before; a rectangular clip never takes part in it. A
 * rounded clip whose corners are all square is a rectangle and clips as one, leaving an earlier
 * rounded clip's corners where they were. With no rounded clip the rounded rectangle is the whole
 * target with no radius, which contains the clip rectangle and so leaves the shader's intersection
 * of the two exactly the rectangle's.
 *
 * <p>Pooled with its state and written in place: nothing here allocates.
 */
final class DeviceClip {

    /** The clip rectangle, device pixels: every clip's bounds intersected. */
    float x0, y0, x1, y1;
    /** The rounded clip's own rectangle, device pixels; the whole target when there is none. */
    float roundX0, roundY0, roundX1, roundY1;
    /** The rounded clip's corner radius, device pixels; 0 when there is none. */
    float radius;

    /** The clip of a fresh frame: the whole target, and no rounded clip. */
    void reset(float width, float height) {
        x0 = 0;
        y0 = 0;
        x1 = width;
        y1 = height;
        roundX0 = 0;
        roundY0 = 0;
        roundX1 = width;
        roundY1 = height;
        radius = 0;
    }

    /**
     * The clip of a space of its own, a re-based layer's: its picture's rectangle, and no rounded
     * clip, whatever clipped the state it was opened in, which its composite applies instead.
     */
    void resetTo(float left, float top, float right, float bottom) {
        x0 = left;
        y0 = top;
        x1 = right;
        y1 = bottom;
        roundX0 = left;
        roundY0 = top;
        roundX1 = right;
        roundY1 = bottom;
        radius = 0;
    }

    void copyFrom(DeviceClip other) {
        x0 = other.x0;
        y0 = other.y0;
        x1 = other.x1;
        y1 = other.y1;
        roundX0 = other.roundX0;
        roundY0 = other.roundY0;
        roundX1 = other.roundX1;
        roundY1 = other.roundY1;
        radius = other.radius;
    }

    /** @return whether the clip rectangle has no area, so nothing drawn under it can show */
    boolean isEmpty() {
        return x1 - x0 <= 0 || y1 - y0 <= 0;
    }

    /** Intersects the clip rectangle with a device rectangle; the rounded clip is untouched. */
    void intersectRect(float dx0, float dy0, float dx1, float dy1) {
        x0 = Math.max(x0, dx0);
        y0 = Math.max(y0, dy0);
        x1 = Math.min(x1, dx1);
        y1 = Math.min(y1, dy1);
        if (x1 < x0) {
            x1 = x0;
        }
        if (y1 < y0) {
            y1 = y0;
        }
    }

    /**
     * Intersects the clip with a rounded device rectangle: its bounds narrow the clip rectangle like
     * any other clip's, and it becomes the rounded clip, its rectangle intersected with an earlier
     * rounded clip's when there is one, and {@code cornerRadius} the radius. A radius of zero or less
     * is a plain rectangle and changes only the clip rectangle.
     */
    void intersectRoundRect(float dx0, float dy0, float dx1, float dy1, float cornerRadius) {
        intersectRect(dx0, dy0, dx1, dy1);
        if (cornerRadius <= 0) {
            return;
        }
        if (radius > 0) {
            roundX0 = Math.max(roundX0, dx0);
            roundY0 = Math.max(roundY0, dy0);
            roundX1 = Math.min(roundX1, dx1);
            roundY1 = Math.min(roundY1, dy1);
        } else {
            // The first rounded clip is taken at its own size, never cut to the target: a surface
            // hanging off the window's edge would otherwise be rounded where the window cuts it.
            roundX0 = dx0;
            roundY0 = dy0;
            roundX1 = dx1;
            roundY1 = dy1;
        }
        if (roundX1 < roundX0) {
            roundX1 = roundX0;
        }
        if (roundY1 < roundY0) {
            roundY1 = roundY0;
        }
        radius = cornerRadius;
    }
}
