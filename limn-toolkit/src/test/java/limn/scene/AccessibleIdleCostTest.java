package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.concurrent.UiRuntime;
import limn.i18n.I18nString;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the accessibility seams cost a window nothing is listening to, which is the state every
 * window in every process is in until an assistive technology arrives.
 *
 * <p>The promise is zero, and it has to be measured rather than asserted, because the seams are
 * exactly the kind of thing that costs nothing until somebody adds one line to a funnel that runs
 * sixty times a second. The three entry points that reach nothing else in the toolkit —
 * {@link Widget#setTooltip}, {@link Widget#setFocusable} and {@link Widget#invalidateAccessible()}
 * — set their flag and buy no frame; a frame renders byte for byte what it did before; and an
 * announcement made into a process with no reader is still queued, still bounded, and still costs
 * no frame.
 *
 * <p>Two of the costs are stated per widget and per frame, and each has a test that measures it
 * rather than a frame test that happens not to see it. Per widget, the whole memory cost of the
 * tree is one nullable reference, and it becomes an object on the first thing an application
 * declares and never before. Per frame, the dirty flag is a boolean store from every damage funnel,
 * and a thousand widgets storing it on every frame cost that frame no memory, no describe call and
 * no question to the platform beyond the one it is owed.
 */
class AccessibleIdleCostTest {

    private static final class Box extends Widget<Box> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(100, 40);
        }
    }

    private HeadlessUi ui;
    private UiRuntime runtime;
    private final AtomicLong nanos = new AtomicLong();
    private RecordingWindow window;
    private Scene scene;
    private Box box;

    @BeforeEach
    void bindScene() {
        ui = new HeadlessUi(nanos::get);
        runtime = ui.runtime();
        box = new Box();
        scene = new Scene(box, nanos::get);
        window = new RecordingWindow();
        scene.bind(window);
        scene.renderFrame(new NoopCanvas(200, 200));
        window.frameRequests = 0;
    }

    @AfterEach
    void unbind() {
        ui.close();
    }

    @Test
    void theThreeEntryPointsThatReachNothingElseBuyNoFrameWithNothingListening() {
        box.setTooltip("Play");
        box.setFocusable(true);
        box.invalidateAccessible();
        box.setAccessibleName("Play the film");
        box.setAccessibleRole(Accessible.Role.BUTTON);
        box.setAccessibleDescription(I18nString.literal("Starts playback"));
        box.setAccessibleIgnored(false);

        assertEquals(0, window.frameRequests,
                "nothing is listening, so nothing may spend a frame on it");
    }

    /** The control: a change that paints still buys its frame, so the harness is not simply dead. */
    @Test
    void aChangeThatPaintsStillBuysItsFrame() {
        box.invalidate();
        assertEquals(1, window.frameRequests);
    }

    @Test
    void setFocusableIsANoOpWhenTheFlagDidNotMove() {
        box.setFocusable(true);
        box.setFocusable(true);
        box.setFocusable(true);
        assertEquals(0, window.frameRequests);
        assertTrue(box.isFocusable());
    }

    @Test
    void anAnnouncementIntoAProcessWithNoReaderIsQueuedAndCostsNoFrame() {
        for (int i = 0; i < 200; i++) {
            scene.announce("row " + i + " added", Accessible.Politeness.POLITE);
        }
        scene.announce(I18nString.literal("done"), Accessible.Politeness.ASSERTIVE);

        assertEquals(0, window.frameRequests,
                "an application's diagnostics must not depend on a reader being present, and must "
                        + "not spend frames on speech nobody will hear");
    }

    /**
     * A frame on a window nothing is listening to allocates nothing.
     *
     * <p>The whole point of hanging the accessibility flag on the funnel every repaint goes through
     * is that it costs one boolean store. This is what makes that a measurement rather than a
     * claim: sixty frames of a settled scene, and the smallest of them allocates nothing at all.
     */
    @Test
    void aFrameWithNothingListeningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        NoopCanvas canvas = new NoopCanvas(200, 200);
        scene.renderFrame(canvas);

        long least = AllocationProbe.leastAllocatedBy(() -> scene.renderFrame(canvas), 60);

        assertEquals(0, least, "a frame nobody is listening to must cost no memory");
    }

    /**
     * And with a bridge attached and something listening, a frame over a tree that did not move
     * costs the same nothing.
     *
     * <p>That is the state the flag riding the damage funnel pays for: damage is a coarse trigger,
     * so a repaint that changed no accessible fact does reach the publish step. What it must not do
     * is produce anything.
     */
    @Test
    void aFrameWithALiveBridgeAndACleanTreeAllocatesNothingEither() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        RecordingAccessibilityBridge listening = new RecordingAccessibilityBridge();
        listening.listening = true;
        window.accessibility = listening;
        scene.bind(window);
        NoopCanvas surface = new NoopCanvas(200, 200);
        scene.renderFrame(surface);
        scene.renderFrame(surface);

        long least = AllocationProbe.leastAllocatedBy(() -> scene.renderFrame(surface), 60);

        assertEquals(0, least, "a settled tree costs nothing to confirm as settled");
    }

    // ------------------------------------------------------------- §6, measured

    /** A widget that counts how often it is asked to describe itself, and never says anything. */
    private static final class Counted extends Widget<Counted> {
        int describes;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(10, 10);
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            describes++;
        }
    }

    /** A bridge nobody listens to, that counts every question and every statement it receives. */
    private static final class Deaf implements AccessibilityBridge {
        int listeningAsked;
        int published;
        int emitted;

        @Override
        public boolean isListening() {
            listeningAsked++;
            return false;
        }

        @Override
        public void publish(AccessibleTree tree, boolean reentrant) {
            published++;
        }

        @Override
        public void emit(AccessibleEvent event) {
            emitted++;
        }
    }

    /** Where the probes park what they built, so the compiler cannot elide the allocation. */
    private Widget<?> keep;

    /**
     * The per-widget cost: one nullable reference, and an object only on the first declaration.
     *
     * <p>Measured by difference, interleaved and typical rather than least, because two numbers
     * are being compared. A widget nobody described allocates itself and nothing for the tree; the
     * first thing an application declares about it costs one object; the second declaration costs
     * nothing more, which is what makes it one object and not one per setter; and re-declaring on a
     * widget that is already in a bound scene nobody is listening to allocates nothing and buys no
     * frame.
     */
    @Test
    void theFieldBecomesAnObjectOnTheFirstDeclarationAndNeverBefore() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");

        long[] bareAndDeclared = AllocationProbe.typicalAllocatedByEach(
                () -> keep = new Box(),
                () -> {
                    Box declared = new Box();
                    declared.setAccessibleRole(Accessible.Role.BUTTON);
                    keep = declared;
                }, 61);
        assertTrue(bareAndDeclared[1] > bareAndDeclared[0],
                "the first declaration is where the field becomes an object: bare "
                        + bareAndDeclared[0] + ", declared " + bareAndDeclared[1]);

        long[] onceAndTwice = AllocationProbe.typicalAllocatedByEach(
                () -> {
                    Box once = new Box();
                    once.setAccessibleRole(Accessible.Role.BUTTON);
                    keep = once;
                },
                () -> {
                    Box twice = new Box();
                    twice.setAccessibleRole(Accessible.Role.BUTTON);
                    twice.setAccessibleRole(Accessible.Role.CHECK_BOX);
                    twice.setAccessibleIgnored(false);
                    keep = twice;
                }, 61);
        assertEquals(onceAndTwice[0], onceAndTwice[1],
                "one object for every declaration, not one per declaration");

        box.setAccessibleRole(Accessible.Role.BUTTON); // the object now exists on the bound widget
        window.frameRequests = 0;
        long redeclared = AllocationProbe.leastAllocatedBy(
                () -> box.setAccessibleRole(Accessible.Role.BUTTON), 60);
        assertEquals(0, redeclared,
                "a declaration on a widget that already carries the object is a store");
        assertEquals(0, window.frameRequests, "and nothing is listening, so it buys no frame");
    }

    /**
     * The per-frame cost: the boolean store on every damage funnel, at a scale no single-widget
     * test reaches.
     *
     * <p>A thousand widgets damage themselves before every frame, so the flag is stored a thousand
     * times through the funnel every repaint goes through, and the frame that follows is measured
     * three ways. It allocates nothing. It describes nobody: the hook is not entered once on any of
     * the thousand, with no bridge and with a bridge nobody listens to alike. And with that bridge
     * attached it asks the platform exactly one question per frame, which is the one §6 says it may,
     * and tells it nothing.
     */
    @Test
    void aThousandWidgetsDamagingThemselvesCostAFrameNothingWithNothingListening() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        AccessibleTestBase.Group root = new AccessibleTestBase.Group();
        Counted[] many = new Counted[1000];
        for (int i = 0; i < many.length; i++) {
            many[i] = new Counted();
            root.add(many[i]);
        }
        scene = new Scene(root, nanos::get);
        window = new RecordingWindow();
        scene.bind(window);
        NoopCanvas surface = new NoopCanvas(200, 200);
        scene.renderFrame(surface);
        Runnable damagedFrame = () -> {
            for (int i = 0; i < many.length; i++) {
                many[i].invalidate();
            }
            scene.renderFrame(surface);
        };

        long least = AllocationProbe.leastAllocatedBy(damagedFrame, 60);
        assertEquals(0, least,
                "a thousand flag stores through the damage funnel must cost the frame no memory");
        for (Counted widget : many) {
            assertEquals(0, widget.describes, "no bridge: nobody may be asked to describe itself");
        }

        Deaf deaf = new Deaf();
        window.accessibility = deaf;
        scene.bind(window);
        scene.renderFrame(surface);
        deaf.listeningAsked = 0;

        least = AllocationProbe.leastAllocatedBy(damagedFrame, 60);
        assertEquals(0, least, "a bridge nobody listens to changes nothing about that");
        assertEquals(61, deaf.listeningAsked,
                "one question per frame, the warm-up frame included, and no more");
        assertEquals(0, deaf.published, "and no tree");
        assertEquals(0, deaf.emitted, "and no event");
        for (Counted widget : many) {
            assertEquals(0, widget.describes,
                    "a bridge nobody listens to: nobody may be asked to describe itself");
        }
    }
}
