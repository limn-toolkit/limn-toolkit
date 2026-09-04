package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SizedBox} becomes in the accessible tree, which is nothing, and what survives it
 * being nothing.
 *
 * <p>The class is two final floats, one final child, an {@code onMeasure} and an {@code onLayout}.
 * It declares no role, no name, no description, no action and no state; it declares no
 * {@code onPaint}, so the paints-and-says-nothing warning stays silent and this is not the
 * {@code BackdropPanel} case; and it is never focusable of its own accord. So ADR 039 §1.6's
 * predicate deletes it and hoists its one child into the box's own parent. That is the whole of the
 * answer and it needs no code in the class.
 *
 * <p><b>What the deletion leaves behind is not an origin but a whole rectangle.</b>
 * {@code onLayout} forces the child to {@code (0, 0, width(), height())}, unconditionally and on
 * both axes, whatever the child measured. There is no gutter to subtract as there is in
 * {@code Padding} and no cursor to advance as there is in {@code Flex}, so the child's published box
 * <em>is</em> the deleted node's box, in every configuration. That makes §7's "the tree is the
 * controls, not the boxes" backwards for this one widget: the deletion costs nothing precisely
 * because the surviving node already carries the deleted node's geometry. A control inside one is
 * published at the wrapper's size and never at its own measured size, which is why
 * {@code MediaControls}' volume slider is seventy-two points wide in the tree.
 *
 * <p><b>The constructor's number is a request the parent may refuse.</b> {@code onMeasure} clamps it
 * through the incoming constraints, so a stretching column or a tight root discards it and the child
 * follows the granted box rather than the asked-for one. A reader is told what was granted. That is
 * also why everything below binds inside a {@code Group} rather than as the scene root: the root is
 * measured tight to the canvas, and a {@code SizedBox} bound there would have its fixed size clamped
 * away before any of these assertions ran.
 *
 * <p><b>The two-argument constructor is a childless spacer</b>, and it is the only member of §7's
 * scaffolding row that can hold nothing at all. §1.6's "removed and its children hoisted" degenerates
 * to "removed": a rigid gap publishes nothing and there is nothing to hoist. It paints nothing, so it
 * needs no {@code setAccessibleIgnored} — the flag §1.6 reaches for on "a spacer image or a
 * decorative rule" is already unnecessary for the toolkit's own spacer.
 *
 * <p><b>Nothing about it mirrors.</b> The class never reads {@code layoutDirection()}, and the child
 * sits at the box's origin in both directions. It is the one member of the scaffolding family whose
 * deletion leaves no mirroring expression behind, where {@code Padding} leaves its leading inset and
 * {@code Column} leaves Flex's cross-axis reflection. The absence is pinned below so that a later
 * right-to-left sweep cannot mirror an origin that has to stay at zero.
 *
 * <p><b>Transparency is per instance, and the escape hatch is co-extensive with the child.</b>
 * Widget's naming surface is public, so a name, a role or a tooltip on a {@code SizedBox}
 * materialises a {@code GROUP} — but over a rectangle identical to its child's, rather than over the
 * larger enclosing region {@code Padding}'s hatch produces. It is the one case where the hatch adds a
 * node describing exactly the same rectangle as the node underneath, which is worth writing down
 * before somebody reads it as a bug. {@code setFocusable} alone is not a hatch: it materialises an
 * {@code UNKNOWN}, nameless node the walk warns about, the same half-materialised defect
 * {@code PaddingAccessibilityTest} pins.
 *
 * <p>The class is {@code final} and setterless, so unlike {@code Padding}, {@code Row},
 * {@code Column} and {@code Stack} this verdict stands on no ancestor staying hookless, no subclass
 * can inherit a hook, and nothing an application does to an instance can change its box: every change
 * to the published rectangle arrives from the parent's constraints. There is therefore no equality
 * guard of the {@code Padding.setInsets} kind to protect and no revision counter owed.
 * {@code TokenBox} repeats this body rather than extending it and owes a step of its own.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins;
 * the paints-and-says-nothing seam, for which this widget is the silent case rather than the warned
 * one; and the resolution of a relation past a deleted target, which {@code AccessibleRelationTest}
 * pins with its own probe. The bookkeeping — that this class is settled as transparent and still says
 * nothing about itself — is {@code AccessibleCoverageTest}'s own two tests and is not duplicated.
 *
 * <p>Everything below drives SizedBox's public constructors and Widget's public setters on a bound
 * scene; nothing constructs a node.
 */
class SizedBoxAccessibilityTest extends AccessibleTestBase {

    /**
     * Deliberately unlike any fixed size used below, so a regression that published the child's own
     * measure instead of the granted box fails rather than coincides.
     */
    private static final float PREF_WIDTH = 57;
    private static final float PREF_HEIGHT = 23;

    private static Probe probe(String name) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefWidth = PREF_WIDTH;
        probe.prefHeight = PREF_HEIGHT;
        return probe;
    }

    /**
     * Binds {@code widget} inside a container, never as the scene root: the root is measured tight
     * to the canvas, which would clamp away every fixed size these tests are about.
     */
    private void bindInside(Widget widget) {
        Group box = new Group();
        box.add(widget);
        bind(box);
        frame();
    }

    private void assertCoincides(SizedBox sized, AccessibleNode child, String why) {
        assertEquals(sized.localToSceneX(), child.x(), why + ": x");
        assertEquals(sized.localToSceneY(), child.y(), why + ": y");
        assertEquals(sized.width(), child.width(), why + ": width");
        assertEquals(sized.height(), child.height(), why + ": height");
    }

    /**
     * The deletion itself: a control in a box is a window and the control, with no grouping node in
     * between.
     *
     * <p>Anything that gave this class a hook, a role or a state would re-materialise a box around a
     * great many of the toolkit's controls at once, since a fixed extent is how nearly every one of
     * them is given a width.
     */
    @Test
    void theBoxIsNoNodeAndItsChildHoistsIntoTheWindow() {
        bindInside(new SizedBox(80, 40, probe("ok")));

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(),
                "a window and a button, and no box in between: " + describe(tree));
        assertEquals(0, node("ok").parent(), "the button hoists into the window's place");
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group between the window and the control is the scaffolding this "
                            + "widget exists to not publish: " + describe(tree));
        }
    }

    /**
     * The whole of what the deletion leaves behind: the child's published rectangle is the deleted
     * box's rectangle, exactly, on both axes and in every configuration.
     *
     * <p>A "simplification" of {@code onLayout} that laid the child out at its measured size instead
     * of at the box would leave every layout test green — the box's own size is unchanged — and hand
     * a reader a rectangle that is not what is on screen.
     */
    @Test
    void theChildsBoxIsTheBoxExactly() {
        SizedBox both = new SizedBox(80, 40, probe("ok"));
        bindInside(both);
        assertEquals(80f, both.width(), "both axes were granted");
        assertEquals(40f, both.height());
        assertCoincides(both, node("ok"), "both axes fixed");

        SizedBox tall = new SizedBox(SizedBox.UNSET, 40, probe("ok"));
        bindInside(tall);
        assertEquals(PREF_WIDTH, tall.width(), "an unset axis passes the child's measure through");
        assertEquals(40f, tall.height());
        assertCoincides(tall, node("ok"), "width unset, height fixed");

        SizedBox wide = new SizedBox(80, SizedBox.UNSET, probe("ok"));
        bindInside(wide);
        assertEquals(80f, wide.width());
        assertEquals(PREF_HEIGHT, wide.height(), "and the other way round");
        assertCoincides(wide, node("ok"), "width fixed, height unset");
    }

    /**
     * An oversized child is published at the box and not at what it asked for, which is
     * {@code LayoutTest}'s own forcing case read from the tree instead of from a {@code Size}.
     */
    @Test
    void anOversizedChildIsPublishedAtTheBoxAndNotAtItsMeasure() {
        Probe big = probe("ok");
        big.prefWidth = 200;
        big.prefHeight = 200;
        SizedBox sized = new SizedBox(80, 40, big);
        bindInside(sized);

        AccessibleNode child = node("ok");
        assertEquals(80f, child.width(), "the box wins over the measure: " + child.bounds());
        assertEquals(40f, child.height());
        assertTrue(child.width() >= 0 && child.height() >= 0,
                "a negative rectangle is unrecoverable at the bridge: " + child.bounds());
        assertCoincides(sized, child, "an oversized child is still the box");
    }

    /**
     * A stretching parent overrides the fixed size and the child follows it, which is the claim §7's
     * flat "transparent" row hides: the number handed to the constructor is a request, and the
     * granted box is what a reader is told.
     */
    @Test
    void aStretchingParentOverridesTheFixedSizeAndTheChildFollows() {
        SizedBox sized = new SizedBox(80, 40, probe("ok"));
        Column column = new Column();
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(sized);
        bindInside(column);

        assertEquals(200f, column.width(), "the harness is two hundred wide");
        assertEquals(200f, sized.width(),
                "a stretching cross axis measures its child with a tight minimum, which discards "
                        + "the eighty");
        assertEquals(200f, node("ok").width(), "and the child follows the box it was granted");
        assertCoincides(sized, node("ok"), "granted rather than asked for");
    }

    /**
     * A zero-sized box publishes an operable node with an empty rectangle, and never a negative one.
     *
     * <p>Recorded rather than defended: nothing in the class subtracts, so unlike {@code Padding}
     * there is no clamp here to regress and none is needed. An empty rectangle is the honest report
     * of a control that really was forced to zero size, and it is still enabled, still focusable and
     * still carries its verb, because the class does not clip and nothing about a zero extent takes
     * a state away.
     */
    @Test
    void aZeroSizedBoxPublishesAnOperableNodeWithAnEmptyRectangle() {
        Probe operable = probe("ok");
        operable.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        operable.setFocusable(true);
        SizedBox sized = new SizedBox(0, 0, operable);
        bindInside(sized);

        AccessibleNode child = node("ok");
        assertEquals(0f, child.width(), "exactly zero: " + child.bounds());
        assertEquals(0f, child.height());
        assertTrue(child.width() >= 0 && child.height() >= 0, "and never below it");
        assertTrue(child.has(Accessible.State.SHOWING),
                "a SizedBox does not clip, so nothing here is hidden: " + describe(tree()));
        assertTrue(child.has(Accessible.State.FOCUSABLE), describe(tree()));
        assertNotNull(child.actions(), "an empty rectangle is not a reason to drop a verb");
        assertTrue(child.actions().has(Accessible.Action.PRESS));
    }

    /**
     * The childless spacer: it is no node, and there is nothing to hoist in its place either.
     *
     * <p>§1.6's sentence about a transparent widget assumes there are children to lift. Here the
     * constructor null-guards the {@code add}, so the widget holds none and the deletion is total.
     * Removing one from a live scene is therefore a layout change with no structural consequence in
     * the tree at all: a hoist rule that assumed a child would have published a nameless gap in
     * between.
     */
    @Test
    void aSpacerIsNoNodeAndHoistsNothing() {
        Group withoutSpacer = new Group();
        withoutSpacer.add(probe("ok"));
        bind(withoutSpacer);
        frame();
        int bare = tree().nodeCount();

        SizedBox spacer = new SizedBox(24, 24);
        Group withSpacer = new Group();
        withSpacer.add(spacer);
        withSpacer.add(probe("ok"));
        bind(withSpacer);
        frame();

        assertEquals(0, spacer.children().size(), "the two-argument constructor holds no child");
        assertEquals(bare, tree().nodeCount(),
                "a rigid gap costs a node in neither direction: " + describe(tree()));
        assertEquals(2, tree().nodeCount(), describe(tree()));
        assertEquals(24f, node("ok").y(), "it is a gap in the layout and nothing in the tree");
        long child = node("ok").id();
        bridge.clear();

        withSpacer.remove(spacer);
        frame();

        assertEquals(0f, node("ok").y(), "the control moved up into the gap");
        assertEquals(child, node("ok").id(), "and kept its identity");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "removing a widget that published nothing removes nothing: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * Nothing about it mirrors, and the absence is the assertion.
     *
     * <p>Every other member of the scaffolding family leaves a mirroring expression behind when its
     * node is deleted. This one places its child at the box's origin in both directions, so a
     * right-to-left sweep that "added mirroring here" would move every control inside a
     * {@code SizedBox} off its painted position, for a mirrored reader only, with paint unchanged.
     */
    @Test
    void nothingAboutItMirrors() {
        SizedBox sized = new SizedBox(80, 40, probe("ok"));
        Group root = new Group();
        root.add(sized);
        bind(root);
        frame();
        assertCoincides(sized, node("ok"), "left to right");

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertCoincides(sized, node("ok"), "and mirrored, which is the same rectangle");
    }

    /**
     * The escape hatch, and the one thing that makes it unlike {@code Padding}'s: the node an
     * application materialises here describes exactly the rectangle the node underneath already
     * does.
     *
     * <p>A name, a role or a tooltip each supply something the node can be published as, so each
     * materialises one; {@code setFocusable} supplies nothing, so the widget survives the
     * predicate's focusable branch with no role and no name and is published as {@code UNKNOWN},
     * which §12.1 refuses on both counts. That is a defect the walk warns about and not a fourth
     * verb.
     */
    @Test
    void namingOneMaterialisesAGroupCoextensiveWithItsChild() {
        SizedBox sized = new SizedBox(80, 40, probe("ok"));
        bindInside(sized);
        long child = node("ok").id();

        sized.setAccessibleName("Preview");
        frame();

        AccessibleNode named = node("Preview");
        assertEquals(Accessible.Role.GROUP, named.role());
        AccessibleNode inside = node("ok");
        assertEquals(named.x(), inside.x(),
                "the hatch is co-extensive with what it wraps, and there is no gutter between the "
                        + "two: " + describe(tree()));
        assertEquals(named.y(), inside.y());
        assertEquals(named.width(), inside.width());
        assertEquals(named.height(), inside.height());
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(child, inside.id(), "naming the wrapper is not renaming the control");

        sized.setAccessibleName((String) null);
        frame();

        assertEquals(2, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(child, node("ok").id());

        sized.setFocusable(true);
        frame();

        AccessibleNode unknown = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.UNKNOWN) {
                unknown = tree().node(i);
            }
        }
        assertNotNull(unknown,
                "the predicate's focusable branch keeps it: " + describe(tree()));
        assertEquals("", unknown.name(), "but focus is not a name: " + describe(tree()));
        sized.setFocusable(false);

        sized.setTooltip("Preview");
        frame();

        AccessibleNode tooltipped = node("Preview");
        assertEquals(Accessible.Role.GROUP, tooltipped.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, tooltipped.nameFrom());
        assertEquals(3, tree().nodeCount(), describe(tree()));
        assertEquals(child, node("ok").id(), "and a tooltip is not a rebuild either");
    }

    /**
     * Moving a box moves its child and does not re-key it.
     *
     * <p>The class is final and holds no setter, so nothing an application does to an instance can
     * move it: every change to its published rectangle arrives from the parent's constraints, and
     * this test has to reach for the parent's own alignment to produce one at all. That is the
     * finding as much as the assertion is. Identity derived from geometry would then make a
     * re-aligned container destroy and recreate every control sitting in a box beneath it, which is
     * most of them.
     */
    @Test
    void movingItFromOutsideMovesTheChildWithoutRekeyingIt() {
        SizedBox sized = new SizedBox(80, 40, probe("ok"));
        Column column = new Column();
        column.crossAlignment(Flex.CrossAlignment.START);
        column.add(sized);
        bind(column);
        frame();
        assertEquals(0f, node("ok").x(), "against the edge reading starts from");
        assertCoincides(sized, node("ok"), "before the move");
        long child = node("ok").id();
        bridge.clear();

        column.crossAlignment(Flex.CrossAlignment.CENTER);
        frame();

        assertEquals(60f, node("ok").x(), "centred in a two-hundred-wide column");
        assertCoincides(sized, node("ok"), "and it is still the box, wherever the box is");
        assertEquals(child, node("ok").id(), "identity is not a coordinate");
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * An idle frame over one publishes nothing, which is the transparent branch allocating nothing
     * per frame restated on this widget.
     *
     * <p>Cheap here because the class is immutable after construction: there is no derived string to
     * format, no cache to fill and no revision counter owed, so a frame that damaged something and
     * changed no accessible fact has nothing to say.
     */
    @Test
    void anIdleFrameOverOnePublishesNothing() {
        bindInside(new SizedBox(80, 40, probe("ok")));
        bridge.clear();

        for (int i = 0; i < 5; i++) {
            frame();
        }

        assertTrue(bridge.published.isEmpty(), "nothing changed, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }
}
