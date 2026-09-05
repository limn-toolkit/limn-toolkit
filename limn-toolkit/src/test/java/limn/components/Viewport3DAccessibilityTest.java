package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.backend.RenderStats;
import limn.graphics.Image;
import limn.graphics.ScenePixels;
import limn.render3d.Camera;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Graphics3D;
import limn.render3d.MeshData;
import limn.render3d.RenderPass;
import limn.render3d.RenderTarget;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Viewport3D} becomes in the accessible tree: one {@code CANVAS} node over the box
 * the layout gave it, with no name of its own, no verb of its own, and — on the frames that cannot
 * render — the "no GPU backend" message a sighted user is reading, carried as the description.
 *
 * <p>ADR 039 §7's row for this widget is {@code | Viewport3D | CANVAS | — | — |} with the note that
 * the tree faithfully reports a tab stop that does nothing, and read beside §13 item 12 it sounds
 * like a widget the walk handles for free. It is not. The constructor makes the widget focusable,
 * so the transparency predicate never deletes it and a role-less node would be published as an
 * unknown control while the walk named a toolkit class in an application's log; and the opposite
 * path, a viewport made unfocusable to be a display-only render surface, would be deleted by the
 * predicate and reported as a class that paints and says nothing, with the recommendation to strike
 * out the only thing on the screen. One role closes both, and two of the tests below are those two
 * paths.
 *
 * <p>The row is also written from the one paint branch that needs a GPU. The other one — the branch
 * every headless build takes, including every test here, and the branch an application on a machine
 * with no GL takes for its whole life — fills the box and centres one line of text, and under the
 * row as written a reader would be told "canvas" and nothing about the failure. That message is the
 * node's description, read from {@link Graphics3D#isAvailable()} inside the describe hook rather
 * than from a flag the paint left behind, because the publish step runs before the paint passes of
 * the same frame.
 *
 * <p>Everything below drives the viewport's public API, and Widget's, on a bound scene, and reads
 * back the tree the scene published. Nothing constructs a node.
 */
class Viewport3DAccessibilityTest extends AccessibleComponentTestBase {

    private Viewport3D viewport;

    /** Every record the walk logged while a test was running; see {@link #theWalkNeverNamesViewport3D}. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    /**
     * The walk never names this class in an application's log, after every test rather than in one.
     *
     * <p>Both warnings this widget can trip are logged at most once for the life of the virtual
     * machine, so whichever test ran first would be the only one able to catch the role going away
     * — and the two are reached by opposite routes, the focusable one through
     * {@code warnIfUnnamedRole} and the unfocusable one through {@code warnIfItPaints}, so the
     * check belongs after every test rather than beside either.
     */
    @AfterEach
    void theWalkNeverNamesViewport3D() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted pattern and the class name is in
            // getParameters()[0].
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.Viewport3D"),
                    "a render is information and this class declares a role for it, so the walk "
                            + "must never report it as unknown or as a widget that paints and says "
                            + "nothing; a warning here names a toolkit class in an application's "
                            + "log: " + subject + " " + record.getMessage());
        }
    }

    /**
     * A viewport at exactly 200&nbsp;&times;&nbsp;150, which is what makes the box assertions about
     * a number rather than about the window: a scene root is laid out tight to the window.
     */
    private void bindAViewport() {
        viewport = new Viewport3D();
        viewport.setPreferredSize(200, 150);
        Column column = new Column();
        column.add(new SizedBox(200, 150, viewport));
        bind(column);
    }

    /** @return the one canvas node in the published tree */
    private AccessibleNode canvas() {
        return node(Accessible.Role.CANVAS);
    }

    // ------------------------------------------------------------------ the shape of the tree

    /**
     * One canvas node, childless, over the widget's own rectangle.
     *
     * <p>The role is declared and the box is the walk's, and neither is cosmetic. The composite is
     * one quad over the whole box and the placeholder fills the whole box, so unlike a picture
     * there is no letterbox to argue about: the ink and the rectangle are the same thing on both
     * branches, and carving the node down to the render target's pixel count would put a
     * magnifier's cursor where the pointer does not agree. The widget holds no children and
     * declares none — {@code rayAt} inverts an arbitrary pixel, which is a coordinate transform
     * and not a set of addressable regions — so the node is a leaf.
     */
    @Test
    void publishesOneCanvasLeafOverItsOwnBox() {
        bindAViewport();

        AccessibleNode node = canvas();
        assertTrue(childrenOf(node).isEmpty(),
                "a viewport composites one quad and addresses nothing inside it"
                        + describe(tree()));

        assertEquals(viewport.localToSceneX(), node.x(), 0.01f, describe(tree()));
        assertEquals(viewport.localToSceneY(), node.y(), 0.01f, describe(tree()));
        assertEquals(viewport.width(), node.width(), 0.01f, describe(tree()));
        assertEquals(viewport.height(), node.height(), 0.01f, describe(tree()));
        assertEquals(200, node.width(), 0.01f, "and it is the widget's box, not the window's"
                + describe(tree()));
        assertEquals(150, node.height(), 0.01f, describe(tree()));

        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(node.has(Accessible.State.FOCUSABLE),
                "the constructor makes it a tab stop" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.FOCUS),
                "and the walk supplies the two verbs that go with one" + describe(tree()));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
        assertEquals(2, node.actions().actions().size(),
                "orbit, zoom and pick have no honest parameterless form, so the verb list is the "
                        + "walk's and nothing else: " + node.actions());
    }

    /**
     * The picture is in the tree because it declared a role, not because it was a tab stop.
     *
     * <p>{@code setFocusable(false)} is public and final on {@code Widget} and nothing here
     * prevents it, and a display-only render surface — the shape {@code setAnimated(false)} is
     * documented for — is exactly what an application would use it for. Before the role was
     * declared such a viewport declared nothing, held no children, was deleted by the transparency
     * predicate, and then tripped the paints-and-says-nothing warning, whose advice is
     * {@code setAccessibleIgnored(true)}: the toolkit recommending, in an application's log, that
     * the only thing on the screen be struck out. Anyone who later puts the role behind a
     * focusable test reopens all of that, and this is where it goes red.
     */
    @Test
    void theRoleSurvivesTheWidgetBeingMadeUnfocusable() {
        bindAViewport();
        viewport.setFocusable(false);
        frame();

        AccessibleNode node = canvas();
        assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSED), describe(tree()));
        assertEquals(viewport.width(), node.width(), 0.01f,
                "it keeps its box, so a reader can still find where the render is"
                        + describe(tree()));
        assertFalse(node.actions() != null && node.actions().has(Accessible.Action.FOCUS),
                "and it offers neither of the verbs focus brought with it: " + node.actions());
        assertFalse(node.actions() != null
                        && node.actions().has(Accessible.Action.SCROLL_INTO_VIEW),
                "" + node.actions());
    }

    // -------------------------------------------------------------------------------- the name

    /**
     * The class declares no name, and the application's is the one that lands.
     *
     * <p>This is a decision and not an omission: the widget holds no title, no caption and no
     * placeholder, and the failure message is a status rather than an identity. So it follows the
     * two settled precedents for a surface the toolkit cannot describe — a picture and a colour
     * chooser — and leaves all three ways a name arrives to the application and the walk. A
     * toolkit-invented generic name would shadow the application's and would be naming content
     * the toolkit has never seen.
     */
    @Test
    void noNameIsDeclaredAndTheApplicationsWins() {
        bindAViewport();
        assertEquals("", canvas().name(),
                "the widget holds no string to name itself with" + describe(tree()));
        assertEquals(Accessible.Role.CANVAS, canvas().role(), describe(tree()));

        viewport.setAccessibleName("Model preview");
        frame();

        assertEquals("Model preview", canvas().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, canvas().nameFrom(), describe(tree()));
    }

    /**
     * A tooltip becomes the name, and this is the pin for the decision above.
     *
     * <p>The walk's free default only fires while nothing else has named the node. The moment
     * anyone writes a name in this widget's hook — the failure message being the obvious
     * candidate — the default stops firing, every viewport in the toolkit's own demo that is named
     * by its tooltip goes quiet, and nothing else in the suite notices. This test is what notices.
     */
    @Test
    void theTooltipBecomesTheNameAndNotTheDescription() {
        bindAViewport();
        viewport.setTooltip("Model preview");
        frame();

        AccessibleNode node = canvas();
        assertEquals("Model preview", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, node.nameFrom(),
                "the free default reaches this class like any other" + describe(tree()));
        assertNotEquals("Model preview", node.description(),
                "and it is not said twice" + describe(tree()));
    }

    // ------------------------------------------------------------------- the missing backend

    /**
     * With no backend the node carries the message the widget is painting, as its description.
     *
     * <p>This is the branch every headless build takes and the one §7's row does not consider:
     * {@code onPaint} opens by filling the box and centring one line of {@code
     * VIEWPORT3D_NO_BACKEND}, and that line is the whole of what a sighted user has. Description
     * and not name, and not because a name here would win: the walk applies an application's name
     * and a bound caption after this hook and unconditionally, so a name written here is a
     * fallback beneath either, and only the tooltip default — the one route gated on nothing else
     * having named the node — would be displaced. The reasons that do stand are the other two: a
     * failure is a status rather than an identity, and the same control would be named
     * differently on two machines; and in the description slot it stands beside whichever of them
     * names the node, which is the right precedence, a failure outranking a hint.
     */
    @Test
    void theMissingBackendIsPublishedAsTheDescription() {
        bindAViewport();

        assertFalse(Graphics3D.isAvailable(), "the suite runs with no 3D provider installed");
        assertEquals(ComponentStrings.VIEWPORT3D_NO_BACKEND.get(), canvas().description(),
                "what a sighted user reads on the placeholder reaches a reader too"
                        + describe(tree()));
        assertEquals("", canvas().name(),
                "and it does not take the name slot" + describe(tree()));

        viewport.setTooltip("Model preview");
        frame();

        assertEquals("Model preview", canvas().name(), describe(tree()));
        assertEquals(ComponentStrings.VIEWPORT3D_NO_BACKEND.get(), canvas().description(),
                "the failure stands beside the name rather than fighting it for the slot"
                        + describe(tree()));
    }

    /**
     * With a backend installed there is no failure to describe, and the hook is what notices.
     *
     * <p>The branch is read from {@link Graphics3D#isAvailable()} inside the describe hook, not
     * from a boolean the placeholder left behind. The publish step runs before the paint passes of
     * the same frame, so a flag written in {@code onPaint} would describe the previous frame —
     * precisely the defect ADR 039 §7.2 records for the media transport's mute tooltip — and would
     * additionally publish a stale failure on the first frame after a provider arrived.
     */
    @Test
    void aBackendPresentPublishesNoDescription() {
        bindAViewport();
        viewport.setAnimated(false); // no ticker: the render branch is the one that arms it

        StubProvider provider = new StubProvider();
        Graphics3D.install(provider);
        try {
            viewport.invalidate();
            frame();

            AccessibleNode node = canvas();
            assertEquals(Accessible.Role.CANVAS, node.role(),
                    "a viewport that renders is still a canvas" + describe(tree()));
            assertEquals("", node.description(),
                    "and there is no failure to report" + describe(tree()));
        } finally {
            Graphics3D.uninstall(provider); // process-global static: a leak poisons later tests
        }
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * A damaged frame that changes nothing takes no snapshot and allocates nothing.
     *
     * <p>This widget is the one most exposed to the quiet-frame promise in the whole toolkit: an
     * animated viewport invalidates from its own ticker on every frame while showing, so with a
     * reader attached the tree is walked sixty times a second for the life of the window. One
     * string built in the describe hook is sixty allocations a second spent to conclude that
     * nothing has moved, and one accessible fact published per frame is sixty tree copies. The
     * render scale is the setter this is measured through because it is public, it invalidates,
     * and it changes nothing a reader can perceive — the class's own documentation says the box,
     * the layout around it and picking are all in logical points and do not move.
     *
     * <p>The allocation half is measured against the same frame with nothing listening rather than
     * against zero: the placeholder's own paint sits on both sides of the comparison, so what is
     * claimed is the difference the walk makes.
     */
    @Test
    void aRenderScaleChangePublishesNothing() {
        bindAViewport();
        int published = bridge.published.size();
        bridge.events.clear();

        viewport.setRenderScale(0.5f);
        frame();

        assertEquals(0.5f, viewport.renderScale(), 0.001f, "the setter really did take");
        assertEquals(published, bridge.published.size(),
                "the render scale is a pixel count, not an accessible fact");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);

        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            viewport.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            viewport.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and still nothing said: " + bridge.events);


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a viewport is one enum store and one reference comparison, and must "
                        + "cost no memory at all");
    }

    /**
     * A 3D provider that allocates nothing and draws nothing: enough for {@code onPaint} to take
     * its rendering branch under a canvas that composites nothing.
     */
    private static final class StubProvider implements Graphics3D.Provider {

        @Override
        public RenderTarget createTarget(int widthPx, int heightPx, int samples) {
            return new StubTarget(widthPx, heightPx);
        }

        @Override
        public GpuMesh upload(MeshData mesh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void render(RenderTarget target, Camera camera, Consumer<RenderPass> body) {
        }

        @Override
        public void renderDemoScene(RenderTarget target, double timeSeconds) {
        }
    }

    /** A render target that remembers its size and does nothing else. */
    private static final class StubTarget implements RenderTarget {

        private int widthPx;
        private int heightPx;

        StubTarget(int widthPx, int heightPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }

        @Override
        public int widthPx() {
            return widthPx;
        }

        @Override
        public int heightPx() {
            return heightPx;
        }

        @Override
        public void resize(int widthPx, int heightPx) {
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }

        @Override
        public void dispose() {
        }

        @Override
        public int samples() {
            return 1;
        }

        @Override
        public RenderStats stats() {
            return RenderStats.EMPTY;
        }

        @Override
        public Image readDisplayReferred(int x, int y, int widthPx, int heightPx) {
            return new Image(widthPx, heightPx, new byte[widthPx * heightPx * 4]);
        }

        @Override
        public ScenePixels readSceneReferred(int x, int y, int widthPx, int heightPx) {
            return new ScenePixels(widthPx, heightPx, new float[widthPx * heightPx * 4]);
        }
    }
}
