package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segmented single-selection via click and Left/Right, and the control-size ramp.
 * Behavioural cases use the font-blind {@link #RULER} (10pt per code point), where a
 * 1-char segment is {@code 10 + 2 * segPadH} = 42pt at MEDIUM: A[0,42) B[42,84) C[84,126).
 * Those offsets hold only in a scene sized to the track, which is why the click cases use
 * one: the track takes the width its segments need and centres itself in anything larger,
 * so in a wider scene every coordinate here shifts by half the difference.
 * Ramp cases use {@link #SCALED_RULER}, since the floors that make the ramp interesting
 * only bind against font-derived metrics.
 */
class SegmentedControlTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong();

    /**
     * Records what was actually painted. The overflow defect was invisible to every
     * geometry assertion (the control measured correctly and then drew past its own edge),
     * so pinning it needs the draw calls themselves.
     */
    private static final class RecordingCanvas extends FakeCanvas {
        final List<String> texts = new java.util.ArrayList<>();
        final List<float[]> clips = new java.util.ArrayList<>();
        /** The first filled round rect of a paint is the track; {x, y, w, h}. */
        float[] track;

        RecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float radius,
                                  limn.graphics.Paint paint) {
            if (track == null) {
                track = new float[] { x, y, w, h };
            }
        }

        @Override
        public void drawText(String text, float x, float y, limn.graphics.Font font,
                             limn.graphics.Paint paint) {
            texts.add(text);
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            clips.add(new float[] { x, y, w, h });
        }
    }

    /** A/B/C at MEDIUM is 3 * 42 = 126pt of segments; 100 is narrower than that. */
    private static SegmentedControl overflowing(Scene[] out, RecordingCanvas canvas) {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
        Scene scene = new Scene(seg, new AtomicLong()::get);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        out[0] = scene;
        return seg;
    }

    @Test
    void segmentsThatDoNotFitAreNeitherPaintedNorPaintedOver() {
        // The reported defect: the control measured to the width it was given and then drew
        // every segment at its natural offset, so the ones past the edge painted over whatever
        // sat beside it: the control looked like it had no bounds at all.
        Scene[] scene = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(100, 40);
        overflowing(scene, canvas);

        assertTrue(canvas.clips.stream().anyMatch(c -> c[2] > 0 && c[2] < 100),
                "the strip should be clipped to a viewport narrower than the control");
        assertTrue(canvas.texts.size() < 3,
                "a segment wholly outside the viewport should not be drawn at all, but all "
                        + canvas.texts.size() + " were: " + canvas.texts);
        assertTrue(canvas.texts.contains("A"), "the selected segment should still be drawn");
    }

    @Test
    void aControlThatFitsIsNeitherClippedIntoAViewportNorScrolled() {
        // The other half: nothing about a control with room to spare may change.
        Scene[] scene = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(400, 40);
        overflowing(scene, canvas);

        assertEquals(3, canvas.texts.size(), "every segment is drawn when they all fit");
        // The clip is the track, not the box: no chevron zone is reserved, so the viewport is
        // the whole 126pt track sitting centred in the 400 it was handed.
        assertTrue(canvas.clips.stream().allMatch(c -> c[2] >= 126 - 0.001f),
                "a control that fits should reserve no chevron zone, but a clip was "
                        + (canvas.clips.isEmpty() ? "absent" : canvas.clips.get(0)[2]) + " wide");
    }

    @Test
    void theTrackTakesOnlyWhatTheSegmentsNeedAndSitsCentredInABiggerBox() {
        // A stretching parent (a column with STRETCH, which is the usual one) hands this
        // control the whole row. Drawing the pill track across all of it, with the segments
        // huddled at the left end, is the defect this pins.
        Scene[] scene = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(400, 40);
        overflowing(scene, canvas);

        assertEquals(126, canvas.track[2], 0.001f,
                "the track should be the 3 * 42pt the segments need, not the 400 it was given");
        assertEquals((400 - 126) / 2, canvas.track[0], 0.001f, "and centred in the box");
    }

    @Test
    void aClickOnTheMarginBesideTheTrackSelectsNothing() {
        // The margins belong to whatever is behind this control. Clamping a far-off click to
        // the nearest end segment is what a hit test written against the widget box does.
        Scene[] out = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(400, 40);
        SegmentedControl seg = overflowing(out, canvas);
        Scene scene = out[0];

        // The track is [137, 263); 380 is well past its right edge.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 380, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 380, 16);
        scene.inputBatchEnded();
        assertEquals(0, seg.selectedIndex(),
                "a click in the margin should not have selected the last segment");
    }

    @Test
    void theWheelScrollsTheStripAndAClickThenLandsOnWhatIsUnderIt() {
        // Scrolling has to move the hit test with the paint, or a click selects a segment the
        // user is not looking at, which is how a scrolled strip usually breaks.
        Scene[] out = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(100, 40);
        SegmentedControl seg = overflowing(out, canvas);
        Scene scene = out[0];

        // Chevrons take min(height, width/4) = 25 a side, so the viewport is [25, 75).
        // Unscrolled, the left edge of the viewport is segment A.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 30, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 30, 16);
        scene.inputBatchEnded();
        assertEquals(0, seg.selectedIndex(), "before scrolling, the viewport starts at A");

        scene.scrolled(0, -10, 50, 16); // wheel down/right: move the strip on
        scene.inputBatchEnded();
        scene.renderFrame(canvas);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 30, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 30, 16);
        scene.inputBatchEnded();
        assertTrue(seg.selectedIndex() > 0,
                "after scrolling, the same point should land on a later segment, not on A");
    }

    @Test
    void selectingASegmentScrollsItIntoView() {
        // Selection by any route reveals: a programmatic setSelectedIndex on a strip scrolled
        // elsewhere would otherwise move the indicator somewhere nobody can see.
        Scene[] out = new Scene[1];
        RecordingCanvas canvas = new RecordingCanvas(100, 40);
        SegmentedControl seg = overflowing(out, canvas);

        seg.setSelectedIndex(2);
        canvas.texts.clear();
        out[0].renderFrame(canvas);

        assertTrue(canvas.texts.contains("C"),
                "the selected segment should have been scrolled into view, but only "
                        + canvas.texts + " were drawn");
    }

    /**
     * The setter used to be silent and to clamp, so a screen bound to {@code onSelect} missed
     * every change it made itself, and an index computed from a lookup that missed quietly
     * selected the nearest segment instead.
     */
    @Test
    void aProgrammaticSelectionIsAnnouncedAndANonSegmentIsRefused() {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
        AtomicReference<Integer> chosen = new AtomicReference<>(-1);
        seg.onSelect(chosen::set);

        seg.setSelectedIndex(2);
        assertEquals(2, seg.selectedIndex());
        assertEquals(2, chosen.get(), "code and a click reach the listener by the same path");

        chosen.set(-1);
        assertThrows(IndexOutOfBoundsException.class, () -> seg.setSelectedIndex(3));
        assertThrows(IndexOutOfBoundsException.class, () -> seg.setSelectedIndex(-1));
        assertEquals(2, seg.selectedIndex(), "a refused index moves nothing");
        assertEquals(-1, chosen.get(), "and announces nothing");
    }

    @Test
    void clickSelectsTheSegmentAndKeysMoveIt() {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
        AtomicReference<Integer> chosen = new AtomicReference<>(-1);
        seg.onSelect(chosen::set);
        Scene scene = new Scene(seg, clock::get);
        scene.setTextRuler(RULER);
        // Exactly the 3 * 42pt the track needs, so it is not centred away from x=0 and the
        // coordinates in this class's docs are the ones a click actually lands on.
        FakeCanvas canvas = new FakeCanvas(126, 40);
        scene.renderFrame(canvas); // layout

        assertEquals(0, seg.selectedIndex(), "defaults to first");

        // Click within segment B.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 63, 16);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 63, 16);
        scene.inputBatchEnded();
        assertEquals(1, seg.selectedIndex());
        assertEquals(1, chosen.get());

        // The click focused the control; RIGHT advances to C.
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, seg.selectedIndex());
        assertEquals(2, chosen.get());

        // LEFT goes back; already-at-edge does not underflow.
        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, seg.selectedIndex());
    }

    @Test
    void everyStepTakesItsBoxFromItsRow() {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
        Scene scene = new Scene(seg, clock::get);
        scene.setTextRuler(SCALED_RULER);

        // Per segment: max(MIN_HIT_TARGET, 0.6 * body + 2 * segPadH), 22.6 at XSMALL, so the
        // 24pt floor binds there and nowhere else. Height is the controlHeight row verbatim:
        // lineHeight + 2 * padV stays under the box at all five steps, so the floor always wins.
        float[] width = { 3 * 24, 3 * 31.2f, 3 * 40.4f, 3 * 49.6f, 3 * 63.4f };
        float[] height = { 24, 28, 32, 40, 50 };
        for (ControlSize step : ControlSize.values()) {
            seg.setControlSize(step);
            Size size = seg.measure(Constraints.loose(1000, 1000));
            assertEquals(width[step.ordinal()], size.width(), 1e-3f, step + " width");
            assertEquals(height[step.ordinal()], size.height(), 1e-3f, step + " height");
        }
    }

    @Test
    void theSegmentWidthFloorReachesHitTesting() {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
        seg.setControlSize(ControlSize.XSMALL);
        Scene scene = new Scene(seg, clock::get);
        scene.setTextRuler(SCALED_RULER);
        scene.renderFrame(new FakeCanvas(72, 24)); // layout, at exactly the floored track width

        // Floored: A[0,24) B[24,48) C[48,72). Unfloored the segments would be 22.6 wide and
        // x=47 would land in C; the assertion below is exactly that discrimination.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 47, 12);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 47, 12);
        scene.inputBatchEnded();
        assertEquals(1, seg.selectedIndex());
    }

    @Test
    void theBaselineIsTheOneThePaintDrawsWith() {
        SegmentedControl seg = new SegmentedControl(List.of("A"));
        Scene scene = new Scene(seg, clock::get);
        scene.setTextRuler(SCALED_RULER);
        scene.renderFrame(new FakeCanvas(120, 32)); // the root gets the canvas box: 32pt tall

        // (32 - 16.40625) / 2 + 12.98828125: the effective padV of ADR 002 3.1 plus the ascent.
        assertEquals(20.785156f, seg.baselineOffset(), 1e-3f);
    }

    // ------------------------------------------------ the pixel-locked stroke rule

    /**
     * The toolkit's whole pen vocabulary: six distinct values, because the aliases collapse
     * onto them: HAIRLINE / CARET / IME_UNDERLINE are 1 like BORDER, CHECK_MARK /
     * IME_UNDERLINE_ACTIVE are 2 like FOCUS_RING, INDICATOR_BORDER is 1.5 like
     * FOCUS_RING_THIN. The multiset assertion pins <em>which</em> of these the control
     * paints; this set pins that a recorded width is a declared {@link Strokes} weight at all.
     */
    private static final Set<Float> LOCKED_PENS = Set.of(
            Strokes.BORDER, Strokes.FOCUS_RING_THIN, Strokes.ARROW_PEN,
            Strokes.MENU_CHECK_PEN, Strokes.FOCUS_RING, Strokes.TAB_INDICATOR);

    /** One full frame's stroke widths, sorted. Partial rendering is off, so nothing is culled. */
    private static List<Float> strokesOf(Scene host, float w, float h) {
        StrokeRecordingCanvas canvas = new StrokeRecordingCanvas(w, h);
        host.renderFrame(canvas);
        return canvas.widths();
    }

    private static void assertEveryWidthIsALockedPen(List<Float> widths, ControlSize step) {
        for (float pen : widths) {
            assertTrue(LOCKED_PENS.contains(pen),
                    step + " strokes " + pen + ", which is not a Strokes weight");
        }
    }

    @Test
    void everyStrokeIsIdenticalAtEveryStep() {
        // The pixel-lock rule, checked mechanically rather than by reading a diff. The track
        // border is this control's ONLY stroke: the selected pill and the track behind it are
        // fills, the labels are text, and MIN_HIT_TARGET reaches segment WIDTH: a layout
        // input, never a pen. So the whole multiset is one BORDER, at all five steps, and the
        // segPillRadius changes a curve rather than a weight.
        //
        // Focused it paints one more: the focus ring, at FOCUS_RING, drawn outside the track.
        // That is a second locked pen rather than an expression in the fade (unlike the four
        // text-cluster components, whose border width interpolates), so it is the same weight
        // at every step and the fade moves only its alpha.
        List<Float> mediumWidths = null;
        for (ControlSize step : ControlSize.values()) {
            SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C"));
            seg.setControlSize(step);
            Scene host = new Scene(seg, clock::get);
            host.setTextRuler(SCALED_RULER);
            Size box = seg.measure(Constraints.loose(1000, 1000));
            float w = box.width();
            float h = box.height();

            List<Float> widths = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.BORDER), widths,
                    step + " paints one unscaled track border and nothing else");
            assertEveryWidthIsALockedPen(widths, step);

            // Settled the same way the animated components are: two frames, because a
            // ticker's first frame is dt == 0 by contract and only the second (a whole second
            // later, far past any Theme duration) reaches the endpoint exactly, so the ring is
            // at full strength here rather than invisibly at 0.
            host.requestFocus(seg);
            host.renderFrame(new FakeCanvas(w, h));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            host.renderFrame(new FakeCanvas(w, h));
            List<Float> focusedWidths = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.BORDER, Strokes.FOCUS_RING), focusedWidths,
                    step + " focused paints the track border plus one focus ring");
            // The point of the whole case: the ring is a locked pen too, so it is the same
            // weight at XSMALL as at XLARGE and never rides the size ramp.
            assertEveryWidthIsALockedPen(focusedWidths, step);

            if (mediumWidths == null) {
                mediumWidths = widths;
            }
            assertEquals(mediumWidths, widths, step + " matches the first step's multiset");
        }
    }

    /**
     * The jump keys the tab strip this control is modelled on already had. They matter since
     * overflow landed: without them the last segment of a long strip is one press per segment.
     */
    @Test
    void homeAndEndJumpToTheEndsOfTheStrip() {
        SegmentedControl control = new SegmentedControl(java.util.List.of("a", "b", "c", "d"));
        Scene scene = new Scene(control);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 40);
        scene.requestFocus(control);

        scene.keyEvent(Keys.END, true, false, 0);
        scene.keyEvent(Keys.END, false, false, 0);
        scene.inputBatchEnded();
        assertEquals(3, control.selectedIndex());

        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.keyEvent(Keys.HOME, false, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, control.selectedIndex());
    }
}
