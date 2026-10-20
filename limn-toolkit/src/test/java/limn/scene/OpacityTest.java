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
        // Finite, as a Transform's motions are: an infinite fade would never arrive, nor run onArrive.
        assertThrows(IllegalArgumentException.class, () -> w.fadeTo(0.5f, Double.POSITIVE_INFINITY, null));
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
        assertEquals(80, card.localToScene(0, 0).x() + card.width(), "the case puts the card's edge at 80");
        assertEquals(88, inBand.localToScene(0, 0).x());

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
        Rect damage = new Rect(w.localToScene(0, 0).x() - 1, w.localToScene(0, 0).y() - 1,
                Math.min(w.width() + 2, 400 - (w.localToScene(0, 0).x() - 1)),
                Math.min(w.height() + 2, 300 - (w.localToScene(0, 0).y() - 1)));
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

    /** What delayed tasks on the UI queue read as now: moved only by {@link #turnAfter}. */
    private final long[] queueNow = {0};

    /** Puts the UI queue's delayed tasks on {@link #queueNow}, which this test moves. */
    private void queueOnTestClock() {
        runtime.setDelayClock(() -> queueNow[0]);
    }

    /** One turn of the loop, {@code millis} after the last on the queue's clock. */
    private void turnAfter(long millis) {
        queueNow[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        runtime.drain();
    }

    @Test
    void aFadeThatGoesStraightThereIsOneSetAndArrivesAfterItsSecondsOrOnTheNextTurn() {
        Opacity w = faded("w");
        AccessibleHarness harness = new AccessibleHarness(runtime, w);
        queueOnTestClock(); // after the harness, which hands the runtime its own clock
        AtomicInteger arrived = new AtomicInteger();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            w.fadeTo(0.3f, 0.5, arrived::incrementAndGet);
            assertEquals(0.3f, w.opacity(), "less motion: straight to the target");
            assertEquals(0, arrived.get(), "onArrive never inside the call");
            assertEquals(List.of(OPACITY), opacityChanges(harness), "one announcement, as one set");
            runtime.drain();
            turnAfter(499);
            assertEquals(0, arrived.get(), "not on the next turn: after the half second asked for");
            turnAfter(1);
            assertEquals(1, arrived.get(), "then");
            turnAfter(1000);
            assertEquals(1, arrived.get(), "and once");

            harness.clearObservations();
            w.fadeTo(0.3f, 0.5, arrived::incrementAndGet);
            assertTrue(opacityChanges(harness).isEmpty(), "none when the value does not change");
            turnAfter(499);
            assertEquals(1, arrived.get(), "the same value: after its seconds too");
            turnAfter(1);
            assertEquals(2, arrived.get());
        }
        w.fadeTo(0.6f, 0, arrived::incrementAndGet);
        assertEquals(0.6f, w.opacity(), "no time: straight there");
        assertEquals(List.of(OPACITY), opacityChanges(harness));
        assertEquals(2, arrived.get(), "no time: not inside the call");
        runtime.drain();
        assertEquals(3, arrived.get(), "but on the next turn");
        w.fadeTo(0.6f, 0.5, arrived::incrementAndGet);
        runtime.drain();
        assertEquals(3, arrived.get(), "the value it has, motion allowed: after its seconds");
        turnAfter(500);
        assertEquals(4, arrived.get());
        Opacity loose = faded("loose");
        loose.fadeTo(0.2f, 0.5, arrived::incrementAndGet);
        assertEquals(0.2f, loose.opacity(), "no scene to tick: straight there");
        Opacity windowless = faded("windowless");
        new Scene(windowless);
        windowless.fadeTo(0.7f, 0.5, arrived::incrementAndGet);
        assertEquals(0.7f, windowless.opacity(), "a scene with no window: straight there");
        runtime.drain();
        assertEquals(4, arrived.get(), "neither inside its call nor on the next turn");
        turnAfter(500);
        assertEquals(6, arrived.get(), "both after their seconds");
        turnAfter(500);
        assertEquals(6, arrived.get(), "each once");
    }

    /**
     * The chain an application may write, the next {@code fadeTo} in the last one's
     * {@code onArrive}, wherever the fade goes straight there. Run inside {@code fadeTo}, it
     * recursed until a {@link StackOverflowError} came out of the first call; run on the next
     * turn, it flipped the value on every frame and kept the loop from ever sleeping. After the
     * seconds asked for, it steps at the pace the application wrote and the loop sleeps between.
     */
    @Test
    void aPulseChainedInOnArriveSleepsBetweenStepsWhereverItGoesStraightThere() {
        queueOnTestClock();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            Opacity reduced = faded("reduced");
            Stack holder = new Stack();
            holder.add(reduced);
            sceneOf(holder);
            pulseOneArrivalAPeriod("less motion, in a window", reduced, 1.0, false);
        }
        pulseOneArrivalAPeriod("in no scene", faded("loose"), 1.0, false);
        Opacity windowless = faded("windowless");
        new Scene(windowless);
        assertNotNull(windowless.scene());
        pulseOneArrivalAPeriod("in a scene with no window", windowless, 1.0, false);
        Opacity still = faded("still");
        Stack other = new Stack();
        other.add(still);
        sceneOf(other);
        pulseOneArrivalAPeriod("the value it has, in a window", still, 0.6, true);
    }

    /** A chain of zero-second fades: that one asked for no time, so it runs one arrival a turn. */
    @Test
    void aChainOfZeroSecondFadesRunsOneArrivalATurn() {
        Opacity instant = faded("instant");
        Stack holder = new Stack();
        holder.add(instant);
        sceneOf(holder);
        AtomicInteger arrivals = new AtomicInteger();
        Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            arrivals.incrementAndGet();
            instant.fadeTo(instant.opacity() < 0.5f ? 1f : 0f, 0, pulse[0]);
        };
        instant.fadeTo(0f, 0, pulse[0]);
        assertEquals(0, arrivals.get(), "no onArrive inside fadeTo");
        int turns = 20_000; // past the depth at which the stack ran out, about eight thousand
        for (int turn = 1; turn <= turns; turn++) {
            runtime.drain();
            assertEquals(turn, arrivals.get(), "one arrival a turn, the next left to the next");
        }
        instant.setOpacity(0.5f);
        runtime.drain();
        assertEquals(turns, arrivals.get(), "a set supersedes the arrival left for the next turn");
    }

    /**
     * Runs the pulse for many periods: none inside a call, nothing however often the loop turns
     * within a period, the loop free to sleep until the next step, one arrival a period; then a set
     * ends it.
     */
    private void pulseOneArrivalAPeriod(String where, Opacity w, double seconds, boolean sameValue) {
        long period = Math.round(seconds * 1000);
        AtomicInteger arrivals = new AtomicInteger();
        Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            arrivals.incrementAndGet();
            float next = sameValue ? w.opacity() : w.opacity() < 0.5f ? 1f : 0.2f;
            w.fadeTo(next, seconds, pulse[0]);
        };
        float first = sameValue ? 1f : 0.2f;
        w.fadeTo(first, seconds, pulse[0]);
        assertEquals(0, arrivals.get(), where + ": no onArrive inside fadeTo");
        assertEquals(first, w.opacity(), where + ": though the value is there at once");
        int steps = 200;
        for (int step = 1; step <= steps; step++) {
            for (int spin = 0; spin < 50; spin++) {
                runtime.drain(); // the loop woken by something else, as often as it likes
            }
            assertEquals(step - 1, arrivals.get(), where + ": nothing before the period is over");
            assertEquals(TimeUnit.MILLISECONDS.toNanos(period), runtime.nanosUntilNextDeadline(),
                    where + ": the loop may sleep until the next step");
            turnAfter(period - 1);
            assertEquals(step - 1, arrivals.get(), where + ": not a millisecond early");
            turnAfter(1);
            assertEquals(step, arrivals.get(), where + ": one arrival a period");
        }
        assertEquals(first, w.opacity(), where + ": an even number of arrivals, back where it started");
        w.setOpacity(0.5f);
        turnAfter(period);
        assertEquals(steps, arrivals.get(), where + ": a set supersedes the arrival waiting for its seconds");
    }

    @Test
    void aStraightThereFadeSupersededBeforeTheNextTurnNeverRunsItsOnArrive() {
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene scene = sceneOf(holder);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        w.fadeTo(0.4f, 0, first::incrementAndGet);
        w.fadeTo(0.6f, 0, second::incrementAndGet);
        runtime.drain();
        runtime.drain();
        assertEquals(0, first.get(), "a newer fade the same turn: the first's onArrive never runs");
        assertEquals(1, second.get(), "the newer one's once");

        w.fadeTo(0.2f, 0, first::incrementAndGet);
        w.setOpacity(1f);
        runtime.drain();
        assertEquals(0, first.get(), "a set supersedes it too");
        assertEquals(1f, w.opacity());

        w.fadeTo(0f, 0, first::incrementAndGet);
        w.fadeTo(0.5f, 0.3, second::incrementAndGet); // one that moves, on the scene's frames
        runtime.drain();
        assertEquals(0, first.get(), "a fade that moves supersedes it too");
        advance(scene, 16);
        advance(scene, 200);
        advance(scene, 200);
        assertEquals(0.5f, w.opacity());
        assertEquals(2, second.get(), "and arrives as a fade does, once");
        runtime.drain();
        assertEquals(0, first.get());

        AtomicInteger leaving = new AtomicInteger();
        w.fadeTo(0.1f, 0, leaving::incrementAndGet);
        holder.remove(w);
        assertEquals(1, leaving.get(), "leaving the scene runs an arrival waiting for the next turn, as one in flight");
        runtime.drain();
        runtime.drain();
        assertEquals(1, leaving.get(), "and the next turn does not run it again");
    }

    /**
     * A container that leaves its scene runs the {@code onArrive} of its fade then, whether the
     * fade was moving or had gone straight there and waited for its seconds: the same code ends at
     * the same moment with motion and under less motion, as a window's fade ends as it closes.
     */
    @Test
    void aStraightThereFadeWaitingForItsSecondsArrivesAsItsContainerLeaves() {
        queueOnTestClock();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            Opacity w = faded("w");
            Stack holder = new Stack();
            holder.add(w);
            sceneOf(holder);
            AtomicInteger arrived = new AtomicInteger();
            w.fadeTo(0f, 2.0, arrived::incrementAndGet);
            turnAfter(500);
            assertEquals(0, arrived.get(), "waiting its two seconds");
            holder.remove(w);
            assertEquals(1, arrived.get(), "run as the container leaves, as a fade in flight's is");
            turnAfter(2000);
            assertEquals(1, arrived.get(), "and not again when its time comes");
        }
    }

    @Test
    void aStraightThereFadeWaitingForItsSecondsIsSupersededAsOneInFlightIs() {
        queueOnTestClock();
        Opacity w = faded("w");
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            w.fadeTo(0.2f, 0.5, first::incrementAndGet);
            turnAfter(300);
            w.fadeTo(0.8f, 0.5, second::incrementAndGet); // a newer one, waiting its own half second
            turnAfter(300);
            assertEquals(0, first.get(), "the first's time came, but it was superseded");
            assertEquals(0, second.get(), "the newer one waits its own seconds, from its own call");
            turnAfter(200);
            assertEquals(1, second.get());

            w.fadeTo(0.1f, 0.5, first::incrementAndGet);
            turnAfter(100);
            w.setOpacity(0.9f);
            turnAfter(1000);
            assertEquals(0, first.get(), "a set supersedes it too");
        }
        w.fadeTo(0.4f, 0.5, first::incrementAndGet); // no scene: straight there, waiting
        w.fadeTo(0.6f, 0, second::incrementAndGet);  // a zero-second one, the next turn
        runtime.drain();
        assertEquals(2, second.get(), "the newer arrives on the next turn");
        turnAfter(1000);
        assertEquals(0, first.get(), "and the one it superseded never does");
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

    /** The ticker a container's group holds, or null: what ties it to a scene's frames. */
    private static Object heldTicker(Widget<?> container) throws ReflectiveOperationException {
        java.lang.reflect.Field held = Widget.class.getDeclaredField("opacityGroup");
        held.setAccessible(true);
        java.lang.reflect.Field ticker = OpacityGroup.class.getDeclaredField("ticker");
        ticker.setAccessible(true);
        return ticker.get(held.get(container));
    }

    /**
     * The cross-fade "fade out, swap, fade back in" on a container removed mid-fade: the fade back,
     * asked for in the onArrive its leaving runs, finds the container out of the scene it left and
     * goes straight there, where it was stepped by that scene's frames, which kept rendering for a
     * container no longer in them.
     */
    @Test
    void aFadeAskedForInTheOnArriveOfAFadeSettledByLeavingIsNotSteppedByTheSceneLeft()
            throws ReflectiveOperationException {
        queueOnTestClock();
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene a = sceneOf(holder);
        int[] before = a.tickerCounts();
        AtomicReference<Scene> seen = new AtomicReference<>(a);
        AtomicInteger back = new AtomicInteger();
        w.fadeTo(0f, 0.2, () -> {
            seen.set(w.scene());
            w.fadeTo(1f, 0.2, back::incrementAndGet);
        });
        advance(a, 16);
        advance(a, 100);
        holder.remove(w);
        assertNull(seen.get(), "its onArrive sees the container out of the scene it leaves");
        assertEquals(1f, w.opacity(), "the fade back, with no scene to step it, went straight there");
        assertNull(heldTicker(w), "and the container holds no ticker");
        advance(a, 16);
        assertEquals(before[0], a.tickerCounts()[0], "nor, past the frame that drops the one it left, the scene");
        for (int i = 0; i < 20; i++) {
            advance(a, 16);
            assertEquals(1f, w.opacity(), "nothing of the scene left moves it");
        }
        assertEquals(0, back.get());
        turnAfter(200);
        assertEquals(1, back.get(), "the fade back arrives after its seconds, as any fade straight there");
    }

    /**
     * A pulse on a container moved to another window mid-fade goes on in that window: the step its
     * leaving runs goes straight there, and the next, after that step's seconds, is stepped by the
     * new window's frames. It was stepped by the old window's, so it stood still in the new one,
     * invisible here, until the old drew again, and for good once that one closed.
     */
    @Test
    void aPulseWhoseContainerMovesToAnotherWindowMidFadeGoesOnInThatWindow() {
        queueOnTestClock();
        Opacity w = faded("w");
        Stack inA = new Stack();
        inA.add(w);
        Scene a = sceneOf(inA);
        int[] beforeA = a.tickerCounts();
        AtomicInteger arrivals = new AtomicInteger();
        Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            arrivals.incrementAndGet();
            w.fadeTo(w.opacity() < 0.5f ? 1f : 0f, 0.3, pulse[0]);
        };
        w.fadeTo(0f, 0.3, pulse[0]);
        advance(a, 16);
        advance(a, 100);

        inA.remove(w);
        Stack inB = new Stack();
        Scene b = sceneOf(inB);
        inB.add(w);
        assertEquals(1, arrivals.get(), "the fade out settled as the container left");
        assertEquals(1f, w.opacity(), "and the step back, asked for with no scene, went straight there");

        turnAfter(300); // that step's seconds: the pulse asks for the next, now in B
        assertEquals(2, arrivals.get());
        advance(b, 16);
        advance(b, 150);
        float mid = w.opacity();
        assertTrue(mid > 0f && mid < 1f, "B's frames step it: " + mid);
        advance(b, 200);
        assertEquals(0f, w.opacity(), "and bring it to its target");
        assertEquals(3, arrivals.get());
        float inFlight = w.opacity();
        advance(a, 16);
        assertEquals(inFlight, w.opacity(), "the old window, drawing again, moves nothing of it");
        assertEquals(beforeA[0], a.tickerCounts()[0], "and holds nothing of it after that frame");
    }

    /**
     * The onArrive a leaving runs may fade another container leaving with it, here the outer one of
     * two: it runs once the whole subtree is out, so that fade too goes straight there. Run in the
     * middle of the walk, it found the outer container still in the scene and left a ticker there.
     */
    @Test
    void anOnArriveThatFadesAnotherContainerLeavingWithItSeesThatOneOutToo()
            throws ReflectiveOperationException {
        Opacity inner = faded("inner");
        Opacity outer = new Opacity(inner);
        Stack holder = new Stack();
        holder.add(outer);
        Scene a = sceneOf(holder);
        int[] before = a.tickerCounts();
        inner.fadeTo(0f, 0.3, () -> outer.fadeTo(0.5f, 0.3, null));
        advance(a, 16);
        advance(a, 100);
        holder.remove(outer);
        assertEquals(0f, inner.opacity());
        assertEquals(0.5f, outer.opacity(), "the outer fade went straight there");
        assertNull(heldTicker(outer));
        advance(a, 16);
        assertEquals(before[0], a.tickerCounts()[0], "and the scene left holds nothing of either");
        assertEquals(0.5f, outer.opacity());
    }

    /** Records the crashes reported while it is installed. */
    private static final class Reported implements limn.backend.CrashHandler {
        final List<Throwable> errors = new ArrayList<>();
        final List<limn.backend.CrashPhase> phases = new ArrayList<>();

        @Override
        public boolean crashed(limn.backend.CrashPhase phase, Throwable error) {
            phases.add(phase);
            errors.add(error);
            return true;
        }
    }

    /**
     * Two containers leave together and the first one's onArrive throws: the second's still runs,
     * the subtree is out of the scene whole, and the throw is reported, as a ticker's is, and does
     * not come out of the removal. Run in the middle of the walk, the throw stopped it there, the
     * second container still in the scene the removal took it out of.
     */
    @Test
    void aThrowingOnArriveRunByALeavingStopsNeitherTheOtherArrivalsNorTheLeaving() {
        Reported reported = new Reported();
        limn.backend.Crashes.install(reported);
        try {
            Opacity first = faded("first");
            Opacity second = faded("second");
            Row both = new Row();
            both.add(first);
            both.add(second);
            Stack holder = new Stack();
            holder.add(both);
            Scene a = sceneOf(holder);
            AtomicInteger secondArrived = new AtomicInteger();
            IllegalStateException fault = new IllegalStateException("a fault of the application's (expected)");
            first.fadeTo(0f, 0.3, () -> {
                throw fault;
            });
            second.fadeTo(0f, 0.3, secondArrived::incrementAndGet);
            advance(a, 16);
            advance(a, 100);
            holder.remove(both);
            assertEquals(1, secondArrived.get(), "the other arrival ran");
            assertNull(both.scene());
            assertNull(first.scene());
            assertNull(second.scene(), "and the whole subtree left");
            assertEquals(List.of(fault), reported.errors, "the throw reported, once");
            assertEquals(List.of(limn.backend.CrashPhase.TICKER), reported.phases, "as a ticker's is");
        } finally {
            limn.backend.Crashes.uninstall(reported);
        }
    }

    /**
     * A StackOverflowError out of an onDetached as a removal walks two containers fading out: it
     * leaves the removal at once, and neither fade's onArrive runs after it, the one settled before
     * the throw included. The removal ran them in a finally, application code after an error that
     * says nothing more should run.
     */
    @Test
    void aVirtualMachineErrorDuringARemovalLeavesAtOnceAndRunsNoArrivalAfterIt() {
        List<String> ran = new ArrayList<>();
        Probe deep = new Probe("deep", painted) {
            @Override
            protected void onDetached() {
                throw new StackOverflowError("an application's recursion (expected)");
            }
        };
        Opacity before = faded("before");
        Opacity fading = new Opacity(deep);
        Row both = new Row();
        both.add(before);
        both.add(fading);
        Stack holder = new Stack();
        holder.add(both);
        Scene a = sceneOf(holder);
        before.fadeTo(0f, 0.3, () -> ran.add("before"));
        fading.fadeTo(0f, 0.3, () -> ran.add("fading"));
        advance(a, 16);
        assertThrows(StackOverflowError.class, () -> holder.remove(both));
        assertEquals(List.of(), ran, "no application code after it");
    }

    /**
     * An OutOfMemoryError out of an onArrive a removal runs: it leaves the removal, and the next
     * container's onArrive does not run. runSettled logged it and went on, and the removal
     * returned as if nothing had happened.
     */
    @Test
    void aVirtualMachineErrorInAnOnArriveRunByARemovalLeavesIt() {
        Reported reported = new Reported();
        limn.backend.Crashes.install(reported);
        try {
            List<String> ran = new ArrayList<>();
            Opacity first = faded("first");
            Opacity second = faded("second");
            Row both = new Row();
            both.add(first);
            both.add(second);
            Stack holder = new Stack();
            holder.add(both);
            Scene a = sceneOf(holder);
            first.fadeTo(0f, 0.3, () -> {
                throw new OutOfMemoryError("simulated (expected)");
            });
            second.fadeTo(0f, 0.3, () -> ran.add("second"));
            advance(a, 16);
            assertThrows(OutOfMemoryError.class, () -> holder.remove(both));
            assertEquals(List.of(), ran, "no application code after it");
            assertEquals(List.of(), reported.errors, "not contained as a ticker's throw");
        } finally {
            limn.backend.Crashes.uninstall(reported);
        }
    }

    /**
     * A focused container removed mid-fade whose onArrive throws: the removal still does all its
     * own work. The throw came out of the walk, before the removal had given the focus back and
     * laid the parent out again: the scene's focus stayed on a widget in no scene, which kept
     * receiving keys, and a gap stayed where the container had been.
     */
    @Test
    void aThrowingOnArriveDoesNotCutTheRemovalThatSettledItShort() {
        Reported reported = new Reported();
        limn.backend.Crashes.install(reported);
        try {
            Probe lead = new Probe("lead", painted);
            Probe inner = new Probe("inner", painted);
            Probe after = new Probe("after", painted);
            Opacity fading = new Opacity(inner);
            limn.scene.layout.Column column = new limn.scene.layout.Column();
            column.add(lead);
            column.add(fading);
            column.add(after);
            List<Change> heard = new ArrayList<>();
            column.observeChanges((source, change) -> heard.add(change));
            Scene a = sceneOf(column);
            a.requestFocus(inner);
            assertSame(inner, a.focusedWidget());
            float afterY = after.y();
            fading.fadeTo(0f, 0.3, () -> {
                throw new IllegalStateException("a fault of the application's (expected)");
            });
            advance(a, 16);
            column.remove(fading);
            assertEquals(1, reported.errors.size(), "the throw reported");
            assertTrue(a.focusedWidget() != inner, "the focus left the removed container: " + a.focusedWidget());
            assertTrue(heard.stream().anyMatch(c -> c.aspect() == Change.Aspect.CHILDREN),
                    "the removal announced on the parent");
            advance(a, 16);
            assertTrue(after.y() < afterY, "and the parent laid out again: " + afterY + " -> " + after.y());
        } finally {
            limn.backend.Crashes.uninstall(reported);
        }
    }

    /** A focusable probe whose loss of the focus throws, as an application's handler may. */
    private Probe throwingOnFocusLost(String name) {
        return new Probe(name, painted) {
            @Override
            protected void onFocusLost() {
                throw new IllegalStateException("a fault in onFocusLost (expected)");
            }
        };
    }

    /**
     * A focused container removed mid-fade whose child's onFocusLost throws as the removal takes
     * the focus away: the fade's onArrive still runs. The removal ran the settled callbacks after
     * its bookkeeping and not in a finally, so the throw dropped them, never run and never told.
     */
    @Test
    void aThrowingOnFocusLostDuringARemovalDoesNotDropTheSettledOnArrive() {
        Probe inner = throwingOnFocusLost("inner");
        Opacity fading = new Opacity(inner);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(fading);
        Scene a = sceneOf(column);
        a.requestFocus(inner);
        AtomicInteger arrived = new AtomicInteger();
        fading.fadeTo(0f, 0.3, arrived::incrementAndGet);
        advance(a, 16);
        try {
            column.remove(fading);
        } catch (IllegalStateException expected) {
            // the handler's own throw, which the removal does not contain: not this test's concern
        }
        assertEquals(1, arrived.get(), "the onArrive the leaving settled ran");
    }

    /**
     * An overlay moved into this scene mid-fade, the focus here held by a widget whose onFocusLost
     * throws as the push moves the focus into the overlay: the fade's onArrive still runs.
     */
    @Test
    void aThrowingOnFocusLostDuringAnOverlayPushDoesNotDropTheSettledOnArrive() {
        Probe victim = throwingOnFocusLost("victim");
        limn.scene.layout.Column content = new limn.scene.layout.Column();
        content.add(victim);
        Scene a = sceneOf(content);
        a.requestFocus(victim);
        Opacity fading = faded("overlay");
        Scene b = sceneOf(new Stack());
        b.pushOverlay(fading);
        AtomicInteger arrived = new AtomicInteger();
        fading.fadeTo(0.5f, 0.3, arrived::incrementAndGet);
        advance(b, 16);
        try {
            a.pushOverlay(fading); // from b's frames to a's, mid-fade
        } catch (IllegalStateException expected) {
            // the handler's own throw
        }
        assertEquals(1, arrived.get(), "the onArrive the move settled ran");
    }

    /**
     * A removal whose taking the focus out runs an onFocusLost that throws: the parent is still laid
     * out again and the removal still announced on it, the throw rethrown after. Both shared the
     * try the throw left, so the column kept the gap where the removed row had been.
     */
    @Test
    void aThrowingOnFocusLostDuringARemovalStillLaysTheParentOutAndAnnouncesIt() {
        Probe lead = new Probe("lead", painted);
        Probe inner = throwingOnFocusLost("inner");
        Probe after = new Probe("after", painted);
        Opacity fading = new Opacity(inner);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(lead);
        column.add(fading);
        column.add(after);
        List<Change.Aspect> heard = new ArrayList<>();
        column.observeChanges((source, change) -> heard.add(change.aspect()));
        Scene a = sceneOf(column);
        a.requestFocus(inner);
        float afterY = after.y();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> column.remove(fading));
        assertEquals("a fault in onFocusLost (expected)", thrown.getMessage(), "the handler's throw, after");
        assertTrue(heard.contains(Change.Aspect.CHILDREN), "the removal announced on the parent: " + heard);
        advance(a, 16);
        assertTrue(after.y() < afterY, "and the parent laid out again: " + afterY + " -> " + after.y());
    }

    /**
     * A dialog overlay removed while a field in it whose onFocusLost throws holds the focus, a fade
     * in flight inside it: the overlay still leaves the scene whole, told it left, the fade settled
     * and its onArrive run at the end of the removal, and the focus given back to the widget that
     * opened it, the throw rethrown after. The focus step ran first and outside the try, so the
     * overlay left the stack with its subtree never walked: still in the scene, never told it left,
     * its fade ticking in a scene that no longer drew it, and the focus given to nobody.
     */
    @Test
    void aThrowingOnFocusLostDuringAnOverlayRemovalStillTakesItOutWholeAndGivesTheFocusBack() {
        Probe opener = new Probe("opener", painted);
        limn.scene.layout.Column content = new limn.scene.layout.Column();
        content.add(opener);
        Scene a = sceneOf(content);
        a.requestFocus(opener);
        AtomicInteger detached = new AtomicInteger();
        Probe field = new Probe("field", painted) {
            @Override
            protected void onFocusLost() {
                throw new IllegalStateException("a fault in onFocusLost (expected)");
            }

            @Override
            protected void onDetached() {
                detached.incrementAndGet();
            }
        };
        Opacity fading = new Opacity(field).setOpacity(0.2f);
        limn.scene.layout.Column dialog = new limn.scene.layout.Column();
        dialog.add(fading);
        a.pushOverlay(dialog);
        advance(a, 16);
        a.requestFocus(field);
        assertSame(field, a.focusedWidget(), "sanity");
        AtomicInteger arrived = new AtomicInteger();
        fading.fadeTo(1f, 0.5, arrived::incrementAndGet);
        advance(a, 16);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> a.removeOverlay(dialog));

        assertEquals("a fault in onFocusLost (expected)", thrown.getMessage(), "the handler's throw, after");
        assertNull(a.topOverlay(), "out of the stack");
        assertNull(field.scene(), "and out of the scene");
        assertEquals(1, detached.get(), "told it left");
        assertEquals(1, arrived.get(), "the fade settled as it left, its arrival run by the removal");
        assertSame(opener, a.focusedWidget(), "the focus given back to the widget that opened it");
        advance(a, 1000);
        assertEquals(1, arrived.get(), "and nothing ticks it afterwards");
    }

    /**
     * An onArrive run by a removal that puts the container back in the same scene, under another
     * parent, and focuses it, "fade out, move, fade in" taken out mid-fade: it keeps the focus it
     * gave. The removal's own bookkeeping ran after the callback and revoked it.
     */
    @Test
    void anOnArriveThatPutsTheContainerBackAndFocusesItKeepsThatFocus() {
        Probe inner = new Probe("inner", painted);
        Opacity fading = new Opacity(inner);
        limn.scene.layout.Column first = new limn.scene.layout.Column();
        first.add(fading);
        limn.scene.layout.Column second = new limn.scene.layout.Column();
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(first);
        column.add(second);
        Scene a = sceneOf(column);
        a.requestFocus(inner);
        fading.fadeTo(0.2f, 0.3, () -> {
            first.remove(fading); // nothing: the removal below has already taken it out
            second.add(fading);
            fading.fadeTo(1f, 0.2, null);
            a.requestFocus(inner);
        });
        advance(a, 16);
        first.remove(fading);
        assertSame(second, fading.parent(), "back, under the other parent");
        assertSame(a, fading.scene());
        assertSame(inner, a.focusedWidget(), "and focused, as the onArrive asked");
        for (int i = 0; i < 30; i++) {
            advance(a, 16);
        }
        assertEquals(1f, fading.opacity(), "faded back in, on this scene's frames");
        assertSame(inner, a.focusedWidget());
    }

    /**
     * A toast whose fade in has an onArrive with a fault in it: the scene logs the throw and drops
     * the ticker, and the fade out that hides the toast later still moves and arrives. The group
     * kept the dropped ticker as its own, so every later fade on the container found "its" ticker
     * and registered none: it never moved and never arrived, and the toast stayed on screen.
     */
    @Test
    void anOnArriveThatThrowsLeavesTheContainersLaterFadesFreeToMoveAndArrive()
            throws ReflectiveOperationException {
        Opacity toast = faded("toast");
        Stack holder = new Stack();
        holder.add(toast);
        Scene scene = sceneOf(holder);
        int[] before = scene.tickerCounts();
        toast.setOpacity(0f);
        toast.fadeTo(1f, 0.1, () -> {
            throw new IllegalStateException("a fault of the application's");
        });
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(1f, toast.opacity(), "the fade in arrived, and its onArrive threw");
        assertNull(heldTicker(toast), "the container holds no ticker the scene has dropped");

        AtomicInteger hidden = new AtomicInteger();
        toast.fadeTo(0f, 0.1, () -> {
            hidden.incrementAndGet();
            toast.setVisible(false);
        });
        advance(scene, 16);
        advance(scene, 50);
        float mid = toast.opacity();
        assertTrue(mid > 0f && mid < 1f, "the fade out moves: " + mid);
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(0f, toast.opacity());
        assertEquals(1, hidden.get(), "and arrives, so the toast is hidden");
        assertEquals(before[0], scene.tickerCounts()[0], "with no ticker left behind");

        toast.setOpacity(0.3f);
        toast.fadeTo(0.9f, 0.1, null);
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(0.9f, toast.opacity(), "and so does any fade after it");
    }

    /**
     * An onArrive that asks for the next fade and then throws: the fade it asked for still moves
     * and arrives. It was left on the ticker the scene dropped for the throw, and stood still.
     */
    @Test
    void aFadeAskedForByAnOnArriveThatThenThrowsStillMovesAndArrives() {
        Opacity w = faded("w");
        Stack holder = new Stack();
        holder.add(w);
        Scene scene = sceneOf(holder);
        int[] before = scene.tickerCounts();
        AtomicInteger back = new AtomicInteger();
        w.fadeTo(0f, 0.1, () -> {
            w.fadeTo(1f, 0.1, back::incrementAndGet);
            throw new IllegalStateException("a fault of the application's, after the next step");
        });
        for (int i = 0; i < 12; i++) {
            advance(scene, 16);
        }
        float mid = w.opacity();
        assertTrue(mid > 0f && mid < 1f, "the fade back moves: " + mid);
        for (int i = 0; i < 20; i++) {
            advance(scene, 16);
        }
        assertEquals(1f, w.opacity());
        assertEquals(1, back.get(), "and arrives");
        assertEquals(before[0], scene.tickerCounts()[0]);
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
