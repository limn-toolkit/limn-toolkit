package limn.components;

import limn.input.Keys;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menu model + {@link PopupMenu} behavior (keyboard/mouse navigation, checked
 * toggle, cascading submenus) and the overflow-avoiding flip/clamp positioning.
 * The native presentation needs a real window, so these drive the popup's
 * surface directly via package-private test hooks, in layout space, headless
 * with the deterministic 10pt/glyph ruler.
 */
class MenuTest extends ComponentTestBase {

    /** Opens {@code menu} laid out against {@code (boundsW, boundsH)} at anchor {@code (ax, ay)}. */
    private PopupMenu show(Menu menu, float boundsW, float boundsH, float ax, float ay) {
        PopupMenu popup = new PopupMenu(menu);
        popup.showForTest(RULER, ax, ay, 0, 0, boundsW, boundsH);
        return popup;
    }

    /**
     * The row the cascade was actually built from, rather than a copy of MEDIUM's numbers: every
     * geometry expectation below is derived from it, so this file stays correct at whatever step
     * the popup resolves, the dense steps in particular, where a baked 28 pt row is simply wrong.
     */
    private static SizeTokens tokens(PopupMenu popup) {
        return popup.tokensForTest();
    }

    /** Centre of item {@code index} in a column with no separators, relative to the column top. */
    private static float itemCentre(SizeTokens t, int index) {
        return t.menuPadV() + index * t.menuRowHeight() + t.menuRowHeight() / 2;
    }

    // --------------------------------------------------------------- model

    @Test
    void checkItemFlipsStateAndReportsIt() {
        AtomicReference<Boolean> reported = new AtomicReference<>();
        MenuItem check = MenuItem.check("Quebra", false, reported::set);
        assertFalse(check.isChecked());
        check.activate();
        assertTrue(check.isChecked());
        assertEquals(Boolean.TRUE, reported.get());
    }

    @Test
    void separatorsAndDisabledItemsAreNotSelectable() {
        assertFalse(MenuItem.separator().isSelectable());
        assertTrue(MenuItem.of("A", () -> { }).isSelectable());
        assertFalse(MenuItem.of("A", () -> { }).setEnabled(false).isSelectable());
    }

    @Test
    void clearEmptiesTheMenuThroughTheViewEveryHolderReads() {
        Menu menu = new Menu().addItem("A", () -> { }).addSeparator().addItem("B", () -> { });
        var view = menu.items();
        assertEquals(3, view.size());

        menu.clear().addItem("C", () -> { });

        assertFalse(menu.isEmpty());
        // The point of clearing rather than replacing: a MenuBar entry or a
        // submenu item holds the Menu, and so does this view. All of them see the
        // new list because the instance never changed.
        assertEquals(1, view.size());
        assertEquals("C", view.get(0).label());
    }

    // ------------------------------------------------------------- behavior

    @Test
    void enterActivatesTheHighlightedCommandAndCloses() {
        AtomicBoolean ran = new AtomicBoolean();
        Menu menu = new Menu().addItem("Primeiro", () -> ran.set(true)).addItem("Segundo", () -> { });
        PopupMenu popup = show(menu, 300, 200, 20, 20);
        assertTrue(popup.isOpen());
        popup.keyForTest(Keys.ENTER); // first item is highlighted on open
        assertTrue(ran.get(), "the highlighted command ran");
        assertFalse(popup.isOpen(), "the menu closed after choosing");
    }

    @Test
    void downThenEnterActivatesTheSecondItemSkippingSeparators() {
        AtomicReference<String> chosen = new AtomicReference<>();
        Menu menu = new Menu()
                .addItem("One", () -> chosen.set("one"))
                .addSeparator()
                .addItem("Two", () -> chosen.set("two"));
        PopupMenu popup = show(menu, 300, 200, 20, 20);
        popup.keyForTest(Keys.DOWN);  // skips the separator → "Dois"
        popup.keyForTest(Keys.ENTER);
        assertEquals("two", chosen.get());
    }

    @Test
    void clickingAnItemChoosesIt() {
        AtomicReference<String> chosen = new AtomicReference<>();
        Menu menu = new Menu().addItem("One", () -> chosen.set("one")).addItem("Two", () -> chosen.set("two"));
        PopupMenu popup = show(menu, 300, 200, 20, 20);
        float[] r = popup.columnRectForTest(0);
        // Row "Two": menuPadV + one row down, then half a row in (48 at MEDIUM).
        popup.clickForTest(r[0] + 30, r[1] + itemCentre(tokens(popup), 1));
        assertEquals("two", chosen.get());
        assertFalse(popup.isOpen());
    }

    @Test
    void clearingRebuildsTheColumnOfAMenuThatIsAlreadyOpen() {
        AtomicReference<String> chosen = new AtomicReference<>();
        // The recent-files shape: the menu's contents ARE data, and the data can
        // change (another window saved a file) while this menu is on screen.
        Menu recents = new Menu()
                .addItem("report.txt", () -> chosen.set("report"))
                .addItem("notes.md", () -> chosen.set("notes"));
        PopupMenu popup = show(recents, 300, 200, 20, 20);

        recents.clear().addItem("budget.xlsx", () -> chosen.set("budget"));

        popup.keyForTest(Keys.ENTER); // resyncs off modCount before it navigates
        assertEquals("budget", chosen.get(), "the rebuilt item is the one that ran");
    }

    @Test
    void choosingACheckItemTogglesIt() {
        AtomicReference<Boolean> reported = new AtomicReference<>();
        Menu menu = new Menu().addCheck("Ativo", false, reported::set);
        PopupMenu popup = show(menu, 300, 200, 20, 20);
        popup.keyForTest(Keys.ENTER);
        assertEquals(Boolean.TRUE, reported.get());
    }

    @Test
    void rightArrowOpensSubmenuAndEnterActivatesItsChild() {
        AtomicBoolean childRan = new AtomicBoolean();
        Menu sub = new Menu().addItem("Filho", () -> childRan.set(true));
        Menu menu = new Menu().addSubmenu("Parent", sub).addItem("Other", () -> { });
        PopupMenu popup = show(menu, 400, 300, 20, 20);
        assertEquals(1, popup.columnCountForTest());
        popup.keyForTest(Keys.RIGHT); // open the submenu (submenu item highlighted first)
        assertEquals(2, popup.columnCountForTest(), "submenu column opened");
        popup.keyForTest(Keys.ENTER); // activate the submenu's first item
        assertTrue(childRan.get());
        assertFalse(popup.isOpen());
    }

    @Test
    void escapeClosesTheSubmenuThenTheWholeMenu() {
        Menu sub = new Menu().addItem("Filho", () -> { });
        Menu menu = new Menu().addSubmenu("Pai", sub);
        PopupMenu popup = show(menu, 400, 300, 20, 20);
        popup.keyForTest(Keys.RIGHT);
        assertEquals(2, popup.columnCountForTest());
        popup.keyForTest(Keys.ESCAPE); // closes only the submenu
        assertEquals(1, popup.columnCountForTest());
        assertTrue(popup.isOpen());
        popup.keyForTest(Keys.ESCAPE); // closes the whole menu
        assertFalse(popup.isOpen());
    }

    // ------------------------------------------------------------- size step

    @Test
    void theCascadeTakesItsStepFromTheHostAndThenFromTheExplicitOverride() {
        // A PopupMenu is parentless in both presentations (a native window's scene root, or an
        // overlay), so the tree walk can never reach the widget it belongs to. The host link is
        // the only path, and this is the assertion that it is actually installed: without it the
        // menu silently renders at the process default next to an XSMALL surface.
        Label root = new Label("root");
        limn.scene.Scene scene = new limn.scene.Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        root.setControlSize(limn.scene.ControlSize.XSMALL);

        Menu menu = new Menu().addItem("One", () -> { });
        PopupMenu inherited = new PopupMenu(menu);
        inherited.showInSceneForTest(scene, 20, 20, 0, 0);
        assertEquals(SizeTokens.of(limn.scene.ControlSize.XSMALL), inherited.tokensForTest(),
                "the cascade inherited the host's step");
        inherited.close();

        // The explicit override wins over the host: "a compact menu over a roomy surface".
        PopupMenu pinned = new PopupMenu(menu).setControlSize(limn.scene.ControlSize.LARGE);
        pinned.showInSceneForTest(scene, 20, 20, 0, 0);
        assertEquals(SizeTokens.of(limn.scene.ControlSize.LARGE), pinned.tokensForTest());
        // And the row is really what the geometry was built from, not just a stored field.
        float[] r = pinned.columnRectForTest(0);
        assertEquals(SizeTokens.of(limn.scene.ControlSize.LARGE).menuMinWidth(), r[2], 0.01f,
                "the column was built at the declared step");
    }

    // ------------------------------------------- in-scene fullscreen fallback

    @Test
    void inSceneFallbackRendersAsAnOverlayAndNavigatesWithTheKeyboard() {
        AtomicReference<String> chosen = new AtomicReference<>();
        limn.scene.Scene scene = new limn.scene.Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);

        Menu menu = new Menu()
                .addItem("One", () -> chosen.set("one"))
                .addItem("Two", () -> chosen.set("two"));
        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 20, 20, 0, 0); // the fullscreen fallback path
        scene.layoutPass(400, 300); // lay out the pushed overlay (fillScene → bounds = scene)

        assertTrue(popup.isOpen());
        float[] r = popup.columnRectForTest(0);
        assertTrue(r[0] >= 0 && r[0] + r[2] <= 400 + 1e-3, "column stays within the scene bounds");

        // Keyboard reaches the focused overlay surface; choose the second item.
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals("two", chosen.get());
        assertFalse(popup.isOpen(), "choosing an item removed the overlay and closed");
    }

    @Test
    void mutatingTheMenuWhileOpenRebuildsInsteadOfCrashing() {
        // The async-populated "recent files" pattern: items are added while the
        // popup is open; the stale geometry snapshot must never crash paint or
        // navigation: the column rebuilds and shows the new items.
        AtomicReference<String> chosen = new AtomicReference<>();
        limn.scene.Scene scene = new limn.scene.Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);

        Menu menu = new Menu().addItem("One", () -> chosen.set("one"));
        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300);
        assertTrue(popup.isOpen());

        menu.addItem("Two", () -> chosen.set("two")); // mutate while open
        scene.renderFrame(new FakeCanvas(400, 300));  // stale snapshot must not throw

        // Navigation resyncs and can reach (and choose) the new item.
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals("two", chosen.get());
    }

    @Test
    void scrollHintBandClickScrollsInsteadOfActivating() {
        // A clamped column paints "more items" chevron bands over its edges:
        // clicking one must step the scroll, never trigger the mostly-hidden
        // item painted beneath it.
        AtomicReference<String> chosen = new AtomicReference<>();
        limn.scene.Scene scene = new limn.scene.Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 120); // shorter than the 30-item column: it clamps
        Menu menu = new Menu();
        for (int i = 0; i < 30; i++) {
            String label = "Item " + i;
            menu.addItem(label, () -> chosen.set(label));
        }
        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 0, 0, 0, 0);
        scene.layoutPass(300, 120);

        float[] r = popup.columnRectForTest(0);
        float cx = r[0] + r[2] / 2;
        float bottomBand = r[1] + r[3] - 4; // inside the lower hint band
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, cx, bottomBand);
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, cx, bottomBand);
        scene.inputBatchEnded();

        assertTrue(popup.isOpen(), "the band click is a scroll step, not a dismiss");
        assertEquals(null, chosen.get(), "no item activates under the chevron band");
    }

    // ---------------------------------------------------- overflow avoidance

    @Test
    void popupOpeningNearACornerStaysFullyOnScreen() {
        Menu menu = new Menu().addItem("A", () -> { }).addItem("B", () -> { }).addItem("C", () -> { });
        PopupMenu popup = show(menu, 200, 120, 195, 115); // bottom-right corner
        float[] r = popup.columnRectForTest(0);
        assertTrue(r[0] >= 0 && r[1] >= 0, "top-left inside the bounds: " + r[0] + "," + r[1]);
        assertTrue(r[0] + r[2] <= 200 + 1e-3, "right edge inside: " + (r[0] + r[2]));
        assertTrue(r[1] + r[3] <= 120 + 1e-3, "bottom edge inside (flipped above): " + (r[1] + r[3]));
    }

    @Test
    void submenuStaysWithinBoundsWhenThereIsNoRoomOnTheRight() {
        Menu sub = new Menu().addItem("X", () -> { }).addItem("Y", () -> { });
        Menu menu = new Menu().addSubmenu("Pai", sub);
        PopupMenu popup = show(menu, 200, 200, 150, 10); // near the right edge
        popup.keyForTest(Keys.RIGHT); // open submenu
        float[] r = popup.columnRectForTest(1);
        assertTrue(r[0] >= 0, "submenu left inside: " + r[0]);
        assertTrue(r[0] + r[2] <= 200 + 1e-3, "submenu right inside (opened to the left): " + (r[0] + r[2]));
    }

    // ------------------------------------------------- tall menus (clamp+scroll)

    private static Menu manyItems(int count, AtomicReference<String> chosen) {
        Menu menu = new Menu();
        for (int i = 1; i <= count; i++) {
            String id = "item" + i;
            menu.addItem("Item " + i, () -> chosen.set(id));
        }
        return menu;
    }

    /**
     * How far a 30-item column clamped to {@code visibleH} can scroll: its whole content height
     * minus what is on screen. 652 at MEDIUM, but 528 at XSMALL and 590 at SMALL; a literal
     * here (it used to be {@code > 600}) fails at both dense steps for no reason but the copy.
     */
    private static float maxScroll(SizeTokens t, int items, float visibleH) {
        return 2 * t.menuPadV() + items * t.menuRowHeight() - visibleH;
    }

    @Test
    void aTallMenuClampsToTheBoundsAndScrollsToRevealTheKeyboardHighlight() {
        AtomicReference<String> chosen = new AtomicReference<>();
        PopupMenu popup = show(manyItems(30, chosen), 400, 200, 10, 10); // 30 rows ≫ 200pt bounds
        SizeTokens t = tokens(popup);

        assertEquals(200f, popup.columnVisibleHeightForTest(0), 0.01f, "column clamped to the bounds");
        assertEquals(0f, popup.columnScrollForTest(0), 0.01f, "opens at the top");

        popup.keyForTest(Keys.END); // highlight the last item: must scroll to it
        assertEquals(29, popup.highlightForTest(0));
        // Revealing the LAST item lands exactly on the maximum: the reveal wants
        // top + height + menuPadV, which is past the end, and clampScroll pins it.
        assertEquals(maxScroll(t, 30, 200), popup.columnScrollForTest(0), 0.01f,
                "END revealed the far end by scrolling all the way down");

        popup.keyForTest(Keys.ENTER);
        assertEquals("item30", chosen.get(), "the revealed item activates");

        // Reopen and walk back: HOME reveals the top again.
        PopupMenu again = show(manyItems(30, chosen), 400, 200, 10, 10);
        again.keyForTest(Keys.END);
        again.keyForTest(Keys.HOME);
        assertEquals(0f, again.columnScrollForTest(0), 0.01f, "HOME scrolled back to the top");
    }

    @Test
    void clickingHitsTheItemUnderTheCursorAfterScrolling() {
        AtomicReference<String> chosen = new AtomicReference<>();
        PopupMenu popup = show(manyItems(30, chosen), 400, 200, 10, 10);
        SizeTokens t = tokens(popup);
        popup.keyForTest(Keys.END); // scrolled to the maximum

        float[] r = popup.columnRectForTest(0);
        // The last item's centre in CONTENT space, mapped through the scroll: 166 + 14 at
        // MEDIUM, and the same expression the surface hit-tests with at every step.
        float onScreenY = itemCentre(t, 29) - maxScroll(t, 30, 200);
        popup.clickForTest(r[0] + 10, r[1] + onScreenY);

        assertEquals("item30", chosen.get(), "hit-testing accounts for the column scroll");
    }

    // ------------------------------------------------- narrower than the column

    @Test
    void aColumnWiderThanTheBoundsShrinksInsteadOfHangingOffTheEdge() {
        // D17. clamp(v, lo, hi) returns lo when hi < lo, so before the shrink path a column
        // wider than the work area was pinned at the left edge and simply overflowed on the
        // right. menuMinWidth is 224 at XLARGE, so this is reachable on a real narrow monitor,
        // and the five steps ship with no per-component clamping to mitigate it.
        AtomicReference<String> chosen = new AtomicReference<>();
        Menu menu = new Menu().addItem("One", () -> chosen.set("one")).addItem("Two", () -> { });
        // showForTest has no scene and no anchor, so the cascade resolves to the process
        // default: the same row the assertions below read back off the popup.
        float narrow = SizeTokens.of(limn.scene.ControlSize.processDefault()).menuMinWidth() - 40;
        PopupMenu popup = show(menu, narrow, 300, 0, 0);
        SizeTokens t = tokens(popup);
        assertTrue(narrow < t.menuMinWidth(), "the bounds must actually be the binding constraint");

        float[] r = popup.columnRectForTest(0);
        assertEquals(narrow, r[2], 0.01f, "the column shrank to the bounds");
        assertTrue(r[0] >= 0 && r[0] + r[2] <= narrow + 1e-3f,
                "and therefore fits: " + r[0] + " + " + r[2]);

        // Shrinking must not desynchronise the hit test from the paint.
        popup.clickForTest(r[0] + r[2] / 2, r[1] + itemCentre(t, 0));
        assertEquals("one", chosen.get(), "items in a shrunken column still activate");
    }
}
