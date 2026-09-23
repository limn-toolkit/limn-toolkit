package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
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
 * What a {@link Separator} becomes in the accessible tree: one {@code SEPARATOR} node with one
 * orientation bit, no name, no verb, never a tab stop, whose box is the widget's and not the ink.
 *
 * <p>The widget is a painting leaf that holds no string and takes no input, which is exactly the
 * shape ADR 039 §1.6's predicate deletes and then warns about, once per class, in every application
 * that draws a rule. The way out is the role and not the decoration seam: a rule between groups is
 * information, and the {@code SEPARATOR} role is what all three platforms carry for it. So the
 * absence of the walk's warning is asserted after <em>every</em> test rather than in one of them,
 * because the walk names a class at most once for the life of the virtual machine, and whichever
 * test runs first is the one that would catch a hook that stopped declaring the role.
 *
 * <p>Two of these tests exist to pin where the survey was wrong. §7's row says the orientation comes
 * from the laid-out box; the widget fixes it at construction through its two factories, and a hook
 * that read width against height would announce a squeezed horizontal rule as vertical. And the
 * row is silent on bounds: {@code setInset} trims the painted line only, and the published box is
 * the widget's own.
 *
 * <p>Everything below drives the separator's public API, and Widget's, on a bound scene, and reads
 * back the tree the scene published. Nothing constructs a node.
 */
class SeparatorAccessibilityTest extends AccessibleComponentTestBase {

    /**
     * A leaf with a fixed preferred size and an application-supplied name: the groups on either
     * side of the rule, and the neighbours whose order the separator has to sit between.
     */
    private static final class Box extends Widget<Box> {
        Box(String name) {
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, 40);
        }
    }

    private Separator separator;
    private Widget<?> container;

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

    @AfterEach
    void theRuleIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
                        // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0]. Read the message here and the assertion passes
            // whatever the walk does, which is what it did until a verification read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.Separator"),
                    "the separator paints, and it declares a role, so the walk must never say it "
                            + "paints and is deleted; a warning here names a toolkit class an "
                            + "application cannot correct: " + subject + " " + record.getMessage());
        }
    }

    /** A horizontal rule between two named boxes in a column, which is what the class is for. */
    private void bindHorizontal() {
        separator = Separator.horizontal();
        Column column = new Column();
        column.add(new Box("above"));
        column.add(separator);
        column.add(new Box("below"));
        container = column;
        bind(column);
    }

    /** The mirror: a vertical rule between two named boxes in a row, the tool bar's shape. */
    private void bindVertical() {
        separator = Separator.vertical();
        Row row = new Row();
        row.add(new Box("before"));
        row.add(separator);
        row.add(new Box("after"));
        container = row;
        bind(row);
    }

    /** @return the window node, node zero of every tree the scene publishes */
    private AccessibleNode window() {
        return tree().node(0);
    }

    // ------------------------------------------------------------------ the shape of the tree

    /**
     * One node, the right role, the right axis, in its place between its neighbours.
     *
     * <p>The column is scaffolding and is deleted, so the separator hangs off the window node
     * directly, between the two boxes, in tree order. A reader moving through the groups meets the
     * boundary where the eye does.
     */
    @Test
    void aHorizontalRuleIsOneSeparatorNodeOnTheHorizontalAxisBetweenItsNeighbours() {
        bindHorizontal();

        AccessibleNode node = node(Accessible.Role.SEPARATOR);
        assertTrue(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(0, node.parent(),
                "the column is transparent, so the rule's parent is the window" + describe(tree()));

        List<AccessibleNode> children = childrenOf(window());
        assertEquals(3, children.size(), describe(tree()));
        assertEquals("above", children.get(0).name());
        assertEquals(Accessible.Role.SEPARATOR, children.get(1).role());
        assertEquals("below", children.get(2).name());
    }

    /** The same on the other axis: a vertical rule in a row announces vertical and not horizontal. */
    @Test
    void aVerticalRuleIsOneSeparatorNodeOnTheVerticalAxis() {
        bindVertical();

        AccessibleNode node = node(Accessible.Role.SEPARATOR);
        assertTrue(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL), describe(tree()));

        List<AccessibleNode> children = childrenOf(window());
        assertEquals(3, children.size(), describe(tree()));
        assertEquals("before", children.get(0).name());
        assertEquals(Accessible.Role.SEPARATOR, children.get(1).role());
        assertEquals("after", children.get(2).name());
    }

    // ---------------------------------------------------------- where the survey's row was wrong

    /**
     * The orientation is the factory's and never the box's.
     *
     * <p>§7's row says "orientation from the laid-out box". A parent can hand a horizontal rule a
     * box taller than it is wide — a tight spacer, a tight root — and a hook that read width
     * against height would then announce the rule on the wrong axis. The bit is a fact about what
     * the rule divides, fixed at construction, and this test fails the moment anyone "fixes" the
     * hook to read the geometry.
     */
    @Test
    void theOrientationIsTheFactorysAndNotTheBoxs() {
        Separator horizontal = Separator.horizontal();
        Column column = new Column();
        column.add(new Box("above"));
        column.add(new SizedBox(3, 40, horizontal));
        column.add(new Box("below"));
        bind(column);

        assertTrue(horizontal.height() > horizontal.width(),
                "the spacer really squeezed the rule taller than wide: "
                        + horizontal.width() + "x" + horizontal.height());
        AccessibleNode squeezed = node(Accessible.Role.SEPARATOR);
        assertTrue(squeezed.has(Accessible.State.HORIZONTAL),
                "a squeezed horizontal rule is still horizontal" + describe(tree()));
        assertFalse(squeezed.has(Accessible.State.VERTICAL), describe(tree()));

        Separator vertical = Separator.vertical();
        Row row = new Row();
        row.add(new Box("before"));
        row.add(new SizedBox(40, 3, vertical));
        row.add(new Box("after"));
        bind(row);

        assertTrue(vertical.width() > vertical.height(),
                "the spacer really squeezed the rule wider than tall: "
                        + vertical.width() + "x" + vertical.height());
        AccessibleNode flattened = node(Accessible.Role.SEPARATOR);
        assertTrue(flattened.has(Accessible.State.VERTICAL),
                "a flattened vertical rule is still vertical" + describe(tree()));
        assertFalse(flattened.has(Accessible.State.HORIZONTAL), describe(tree()));
    }

    /**
     * The box is the widget's own and not the ink, so the inset changes nothing and the step
     * changes the box.
     *
     * <p>A reader points at the node's rectangle, and a one-point hairline is not a target; what
     * is published is the {@code separatorBox}-thick box the widget lays out in. {@code setInset}
     * trims the painted line's ends only, and it requests a layout, so the walk runs and must find
     * no difference: no snapshot, no event. Moving the control size is a real change to the box,
     * and that one is reported.
     */
    @Test
    void theBoxIsTheWidgetsOwnAndTheInsetTrimsOnlyTheInk() {
        bindHorizontal();
        AccessibleNode node = node(Accessible.Role.SEPARATOR);

        assertEquals(separator.localToSceneX(), node.x(), 0.01f, describe(tree()));
        assertEquals(separator.localToSceneY(), node.y(), 0.01f);
        assertEquals(separator.width(), node.width(), 0.01f);
        assertEquals(separator.height(), node.height(), 0.01f);
        float box = SizeTokens.of(separator.controlSize()).separatorBox();
        assertEquals(box, node.height(), 0.01f,
                "the cross axis is the step's separator box, not the hairline" + describe(tree()));

        int published = bridge.published.size();
        bridge.events.clear();
        separator.setInset(12);
        frame();

        AccessibleNode afterInset = node(Accessible.Role.SEPARATOR);
        assertEquals(node.x(), afterInset.x(), 0.01f, "the inset is paint, not geometry");
        assertEquals(node.width(), afterInset.width(), 0.01f);
        assertEquals(node.height(), afterInset.height(), 0.01f);
        assertEquals(published, bridge.published.size(),
                "the inset's layout pass found no difference, so no snapshot was taken");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        AccessibleNode afterStep = node(Accessible.Role.SEPARATOR);
        float larger = SizeTokens.of(ControlSize.XLARGE).separatorBox();
        assertNotEquals(box, larger, "the two steps must differ for this to test anything");
        assertEquals(larger, afterStep.height(), 0.01f,
                "a real change to the box still publishes" + describe(tree()));
        assertEquals(separator.height(), afterStep.height(), 0.01f);
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the move is what a reader is told about: " + bridge.events);
    }

    // ------------------------------------------------------------- what the walk adds and does not

    /**
     * Enabled, never a tab stop, no verb, no name.
     *
     * <p>The inherited bits arrive; {@code FOCUSABLE} does not, because the widget never is, so the
     * walk adds no {@code FOCUS} or {@code SCROLL_INTO_VIEW} either. Tabbing from nothing never
     * lands on it. Disabling the container publishes the rule without {@code ENABLED} and still as
     * one bare {@code SEPARATOR} node: the transparency verdict on the column does not move, and no
     * skeleton grows around the rule.
     */
    @Test
    void enabledNeverATabStopNoVerbsNoName() {
        bindHorizontal();
        AccessibleNode node = node(Accessible.Role.SEPARATOR);

        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertNull(node.actions(), "a rule has no verb" + describe(tree()));
        assertEquals("", node.name(), "and no name of its own" + describe(tree()));
        assertEquals("", node.description(), describe(tree()));

        for (int i = 0; i < 4; i++) {
            scene.focusTraverse(false);
            assertNotSame(separator, scene.focusedWidget(),
                    "a Tab traversal from nothing never lands on a rule");
        }

        int nodes = tree().nodeCount();
        container.setEnabled(false);
        frame();

        AccessibleNode disabled = node(Accessible.Role.SEPARATOR);
        assertFalse(disabled.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(disabled.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertTrue(disabled.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertEquals(nodes, tree().nodeCount(),
                "a disabled column grows no group around the rule" + describe(tree()));
    }

    // ------------------------------------------------- the ways an application names or hides it

    /**
     * Naming and striking out stay the application's.
     *
     * <p>A tooltip names the node through the walk's free default and nothing else; an explicit
     * name wins and says so in its provenance; ignoring the rule removes its node and nothing
     * beside it. A name declared by the hook would double-speak the role or shadow the
     * application's, which is why the hook declares none.
     */
    @Test
    void namingAndStrikingOutAreTheApplicationsCall() {
        bindHorizontal();

        separator.setTooltip("Sections");
        frame();
        AccessibleNode byTooltip = node(Accessible.Role.SEPARATOR);
        assertEquals("Sections", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(),
                "the free default reaches this class like any other" + describe(tree()));

        separator.setAccessibleName("Between the sections");
        frame();
        AccessibleNode explicit = node(Accessible.Role.SEPARATOR);
        assertEquals("Between the sections", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals("Sections", explicit.description(),
                "and the tooltip becomes the description once something else named it"
                        + describe(tree()));

        separator.setAccessibleIgnored(true);
        frame();
        assertThrows(AssertionError.class, () -> node(Accessible.Role.SEPARATOR),
                "a decorative rule is struck out by the application" + describe(tree()));
        assertEquals("above", node("above").name(), describe(tree()));
        assertEquals("below", node("below").name(), describe(tree()));
        assertEquals(2, childrenOf(window()).size(), describe(tree()));
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * A damaged frame that changes nothing costs nothing to describe.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor of its own, and the rule's own paint sits on both sides of the
     * comparison. What is claimed is the difference. A hook that built an orientation string, or
     * formatted anything at all, would be invisible everywhere except here.
     */
    @Test
    void aDamagedFrameThatChangesNothingCostsNothingToDescribe() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindHorizontal();
        int published = bridge.published.size();
        bridge.events.clear();

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            separator.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            separator.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a rule is two primitive writes and must cost no memory at all");
    }

    // ---------------------------------------------------------------------------- identity

    /**
     * The node keeps its identifier through a quiet frame and through a step change.
     *
     * <p>Identity is minted over the widget tree, so a reader's cursor resting on the rule is not
     * lost when the density changes and the box moves.
     */
    @Test
    void identitySurvivesAQuietFrameAndAStepChange() {
        bindHorizontal();
        long id = node(Accessible.Role.SEPARATOR).id();

        separator.invalidate();
        frame();
        assertEquals(id, node(Accessible.Role.SEPARATOR).id(), "a quiet frame re-keys nothing");

        scene.setControlSize(ControlSize.XSMALL);
        frame();

        AccessibleTree tree = tree();
        assertEquals(id, node(Accessible.Role.SEPARATOR).id(),
                "the density is not the rule's identity" + describe(tree));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }
}
