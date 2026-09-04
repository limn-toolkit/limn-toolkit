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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link TokenBox} becomes in the accessible tree, which is nothing, and what the deletion
 * leaves behind, which for this one widget is a whole rectangle and no offset at all.
 *
 * <p>The class is an {@code Extent} pair, one child, an {@code onMeasure} and an {@code onLayout}.
 * It declares no role, no name, no description, no action and no state; it declares no
 * {@code onPaint} anywhere between itself and {@code Widget}, so the paints-and-says-nothing warning
 * stays silent and this is not the {@code BackdropPanel} case; it never sets a tooltip and is never
 * focusable of its own accord; and it does not clip. So ADR 039 §1.6's predicate deletes it and
 * hoists its one child into the box's own place, with no accessibility code in the class.
 *
 * <p><b>The deletion is geometrically a no-op, which is true of no other member of §7's scaffolding
 * row.</b> {@code onLayout} hands the child {@code (0, 0, width(), height())}, so the surviving
 * node's rectangle is byte-identical to the rectangle the deleted node would have published:
 * nothing is offset as in {@code Padding}, nothing is distributed as in {@code Row} and
 * {@code Column}, nothing overlaps as in {@code Stack}. The row's "the tree is the controls, not the
 * boxes" is exactly backwards here — the box a reader is given for a colour picker's hue ramp
 * <em>is</em> the TokenBox's box, contributed whole to a node that outlives it.
 *
 * <p><b>What the class guarantees is when the extent is read, not what it returns.</b> The
 * {@code Extent} is an application-supplied {@code float of(SizeTokens)} and nothing forces it to
 * read its argument: {@code new TokenBox(t -> 200, null, child)} is a hard-coded two hundred and is
 * indistinguishable, to the tree, from {@code SizeTokens::colorRampW}. What is guaranteed is that it
 * runs inside {@code onMeasure} against {@code Theme.current().tokensFor(this)} — against the
 * resolved step, with a parent in place — which is the size-axis trap the class exists for and is a
 * timing guarantee rather than a derivation one. {@code TokenPadding}'s closed {@code Tokens.Role}
 * really does make the published box follow the ramp with no application code; this one does not
 * promise that, and the tests below prove the timing and not the derivation.
 *
 * <p><b>And the extent is a request.</b> {@code onMeasure} runs it through
 * {@code constrainWidth}/{@code constrainHeight} and then {@code constrain} again, so a tight parent
 * discards it: a TokenBox bound as a scene root publishes the canvas rectangle whatever its
 * {@code Extent} says, which is why everything below except the clamp test nests it inside a
 * {@link Column} whose default cross alignment is {@code START} and reaches it loose on both axes.
 *
 * <p><b>Its ancestry is {@code Widget} alone.</b> Unlike {@code TokenRow}, {@code TokenColumn} and
 * {@code TokenPadding}, which extend {@code Row}, {@code Column} and {@code Padding}, this class
 * extends nothing but {@code Widget} and repeats {@code SizedBox}'s body rather than extending it.
 * So this verdict stands on no other class staying hookless and no future hook anywhere can reach
 * it; and because it is {@code final}, the per-instance naming hatch is the only hatch there is.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins;
 * the paints-and-says-nothing seam, for which this widget is the silent case; and what a plain
 * fixed extent's deletion leaves behind, which {@code SizedBoxAccessibilityTest} pins on the class
 * this one is documented against but does not extend.
 *
 * <p>Everything below drives the public constructor and Widget's public setters on a bound scene and
 * reads the tree the scene published; nothing constructs a node and nothing calls a hook.
 */
class TokenBoxAccessibilityTest extends AccessibleComponentTestBase {

    /** {@code colorRampW} across the step ramp, which is the extent most of these tests read. */
    private static final float RAMP_AT_XSMALL = 12;
    private static final float RAMP_AT_MEDIUM = 18;
    private static final float RAMP_AT_XLARGE = 26;

    /** {@code colorFieldH} at the default step, the height-only case. */
    private static final float FIELD_H_AT_MEDIUM = 148;

    /** {@code controlHeight} at the default step, and the doubled extent built from it. */
    private static final float CONTROL_H_AT_MEDIUM = 32;
    private static final float TWICE_CONTROL_H_AT_MEDIUM = 64;

    /** {@code colorDialogW} at the widest step, which is wider than the canvas on purpose. */
    private static final float DIALOG_W_AT_XLARGE = 440;

    /**
     * A leaf with a preferred size unlike any token below and an application-supplied name:
     * something inside the box that survives the predicate, so the rectangle the box leaves behind
     * has a node to be read from, and so a fixed axis can be seen overriding a real preference.
     */
    private static final class Box extends Widget {
        Box() {
            setAccessibleName("content");
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, 40);
        }
    }

    /** The Box's own preferred extents, which a fixed axis must be seen to discard. */
    private static final float PREF_WIDTH = 60;
    private static final float PREF_HEIGHT = 40;

    /**
     * Binds {@code box} inside a column, never as the scene root: the root is laid out with
     * {@code Constraints.tight}, which would clamp every extent these tests are about away before
     * the first assertion ran.
     *
     * @param box    the widget under test
     * @param column the container to put it in, so a caller can declare a step on it first
     */
    private void bindInside(TokenBox box, Column column) {
        column.add(box);
        bind(column);
    }

    /** The same, for a test with nothing to say to the container. */
    private void bindInside(TokenBox box) {
        bindInside(box, new Column());
    }

    private void assertCoincides(TokenBox box, AccessibleNode child, String why) {
        assertEquals(box.localToSceneX(), child.x(), why + ": x");
        assertEquals(box.localToSceneY(), child.y(), why + ": y");
        assertEquals(box.width(), child.width(), why + ": width");
        assertEquals(box.height(), child.height(), why + ": height");
    }

    /**
     * The deletion itself: a window and the content, with neither the column nor the box in between.
     *
     * <p>A role, a hook or even an empty one added here would re-materialise a nameless box between
     * every colour-picker rail and its row, costing a reader one level of nesting per rail. That is
     * precisely the scaffolding §1.6 exists to delete.
     */
    @Test
    void theBoxIsNoNodeAndItsChildHoistsIntoItsPlace() {
        bindInside(new TokenBox(SizeTokens::colorRampW, null, new Box()));

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(),
                "a window and the content, and no box and no column in between: " + describe(tree));
        assertEquals(0, node("content").parent(),
                "the content hoists all the way into the window's place");
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group is what a wrapper must never publish: " + describe(tree));
        }
    }

    /**
     * The finding, and the test no other member of the scaffolding row can have: the child's
     * published rectangle is the deleted box's rectangle, exactly.
     *
     * <p>Any offset, inset, alignment or centring introduced into {@code onLayout} would silently
     * move every colour-picker rail's published rectangle away from where it is painted, with every
     * layout test still green because the box's own size would be unchanged. The clipping-ancestor
     * assertion in {@code AccessibleGalleryTest} would not see it either, because nothing here
     * clips.
     */
    @Test
    void theChildsBoxIsTheDeletedBoxExactly() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box);

        assertCoincides(box, node("content"), "the deletion is a no-op on the geometry");
    }

    /**
     * A fixed axis publishes the token and stretches the child to it; a free axis publishes the
     * child's own measure. Which is which comes from which {@code Extent} is null, and the colour
     * picker uses all three shapes.
     *
     * <p>Swapping the two {@code Constraints} argument pairs, or dropping the tight minimum on a
     * fixed axis, would make that axis stop stretching the child: the published box would shrink to
     * the child's preference while paint kept drawing the token's rectangle, and the tree and the
     * screen would disagree with nothing red.
     */
    @Test
    void theFixedAxisPublishesTheTokenAndTheFreeAxisPublishesTheChild() {
        TokenBox wide = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(wide);
        assertEquals(RAMP_AT_MEDIUM, node("content").width(),
                "the token, and not the child's preferred " + PREF_WIDTH);
        assertEquals(PREF_HEIGHT, node("content").height(), "the free axis is the child's own");
        assertCoincides(wide, node("content"), "width fixed, height free");

        TokenBox tall = new TokenBox(null, SizeTokens::colorFieldH, new Box());
        bindInside(tall);
        assertEquals(PREF_WIDTH, node("content").width(), "and the other way round");
        assertEquals(FIELD_H_AT_MEDIUM, node("content").height());
        assertCoincides(tall, node("content"), "width free, height fixed");

        TokenBox both = new TokenBox(t -> 2 * t.controlHeight(), SizeTokens::controlHeight,
                new Box());
        bindInside(both);
        assertEquals(TWICE_CONTROL_H_AT_MEDIUM, node("content").width(),
                "an extent is a function of the row and not only a method reference");
        assertEquals(CONTROL_H_AT_MEDIUM, node("content").height());
        assertCoincides(both, node("content"), "both axes fixed");
    }

    /**
     * A density change resizes the box and destroys nothing.
     *
     * <p>The box is not in the tree, so there is no node to add or remove and nothing for identity
     * to be minted over: a step change is a bounds change on the content and no more. The
     * alternative — a screen reader experiencing the whole page as torn down and rebuilt because the
     * user changed a density preference — is one half of what this pins. The other half is the quiet
     * frame afterwards: an extent read outside the measure pass, or a string allocated to conclude
     * that nothing moved, would show up here as a frame that had something to say.
     */
    @Test
    void aStepChangeResizesTheBoxAndDestroysNothing() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box);
        long before = node("content").id();
        assertEquals(RAMP_AT_MEDIUM, node("content").width());
        bridge.events.clear();

        scene.setControlSize(ControlSize.XSMALL);
        frame();

        assertEquals(before, node("content").id(), "the content's identity is not the step");
        assertEquals(RAMP_AT_XSMALL, node("content").width(), "and the extent followed the step");
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the resize is what a reader is told about: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);

        bridge.events.clear();
        frame();
        assertTrue(bridge.events.isEmpty(),
                "a quiet frame after the step says nothing: " + bridge.events);

        box.setControlSize(ControlSize.XLARGE);
        frame();
        assertEquals(RAMP_AT_XLARGE, node("content").width(),
                "a step declared on the widget itself wins over the scene's");
        assertEquals(before, node("content").id(), "and still nothing was re-keyed");
    }

    /**
     * The size-axis trap, which is the whole reason this class exists and the one thing about it
     * worth proving through the tree: the extent resolves against the step an <em>ancestor</em>
     * declares, and never against the process default.
     *
     * <p>Reading the token in the constructor or a field initializer — where the widget has no
     * parent — would pin every box to the process default forever, whatever step the eventual parent
     * declares, and the tree is the only place that failure is visible as a number. The step is
     * driven through the container and the widget and never through {@code setProcessDefault}, which
     * is a static global that would leak into the rest of the run.
     */
    @Test
    void theExtentIsResolvedAgainstTheParentsStepAndNotTheProcessDefault() {
        Column column = new Column();
        column.setControlSize(ControlSize.XLARGE);
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box, column);

        assertEquals(RAMP_AT_XLARGE, node("content").width(),
                "the extent is read inside the measure pass, with a parent in place");
        assertCoincides(box, node("content"), "against an ancestor's step");
    }

    /**
     * A tight parent discards the token, so what a reader is told is what was granted.
     *
     * <p>The scene lays its root out with {@code Constraints.tight}, so a TokenBox bound there
     * publishes the canvas rectangle whatever its {@code Extent} returns; and a token wider than the
     * space available is clamped rather than honoured. Offering a reader a rectangle the control
     * does not occupy is the failure, and this is also the one place anybody will look to learn that
     * the extent is a request.
     */
    @Test
    void aTightParentDiscardsTheToken() {
        TokenBox root = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bind(root);
        assertEquals(400f, node("content").width(),
                "the root is measured tight to the canvas, and the token is gone");
        assertCoincides(root, node("content"), "granted rather than asked for");

        Column column = new Column();
        column.setControlSize(ControlSize.XLARGE);
        TokenBox tooWide = new TokenBox(SizeTokens::colorDialogW, null, new Box());
        bindInside(tooWide, column);
        assertTrue(DIALOG_W_AT_XLARGE > 400, "the token asks for more than the canvas has");
        assertEquals(400f, node("content").width(), "and is clamped to what there is");
        assertCoincides(tooWide, node("content"), "clamped rather than refused");
    }

    /**
     * Nothing about it mirrors, and the absence is the assertion.
     *
     * <p>{@code onLayout} places the child at the physical origin and fills the whole box, so there
     * is no leading-or-trailing choice to make and the published rectangle is identical under both
     * directions. TokenBox is the only direction-invariant member of the {@code Token*} family —
     * TokenPadding's origin becomes {@code insets.right()} under a right-to-left subtree, and a
     * column's cross axis reflects — so a later mirroring sweep that treated this class like its
     * siblings would move every control inside one off its painted position, for a mirrored reader
     * only, with paint unchanged.
     */
    @Test
    void theBoxDoesNotMirror() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box);
        AccessibleNode ltr = node("content");
        float x = ltr.x();
        float y = ltr.y();
        float width = ltr.width();
        float height = ltr.height();

        box.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode rtl = node("content");
        assertEquals(x, rtl.x(), "the child sits at the physical origin from either side");
        assertEquals(y, rtl.y());
        assertEquals(width, rtl.width());
        assertEquals(height, rtl.height());
        assertCoincides(box, rtl, "and it is still the box, mirrored or not");
    }

    /**
     * The escape hatch, and the shape that makes it unlike {@code TokenPadding}'s: the node an
     * application materialises here describes exactly the rectangle the node underneath already
     * does, so naming a TokenBox buys a name and no geometry.
     *
     * <p>Two regressions are pinned at once. A future "TokenBox is always deleted" shortcut in the
     * walk would swallow an application's name outright; and identity minted over the published tree
     * rather than over the widget tree would re-key everything inside a region the moment it was
     * labelled.
     */
    @Test
    void namingTheBoxMaterialisesAGroupCoextensiveWithItsChild() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box);
        long child = node("content").id();

        box.setAccessibleName("Swatch");
        frame();

        AccessibleNode named = node("Swatch");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(box.localToSceneX(), named.x(), "the box's own rectangle");
        assertEquals(box.localToSceneY(), named.y());
        assertEquals(box.width(), named.width());
        assertEquals(box.height(), named.height());

        AccessibleNode inside = node("content");
        assertEquals(named.x(), inside.x(),
                "and the hatch is co-extensive with what it wraps, with no gutter between the two: "
                        + describe(tree()));
        assertEquals(named.y(), inside.y());
        assertEquals(named.width(), inside.width());
        assertEquals(named.height(), inside.height());
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(child, inside.id(), "naming the region is not renaming what is in it");
    }

    /**
     * Focusing alone is not a fourth verb: it supplies nothing to publish, so the node survives the
     * predicate's focusable branch as {@code UNKNOWN} and unnamed, which is a defect the walk warns
     * about once per class and not a supported way to get a node.
     *
     * <p>The same half-materialised shape {@code PaddingAccessibilityTest} pins on the superclass of
     * this widget's siblings, pinned here because this widget has no superclass to inherit it from.
     */
    @Test
    void focusingAloneIsNotAFourthVerb() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        bindInside(box);

        box.setFocusable(true);
        frame();

        AccessibleNode unknown = null;
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.UNKNOWN) {
                unknown = tree.node(i);
            }
        }
        assertNotNull(unknown, "the predicate's focusable branch keeps it: " + describe(tree));
        assertEquals("", unknown.name(), "but focus is not a name: " + describe(tree));
    }

    /**
     * A zero extent is a fixed zero, and what it publishes is an operable-sized nothing that is
     * still {@code SHOWING}.
     *
     * <p>Honest rather than aspirational. {@code onMeasure} treats a negative extent as unset and
     * zero as fixed, so a lambda returning zero measures the child tight at zero;
     * {@code Widget#isShowing()} rejects a degenerate rectangle only when a <em>clipping</em>
     * ancestor produced it, and TokenBox does not clip, so a reader is handed a control of no size
     * that claims to be on screen. If a later change makes the walk prune zero-area nodes, this is
     * the test that has to be revisited deliberately rather than deleted.
     */
    @Test
    void aZeroExtentPublishesAZeroWidthNodeThatIsStillShowing() {
        TokenBox box = new TokenBox(t -> 0, null, new Box());
        bindInside(box);

        AccessibleNode content = node("content");
        assertEquals(0f, content.width(), "exactly zero: " + content.bounds());
        assertTrue(content.width() >= 0 && content.height() >= 0,
                "and never below it: " + content.bounds());
        assertTrue(content.has(Accessible.State.SHOWING),
                "nothing here clips, so nothing here is hidden: " + describe(tree()));
    }

    /**
     * A second child is never measured, never laid out, and publishes a degenerate box at the
     * TokenBox's origin.
     *
     * <p>{@code Widget#add} is public and non-final and this class does not override it, so adding a
     * second child to a widget that lays out only its constructor child is legal and silent. The
     * extra widget keeps the zero box it was born with, and if it declares anything at all it
     * publishes an operable node with no area sitting on top of the real content. It is a limit to
     * record rather than a defect to fix in this class: adding a guard would be a new refusal in a
     * layout wrapper, and the answer for a caller who wants two children is a container.
     */
    @Test
    void aSecondChildIsNeverLaidOutAndPublishesADegenerateBox() {
        TokenBox box = new TokenBox(SizeTokens::colorRampW, null, new Box());
        Box extra = new Box();
        extra.setAccessibleName("extra");
        box.add(extra);
        bindInside(box);

        AccessibleNode stray = node("extra");
        assertEquals(box.localToSceneX(), stray.x(), "at the box's own origin");
        assertEquals(box.localToSceneY(), stray.y());
        assertEquals(0f, stray.width(), "and never laid out: " + stray.bounds());
        assertEquals(0f, stray.height());
        assertCoincides(box, node("content"), "the constructor's child is unaffected");
    }
}
