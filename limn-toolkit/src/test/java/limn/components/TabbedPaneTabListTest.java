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
