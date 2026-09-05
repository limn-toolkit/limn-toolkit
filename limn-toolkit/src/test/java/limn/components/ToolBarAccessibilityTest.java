package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ToolBar} becomes in the accessible tree: one {@code TOOL_BAR} node on the
 * horizontal axis, with no name, no facet and no verb, whose children are its items in the order
 * they were added.
 *
 * <p>The bar is a container that holds no string, takes no input and is never a tab stop, so ADR
 * 039 §1.6's predicate would delete it and hoist its items — and because the class paints a
 * surface and an outline, the deletion would name a toolkit class in an application's log and
 * recommend {@code setAccessibleIgnored(true)}, which on this widget removes every control in the
 * bar. The role is what prevents both, which is why the absence of that warning is asserted after
 * <em>every</em> test rather than inside one: the walk names a class at most once for the life of
 * the virtual machine, so whichever case runs first is the only one that could catch a hook that
 * stopped declaring it.
 *
 * <p>Three cases pin where §7's row — {@code | ToolBar | TOOL_BAR | — | — | |} — is wrong or
 * silent against the source. The axis is a fact the class fixes and not one read from the box, and
 * the box is whatever the parent granted. The bar does not clip while its layout keeps walking its
 * cursor, so an over-subscribed bar publishes item boxes outside its own rectangle and still on
 * screen. And mirroring moves the placed coordinate only, so tree order stays the order items were
 * added in and goes on matching the Tab order, which §11 defines it to equal.
 *
 * <p>Everything below drives the bar's own public API, and Widget's, on a bound scene, and reads
 * back the tree the scene published. Nothing constructs a node.
 */
class ToolBarAccessibilityTest extends AccessibleComponentTestBase {

    private static final float EPS = 0.01f;

    /**
     * A leaf of a fixed size carrying an application-supplied name: an item that is neither a tab
     * stop nor a painter, so that what a case asserts is the bar's and not a button's.
     */
    private static final class Box extends Widget {
        private final float w;
        private final float h;

        Box(String name, float w, float h) {
            setAccessibleName(name);
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
        }
    }

    private ToolBar bar;

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
    void theBarIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
                        // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0]. Read the message here and the assertion passes
            // whatever the walk does, which is what it did until a verification read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.ToolBar"),
                    "the bar paints, and it declares a role, so the walk must never say it paints "
                            + "and is deleted; the warning names a toolkit class an application "
                            + "cannot correct, and the flag it recommends would take every control "
                            + "in the bar out of the tree: " + subject + " " + record.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A bar holding {@code items}, inside a column that keeps it at its natural size. The column
     * declares nothing and is deleted, so the bar's node hangs off the window.
     *
     * @param items what to add, in order
     */
    private void bindBar(Widget... items) {
        bar = new ToolBar();
        for (Widget item : items) {
            bar.addItem(item);
        }
        Column root = new Column();
        root.add(bar);
        bind(root);
    }

    /** @return the bar's node, which is the one and only tool bar in the tree */
    private AccessibleNode strip() {
        return node(Accessible.Role.TOOL_BAR);
    }

    /** @return the resolved step's tokens, which every geometric expectation below is built from */
    private SizeTokens tokens() {
        return SizeTokens.of(bar.controlSize());
    }

    /**
     * @param nodes the nodes to read
     * @return their names, in the order given
     */
    private static List<String> namesOf(List<AccessibleNode> nodes) {
        List<String> names = new ArrayList<>();
        for (AccessibleNode node : nodes) {
            names.add(node.name());
        }
        return names;
    }

    /** @return every node's identifier, in tree order */
    private List<Long> identities() {
        List<Long> ids = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            ids.add(tree.node(i).id());
        }
        return ids;
    }

    // ------------------------------------------------------------------ the shape of the tree

    /**
     * One node, the right role, the right axis, and the items beneath it in the order they were
     * added.
     *
     * <p>The bar declares nothing else: no name, no description, no facet and no verb — not even
     * the walk's focus verbs, because the class never makes itself focusable. The divider is the
     * bar's own {@link ToolBar#addSeparator()}, and it arrives as a real {@code SEPARATOR} node on
     * its own axis rather than as anything this bar said, which is what a reader needs to hear
     * between two groups of commands.
     */
    @Test
    void aBarIsOneHorizontalToolBarWhoseChildrenAreItsItemsInAddOrder() {
        bar = new ToolBar();
        bar.addItem(new Box("Cut", 40, 24));
        bar.addSeparator();
        bar.addItem(new Box("Paste", 40, 24));
        Column root = new Column();
        root.add(bar);
        bind(root);

        AccessibleNode node = strip();
        assertEquals(0, node.parent(),
                "the column is transparent, so the bar hangs off the window" + describe(tree()));
        assertEquals("", node.name(), "the bar names itself nothing" + describe(tree()));
        assertEquals("", node.description(), describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "the class never makes itself focusable" + describe(tree()));
        assertNull(node.actions(), "and so it offers no verb at all" + describe(tree()));
        assertNull(node.selection(), "a tool bar has no selected item" + describe(tree()));
        assertNull(node.scroll(), "and no viewport" + describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertTrue(node.relations().isEmpty(),
                "the bar holds no reference to a thing it controls" + describe(tree()));

        List<AccessibleNode> items = childrenOf(node);
        assertEquals(3, items.size(), describe(tree()));
        assertEquals("Cut", items.get(0).name(), describe(tree()));
        assertEquals(Accessible.Role.SEPARATOR, items.get(1).role(),
                "the divider the bar built describes itself" + describe(tree()));
        assertTrue(items.get(1).has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals("Paste", items.get(2).name(), describe(tree()));
    }

    /**
     * A bar with nothing in it is still one tool bar node with the bar's own box.
     *
     * <p>The role is unconditional, so an application that builds its strip and fills it later
     * never has the node appear and disappear underneath a reader.
     */
    @Test
    void anEmptyBarIsStillOneToolBarNode() {
        bindBar();

        AccessibleNode node = strip();
        assertEquals(AccessibleNode.NONE, node.firstChild(), describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertEquals(bar.localToSceneX(), node.x(), EPS, describe(tree()));
        assertEquals(bar.localToSceneY(), node.y(), EPS);
        assertEquals(bar.width(), node.width(), EPS);
        assertEquals(bar.height(), node.height(), EPS);
    }

    // ---------------------------------------------------------- where the survey's row was wrong

    /**
     * The axis is the class's and never the laid-out box's.
     *
     * <p>{@code onMeasure} ends in {@code constraints.constrain(...)}, so the bar is whatever its
     * parent granted: squeezed into a tall, narrow box it is taller than it is wide while still
     * laying its items out in a single run along x. A hook that inferred the orientation from the
     * geometry would announce this bar vertical, which is the mistake §7's separator row made and
     * the reason this case exists.
     *
     * <p>The squeeze comes from a fixed box rather than from binding the bar as the scene root:
     * the root is measured tight to the window, and this suite's window is wider than it is tall,
     * so the root case cannot tell a class-fixed axis from an inferred one.
     */
    @Test
    void theAxisIsTheClassAndNotTheBox() {
        bar = new ToolBar();
        bar.addItem(new Box("Cut", 40, 24));
        bar.addItem(new Box("Paste", 40, 24));
        Column root = new Column();
        root.add(new SizedBox(48, 200, bar));
        bind(root);

        assertTrue(bar.height() > bar.width(),
                "the fixed box really squeezed the bar taller than wide: "
                        + bar.width() + "x" + bar.height());
        AccessibleNode node = strip();
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "a squeezed bar is still a horizontal one" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
    }

    /**
     * Tree order is the order items were added in, in both directions, and the boxes are the ones
     * the bar placed.
     *
     * <p>Mirroring reflects the placed coordinate and leaves the cursor walk alone, so the first
     * item added sits at the right-hand end while {@code children()} — and therefore tree order,
     * and therefore the Tab order the scene collects — is unchanged. §11 defines reading order to
     * equal the Tab order; re-ordering the nodes to match a right-to-left picture would break that
     * on the one widget whose own documentation is mostly about mirroring.
     */
    @Test
    void treeOrderIsAddOrderAndOnlyTheBoxesMirror() {
        Button first = new Button("First");
        Button second = new Button("Second");
        Button third = new Button("Third");
        bar = new ToolBar();
        bar.addItem(first);
        bar.addItem(second);
        bar.addItem(third);
        bar.setLayoutDirection(LayoutDirection.RTL);
        Column root = new Column();
        root.add(bar);
        bind(root);

        List<AccessibleNode> items = childrenOf(strip());
        assertEquals(List.of("First", "Second", "Third"), namesOf(items),
                "the tree is the order they were added in" + describe(tree()));
        assertTrue(items.get(0).x() > items.get(2).x(),
                "and the boxes really did mirror" + describe(tree()));

        Widget[] widgets = {first, second, third};
        for (int i = 0; i < widgets.length; i++) {
            assertEquals(widgets[i].localToSceneX(), items.get(i).x(), EPS, describe(tree()));
            assertEquals(widgets[i].width(), items.get(i).width(), EPS, describe(tree()));
        }

        for (Widget expected : widgets) {
            scene.focusTraverse(false);
            assertSame(expected, scene.focusedWidget(),
                    "Tab visits the items in tree order right to left as well");
        }
    }

    /**
     * An over-subscribed bar publishes item boxes outside its own rectangle, and they are still on
     * screen.
     *
     * <p>{@code onMeasure} clamps the bar's width to what the parent granted while {@code onLayout}
     * keeps walking its cursor, and the class never clips, so an item can be placed past the bar's
     * own right edge. {@code isShowing()} intersects clipping ancestors only, so it stays true —
     * truthfully, because nothing trims the paint either. §7.2 left "every row's bounds claim" open
     * and this is the answer for this widget: the tree reports where the items are, and does not
     * guess at overflow.
     */
    @Test
    void anOverSubscribedBarPublishesBoxesOutsideItselfAndStillShowing() {
        bar = new ToolBar();
        bar.addItem(new Box("Cut", 80, 24));
        bar.addItem(new Box("Paste", 80, 24));
        Column root = new Column();
        root.add(new SizedBox(100, 60, bar));
        bind(root);

        AccessibleNode node = strip();
        AccessibleNode last = node("Paste");
        assertTrue(last.x() + last.width() > node.x() + node.width(),
                "the last item really did run past the bar's own edge" + describe(tree()));
        assertTrue(last.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(last.has(Accessible.State.SHOWING),
                "nothing clips it, so it is on screen and is published as such" + describe(tree()));
    }

    // --------------------------------------------------------------- the divider the bar builds

    /**
     * The separator the bar built is its own node across the inner band, and the inset the bar
     * pushes onto it is ink.
     *
     * <p>{@link ToolBar#addSeparator()} constructs a real {@link Separator} and tracks it so that
     * measure can keep its inset in step with the step; the inset trims the painted line's ends and
     * changes nothing published, so the node's box is the whole band between the bar's paddings.
     * The bar says nothing else about it — no name, no place in a set, not selected — because a
     * tool bar is not a selection container and a divider is not a member of one.
     */
    @Test
    void theBarBuiltSeparatorIsItsOwnNodeAcrossTheInnerBandAndTheInsetIsNotInIt() {
        Box first = new Box("Cut", 40, 24);
        bar = new ToolBar();
        bar.addItem(first);
        bar.addSeparator();
        bar.addItem(new Box("Paste", 40, 24));
        Column root = new Column();
        root.add(bar);
        bind(root);

        AccessibleNode node = strip();
        AccessibleNode rule = node(Accessible.Role.SEPARATOR);
        float pad = tokens().toolBarPad();
        float gap = tokens().toolBarGap();
        assertEquals(node.height() - 2 * pad, rule.height(), EPS,
                "the rule spans the band between the bar's paddings" + describe(tree()));
        assertEquals(node.y() + pad, rule.y(), EPS, describe(tree()));
        assertEquals(node.x() + pad + first.width() + gap, rule.x(), EPS, describe(tree()));

        Separator built = (Separator) bar.children().get(1);
        assertTrue(built.inset() > 0,
                "the bar really did push its step's inset onto the rule: " + built.inset());
        assertEquals(built.height(), rule.height(), EPS,
                "and the inset is ink: it is nowhere in the published box");
        assertEquals("", rule.name(), "the bar says nothing about it" + describe(tree()));
        assertNull(rule.selectionItem(), describe(tree()));
        assertFalse(rule.has(Accessible.State.SELECTED), describe(tree()));

        long id = rule.id();
        bridge.events.clear();
        scene.setControlSize(ControlSize.XLARGE);
        frame();

        AccessibleNode moved = node(Accessible.Role.SEPARATOR);
        assertEquals(id, moved.id(), "the step is not the rule's identity" + describe(tree()));
        assertNotEquals(rule.x(), moved.x(), "and the step really did move it" + describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the move is what a reader is told about: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    // ------------------------------------------------------------------------------- identity

    /**
     * A layout token moves the boxes and rebuilds nothing.
     *
     * <p>Both of the bar's layout knobs — the explicit gap and the inherited step — change every
     * item's rectangle and nothing about the tree's shape. Identity is minted over the widget tree,
     * so a reader's cursor resting on an item survives a density change, which is exactly the
     * moment it would be lost if the tree were re-keyed by geometry.
     */
    @Test
    void theGapAndTheStepMoveBoxesAndNeverRebuildTheTree() {
        bindBar(new Box("Cut", 40, 24), new Box("Paste", 40, 24));
        List<Long> before = identities();
        bridge.events.clear();

        bar.gap(tokens().toolBarGap() + 12);
        frame();

        assertEquals(before, identities(), "a gap is not identity" + describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "and it did move the items: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);

        bridge.events.clear();
        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(before, identities(), "nor is the density" + describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0, "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }

    // ------------------------------------------- the ways an application names or strikes it out

    /**
     * Naming is the application's, and striking the bar out takes its items with it.
     *
     * <p>A tooltip names the node through the walk's free default; an explicit name wins and
     * demotes the tooltip to the description. The hook declares no name of its own, which would
     * both be spoken over the role and shadow the application's.
     *
     * <p>Ignoring is the trap worth pinning, because it does not behave like transparency: a
     * deleted container hoists its children, an ignored one deletes them, so the flag §1.6's paint
     * warning would have recommended for this class removes every command in the bar.
     */
    @Test
    void namingIsTheApplicationsAndIgnoringTakesTheItemsWithIt() {
        bindBar(new Box("Cut", 40, 24));

        bar.setTooltip("Formatting");
        frame();
        AccessibleNode byTooltip = strip();
        assertEquals("Formatting", byTooltip.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, byTooltip.nameFrom(), describe(tree()));

        bar.setAccessibleName("Text formatting");
        frame();
        AccessibleNode explicit = strip();
        assertEquals("Text formatting", explicit.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, explicit.nameFrom(), describe(tree()));
        assertEquals("Formatting", explicit.description(),
                "the tooltip becomes the description once something else named it"
                        + describe(tree()));

        bar.setAccessibleIgnored(true);
        frame();
        assertThrows(AssertionError.class, this::strip,
                "an ignored bar is not in the tree" + describe(tree()));
        assertThrows(AssertionError.class, () -> node("Cut"),
                "and neither is anything it held: ignoring a container deletes its subtree, where "
                        + "transparency would have hoisted it" + describe(tree()));
    }

    // ---------------------------------------------------------------- what the walk adds and not

    /**
     * A disabled bar publishes its items disabled and grows no skeleton around them.
     *
     * <p>§1.6's predicate reads the facts a widget declared for itself and never the bits carried
     * down the walk, so disabling the strip does not materialise a group for the column above it
     * or for anything else that was deleted. What changes is the items: they lose {@code ENABLED},
     * they stop being tab stops, and the walk's focus verbs go with that — while each keeps its own
     * role and its own verb, so a reader hears a disabled command rather than an absent one.
     */
    @Test
    void aDisabledBarPublishesItsItemsDisabledAndGrowsNoSkeleton() {
        Button save = new Button("Save");
        bindBar(save, new Box("Cut", 40, 24));
        int nodes = tree().nodeCount();

        bar.setEnabled(false);
        frame();

        AccessibleNode node = strip();
        assertFalse(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "and it is still a horizontal tool bar" + describe(tree()));

        AccessibleNode button = node("Save");
        assertFalse(button.has(Accessible.State.ENABLED), describe(tree()));
        assertFalse(button.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertFalse(button.actions().has(Accessible.Action.FOCUS),
                "a disabled item is not a tab stop, so the walk offers no focus verb"
                        + describe(tree()));
        assertTrue(button.actions().has(Accessible.Action.PRESS),
                "its own verb is its own business" + describe(tree()));
        assertEquals(nodes, tree().nodeCount(),
                "a disabled bar grows no group around anything" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    /**
     * A damaged frame that changes nothing costs nothing to describe.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor of its own, and the bar's own paint sits on both sides of the
     * comparison. What is claimed is the difference, which is two primitive writes into the
     * builder's reused slot. A hook that formatted anything — an item count, the word "Tool bar" —
     * would allocate one string per damaged frame to conclude that nothing had moved, and would be
     * invisible everywhere except here.
     */
    @Test
    void aDamagedFrameThatChangesNothingCostsNothingToDescribe() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bar = new ToolBar();
        bar.addItem(new Box("Cut", 40, 24));
        bar.addSeparator();
        bar.addItem(new Box("Paste", 40, 24));
        Column root = new Column();
        root.add(bar);
        bind(root);
        int published = bridge.published.size();
        bridge.events.clear();

        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            bar.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            bar.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a bar is two primitive writes and must cost no memory at all");
    }
}
