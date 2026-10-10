package limn.components.internal;

import limn.concurrent.Ui;
import limn.scene.Scene;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rows that are drawn somewhere else than where the layout put them, for a while: the rows a
 * pointer's drag lifted out are not drawn at all, the ones below close over their places, room
 * opens where a drop would land, and every change of any of it slides. What the list and the
 * tree each held a copy of.
 *
 * <p>It keeps no layout. It answers, for the realized rows of a {@link Rows}, how far from its
 * laid-out place each is drawn at this moment, and it wears the difference down to nothing over
 * about a tenth of a second, at once under reduced motion. The widget says which rows are lifted
 * and where the room begins, and paints each row at its place plus what this answers.
 *
 * <p><b>UI thread only.</b> Not API: see the package.
 */
public final class RowSlides {

    /** How long a row takes to cover most of the way to its new place, in seconds. */
    private static final double SLIDE_SECONDS = 0.08;

    /**
     * How much of the height of what is dragged the room opened for it takes. Half: a row is
     * mostly its own margins, and the rows on either side of the room bring theirs, so room as
     * tall as the row read as twice the space the item needs. What is dropped takes the rest by
     * pushing its neighbours apart, which slides like everything else.
     */
    private static final float ROOM_SHARE = 0.5f;

    /** What a widget answers about the drag in flight and about its rows. */
    public interface Host {

        /** Called before the rows are asked about: a widget that works out what is lifted does it here. */
        void prepare();

        /**
         * @param row a row
         * @return whether it is out of the widget for the drag in flight, and so not drawn
         */
        boolean isLifted(int row);

        /** @return the first row the room opened for a drop pushes down, or -1 when none is open */
        int roomFrom();

        /**
         * @param row a row
         * @return what the row shows, by which it is known again after the rows change under it;
         *         null for a row that shows nothing of the application's
         */
        @Nullable Object keyOf(int row);

        /**
         * @param position among the realized rows, in row order
         * @return where the layout put that row's top
         */
        float yAt(int position);

        /** @return the scene the widget is in, or null */
        @Nullable Scene scene();

        /** Asks for a repaint of the rows. */
        void invalidate();
    }

    private final Rows<?> rows;
    private final Host host;
    private final boolean byIdentity;
    /**
     * How far from where it belongs each row is drawn at this moment, by row: what is left of a
     * slide, which a ticker wears down. And where each row was last said to belong, against which
     * the next change is measured.
     */
    private final HashMap<Integer, Float> sliding = new HashMap<>();
    private final HashMap<Integer, Float> belonged = new HashMap<>();
    private boolean ticking;
    /**
     * Rows a drag gave back that are not drawn yet: the rows that closed over their places are
     * still sliding off them, and drawn at once they stood on top of those.
     */
    private @Nullable BitSet returning;
    /** Where the rows were drawn before they changed under the widget, by what each shows, until the next layout. */
    private @Nullable Map<Object, Float> drawnBefore;
    private float[] offsets = new float[16];

    /**
     * @param rows       the realized rows these are the offsets of
     * @param host       the widget's answers
     * @param byIdentity whether a row is known again by being the same object, and not an equal
     *                   one: two rows of a list may show equal items
     */
    public RowSlides(Rows<?> rows, Host host, boolean byIdentity) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.host = Objects.requireNonNull(host, "host");
        this.byIdentity = byIdentity;
    }

    /**
     * @return the room opened where a drop would land: a share of the height of what is dragged,
     *         as far as is realized, or of a row's mean height; zero when no room is open
     */
    public float room() {
        if (host.roomFrom() < 0) {
            return 0;
        }
        float room = 0;
        for (int i = 0; i < rows.mountedCount(); i++) {
            int row = rows.rowAt(i);
            if (host.isLifted(row) && rows.isPlaced(row)) {
                room += rows.heightAt(i);
            }
        }
        return (room > 0 ? room : rows.meanHeight()) * ROOM_SHARE;
    }

    /**
     * How far from where the layout put it each realized row belongs, in the order they are
     * realized: up by the rows lifted out above it, down by the room opened at or above it. NaN
     * for a row that is lifted, which is not drawn. The array is reused: read it before asking
     * again.
     *
     * @return the offsets, one for each realized row
     */
    public float[] belongs() {
        int count = rows.mountedCount();
        if (offsets.length < count) {
            offsets = new float[Math.max(16, count * 2)];
        }
        host.prepare();
        int from = host.roomFrom();
        float room = from < 0 ? 0 : room();
        float up = 0;
        for (int i = 0; i < count; i++) {
            int row = rows.rowAt(i);
            if (host.isLifted(row)) {
                offsets[i] = Float.NaN;
                if (rows.isPlaced(row)) {
                    up += rows.heightAt(i);
                }
            } else {
                offsets[i] = -up + (room > 0 && row >= from ? room : 0);
            }
        }
        return offsets;
    }

    /**
     * {@link #belongs} with what is left of each row's slide: where the rows are drawn now.
     *
     * @return the offsets, one for each realized row; NaN for a lifted one
     */
    public float[] drawnOffsets() {
        float[] at = belongs();
        if (!sliding.isEmpty()) {
            for (int i = 0; i < rows.mountedCount(); i++) {
                Float left = sliding.get(rows.rowAt(i));
                if (left != null && !Float.isNaN(at[i])) {
                    at[i] += left;
                }
            }
        }
        return at;
    }

    /**
     * The rows belong somewhere else than a moment ago (rows lifted or put back, room opened,
     * moved or closed): each slides there from where it is drawn now.
     */
    public void rowsBelongElsewhere() {
        float[] now = belongs();
        boolean still = limn.animation.Motion.reduced() || host.scene() == null;
        boolean any = false;
        for (int i = 0; i < rows.mountedCount(); i++) {
            Integer row = rows.rowAt(i);
            if (Float.isNaN(now[i])) {
                sliding.remove(row);
                belonged.remove(row);
                continue;
            }
            Float was = belonged.put(row, now[i]);
            if (was != null && !still) {
                float left = was + sliding.getOrDefault(row, 0f) - now[i];
                if (Math.abs(left) >= 0.5f) {
                    sliding.put(row, left);
                    any = true;
                } else {
                    sliding.remove(row);
                }
            }
        }
        host.invalidate();
        if (any) {
            slide();
        }
    }

    /**
     * Notes where every realized row belongs now, before what decides it changes: a row slides
     * from where it was drawn, and one realized since the last change (scrolled in, or placed
     * under the edge for a drag) was drawn where it belonged, which nothing else remembers. Taken
     * for a row at rest where the layout put it, such a row jumped there, over its neighbours,
     * and slid back.
     */
    public void rowsAreHere() {
        float[] now = belongs();
        for (int i = 0; i < rows.mountedCount(); i++) {
            if (!Float.isNaN(now[i])) {
                belonged.put(rows.rowAt(i), now[i]);
            }
        }
    }

    /**
     * After a layout: a row realized while the others slide comes in with them, by as much as the
     * row above it has left to go, and not at once where it belongs.
     */
    public void newRowsSlideToo() {
        if (sliding.isEmpty()) {
            return;
        }
        for (int i = 1; i < rows.mountedCount(); i++) {
            Integer row = rows.rowAt(i);
            Float above = sliding.get(rows.rowAt(i - 1));
            if (above != null && rows.isPlaced(row) && !belonged.containsKey(row) && !sliding.containsKey(row)
                    && !host.isLifted(row)) {
                sliding.put(row, above);
            }
        }
        rowsAreHere();
    }

    private void slide() {
        Scene scene = host.scene();
        if (ticking || scene == null) {
            return;
        }
        ticking = true;
        scene.addRealTimeTicker(dt -> {
            float keep = (float) Math.exp(-dt / SLIDE_SECONDS);
            sliding.replaceAll((row, left) -> left * keep);
            sliding.values().removeIf(left -> Math.abs(left) < 0.5f);
            ticking = !sliding.isEmpty();
            if (!ticking) {
                returning = null;
            }
            host.invalidate();
            return ticking;
        });
    }

    /** Every row is where it belongs, at once: the rows were renumbered, or are gone. */
    public void forget() {
        returning = null;
        sliding.clear();
        belonged.clear();
    }

    /**
     * Remembers where every row is drawn, by what it shows, before the rows change under the
     * widget: the next layout slides each from there to where it then stands.
     */
    public void slideFromHere() {
        if (limn.animation.Motion.reduced() || host.scene() == null) {
            forget();
            return;
        }
        float[] at = drawnOffsets();
        Map<Object, Float> drawn = byIdentity ? new IdentityHashMap<>() : new HashMap<>();
        for (int i = 0; i < rows.mountedCount(); i++) {
            int row = rows.rowAt(i);
            Object key = !Float.isNaN(at[i]) && rows.isPlaced(row) ? host.keyOf(row) : null;
            if (key != null) {
                drawn.put(key, host.yAt(i) + at[i]);
            }
        }
        forget();
        drawnBefore = drawn;
        // For the layout the change brings, and no later one: a drop the application refused
        // changes nothing, and what was remembered here is not where a scroll finds the rows.
        Ui.postDelayed(() -> {
            if (drawnBefore == drawn) {
                drawnBefore = null;
            }
        }, 300);
    }

    /** After a layout: the rows remembered by {@link #slideFromHere} slide to where they stand. */
    public void slideToLayout() {
        Map<Object, Float> drawn = drawnBefore;
        if (drawn == null) {
            return;
        }
        drawnBefore = null;
        boolean any = false;
        for (int i = 0; i < rows.mountedCount(); i++) {
            int row = rows.rowAt(i);
            Object key = host.keyOf(row);
            Float was = key == null ? null : drawn.get(key);
            if (was != null && Math.abs(was - host.yAt(i)) >= 0.5f) {
                sliding.put(row, was - host.yAt(i));
                any = true;
            }
        }
        if (any) {
            slide();
        }
    }

    /** @return whether {@link #slideFromHere} is waiting for the layout that follows it */
    public boolean isRemembering() {
        return drawnBefore != null;
    }

    /** @return whether any row is still on its way to where it belongs */
    public boolean isSliding() {
        return !sliding.isEmpty();
    }

    /**
     * Names the rows a drag gave back that are not to be drawn until the rows that closed over
     * their places have slid off them.
     *
     * @param rowsBack the rows, or null for none
     */
    public void setReturning(@Nullable BitSet rowsBack) {
        returning = rowsBack;
    }

    /**
     * @param row a row
     * @return whether it was given back by a drag and is not drawn yet
     */
    public boolean isReturning(int row) {
        BitSet back = returning;
        return back != null && back.get(row);
    }
}
