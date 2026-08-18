package limn.components;

import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A widget that throws mid-paint must not leave the canvas holding a {@code save()}.
 *
 * <p>The frame itself is contained higher up (the backend logs it and carries on), so the crash is
 * survivable by design. What is not survivable is the bookkeeping: every container in the unwind
 * that had pushed a clip leaves its {@code save()} behind, the canvas ends the frame unbalanced,
 * and the warning that fires names nothing useful because the widget that threw is long gone from
 * the stack. Worse, it fires for a bug that has nothing to do with balance, which is how a real
 * imbalance later gets ignored.
 *
 * <p>{@code Widget.paintChildren} has had the {@code try/finally} for this since it was written.
 * The overrides did not, and every one of them is a container, which is to say, exactly the
 * widgets that are in the unwind when something below them throws.
 *
 * <p><b>Not covered here:</b> the scene's own per-pass clip on the PARTIAL rendering path. It had
 * the same defect and has the same fix, but driving a component test into that branch needs damage
 * that neither covers the canvas nor settles to nothing, and every attempt from here produced a
 * full frame instead. It is fixed by inspection and it is not asserted; a test for it belongs
 * beside the other damage tests, which already know how to get there.
 */
class PaintUnwindTest extends ComponentTestBase {

    /** Throws from its own paint, the way application code in a viewport or a cell does. */
    private static final class Exploding extends Widget {

        static final class Boom extends RuntimeException {
            private static final long serialVersionUID = 1L;

            Boom() {
                super("thrown from onPaint");
            }
        }

        /** Off until armed, so a scene can be brought up cleanly and made to fail afterwards. */
        boolean armed = true;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(100, 100);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (armed) {
                throw new Boom();
            }
        }
    }

    /**
     * Renders one frame whose paint throws, and returns the canvas.
     *
     * <p>{@code renderFrame} does NOT rethrow: the scene contains a frame crash on purpose, tells
     * the {@link limn.backend.Crashes} handler and keeps the loop alive. That containment is the
     * whole reason this test exists: the application survives, so nothing forces anyone to notice
     * that the canvas did not.
     */
    private static RecordingTestCanvas paintExpectingBoom(Widget root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas);
        return canvas;
    }

    /** The case in the report: a throwing widget under a scroll view under a tabbed pane. */
    @Test
    void aScrollViewLeavesTheCanvasBalancedWhenItsContentThrows() {
        TabbedPane tabs = new TabbedPane();
        tabs.addTab("AA", new ScrollView(new Exploding()));
        assertEquals(0, paintExpectingBoom(tabs).saveDepth,
                "the scroll view and the pane each pushed a clip and neither took it back");
    }

    @Test
    void aScrollViewAloneLeavesTheCanvasBalanced() {
        assertEquals(0, paintExpectingBoom(new ScrollView(new Exploding())).saveDepth);
    }

    @Test
    void aTabbedPaneLeavesTheCanvasBalanced() {
        TabbedPane tabs = new TabbedPane();
        tabs.addTab("AA", new Exploding());
        assertEquals(0, paintExpectingBoom(tabs).saveDepth);
    }

    @Test
    void aSplitPaneLeavesTheCanvasBalanced() {
        assertEquals(0, paintExpectingBoom(
                SplitPane.horizontal(new Exploding(), new Label("right"))).saveDepth);
    }

    /**
     * A list view's cells are the application's (built by its adapter), so this is the container
     * most likely to have foreign code throwing inside its clip.
     */
    @Test
    void aListViewLeavesTheCanvasBalancedWhenACellThrows() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 3;
            }

            @Override
            public Widget rowAt(int index) {
                return new Exploding();
            }
        });
        assertEquals(0, paintExpectingBoom(list).saveDepth);
    }

    /** Saves and never restores: a plain bug, with no exception to blame it on. */
    private static final class Leaky extends Widget {

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(50, 50);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.save();
            canvas.save(); // and a second one, so a single stray restore would not hide it
        }
    }

    /** Records the depth it is painted at, to prove a sibling's leak never reached it. */
    private static final class DepthProbe extends Widget {

        int depthSeen = -1;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(50, 50);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            depthSeen = canvas.saveCount();
        }
    }

    /**
     * The net under all of the above: {@code paintWidget} puts the canvas back where it found it,
     * so a widget that forgets a restore is contained at its OWN boundary rather than handing the
     * mistake to everything painted after it.
     */
    @Test
    void aWidgetThatForgetsToRestoreIsTrimmedAtItsOwnBoundary() {
        // Two probes around the leak rather than one absolute number: a child is legitimately
        // painted inside its parent's own per-child save, so the depth a sibling sees is not zero
        // and is not this test's business. What is its business is that the two siblings see the
        // SAME depth: that the one after the leak inherited nothing from it.
        DepthProbe before = new DepthProbe();
        DepthProbe after = new DepthProbe();
        Column column = new Column();
        column.add(before);
        column.add(new Leaky());
        column.add(after);

        Scene scene = new Scene(column);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas);

        assertTrue(before.depthSeen >= 0, "both probes painted");
        assertEquals(before.depthSeen, after.depthSeen,
                "the sibling after the leak must be painted at the same depth as the one before");
        assertEquals(0, canvas.saveDepth, "and the frame ends balanced");
    }

    /** And a dialog, whose whole subtree paints inside one save for the fade. */
    @Test
    void anInSceneDialogLeavesTheCanvasBalanced() {
        Scene scene = new Scene(new Label("host"));
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        Dialog dialog = new Dialog("Title", "Message")
                .setContent(new Exploding())
                .setDisplayMode(DisplayMode.IN_SCENE);
        dialog.show(scene);
        scene.layoutPass(400, 300);

        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas);
        assertEquals(0, canvas.saveDepth);
    }
}
