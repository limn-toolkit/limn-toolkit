package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an {@link Expanded} becomes in the accessible tree, which is nothing, and what survives it
 * being nothing.
 *
 * <p>The class is a weight, a floor, one child, an {@code onMeasure} and an {@code onLayout}. It
 * declares no role, no name, no description, no action and no state; it declares no
 * {@code onPaint}, so the paints-and-says-nothing warning stays silent and this is not the
 * {@code BackdropPanel} case; and it is never focusable of its own accord. So ADR 039 §1.6's
 * predicate deletes it and hoists its one child into the row's own place, and that is the whole of
 * the answer with no code in the class.
 *
 * <p><b>It is the one wrapper whose deletion leaves no coordinate behind at all.</b>
 * {@code onLayout} hands the child {@code (0, 0, width(), height())}, so the control's published
 * box <em>is</em> the deleted node's box on both axes, in every cross-alignment mode and mirrored
 * or not. {@code Padding} leaves an inset behind and a row leaves a gap and an alignment; this
 * leaves nothing to leave. That is why the step is cheap, and it is the opposite reason from the
 * one that makes the rest of §7's scaffolding row cheap, which the shared cell hides.
 *
 * <p><b>What does outlive it is the number a reader is told for the control's width.</b> The
 * resolved share is a <em>tight</em> main-axis constraint, so a control that measured itself at one
 * hundred and sixteen points and was granted thirty-five publishes thirty-five: the boxes are not
 * deleted, one wrapper class decides them, and §7's "the tree is the controls, not the boxes" is
 * more misleading here than anywhere else in that row. {@code atLeast} is the only thing that makes
 * it publish otherwise — the floored control keeps its chrome and an unfloored sibling gives up the
 * points — and floors that cannot all fit over-subscribe the row, whose children then publish boxes
 * outside the container and are still {@code SHOWING}, because a {@link Flex} does not clip. That is
 * §7.2's open bounds claim answered for this widget, in the same direction {@code Column} answered
 * it.
 *
 * <p><b>The spacer is the shape the survey's row does not admit.</b>
 * {@link Expanded#spacer(int)} holds no child, so "transparent, children hoisted" degenerates to
 * nothing at all: flexible whitespace is absent from the tree rather than published as an empty
 * group between every pair of toolbar controls.
 *
 * <p><b>Transparency is per instance, and here the hatch is the same rectangle as the control.</b>
 * Widget's naming surface is public and the walk applies the overrides before it evaluates the
 * predicate, so a name, a role or a tooltip on one materialises a {@code GROUP} — over a rectangle
 * identical to the wrapped control's, which is worth writing down before somebody reads it as a
 * bug. {@code setFocusable} alone is not a fourth verb: it supplies nothing to publish, so the node
 * survives as {@code UNKNOWN} and unnamed, which is the defect the walk warns about and the
 * precedent {@code PaddingAccessibilityTest} pins. {@code setAccessibleIgnored(true)} is the trap:
 * on a wrapper it takes the control being wrapped out of the tree with it, and it is harmless only
 * on a spacer, which has nothing to lose. The class is {@code final}, so all of this is per
 * instance and no subclass can inherit anything.
 *
 * <p><b>The finding later steps depend on: a component cannot describe a control it wrapped.</b>
 * {@code onAccessibilityChild} is asked of the walk's caller, which is the <em>widget</em> parent,
 * and for a wrapped control that is the {@code Expanded} rather than the component that built it.
 * So the owner is never asked, cannot annotate, cannot add a role and — the sharp edge — cannot use
 * §1.3's parent-chosen key path. {@code ColorPicker}'s field, hex field, alpha rail and channel
 * track and {@code MediaControls}' scrub slider are all wrapped this way, so their own steps have to
 * plan on the control describing itself or on {@code setAccessible*} called on the control. Nothing
 * is forwarded here: a hook on a class settled as declaring nothing would be new machinery for a
 * need nobody has demonstrated, and the last test below is where that decision gets revisited rather
 * than discovered.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins;
 * the inheritance of the disabled and hidden bits through deleted scaffolding, which
 * {@code AccessibleFocusOrderTest} pins on the mechanism — the two below ask only what is this
 * widget's own, which is what the row does with the share when a wrapper goes away; the
 * paints-and-says-nothing seam, for which this widget is the silent case; per-frame allocation on
 * the drop path, which {@code AccessiblePublishCostTest} and {@code AccessibleIdleCostTest} own;
 * {@code Flex}'s measure-count budget, which {@code FlexMeasureCountTest} owns; and the bookkeeping
 * that this class is settled as transparent and still says nothing, which is
 * {@code AccessibleCoverageTest}'s own two tests.
 *
 * <p>Everything below drives {@code Expanded}'s factories, {@code atLeast} and Widget's public
 * setters on a bound scene; nothing constructs a node.
 */
class ExpandedAccessibilityTest extends AccessibleTestBase {

    private static Probe probe(String name, float width) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefWidth = width;
        return probe;
    }

    /** Binds {@code row} inside a box of exactly this size, for the tests that need a cross axis. */
    private void bindSized(float width, float height, Row row) {
        Group box = new Group();
        box.add(new SizedBox(width, height, row));
        bind(box);
        frame();
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    private void assertCoincides(Expanded wrapper, AccessibleNode child, String why) {
        assertEquals(wrapper.localToSceneX(), child.x(), why + ": x");
        assertEquals(wrapper.localToSceneY(), child.y(), why + ": y");
        assertEquals(wrapper.width(), child.width(), why + ": width");
        assertEquals(wrapper.height(), child.height(), why + ": height");
    }

    private void assertNoNamelessGroup(AccessibleTree tree) {
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group between the window and the controls is the scaffolding this "
                            + "widget exists to not publish: " + describe(tree));
        }
    }

    /**
     * The deletion itself: a squeezed control is a window and the control, with no wrapper in
     * between.
     *
     * <p>Anything that gave this class a role, a state or a hook — or gave one to {@code Flex}
     * above it, which the transparency check walks through — would re-materialise a box between the
     * window and very nearly every squeezed control in the toolkit at once, by inheritance.
     */
    @Test
    void theWrapperIsNoNodeAndTheControlItHoldsHoistsIntoTheRowsPlace() {
        Row row = new Row();
        row.add(probe("a", 40));
        row.add(Expanded.of(probe("b", 40)));
        bind(row);
        frame();

        AccessibleTree tree = tree();
        assertEquals(3, tree.nodeCount(),
                "a window and two buttons, and no wrapper in between: " + describe(tree));
        assertEquals(List.of("a", "b"), readingOrder(), describe(tree));
        assertEquals(0, node("a").parent(), "both hoist into the window's place");
        assertEquals(0, node("b").parent());
        assertNoNamelessGroup(tree);
    }

    /**
     * The whole of what the deletion leaves behind, which is no coordinate: the control's published
     * rectangle is the wrapper's rectangle, exactly, on both axes and in every cross-alignment mode
     * and both directions.
     *
     * <p>A wrapper that ever inset, centred or padded its child would move a control's box away
     * from where it paints with nothing in the tree left to say so, and every layout assertion in
     * the suite would stay green because the wrapper's own box is unchanged. The mirrored pass is
     * the other half: nothing in this class reads the layout direction, so a later right-to-left
     * sweep that "added mirroring here" would move every squeezed control off its painted position
     * for a mirrored reader alone.
     */
    @Test
    void theWrappedControlsBoxIsTheWrappersBoxOnBothAxes() {
        Expanded wrapper = Expanded.of(probe("flex", 40));
        Row row = new Row();
        row.add(probe("fixed", 40));
        row.add(wrapper);
        bindSized(200, 60, row);

        assertEquals(40f, node("flex").x(), "the fixed sibling's right edge");
        assertEquals(160f, node("flex").width(), "and the leftover, which is the share");
        assertEquals(0f, node("flex").y());
        assertEquals(20f, node("flex").height(), "the control's own cross measure passes through");
        assertCoincides(wrapper, node("flex"), "cross START");

        row.crossAlignment(Flex.CrossAlignment.CENTER);
        frame();
        assertEquals(20f, node("flex").y(), "centred in a sixty-point row");
        assertCoincides(wrapper, node("flex"), "cross CENTER");

        row.crossAlignment(Flex.CrossAlignment.END);
        frame();
        assertEquals(40f, node("flex").y());
        assertCoincides(wrapper, node("flex"), "cross END");

        row.crossAlignment(Flex.CrossAlignment.STRETCH);
        frame();
        assertEquals(60f, node("flex").height(), "a stretched wrapper stretches what it holds");
        assertCoincides(wrapper, node("flex"), "cross STRETCH");

        row.crossAlignment(Flex.CrossAlignment.START);
        row.setLayoutDirection(LayoutDirection.RTL);
        frame();
        assertEquals(0f, node("flex").x(), "the main axis reflected and the wrapper went with it");
        assertEquals(160f, node("fixed").x());
        assertCoincides(wrapper, node("flex"), "mirrored, which is still the same rectangle");
    }

    /**
     * The share is the published width, and it is not what the control asked for.
     *
     * <p>This is the surprising thing the class exists to do, read from the tree instead of from a
     * {@code Size}: the share is a tight constraint, so a control measured at one hundred and
     * sixteen points and granted thirty-five is thirty-five points wide to a reader, exactly as it
     * is to paint. Publishing a child's measured size instead of its laid-out box would make the
     * tree disagree with the screen precisely where the layout is doing the surprising thing.
     */
    @Test
    void theShareIsThePublishedWidthAndNotWhatTheControlAskedFor() {
        Probe wide = probe("value", 116);
        Row row = new Row();
        row.add(probe("label", 165));
        row.add(Expanded.of(wide));
        bind(row);
        frame();

        assertEquals(35f, wide.width(), "thirty-five is what the split left it");
        assertEquals(35f, node("value").width(), "and the tree says what paint says");
        assertEquals(165f, node("value").x());
    }

    /**
     * A floor survives as the published width, and the sibling gives up the points.
     *
     * <p>Floors are resolved before weight, and the loser is whoever declared none: that is the
     * arrangement a floored stepper beside an ellipsising label depends on. Resolving them after the
     * split, or honouring them in the measure pass and not in the layout pass, would silently shrink
     * the control the floor exists to protect and leave the tree agreeing with the wrong one.
     */
    @Test
    void aFloorSurvivesAsThePublishedWidthAndTheSiblingGivesUpThePoints() {
        Row row = new Row();
        row.add(Expanded.of(probe("label", 40)));
        row.add(Expanded.of(probe("stepper", 40)).atLeast(116));
        bind(row);
        frame();

        assertEquals(116f, node("stepper").width(),
                "the floor beats the hundred an even split would have given it");
        assertEquals(84f, node("label").width(), "and the unfloored sibling absorbs the whole loss");
        assertEquals(0f, node("label").x());
        assertEquals(84f, node("stepper").x());
    }

    /**
     * Floors that cannot all fit over-subscribe the row, and the tree publishes boxes outside the
     * container rather than tidying them away.
     *
     * <p>{@code atLeast}'s documented outcome is that everybody keeps their floor and the row
     * overflows, because a {@link Flex} neither clips nor wraps. So the honest report is a
     * rectangle past the container's edge that is still {@code SHOWING}, since
     * {@code Widget#isShowing} walks clipping ancestors and finds none. A clip invented for
     * {@code Flex}, or a {@code SHOWING} derived from the parent's rectangle instead of from that
     * walk, would start lying in the opposite direction — and either would put a reader's cursor
     * where the control is not. This is §7.2's bounds claim settled for this widget.
     */
    @Test
    void overSubscribedFloorsPublishBoxesOutsideTheContainerAndStillShowing() {
        Row row = new Row();
        row.add(Expanded.of(probe("a", 40)).atLeast(150));
        row.add(Expanded.of(probe("b", 40)).atLeast(150));
        bind(row);
        frame();

        assertEquals(200f, row.width(), "the row is two hundred and the floors want three");
        assertEquals(0f, node("a").x());
        assertEquals(150f, node("a").width(), "nobody is squeezed under a floor");
        assertEquals(150f, node("b").x());
        assertEquals(150f, node("b").width());
        assertTrue(node("b").x() + node("b").width() > row.width(),
                "the second one really is outside the container: " + node("b").bounds());
        for (String name : List.of("a", "b")) {
            AccessibleNode node = node(name);
            assertTrue(node.width() >= 0 && node.height() >= 0,
                    "a negative rectangle is unrecoverable at the bridge: " + node.bounds());
            assertTrue(node.has(Accessible.State.SHOWING),
                    "a row does not clip, so nothing here is hidden: " + describe(tree()));
        }
    }

    /**
     * The spacer publishes nothing at all: it is dropped, and there is nothing to hoist in its
     * place either.
     *
     * <p>§1.6's "removed and its children hoisted" degenerates to "removed", because the factory
     * holds no child to lift. A childless wrapper that ever survived the predicate would inject an
     * empty unnamed group between every pair of controls in a toolbar, which is where flexible
     * whitespace is used most.
     */
    @Test
    void aSpacerPublishesNothingAtAll() {
        Row bare = new Row();
        bare.add(probe("a", 40));
        bare.add(probe("b", 40));
        bind(bare);
        frame();
        int withoutSpacer = tree().nodeCount();
        List<String> orderWithout = readingOrder();
        assertEquals(40f, node("b").x());

        Expanded spacer = Expanded.spacer(1);
        Row spaced = new Row();
        spaced.add(probe("a", 40));
        spaced.add(spacer);
        spaced.add(probe("b", 40));
        bind(spaced);
        frame();

        assertEquals(0, spacer.children().size(), "the spacer factory holds no child");
        assertEquals(withoutSpacer, tree().nodeCount(),
                "flexible whitespace costs a node in neither direction: " + describe(tree()));
        assertEquals(orderWithout, readingOrder(), "and does not come between the two controls");
        assertEquals(160f, node("b").x(), "it is a gap in the layout and nothing in the tree");
        assertNoNamelessGroup(tree());
    }

    /**
     * A floor set to the value it already had publishes nothing, and a negative one clamps onto the
     * zero it already had.
     *
     * <p>{@code atLeast} carries an equality guard, and it is the guard rather than the walk that
     * keeps this cheap: without it a call site re-applying its floor would buy a layout pass and a
     * full tree walk to conclude that nothing moved, sixty times a second. The clamp is the second
     * half — a negative floor is not a different floor.
     */
    @Test
    void aFloorSetToTheValueItAlreadyHadPublishesNothing() {
        Expanded wrapper = Expanded.of(probe("a", 40));
        Row row = new Row();
        row.add(wrapper);
        row.add(Expanded.of(probe("b", 40)));
        bind(row);
        frame();
        assertEquals(100f, node("a").width(), "an even split to start with");
        bridge.clear();

        wrapper.atLeast(150);
        frame();
        assertEquals(150f, node("a").width(), "the floor really did move something");
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        bridge.clear();

        wrapper.atLeast(150);
        frame();
        assertTrue(bridge.published.isEmpty(), "the same floor is not a change, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        wrapper.atLeast(0);
        frame();
        assertEquals(100f, node("a").width(), "and it lets go again");
        bridge.clear();

        wrapper.atLeast(-5);
        frame();
        assertTrue(bridge.published.isEmpty(), "a negative floor clamps onto the zero it had");
        assertTrue(bridge.events.isEmpty(), "" + bridge.events);
    }

    /**
     * Changing a floor moves the controls and does not re-key them.
     *
     * <p>A re-key here is a screen reader losing every element it is holding because a row got
     * tighter, which is a property change the user never made. Identity is minted over the widget
     * tree and owes nothing to a coordinate, and this is that rule reached through the one class
     * whose whole purpose is to change coordinates.
     */
    @Test
    void changingTheFloorMovesTheControlsWithoutRekeyingThem() {
        Expanded wrapper = Expanded.of(probe("b", 40));
        Row row = new Row();
        row.add(Expanded.of(probe("a", 40)));
        row.add(wrapper);
        bind(row);
        frame();
        long first = node("a").id();
        long second = node("b").id();
        bridge.clear();

        wrapper.atLeast(150);
        frame();

        assertEquals(50f, node("a").width(), "the unfloored one gave up the points");
        assertEquals(50f, node("b").x());
        assertEquals(150f, node("b").width());
        assertEquals(first, node("a").id(), "identity is not a coordinate");
        assertEquals(second, node("b").id());
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * Hiding a wrapper hides the control that outlives it, and hands its share to the survivors.
     *
     * <p>The transparent branch computes the inherited bits on the widget being dropped and passes
     * them into the children it hoists; computing them after the drop, or passing the caller's,
     * would publish a hidden control as visible with no node anywhere left to explain it. What is
     * this widget's own rather than the mechanism's is the second half: an invisible wrapper leaves
     * the split altogether, so the sibling's published width grows by the whole of the missing
     * share, with nothing in the tree to say why. Its own coordinates are deliberately not asserted
     * — layout skips an invisible child, so the box it keeps is the one it last had, and the state
     * rather than the geometry is what says it is gone.
     */
    @Test
    void hidingAWrapperHidesTheControlAndRedividesTheRow() {
        Expanded wrapper = Expanded.of(probe("a", 40));
        Row row = new Row();
        row.add(wrapper);
        row.add(Expanded.of(probe("b", 40)));
        bind(row);
        frame();
        assertEquals(100f, node("b").width());
        bridge.clear();

        wrapper.setVisible(false);
        frame();

        AccessibleNode gone = node("a");
        assertFalse(gone.has(Accessible.State.VISIBLE),
                "the control the wrapper held is hidden with it: " + describe(tree()));
        assertFalse(gone.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(200f, node("b").width(),
                "and the survivor takes the whole row, which no node accounts for");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "hidden is a state, not a removal: " + bridge.events);
    }

    /**
     * Disabling a wrapper disables the control, moves nothing, and grows no skeleton.
     *
     * <p>This is §1.6's disabled-form clause reached through a wrapper, and the contrast with the
     * test above is the part that belongs to this widget: the split reads visibility and not
     * enablement, so a disabled wrapper keeps its weight and every box in the row stays where it
     * was. A predicate that looked at the inherited bits rather than at the widget's own
     * declarations would materialise a grouping node per squeezed control the moment a form was
     * disabled.
     */
    @Test
    void disablingAWrapperDisablesTheControlAndGrowsNoSkeleton() {
        Expanded wrapper = Expanded.of(probe("a", 40));
        Row row = new Row();
        row.add(wrapper);
        row.add(Expanded.of(probe("b", 40)));
        bind(row);
        frame();
        int before = tree().nodeCount();

        wrapper.setEnabled(false);
        frame();

        assertFalse(node("a").has(Accessible.State.ENABLED),
                "a control inside a disabled wrapper is disabled: " + describe(tree()));
        assertTrue(node("a").has(Accessible.State.VISIBLE), "disabled is not hidden");
        assertEquals(100f, node("a").width(), "and the split does not read enablement");
        assertEquals(100f, node("b").width());
        assertEquals(before, tree().nodeCount(),
                "disabling must not materialise the scaffolding: " + describe(tree()));
        assertNoNamelessGroup(tree());
    }

    /**
     * The escape hatch, and the one thing that makes it unlike every other wrapper's: the node an
     * application materialises here describes exactly the rectangle the control underneath already
     * does.
     *
     * <p>The overrides are applied before the predicate is evaluated, so this is the supported way
     * to put a named region over a squeezed control — and on a spacer the same call produces a named
     * childless group over the empty flexible region, which is the only way that region is ever in
     * the tree. Both are supported rather than leaks. An {@code applyOverrides} that ran after the
     * predicate would leave the documented route silently doing nothing.
     */
    @Test
    void namingAWrapperMaterialisesABoxCoextensiveWithTheControl() {
        Expanded wrapper = Expanded.of(probe("ok", 40));
        Row row = new Row();
        row.add(probe("fixed", 40));
        row.add(wrapper);
        bind(row);
        frame();
        long control = node("ok").id();

        wrapper.setAccessibleName("Volume");
        frame();

        AccessibleNode named = node("Volume");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(wrapper.localToSceneX(), named.x(), "the wrapper's own box");
        assertEquals(wrapper.width(), named.width());
        AccessibleNode inside = node("ok");
        assertEquals(named.x(), inside.x(),
                "which is the control's box too, with no gutter between them: " + describe(tree()));
        assertEquals(named.y(), inside.y());
        assertEquals(named.width(), inside.width());
        assertEquals(named.height(), inside.height());
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(control, inside.id(), "naming the wrapper is not renaming the control");

        wrapper.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(control, node("ok").id());

        Expanded spacer = Expanded.spacer(1);
        Row withSpacer = new Row();
        withSpacer.add(probe("a", 40));
        withSpacer.add(spacer);
        bind(withSpacer);
        frame();
        spacer.setTooltip("Filler");
        frame();

        AccessibleNode filler = node("Filler");
        assertEquals(Accessible.Role.GROUP, filler.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, filler.nameFrom());
        assertEquals(AccessibleNode.NONE, filler.firstChild(),
                "a named spacer is a node with nothing in it: " + describe(tree()));
    }

    /**
     * Focusing one alone is not a fourth verb, and this is what it really does.
     *
     * <p>A name, a role and a tooltip each supply something the node can be published as;
     * {@code setFocusable} supplies nothing, so the wrapper survives on the predicate's focusable
     * branch with nothing to say and is published as {@code UNKNOWN} and unnamed, which §12.1
     * refuses on both counts. What this pins is that the node is not dropped: an unreachable tab
     * stop absent from the tree would be a defect a reader cannot even report.
     */
    @Test
    void focusingAWrapperAloneMaterialisesANodeThatIsNotYetSayable() {
        Expanded wrapper = Expanded.of(probe("ok", 40));
        Row row = new Row();
        row.add(wrapper);
        bind(row);
        frame();

        wrapper.setFocusable(true);
        frame();

        AccessibleNode unknown = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.UNKNOWN) {
                unknown = tree().node(i);
            }
        }
        assertNotNull(unknown,
                "the predicate's focusable branch keeps it: " + describe(tree()));
        assertEquals("", unknown.name(),
                "but nothing named it, and focus is not a name: " + describe(tree()));
    }

    /**
     * The trap beside the hatch: ignoring a wrapper takes the control it holds out of the tree with
     * it, and ignoring a spacer removes nothing because there is nothing to remove.
     *
     * <p>The walk returns before it reaches the children, which is exactly what "ignored" means and
     * exactly why §1.6 refuses it for anything operable. Pinned so that the cost is written down
     * before somebody reaches for the flag on a wrapper the way {@code BackdropPanel} was nearly
     * advised to.
     */
    @Test
    void ignoringAWrapperTakesTheControlItHoldsWithIt() {
        Expanded wrapper = Expanded.of(probe("b", 40));
        Row row = new Row();
        row.add(probe("a", 40));
        row.add(wrapper);
        bind(row);
        frame();
        assertEquals(3, tree().nodeCount());

        wrapper.setAccessibleIgnored(true);
        frame();

        assertEquals(2, tree().nodeCount(),
                "the control went with the wrapper: " + describe(tree()));
        assertEquals(List.of("a"), readingOrder(), describe(tree()));

        Expanded spacer = Expanded.spacer(1);
        Row withSpacer = new Row();
        withSpacer.add(probe("a", 40));
        withSpacer.add(spacer);
        bind(withSpacer);
        frame();
        int before = tree().nodeCount();

        spacer.setAccessibleIgnored(true);
        frame();

        assertEquals(before, tree().nodeCount(),
                "on a spacer the same call has nothing to take: " + describe(tree()));
    }

    /**
     * The constraint every later step that wraps a control in one has to plan around: a parent
     * cannot describe a child it wrapped.
     *
     * <p>The walk asks the <em>widget</em> parent to add what only it knows, and for a wrapped
     * control that parent is the wrapper. So the owner is not asked, its annotation never lands, and
     * its choice of key never runs — which means the control keeps a serial of its own and a pooled
     * container that wrapped its cells would lose identity across recycling without anything failing
     * loudly. The direct child beside it is the control: same container, same hook, and it keeps its
     * identifier when the widget behind it is replaced, exactly as §1.3 promises.
     *
     * <p>If a forwarding hook is ever added to {@code Expanded}, this is where the decision is
     * revisited rather than discovered.
     */
    @Test
    void aParentCannotDescribeAControlItWrapped() {
        Keying owner = new Keying();
        owner.add(new Cell("direct", 1));
        owner.add(Expanded.of(new Cell("wrapped", 2)));
        bind(owner);
        frame();

        assertTrue(owner.asked.contains("direct"), "" + owner.asked);
        assertTrue(owner.asked.contains("Expanded"),
                "the wrapper is offered in the wrapped cell's place: " + owner.asked);
        assertFalse(owner.asked.contains("wrapped"),
                "and the control inside it is never offered at all: " + owner.asked);
        assertNotNull(node("direct").selectionItem(), describe(tree()));
        assertEquals(1, node("direct").selectionItem().positionInSet());
        assertNull(node("wrapped").selectionItem(),
                "the owner was never asked about it, so it carries none of the contribution: "
                        + describe(tree()));
        long direct = node("direct").id();
        long wrapped = node("wrapped").id();

        owner.remove(owner.children().get(1));
        owner.remove(owner.children().get(0));
        owner.add(new Cell("direct", 1));
        owner.add(Expanded.of(new Cell("wrapped", 2)));
        frame();

        assertEquals(direct, node("direct").id(),
                "a parent-chosen key survives the widget behind it being replaced");
        assertNotEquals(wrapped, node("wrapped").id(),
                "and a wrapped control has no key to survive on, only its own serial");
    }

    /** A cell whose identity is the owner's number for it, not the widget object. */
    private static final class Cell extends Probe {
        final String label;
        final int key;

        Cell(String label, int key) {
            super(Accessible.Role.BUTTON, label);
            this.label = label;
            this.key = key;
            this.prefWidth = 40;
        }
    }

    /** A pooling container in {@code ListView}'s shape: it keys and annotates its own children. */
    private static final class Keying extends Widget<Keying> {

        /** Every child the walk offered, by the simple name of its class, in order. */
        final List<String> asked = new ArrayList<>();

        @Override
        protected Size onMeasure(Constraints constraints) {
            for (int i = 0; i < children().size(); i++) {
                children().get(i).measure(constraints);
            }
            return constraints.constrain(200, 20);
        }

        @Override
        protected void onLayout() {
            float x = 0;
            for (int i = 0; i < children().size(); i++) {
                children().get(i).layoutBox(x, 0, 60, 20);
                x += 60;
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.LIST);
            a.selection(false, false);
        }

        @Override
        protected void onAccessibilityChildIdentity(Widget<?> child, Accessibility a) {
            if (child instanceof Cell cell) {
                a.key(cell.key);
            }
        }

        @Override
        protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
            asked.add(child instanceof Cell named ? named.label : "Expanded");
            if (child instanceof Cell cell) {
                a.selectionItem(false, cell.key, 2);
            }
        }
    }
}
