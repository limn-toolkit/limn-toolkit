package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TabbedPane}'s tabs that close and move (ADR 067 section 2): closed by their button, a
 * middle click and the command key's W, after a handler that may refuse now or later; the
 * selection that follows a close; moved by dragging along the bar and from the keyboard, the
 * reader told the new place; and the application told what the user did, once, and nothing it
 * did itself.
 */
class TabbedPaneCloseMoveTest extends AccessibleComponentTestBase {

    private static final int COMMAND = Accelerator.commandModifier();

    private TabbedPane pane;
    private final List<Change> changes = new ArrayList<>();
    private final List<String> moves = new ArrayList<>();

    private void bindPane(boolean closable, LayoutDirection direction) {
        pane = new TabbedPane().setTabsClosable(closable);
        for (String title : List.of("Alpha", "Beta", "Gamma")) {
            pane.addTab(title, new Button(title + " page"));
        }
        pane.setLayoutDirection(direction);
        pane.onTabMove((from, to) -> moves.add(from + ">" + to));
        // Away from the window's corner, as a pane in an application is: a pointer event carries
        // scene coordinates, and at the origin a widget that reads them as its own still works.
        bind(new limn.scene.layout.Padding(limn.scene.Insets.all(40), pane));
        scene.setTextRuler(RULER);
        frame();
        // The pane is the scene's root here, which also hears every layout pass: not its own.
        pane.observeChanges((w, change) -> {
            if (change.aspect() != Change.Aspect.LAYOUT) {
                changes.add(change);
            }
        });
    }

    private Widget<?> header(int index) {
        return pane.children().get(0).children().get(index);
    }

    private List<String> titles() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pane.tabCount(); i++) {
            out.add(pane.tabTitle(i));
        }
        return out;
    }

    private void click(int button, float x, float y) {
        // The pointer arrives first, as a real one does, and the frame between draws the button.
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        frame();
        drive(scene).mouseButton(button, true, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(button, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
    }

    /** The middle of a header's close button, which sits at the end reading goes to. */
    private float closeX(int index) {
        Widget<?> h = header(index);
        SizeTokens t = SizeTokens.MEDIUM;
        float edge = t.tabPadH() / 2;
        float left = h.isRightToLeft() ? edge : h.width() - edge - t.tabIconSize();
        return h.localToSceneX() + left + t.tabIconSize() / 2;
    }

    private float middleY() {
        return header(0).localToSceneY() + header(0).height() / 2;
    }

    @Test
    void theCloseButtonClosesTheSelectedTabAndTheNextOneIsSelected() {
        bindPane(true, LayoutDirection.LTR);
        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        assertEquals(List.of("Beta", "Gamma"), titles());
        assertEquals(0, pane.selectedIndex(), "the one after it");
        assertEquals(List.of(Change.of(Change.Aspect.CHILDREN, Change.Origin.USER),
                Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT)), changes);
    }

    @Test
    void rightToLeftTheCloseButtonIsAtTheTabsLeftEndAndCloses() {
        bindPane(true, LayoutDirection.RTL);
        Widget<?> first = header(0);
        assertTrue(closeX(0) < first.localToSceneX() + first.width() / 2, "at the end reading goes to");
        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        assertEquals(List.of("Beta", "Gamma"), titles());
    }

    @Test
    void closingTheLastTabSelectsTheOneBeforeAndATabBeforeKeepsTheSelection() {
        bindPane(true, LayoutDirection.LTR);
        pane.setSelectedIndex(2);
        pane.removeTab(2);
        assertEquals(1, pane.selectedIndex(), "the one before it");
        assertEquals("Beta", pane.tabTitle(pane.selectedIndex()));
        pane.removeTab(0);
        assertEquals(0, pane.selectedIndex(), "the same tab, one place on");
        assertEquals("Beta", pane.tabTitle(0));
        pane.removeTab(0);
        assertEquals(-1, pane.selectedIndex());
        assertEquals(0, pane.tabCount());
    }

    @Test
    void aMiddleClickClosesAClosableTabAndNotAnother() {
        bindPane(false, LayoutDirection.LTR);
        pane.setTabClosable(1, true);
        click(Keys.MOUSE_MIDDLE, header(0).localToSceneX() + 5, middleY());
        assertEquals(3, pane.tabCount(), "not closable");
        click(Keys.MOUSE_MIDDLE, header(1).localToSceneX() + 5, middleY());
        assertEquals(List.of("Alpha", "Gamma"), titles());
        assertEquals(0, pane.selectedIndex(), "a tab that was not selected leaves the selection");
    }

    @Test
    void theCommandKeysWClosesTheSelectedTabFromInsideItsPageAndTheKeyboardGoesToTheNextTab() {
        bindPane(true, LayoutDirection.LTR);
        Widget<?> page = pane.tabContent(0);
        page.requestFocus();
        drive(scene).press(Keys.W, COMMAND);
        assertEquals(List.of("Beta", "Gamma"), titles());
        assertSame(header(0), scene.focusedWidget(), "the keyboard is on the tab now selected");
    }

    /**
     * A press on the close button leaves the keyboard in the page: a close refused (an unsaved
     * document kept) finds it where the user was typing. It moved to the tab, and a dialog that
     * asked returned it there.
     */
    @Test
    void theCloseButtonLeavesTheKeyboardInThePage() {
        bindPane(true, LayoutDirection.LTR);
        List<TabbedPane.TabCloseRequest> asked = new ArrayList<>();
        pane.onTabClose(asked::add);
        Widget<?> page = buttonNamed(pane, "Alpha page");
        scene.requestFocus(page);
        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        assertEquals(1, asked.size());
        assertSame(page, scene.focusedWidget(), "the press on the close button took no keyboard");
        asked.get(0).refuse();
        assertSame(page, scene.focusedWidget());
    }

    private static Widget<?> buttonNamed(Widget<?> at, String text) {
        if (at instanceof Button button && text.equals(button.text())) {
            return button;
        }
        for (Widget<?> child : at.children()) {
            Widget<?> found = buttonNamed(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aRefusedCloseKeepsTheTabAndAPendingOneIsNotAskedAgain() {
        bindPane(true, LayoutDirection.LTR);
        List<TabbedPane.TabCloseRequest> asked = new ArrayList<>();
        pane.onTabClose(asked::add);
        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        assertEquals(1, asked.size(), "asked once while unanswered");
        assertEquals(3, pane.tabCount());
        asked.get(0).refuse();
        assertThrows(IllegalStateException.class, () -> asked.get(0).allow());
        assertEquals(3, pane.tabCount());

        click(Keys.MOUSE_LEFT, closeX(0), middleY());
        assertEquals(2, asked.size(), "answered, it is asked again");
        TabbedPane.TabCloseRequest later = asked.get(1);
        pane.moveTab(0, 2);
        assertEquals(2, later.index(), "known by its content after a move");
        later.allow();
        assertEquals(List.of("Beta", "Gamma"), titles(), "the tab asked about, wherever it went");
        assertEquals(-1, later.index());
    }

    @Test
    void removeTabAsksNoHandlerAndSaysItWasCode() {
        bindPane(true, LayoutDirection.LTR);
        List<TabbedPane.TabCloseRequest> asked = new ArrayList<>();
        pane.onTabClose(asked::add);
        pane.removeTab(1);
        assertTrue(asked.isEmpty());
        assertEquals(List.of(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE)), changes);
    }

    @Test
    void theKeyboardMovesTheSelectedTabAndTheReaderHearsWhereItIs() {
        bindPane(false, LayoutDirection.LTR);
        header(0).requestFocus();
        bridge.events.clear();
        drive(scene).press(Keys.PAGE_DOWN, COMMAND | Keys.MOD_SHIFT);
        assertEquals(List.of("Beta", "Alpha", "Gamma"), titles());
        assertEquals(1, pane.selectedIndex(), "the selection goes with its tab");
        assertEquals(List.of("0>1"), moves);
        assertEquals(List.of(Change.of(Change.Aspect.CHILDREN, Change.Origin.USER)), changes);
        assertTrue(header(1).isFocused(), "the keyboard stays on the tab it moved");
        frame();
        String said = ComponentStrings.TAB_MOVED.format("Alpha", 2, 3);
        boolean heard = false;
        for (AccessibleEvent event : bridge.events) {
            heard |= event.type() == AccessibleEvent.Type.ANNOUNCEMENT && event.toString().contains(said);
        }
        assertTrue(heard, "said: " + said + " in " + bridge.events);

        drive(scene).press(Keys.PAGE_UP, COMMAND | Keys.MOD_SHIFT);
        drive(scene).press(Keys.PAGE_UP, COMMAND | Keys.MOD_SHIFT);
        assertEquals(List.of("Alpha", "Beta", "Gamma"), titles(), "and stops at the start");
        assertEquals(List.of("0>1", "1>0"), moves);
    }

    @Test
    void moveTabFromCodeTellsNoHandlerAndTheSelectionFollows() {
        bindPane(false, LayoutDirection.LTR);
        pane.moveTab(0, 2);
        assertEquals(List.of("Beta", "Gamma", "Alpha"), titles());
        assertEquals(2, pane.selectedIndex());
        assertTrue(moves.isEmpty());
        assertEquals(List.of(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE)), changes);
    }

    @Test
    void aTabDraggedPastItsNeighbourTakesItsPlaceAndIsToldOnceWhenLetGo() {
        bindPane(false, LayoutDirection.LTR);
        float y = middleY();
        float start = header(0).localToSceneX() + 5;
        float past = header(1).localToSceneX() + header(1).width() - 2;
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, start, y, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(start + 10, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(past, y);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("Beta", "Alpha", "Gamma"), titles(), "moved while dragged");
        assertTrue(moves.isEmpty(), "and told nothing yet");
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, past, y, 1);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
        assertEquals(List.of("0>1"), moves);
        assertEquals(1, pane.selectedIndex(), "the dragged tab is the selected one");
        assertEquals(1, changes.stream().filter(c -> c.aspect() == Change.Aspect.CHILDREN).count());
        assertEquals(header(0).width(), header(1).localToSceneX() - header(0).localToSceneX(), 0.01f,
                "home in its place");
    }

    @Test
    void aDraggedTabStaysUnderThePointerStepByStep() {
        bindPane(false, LayoutDirection.LTR);
        float y = middleY();
        float start = header(0).localToSceneX() + 10;
        float grab = start - header(0).localToSceneX();
        Widget<?> dragged = header(0);
        drive(scene).mouseMoved(start, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, start, y, 1);
        drive(scene).inputBatchEnded();
        float end = header(2).localToSceneX() + header(2).width() - 20;
        float runEnd = header(2).localToSceneX() + header(2).width();
        for (float x = start + 3; x <= end; x += 3) {
            drive(scene).mouseMoved(x, y);
            drive(scene).inputBatchEnded();
            advanceTime(16, null);
            if (x - start >= 4) { // a press becomes a drag past four points
                // Under the pointer, and held inside the bar: it stops at the last tab's end.
                assertEquals(Math.min(x - grab, runEnd - dragged.width()), dragged.localToSceneX(), 0.5f,
                        "under the pointer at " + x);
            }
        }
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, end, y, 1);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
        assertEquals(List.of("Beta", "Gamma", "Alpha"), titles());
        assertEquals(List.of("0>2"), moves, "told once, from where it was to where it is");
    }

    @Test
    void aTabWiderThanTheLastReachesTheEndAndIsDrawnOnABackgroundOfItsOwn() {
        pane = new TabbedPane();
        for (String title : List.of("A rather longer title", "Beta", "Gamma")) {
            pane.addTab(title, new Button(title + " page"));
        }
        pane.onTabMove((from, to) -> moves.add(from + ">" + to));
        bind(new limn.scene.layout.Padding(limn.scene.Insets.all(40), pane));
        scene.setTextRuler(RULER);
        frame();
        Widget<?> wide = header(0);
        float y = middleY();
        float start = wide.localToSceneX() + 10;
        float end = header(2).localToSceneX() + header(2).width() + 60;
        drive(scene).mouseMoved(start, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, start, y, 1);
        drive(scene).inputBatchEnded();
        int[] backgrounds = {0};
        for (float x = start + 3; x <= end; x += 3) {
            // Every move ends: one that traded a tab back and forth for ever hung the window here.
            drive(scene).mouseMoved(x, y);
            drive(scene).inputBatchEnded();
            canvas = new FakeCanvas(400, 300) {
                @Override
                public void fillRoundRect(float x0, float y0, float w, float h, float r, limn.graphics.Paint paint) {
                    if (Math.abs(w - wide.width()) < 0.01f && Math.abs(h - wide.height()) < 0.01f) {
                        backgrounds[0]++;
                    }
                }
            };
            advanceTime(16, wide);
        }
        assertTrue(backgrounds[0] > 0, "the lifted tab fills its own box, so no text shows through it");
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, end, y, 1);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
        assertEquals(List.of("Beta", "Gamma", "A rather longer title"), titles());
        assertEquals(List.of("0>2"), moves);
    }

    @Test
    void rightToLeftATabDraggedLeftwardMovesOnwardInTheOrder() {
        bindPane(false, LayoutDirection.RTL);
        float y = middleY();
        assertTrue(header(1).localToSceneX() < header(0).localToSceneX(), "the second tab is on the left");
        float start = header(0).localToSceneX() + header(0).width() - 5;
        float past = header(1).localToSceneX() + 2;
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, start, y, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(start - 10, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(past, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, past, y, 1);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
        assertEquals(List.of("Beta", "Alpha", "Gamma"), titles());
        assertEquals(List.of("0>1"), moves);
    }

    @Test
    void aReaderFindsEachClosableTabsCloseButtonAndPressesIt() throws InterruptedException {
        bindPane(true, LayoutDirection.LTR);
        frame();
        List<AccessibleNode> buttons = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            if (node.role() == Accessible.Role.BUTTON && node.name().equals(ComponentStrings.TAB_CLOSE.get())) {
                buttons.add(node);
                assertEquals(Accessible.Role.TAB, tree().node(node.parent()).role(), "under its tab");
            }
        }
        assertEquals(3, buttons.size(), "one per tab, drawn or not: " + describe(tree()));
        assertTrue(perform(buttons.get(1).id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        assertEquals(List.of("Alpha", "Gamma"), titles());
        assertFalse(changes.isEmpty());
    }
}
