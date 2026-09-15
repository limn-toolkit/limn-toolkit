package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.backend.Display;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a strip of top-level menu titles becomes in the accessible tree.
 *
 * <p>ADR 039 §7's row for this widget gets the role and the shape of the children right and is
 * wrong or silent about everything else, which is what §7 says a survey written from the outside
 * will be. The row's facet column is a dash, and without a selection facet on the bar the
 * difference between two trees never computes an active-descendant event, so the roving title
 * cursor — the whole of this widget's keyboard model — is silent to a reader. The row publishes
 * the popup state and the verb unconditionally, and {@link PopupMenu} refuses an empty menu on
 * every platform every time, which the gallery's own menu-bar scene ships two of. The row says
 * nothing about which title is open, which title is current, the name a permanently focusable
 * widget owes, the mnemonic's chord, or the boxes — which are emphatically not the widget's box.
 *
 * <p>Everything here drives the bar's public API, the scene's own input, or stands where a bridge
 * stands. Nothing constructs a node, and nothing calls a hook.
 */
class MenuBarAccessibilityTest extends AccessibleComponentTestBase {

    /** Every title in the fixtures below is four characters: 10&nbsp;pt each under RULER. */
    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;

    /**
     * A title's box width, derived the way {@link MenuBarTest} derives it rather than baked: this
     * file's whole claim is that the describe hook is the fifth walk over the same prefix sum, so
     * pinning a literal here would only re-state MEDIUM.
     *
     * @param title the title's text
     * @return what the bar's own formula makes it under {@code RULER}
     */
    private static float titleWidth(String title) {
        return Math.max(Strokes.MIN_HIT_TARGET,
                10f * title.length() + 2 * MEDIUM.menuBarPadH());
    }

    private MenuBar bar;
    private Column root;

    @AfterEach
    void restoreLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * What the toolkit calls the strip, resolved the way the tree resolves it.
     *
     * <p>Never a literal: the name is an {@code I18nString} the toolkit holds and the node carries
     * it already resolved, so a test written against the English word passes only where the
     * process happens to speak English.
     *
     * @param locale the language to resolve under
     * @return the resolved name
     */
    private static String menuBarNameIn(Locale locale) {
        Locale enclosing = I18n.pushScope(locale);
        try {
            return ComponentStrings.MENU_BAR.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** The same, under the language a scene that declares none resolves to. */
    private static String menuBarName() {
        return menuBarNameIn(I18n.processLocale());
    }

    /**
     * Binds {@code built} as the only child of a column, so the strip keeps its own measured box
     * and there is room below it for a dropdown.
     *
     * <p>The scene is assembled here rather than through the base's {@code bind}, and the reason
     * is worth one line: the ruler has to be installed <em>before</em> the first layout. A ruler
     * swapped in afterwards marks the scene's layout dirty but not each widget's own measure
     * cache, which is keyed on the step, the direction, the language and the constraints and not
     * on the ruler — so the bar would keep the width it measured under the degenerate default
     * while every later walk used the real one.
     *
     * @param built the bar under test
     * @param over  the window to bind to
     */
    private void bindBar(MenuBar built, StubWindow over) {
        bar = built;
        root = new Column();
        root.add(bar);
        bridge = RecordingAccessibilityBridge.listening();
        window = over;
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** Three titles, each with a menu that can open, the first carrying an access letter. */
    private static MenuBar threeMenus() {
        return new MenuBar()
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("File", 'F', new Menu().addItem("New", () -> { }))
                .addMenu("Edit", new Menu().addItem("Undo", () -> { }))
                .addMenu("View", new Menu().addItem("Zoom", () -> { }));
    }

    /** The usual fixture: a window that can host an in-scene dropdown. */
    private void bindBar() {
        bindBar(threeMenus(), new StubWindow());
    }

    /** The same bar, over a window that cannot place a popup. */
    private void bindBar(StubWindow over) {
        bindBar(threeMenus(), over);
    }

    /**
     * The gallery's own configuration: one filled menu and two empty ones, which can never open.
     */
    private void bindGalleryBar() {
        bindBar(new MenuBar()
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("File", new Menu().addItem("New", () -> { }))
                .addMenu("Edit", new Menu())
                .addMenu("View", new Menu()), new StubWindow());
    }

    /**
     * A window on a platform that cannot place a popup: the bar builds one, records that it is
     * open, and {@link PopupMenu} refuses to show it. Nothing else in the suite reaches that
     * state, and it is the state every existing {@link MenuBarTest} assertion is written in.
     */
    private static final class NoPopupWindow extends StubWindow {
        @Override
        public Display display() {
            return null;
        }
    }

    /** A window that cannot place a popup until it is told it can. */
    private static final class LateDisplayWindow extends StubWindow {
        boolean canHost;

        @Override
        public Display display() {
            return canHost ? super.display() : null;
        }
    }

    /** @return the bar's own node */
    private AccessibleNode barNode() {
        return node(Accessible.Role.MENU_BAR);
    }

    /** @return the title nodes, in tree order */
    private List<AccessibleNode> titles() {
        return childrenOf(barNode());
    }

    /** Clicks the middle of a title's published box, through the scene. */
    private void clickTitle(int index) {
        AccessibleNode title = titles().get(index);
        scene.mouseButton(Keys.MOUSE_LEFT, true,
                0, title.x() + title.width() / 2, title.y() + title.height() / 2);
        scene.inputBatchEnded();
        frame();
    }

    // ------------------------------------------------------------------------ the shape

    @Test
    void theStripIsOneNamedMenuBarWithOneItemPerTitle() {
        bindBar();

        AccessibleNode strip = barNode();
        assertEquals(menuBarName(), strip.name(),
                "focusable from its constructor, so it is a permanent tab stop and owes a name");
        assertEquals(Accessible.NameFrom.CONTENT, strip.nameFrom());
        assertTrue(strip.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertNotNull(strip.selection(),
                "without the facet the difference never computes an active-descendant event, and "
                        + "the roving title cursor is silent: " + describe(tree()));
        assertFalse(strip.selection().multiSelectable(), "one menu is down at a time");
        assertFalse(strip.selection().required(),
                "unfocused with nothing open there is honestly no current title");

        List<AccessibleNode> found = titles();
        assertEquals(List.of("File", "Edit", "View"),
                found.stream().map(AccessibleNode::name).toList(), describe(tree()));
        for (int i = 0; i < 3; i++) {
            AccessibleNode title = found.get(i);
            assertEquals(Accessible.Role.MENU_ITEM, title.role());
            assertNotNull(title.selectionItem(), describe(tree()));
            assertEquals(i + 1, title.selectionItem().positionInSet());
            assertEquals(3, title.selectionItem().sizeOfSet());
            assertEquals(AccessibleNode.NONE, title.firstChild(),
                    "the cascade is described where it is drawn, not hung off the strip");
        }
    }

    @Test
    void everyTitleIsPublishedAtTheBoxThePointerLandsIn() {
        bindBar();

        float expected = 0;
        for (AccessibleNode title : titles()) {
            assertEquals(bar.x() + expected, title.x(), 1e-3, describe(tree()));
            assertEquals(bar.y(), title.y(), 1e-3, "the whole band, not an inset chip");
            assertEquals(titleWidth("File"), title.width(), 1e-3,
                    "each title's own box, never the widget's and never a nominal width");
            assertEquals(bar.height(), title.height(), 1e-3);
            expected += title.width();
        }

        // The fifth walk agreeing with the four: a click at each published centre opens that
        // title, and toggling it shut again proves the same box answered twice.
        for (int i = 0; i < 3; i++) {
            clickTitle(i);
            assertTrue(bar.isOpen(), "title " + i + " opens at the box the tree published");
            clickTitle(i);
            assertFalse(bar.isOpen(), "and the same point closes it");
        }
    }

    @Test
    void mirroringMovesTheBoxesAndLeavesTheOrderAlone() {
        bindBar();
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> found = titles();
        assertEquals(List.of("File", "Edit", "View"),
                found.stream().map(AccessibleNode::name).toList(),
                "reading order is declaration order in either direction: the coordinate mirrors, "
                        + "the tree does not" + describe(tree()));
        assertEquals(bar.x() + bar.width() - titleWidth("File"), found.get(0).x(), 1e-3,
                "the first title is at the edge reading starts from" + describe(tree()));
        for (int i = 1; i < 3; i++) {
            assertTrue(found.get(i).x() < found.get(i - 1).x(),
                    "boxes decrease in x as the order advances" + describe(tree()));
        }

        clickTitle(2);
        assertTrue(bar.isOpen(), "and the mirrored box is still the box a click lands in");
    }

    // ------------------------------------------------------------------------ empty menus

    @Test
    void aTitleWhoseMenuIsEmptyIsANodeWithNoOperation() throws Exception {
        bindGalleryBar();

        List<AccessibleNode> found = titles();
        assertEquals(List.of("File", "Edit", "View"),
                found.stream().map(AccessibleNode::name).toList(), describe(tree()));
        for (int i = 1; i < 3; i++) {
            AccessibleNode empty = found.get(i);
            assertEquals(Accessible.Role.MENU_ITEM, empty.role(),
                    "it is drawn and a user has to be able to find it");
            assertEquals(i + 1, empty.selectionItem().positionInSet());
            assertEquals(titleWidth("Edit"), empty.width(), 1e-3);
            assertFalse(empty.has(Accessible.State.HAS_POPUP),
                    "PopupMenu refuses an empty menu on every platform, every time");
            assertNull(empty.expand(), describe(tree()));
            assertEquals(java.util.Set.of(Accessible.Action.FOCUS), empty.actions().actions(),
                    "a verb refused every time is worse than an absent verb, so nothing that "
                            + "opens; the cursor move opens nothing and the arrows land here too "
                            + "(decision 11)" + describe(tree()));
        }
        assertTrue(found.get(0).has(Accessible.State.HAS_POPUP),
                "and the filled one still offers everything" + describe(tree()));

        perform(found.get(1).id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        assertFalse(bar.isOpen(),
                "the hook repeats the gate the pointer path does not have, so an assistive "
                        + "technology cannot reach the wedge a click on such a title causes");
    }

    // ------------------------------------------------------------------------ opening

    @Test
    void showMenuOpensTheCascadeThroughTheWidgetsOwnPath() throws Exception {
        bindBar();
        long fileTitle = titles().get(0).id();

        assertTrue(perform(fileTitle, Accessible.Action.SHOW_MENU, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        assertTrue(bar.isOpen(), "the same path a click and Down both take");
        assertFalse(nodesWith(Accessible.State.MODAL).isEmpty(),
                "and a cascade is really mounted, not merely believed in" + describe(tree()));
        List<AccessibleNode> found = titles();
        assertTrue(found.get(0).expand().expanded(), describe(tree()));
        assertFalse(found.get(1).expand().expanded(), "and only that title" + describe(tree()));
        assertFalse(found.get(2).expand().expanded(), describe(tree()));
    }

    // A title already down refusing SHOW_MENU, and COLLAPSE closing the open title and nothing
    // else, are pinned in limn-demo's MenuBarNativePopupTest (moved 2026-09-15): both need a
    // cascade on screen, and the in-scene overlay's input gate refuses every verb on the bar
    // beneath it before the hook runs, so only a window of its own reaches the hook there.

    /**
     * The expanded bit and the verbs are keyed on one fact (decision 2: "by state"): whether a
     * cascade is on screen. {@link MenuBar#isOpen} is the bar's belief, recorded before the popup
     * is asked to show, and the ask is refused over a window that cannot host one; until
     * 2026-09-15 the bit read the popup and the verbs read the belief, so a refused title
     * published {@code collapsed} with {@code {COLLAPSE}} — a closed control whose only verb was
     * the one a closed control refuses, and not the one that opens it.
     */
    @Test
    void aTitleWhoseCascadeWasRefusedPublishesTheVerbsOfAClosedTitle() throws Exception {
        LateDisplayWindow late = new LateDisplayWindow();
        bindBar(late);
        clickTitle(0);
        assertTrue(bar.isOpen(), "the bar believes a menu is down; nothing is on screen");

        AccessibleNode refused = titles().get(0);
        assertFalse(refused.expand().expanded(), describe(tree()));
        assertEquals(java.util.Set.of(Accessible.Action.SHOW_MENU, Accessible.Action.EXPAND),
                refused.actions().actions(),
                "collapsed, so the verbs of a collapsed title, read off the same fact"
                        + describe(tree()));

        perform(refused.id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        frame();
        assertTrue(bar.isOpen(),
                "and the collapse it no longer publishes is refused by the hook as well: the "
                        + "published list is what the title accepts" + describe(tree()));

        late.canHost = true;
        perform(refused.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();
        assertTrue(titles().get(0).expand().expanded(),
                "the published EXPAND is performed, not refused as a title already down: the "
                        + "open is asked again, and this time the window can host it"
                        + describe(tree()));
        assertNull(titles().get(0).actions(),
                "down in the scene, so beneath the cascade's layer, where the scene refuses every "
                        + "verb and the title publishes none (ADR 039 §1.13, amended 2026-09-15); "
                        + "the COLLAPSE of a title down in a window of its own is "
                        + "MenuBarNativePopupTest's" + describe(tree()));
    }

    /**
     * A title publishes exactly the verbs it accepts, by its state (ADR 039 §1.5, amended
     * 2026-09-14; decision 2; CRIT-1): the published list is the only refusal a platform can see,
     * because {@code Host#perform} answers from the snapshot and a later refusal on the UI thread
     * reaches nobody. Until that day the title published {@code SHOW_MENU} alone and accepted
     * {@code EXPAND}, {@code COLLAPSE} and {@code PRESS} in silence — a control one platform
     * invoked through its expand pattern and another could not see.
     */
    @Test
    void aTitlePublishesTheVerbsItAcceptsAndNoOtherByState() throws Exception {
        bindBar();

        assertEquals(java.util.Set.of(Accessible.Action.SHOW_MENU, Accessible.Action.EXPAND,
                        Accessible.Action.FOCUS),
                titles().get(1).actions().actions(),
                "closed: the verb and the synonym that both open it, the cursor move, and not the "
                        + "collapse it would refuse" + describe(tree()));

        perform(titles().get(1).id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();

        assertTrue(bar.isOpen(), "the published synonym is dispatched" + describe(tree()));
        assertTrue(titles().get(1).has(Accessible.State.ACTIVE), describe(tree()));
        for (AccessibleNode title : titles()) {
            assertNull(title.actions(),
                    "open in the scene: the cascade's layer owns the input and the scene refuses "
                            + "every verb on the bar beneath it, so no title publishes one (ADR "
                            + "039 §1.13, amended 2026-09-15). The open title's COLLAPSE and the "
                            + "closed titles' SHOW_MENU and EXPAND without FOCUS are the verbs of "
                            + "a cascade in a window of its own, MenuBarNativePopupTest's"
                            + describe(tree()));
        }
    }

    /**
     * The central rule, read on the bar (ADR 039 §1.9 and §1.13, amended 2026-09-15; semantics
     * 5): while an in-scene cascade holds the scene's input, the titles beneath it publish no verb
     * and every verb sent to one anyway moves nothing, and the verbs the cascade publishes are the
     * ones performed. Until that day the open title published {@code COLLAPSE} and the others
     * {@code SHOW_MENU} and {@code EXPAND}, each reported accepted and dropped by the scene's gate.
     */
    @Test
    void anInSceneCascadeTakesEveryVerbOffTheBarAndPerformsTheOnesItPublishes() throws Exception {
        java.util.concurrent.atomic.AtomicInteger chosen =
                new java.util.concurrent.atomic.AtomicInteger();
        bindBar(new MenuBar()
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("File", new Menu().addItem("New", chosen::incrementAndGet))
                .addMenu("Edit", new Menu().addItem("Undo", () -> { })), new StubWindow());
        assertTrue(perform(titles().get(0).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();
        assertTrue(bar.isOpen());
        assertFalse(nodesWith(Accessible.State.MODAL).isEmpty(),
                "a cascade is really mounted in the scene" + describe(tree()));

        assertNull(barNode().actions(), describe(tree()));
        for (AccessibleNode title : titles()) {
            assertNull(title.actions(), "no title beneath the cascade publishes a verb"
                    + describe(tree()));
        }
        for (Accessible.Action verb : List.of(Accessible.Action.COLLAPSE,
                Accessible.Action.SHOW_MENU, Accessible.Action.EXPAND, Accessible.Action.FOCUS)) {
            perform(titles().get(0).id(), verb, Accessible.Argument.NONE);
            perform(titles().get(1).id(), verb, Accessible.Argument.NONE);
            frame();
            assertTrue(bar.isOpen(), verb + " on a title beneath the cascade is refused"
                    + describe(tree()));
            assertTrue(titles().get(0).expand().expanded() && titles().get(0).has(
                    Accessible.State.ACTIVE) && !titles().get(1).has(Accessible.State.ACTIVE),
                    verb + " moved nothing: the open title is still down and the cursor"
                            + describe(tree()));
        }

        AccessibleNode surface = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode candidate = tree().node(i);
            if (candidate.actions() != null
                    && candidate.actions().has(Accessible.Action.CANCEL)) {
                surface = candidate;
            }
        }
        assertNotNull(surface, "the cascade publishes its dismissal" + describe(tree()));
        assertTrue(perform(surface.id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        frame();
        assertFalse(bar.isOpen(), "and its published CANCEL is performed" + describe(tree()));
        assertNull(titles().get(0).actions(),
                "while the closed cascade fades out its layer still holds the input, the scene "
                        + "still refuses the bar, and the titles still publish nothing"
                        + describe(tree()));

        bindBar(new MenuBar()
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("File", new Menu().addItem("New", chosen::incrementAndGet)),
                new StubWindow());
        assertTrue(perform(titles().get(0).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();
        assertNull(titles().get(0).actions(), describe(tree()));
        AccessibleNode row = node("New");
        assertTrue(row.actions().has(Accessible.Action.PRESS), describe(tree()));
        assertTrue(perform(row.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(1, chosen.get(), "the cascade's published PRESS is performed");
        assertFalse(bar.isOpen(), "and choosing closes the cascade" + describe(tree()));
    }

    @Test
    void pressIsNoLongerASynonymATitleAnswersInSilence() throws Exception {
        bindBar();
        int published = bridge.published.size();

        perform(titles().get(1).id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertFalse(bar.isOpen(),
                "a verb the title does not publish is one it does not perform: a reader cannot "
                        + "see it, and one platform would send it by accident" + describe(tree()));
        assertEquals(published, bridge.published.size(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    // ------------------------------------------------------------------------ the cursor

    /**
     * Decision 11's positive half (2026-09-15): the bar's cursor is not its choice — choosing a
     * title opens its menu — so every title publishes {@code FOCUS} while no menu is down, and
     * performing it takes the keyboard and puts the cursor on that title as Left and Right do,
     * opening nothing.
     */
    @Test
    void focusPutsTheBarsCursorOnATitleAndOpensNothing() throws Exception {
        bindBar();
        for (AccessibleNode title : titles()) {
            assertTrue(title.actions().actions().contains(Accessible.Action.FOCUS),
                    "published on every title while nothing is open" + describe(tree()));
        }
        assertNotEquals(barNode().id(), tree().focused(), "the bar starts without the keyboard");

        assertTrue(perform(titles().get(2).id(), Accessible.Action.FOCUS,
                Accessible.Argument.NONE));
        frame();

        assertEquals(barNode().id(), tree().focused(),
                "the keyboard first, because the cursor is published only while the bar holds it"
                        + describe(tree()));
        assertEquals(List.of("View"), activeNames(), describe(tree()));
        assertEquals(titles().get(2).id(), tree().activeDescendant(), describe(tree()));
        assertFalse(bar.isOpen(), "a cursor move opens nothing");

        scene.keyEvent(Keys.LEFT, true, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals(List.of("Edit"), activeNames(),
                "and it is the field the arrows move, not a copy of it" + describe(tree()));
    }

    /**
     * Decision 11's negative half with a cascade really down, in the scene. Until 2026-09-15 this
     * case built a cascade the window refused to show, which is the bar's belief and not a menu
     * on screen. With one on screen as an overlay of the scene the answer is the central rule's
     * (ADR 039 §1.13, amended that day): the scene refuses every verb beneath the overlay, so no
     * title publishes {@code FOCUS} &mdash; nor any other verb &mdash; and a {@code FOCUS} sent
     * anyway moves nothing. The bar's own gate, that no title takes {@code FOCUS} while a menu is
     * down because the open title is the cursor, is what a cascade in a window of its own reads,
     * and {@code MenuBarNativePopupTest} holds it there.
     */
    @Test
    void whileAMenuIsDownNoTitleTakesFocus() throws Exception {
        bindBar();
        clickTitle(0);
        assertTrue(bar.isOpen(), describe(tree()));
        assertTrue(titles().get(0).expand().expanded(),
                "a cascade is on screen, not merely believed in" + describe(tree()));
        assertFalse(nodesWith(Accessible.State.MODAL).isEmpty(),
                "and it is an overlay of the scene" + describe(tree()));
        for (AccessibleNode title : titles()) {
            assertNull(title.actions(),
                    "beneath the in-scene cascade no title publishes FOCUS or any other verb"
                            + describe(tree()));
        }

        perform(titles().get(2).id(), Accessible.Action.FOCUS, Accessible.Argument.NONE);
        frame();
        assertTrue(titles().get(0).has(Accessible.State.ACTIVE)
                        && !titles().get(2).has(Accessible.State.ACTIVE),
                "sent anyway, the scene refuses it: the open title is still the cursor"
                        + describe(tree()));
        assertTrue(bar.isOpen() && titles().get(0).expand().expanded(),
                "and nothing else opened or closed" + describe(tree()));
    }

    @Test
    void theKeyboardCursorIsPublishedAndAPointerHoverIsNot() {
        bindBar();

        scene.requestFocus(bar);
        frame();
        assertEquals(List.of("File"), activeNames(), describe(tree()));
        assertTrue(titles().get(0).selectionItem().selected(),
                "selection and cursor are one thing here");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                bridge.events.toString());

        bridge.events.clear();
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        frame();

        assertEquals(List.of("Edit"), activeNames(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "Right along the strip is the event a reader follows: " + bridge.events);

        scene.requestFocus(null);
        frame();
        bridge.events.clear();

        AccessibleNode third = titles().get(2);
        scene.mouseMoved(third.x() + third.width() / 2, third.y() + third.height() / 2);
        scene.inputBatchEnded();
        frame();

        assertEquals(List.of(), activeNames(),
                "a bare hover would announce a title to a user whose keyboard is in a text "
                        + "field, once per mouse move across the strip" + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.SELECTED).stream()
                .map(AccessibleNode::name).toList(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                bridge.events.toString());
    }

    @Test
    void theOpenTitleStaysCurrentWhenThePointerLeavesTheStrip() {
        bindBar(new NoPopupWindow());
        clickTitle(0);
        assertEquals(List.of("File"), activeNames(), describe(tree()));

        // Off the strip entirely, which is what happens the moment the pointer moves down into
        // the cascade: the bar's own EXIT clears the hover unconditionally, open or not.
        scene.mouseMoved(200, 200);
        scene.inputBatchEnded();
        frame();

        assertTrue(bar.isOpen());
        assertEquals(List.of("File"), activeNames(),
                "the open menu is consulted before the hover, and the two genuinely diverge"
                        + describe(tree()));
    }

    @Test
    void theExpandedBitNeverOutlivesTheCascade() {
        bindBar(new NoPopupWindow());

        clickTitle(0);

        assertTrue(bar.isOpen(), "the bar's belief, which is what isOpen() has always answered");
        assertEquals(List.of(), nodesWith(Accessible.State.EXPANDED).stream()
                .map(AccessibleNode::name).toList(),
                "read from the popup and not from the index: openMenu records the index before "
                        + "asking the popup to show, and the ask is refused over a window that "
                        + "cannot host one" + describe(tree()));
        assertFalse(titles().get(0).expand().expanded(), describe(tree()));
    }

    // ------------------------------------------------------------------------ names and chords

    @Test
    void aTitleWithAnAccessLetterCarriesItsChordAndTheOthersCarryNone() {
        bindBar();

        assertEquals(new Accelerator('F', Keys.MOD_ALT).display(),
                titles().get(0).actions().keyBinding(),
                "the only route by which a mnemonic reaches a user who cannot see the underline");
        assertNull(titles().get(1).actions().keyBinding(), describe(tree()));
        assertEquals(Accelerator.of(Keys.F10).display(), barNode().actions().keyBinding(),
                "and the one chord that reaches the strip from anywhere, since a bare Alt is not "
                        + "an accelerator any platform can be told about");
    }

    @Test
    void theSuppliedNameFollowsTheLanguageAndAnApplicationWinsOverIt() {
        bindBar();
        assertEquals(menuBarName(), barNode().name());

        I18n.setLocale(Locale.FRENCH);
        frame();
        assertEquals(menuBarNameIn(Locale.FRENCH), barNode().name(),
                "the toolkit's own string, re-resolved with no subscription anywhere"
                        + describe(tree()));

        I18n.setLocale(Locale.ENGLISH);
        bar.setAccessibleName("Application menus");
        frame();
        assertEquals("Application menus", node("Application menus").name(),
                "an application's name is applied after the hook and wins" + describe(tree()));
    }

    // ------------------------------------------------------------------------ nothing invented

    @Test
    void theBarDeclaresNothingItHasNoBusinessDeclaring() {
        bindBar();

        AccessibleNode strip = barNode();
        assertNull(strip.value(), describe(tree()));
        assertNull(strip.toggle(), describe(tree()));
        assertNull(strip.text(), describe(tree()));
        assertNull(strip.scroll(), describe(tree()));
        assertNull(strip.expand(), "the bar does not expand; its titles do");
        assertEquals(List.of(), strip.relations(),
                "with nothing open the walk has no popup to link, and the bar declares none");
        for (AccessibleNode title : titles()) {
            assertNull(title.value(), describe(tree()));
            assertNull(title.toggle(), describe(tree()));
            assertNull(title.text(), describe(tree()));
            assertEquals(List.of(), title.relations(),
                    "the open dropdown is linked to the bar by the walk, never to the title: two "
                            + "nodes in one tree claiming the same popup resolve to nothing");
        }
    }

    // ------------------------------------------------------------------------ what it costs

    @Test
    void aQuietBarAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindBar();
        scene.requestFocus(bar);
        frame();

        int published = bridge.published.size();
        bridge.events.clear();
        for (int i = 0; i < 20; i++) {
            bar.invalidate();
            frame();
        }
        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot" + describe(tree()));
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        // Shaping a title inside the hook is a ShapedText and its metrics per title per damaged
        // frame; Accelerator.display() is a builder and a string; the variable-argument action
        // call is an array. This is the only place any of the three is visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            bar.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            bar.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a strip that did not move must cost no memory: the widths come from "
                        + "the cache the measure filled, every name is a held I18nString compared "
                        + "by reference, and the chord was spelled when the menu was added");
    }

    /**
     * @return the names of every title published active, in tree order. Filtered to the titles
     *         because the window's own node carries {@code ACTIVE} to say which window the user
     *         is in, which is a different fact wearing the same name.
     */
    private List<String> activeNames() {
        return nodesWith(Accessible.State.ACTIVE).stream()
                .filter(node -> node.role() == Accessible.Role.MENU_ITEM)
                .map(AccessibleNode::name)
                .toList();
    }

    /**
     * A mnemonic this toolkit cannot spell publishes no key binding at all.
     *
     * <p>{@code addMenu} takes "a letter or a digit" through {@code Character.isLetterOrDigit},
     * which admits every script, and {@code Accelerator} names a code outside printable ASCII as
     * {@code "Key<code>"}. So a Cyrillic access letter published {@code Alt+Key1060} — unreadable,
     * and a chord that can never fire either, because both key routes compare against
     * {@code Keys} constants and nothing produces that code. A binding withheld is an absence a
     * reader can live with; one like that is a lie with a keystroke attached.
     */
    @Test
    void aMnemonicThisToolkitCannotSpellPublishesNoBindingRatherThanAWrongOne() {
        bindBar(new MenuBar()
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("File", 'F', new Menu().addItem("New", () -> { }))
                .addMenu("Файл", 'ф', new Menu().addItem("Создать", () -> { })), new StubWindow());

        List<AccessibleNode> titles = titles();
        assertEquals(2, titles.size(), describe(tree()));
        String ascii = titles.get(0).actions().keyBinding();
        assertNotNull(ascii, "printable ASCII is nameable, and that one is published"
                + describe(tree()));
        // The modifier's spelling is the host platform's -- Alt+F on Windows and Linux, the option
        // glyph on macOS -- so what is asserted is the letter and the fact that it was published,
        // not a rendering this test would then only prove on the machine it ran on.
        assertTrue(ascii.endsWith("F"), ascii + describe(tree()));
        assertNull(titles.get(1).actions().keyBinding(),
                "and the one that would have read Alt+Key1060 says nothing instead"
                        + describe(tree()));
    }
}
