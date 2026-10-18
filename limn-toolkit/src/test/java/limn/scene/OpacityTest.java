package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Rect;
import limn.i18n.I18nString;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Flex;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.testing.AccessibleHarness;
import limn.testing.NoopCanvas;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link Opacity} container (ADR 071 §2.1, §2.3, §2.4, §7): what it takes, what it draws, what
 * it damages, what a watcher hears, and that it is nothing to layout and to the accessible tree. The
 * pixels are the backend's ({@code GlLayerTest}, {@code OpacityPassSeamTest}); this holds the
 * container's side of it against a canvas that records the layers it is asked to open.
 */
class OpacityTest extends SceneTestBase {

    private static final Change OPACITY = Change.of(Change.Aspect.OPACITY, Change.Origin.CODE);

    /** A focusable leaf that says when it is painted, laid out and pressed, and has a name. */
    static class Probe extends Widget<Probe> {
        final String name;
        final List<String> painted;
        int layouts;
        int presses;
        /** How often an accessible walk described it: a walk is what a change of the tree costs. */
        int described;

        Probe(String name, List<String> painted) {
            this.name = name;
            this.painted = painted;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(40, 20);
        }

        @Override
        protected void onLayout() {
            layouts++;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            painted.add(name);
            canvas.fillRect(0, 0, width(), height(), Color.BLACK);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                presses++;
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            described++;
            a.role(Accessible.Role.BUTTON);
            a.name(I18nString.literal(name));
        }

        @Override
        protected float baselineOffset() {
            return 13; // not its height, so a container that answered its own would show
        }
    }

    /** Keeps each pass's rectangle and each layer it is asked to open. */
    static final class LayerCanvas extends NoopCanvas {
        final List<Rect> passes = new ArrayList<>();
        final List<float[]> layers = new ArrayList<>();
        int clears;
        /** The blend mode in force, which this canvas keeps as a field: a paint sets it and sets it back. */
        BlendMode mode = BlendMode.NORMAL;

        LayerCanvas() {
            super(400, 300);
        }

        @Override
        public void clear(Color color) {
            clears++;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (saveCount() == 1) {
                passes.add(new Rect(x, y, w, h)); // the scene clips each pass at the first save
            }
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity) {
            layers.add(new float[] {x, y, w, h, opacity});
            super.saveLayer(x, y, w, h, opacity);
        }

        @Override
        public void setBlendMode(BlendMode blendMode) {
            mode = blendMode;
        }

        @Override
        public BlendMode blendMode() {
            return mode;
        }

        void reset() {
            passes.clear();
            layers.clear();
            clears = 0;
        }
    }

    /** Paints its children with a blend mode other than normal left in force, as a parent may. */
    static final class Multiplying extends Widget<Multiplying> {
        Multiplying(Widget<?> child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = children().get(0).measure(constraints);
            return constraints.constrain(size.width(), size.height());
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, width(), height());
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            BlendMode was = canvas.blendMode();
            canvas.setBlendMode(BlendMode.MULTIPLY);
            super.paintChildren(canvas);
            canvas.setBlendMode(was);
        }
    }

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> painted = new ArrayList<>();
    private final LayerCanvas canvas = new LayerCanvas();

    /** A scene in a window, so fades have frames to step them, on a clock this test moves. */
    private Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root, () -> now[0]);
        scene.bind(new RecordingWindow());
        scene.setPartialRendering(true);
        frame(scene);
        return scene;
    }

    /** One frame, its buffer one present old, so a pass is exactly what this frame damaged. */
    private void frame(Scene scene) {
        canvas.reset();
        painted.clear();
        scene.renderFrame(canvas, false, Float.NaN, 1);
    }

    /** What the harness heard about opacity: the pass markers a layout adds are another matter. */
    private static List<Change> opacityChanges(AccessibleHarness harness) {
        return harness.changes.stream().filter(c -> c.aspect() == Change.Aspect.OPACITY).toList();
    }

    private void advance(Scene scene, long millis) {
        now[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        frame(scene);
    }

    /** A probe in a container that fades it. */
    private Opacity faded(String name) {
        return new Opacity(new Probe(name, painted));
    }

    // ------------------------------------------------------------------ the value

    @Test
    void anOpacityIsClampedNaNIsRefusedAndTheDefaultIsOne() {
        Opacity w = faded("w");
        assertEquals(1f, w.opacity());
        assertEquals(0.5f, w.setOpacity(0.5f).opacity(), "fluent, and it keeps the value");
        assertEquals(1f, w.setOpacity(7f).opacity(), "clamped above");
        assertEquals(0f, w.setOpacity(-1f).opacity(), "clamped below");
        assertThrows(IllegalArgumentException.class, () -> w.setOpacity(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> w.fadeTo(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> w.fadeTo(0.5f, -1, null));
        assertThrows(IllegalArgumentException.class, () -> w.fadeTo(0.5f, Double.NaN, null));
        assertEquals(0f, w.opacity(), "a refused call changes nothing");
    }

    @Test
    void onlyTheUiThreadSetsIt() throws InterruptedException {
        Opacity w = faded("w");
        AtomicReference<Throwable> setting = new AtomicReference<>();
        AtomicReference<Throwable> fading = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                w.setOpacity(0.5f);
            } catch (Throwable t) {
                setting.set(t);
            }
            try {
                w.fadeTo(0.5f);
            } catch (Throwable t) {
                fading.set(t);
            }
        });
        other.start();
        other.join();
        assertInstanceOf(IllegalStateException.class, setting.get());
        assertInstanceOf(IllegalStateException.class, fading.get());
        assertEquals(1f, w.opacity());
    }

    @Test
    void aPaintMayNotMoveIt() {
        Opacity w = faded("w");
        Widget.beginPaint();
        try {
            assertThrows(IllegalStateException.class, () -> w.setOpacity(0.5f));
            assertThrows(IllegalStateException.class, () -> w.setOpacity(1f), "even to the value it has");
            assertThrows(IllegalStateException.class, () -> w.fadeTo(0.5f));
        } finally {
            Widget.endPaint();
        }
        assertEquals(1f, w.opacity());
    }

    // ------------------------------------------------------------------ what is drawn

    @Test
    void oneOpensNoLayerBetweenOpensOneAndZeroPaintsNothing() {
        Probe a = new Probe("a", painted);
        Probe b = new Probe("b", painted);
        Opacity inner = new Opacity(b);
        Stack held = new Stack();
        held.add(a);
        held.add(new Padding(new Insets(30, 0, 0, 0), inner));
        Opacity group = new Opacity(held);
        Scene scene = sceneOf(new Padding(Insets.all(10), group));

        scene.requestRender();
        frame(scene);
        assertEquals(List.of("a", "b"), painted);
        assertTrue(canvas.layers.isEmpty(), "at 1 no layer is opened");

        group.setOpacity(0.5f);
        scene.requestRender();
        frame(scene);
        assertEquals(List.of("a", "b"), painted, "between, the whole subtree is painted");
        assertEquals(1, canvas.layers.size(), "into one layer");
        float[] layer = canvas.layers.get(0);
        float outset = 1 + ((Widget<?>) held).paintOutset(); // the hook is package-visible on Widget
        assertEquals(outset, 1 + ((Widget<?>) group).paintOutset(), "the container declares its child's reach");
        assertEquals(-outset, layer[0], 1e-4f, "the box grown by a pixel and the outset");
        assertEquals(group.width() + 2 * outset, layer[2], 1e-4f);
        assertEquals(0.5f, layer[4]);

        inner.setOpacity(0.5f);
        scene.requestRender();
        frame(scene);
        assertEquals(2, canvas.layers.size(), "a container at one half inside the group opens its own, nested");

        group.setOpacity(0f);
        scene.requestRender();
        frame(scene);
        assertTrue(painted.isEmpty(), "at 0 nothing under it is painted");
        assertTrue(canvas.layers.isEmpty());

        group.setOpacity(0.5f).setVisible(false);
        scene.requestRender();
        frame(scene);
        assertTrue(painted.isEmpty(), "hidden wins over any opacity");
        group.setVisible(true);
        assertEquals(0.5f, group.opacity(), "and the opacity is kept for when it is shown");
    }

    /**
     * The blend-mode rule is the container's alone: at 1 under a mode other than normal it opens a
     * layer, so its fade arriving at 1 does not jump, and a widget nobody fades opens none there, as
     * it drew before there were containers.
     */
    @Test
    void underAnotherBlendModeTheContainerIsOnePictureAtOneAndAPlainWidgetIsNot() {
        Probe plain = new Probe("plain", painted);
        Opacity group = faded("grouped");
        Row both = new Row();
        both.add(new Multiplying(plain));
        both.add(new Multiplying(group));
        Scene scene = sceneOf(both);

        scene.requestRender();
        frame(scene);
        assertEquals(List.of("plain", "grouped"), painted);
        assertEquals(1, canvas.layers.size(), "one layer, the container's, and none for the plain widget");
        assertEquals(1f, canvas.layers.get(0)[4], "at an opacity of 1");
    }

    @Test
    void atZeroTheChildIsStillLaidOutHitFocusedAndRead() {
        Probe w = new Probe("Hidden by opacity", painted);
        Opacity fade = new Opacity(w);
        AccessibleHarness harness = new AccessibleHarness(runtime, new Padding(Insets.all(20), fade));
        fade.setOpacity(0f);
        harness.frame();

        assertTrue(w.width() > 0 && w.height() > 0, "laid out");
        drive(harness.scene).click(w);
        assertEquals(1, w.presses, "hit");
        harness.focus(w);
        assertSame(w, harness.scene.focusedWidget(), "focused");
        assertNotNull(harness.node("Hidden by opacity"), "read: in the accessible tree");
    }

    // ------------------------------------------------------------------ what it is to the rest

    @Test
    void theContainerIsNothingToLayoutAndNothingToTheAccessibleTree() {
        Probe alone = new Probe("alone", painted);
        Probe wrapped = new Probe("wrapped", painted);
        Opacity fade = new Opacity(wrapped);
        Row row = new Row();
        row.crossAlignment(Flex.CrossAlignment.BASELINE);
        row.add(alone);
        row.add(fade);
        row.add(new Probe("tall", painted) {
            @Override
            protected Size onMeasure(Constraints constraints) {
                return constraints.constrain(40, 60);
            }

            @Override
            protected float baselineOffset() {
                return 50;
            }
        });
        AccessibleHarness harness = new AccessibleHarness(runtime, row);
        harness.frame();

        assertEquals(alone.width(), fade.width(), "measured as the child alone");
        assertEquals(alone.height(), fade.height());
        assertEquals(0f, wrapped.x());
        assertEquals(0f, wrapped.y(), "the child given the whole box");
        assertEquals(fade.width(), wrapped.width());
        assertEquals(alone.y(), fade.y(), "on the line's baseline as the child alone would be");

        assertEquals(4, harness.tree().nodeCount(), "a window and three buttons, and nothing for the container");
        assertEquals(harness.node("alone").parent(), harness.node("wrapped").parent(),
                "the child hoists into the container's place");
    }

    /**
     * The container declares its child's reach and not a number of its own: a repaint that lands
     * only in a card's shadow, past the container's box, reaches the card, where a container that
     * declared less was culled from that pass and left the shadow's band cleared.
     */
    @Test
    void aRepaintInTheChildsShadowAloneReachesTheChild() {
        Probe card = new Probe("card", painted) {
            @Override
            protected float paintOutset() {
                return 16; // a shadow
            }
        };
        // The padding reaches well past the card, so that only the container can be culled.
        Stack layers = new Stack();
        layers.add(new Padding(new Insets(40, 100, 100, 40), new Opacity(card)));
        // A small widget in the shadow's band only, 8 points past the card's right edge, beyond the
        // reach of a focus ring, which is what a wrapper declares by default.
        SizedBox inBand = new SizedBox(4, 4);
        layers.add(new Padding(new Insets(40, 0, 0, 88), inBand));
        Scene scene = sceneOf(layers);
        assertEquals(80, card.localToSceneX() + card.width(), "the case puts the card's edge at 80");
        assertEquals(88, inBand.localToSceneX());

        inBand.invalidate();
        frame(scene);
        assertTrue(painted.contains("card"), "the shadow's band repainted with the card in it: " + painted);
    }

    // ------------------------------------------------------------------ what is damaged

    @Test
    void aChangeDamagesTheBoxAndOutsetAndLaysNothingOut() {
        Probe p = new Probe("w", painted);
        Opacity w = new Opacity(p);
        Scene scene = sceneOf(new Padding(new Insets(40, 0, 0, 60), w));
        int layouts = p.layouts;

        // The box a pixel past on every side, as far as the canvas reaches.
        Rect damage = new Rect(w.localToSceneX() - 1, w.localToSceneY() - 1,
                Math.min(w.width() + 2, 400 - (w.localToSceneX() - 1)),
                Math.min(w.height() + 2, 300 - (w.localToSceneY() - 1)));
        assertEquals(60, damage.x() + 1, "the case puts the box on whole points");
        w.setOpacity(0.5f);
        frame(scene);
        assertEquals(List.of(damage), canvas.passes,
                "the box, a pixel past it on every side, and nothing else");
        assertEquals(0, canvas.clears, "not a whole frame");
        assertEquals(layouts, p.layouts, "no layout");

        w.setOpacity(1f); // the layer stops: the same damage
        frame(scene);
        assertEquals(List.of(damage), canvas.passes);

        w.setOpacity(1f);
        frame(scene);
        assertTrue(canvas.passes.isEmpty() && canvas.clears == 0, "the value it has damages nothing");
        assertEquals(layouts, p.layouts);
    }

    // ------------------------------------------------------------------ what a watcher hears

    @Test
    void aDirectSetAnnouncesEveryChangeAndNothingForTheSameValue() {
        Probe p = new Probe("w", painted);
        Opacity w = new Opacity(p);
        AccessibleHarness harness = new AccessibleHarness(runtime, w);
        int trees = harness.bridge.published.size();
        int described = p.described;

        w.setOpacity(0.5f);
        harness.frame();
        w.setOpacity(0.25f);
        harness.frame();
        assertEquals(List.of(OPACITY, OPACITY), opacityChanges(harness), "one change for each set that moved it");

        harness.clearObservations();
        w.setOpacity(0.25f);
        harness.frame();
        assertTrue(opacityChanges(harness).isEmpty(), "none for the value it has");
        assertEquals(trees, harness.bridge.published.size(), "and the accessible tree is never republished");
        assertTrue(harness.bridge.events.isEmpty());
        assertEquals(described, p.described, "nor even walked: nothing in it reads an opacity");
    }

    @Test
    void aFadeAnnouncesItsStartAndItsEndAndNoFrameBetween() {
        Probe p = new Probe("w", painted);
        Opacity w = new Opacity(p);
        AccessibleHarness harness = new AccessibleHarness(runtime, w);
        int trees = harness.bridge.published.size();
        int described = p.described;
        AtomicInteger arrived = new AtomicInteger();

        w.fadeTo(0f, 0.5, arrived::incrementAndGet);
        assertEquals(List.of(OPACITY), opacityChanges(harness), "the start, at once");
        harness.frame(); // a ticker's first frame is its start, and moves nothing
        List<Float> seen = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            harness.advanceTime(50, null);
            seen.add(w.opacity());
        }
        assertEquals(List.of(OPACITY, OPACITY), opacityChanges(harness), "twelve frames, two announcements");
        assertEquals(1, arrived.get(), "onArrive once");
        assertEquals(0f, w.opacity(), "at the target");
        assertTrue(seen.get(0) < 1f && seen.get(0) > 0f, "the frames between move it: " + seen);
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i) <= seen.get(i - 1), "eased one way: " + seen);
        }
        assertEquals(trees, harness.bridge.published.size(), "a fade republishes no tree");
        assertEquals(described, p.described, "and walks none, on any of its frames");
    }

    @Test
    void aNewerFadeOrASetSupersedesOneInFlightWhoseOnArriveNeverRuns() {
        Opacity w = faded("w");
        AccessibleHarness harness = new AccessibleHarness(runtime, w);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        w.fadeTo(0f, 1.0, first::incrementAndGet);
        harness.frame();
        harness.advanceTime(200, null);
        float mid = w.opacity();
        assertTrue(mid > 0f && mid < 1f, "in flight: " + mid);
        w.fadeTo(1f, 0.2, second::incrementAndGet);
        assertEquals(mid, w.opacity(), "superseded where it was");
        harness.advanceTime(1500, null);
        assertEquals(0, first.get(), "the superseded fade's onArrive never runs");
        assertEquals(1, second.get());
        assertEquals(1f, w.opacity());
        assertEquals(List.of(OPACITY, OPACITY, OPACITY, OPACITY), opacityChanges(harness),
                "the first's start and its end, the second's start and its end");

        harness.clearObservations();
        w.fadeTo(0f, 1.0, first::incrementAndGet);
        harness.frame();
        harness.advanceTime(200, null);
        w.setOpacity(1f);
        harness.advanceTime(1500, null);
        assertEquals(0, first.get(), "a set supersedes it too");
        assertEquals(1f, w.opacity());
        assertEquals(List.of(OPACITY, OPACITY, OPACITY), opacityChanges(harness),
                "the fade's start and its end, and the set's own change");
    }

    @Test
    void fadesAskedForWithNoFrameBetweenThemKeepOneTicker() {
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene scene = sceneOf(holder);
        int[] before = scene.tickerCounts();
        AtomicInteger arrived = new AtomicInteger();

        // A covered or minimized window renders no frame: a fade asked for on each update of some
        // data, all day, is asked for again and again with nothing stepping the one before.
        for (int i = 0; i < 10_000; i++) {
            w.fadeTo((i & 1) == 0 ? 0.2f : 0.8f, 0.3, arrived::incrementAndGet);
        }
        int[] during = scene.tickerCounts();
        for (int set = 0; set < during.length; set++) {
            assertEquals(before[set] + 1, during[set], "one ticker, whatever the number of fades: set " + set);
        }

        advance(scene, 16); // the window shows again: the last fade asked for is the one that runs
        advance(scene, 100);
        float mid = w.opacity();
        assertTrue(mid < 1f && mid > 0.8f, "stepping the last fade, from 1 where no frame moved it: " + mid);
        advance(scene, 400);
        assertEquals(0.8f, w.opacity(), "at the last fade's target");
        assertEquals(1, arrived.get(), "and only its onArrive ran");
        assertEquals(before[0], scene.tickerCounts()[0], "and the ticker is gone with the fade");
    }

    @Test
    void aFadeStartedInAnotherFadesOnArriveRunsOnTheSameTicker() {
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene scene = sceneOf(holder);
        int[] before = scene.tickerCounts();
        AtomicInteger back = new AtomicInteger();
        w.fadeTo(0f, 0.2, () -> w.fadeTo(1f, 0.2, back::incrementAndGet));
        advance(scene, 16);
        advance(scene, 300);
        assertEquals(0f, w.opacity(), "the first arrived");
        assertEquals(before[0] + 1, scene.tickerCounts()[0], "and the second runs on its ticker");
        advance(scene, 16); // the second's first frame is its start, as any fade's is
        advance(scene, 100);
        float mid = w.opacity();
        assertTrue(mid > 0f && mid < 1f, "the second moves: " + mid);
        advance(scene, 300);
        assertEquals(1f, w.opacity());
        assertEquals(1, back.get());
        assertEquals(before[0], scene.tickerCounts()[0]);
    }

    @Test
    void aFadeThatGoesStraightThereIsOneSet() {
        Opacity w = faded("w");
        AccessibleHarness harness = new AccessibleHarness(runtime, w);
        AtomicInteger arrived = new AtomicInteger();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            w.fadeTo(0.3f, 0.5, arrived::incrementAndGet);
            assertEquals(0.3f, w.opacity(), "less motion: straight to the target");
            assertEquals(1, arrived.get(), "onArrive at once");
            assertEquals(List.of(OPACITY), opacityChanges(harness), "one announcement, as one set");

            harness.clearObservations();
            w.fadeTo(0.3f, 0.5, arrived::incrementAndGet);
            assertTrue(opacityChanges(harness).isEmpty(), "none when the value does not change");
            assertEquals(2, arrived.get());
        }
        w.fadeTo(0.6f, 0, arrived::incrementAndGet);
        assertEquals(0.6f, w.opacity(), "no time: straight there");
        assertEquals(List.of(OPACITY), opacityChanges(harness));
        Opacity loose = faded("loose");
        loose.fadeTo(0.2f, 0.5, arrived::incrementAndGet);
        assertEquals(0.2f, loose.opacity(), "no scene to tick: straight there");
        assertEquals(4, arrived.get());
    }

    @Test
    void aFadeOnAContainerThatLeavesItsSceneSettlesThere() {
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene scene = sceneOf(holder);
        AtomicInteger arrived = new AtomicInteger();
        w.fadeTo(0f, 1.0, arrived::incrementAndGet);
        advance(scene, 100);
        holder.remove(w);
        advance(scene, 16);
        assertEquals(0f, w.opacity(), "at its target");
        assertEquals(1, arrived.get(), "and its onArrive has run");
    }

    @Test
    void aFadeOnAContainerMovedToAnotherWindowSettlesAsItLeavesAndKeepsNothingOfTheOldScene()
            throws ReflectiveOperationException {
        Opacity w = faded("w");
        Stack inA = new Stack();
        inA.add(w);
        Scene a = sceneOf(inA);
        AtomicInteger arrived = new AtomicInteger();
        List<Change> heard = new ArrayList<>();
        w.observeChanges((source, change) -> heard.add(change));
        w.fadeTo(0f, 0.3, arrived::incrementAndGet);
        advance(a, 16);
        advance(a, 100);
        float mid = w.opacity();
        assertTrue(mid > 0f && mid < 1f, "in flight: " + mid);

        // A's window never renders again (closed, covered or minimized); the container goes to B.
        inA.remove(w);
        assertEquals(0f, w.opacity(), "settled at its target as it left, not at A's next frame");
        assertEquals(1, arrived.get(), "its onArrive run then, once");
        assertEquals(2, heard.stream().filter(c -> c.aspect() == Change.Aspect.OPACITY).count(),
                "its start and its end, and nothing else");

        Stack inB = new Stack();
        inB.add(w);
        Scene b = sceneOf(inB);
        for (int i = 0; i < 30; i++) {
            advance(b, 16);
        }
        assertEquals(0f, w.opacity(), "nothing moves it in B");
        assertEquals(1, arrived.get());
        java.lang.reflect.Field held = Widget.class.getDeclaredField("opacityGroup");
        held.setAccessible(true);
        Object group = held.get(w);
        for (String field : new String[] {"fade", "ticker"}) {
            java.lang.reflect.Field f = OpacityGroup.class.getDeclaredField(field);
            f.setAccessible(true);
            assertNull(f.get(group), field + ": nothing of A is held through the container");
        }
        assertNotNull(a); // A is kept by this test, not by the container
    }

    @Test
    void aWidgetNobodyFadesHoldsNoGroup() throws ReflectiveOperationException {
        java.lang.reflect.Field held = Widget.class.getDeclaredField("opacityGroup");
        held.setAccessible(true);
        Probe plain = new Probe("plain", painted);
        assertNull(held.get(plain), "the feature costs a widget nobody fades one null reference");
        assertNotNull(held.get(new Opacity(plain)), "and is where the container is");
    }
}
