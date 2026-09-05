package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an open menu cascade becomes in the accessible tree.
 *
 * <p>ADR 039 §7's row for this widget is wrong in the place that decides everything else: it makes
 * the surface the {@code MENU} and gives it the root column's rectangle. The surface is neither.
 * In scene it is an overlay laid out over the whole window, and in a window of its own that window
 * is sized to the union of every open column, so its box equals the root column only while no
 * submenu is open — and the box it really has is the capture layer, where a press that misses
 * every column dismisses the menu. So it is a {@code GROUP} with a {@code CANCEL}, and every
 * column, root included, is a {@code MENU} of its own with the menu's rectangle.
 *
 * <p>The row is also silent about the surface being focusable, which is what makes all of that
 * compulsory: it holds the keyboard for the whole life of the cascade, so it can never be
 * transparent, it owes a role or it publishes as {@code UNKNOWN}, and it owes a name no
 * application can supply. It gives it no verb at all. It says nothing about the two scroll-hint
 * bands, which take a click before the row painted beneath them. And it stops at "keyed by the
 * {@code MenuItem}'s serial", which is right for rows and leaves the columns and the bands
 * unkeyed.
 *
 * <p>Everything here drives the public API, the scene's own input, or stands where a bridge
 * stands. Nothing constructs a node, and nothing calls a hook.
 */
class PopupMenuAccessibilityTest extends AccessibleComponentTestBase {

    /** The clock the fades tick on, so a close can be driven to completion. */
    private final AtomicLong clock = new AtomicLong();

    /** What the fixture's command rows report when they are chosen. */
    private final List<String> chosen = new ArrayList<>();

    /** What its check row reports through {@code onToggle}. */
    private final List<Boolean> toggled = new ArrayList<>();

    private PopupMenu popup;
    private Menu root;

    @AfterEach
    void closeAndRestore() {
        if (popup != null && popup.isOpen()) {
            popup.close();
        }
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * What the toolkit calls the capture layer, resolved the way the tree resolves it rather than
     * written out: the node carries an already-resolved {@code I18nString}, so a test spelling the
     * English word passes only where the process happens to speak English.
     *
     * @return the resolved name
     */
    private static String popupName() {
        Locale enclosing = I18n.pushScope(I18n.processLocale());
        try {
            return ComponentStrings.MENU_POPUP.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** The same, for a scroll band. */
    private static String bandName(boolean previous) {
        Locale enclosing = I18n.pushScope(I18n.processLocale());
        try {
            return (previous ? ComponentStrings.MENU_SCROLL_PREVIOUS
                    : ComponentStrings.MENU_SCROLL_NEXT).get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /**
     * A command, a check, a rule, a submenu, a submenu with nothing in it, a disabled row and a
     * command with a chord: every shape a row can take, in one column.
     *
     * @return the menu
     */
    private Menu everyShape() {
        return new Menu()
                .addItem("New", () -> chosen.add("New"))
                .addCheck("Wrap", false, on -> toggled.add(on))
                .addSeparator()
                .addSubmenu("Export", new Menu()
                        .addItem("PNG", () -> chosen.add("PNG"))
                        .addItem("SVG", () -> chosen.add("SVG")))
                .addSubmenu("Empty", new Menu())
                .add(MenuItem.of("Paste", () -> chosen.add("Paste")).setEnabled(false))
                .add(MenuItem.of("Quit", () -> chosen.add("Quit"))
                        .setAccelerator(Accelerator.command(Keys.Q)));
    }

    /**
     * Builds a scene over a stub window, binds it, and opens {@code menu} in scene at
     * {@code (20, 20)}.
     *
     * <p>The scene is assembled here rather than through the base's {@code bind} for the reason
     * the menu bar's own file gives: the ruler has to be installed before the first layout, or the
     * columns are measured against the degenerate default while every later pass uses the real
     * one. The presentation is asked for explicitly because the default is a window of its own,
     * and {@link StubWindow#backend()} throws by design — no headless test can create one.
     *
     * @param menu      what to show
     * @param direction the direction the whole cascade is opened at, which is captured once
     * @param anchorX   the scene x the cascade's leading corner is asked for
     */
    private void open(Menu menu, LayoutDirection direction, float anchorX) {
        root = menu;
        chosen.clear();
        toggled.clear();
        Label anchor = new Label("anchor");
        Column column = new Column();
        column.add(anchor);
        column.setLayoutDirection(direction);

        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new FakeCanvas(400, 300);
        scene = new Scene(column, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();

        popup = new PopupMenu(menu).setDisplayMode(DisplayMode.IN_SCENE);
        popup.showAt(anchor, anchorX, 20);
        frame();
        assertTrue(popup.isOpen(), "the fixture did not open");
        assertEquals(DisplayMode.IN_SCENE, popup.displayMode(), "and not in a window of its own");
        bridge.events.clear();
    }

    /** The usual fixture: every row shape, reading left to right, opened near the origin. */
    private void open(Menu menu) {
        open(menu, LayoutDirection.LTR, 20);
    }

    /** Advances the clock past the overlay fades, which is what removes a closed cascade. */
    private void settle() {
        for (int i = 0; i < 40; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            frame();
        }
    }

    /** A press and a release at one point, which is what synthesizes a click. */
    private void click(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
        frame();
    }

    private void key(int code) {
        scene.keyEvent(code, true, false, 0);
        scene.inputBatchEnded();
        frame();
    }

    // ------------------------------------------------------------------------------ reading it

    /** @return the capture layer's node */
    private AccessibleNode surface() {
        return node(popupName());
    }

    /**
     * @param under a node to read the menu child of
     * @return the one {@code MENU} directly under it
     */
    private AccessibleNode columnUnder(AccessibleNode under) {
        List<AccessibleNode> menus = childrenOf(under).stream()
                .filter(child -> child.role() == Accessible.Role.MENU)
                .toList();
        assertEquals(1, menus.size(), "expected one menu under " + under.name() + describe(tree()));
        return menus.get(0);
    }

    /** @return the root column's node */
    private AccessibleNode rootColumn() {
        return columnUnder(surface());
    }

    /**
     * @param column a column's node
     * @return its rows, in tree order, without the scroll bands
     */
    private List<AccessibleNode> rowsOf(AccessibleNode column) {
        return childrenOf(column).stream()
                .filter(child -> child.role() != Accessible.Role.BUTTON)
                .toList();
    }

    /**
     * @param column a column's node
     * @return the scroll bands it publishes, in tree order: both while it is between its clamps,
     *         one while it is parked at one of them, none when it does not scroll
     */
    private List<AccessibleNode> bandsOf(AccessibleNode column) {
        return childrenOf(column).stream()
                .filter(child -> child.role() == Accessible.Role.BUTTON)
                .toList();
    }

    /**
     * The offset the column's own layout puts a row at, derived from the row the cascade was
     * opened at rather than baked: this file's whole claim is that the describe hook is the third
     * walk over the same numbers, so a literal here would only re-state MEDIUM.
     *
     * @param menu  the menu the column was built from
     * @param index the row
     * @return its top, relative to the column's content top
     */
    private float rowTop(Menu menu, int index) {
        SizeTokens t = popup.tokensForTest();
        float y = t.menuPadV();
        for (int i = 0; i < index; i++) {
            y += menu.items().get(i).isSeparator() ? t.separatorBox() : t.menuRowHeight();
        }
        return y;
    }

    /**
     * @param id the identifier to look up
     * @return the node carrying it
     */
    private AccessibleNode byId(long id) {
        AccessibleTree tree = tree();
        int at = tree.indexOf(id);
        assertNotEquals(AccessibleNode.NONE, at, "node " + id + " is gone" + describe(tree));
        return tree.node(at);
    }

    // ------------------------------------------------------------------------------ the shape

    @Test
    void theSurfaceIsTheCaptureLayerAndEveryColumnIsAMenuOfItsOwn() {
        open(everyShape());

        AccessibleNode layer = surface();
        assertEquals(Accessible.Role.GROUP, layer.role(),
                "not the MENU: the columns are the menus and they have the menus' rectangles"
                        + describe(tree()));
        assertEquals(popupName(), layer.name(),
                "focusable from its constructor and focused for the whole life of the cascade, "
                        + "so it owes a name no application can reach it to supply");
        assertEquals(Accessible.NameFrom.CONTENT, layer.nameFrom());
        assertTrue(layer.has(Accessible.State.FOCUSED), describe(tree()));
        assertTrue(layer.has(Accessible.State.MODAL),
                "stamped by the walk on a parentless top overlay, never declared here"
                        + describe(tree()));
        assertNotNull(layer.selection(),
                "focus never leaves this node, so the cursor reaches a reader only as its active "
                        + "descendant, and only a node with the facet has one" + describe(tree()));
        assertFalse(layer.selection().multiSelectable(), "one row is current");
        assertFalse(layer.selection().required(),
                "a column of nothing but disabled rows has no current row at all");
        assertTrue(layer.actions().actions().contains(Accessible.Action.CANCEL),
                "a press anywhere on this box that misses every column closes the menu"
                        + describe(tree()));

        AccessibleNode menu = rootColumn();
        assertTrue(menu.has(Accessible.State.VERTICAL), "the mirror of the bar's HORIZONTAL");
        assertNotNull(menu.selection(), describe(tree()));
        assertEquals("", menu.name(),
                "nothing names the root column, and the layer above already carries a name");

        assertEquals(List.of("New", "Wrap", "", "Export", "Empty", "Paste", "Quit"),
                rowsOf(menu).stream().map(AccessibleNode::name).toList(), describe(tree()));
        assertEquals(List.of(Accessible.Role.MENU_ITEM, Accessible.Role.CHECK_MENU_ITEM,
                        Accessible.Role.SEPARATOR, Accessible.Role.MENU_ITEM,
                        Accessible.Role.MENU_ITEM, Accessible.Role.MENU_ITEM,
                        Accessible.Role.MENU_ITEM),
                rowsOf(menu).stream().map(AccessibleNode::role).toList(), describe(tree()));
    }

    @Test
    void everyRowIsNumberedOverTheRowsAReaderCanReach() {
        open(everyShape());

        List<AccessibleNode> rows = rowsOf(rootColumn());
        assertNull(rows.get(2).selectionItem(),
                "a rule is a real hit region that does nothing, and is in no set" + describe(tree()));
        int[] expected = {1, 2, 0, 3, 4, 5, 6};
        for (int i = 0; i < rows.size(); i++) {
            if (expected[i] == 0) {
                continue;
            }
            assertEquals(expected[i], rows.get(i).selectionItem().positionInSet(),
                    rows.get(i).name() + describe(tree()));
            assertEquals(6, rows.get(i).selectionItem().sizeOfSet(),
                    "counted over the rows that are not rules" + describe(tree()));
        }
    }

    @Test
    void aCheckRowCarriesItsStateAndACommandWithAChordCarriesTheChord() {
        open(everyShape());

        List<AccessibleNode> rows = rowsOf(rootColumn());
        assertEquals(limn.accessibility.ToggleFacet.State.OFF, rows.get(1).toggle().state(),
                describe(tree()));
        assertNull(rows.get(0).toggle(), "a command does not toggle");
        assertEquals(Accelerator.command(Keys.Q).display(), rows.get(6).actions().keyBinding(),
                "the string the column resolved when it was built" + describe(tree()));
        assertNull(rows.get(0).actions().keyBinding(), describe(tree()));
    }

    @Test
    void aRowThatCannotBeChosenCarriesNoVerbAndSaysSoInNoOtherWay() throws Exception {
        open(everyShape());

        List<AccessibleNode> rows = rowsOf(rootColumn());
        AccessibleNode rule = rows.get(2);
        AccessibleNode empty = rows.get(4);
        AccessibleNode disabled = rows.get(5);

        assertNull(rule.actions(), describe(tree()));
        assertNull(empty.actions(),
                "hasSubmenu() is false for a submenu with nothing in it while isSelectable() "
                        + "stays true and activate() is a no-op" + describe(tree()));
        assertFalse(empty.has(Accessible.State.HAS_POPUP), describe(tree()));
        assertNull(empty.expand(), describe(tree()));
        assertNull(disabled.actions(), describe(tree()));
        assertTrue(disabled.has(Accessible.State.ENABLED),
                "a synthetic child takes the owner's enabled bit by every route there is, so the "
                        + "absent verb is the whole of it -- SegmentedControl's recorded case, and "
                        + "the common one here: every text field ships disabled Cut and Copy rows");

        // The scene accepts an identifier it published and re-checks the rest on arrival, so what
        // a refusal looks like from out here is that nothing happened.
        int published = bridge.published.size();
        perform(empty.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(disabled.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(rule.id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), chosen, describe(tree()));
        assertTrue(popup.isOpen(), "nothing was chosen and nothing closed");
        assertEquals(published, bridge.published.size(),
                "and nothing moved in the tree either" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the boxes

    @Test
    void theSurfacesBoxIsTheWholeSceneAndTheMenusIsTheColumns() {
        open(everyShape());

        AccessibleNode layer = surface();
        AccessibleNode menu = rootColumn();
        assertEquals(0, layer.x(), 1e-3, describe(tree()));
        assertEquals(0, layer.y(), 1e-3);
        assertEquals(400, layer.width(), 1e-3,
                "an overlay is laid out over the whole scene, which is the region a press "
                        + "dismisses from" + describe(tree()));
        assertEquals(300, layer.height(), 1e-3);

        // The survey's claim is that these two are the same rectangle, so the test states the
        // inequality rather than only checking the column.
        assertNotEquals(layer.width(), menu.width(),
                "the root column's rectangle is not the surface node's" + describe(tree()));
        assertNotEquals(layer.height(), menu.height(), describe(tree()));
        assertEquals(20, menu.x(), 1e-3, "the column's corner sits at the anchor point");
        assertEquals(20, menu.y(), 1e-3, describe(tree()));
        assertEquals(popup.columnVisibleHeightForTest(0), menu.height(), 1e-3,
                "the clamped on-screen height, never the content height" + describe(tree()));
    }

    @Test
    void everyRowIsPublishedAtTheOffsetTheColumnLaidItOutAt() {
        open(everyShape());

        AccessibleNode menu = rootColumn();
        List<AccessibleNode> rows = rowsOf(menu);
        SizeTokens t = popup.tokensForTest();
        for (int i = 0; i < rows.size(); i++) {
            AccessibleNode row = rows.get(i);
            assertEquals(menu.y() + rowTop(root, i), row.y(), 1e-3,
                    root.items().get(i).label() + describe(tree()));
            assertEquals(root.items().get(i).isSeparator() ? t.separatorBox() : t.menuRowHeight(),
                    row.height(), 1e-3, describe(tree()));
            assertEquals(menu.x(), row.x(), 1e-3,
                    "the whole row, never the highlight's inset band: hit() maps the whole row");
            assertEquals(menu.width(), row.width(), 1e-3, describe(tree()));
            assertTrue(row.has(Accessible.State.SHOWING), "nothing here is scrolled away");
        }
    }

    @Test
    void aClickAtEveryPublishedCentreChoosesThatRow() {
        // Each pass opens its own cascade, because choosing closes the one it was aimed at.
        for (int i : new int[]{0, 6}) {
            open(everyShape());
            AccessibleNode row = rowsOf(rootColumn()).get(i);
            click(row.x() + row.width() / 2, row.y() + row.height() / 2);
            assertEquals(List.of(root.items().get(i).label()), chosen,
                    "the tree's box has to be the box a click lands in" + describe(tree()));
            assertFalse(popup.isOpen());
        }

        // The submenu row, whose click opens instead of choosing, through the same hit test.
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        click(export.x() + export.width() / 2, export.y() + export.height() / 2);
        assertEquals(2, popup.columnCountForTest(), describe(tree()));
    }

    @Test
    void mirroringMovesTheBoxesAndLeavesTheOrderAlone() {
        // Far enough from the leading edge that the mirrored placement fits: a column that runs
        // off the bounds falls back to the anchor's other edge in either direction, and this case
        // is about the mirror rather than about the fallback.
        open(everyShape(), LayoutDirection.RTL, 300);

        AccessibleNode menu = rootColumn();
        assertEquals(List.of("New", "Wrap", "", "Export", "Empty", "Paste", "Quit"),
                rowsOf(menu).stream().map(AccessibleNode::name).toList(),
                "reading order is declaration order in either direction" + describe(tree()));
        assertEquals(300 - menu.width(), menu.x(), 1e-3,
                "the column's corner sits at the anchor's leading edge, which reading right to "
                        + "left is its right" + describe(tree()));
        for (AccessibleNode row : rowsOf(menu)) {
            assertEquals(menu.x(), row.x(), 1e-3, describe(tree()));
        }

        AccessibleNode first = rowsOf(menu).get(0);
        click(first.x() + first.width() / 2, first.y() + first.height() / 2);
        assertEquals(List.of("New"), chosen,
                "and the mirrored box is still the box a click lands in");
    }

    // ------------------------------------------------------------------------------ the cascade

    @Test
    void aSubmenuHangsUnderTheRowThatOpenedIt() throws Exception {
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        assertTrue(export.has(Accessible.State.HAS_POPUP), describe(tree()));
        assertFalse(export.expand().expanded(), describe(tree()));
        assertEquals(Set.of(Accessible.Action.SHOW_MENU), export.actions().actions(),
                "one verb: expand, collapse and press are accepted below without being advertised"
                        + describe(tree()));

        assertTrue(perform(export.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE),
                "accepted, which is not the same as done");
        frame();

        AccessibleNode opener = byId(export.id());
        assertTrue(opener.expand().expanded(), describe(tree()));
        AccessibleNode submenu = columnUnder(opener);
        assertEquals("Export", submenu.name(),
                "a submenu is titled by the row that opened it" + describe(tree()));
        assertEquals(List.of("PNG", "SVG"),
                rowsOf(submenu).stream().map(AccessibleNode::name).toList(), describe(tree()));
        assertEquals(0, childrenOf(rootColumn()).stream()
                        .filter(child -> child.role() == Accessible.Role.MENU).count(),
                "a deeper column hangs under the ROW, never beside it under the parent column"
                        + describe(tree()));
    }

    @Test
    void onlyTheDeepestColumnsHighlightIsActive() throws Exception {
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        perform(export.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        AccessibleNode opener = byId(export.id());
        AccessibleNode submenu = columnUnder(opener);
        AccessibleNode deep = rowsOf(submenu).get(0);

        assertEquals(List.of("PNG"), nodesWith(Accessible.State.ACTIVE).stream()
                        .filter(item -> item.role() == Accessible.Role.MENU_ITEM
                                || item.role() == Accessible.Role.CHECK_MENU_ITEM)
                        .map(AccessibleNode::name).toList(),
                "marking every column's highlight active would resolve the focused node to a "
                        + "root-column row and make the whole cascade silent" + describe(tree()));
        assertTrue(opener.selectionItem().selected(),
                "the parent row stays selected, because that is where the cascade came from");
        assertEquals(deep.id(), surface().selection().activeDescendant(),
                "the node holding the focus resolves its cursor into the deepest column"
                        + describe(tree()));
        assertEquals(deep.id(), rootColumn().selection().activeDescendant(),
                "and a parent column's own facet resolves into its open submenu, which is "
                        + "truthful: that is where the cursor is" + describe(tree()));
    }

    @Test
    void anArrowKeyMovesTheCursorTheSurfacePublishes() {
        open(everyShape());
        long layer = surface().id();
        long before = surface().selection().activeDescendant();
        assertEquals(rowsOf(rootColumn()).get(0).id(), before,
                "the first selectable row is current the moment the menu opens" + describe(tree()));

        key(Keys.DOWN);

        long now = surface().selection().activeDescendant();
        assertEquals(rowsOf(rootColumn()).get(1).id(), now, describe(tree()));
        List<AccessibleEvent> moved = bridge.events.stream()
                .filter(event -> event.type() == AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED
                        && event.nodeId() == layer)
                .toList();
        assertEquals(1, moved.size(),
                "Down in a menu is the event a reader follows: " + bridge.events);
        assertEquals(before, moved.get(0).oldValue());
        assertEquals(now, moved.get(0).newValue());
    }

    @Test
    void aSubmenuAlreadyOpenIsNotTornDownAndBuiltAgain() throws Exception {
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        perform(export.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        perform(export.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        assertEquals(published, bridge.published.size(),
                "the hook refuses what it is already doing: reopening truncates the cascade and "
                        + "builds another one with new identifiers, which the pointer never does"
                        + describe(tree()));
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
        assertEquals(2, popup.columnCountForTest());
    }

    @Test
    void collapseClosesTheOpenSubmenuAndNothingElse() throws Exception {
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        perform(export.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        frame();
        assertEquals(2, popup.columnCountForTest(),
                "the unadvertised verb is still dispatched" + describe(tree()));

        perform(rowsOf(rootColumn()).get(0).id(), Accessible.Action.COLLAPSE,
                Accessible.Argument.NONE);
        frame();
        assertEquals(2, popup.columnCountForTest(),
                "a collapse on a row with no submenu closes nothing" + describe(tree()));

        perform(byId(export.id()).id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        frame();
        assertEquals(1, popup.columnCountForTest(), describe(tree()));
        assertFalse(byId(export.id()).expand().expanded(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ scrolling

    /** Thirty rows in a 300 pt scene: the column clamps, scrolls, and grows its two bands. */
    private Menu thirtyRows() {
        Menu menu = new Menu();
        for (int i = 0; i < 30; i++) {
            int row = i;
            menu.addItem("Item " + row, () -> chosen.add("Item " + row));
        }
        return menu;
    }

    @Test
    void aScrolledColumnPublishesEveryRowAndSaysWhichAreOffScreen() {
        open(thirtyRows());

        AccessibleNode menu = rootColumn();
        assertTrue(popup.columnVisibleHeightForTest(0) < 30 * popup.tokensForTest().menuRowHeight(),
                "the fixture did not force a scroll");
        List<AccessibleNode> rows = rowsOf(menu);
        assertEquals(30, rows.size(), "every row is published: the set does not move with the "
                + "scroll" + describe(tree()));

        float visible = popup.columnVisibleHeightForTest(0);
        for (int i = 0; i < rows.size(); i++) {
            AccessibleNode row = rows.get(i);
            float top = rowTop(root, i);
            assertEquals(menu.y() + top, row.y(), 1e-3, describe(tree()));
            boolean onScreen = top + row.height() >= 0 && top <= visible;
            assertEquals(onScreen, row.has(Accessible.State.SHOWING),
                    "row " + i + describe(tree()));
            assertTrue(row.has(Accessible.State.VISIBLE),
                    "scrolled away is not hidden" + describe(tree()));
            assertEquals(i + 1, row.selectionItem().positionInSet(), describe(tree()));
            assertEquals(30, row.selectionItem().sizeOfSet(), describe(tree()));
        }

        assertEquals(0, menu.scroll().verticalPercent(), 1e-6, describe(tree()));
        assertEquals(visible / (2 * popup.tokensForTest().menuPadV()
                        + 30 * popup.tokensForTest().menuRowHeight()),
                menu.scroll().verticalViewSize(), 1e-6, describe(tree()));
        assertTrue(menu.scroll().verticallyScrollable(), describe(tree()));
        assertFalse(menu.scroll().horizontallyScrollable(),
                "a width-clamped column clips its labels with no scroll route at all, and the "
                        + "name comes from the model rather than from the pixels");
    }

    @Test
    void aColumnThatDoesNotScrollStillPublishesTheFacet() {
        open(everyShape());

        assertNotNull(rootColumn().scroll(),
                "published on every column on every frame, so that reaching the clamp moves two "
                        + "numbers instead of making a facet appear" + describe(tree()));
        assertFalse(rootColumn().scroll().verticallyScrollable(), describe(tree()));
        assertEquals(List.of(), bandsOf(rootColumn()),
                "and a column with nothing to scroll grows no bands" + describe(tree()));
    }

    @Test
    void aBandParkedAtItsClampIsNotPublishedAtAll() {
        open(thirtyRows());

        AccessibleNode menu = rootColumn();
        assertEquals(List.of(bandName(false)),
                bandsOf(menu).stream().map(AccessibleNode::name).toList(),
                "a column opens at a scroll of 0, where paintScrollHint draws nothing at the top "
                        + "and hintBandDirection answers 0: there is no band there to publish"
                        + describe(tree()));

        // What that rectangle really holds at this scroll, and what a BUTTON over it would have
        // been saying: MENU_SCROLL_HINT_H is 12 and the first row starts at menuPadV.
        click(menu.x() + menu.width() / 2, menu.y() + Strokes.MENU_SCROLL_HINT_H / 2);

        assertEquals(List.of("Item 0"), chosen,
                "a band published there would tell a reader that a press scrolls, where the "
                        + "click chooses the first row" + describe(tree()));
    }

    @Test
    void theBandAtTheFarClampStopsBeingPublishedWhenTheColumnReachesIt() throws Exception {
        open(thirtyRows());

        long down = bandsOf(rootColumn()).get(0).id();
        for (float before = -1; before != popup.columnScrollForTest(0); ) {
            before = popup.columnScrollForTest(0);
            perform(down, Accessible.Action.PRESS, Accessible.Argument.NONE);
            frame();
        }

        assertEquals(List.of(bandName(true)),
                bandsOf(rootColumn()).stream().map(AccessibleNode::name).toList(),
                "and the other side goes the same way at the other clamp" + describe(tree()));
        assertEquals(Set.of(Accessible.Action.PRESS),
                bandsOf(rootColumn()).get(0).actions().actions(),
                "the side that is left is the live one" + describe(tree()));
    }

    @Test
    void theScrollBandsArePublishedAtTheRegionAClickLandsIn() throws Exception {
        open(thirtyRows());
        // One step down, so the column is between its clamps and both sides are on the glass.
        perform(bandsOf(rootColumn()).get(0).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE);
        frame();

        AccessibleNode menu = rootColumn();
        List<AccessibleNode> bands = bandsOf(menu);
        assertEquals(List.of(bandName(true), bandName(false)),
                bands.stream().map(AccessibleNode::name).toList(),
                "they name a position in the item order, never a screen side" + describe(tree()));
        AccessibleNode up = bands.get(0);
        AccessibleNode down = bands.get(1);

        assertEquals(menu.x(), up.x(), 1e-3,
                "hintBandDirection measures across the whole width, while paintScrollHint insets "
                        + "by ROW_CLIP: what is published is the box a click lands in"
                        + describe(tree()));
        assertEquals(menu.width(), up.width(), 1e-3, describe(tree()));
        assertEquals(menu.y(), up.y(), 1e-3,
                "and the top band starts flush with the column, not a point below it");
        assertEquals(Strokes.MENU_SCROLL_HINT_H, up.height(), 1e-3, describe(tree()));
        assertEquals(menu.y() + menu.height() - Strokes.MENU_SCROLL_HINT_H, down.y(), 1e-3,
                describe(tree()));

        assertEquals(Set.of(Accessible.Action.PRESS), up.actions().actions(),
                "a published band is a live one, so the verb is unconditional: the parked side "
                        + "is left out of the tree rather than published without it, because a "
                        + "synthetic child cannot be published disabled by any route"
                        + describe(tree()));
        assertEquals(Set.of(Accessible.Action.PRESS), down.actions().actions(), describe(tree()));

        float rowY = rowsOf(menu).get(0).y();
        assertTrue(perform(down.id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(2 * Strokes.WHEEL_STEP, popup.columnScrollForTest(0), 1e-3,
                "the same expression the click branch uses" + describe(tree()));
        assertEquals(rowY - Strokes.WHEEL_STEP, rowsOf(rootColumn()).get(0).y(), 1e-3,
                "and every row's box moved with it" + describe(tree()));
        assertFalse(rowsOf(rootColumn()).get(0).has(Accessible.State.SHOWING), describe(tree()));
    }

    @Test
    void aBandPressThatClampsToNothingIsRefused() throws Exception {
        open(thirtyRows());
        // A key minted while the top band was live, sent after the column parked back at 0: a key
        // outlives the snapshot it was read from, which is why pressBand asks the question again.
        long down = bandsOf(rootColumn()).get(0).id();
        perform(down, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();
        long up = bandsOf(rootColumn()).get(0).id();
        perform(up, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();
        assertEquals(0, popup.columnScrollForTest(0), 1e-3, "the fixture is back at the clamp");
        assertEquals(List.of(bandName(false)),
                bandsOf(rootColumn()).stream().map(AccessibleNode::name).toList(),
                "and that band is no longer in the tree" + describe(tree()));
        int published = bridge.published.size();

        perform(up, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(0, popup.columnScrollForTest(0), 1e-3,
                "the band is parked at its clamp and the press moves nothing" + describe(tree()));
        assertEquals(published, bridge.published.size(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ the verbs

    @Test
    void pressingACommandRowRunsTheApplicationsOwnRunnableAndCloses() throws Exception {
        open(everyShape());

        assertTrue(perform(rowsOf(rootColumn()).get(0).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        frame();

        assertEquals(List.of("New"), chosen, "the same activate() a click reaches");
        assertFalse(popup.isOpen());
        settle();
        assertEquals(0L, countOf(Accessible.Role.MENU),
                "and the cascade really left the tree once its fade finished" + describe(tree()));
    }

    @Test
    void pressingACheckRowFlipsItThroughTheItemsOwnActivation() throws Exception {
        open(everyShape());

        perform(rowsOf(rootColumn()).get(1).id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(true), toggled,
                "activate(), which flips and reports; never setChecked, which reports nothing");
        assertTrue(root.items().get(1).isChecked());
        assertFalse(popup.isOpen(), "choosing a check row in a menu closes it, as a click does");
    }

    @Test
    void toggleIsAcceptedOnACheckRowWithoutBeingAdvertised() throws Exception {
        open(everyShape());

        assertEquals(Set.of(Accessible.Action.PRESS),
                rowsOf(rootColumn()).get(1).actions().actions(),
                "choosing a check row in a menu is one gesture" + describe(tree()));

        perform(rowsOf(rootColumn()).get(1).id(), Accessible.Action.TOGGLE,
                Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(true), toggled,
                "and the unadvertised verb is still dispatched" + describe(tree()));
    }

    @Test
    void cancelOnTheCaptureLayerDismissesTheWholeCascade() throws Exception {
        open(everyShape());
        perform(rowsOf(rootColumn()).get(3).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE);
        frame();
        assertEquals(2, popup.columnCountForTest());

        assertTrue(perform(surface().id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        frame();

        assertFalse(popup.isOpen(),
                "a press on the capture layer closes the cascade, where Escape at depth closes "
                        + "one column: the verb has to mean what the box means");
    }

    // ------------------------------------------------------------------------------ identity

    @Test
    void aRowKeepsItsIdentityAcrossAReopenAndAcrossAModelChange() throws Exception {
        open(everyShape());
        AccessibleNode export = rowsOf(rootColumn()).get(3);
        long exportId = export.id();

        perform(exportId, Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();
        long png = rowsOf(columnUnder(byId(exportId))).get(0).id();
        perform(exportId, Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        frame();
        perform(exportId, Accessible.Action.SHOW_MENU, Accessible.Argument.NONE);
        frame();

        assertEquals(png, rowsOf(columnUnder(byId(exportId))).get(0).id(),
                "a row is keyed by the serial MenuItem mints once, so closing and reopening its "
                        + "column hands a client back the identifier it already holds"
                        + describe(tree()));

        List<Long> before = rowsOf(rootColumn()).stream().map(AccessibleNode::id).toList();
        root.addItem("Open", () -> chosen.add("Open"));
        frame();
        runtime.drain(); // the rebuild the paint posts, which never runs mid-frame
        frame();

        List<Long> after = rowsOf(rootColumn()).stream().map(AccessibleNode::id).toList();
        assertEquals(before, after.subList(0, before.size()),
                "an inserted row must not hand row three's identifier to row nine, which is what "
                        + "an index-keyed row would do" + describe(tree()));
        assertEquals(before.size() + 1, after.size(), describe(tree()));
        assertFalse(before.contains(after.get(after.size() - 1)), describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    @Test
    void anOpenCascadeThatDidNotMovePublishesNothing() {
        open(everyShape());
        int published = bridge.published.size();

        for (int i = 0; i < 10; i++) {
            frame(); // the in-scene fade damages the surface on every one of them
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot" + describe(tree()));
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        AccessibleNode first = rowsOf(rootColumn()).get(0);
        scene.mouseMoved(first.x() + first.width() / 2, first.y() + first.height() / 2);
        scene.inputBatchEnded();
        frame();

        assertEquals(published, bridge.published.size(),
                "and a pointer move within the row already current moves nothing either"
                        + describe(tree()));
    }

    @Test
    void describingACascadeThatDidNotMoveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        open(everyShape());

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            frame();
        }, () -> {
            bridge.listening = false;
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(withNobodyListening, withAReaderAttached,
                "every name is an I18nString the model holds, every chord is the string the "
                        + "column resolved when it was built, and every box is a primitive out of "
                        + "its arrays: label(), mnemonicIndex() and Accelerator.display() would "
                        + "each allocate per row per damaged frame to conclude nothing had moved");
    }

    /**
     * @param role the role to count
     * @return how many nodes carry it
     */
    private long countOf(Accessible.Role role) {
        AccessibleTree tree = tree();
        long found = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                found++;
            }
        }
        return found;
    }
}
