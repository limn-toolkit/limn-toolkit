package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saturation/value cursor under partial rendering: the ring is centred on the exact colour,
 * so at an edge of the field half of it is drawn outside the field, and at the field's top
 * corners outside the picker as well.
 *
 * <p>Two ways that ink went stale, one per half of this file. <b>Damage</b>: moving the cursor
 * off a corner repainted the field's box and a pixel round it, so the half of the old ring
 * outside the field stayed on screen as an arc beside the new ring. <b>Culling</b>: a repaint
 * pass skips a subtree whose box, grown by what it declares it paints outside itself, misses the
 * pass, so a pass landing only where the ring hangs past some ancestor of the field skipped that
 * ancestor, never painted the field, and wiped the arc of a ring that had not moved. The culling
 * half has one case per ancestor level the ring reaches past, because declaring the reach on the
 * field and the picker alone still left the containers between them to be skipped.
 *
 * <p>Every ring box here is read off the circles the field actually drew, radius and pen
 * included, so a later change to the cursor's size is measured rather than assumed.
 */
class ColorPickerCursorDamageTest extends ComponentTestBase {

    private static final int W = 420;
    private static final int H = 640;
    /** Room round the picker, so that the ring past its top corners is inside the window. */
    private static final float INSET = 24;
    /** The pixel of analytic antialiasing any shape may feather past its own geometry. */
    private static final float FEATHER = 1;
    /** A colour well inside the field, whose ring touches no edge of it. */
    private static final Color MIDDLE = Color.rgb(0x806040);

    private ColorPicker picker;
    private Scene scene;
    private AtomicLong nanos;
    private PaintRecorder canvas;

    private void mount(Color colour) {
        picker = new ColorPicker();
        picker.setInitialColor(colour);
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(INSET), picker), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new PaintRecorder(W, H);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("never settled");
    }

    /**
     * The box the cursor's ring covers now, antialiased edge included, read off a whole frame:
     * the picker is damaged whole first, because a partial pass could cull the very widget whose
     * ring is being measured.
     */
    private Rect ringBox() {
        picker.invalidate();
        nanos.addAndGet(16_000_000L);
        canvas.reset();
        scene.renderFrame(canvas);
        Widget<?> field = picker.saturationValueField();
        Rect box = null;
        for (Ring ring : canvas.rings) {
            if (ring.cx() >= field.localToSceneX() - 1e-3f
                    && ring.cx() <= field.localToSceneX() + field.width() + 1e-3f
                    && ring.cy() >= field.localToSceneY() - 1e-3f
                    && ring.cy() <= field.localToSceneY() + field.height() + 1e-3f) {
                float reach = ring.radius() + ring.pen() / 2 + FEATHER;
                Rect one = new Rect(ring.cx() - reach, ring.cy() - reach, 2 * reach, 2 * reach);
                box = box == null ? one : box.union(one);
            }
        }
        assertNotNull(box, "the field painted no cursor");
        settle();
        return box;
    }

    private static Rect sceneBox(Widget<?> w) {
        return new Rect(w.localToSceneX(), w.localToSceneY(), w.width(), w.height());
    }

    // ------------------------------------------------------------------ damage

    /**
     * Moves the cursor from {@code corner} to the middle of the field and holds that every point
     * of the old ring's box was inside some repaint pass of the frames the move caused.
     */
    private void assertTheOldRingIsRepaintedWhenTheCursorLeaves(Color corner) {
        mount(corner);
        Rect old = ringBox();
        Rect field = sceneBox(picker.saturationValueField());
        assertFalse(field.contains(old.x(), old.y()) && field.contains(old.right(), old.bottom()),
                "the fixture puts the ring across an edge of the field, or it proves nothing");

        List<Rect> passes = new ArrayList<>();
        boolean whole = false;
        picker.setColor(MIDDLE);
        for (int i = 0; i < 60; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            whole |= canvas.cleared;
            passes.addAll(canvas.passes);
        }
        assertFalse(whole, "a whole frame would repaint the arc by accident and measure nothing");
        // Sampled on a half-point grid rather than asked of one pass, because damage is a list of
        // rectangles and nothing promises the old ring lands whole in any single one of them.
        for (float y = old.y() + 0.25f; y < old.bottom(); y += 0.5f) {
            for (float x = old.x() + 0.25f; x < old.right(); x += 0.5f) {
                boolean covered = false;
                for (Rect pass : passes) {
                    if (pass.contains(x, y)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    throw new AssertionError("the ring the cursor left at " + corner + " stays on "
                            + "screen at " + x + "," + y + ": no pass of " + passes
                            + " repainted its box " + old);
                }
            }
        }
    }

    @Test
    void leavingTheWhiteCornerRepaintsTheWholeOldRing() {
        assertTheOldRingIsRepaintedWhenTheCursorLeaves(Color.WHITE); // top left
    }

    @Test
    void leavingThePureHueCornerRepaintsTheWholeOldRing() {
        assertTheOldRingIsRepaintedWhenTheCursorLeaves(Color.rgb(0xFF0000)); // top right
    }

    @Test
    void leavingTheBlackEdgeRepaintsTheWholeOldRing() {
        assertTheOldRingIsRepaintedWhenTheCursorLeaves(Color.BLACK); // bottom left
    }

    // ----------------------------------------------------------------- culling

    /**
     * Parks the cursor at {@code colour}, repaints only {@code band} and holds that the ring was
     * drawn in that pass. {@code band} is chosen to meet the ring's box and to miss the box of one
     * of the field's ancestors, which is what let the pass skip that ancestor.
     */
    private void assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color colour, Band band) {
        mount(colour);
        Rect ring = ringBox();
        Rect field = sceneBox(picker.saturationValueField());
        Rect region = band.of(field);
        Rect met = ring.intersect(region);
        assertTrue(met.width() > 0 && met.height() > 0, "the band must meet the ring's box");
        Rect near = field.inflate(FEATHER);
        Rect off = near.intersect(region);
        assertFalse(off.width() > 0 && off.height() > 0,
                "the band must miss the field's own box, or the field is painted for that reason");

        scene.damage(region);
        nanos.addAndGet(16_000_000L);
        canvas.reset();
        scene.renderFrame(canvas);
        assertFalse(canvas.cleared, "a whole frame paints everything and tests no culling");
        assertFalse(canvas.passes.isEmpty(), "the damage produced no pass");
        boolean drawn = false;
        for (Ring each : canvas.rings) {
            if (ring.contains(each.cx(), each.cy())) {
                drawn = true;
                break;
            }
        }
        assertTrue(drawn, "a pass over " + region + " wiped the part of the ring " + ring
                + " inside it and did not draw it again");
    }

    /** A rectangle placed against the field's box in scene coordinates. */
    @FunctionalInterface
    private interface Band {
        Rect of(Rect field);
    }

    /**
     * Above the top-left corner, outside the picker altogether: the picker and the column filling
     * it are both skipped unless they declare the reach.
     */
    @Test
    void aPassAboveThePickerStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.WHITE,
                f -> new Rect(f.x() - 4, f.y() - 7, 8, 4));
    }

    /**
     * Below the bottom-left corner, inside the picker, in the gap above the swatch row: the
     * picker's column holds it, and the row holding the field and the hue ramp does not.
     */
    @Test
    void aPassUnderTheFieldStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.BLACK,
                f -> new Rect(f.x() + 1, f.bottom() + 3, 4, 3));
    }

    /**
     * Right of the top-right corner, in the gap before the hue ramp: the row holds it, and
     * nothing but the field's own box stood between the pass and the field.
     */
    @Test
    void aPassBesideTheFieldStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.rgb(0xFF0000),
                f -> new Rect(f.right() + 2, f.y() + 2, 4, 4));
    }

    /*
     * The same three bands, farther out: six points past the field, where the ring still reaches
     * and the focus ring's three points, which every layout declares unless it is told otherwise,
     * do not. Each layout between the field and the picker has to declare the cursor's own reach
     * for these, the field's expanded share and the box giving its row a height included.
     */

    /** Six points above the top-left corner, outside the picker. */
    @Test
    void aPassFarAboveThePickerStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.WHITE,
                f -> new Rect(f.x() - 2, f.y() - 8, 4, 2));
    }

    /** Six points below the bottom-left corner, in the gap above the swatch row. */
    @Test
    void aPassFarUnderTheFieldStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.BLACK,
                f -> new Rect(f.x() + 1, f.bottom() + 6, 4, 2));
    }

    /** Six points right of the top-right corner, across the gap and onto the hue ramp. */
    @Test
    void aPassFarBesideTheFieldStillDrawsTheRingHangingIntoIt() {
        assertTheRingIsRedrawnWhenOnlyThisIsRepainted(Color.rgb(0xFF0000),
                f -> new Rect(f.right() + 6, f.y() + 2, 2, 4));
    }

    // ------------------------------------------------------------------ canvas

    private record Ring(float cx, float cy, float radius, float pen) {
    }

    /**
     * Records the scene's own repaint passes (the clips pushed at the first save of a frame, where
     * the scene applies damage) and every circle stroked, in scene coordinates by following the
     * translations the paint walk pushes.
     */
    private static final class PaintRecorder extends FakeCanvas {

        final List<Rect> passes = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        boolean cleared;
        private int paints;
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float tx;
        private float ty;

        PaintRecorder(float width, float height) {
            super(width, height);
        }

        void reset() {
            passes.clear();
            rings.clear();
            cleared = false;
            paints = 0;
        }

        boolean nothingPainted() {
            return !cleared && passes.isEmpty() && paints == 0;
        }

        @Override
        public void clear(Color color) {
            cleared = true;
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty});
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] outer = saved.pop();
                tx = outer[0];
                ty = outer[1];
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(tx + x, ty + y, w, h));
            }
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            paints++;
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
            paints++;
            rings.add(new Ring(tx + cx, ty + cy, radius, strokeWidth));
        }
    }
}
