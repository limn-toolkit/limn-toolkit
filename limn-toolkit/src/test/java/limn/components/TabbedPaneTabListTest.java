package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.input.Keys;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of every tab a strip that overflows opens (ADR 068 §4): a row per tab, the selected
 * one marked; the keys that move along it, choose and close; a tab closed from it asking the
 * pane's handler and leaving the list open; a close button on a closable tab's row; the list going
 * with the last tab. Drawn in the scene, on a window that cannot place one of its own.
 */
class TabbedPaneTabListTest extends AccessibleComponentTestBase {

    private static final int COMMAND = Accelerator.commandModifier();
    private static final List<String> TITLES = List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot");

    private TabbedPane pane;
    private final List<TabbedPane.TabCloseRequest> asked = new ArrayList<>();

    private void bindPane(boolean closable, boolean askFirst) {
        pane = new TabbedPane().setTabsClosable(closable);
        for (String title : TITLES) {
            pane.addTab(title, new SizedBox(60, 60));
        }
        if (askFirst) {
            pane.onTabClose(asked::add);
        }
        Column root = new Column();
        root.add(new SizedBox(200, 120, pane)); // narrower than six tabs: the strip overflows
        bind(root, new StubWindow(false));
        scene.setTextRuler(RULER);
        frame();
    }

    private AccessibleNode listButton() {
        return node(ComponentStrings.TAB_LIST_ALL.get());
    }

    private void openList() throws InterruptedException {
        assertTrue(perform(listButton().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
    }

    private List<AccessibleNode> rows() {
        List<AccessibleNode> out = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.LIST_ITEM) {
                out.add(tree().node(i));
            }
        }
        return out;
    }

    private List<String> rowNames() {
        List<String> out = new ArrayList<>();
        for (AccessibleNode row : rows()) {
            out.add(row.name());
        }
        return out;
    }

    private List<String> titles() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pane.tabCount(); i++) {
            out.add(pane.tabTitle(i));
        }
        return out;
    }

    private void press(int key, int modifiers) {
        drive(scene).press(key, modifiers);
        frame();
    }

    @Test
    void theListHasARowPerTabTheSelectedOneMarked() throws InterruptedException {
        bindPane(false, false);
        pane.setSelectedIndex(2);
        frame();
        openList();
        assertEquals(TITLES, rowNames(), describe(tree()));
        assertTrue(rows().get(2).selectionItem().selected(), describe(tree()));
        assertTrue(listButton().expand().expanded());
    }

    @Test
    void theArrowsMoveAlongItAndEnterChoosesAndClosesIt() throws InterruptedException {
        bindPane(false, false);
        openList();
        press(Keys.DOWN, 0);
        press(Keys.DOWN, 0);
        assertTrue(rows().get(2).has(Accessible.State.ACTIVE), "the cursor is on the third" + describe(tree()));
        press(Keys.ENTER, 0);
        assertEquals(2, pane.selectedIndex());
        assertFalse(listButton().expand().expanded(), "chosen, the list goes");
    }

    @Test
    void deleteAndTheCommandKeysWCloseTheTabUnderTheCursorAndTheListStays() throws InterruptedException {
        bindPane(true, false);
        openList();
        press(Keys.DOWN, 0);
        press(Keys.DELETE, 0);
        assertEquals(List.of("Alpha", "Charlie", "Delta", "Echo", "Foxtrot"), titles());
        assertEquals(titles(), rowNames(), "the list stays open, without the row" + describe(tree()));
        press(Keys.W, COMMAND);
        assertEquals(List.of("Alpha", "Delta", "Echo", "Foxtrot"), titles(), "the cursor stayed on the place");
    }

    @Test
    void aCloseFromTheListAsksTheHandlerAsEveryCloseDoes() throws InterruptedException {
        bindPane(true, true);
        openList();
        press(Keys.DELETE, 0);
        assertEquals(1, asked.size());
        assertEquals(TITLES, titles(), "not closed before the answer");
        asked.get(0).allow();
        frame();
        assertEquals(TITLES.subList(1, TITLES.size()), rowNames(), describe(tree()));
    }

    @Test
    void aClosableTabsRowHoldsACloseButtonThatClosesIt() throws InterruptedException {
        bindPane(false, false);
        pane.setTabClosable(3, true);
        frame();
        openList();
        List<AccessibleNode> buttons = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.BUTTON && node.name().equals(ComponentStrings.TAB_CLOSE.get())
                    && tree().node(node.parent()).role() == Accessible.Role.LIST_ITEM) {
                buttons.add(node);
            }
        }
        assertEquals(1, buttons.size(), "only the closable tab's row has one" + describe(tree()));
        assertEquals("Delta", tree().node(buttons.get(0).parent()).name());
        assertTrue(perform(buttons.get(0).id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertFalse(titles().contains("Delta"));
        assertEquals(titles(), rowNames(), "and the list stays" + describe(tree()));
    }

    @Test
    void theListGoesWithTheLastTabAndEscapeClosesIt() throws InterruptedException {
        bindPane(true, false);
        openList();
        press(Keys.ESCAPE, 0);
        assertFalse(listButton().expand().expanded());
        openList();
        for (int i = 0; i < TITLES.size(); i++) {
            press(Keys.DELETE, 0);
        }
        assertEquals(0, pane.tabCount());
        assertTrue(rows().isEmpty(), "no tab, no list" + describe(tree()));
    }

    @Test
    void aLetterMovesToTheNextTabItStartsWith() throws InterruptedException {
        bindPane(false, false);
        openList();
        drive(scene).type("e");
        drive(scene).inputBatchEnded();
        frame();
        assertTrue(rows().get(4).has(Accessible.State.ACTIVE), "Echo" + describe(tree()));
    }

    /** Alt+Down on the focused tab opens the list; the arrows then walk it and Enter chooses. */
    @Test
    void altAndDownOnTheFocusedTabOpensTheList() throws InterruptedException {
        bindPane(false, false);
        scene.requestFocus(tabStop(pane));
        press(Keys.DOWN, Keys.MOD_ALT);
        assertEquals(TITLES, rowNames(), "the list is open, a row per tab");
        press(Keys.DOWN, 0);
        press(Keys.ENTER, 0);
        assertTrue(rows().isEmpty(), "Enter chose and closed it");
        assertEquals(1, pane.selectedIndex(), "the row under the cursor was chosen");
    }

    private static limn.scene.Widget<?> tabStop(limn.scene.Widget<?> at) {
        if (at.isFocusable() && at.getClass().getSimpleName().equals("TabHeader")) {
            return at;
        }
        for (limn.scene.Widget<?> child : at.children()) {
            limn.scene.Widget<?> found = tabStop(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A tab's icon leads its row, as in the strip; a tab without one keeps the captions' column. */
    @Test
    void aTabsIconLeadsItsRowAndTheCaptionsLineUp() throws InterruptedException {
        RecordingIcon first = new RecordingIcon();
        RecordingIcon last = new RecordingIcon();
        pane = new TabbedPane();
        for (String title : TITLES) {
            pane.addTab(title, title.equals("Alpha") ? first : title.equals("Foxtrot") ? last : null,
                    new SizedBox(60, 60));
        }
        Column root = new Column();
        root.add(new SizedBox(200, 120, pane));
        bind(root, new StubWindow(false));
        scene.setTextRuler(RULER);
        frame();
        first.at.clear();
        last.at.clear();
        scene.renderFrame(new RecordingTestCanvas(400, 300));
        int firstShut = first.at.size();
        int lastShut = last.at.size();
        openList();
        first.at.clear();
        last.at.clear();
        scene.renderFrame(new RecordingTestCanvas(400, 300));

        assertEquals(firstShut + 1, first.at.size(), "Alpha's row draws its icon");
        assertEquals(lastShut + 1, last.at.size(), "and Foxtrot's, its tab scrolled off the strip");
        // The strip paints first and the list over it, so each icon's last paint is its row's.
        float[] alpha = first.at.get(first.at.size() - 1);
        float[] foxtrot = last.at.get(last.at.size() - 1);
        assertEquals(alpha[0], foxtrot[0], 0.001f, "the two rows share the icon column");
        assertTrue(foxtrot[1] > alpha[1], "Foxtrot's row is under Alpha's");
    }

    /** Records where it was painted, in the painting widget's own coordinates; never rasterized. */
    private static final class RecordingIcon implements limn.graphics.Icon {

        final List<float[]> at = new ArrayList<>();

        @Override
        public limn.graphics.Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(limn.graphics.Canvas canvas, float x, float y, float size,
                limn.graphics.Color tint, boolean dark, boolean mirrored) {
            at.add(new float[] {x, y});
        }
    }

    @Test
    void aClickOnARowChoosesItsTab() throws InterruptedException {
        bindPane(false, false);
        openList();
        AccessibleNode echo = rows().get(4);
        float x = echo.x() + 10;
        float y = echo.y() + echo.height() / 2;
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(4, pane.selectedIndex());
    }
}
