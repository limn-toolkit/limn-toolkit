package limn.components;

import limn.testing.StubWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the wrapper {@link ContextMenus#attach} returns becomes in the accessible tree: one group
 * over the content's own rectangle, saying that asking here may open a menu, and offering the ask.
 *
 * <p><b>ADR 039 §7's row asks for the opposite and cannot have it.</b> The row says the region is
 * transparent and that {@code HAS_POPUP} and the menu verb go "on its own child, through
 * {@code onAccessibilityChild}", and §1.5 and §1.6 repeat the claim in prose. The walk records the
 * <em>owner</em> of a node as the widget the node came from and the scene dispatches an action
 * strictly to that owner, with no fallback to a parent, so a verb the region wrote onto its child
 * would be dispatched to the child: an arbitrary application widget whose action hook is
 * {@code Widget}'s and answers false. The row therefore publishes a "show context menu" that is
 * refused on all three platforms, which §1.6's own rule — an operation is never deleted with the
 * box that carried it — is worse off for than an absent verb, because the platform reports a
 * failure rather than an absence. Nor does the row save a node: a state and a verb written onto a
 * {@code Column} or a {@code Padding} make that scaffold survive §1.6's predicate, so the node
 * appears anyway, one level deeper, carrying a verb nothing can perform. And the row never
 * considers a child that already speaks for itself — a {@code ComboBox}, a
 * {@code ColorPickerButton} and a {@code TextField} all declare {@code HAS_POPUP} of their own.
 * The one true sentence in the row is the argument for describing the wrapper: the region measures
 * and lays out to exactly its content's box, so the two rectangles are the same rectangle and only
 * one of the two nodes can open the menu.
 *
 * <p><b>What the region deliberately does not have is a name.</b> It holds no {@code I18nString}
 * and derives nothing, and borrowing the content's would say one thing twice. The hatch is public
 * and per instance, because {@code attach} hands the wrapper back: an application names it, binds
 * a caption to it or gives it a tooltip, and a case below pins the first of those.
 *
 * <p>Every case drives {@code ContextMenus.attach} and {@code Widget}'s public setters on a bound
 * scene, or calls the scene from where a bridge stands. Nothing constructs a node and nothing calls
 * a hook. The supplier is always counting, because the number of times an application's callback
 * runs is the whole observation this widget affords: what it builds is a {@code Menu} the class
 * hands to a {@code PopupMenu} and to nobody else, so the menu's placement — the lower leading
 * corner and its right-to-left mirror — is not assertable here, exactly as {@code ContextMenusTest}
 * already records of the two gesture routes.
 */
class ContextRegionAccessibilityTest extends AccessibleComponentTestBase {

    /** How far the padding pushes the region off the scene origin, on both axes. */
    private static final float INSET = 12;

    /**
     * A supplier that counts, so that "was the application asked" is a number.
     *
     * <p>It answers {@code null} until a case says otherwise — the documented "not here", and the
     * answer that lets a case drive the verb without a popup mounting behind it.
     */
    private static final class CountingSource implements Supplier<Menu> {

        /** How many times the region has asked for a menu. */
        int asked;

        /** What to answer, replaceable between actions. */
        Supplier<Menu> answer = () -> null;

        /** Answers a fresh one-row menu, which really opens. */
        void oneRow() {
            answer = () -> new Menu().addItem("Rename", () -> { });
        }

        /** Answers a menu with no rows, which is the second shape of "not here". */
        void empty() {
            answer = Menu::new;
        }

        @Override
        public Menu get() {
            asked++;
            return answer.get();
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * Puts {@code content} in an attached region, inside a padding so that the region is nowhere
     * near the scene origin, inside a column so that it is measured loose rather than stretched to
     * the canvas.
     *
     * @param content what to give a menu to
     * @param source  the supplier to attach
     * @return the wrapper {@code attach} returned, which is the naming surface
     */
    private Widget bindAttached(Widget content, Supplier<Menu> source) {
        return bindAttached(content, source, new StubWindow());
    }

    /**
     * The same, over a window a case chose, and with a focusable button beside the region.
     *
     * <p>The button is not decoration. It is the widget that makes the difference between the two
     * ways of raising the menu visible at all: with nothing in the scene focusable,
     * {@code showForFocus} falls back to its anchor and behaves identically to anchoring on this
     * node, so a fixture without it would pass whichever of the two the hook used.
     */
    private Widget bindAttached(Widget content, Supplier<Menu> source, StubWindow over) {
        Widget attached = ContextMenus.attach(content, source);
        padding = new Padding(Insets.all(INSET), attached);
        elsewhere = new Button("Elsewhere");
        Column root = new Column();
        root.add(elsewhere);
        root.add(padding);
        bind(root, over);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
        return attached;
    }

    /** The box between the scene and the region, for the case that disables it. */
    private Padding padding;

    /** A focusable widget outside the region, for the case that opens the menu. */
    private Button elsewhere;

    /** @return the region's node, which is the only group in a tree with no menu open */
    private AccessibleNode region() {
        return node(Accessible.Role.GROUP);
    }


    /** @return the identifier {@code kind} resolves to on this node, or zero when it has none */
    private long targetOf(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------ the node

    /**
     * The region is the node that carries the menu, and its rectangle is the content's.
     *
     * <p>This is the whole verdict in one case. Restore §7's row and it fails twice over: the
     * group is gone, and {@code HAS_POPUP} is on the label.
     */
    @Test
    void theRegionIsTheNodeThatCarriesTheMenu() {
        bindAttached(new Label("Files"), new CountingSource());

        AccessibleNode group = region();
        assertEquals("", group.name(),
                "no name of its own: the region holds no string and must not borrow the "
                        + "content's, which names its own node" + describe(tree()));
        assertEquals("", group.description(), describe(tree()));
        assertTrue(group.has(Accessible.State.HAS_POPUP),
                "asking at this rectangle may open a menu" + describe(tree()));
        assertTrue(group.actions().has(Accessible.Action.SHOW_MENU), describe(tree()));
        assertEquals(1, group.actions().actions().size(),
                "and nothing else: FOCUS and SCROLL_INTO_VIEW are the walk's, and only for a "
                        + "focusable widget, which this deliberately is not" + describe(tree()));

        AccessibleNode label = node(Accessible.Role.LABEL);
        assertEquals(List.of(label), childrenOf(group),
                "the content is published unchanged underneath it, with its own identity and its "
                        + "own name" + describe(tree()));
        assertEquals(group.x(), label.x(), describe(tree()));
        assertEquals(group.y(), label.y(), describe(tree()));
        assertEquals(group.width(), label.width(), describe(tree()));
        assertEquals(group.height(), label.height(),
                "onMeasure returns the content's measure and onLayout hands it the whole box, so "
                        + "the wrapper's rectangle IS the rectangle a user would right-click"
                        + describe(tree()));
        assertNotEquals(0f, group.x(),
                "and the padding really did move it off the origin, which is the only reason the "
                        + "four numbers above can tell a local box from a scene one"
                        + describe(tree()));
    }

    /**
     * Attaching a menu adds one node and no level: the scaffolding inside the region is still
     * deleted.
     *
     * <p>This is the half of §7's row that is arithmetic rather than dispatch. Writing a state and
     * a verb onto the child materialises the very node the row says it is avoiding — and
     * materialises a container, one level deeper, carrying an operation nothing can perform.
     */
    @Test
    void attachingAMenuDoesNotMaterialiseTheScaffoldingItWraps() {
        Column inner = new Column();
        inner.add(new Button("Rename"));
        inner.add(new Button("Delete"));
        bindAttached(inner, new CountingSource());

        AccessibleNode group = region();
        List<AccessibleNode> children = childrenOf(group);
        assertEquals(2, children.size(),
                "the column is still transparent: it declares nothing of its own and nothing was "
                        + "written onto it" + describe(tree()));
        for (AccessibleNode child : children) {
            assertEquals(Accessible.Role.BUTTON, child.role(), describe(tree()));
            assertFalse(child.has(Accessible.State.HAS_POPUP),
                    "and no state was written onto a child either" + describe(tree()));
            assertFalse(child.actions().has(Accessible.Action.SHOW_MENU),
                    "a verb here would be dispatched to the button, whose hook knows nothing "
                            + "about the region's menu" + describe(tree()));
        }
    }

    /**
     * The publish step never asks the application for a menu, however many damaged frames run
     * over it.
     *
     * <p>{@code HAS_POPUP} is unconditional for exactly this reason. Making it conditional on the
     * supplier answering something would run application code inside the publish step and build a
     * {@code Menu} per damaged frame, to conclude that nothing had moved — and
     * {@code AccessiblePublishCostTest} could not attribute that allocation to the walk, because
     * the object belongs to the application.
     */
    @Test
    void thePublishStepNeverAsksForTheMenu() {
        Label label = new Label("Files");
        CountingSource source = new CountingSource();
        bindAttached(label, source);

        int published = bridge.published.size();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                label.setText("Folders");
            }
            frame();
        }

        assertTrue(bridge.published.size() > published,
                "a real edit really did republish the tree, so the describe hook really did run "
                        + "again" + describe(tree()));
        assertEquals(0, source.asked,
                "and it never asked the application what its menu would be" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the verb

    /**
     * The menu verb reaches the region's own path, once per action, and answers honestly when the
     * supplier says "not here".
     *
     * <p>The count is the assertion that matters. A hook that guarded with one {@code get()} and
     * opened with another would take it to two on one action, and the class's contract — the
     * supplier is asked at the moment of the gesture — would be broken for the one route that has
     * no gesture. Both shapes of "not here" are driven, and the second is the one that catches
     * that mistake: a hook that guarded on {@code null} alone would ask twice for a menu that
     * exists and has no rows.
     */
    @Test
    void showMenuFromAnAssistiveTechnologyAsksTheRegionOnce() throws Exception {
        CountingSource source = new CountingSource();
        bindAttached(new Label("Files"), source);

        assertTrue(perform(region().id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE),
                "the identifier resolves, which is all the immediate answer can honestly mean");
        assertEquals(1, source.asked, "asked once for the absent menu" + describe(tree()));

        source.empty();
        assertTrue(perform(region().id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE));
        assertEquals(2, source.asked,
                "and once for the one with no rows, which is a menu the region declines to open "
                        + "and not a reason to ask again" + describe(tree()));
    }

    /** No other verb reaches the supplier, and none of them throws. */
    @Test
    void noOtherVerbReachesTheSupplier() throws Exception {
        CountingSource source = new CountingSource();
        bindAttached(new Label("Files"), source);

        long id = region().id();
        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(id, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0.5));

        assertEquals(0, source.asked,
                "SHOW_MENU is the only verb this node offers, and the hook answers false for the "
                        + "rest rather than opening a menu for a press" + describe(tree()));
    }

    /**
     * The verb reaches the real {@link PopupMenu} path, and the menu names the region as its host.
     *
     * <p>Bound over a window that cannot place one of its own — the Wayland shape, where the
     * documented fallback is an in-scene overlay — because no headless test can create a native
     * popup window. Nothing here asserts what the surface's node <em>is</em>: that widget is still
     * undescribed and its own step will change the answer. What is asserted is which node the pair
     * of relations resolves to, and this is the case that fails if the hook is "simplified" to
     * {@code showForFocus}: the popup's host becomes whatever holds focus, {@code POPUP_FOR}
     * resolves to another node, and a reader cannot get from the group to the menu it just opened.
     * The keyboard route may take that shortcut because a key event reaches the region only by
     * bubbling out of a focusable widget inside it; a verb carries no such guarantee, which is why
     * the button holding focus here is <em>outside</em> the region.
     */
    @Test
    void theMenuMountsInSceneAndNamesTheRegionAsItsHost() throws Exception {
        CountingSource source = new CountingSource();
        source.oneRow();
        bindAttached(new Label("Files"), source, new StubWindow(false));
        elsewhere.requestFocus();
        frame();

        AccessibleNode before = region();
        assertEquals(List.of(), before.relations(), "no menu, no relation" + describe(tree()));
        long group = before.id();
        int nodes = tree().nodeCount();

        assertTrue(perform(group, Accessible.Action.SHOW_MENU, Accessible.Argument.NONE));
        frame();

        assertEquals(1, source.asked, describe(tree()));
        assertTrue(tree().nodeCount() > nodes,
                "the overlay joined the tree" + describe(tree()));
        long surface = targetOf(node(group), Accessible.Relation.CONTROLLER_FOR);
        assertNotEquals(0, surface,
                "the region controls the layer the menu mounted as" + describe(tree()));
        assertEquals(group, targetOf(node(surface), Accessible.Relation.POPUP_FOR),
                "and the menu points back at the node that declared HAS_POPUP rather than at "
                        + "whatever happened to hold focus" + describe(tree()));
    }

    /**
     * A region inside a disabled container publishes no verb, and the scene refuses one sent
     * anyway.
     *
     * <p>Until 2026-09-15 the verb stayed published here, on the argument that withholding it would
     * make an action appear and disappear on a property change that is not about structure. The
     * published list is what a node accepts now (semantics 5), and the scene refuses every verb
     * under a disabled ancestor, so the walk withdraws it from every node that is not
     * {@code ENABLED} (ADR 039 §1.5, amended that day). The hook still needs no enabled check of
     * its own: §1.9's gate walks the widget and every ancestor.
     */
    @Test
    void aRegionInsideADisabledContainerRefusesTheMenu() throws Exception {
        CountingSource source = new CountingSource();
        bindAttached(new Label("Files"), source);
        padding.setEnabled(false);
        frame();

        AccessibleNode group = region();
        assertFalse(group.has(Accessible.State.ENABLED),
                "the ancestor's flag is inherited down the walk" + describe(tree()));
        assertNull(group.actions(),
                "and no verb is published, because the scene refuses every one there"
                        + describe(tree()));

        perform(group.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);

        assertEquals(0, source.asked,
                "the scene's ancestor gate refused it before the hook ran" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the name

    /**
     * Naming the wrapper names the group, and the content keeps its own name.
     *
     * <p>{@code attach} returns the region as the widget to put in the tree, so the whole naming
     * surface is public and per instance. That is the documented answer to a reader landing on an
     * unnamed group, and the reason the hook must not invent one: an invented name would be here
     * for every application that never asked for it, and would be overwritten by this one anyway.
     */
    @Test
    void namingTheRegionNamesTheGroup() {
        Widget attached = bindAttached(new Label("Files"), new CountingSource());

        attached.setAccessibleName("File list");
        frame();

        AccessibleNode group = region();
        assertEquals("File list", group.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, group.nameFrom(),
                "an application's own word, and not something derived" + describe(tree()));
        assertEquals("Files", node(Accessible.Role.LABEL).name(),
                "and the content still says what it says" + describe(tree()));
    }
}
