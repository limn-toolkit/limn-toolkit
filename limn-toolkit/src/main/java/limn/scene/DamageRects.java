package limn.scene;

import limn.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * A frame's damage: a handful of rectangles, kept as floats and merged in place.
 *
 * <p>Every {@link Widget#invalidate()} lands in one of these, and with partial rendering the
 * default that is the busiest path in the toolkit. Kept as a {@code List<Rect>} it
 * cost an object per call and a list per frame -- ninety-six kilobytes for a thousand widgets
 * damaging themselves once, in a frame the rest of the toolkit had been made to cost nothing. Kept
 * as floats it costs nothing.
 *
 * <p><b>The arithmetic is the list version's arithmetic, in the same order</b>: the merge test,
 * the union, the cheapest pair and its tie-break, and removal that keeps the order the way
 * {@code ArrayList.remove} did. So the passes a frame paints are exactly the passes it painted
 * before this existed, which is what lets the partial-rendering tests stand as its proof.
 *
 * <p>{@linkplain #isWhole() Whole} stands for what {@code null} meant there: the whole scene.
 * The capacity is fixed at {@code max + 1} on every ordinary path; only an append of rectangles
 * already merged elsewhere can exceed it -- the damage-debug flashes, up to sixty-four of them --
 * and that path grows the array, which is the one allocation left and a debug switch's.
 */
final class DamageRects {

    private final int max;
    private float[] r;
    private int count;
    private boolean whole;

    /**
     * @param max   how many rectangles a merging {@link #add} keeps, merging the cheapest pair
     *              beyond it
     * @param whole whether it starts as the whole scene, as a frame with no history must
     */
    DamageRects(int max, boolean whole) {
        this.max = max;
        this.r = new float[(max + 1) * 4];
        this.whole = whole;
    }

    boolean isWhole() {
        return whole;
    }

    boolean isEmpty() {
        return !whole && count == 0;
    }

    int size() {
        return count;
    }

    float x(int i) {
        return r[i * 4];
    }

    float y(int i) {
        return r[i * 4 + 1];
    }

    float width(int i) {
        return r[i * 4 + 2];
    }

    float height(int i) {
        return r[i * 4 + 3];
    }

    /** {@code x + width}, the same float operation {@link Rect#right()} performs. */
    float right(int i) {
        return r[i * 4] + r[i * 4 + 2];
    }

    /** {@code y + height}, as {@link Rect#bottom()}. */
    float bottom(int i) {
        return r[i * 4 + 1] + r[i * 4 + 3];
    }

    void clear() {
        count = 0;
        whole = false;
    }

    void setWhole() {
        count = 0;
        whole = true;
    }

    void copyFrom(DamageRects other) {
        ensure(other.count);
        System.arraycopy(other.r, 0, r, 0, other.count * 4);
        count = other.count;
        whole = other.whole;
    }

    /** Adds a rectangle as it is, without merging: for rectangles already merged among themselves. */
    void append(float x, float y, float w, float h) {
        ensure(count + 1);
        int o = count * 4;
        r[o] = x;
        r[o + 1] = y;
        r[o + 2] = w;
        r[o + 3] = h;
        count++;
    }

    /**
     * Adds a rectangle, merging it with any whose union wastes little area, so that repeated and
     * overlapping damage collapses while disjoint hot spots stay separate passes: an animation at
     * the top and a footer at the bottom. Bounded at {@code max} by merging the cheapest pair.
     *
     * <p>Measured, not built: the union's area is computed from the four edges and no rectangle is
     * made to ask it, because this runs for every {@code invalidate()} in the frame and scans every
     * entry.
     */
    void add(float x, float y, float w, float h) {
        if (whole || w <= 0 || h <= 0) {
            return;
        }
        boolean merged = true;
        while (merged) { // a merge can bring the grown rect near another one
            merged = false;
            for (int i = 0; i < count; i++) {
                int o = i * 4;
                float ex = r[o];
                float ey = r[o + 1];
                float ew = r[o + 2];
                float eh = r[o + 3];
                if (unionWaste(ex, ey, ew, eh, x, y, w, h) <= 0.5f * (ew * eh + w * h)) {
                    // e.union(rect), as Rect.union computes it with e as the receiver
                    float nx = Math.min(ex, x);
                    float ny = Math.min(ey, y);
                    float nr = Math.max(ex + ew, x + w);
                    float nb = Math.max(ey + eh, y + h);
                    remove(i);
                    x = nx;
                    y = ny;
                    w = nr - nx;
                    h = nb - ny;
                    merged = true;
                    break;
                }
            }
        }
        append(x, y, w, h);
        while (count > max) {
            mergeCheapestPair();
        }
    }

    /**
     * {@code this = a ∪ b}: the whole scene when either is; otherwise {@code a}, with {@code b}'s
     * rectangles merged into it -- and when one side is empty, the other exactly as it is.
     */
    void unionOf(DamageRects a, DamageRects b) {
        if (a.whole || b.whole) {
            setWhole();
            return;
        }
        if (b.count == 0) {
            copyFrom(a);
            return;
        }
        if (a.count == 0) {
            copyFrom(b);
            return;
        }
        copyFrom(a);
        for (int i = 0; i < b.count; i++) {
            add(b.x(i), b.y(i), b.width(i), b.height(i));
        }
    }

    /** {@code this = this ∪ b}, by the same rule as {@link #unionOf}; the damage-debug path. */
    void unionWith(List<Rect> b) {
        if (whole || b.isEmpty()) {
            return;
        }
        boolean asItIs = count == 0;
        for (int i = 0; i < b.size(); i++) {
            Rect rect = b.get(i);
            if (asItIs) {
                append(rect.x(), rect.y(), rect.width(), rect.height());
            } else {
                add(rect.x(), rect.y(), rect.width(), rect.height());
            }
        }
    }

    /** Whether any rectangle overlaps the given one, edges exclusive. */
    boolean intersectsAny(float x, float y, float w, float h) {
        for (int i = 0; i < count; i++) {
            int o = i * 4;
            float ax = r[o];
            float ay = r[o + 1];
            if (ax < x + w && x < ax + r[o + 2] && ay < y + h && y < ay + r[o + 3]) {
                return true;
            }
        }
        return false;
    }

    /** Whether one rectangle alone covers a canvas of the given size. */
    boolean coversWhole(float width, float height) {
        for (int i = 0; i < count; i++) {
            if (x(i) <= 0 && y(i) <= 0 && right(i) >= width && bottom(i) >= height) {
                return true;
            }
        }
        return false;
    }

    /** The rectangles as objects, for the damage-debug overlay, which may allocate. */
    List<Rect> toList() {
        List<Rect> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Rect(x(i), y(i), width(i), height(i)));
        }
        return list;
    }

    private void mergeCheapestPair() {
        int bestA = 0;
        int bestB = 1;
        float bestWaste = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                float waste = unionWaste(x(i), y(i), width(i), height(i),
                        x(j), y(j), width(j), height(j));
                if (waste < bestWaste) {
                    bestWaste = waste;
                    bestA = i;
                    bestB = j;
                }
            }
        }
        float ax = x(bestA);
        float ay = y(bestA);
        float bx = x(bestB);
        float by = y(bestB);
        float nx = Math.min(ax, bx);
        float ny = Math.min(ay, by);
        float nr = Math.max(right(bestA), right(bestB));
        float nb = Math.max(bottom(bestA), bottom(bestB));
        remove(bestB); // higher index first
        remove(bestA);
        append(nx, ny, nr - nx, nb - ny);
    }

    /**
     * Extra area the union of two rectangles would cover beyond the two of them (negative when
     * they overlap), in exactly the float operations the {@code Rect} version performed.
     */
    private static float unionWaste(float ax, float ay, float aw, float ah,
                                    float bx, float by, float bw, float bh) {
        float width = Math.max(ax + aw, bx + bw) - Math.min(ax, bx);
        float height = Math.max(ay + ah, by + bh) - Math.min(ay, by);
        return width * height - aw * ah - bw * bh;
    }

    /** Removes one rectangle keeping the order of the rest, as {@code ArrayList.remove} did. */
    private void remove(int i) {
        System.arraycopy(r, (i + 1) * 4, r, i * 4, (count - i - 1) * 4);
        count--;
    }

    private void ensure(int rects) {
        if (rects * 4 > r.length) {
            r = java.util.Arrays.copyOf(r, Math.max(rects * 4, r.length * 2));
        }
    }
}
