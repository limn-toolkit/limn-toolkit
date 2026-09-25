package limn.scene;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Rect;
import limn.scene.layout.Column;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Partial rendering (Phase A): damage accumulation from {@link Widget#invalidate()},
 * the two-frame repaint union required by double buffering, re-present reuse,
 * the full-frame fallbacks (flag off, layout, requestRender, translucent
 * background) and the damage-debug overlay's extra frame of history.
 */
class PartialRenderingTest extends SceneTestBase {

    private static final float EPS = 1e-4f;

    /** Records the frame-level calls that distinguish a full repaint from a partial one. */
    static final class RecordingCanvas extends NoopCanvas {
        final List<String> log = new ArrayList<>();
        final List<Rect> clips = new ArrayList<>();
        Rect lastClip;

        RecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void clear(Color color) {
            log.add("clear");
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            log.add("clip");
            lastClip = new Rect(x, y, w, h);
            clips.add(lastClip);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            log.add("fill");
        }

        void reset() {
            log.clear();
            clips.clear();
            lastClip = null;
        }

        boolean fullFramePainted() {
            return log.contains("clear");
        }

        boolean partialFramePainted() {
            return !log.contains("clear") && log.contains("clip");
        }

        boolean nothingPainted() {
            return log.isEmpty();
        }
    }

    /** Counts paints so tests can tell whether the tree was walked at all. */
    static final class PaintCountingBox extends SceneTestBase.FixedBox {
        int paints;

        PaintCountingBox(float w, float h) {
            super(w, h);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            paints++;
        }
    }

    private RecordingCanvas canvas = new RecordingCanvas(400, 300);
    private PaintCountingBox top;
    private PaintCountingBox bottom;
    private Scene scene;

    @org.junit.jupiter.api.BeforeEach
    void buildScene() { // after the base class installed the UI runtime
        top = new PaintCountingBox(100, 50);
        bottom = new PaintCountingBox(100, 50);
        Column column = new Column();
        column.add(top);
        column.add(bottom);
        scene = new Scene(column);
    }

    private void frame() {
        canvas.reset();
        scene.renderFrame(canvas);
    }

    /** The damage rect invalidate() records: widget scene bounds +1px, snapped and clamped. */
    private Rect expectedDamage(Widget<?> w) {
        float x = Math.max(0, (float) Math.floor(w.localToSceneX() - 1));
        float y = Math.max(0, (float) Math.floor(w.localToSceneY() - 1));
        float right = Math.min(canvas.width(), (float) Math.ceil(w.localToSceneX() + w.width() + 1));
        float bottom = Math.min(canvas.height(), (float) Math.ceil(w.localToSceneY() + w.height() + 1));
        return new Rect(x, y, right - x, bottom - y);
    }

    private void assertRectEquals(Rect expected, Rect actual) {
        assertEquals(expected.x(), actual.x(), EPS);
        assertEquals(expected.y(), actual.y(), EPS);
        assertEquals(expected.width(), actual.width(), EPS);
        assertEquals(expected.height(), actual.height(), EPS);
    }

    @Test
    void flagOffAlwaysPaintsFullFrames() {
        frame();
        assertTrue(canvas.fullFramePainted());
        bottom.invalidate();
        frame();
        assertTrue(canvas.fullFramePainted());
        assertFalse(canvas.log.contains("clip"));
    }

    @Test
    void invalidateRepaintsOnlyTheDamagedRegionAfterHistorySettles() {
        scene.setPartialRendering(true);
        frame(); // first frame: everything
        assertTrue(canvas.fullFramePainted());

        bottom.invalidate();
        frame(); // previous frame was full → still full (two-frame union)
        assertTrue(canvas.fullFramePainted());

        bottom.invalidate();
        frame(); // damage twice in a row → clipped to the widget's rect
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(bottom), canvas.lastClip);
        assertTrue(bottom.paints > 0); // the damaged widget itself always repaints
    }

    @Test
    void disjointDamageRepaintsTwoRegionsAndSkipsEverythingBetween() {
        PaintCountingBox a = new PaintCountingBox(100, 50);
        PaintCountingBox middle = new PaintCountingBox(100, 50);
        PaintCountingBox b = new PaintCountingBox(100, 50);
        Column column = new Column();
        column.gap(100); // far enough apart that merging the damage would waste area
        column.add(a);
        column.add(middle);
        column.add(b);
        scene = new Scene(column);
        canvas = new RecordingCanvas(400, 600);
        scene.setPartialRendering(true);
        frame();
        a.invalidate();
        b.invalidate();
        frame(); // still full: history holds the first frame
        int middlePaints = middle.paints;
        a.invalidate();
        b.invalidate();
        frame();
        // Two separate passes, NOT one bounding box spanning the middle.
        assertEquals(2, canvas.clips.size());
        Rect first = canvas.clips.get(0);
        Rect second = canvas.clips.get(1);
        assertTrue(first.bottom() <= second.y() || second.bottom() <= first.y(),
                "passes must be disjoint, got " + first + " and " + second);
        assertEquals(middlePaints, middle.paints, "the widget between the hot spots must not repaint");
        assertTrue(a.paints > 0 && b.paints > 0);
    }

    @Test
    void cullingSkipsSubtreesOutsideTheRepaintPass() {
        PaintCountingBox a = new PaintCountingBox(100, 50);
        PaintCountingBox b = new PaintCountingBox(100, 50);
        Column column = new Column();
        column.gap(20); // clear of the ±1px AA margin
        column.add(a);
        column.add(b);
        scene = new Scene(column);
        scene.setPartialRendering(true);
        frame();
        b.invalidate();
        frame(); // full (history)
        int aPaints = a.paints;
        b.invalidate();
        frame(); // partial: pass = b's rect → a's subtree is skipped
        assertTrue(canvas.partialFramePainted());
        assertEquals(aPaints, a.paints, "widget outside the pass must be culled");
        assertTrue(b.paints > aPaints - 1); // b repainted in the pass
    }

    @Test
    void repaintRegionUnionsThisAndPreviousFrameDamage() {
        scene.setPartialRendering(true);
        frame();
        top.invalidate();
        frame();
        // Previous frame damaged `top`; this frame damages `bottom`: the
        // repaint must cover both (the back buffer is two presents old).
        bottom.invalidate();
        frame();
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(top).union(expectedDamage(bottom)), canvas.lastClip);
    }

    @Test
    void frameWithoutAnyDamagePaintsNothing() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        frame(); // repaints the previous frame's damage into the other buffer
        assertTrue(canvas.partialFramePainted());
        int paintsBefore = bottom.paints;
        frame(); // no damage on either side of the union now
        assertTrue(canvas.nothingPainted());
        assertEquals(paintsBefore, bottom.paints);
    }

    @Test
    void rePresentRepaintsExactlyTheLastRegion() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        bottom.invalidate();
        frame();
        Rect region = canvas.lastClip;
        canvas.reset();
        scene.renderFrame(canvas, true); // backend double-present
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(region, canvas.lastClip);
    }

    @Test
    void requestRenderForcesAFullFrame() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        bottom.invalidate();
        frame();
        assertTrue(canvas.partialFramePainted());
        scene.requestRender();
        frame();
        assertTrue(canvas.fullFramePainted());
    }

    @Test
    void layoutChangeForcesAFullFrame() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        // A structural layout request is still a full frame. It used to be written with
        // setVisible, which no longer is one: VisibilityDamageTest says what a visibility change
        // repaints now (ADR 043 §9.4.4).
        top.markNeedsLayout();
        frame();
        assertTrue(canvas.fullFramePainted());
    }

    @Test
    void translucentBackgroundStillRepaintsPartially() {
        // Transparent popup windows (menus, dropdowns) must get partial frames
        // too: the pass resets its rect with clearRect (a true replace), so a
        // translucent background is no longer a full-frame fallback.
        scene.setBackground(Color.rgba(0x14181F, 0.5f));
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        bottom.invalidate();
        frame();
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(bottom), canvas.lastClip);
    }

    @Test
    void damageDebugFlashPersistsWhileFadingThenCleansBothBuffers() {
        java.util.concurrent.atomic.AtomicLong nanos =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        top = new PaintCountingBox(100, 50);
        bottom = new PaintCountingBox(100, 50);
        Column column = new Column();
        column.add(top);
        column.add(bottom);
        scene = new Scene(column, nanos::get);
        scene.setPartialRendering(true);
        scene.setDamageDebug(true);

        frame(); // first frame: full, and a whole-canvas flash is born
        nanos.addAndGet(3_000_000_000L); // let the startup flash expire
        frame();
        frame();
        frame();
        frame();
        assertTrue(canvas.nothingPainted()); // history fully drained

        bottom.invalidate();
        frame();
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(bottom), canvas.lastClip);
        assertTrue(canvas.log.contains("fill")); // background + flash wash

        nanos.addAndGet(500_000_000L); // 0.5s: flash mid-fade
        frame(); // no new damage: the fading flash alone keeps the region repainting
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(bottom), canvas.lastClip);

        nanos.addAndGet(1_000_000_000L); // past DAMAGE_FLASH_SECONDS: flash expired
        frame(); // erase pass for one buffer
        assertTrue(canvas.partialFramePainted());
        frame(); // and for the other (buffers are two presents apart)
        assertTrue(canvas.partialFramePainted());
        frame();
        assertTrue(canvas.nothingPainted()); // no residue: fade fully cleaned up
    }

    @Test
    void popupScenesInheritTheOwnerSceneFlags() {
        // The owner set against the default on both flags, so that inheriting is visible: a
        // popup that merely kept its own defaults would pass a test written the other way round.
        scene.setPartialRendering(false);
        scene.setDamageDebug(true);
        Scene popup = new Scene(new PaintCountingBox(50, 50));
        assertTrue(popup.isPartialRendering(), "a scene starts with partial rendering on (ADR 043)");
        assertFalse(popup.isDamageDebug());
        popup.inheritRenderingFlags(scene);
        assertFalse(popup.isPartialRendering(), "the owner's choice travels to its popup");
        assertTrue(popup.isDamageDebug());
    }

    /** FixedBox that reacts to hover the way real components do: by invalidating itself. */
    static final class HoverBox extends SceneTestBase.FixedBox {
        HoverBox(float w, float h) {
            super(w, h);
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            if (event.type() == limn.scene.event.MouseEvent.Type.ENTER
                    || event.type() == limn.scene.event.MouseEvent.Type.EXIT) {
                invalidate();
            }
        }
    }

    @Test
    void mouseMoveOverInertWidgetsDamagesNothing() {
        scene.setPartialRendering(true);
        frame();
        frame(); // settle history
        drive(scene).mouseMoved(50, 25); // over `top`, which does not react to hover
        drive(scene).inputBatchEnded();
        frame();
        drive(scene).mouseMoved(60, 80); // crosses into `bottom`: hover CHANGES, nobody cares
        drive(scene).inputBatchEnded();
        frame();
        frame();
        assertTrue(canvas.nothingPainted()); // no reaction → no damage → no repaint
    }

    @Test
    void hoverChangeRepaintsOnlyTheReactingWidgets() {
        HoverBox a = new HoverBox(100, 50);
        HoverBox b = new HoverBox(100, 50);
        Column column = new Column();
        column.add(a);
        column.add(b);
        scene = new Scene(column);
        scene.setPartialRendering(true);
        frame();
        drive(scene).mouseMoved(50, 25); // ENTER a
        drive(scene).inputBatchEnded();
        frame(); // full: history still holds the first frame
        drive(scene).mouseMoved(50, 80); // EXIT a + ENTER b: both invalidate
        drive(scene).inputBatchEnded();
        frame();
        drive(scene).mouseMoved(40, 85); // move within b: hover unchanged, no damage
        drive(scene).inputBatchEnded();
        frame();
        // Repaint = previous frame's damage (a ∪ b): clipped, never full.
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(expectedDamage(a).union(expectedDamage(b)), canvas.lastClip);
    }

    @Test
    void localRegionInvalidateDamagesJustThatRegion() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate(10, 20, 5, 8);
        frame();
        bottom.invalidate(10, 20, 5, 8);
        frame();
        assertTrue(canvas.partialFramePainted());
        float x = (float) Math.floor(bottom.localToSceneX() + 10 - 1);
        float y = (float) Math.floor(bottom.localToSceneY() + 20 - 1);
        assertRectEquals(new Rect(x, y, 7, 10), canvas.lastClip);
    }

    @Test
    void windowResizeForcesAFullFrame() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        canvas = new RecordingCanvas(500, 400); // the next frame arrives resized
        frame();
        assertTrue(canvas.fullFramePainted());
    }

    @Test
    void noOpFramesAreNotCountedInFrameMetrics() {
        scene.setPartialRendering(true);
        frame();
        bottom.invalidate();
        frame();
        frame(); // repaints the previous frame's damage
        long counted = scene.metrics().totalFrames();
        frame(); // backend-forced wakeup with nothing to paint
        assertTrue(canvas.nothingPainted());
        assertEquals(counted, scene.metrics().totalFrames(), "no-op frames must not count as paints");
        bottom.invalidate();
        frame();
        assertEquals(counted + 1, scene.metrics().totalFrames());
    }

    /** Viewport-like container: clips children, holding one child at a fixed offset. */
    static final class ClippingBox extends Widget<ClippingBox> {
        private final float boxW;
        private final float boxH;
        private final Widget<?> child;
        private final float childX;
        private final float childY;

        ClippingBox(float boxW, float boxH, Widget<?> child, float childX, float childY) {
            this.boxW = boxW;
            this.boxH = boxH;
            this.child = child;
            this.childX = childX;
            this.childY = childY;
            add(child);
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(boxW, boxH);
        }

        @Override
        protected void onLayout() {
            child.measure(Constraints.loose(1000, 1000));
            child.layoutBox(childX, childY, 100, 50);
        }
    }

    @Test
    void childScrolledFullyOutOfAClippingViewportDamagesNothing() {
        PaintCountingBox child = new PaintCountingBox(100, 50);
        ClippingBox viewport = new ClippingBox(100, 60, child, 0, -80); // fully above the viewport
        Column column = new Column();
        column.add(viewport);
        scene = new Scene(column);
        scene.setPartialRendering(true);
        frame();
        frame(); // settle history
        child.invalidate();
        frame();
        frame();
        assertTrue(canvas.nothingPainted(), "clipped-away damage must not repaint anything");
    }

    @Test
    void partiallyVisibleChildDamagesOnlyItsVisibleSlice() {
        PaintCountingBox child = new PaintCountingBox(100, 50);
        ClippingBox viewport = new ClippingBox(100, 60, child, 0, -30); // 20px visible
        Column column = new Column();
        column.add(viewport);
        scene = new Scene(column);
        scene.setPartialRendering(true);
        frame();
        child.invalidate();
        frame();
        child.invalidate();
        frame();
        assertTrue(canvas.partialFramePainted());
        // Child rect is 52px tall inflated; the viewport clamps it to the
        // ~20px visible slice (+AA margins at the widget and clip edges).
        assertTrue(canvas.lastClip.height() <= 24,
                "damage must clamp to the visible slice, got " + canvas.lastClip);
    }

    @Test
    void clippedAwayWidgetIsNotShowingAndItsAnimationsPause() {
        PaintCountingBox child = new PaintCountingBox(100, 50);
        ClippingBox viewport = new ClippingBox(100, 60, child, 0, -80); // fully out of view
        Column column = new Column();
        column.add(viewport);
        scene = new Scene(column);
        RecordingWindow window = new RecordingWindow();
        scene.bind(window);
        scene.setPartialRendering(true);
        frame(); // layout places the child (clipped away)
        assertFalse(child.isShowing(), "fully clipped = not on screen");

        limn.animation.Transition anim =
                new limn.animation.Transition(child).duration(60); // long-running
        anim.to(1); // registers a ticker
        frame(); // the tick sees a clipped-away owner and unregisters
        int requests = window.frameRequests;
        frame();
        frame();
        assertEquals(requests, window.frameRequests,
                "a clipped-away animation must not keep the frame loop alive");
    }

    @Test
    void partiallyVisibleWidgetIsStillShowing() {
        PaintCountingBox child = new PaintCountingBox(100, 50);
        ClippingBox viewport = new ClippingBox(100, 60, child, 0, -30); // 20px visible
        Column column = new Column();
        column.add(viewport);
        scene = new Scene(column);
        frame();
        assertTrue(child.isShowing());
    }

    @Test
    void hiddenBranchDamagesNothing() {
        scene.setPartialRendering(true);
        frame();
        top.setVisible(false);
        frame();
        frame(); // settle
        top.invalidate(); // hidden widget: paints nothing, so nothing changed
        frame();
        frame();
        assertTrue(canvas.nothingPainted());
    }

    @Test
    void explicitDamageRectWidensTheRepaintRegion() {
        scene.setPartialRendering(true);
        frame();
        Rect shadow = new Rect(0, 0, 250, 200);
        scene.damage(shadow);
        frame();
        scene.damage(shadow);
        frame();
        assertTrue(canvas.partialFramePainted());
        assertRectEquals(shadow, canvas.lastClip);
    }

    /**
     * ADR 046 §5: the scene repaints across as many frames as the backend says its back buffer is
     * old, instead of assuming two. 0 is a backend that does not know — the whole window; 1 a buffer
     * that keeps the last frame — only what changed now; 3 triple buffering — two frames of history.
     */
    @Test
    void theBufferAgeDecidesHowManyFramesOfDamageARepaintCovers() {
        scene.setPartialRendering(true);
        frame();
        frame(); // history settled: nothing damaged since

        top.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 1);
        assertTrue(canvas.partialFramePainted(), canvas.log.toString());
        assertEquals(1, canvas.clips.size(), "one frame of damage: " + canvas.clips);
        assertRectEquals(expectedDamage(top), canvas.lastClip);

        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 1); // a frame that damaged nothing
        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 3);
        assertTrue(canvas.partialFramePainted(), canvas.log.toString());
        // top's damage is two frames old now: only a history of two frames still holds it
        assertTrue(coveredBy(canvas.clips, expectedDamage(top))
                        && coveredBy(canvas.clips, expectedDamage(bottom)),
                "top's damage two frames ago is repainted with bottom's now: " + canvas.clips);

        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 0);
        assertTrue(canvas.fullFramePainted(), "an unknown age repaints the whole window");
    }

    /**
     * An age older than the history the scene keeps repaints the whole window. A driver on the
     * Linux guests handed over a buffer it had not presented for twenty frames; treating that age as
     * three repainted three frames of damage over a buffer that held none of them.
     */
    @Test
    void anAgeOlderThanTheHistoryRepaintsTheWholeWindow() {
        scene.setPartialRendering(true);
        frame();
        frame(); // history settled
        for (int i = 0; i < 20; i++) {
            canvas.reset();
            scene.renderFrame(canvas, false, Float.NaN, 1);
        }
        top.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 21);
        assertTrue(canvas.fullFramePainted(), "a buffer 21 presents old: " + canvas.log);
    }

    /**
     * A re-present is a present: the age a backend gives counts it, so the history does too. With
     * three buffers, a re-present after two content frames has to repaint what both of them changed;
     * copying the last content frame's region, as double buffering allowed, left the older frame's
     * change out, and that is how the popups' rows went missing.
     */
    @Test
    void aRePresentRepaintsWhatThePresentsItsBufferMissedChanged() {
        scene.setPartialRendering(true);
        frame();
        frame(); // history settled
        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 1);
        top.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 1); // repaints top alone: a buffer that keeps its contents
        canvas.reset();
        scene.renderFrame(canvas, true, Float.NaN, 3); // the backend now hands over a buffer three presents old
        assertTrue(canvas.partialFramePainted(), canvas.log.toString());
        assertTrue(coveredBy(canvas.clips, expectedDamage(top)) && coveredBy(canvas.clips, expectedDamage(bottom)),
                "both content frames' changes: " + canvas.clips);
    }

    /**
     * And the history counts re-presents as presents that changed nothing: with double buffering a
     * content frame after a re-present repaints only its own damage and the re-present's (none),
     * not the content frame before the re-present, whose buffer already caught up.
     */
    @Test
    void aRePresentCountsAsAPresentThatChangedNothing() {
        scene.setPartialRendering(true);
        frame();
        frame(); // history settled
        top.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 2);
        canvas.reset();
        scene.renderFrame(canvas, true, Float.NaN, 2); // converges the other buffer
        assertTrue(coveredBy(canvas.clips, expectedDamage(top)), canvas.clips.toString());
        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, false, Float.NaN, 2);
        assertTrue(canvas.partialFramePainted(), canvas.log.toString());
        assertEquals(1, canvas.clips.size(), canvas.clips.toString());
        assertRectEquals(expectedDamage(bottom), canvas.lastClip);
    }

    /**
     * A frame callback of an application's own forwards the backend's FrameInfo whole, and the age
     * in it reaches the repaint decision. The demo's callbacks forwarded the flag and the GPU sample
     * through an overload that assumed the age, and the kitchen drew black on Wayland, where the
     * backend said three.
     */
    @Test
    void aForwardedFrameInfoCarriesTheBufferAgeToTheRepaint() {
        scene.setPartialRendering(true);
        frame();
        frame(); // history settled
        top.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, new limn.backend.FrameInfo(400, 300, 1f, false, Float.NaN, 1));
        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, new limn.backend.FrameInfo(400, 300, 1f, false, Float.NaN, 3));
        assertTrue(coveredBy(canvas.clips, expectedDamage(top)) && coveredBy(canvas.clips, expectedDamage(bottom)),
                "age 3 from the FrameInfo repaints two presents back: " + canvas.clips);
        bottom.invalidate();
        canvas.reset();
        scene.renderFrame(canvas, new limn.backend.FrameInfo(400, 300, 1f, false, Float.NaN, 0));
        assertTrue(canvas.fullFramePainted(), "age 0 from the FrameInfo repaints the window");
    }

    private static boolean coveredBy(List<Rect> clips, Rect r) {
        for (Rect c : clips) {
            if (c.x() <= r.x() + EPS && c.y() <= r.y() + EPS
                    && c.x() + c.width() >= r.x() + r.width() - EPS
                    && c.y() + c.height() >= r.y() + r.height() - EPS) {
                return true;
            }
        }
        return false;
    }
}
