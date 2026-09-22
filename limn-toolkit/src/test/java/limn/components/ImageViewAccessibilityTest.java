package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an {@link ImageView} becomes in the accessible tree: one {@code IMAGE} node, always, with
 * no name of its own, no verb, never a tab stop, whose box is the widget's and not the drawing's.
 *
 * <p>The widget is a painting leaf that holds no string and takes no input, so before it described
 * itself it was in two wrong states at once. Unnamed, ADR 039 §1.6's predicate deleted it and
 * warned about it once per class, in every application that shows a picture — and answering that
 * warning with the decoration seam would have deleted the picture in silence instead, which is the
 * one thing §1.6 says a widget that paints information may not do. Named, it survived the predicate
 * on the strength of the name and published under the builder's default role, so the very case
 * §7's row is about — "name from the application or the tooltip" — reached a reader as a group box
 * wearing the picture's name, with no warning anywhere, because the unnamed-role warning only fires
 * on a focusable node. One role fixes both, and the tests below hold both halves down.
 *
 * <p>Three of these exist to pin where §7's row was wrong. The row's whole guidance is "ignored
 * when it has neither" a name nor a tooltip, and that is unachievable as written: the walk runs
 * this widget's hook <em>before</em> the application's name, its bound caption and the tooltip
 * default, so inside the hook there is nothing to condition on — and it is forbidden in spirit
 * besides, because {@code ignore()} is consulted after those overrides and would overrule all
 * three with no appeal. The row is also silent on the box, which under {@link ImageView.Fit} is
 * genuinely not the ink.
 *
 * <p>Everything below drives the view's public API, and Widget's, on a bound scene, and reads back
 * the tree the scene published. Nothing constructs a node.
 */
class ImageViewAccessibilityTest extends AccessibleComponentTestBase {

    /** A leaf with a fixed preferred size and an application-supplied name: the picture's neighbours. */
    private static final class Box extends Widget {
        Box(String name) {
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, 40);
        }
    }

    /**
     * @param width  the picture's width in pixels
     * @param height its height
     * @return an opaque-black picture of that size, which is all the tree ever sees of one
     */
    private static Image picture(int width, int height) {
        return new Image(width, height, new byte[width * height * 4]);
    }

    private ImageView view;
    private Widget container;

    /** Every record the walk logged while a test was running; see the class comment. */
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
     * <p>The warning is logged at most once for the life of the virtual machine, so whichever test
     * ran first would be the only one able to catch a hook that stopped declaring the role — or a
     * {@code paintsDecoration()} override added to "silence the warning", which would delete every
     * picture in the interface and say nothing.
     */
    @AfterEach
    void theWalkNeverNamesImageViewInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0].
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.ImageView"),
                    "a picture is information, and this class declares a role for it, so the walk "
                            + "must never say it paints and is deleted; a warning here names a "
                            + "toolkit class in an application's log: "
                            + subject + " " + record.getMessage());
        }
    }

    /** A picture between two named boxes in a column, which is how one is normally placed. */
    private void bindInAColumn() {
        view = new ImageView(picture(32, 16));
        Column column = new Column();
        column.add(new Box("above"));
        column.add(view);
        column.add(new Box("below"));
        container = column;
        bind(column);
    }

    /** @return the window node, node zero of every tree the scene publishes */
    private AccessibleNode window() {
        return tree().node(0);
    }

    // ------------------------------------------------------------------ the shape of the tree

    /**
     * One picture node in its place between its neighbours, with the walk's box and nothing said.
     *
     * <p>The column is scaffolding and is deleted, so the picture hangs off the window node
     * directly, between the two boxes, in reading order. It declares no name, no description and no
     * verb, and it is never a tab stop, because the widget takes no input at all — so the walk adds
     * neither {@code FOCUS} nor {@code SCROLL_INTO_VIEW} and a Tab traversal from nothing never
     * lands on it.
     */
    @Test
    void anImageViewIsOnePictureNodeWithItsOwnBoxAndNoNameOfItsOwn() {
        bindInAColumn();

        AccessibleNode node = node(Accessible.Role.IMAGE);
        assertEquals(0, node.parent(),
                "the column is transparent, so the picture's parent is the window"
                        + describe(tree()));

        List<AccessibleNode> children = childrenOf(window());
        assertEquals(3, children.size(), describe(tree()));
        assertEquals("above", children.get(0).name());
        assertEquals(Accessible.Role.IMAGE, children.get(1).role());
        assertEquals("below", children.get(2).name());

        assertEquals("", node.name(), "the widget holds no string to name itself with"
                + describe(tree()));
        assertEquals("", node.description(), describe(tree()));
        assertNull(node.actions(), "a picture has no verb" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));

        assertEquals(view.localToSceneX(), node.x(), 0.01f, describe(tree()));
        assertEquals(view.localToSceneY(), node.y(), 0.01f);
        assertEquals(view.width(), node.width(), 0.01f);
        assertEquals(view.height(), node.height(), 0.01f);

        for (int i = 0; i < 4; i++) {
            scene.focusTraverse(false);
            assertNotSame(view, scene.focusedWidget(),
                    "a Tab traversal from nothing never lands on a picture");
        }
    }

    // ---------------------------------------------------------- where the survey's row was wrong

    /**
     * A named picture is an image and not a group, which is the case §7's row is about and the one
     * that was broken.
     *
     * <p>All three ways a picture gets a name are the walk's and the application's, and none of
     * them is shadowed by this class: the tooltip default names it and says so in the provenance,
     * an explicit name beats the tooltip and pushes it into the description, and a bound caption
     * names it through a relation a client can walk to. What every one of them reaches is an
     * {@code IMAGE} node — before the role was declared it was a {@code GROUP}, because a name is
     * enough to survive the transparency predicate and nothing else fills the role in.
     */
    @Test
    void aNamedPictureIsAnImageAndNotAGroup() {
        bindInAColumn();

        view.setTooltip("Company logo");
        frame();
        AccessibleNode byTooltip = node(Accessible.Role.IMAGE);
        assertEquals("Company logo", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(),
                "the free default reaches this class like any other" + describe(tree()));

        view.setAccessibleName("The Limn mark");
        frame();
        AccessibleNode explicit = node(Accessible.Role.IMAGE);
        assertEquals("The Limn mark", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals("Company logo", explicit.description(),
                "and the tooltip becomes the description once something else named it"
                        + describe(tree()));

        ImageView captioned = new ImageView(picture(32, 16));
        Label caption = new Label("Export preview");
        Column column = new Column();
        column.add(caption);
        column.add(captioned);
        bind(column);
        caption.setLabelFor(captioned);
        frame();

        AccessibleNode labelled = node(Accessible.Role.IMAGE);
        assertEquals("Export preview", labelled.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, labelled.nameFrom(),
                "the provenance is what stops a bridge writing a caption into the title attribute"
                        + describe(tree()));
        long target = AccessibleNode.NONE;
        int relations = 0;
        for (var relation : labelled.relations()) {
            if (relation.kind() == Accessible.Relation.LABELLED_BY) {
                target = relation.target();
                relations++;
            }
        }
        assertEquals(1, relations, "one LABELLED_BY on the picture: " + labelled.relations());
        assertEquals(node(Accessible.Role.LABEL).id(), target,
                "and it resolves to the caption's own node" + describe(tree()));
    }

    /**
     * Striking a decorative picture out is the application's call, and this class never makes it.
     *
     * <p>§7's row asks the widget to ignore itself when it has neither a name nor a tooltip. It
     * cannot: the walk runs this hook first and the application's name, its bound caption and the
     * tooltip default afterwards, so inside the hook there is nothing to look at. And it must not,
     * because {@code ignore()} is consulted after those overrides, so a class-level ignore would
     * beat a name the application had already written with no way to appeal. What the row wanted is
     * one line in the application, and it still works on a widget that describes itself.
     */
    @Test
    void strikingADecorativeIconOutIsTheApplicationsCall() {
        bindInAColumn();
        long above = node("above").id();
        long below = node("below").id();
        long picture = node(Accessible.Role.IMAGE).id();

        view.setAccessibleIgnored(true);
        frame();

        assertThrows(AssertionError.class, () -> node(Accessible.Role.IMAGE),
                "a spacer image is struck out by the application" + describe(tree()));
        assertEquals(2, childrenOf(window()).size(), describe(tree()));
        assertEquals(above, node("above").id(), "and its neighbours are untouched"
                + describe(tree()));
        assertEquals(below, node("below").id(), describe(tree()));

        view.setAccessibleIgnored(false);
        frame();

        assertEquals(3, childrenOf(window()).size(), describe(tree()));
        assertEquals(picture, node(Accessible.Role.IMAGE).id(),
                "the picture comes back as the node a reader was holding" + describe(tree()));
    }

    /**
     * The published box is the widget's own and never the drawing's, on every {@link ImageView.Fit}.
     *
     * <p>The row is silent here and the two really do differ: {@code onPaint} centres the image, so
     * {@code CONTAIN} letterboxes a wide picture inside a square box, {@code NONE} draws it at its
     * natural size in the middle, and {@code COVER} overflows and is clipped. Publishing the drawn
     * rectangle would disagree with the pointer, because {@code hitTest} claims the whole box, and
     * would pay the fit arithmetic again on every publish. Changing the fit only repaints, so the
     * walk must find no difference either: no snapshot, and nothing said.
     */
    @Test
    void theBoxIsTheWidgetsAndNotTheInk() {
        view = new ImageView(picture(32, 16));
        // Inside a column and not as the root: a root is laid out tight to the window, which would
        // hand the picture the window's box and make the assertion below about the wrong number.
        Column column = new Column();
        column.add(new SizedBox(200, 200, view));
        bind(column);

        AccessibleNode node = node(Accessible.Role.IMAGE);
        assertEquals(200, node.width(), 0.01f,
                "CONTAIN draws 200x100 in the middle; the node is the box" + describe(tree()));
        assertEquals(200, node.height(), 0.01f, describe(tree()));
        assertEquals(view.localToSceneX(), node.x(), 0.01f);
        assertEquals(view.localToSceneY(), node.y(), 0.01f);
        assertNotEquals(32f, node.width(),
                "and it is not the picture's own size either" + describe(tree()));

        int published = bridge.published.size();
        bridge.events.clear();

        view.setFit(ImageView.Fit.NONE);
        frame();
        AccessibleNode natural = node(Accessible.Role.IMAGE);
        assertEquals(node.x(), natural.x(), 0.01f, "the fit is paint, not geometry");
        assertEquals(node.y(), natural.y(), 0.01f);
        assertEquals(node.width(), natural.width(), 0.01f);
        assertEquals(node.height(), natural.height(), 0.01f);

        view.setFit(ImageView.Fit.COVER);
        frame();
        AccessibleNode cropped = node(Accessible.Role.IMAGE);
        assertEquals(node.x(), cropped.x(), 0.01f, "even when the drawing overflows and is clipped");
        assertEquals(node.y(), cropped.y(), 0.01f);
        assertEquals(node.width(), cropped.width(), 0.01f);
        assertEquals(node.height(), cropped.height(), 0.01f);

        assertEquals(published, bridge.published.size(),
                "a repaint that changes no accessible fact takes no snapshot");
        assertTrue(bridge.events.isEmpty(), "and says nothing: " + bridge.events);
    }

    // ------------------------------------------------ the picture that has not arrived, and icons

    /**
     * A view holding no image is still one picture node, and it keeps what the application said.
     *
     * <p>The class permits a null image — neither the constructor nor {@code setImage} refuses one,
     * and both {@code onMeasure} and {@code onPaint} guard — so "a picture" is a state the widget
     * can be in without holding one, and a lazily loaded photograph passes through it. The
     * tempting {@code if (image == null) ignore()} would make the node appear and vanish as
     * pictures arrive and would swallow the alt text on the way, so there is no such branch: the
     * node stands, at the box the preferred size gives it, and the load leaves the identifier alone.
     */
    @Test
    void anEmptyImageViewIsStillOnePictureNodeAndKeepsWhatTheApplicationSaid() {
        view = new ImageView(null);
        view.setPreferredSize(64, 64);
        view.setAccessibleName("Product photo");
        Column column = new Column();
        column.add(view);
        bind(column);

        AccessibleNode empty = node(Accessible.Role.IMAGE);
        assertEquals("Product photo", empty.name(),
                "the application's alt text survives an image that has not arrived"
                        + describe(tree()));
        assertEquals(64, empty.width(), 0.01f, describe(tree()));
        assertEquals(64, empty.height(), 0.01f, describe(tree()));
        long id = empty.id();

        view.setImage(picture(64, 64));
        frame();

        AccessibleNode loaded = node(Accessible.Role.IMAGE);
        assertEquals(id, loaded.id(),
                "the picture arriving is not a new element" + describe(tree()));
        assertEquals("Product photo", loaded.name(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader was holding went away: " + bridge.events);
    }

    /**
     * Icon mode is paint, and the enabled bit is the walk's.
     *
     * <p>A tint recolours the image's alpha and swaps in the disabled ink while it is at it; none
     * of that is a fact about the element, and the model carries nothing for either, so setting and
     * clearing a tint publishes no difference at all. Disabling the container publishes the picture
     * without {@code ENABLED} and still as one bare {@code IMAGE} node — the column's transparency
     * verdict does not move, so no skeleton grows around it.
     */
    @Test
    void theTintAndTheDisabledPictureSayNothingExtra() {
        bindInAColumn();
        int published = bridge.published.size();
        bridge.events.clear();

        view.setTint(Theme.current().primary());
        frame();
        view.setTint(null);
        frame();

        assertEquals(published, bridge.published.size(),
                "an icon is a picture drawn differently, not a different element");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);

        int nodes = tree().nodeCount();
        container.setEnabled(false);
        frame();

        AccessibleNode disabled = node(Accessible.Role.IMAGE);
        assertFalse(disabled.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(disabled.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertEquals(nodes, tree().nodeCount(),
                "a disabled column grows no group around the picture" + describe(tree()));
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * A damaged frame that changes nothing costs nothing to describe.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor of its own and the picture's own paint sits on both sides of the
     * comparison, so what is claimed is the difference. The hook is one enum store. A derived
     * string creeping into it — a size sentence, the fit's name, an "image" fallback label — would
     * be invisible in every other test here and would spend one string per damaged frame to
     * conclude that nothing had moved.
     */
    @Test
    void aDamagedFrameThatChangesNothingCostsNothingToDescribe() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindInAColumn();
        int published = bridge.published.size();
        bridge.events.clear();

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            view.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            view.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a picture is one primitive write and must cost no memory at all");
    }

    // ---------------------------------------------------------------------------- identity

    /**
     * The node keeps its identifier through a quiet frame, a swapped image and a resize.
     *
     * <p>Identity is minted over the widget tree, so a reader's cursor resting on a picture is not
     * thrown away when the picture behind it changes. The resize is a real change to the box and is
     * reported as one; nothing in the sequence destroys a node, which is the failure that is
     * invisible from inside the toolkit and shows up only as a cursor jumping to the top of the
     * window.
     */
    @Test
    void identitySurvivesAQuietFrameAnImageSwapAndAResize() {
        bindInAColumn();
        long id = node(Accessible.Role.IMAGE).id();

        view.invalidate();
        frame();
        assertEquals(id, node(Accessible.Role.IMAGE).id(), "a quiet frame re-keys nothing");

        view.setImage(picture(64, 48));
        frame();
        assertEquals(id, node(Accessible.Role.IMAGE).id(),
                "the picture is not the element" + describe(tree()));

        view.setFit(ImageView.Fit.FILL);
        frame();
        assertEquals(id, node(Accessible.Role.IMAGE).id(), describe(tree()));

        bridge.events.clear();
        view.setPreferredSize(120, 90);
        frame();

        assertEquals(id, node(Accessible.Role.IMAGE).id(), describe(tree()));
        assertEquals(120, node(Accessible.Role.IMAGE).width(), 0.01f, describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the move is what a reader is told about: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing went away: " + bridge.events);
    }
}
