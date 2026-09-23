package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link BackdropPanel} becomes in the accessible tree, which is nothing, and what that
 * costs, which is also nothing.
 *
 * <p>The panel declares no role, no name, no description, no action and no state of its own; it is
 * never focusable unless an application makes it so; and it holds exactly one child. So ADR 039
 * §1.6's predicate deletes it and hoists that child into the panel's own place, and the published
 * tree is complete: everything the panel contributes is background over <em>siblings</em> it holds
 * no reference to, and everything a user can operate is inside the child, which publishes unchanged
 * with its own identity and its own box.
 *
 * <p><b>What was not free is the log.</b> BackdropPanel is the one member of §7's scaffolding row
 * that overrides {@code onPaint} — the rows, the columns and the paddings beside it in that row all
 * paint nothing — so §1.6's paints-and-says-nothing warning fired on it: a toolkit class named in an
 * application's log, for a picture that means nothing, advising {@code setAccessibleIgnored(true)},
 * which on this widget deletes the whole child subtree and with it every control the glass sits
 * behind. The last two tests are the two halves of that: the warning is gone because the panel says
 * its painting is decoration, and the advice it used to give really would have taken the controls.
 *
 * <p>The absence of the warning is asserted after <em>every</em> test rather than in one of them, on
 * purpose. The walk names a class at most once for the life of the virtual machine, so an assertion
 * placed in a single test would be checking a set another test had already filled and would pass
 * whatever the panel said about itself; checked after each, whichever test runs first is the one
 * that catches it. That the seam itself works in both directions is pinned where it lives, in
 * {@code limn.scene.AccessiblePaintWarningTest}.
 *
 * <p>Everything below drives BackdropPanel's own public setters, and Widget's, on a bound scene, and
 * reads back the tree the scene published. Nothing constructs a node.
 */
class BackdropPanelAccessibilityTest extends AccessibleComponentTestBase {

    /** A wash: the ordinary glass bar's material. */
    private static final BackdropEffect GLASS = new BackdropEffect.Wash(Color.BLACK, 0.4f, -0.2f);

    /** A second and a third, for a stack. */
    private static final BackdropEffect BLUR =
            new BackdropEffect.Blur(Color.BLACK, 8, BackdropEffect.Blur.Axis.X);
    private static final BackdropEffect TINT = new BackdropEffect.Wash(Color.BLACK, 1f);

    /**
     * The effect a redaction is painted with, whose own documentation calls it redaction rather
     * than decoration. It hides the pixels from the eye and nothing from the tree, which is the
     * one thing an application has to be told about this widget.
     */
    private static final BackdropEffect REDACTION = new BackdropEffect.Pixelate(Color.BLACK, 8);

    /**
     * A leaf with a fixed preferred size and an application-supplied name: a control inside the
     * glass, which is what the panel's deletion has to leave standing.
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

    /**
     * A named container that fills what it is given and hands all of it to each of its children,
     * one over the next: the stack a backdrop is meant to be used in, and somewhere other than the
     * window node for the hoist to land, so that a test cannot pass by everything collapsing to
     * the root.
     */
    private static final class Stage extends Widget<Stage> {
        Stage(Widget<?>... children) {
            for (Widget<?> child : children) {
                add(child);
            }
            setAccessibleName("Stage");
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            for (int i = 0; i < children().size(); i++) {
                children().get(i).measure(constraints);
            }
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            for (int i = 0; i < children().size(); i++) {
                children().get(i).layoutBox(0, 0, width(), height());
            }
        }
    }

    private Box content;
    private BackdropPanel panel;

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
    void theGlassIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        assertTrue(logged.isEmpty(),
                "the panel paints, and is deleted, and there is nothing about that worth telling "
                        + "an application: the ink is a wash over content the panel does not own, "
                        + "and every control it wraps is published. A warning here names a toolkit "
                        + "class the application cannot correct and recommends a flag that would "
                        + "delete the controls: " + logged);
    }

    /** Binds a named box inside a glass panel inside a named container, and settles a frame. */
    private void bindPanel() {
        bindPanel(Insets.all(12));
    }

    /** The same, with the insets a test needs. */
    private void bindPanel(Insets insets) {
        content = new Box("Play");
        panel = new BackdropPanel(GLASS, insets, content);
        bind(new Stage(panel));
    }

    // ------------------------------------------------------------------ the shape of the tree

    /**
     * The panel is no node at all, and the control it wraps hangs where the panel hung.
     *
     * <p>A role, a state or even an empty describe hook added to this class would grow a nameless
     * group around every glass bar in every application that uses one, and cost a reader a level of
     * nesting for a piece of material.
     */
    @Test
    void thePanelIsNoNodeAndItsChildHoistsIntoItsPlace() {
        bindPanel();

        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group survived the predicate: " + describe(tree));
        }
        assertEquals(3, tree.nodeCount(),
                "the window, the container and the control; the glass is not among them"
                        + describe(tree));
        assertEquals(tree.indexOf(node("Stage").id()), node("Play").parent(),
                "the control hoists into the panel's own place, under the container"
                        + describe(tree));
    }

    /**
     * The control's box is the child's own laid-out box, inset by the panel, and it mirrors.
     *
     * <p>Nothing here is written anywhere: the walk reads the widget's scene coordinates, and
     * {@code Padding} already puts the child at the trailing inset when the subtree reads right to
     * left. What this pins is that no future geometry override starts publishing the panel's box in
     * the child's place, which would put a reader's cursor around the glass instead of the control.
     */
    @Test
    void theControlsBoxIsItsOwnAndMirrorsWithTheSubtree() {
        bindPanel(new Insets(4, 30, 4, 10));

        assertEquals(content.localToSceneX(), node("Play").x(), 0.01f, describe(tree()));
        assertEquals(content.localToSceneY(), node("Play").y(), 0.01f);
        assertEquals(content.width(), node("Play").width(), 0.01f);
        assertEquals(content.height(), node("Play").height(), 0.01f);
        assertEquals(panel.localToSceneX() + 10, node("Play").x(), 0.01f,
                "the leading inset in a left-to-right subtree" + describe(tree()));

        panel.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(panel.localToSceneX() + 30, node("Play").x(), 0.01f,
                "and the other one reading the other way, with no accessibility code anywhere"
                        + describe(tree()));
        assertEquals(content.localToSceneX(), node("Play").x(), 0.01f);
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * Changing the material says nothing to anybody, because none of it is a fact about the tree.
     *
     * <p>An effect stack is what the glass is made of. Publishing it — as a description, or by
     * wiring {@code invalidateAccessible} into these setters — would republish the whole tree on
     * every frame of an animated backdrop with the user doing nothing at all.
     */
    @Test
    void changingTheMaterialPublishesNothing() {
        bindPanel();
        int published = bridge.published.size();
        bridge.events.clear();

        panel.setEffect(BLUR);
        frame();
        panel.setEffects(GLASS, BLUR, TINT);
        frame();
        panel.setCornerRadius(20);
        frame();
        panel.setCornerRadius(BackdropPanel.RADIUS_FROM_TOKENS);
        frame();

        assertEquals(published, bridge.published.size(),
                "nothing about the tree changed, so no snapshot was taken");
        assertTrue(bridge.events.isEmpty(), "and nothing was said: " + bridge.events);
    }

    /**
     * An animated backdrop damages itself on every frame and costs nothing to describe.
     *
     * <p>This is the widget most likely to be damaged on every frame of a real application — the
     * glass over a playing film — which makes it the right place for the claim the two allocation
     * rules exist for. A describe hook added here later that formats anything, or a name built
     * rather than held, would be invisible everywhere except in this measurement.
     *
     * <p>Measured against the same frame with nothing listening rather than against zero: a
     * headless frame has a floor of its own, and the panel's own paint allocates a shape per pass,
     * both of which are on either side of the comparison and cancel.
     */
    @Test
    void anAnimatedBackdropCostsNothingToDescribe() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindPanel();
        int published = bridge.published.size();
        bridge.events.clear();

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            panel.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            panel.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a backdrop that says nothing must cost no memory at all; a frame of "
                        + "animated glass walks the tree to conclude that nothing moved");
    }

    /**
     * The control keeps its identifier through every setter the panel has, and through a
     * relayout.
     *
     * <p>Identity is minted over the widget tree and never over the published one, so a control
     * under a transparent parent is not re-keyed by anything done to that parent. A screen reader
     * experiences a re-key as the element it is holding becoming invalid in the middle of reading.
     */
    @Test
    void theControlKeepsItsIdentityThroughEveryPanelSetter() {
        bindPanel();
        long id = node("Play").id();
        float wasAt = node("Play").x();

        panel.setEffects(GLASS, BLUR);
        frame();
        panel.setCornerRadius(20);
        frame();
        assertEquals(id, node("Play").id(), "the material is not the control's identity");

        panel.setInsets(Insets.all(40));
        frame();

        assertEquals(id, node("Play").id(), "and neither is where the panel put it");
        assertNotEquals(wasAt, node("Play").x(), "which did move" + describe(tree()));
        assertEquals(content.localToSceneX(), node("Play").x(), 0.01f);
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the move is what a reader is told about: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    // ------------------------------------------------- the ways an application materialises it

    /**
     * Naming the panel gives it a node, and re-keys nothing underneath.
     *
     * <p>Transparency here is per instance, not per class: a glass control bar is a plausible thing
     * for an application to want announced as a region, and naming one is the supported way to ask.
     */
    @Test
    void namingThePanelMaterialisesItAndReKeysNothing() {
        bindPanel();
        long child = node("Play").id();

        panel.setAccessibleName("Transport");
        frame();

        AccessibleNode named = node("Transport");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(Accessible.NameFrom.EXPLICIT, named.nameFrom());
        assertEquals(panel.localToSceneX(), named.x(), 0.01f, "the outer box, glass included");
        assertEquals(panel.width(), named.width(), 0.01f);
        assertEquals(tree().indexOf(named.id()), node("Play").parent(), describe(tree()));
        assertEquals(child, node("Play").id(),
                "a container that stops being scaffolding must not re-key what is under it");
    }

    /** A tooltip on the panel names it too, through the walk's free default and nothing else. */
    @Test
    void aTooltipOnThePanelNamesItTheSameWay() {
        bindPanel();
        long child = node("Play").id();

        panel.setTooltip("Transport");
        frame();

        AccessibleNode named = node("Transport");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, named.nameFrom(),
                "the free default reaches this class like any other" + describe(tree()));
        assertEquals(child, node("Play").id());
    }

    // --------------------------------------------- why the warning's third suggestion was wrong

    /**
     * Ignoring the panel takes its controls with it, which is why the seam is not that flag.
     *
     * <p>Documentation as much as protection. The paints-and-says-nothing warning used to fire on
     * this class and offer {@code setAccessibleIgnored(true)} as one of its three one-line fixes;
     * on a widget whose whole job is to sit behind controls, that line deletes a demo's entire
     * video transport from the tree. If the decoration seam is ever reverted, this test is the
     * reason it existed.
     */
    @Test
    void ignoringThePanelTakesItsControlsWithIt() {
        bindPanel();
        assertEquals(3, tree().nodeCount(), describe(tree()));

        panel.setAccessibleIgnored(true);
        frame();

        assertEquals(2, tree().nodeCount(),
                "the window and the container, and nothing inside it" + describe(tree()));
        assertThrows(AssertionError.class, () -> node("Play"),
                "the control is gone, and it was never the panel's to hide" + describe(tree()));
    }

    /**
     * A redaction painted by this panel is a picture, and the covered text is published verbatim.
     *
     * <p>The effect samples what the frame has already drawn, so it covers <em>siblings</em> the
     * panel holds no reference to and cannot describe. An application that means to redact
     * something hides or ignores the widget that holds it; the panel is what the eye is shown.
     */
    @Test
    void aRedactionOverASiblingHidesNothingFromAReader() {
        Box secret = new Box("4111 1111 1111 1111");
        BackdropPanel over = new BackdropPanel(REDACTION, Insets.NONE, new Box("Reveal"));
        bind(new Stage(secret, over));

        assertEquals("4111 1111 1111 1111", node("4111 1111 1111 1111").name(),
                "the pixels are hidden from the eye and from nobody else" + describe(tree()));

        secret.setAccessibleIgnored(true);
        frame();

        assertThrows(AssertionError.class, () -> node("4111 1111 1111 1111"),
                "redacting for a reader is done on the widget that holds the secret"
                        + describe(tree()));
        assertEquals("Reveal", node("Reveal").name(),
                "and the control inside the panel is untouched" + describe(tree()));
    }
}
