package limn.backend.lwjgl;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.Transform;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What painting a whole window allocates, with the real shaping ruler (PF-6). Measured before the
 * fix at 18–40 KB a full frame: a new key for every lookup in the shaping cache, colours mixed
 * per paint in the checkbox, and a rounded rectangle built per paint. The ceiling is what is
 * left, so a new allocation on the paint path of these widgets fails here.
 */
class FullFrameAllocationTest {

    @Test
    void aFullFrameOfAFormAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column page = form();
            Scene scene = new Scene(new Padding(Insets.all(8), page));
            assertFrameUnderCeiling(scene, store, "a full frame");
        }
    }

    /**
     * The same form fading as one picture in an Opacity container (ADR 071), with its status line in
     * a container at a half of its own: the layer a group opens, the fade's step on every frame and
     * the damage it adds allocate nothing of their own, so a fade costs the paint path no garbage.
     * The canvas is the measuring one, whose layer is a save and a multiplied opacity, which is the
     * widget's side of the path; the backend's layer is measured in pixels and time, not here.
     */
    @Test
    void aFrameOfAFadingFormAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column page = form();
            Widget<?> status = page.children().get(1);
            page.remove(status);
            page.add(new Opacity(status).setOpacity(0.5f));
            Opacity fading = new Opacity(page);
            Scene scene = new Scene(new Padding(Insets.all(8), fading));
            scene.bind(new StubWindow()); // a window, so the fade has frames to step it
            fading.fadeTo(0.2f, 20, null); // in flight for every frame measured, and moving
            assertFrameUnderCeiling(scene, store, "a frame of a fading form");
            assertTrue(fading.opacity() < 1f && fading.opacity() > 0.2f, "the fade moved and is still moving");
        }
    }

    /**
     * The same form sliding and scaled in Transform containers (ADR 072, stage 1), with its status
     * line in a scaled container of its own: the matrix loaded in place on every step of the slide,
     * the damage of where the form was and where it is, a pass reaching the moved form past its
     * parent's box, and the paint through the matrix allocate nothing of their own.
     */
    @Test
    void aFrameOfASlidingFormAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column page = form();
            Widget<?> status = page.children().get(1);
            page.remove(status);
            page.add(new Transform(status).setOrigin(0, 0).setScale(1.25f));
            Transform sliding = new Transform(page).setScale(0.9f);
            Scene scene = new Scene(new Padding(Insets.all(8), sliding));
            scene.bind(new StubWindow()); // a window, so the slide has frames to step it
            sliding.slideTo(300, 40, 20, null); // in flight for every frame measured, and moving
            assertFrameUnderCeiling(scene, store, "a frame of a sliding form");
            assertTrue(sliding.translationX() > 0f && sliding.translationX() < 300f,
                    "the slide moved and is still moving");
        }
    }

    /**
     * The same form turning in a Transform container (ADR 072, stage 2), with its status line in a
     * skewed container of its own: the step of the rotation, the matrix loaded in place and taken
     * as a value once per step for the picture, the damage of the turned bounds, and the paint into
     * the picture allocate almost nothing; the skewed line, still, takes its value once and then
     * nothing at all.
     */
    @Test
    void aFrameOfARotatingFormAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column page = form();
            Widget<?> status = page.children().get(1);
            page.remove(status);
            page.add(new Transform(status).setMatrix(limn.math.Mat4.affine2D(1, 0, 0.3f, 1, 0, 0)));
            Transform turning = new Transform(page).setRotation(2);
            Scene scene = new Scene(new Padding(Insets.all(8), turning));
            scene.bind(new StubWindow()); // a window, so the rotation has frames to step it
            turning.rotateTo(25, 20, null); // in flight for every frame measured, and moving
            assertFrameUnderCeiling(scene, store, "a frame of a rotating form");
            assertTrue(turning.rotation() > 2f && turning.rotation() < 25f,
                    "the rotation moved and is still moving");
        }
    }

    /**
     * The same form flipping over in depth in a Transform container seen in perspective (ADR 072,
     * stage 3), with its status line turned in depth, still, in a container of its own: the step of
     * the flip, the perspective composed into the matrix in place, the picture laid in perspective
     * and cut at the near plane, and the damage of the projected bounds allocate almost nothing.
     */
    @Test
    void aFrameOfAFormFlippingInDepthAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column page = form();
            Widget<?> status = page.children().get(1);
            page.remove(status);
            page.add(new Transform(status).setPerspective(600).setRotation3D(limn.math.Vec3.UNIT_Y, 20));
            Transform flipping = new Transform(page).setPerspective(1200);
            Scene scene = new Scene(new Padding(Insets.all(8), flipping));
            scene.bind(new StubWindow()); // a window, so the flip has frames to step it
            int[] edges = new int[1];
            flipping.flipTo(Transform.Flip.FORWARD, 40, () -> edges[0]++, null); // in flight throughout
            assertFrameUnderCeiling(scene, store, "a frame of a form flipping in depth");
            assertTrue(limn.scene.internal.TransformAccess.childMatrix(flipping) != null && edges[0] == 0,
                    "the flip moved and has not reached its edge");
        }
    }

    /** Forty rows of a label, a field, a check box, a bar and a button, in a scroll view. */
    private static Column form() {
        Column list = new Column();
        list.gap(4);
        for (int i = 0; i < 40; i++) {
            Row row = new Row();
            row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(new Label(String.format("Item %03d", i)));
            row.add(Expanded.of(new TextField().setText("Value " + i), 1));
            row.add(new Checkbox(Checkbox.Variant.BOX, "Enabled").setChecked(i % 2 == 0));
            ProgressBar bar = new ProgressBar();
            bar.setProgress((i % 100) / 100f);
            row.add(Expanded.of(bar, 1));
            row.add(new Button("Open"));
            list.add(row);
        }
        Column page = new Column();
        page.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(Expanded.of(new ScrollView(list), 1));
        page.add(new Label("Ready."));
        return page;
    }

    private static void assertFrameUnderCeiling(Scene scene, FontStore store, String what) {
        scene.setTextRuler(new ShapingRuler(store));
        scene.setPartialRendering(true);
        // The scalar round-rect forms drawn directly, as GlCanvas draws them: the interface's
        // default builds a RoundRect per call, which is the canvas's garbage and not the paint's.
        NoopCanvas canvas = new NoopCanvas(1100, 800) {
            @Override
            public void fillRoundRect(float x, float y, float w, float h, float r, limn.graphics.Paint p) {
            }

            @Override
            public void drawRoundRect(float x, float y, float w, float h, float r, float s,
                                      limn.graphics.Paint p) {
            }
        };
        for (int i = 0; i < 50; i++) {
            scene.requestRender();
            scene.renderFrame(canvas);
        }
        long bytes = AllocationProbe.leastAllocatedBy(() -> {
            scene.requestRender();
            scene.renderFrame(canvas);
        }, 20);
        assertTrue(bytes <= CEILING, what + " allocated " + bytes + " bytes; ceiling " + CEILING);
    }

    /** Measured at 64 bytes after the fix; the slack is for what the JIT may yet decide. */
    private static final long CEILING = 1024;
}
