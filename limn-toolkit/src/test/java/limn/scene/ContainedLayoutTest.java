package limn.scene;

import limn.graphics.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Widget#markNeedsContainedLayout()}: the one way a layout can happen without the frame
 * being a full repaint, and the four ways it refuses.
 *
 * <p>The rule it carves out of is a structural one: a layout pass may move any widget without
 * that widget invalidating where it used to be, so a layout frame damages everything. What buys
 * the exception is that both halves of that sentence are checked rather than trusted: the widget
 * clips its children, so nothing it moved can be outside the rectangle damaged, and it
 * re-measures to the same size, so its parent's placement of it is still true.
 *
 * <p><b>Every refusal escalates to a full pass rather than to a wrong frame.</b> That is the
 * property worth protecting: getting this wrong should cost a repaint, never a stale pixel.
 */
class ContainedLayoutTest extends SceneTestBase {

    /** A box of a fixed size that clips, and records every time it lays out. */
    private static class Box extends Widget {
        final List<String> layouts;
        final String name;
        float wanted;
        boolean clip = true;

        Box(List<String> layouts, String name, float wanted) {
            this.layouts = layouts;
            this.name = name;
            this.wanted = wanted;
        }

        @Override
        protected boolean clipsChildren() {
            return clip;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(wanted, wanted);
        }

        @Override
        protected void onLayout() {
            layouts.add(name);
        }
    }

    /**
     * Places its child at 20,20 at whatever size the child asks for. A {@code Padding} cannot
     * stand in here: it hands the child TIGHT constraints, so the child's measure can never come
     * out different and the escalation this file is about would be untestable.
     */
    private static final class Host extends Widget {
        private final Widget child;

        Host(Widget child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            child.measure(Constraints.loose(constraints.maxWidth(), constraints.maxHeight()));
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            Size size = child.measure(Constraints.loose(width(), height()));
            child.layoutBox(20, 20, size.width(), size.height());
        }
    }

    private final List<String> layouts = new ArrayList<>();

    private PartialRenderingTest.RecordingCanvas settle(Scene scene, Widget... unused) {
        PartialRenderingTest.RecordingCanvas canvas =
                new PartialRenderingTest.RecordingCanvas(200, 200);
        for (int i = 0; i < 8; i++) {
            canvas.reset();
            scene.renderFrame(canvas);
        }
        return canvas;
    }

    @Test
    void aContainedLayoutIsAPartialFrameClippedToTheWidget() {
        Box box = new Box(layouts, "box", 60);
        Scene scene = new Scene(new Host(box));
        scene.setPartialRendering(true);
        PartialRenderingTest.RecordingCanvas canvas = settle(scene);

        canvas.reset();
        layouts.clear();
        box.markNeedsContainedLayout();
        scene.renderFrame(canvas);

        assertFalse(canvas.log.contains("clear"), "a contained layout must not clear the frame");
        assertTrue(canvas.lastClip != null, "it must have clipped to something");
        Rect clip = canvas.clips.get(0);
        // The box sits at 20,20 and is 60x60; the pass clip carries a 1px anti-aliasing outset.
        assertTrue(clip.x() >= 18 && clip.y() >= 18 && clip.width() <= 64 && clip.height() <= 64,
                "damage must be the widget's box, was " + clip);
        assertEquals(List.of("box"), layouts, "only the widget itself laid out again");
    }

    @Test
    void aSizeChangeEscalatesToAFullPass() {
        Box box = new Box(layouts, "box", 60);
        Scene scene = new Scene(new Host(box));
        scene.setPartialRendering(true);
        PartialRenderingTest.RecordingCanvas canvas = settle(scene);

        canvas.reset();
        box.wanted = 90; // the parent placed it at 60: its layout is now stale
        box.markNeedsContainedLayout();
        scene.renderFrame(canvas);

        assertTrue(canvas.log.contains("clear"),
                "a widget whose size moved must fall back to a full frame");
        assertEquals(90, box.width(), 0.001f, "and the full pass must have applied the new size");
    }

    @Test
    void aWidgetThatDoesNotClipItsChildrenEscalates() {
        Box box = new Box(layouts, "box", 60);
        box.clip = false;
        Scene scene = new Scene(new Host(box));
        scene.setPartialRendering(true);
        PartialRenderingTest.RecordingCanvas canvas = settle(scene);

        canvas.reset();
        box.markNeedsContainedLayout();
        scene.renderFrame(canvas);

        assertTrue(canvas.log.contains("clear"),
                "without a clip a child can paint outside the damaged rect, so the frame is full");
    }

    @Test
    void aWidgetOutsideTheSceneEscalatesInsteadOfBeingLaidOut() {
        Box box = new Box(layouts, "box", 60);
        Host root = new Host(box);
        Scene scene = new Scene(root);
        scene.setPartialRendering(true);
        PartialRenderingTest.RecordingCanvas canvas = settle(scene);

        box.markNeedsContainedLayout();
        root.remove(box); // detached between the request and the pass
        canvas.reset();
        scene.renderFrame(canvas);

        assertTrue(canvas.log.contains("clear"), "the removal alone already makes this frame full");
    }

    /**
     * Adding and removing children is what a contained pass is FOR (a virtualised list mounts
     * and recycles rows as it scrolls), and both go through {@code markNeedsLayout}. Without the
     * suppression the pass would schedule the very full frame it exists to avoid.
     */
    @Test
    void mountingAndRecyclingInsideThePassDoesNotEscalate() {
        List<Widget> pool = new ArrayList<>();
        Box box = new Box(layouts, "box", 60) {
            @Override
            protected void onLayout() {
                super.onLayout();
                while (!children().isEmpty()) {
                    remove(children().get(0));
                }
                Box child = new Box(layouts, "row", 20);
                pool.add(child);
                add(child);
                child.measure(Constraints.tight(20, 20));
                child.layoutBox(0, 0, 20, 20);
            }
        };
        Scene scene = new Scene(new Host(box));
        scene.setPartialRendering(true);
        PartialRenderingTest.RecordingCanvas canvas = settle(scene);

        canvas.reset();
        box.markNeedsContainedLayout();
        scene.renderFrame(canvas);

        assertFalse(canvas.log.contains("clear"),
                "a row mounted inside the pass must not turn it into a full frame");
        assertTrue(pool.size() >= 1, "the pass really did mount a row");
    }

    /**
     * {@code layoutPass} is the entry point a headless test or an embedder calls instead of
     * rendering, and it has to owe the same debt: without this a list that scrolled would sit on
     * stale rows until something unrelated dirtied the layout.
     */
    @Test
    void layoutPassAloneRunsTheContainedRequest() {
        Box box = new Box(layouts, "box", 60);
        Scene scene = new Scene(new Host(box));
        scene.layoutPass(200, 200);

        layouts.clear();
        box.markNeedsContainedLayout();
        scene.layoutPass(200, 200); // same size, nothing globally dirty

        assertEquals(List.of("box"), layouts, "the contained request was still owed a pass");
    }
}
