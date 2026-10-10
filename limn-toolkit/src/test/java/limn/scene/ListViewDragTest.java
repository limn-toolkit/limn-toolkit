package limn.scene;

import limn.components.ImageView;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.input.Keys;
import limn.scene.event.DragEvent;
import limn.scene.event.DropEvent;
import limn.testing.HeadlessUi;
import limn.transfer.DataFormat;
import limn.transfer.DropAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rows of a {@link ListView} dragged (ADR 054 §4): to another place in their list, which asks the
 * application and then shows, selects and says what it did; to another list, which is asked to
 * take them while the first is told they left; by the pointer, by the scene's keyboard drag and
 * by Alt+Shift with an arrow. And an {@link ImageView}'s picture, when the view says so.
 */
class ListViewDragTest {

    private static final float ROW = 20;

    private HeadlessUi ui;
    private Scene scene;
    private Pair pair;
    private List<String> fruit;
    private List<String> basket;
    private ListView<String> left;
    private ListView<String> right;
    private final List<String> log = new ArrayList<>();
    private java.util.Locale localeBefore;

    /** A row a fixed height tall. */
    private static class Cell extends Widget<Cell> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), ROW);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    /** Its children side by side, 150 wide each. */
    private static final class Pair extends Widget<Pair> {
        void put(Widget<?> child) {
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 120);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected void onLayout() {
            float x = 0;
            for (Widget<?> child : children()) {
                child.measure(Constraints.loose(150, height()));
                child.layoutBox(x, 0, 150, height());
                x += 150;
            }
        }
    }

    @BeforeEach
    void build() {
        ui = new HeadlessUi();
        localeBefore = limn.i18n.I18n.locale();
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        fruit = new ArrayList<>(List.of("apple", "banana", "cherry", "date", "elder"));
        basket = new ArrayList<>(List.of("fig", "grape"));
        left = new ListView<String>(item -> new Cell()).setItems(fruit).setDragEnabled(true);
        left.onReorder((items, index) -> {
            log.add("reorder " + items + " at " + index);
            fruit.removeAll(items);
            fruit.addAll(index, items);
        });
        left.onMoveOut(items -> {
            log.add("out " + items);
            fruit.removeAll(items);
            left.refresh();
        });
        right = new ListView<String>(item -> new Cell()).setItems(basket);
        right.setDropFormats(ListView.ITEMS);
        right.onDrop((DropEvent event, int index) -> event.offer().read(ListView.ITEMS).thenAccept(items -> {
            log.add("drop " + items + " at " + index + " " + event.action());
            for (int i = 0; i < items.size(); i++) {
                basket.add(index + i, (String) items.get(i));
            }
            right.refresh();
        }));
        pair = new Pair();
        pair.put(left);
        pair.put(right);
        scene = new Scene(pair);
        scene.layoutPass(300, 120);
    }

    @AfterEach
    void close() {
        limn.i18n.I18n.setLocale(localeBefore);
        ui.close();
    }

    private void press(float x, float y, int modifiers) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, modifiers, x, y, 1);
        drive(scene).inputBatchEnded();
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
        scene.layoutPass(300, 120);
    }

    private void key(int key, int modifiers) {
        drive(scene).keyEvent(key, 0, true, false, modifiers);
        drive(scene).keyEvent(key, 0, false, false, modifiers);
        drive(scene).inputBatchEnded();
        scene.layoutPass(300, 120);
    }

    private String lastSaid() {
        List<String> said = scene.queuedAnnouncements();
        return said.isEmpty() ? "" : said.get(said.size() - 1);
    }

    @Test
    void aRowDraggedBetweenTwoOthersIsMovedByTheApplicationThenSelectedAndSaid() {
        press(50, 30, 0); // banana
        move(50, 60);
        assertTrue(scene.isDragging());
        move(50, 75); // over elder, as the rows stand without banana
        release(50, 75);

        assertEquals(List.of("reorder [banana] at 3"), log, "the place counts the rows that stay");
        assertEquals(List.of("apple", "cherry", "date", "banana", "elder"), fruit);
        assertEquals(3, left.selectedIndex(), "the selection went with the row");
        assertEquals("banana, 4 of 5", lastSaid());
    }

    @Test
    void aRowDroppedWhereItIsAsksNothing() {
        press(50, 30, 0);
        move(50, 36);
        move(50, 38); // still over the room it left
        release(50, 38);
        assertEquals(List.of(), log);
        assertEquals(1, left.selectedIndex());
    }

    @Test
    void pressingOneOfSeveralSelectedRowsDragsThemAllAndAPlainClickSelectsItAlone() {
        left.setSelectionMode(SelectionMode.MULTI);
        int command = limn.backend.Platform.current().isMacOs() ? Keys.MOD_SUPER : Keys.MOD_CONTROL;
        press(50, 10, 0); // apple
        release(50, 10);
        press(50, 50, command); // and cherry
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, command, 50, 50, 1);
        drive(scene).inputBatchEnded();
        assertArrayEquals(new int[] {0, 2}, left.selectedIndices());

        press(50, 10, 0);
        assertArrayEquals(new int[] {0, 2}, left.selectedIndices(), "the press keeps them: it may be a drag");
        move(50, 60);
        move(50, 95); // after elder
        release(50, 110);
        assertEquals(List.of("reorder [apple, cherry] at 3"), log);
        assertEquals(List.of("banana", "date", "elder", "apple", "cherry"), fruit);
        assertArrayEquals(new int[] {3, 4}, left.selectedIndices());
        assertEquals("2 items, 4 of 5", lastSaid());

        press(50, 70, 0);
        release(50, 70);
        assertArrayEquals(new int[] {3}, left.selectedIndices(), "a click, and not a drag, is this row alone");
    }

    @Test
    void altShiftAndAnArrowMoveTheSelectedRowsOnePlace() {
        left.setSelectedIndex(3);
        scene.requestFocus(left);
        key(Keys.DOWN, Keys.MOD_ALT | Keys.MOD_SHIFT);
        assertEquals(List.of("apple", "banana", "cherry", "elder", "date"), fruit);
        assertEquals(4, left.selectedIndex());
        assertEquals("date, 5 of 5", lastSaid());

        log.clear();
        key(Keys.DOWN, Keys.MOD_ALT | Keys.MOD_SHIFT);
        assertEquals(List.of(), log, "the last row has nowhere lower to go");

        key(Keys.UP, Keys.MOD_ALT | Keys.MOD_SHIFT);
        key(Keys.UP, Keys.MOD_ALT | Keys.MOD_SHIFT);
        assertEquals(List.of("apple", "banana", "date", "cherry", "elder"), fruit);
        assertEquals(2, left.selectedIndex());
    }

    @Test
    void aRowMovedByTheKeyboardWhereItIsInSightDoesNotScrollTheList() {
        fruit.addAll(List.of("fennel", "ginger", "hazel", "iris", "juniper", "kale", "lime"));
        left.refresh();
        scene.layoutPass(300, 120); // six rows of twelve in sight
        left.setSelectedIndex(0);
        scene.requestFocus(left);

        key(Keys.DOWN, Keys.MOD_ALT | Keys.MOD_SHIFT);
        scene.layoutPass(300, 120);
        assertEquals("banana", fruit.get(0));
        assertEquals(0, left.firstVisibleIndex(), "the row it passed is still the first in sight");

        key(Keys.DOWN, Keys.MOD_ALT | Keys.MOD_SHIFT);
        key(Keys.UP, Keys.MOD_ALT | Keys.MOD_SHIFT);
        scene.layoutPass(300, 120);
        assertEquals(0, left.firstVisibleIndex(), "and after two moves between two layouts");
    }

    @Test
    void rowsDroppedOnAnotherListAreTakenThereAndTheFirstIsToldTheyLeft() {
        press(50, 30, 0); // banana
        move(60, 30);
        move(200, 25); // the other list, past the middle of fig
        release(200, 25);

        assertEquals(List.of("drop [banana] at 1 MOVE", "out [banana]"), log);
        assertEquals(List.of("fig", "banana", "grape"), basket);
        assertEquals(List.of("apple", "cherry", "date", "elder"), fruit);
    }

    @Test
    void aListThatNamesNoFormatTakesNothing() {
        right.setDropFormats();
        press(50, 30, 0);
        move(60, 30);
        move(200, 25);
        release(200, 25);
        assertEquals(List.of(), log);
        assertEquals(5, fruit.size());
    }

    @Test
    void theKeyboardDragMovesARowWithTheArrowsAndSaysEachPlace() {
        left.setSelectedIndex(1);
        scene.requestFocus(left);
        int mods = Keys.MOD_SHIFT | (limn.backend.Platform.current().isMacOs() ? Keys.MOD_SUPER : Keys.MOD_CONTROL);
        key(Keys.D, mods);
        assertTrue(scene.isDragging());
        List<String> said = scene.queuedAnnouncements();
        assertEquals(1, said.size(), "what was picked up and where it stands are one announcement: " + said);
        assertTrue(said.get(0).startsWith("Dragging banana."), said.toString());
        assertTrue(said.get(0).endsWith("Before banana"), said.toString());

        key(Keys.DOWN, 0);
        key(Keys.DOWN, 0);
        assertEquals("Before date", lastSaid());
        key(Keys.ENTER, 0);
        assertFalse(scene.isDragging());
        assertEquals(List.of("reorder [banana] at 2"), log);
        assertEquals(List.of("apple", "cherry", "banana", "date", "elder"), fruit);
        assertEquals("banana, 3 of 5", lastSaid(), "where it went is the outcome: no \"Dropped\" cuts it off");

        log.clear();
        key(Keys.D, mods);
        key(Keys.TAB, 0); // the other list
        key(Keys.ENTER, 0);
        assertEquals(List.of("drop [banana] at 2 MOVE", "out [banana]"), log, "at the end of a list with no cursor");
    }

    @Test
    void aListDoesNotDragUnlessAsked() {
        left.setDragEnabled(false);
        press(50, 30, 0);
        move(50, 75);
        assertFalse(scene.isDragging());
    }

    @Test
    void aPictureIsDraggedOnlyFromAViewThatSaysSo() {
        Image picture = new Image(32, 16, new byte[32 * 16 * 4]);
        ImageView view = new ImageView(picture);
        List<String> seen = new ArrayList<>();
        Widget<?> target = new Cell() {
            @Override
            protected void onDragOver(DragEvent event) {
                if (event.offer().has(DataFormat.IMAGE)) {
                    event.accept();
                }
            }

            @Override
            protected void onDrop(DropEvent event) {
                event.offer().read(DataFormat.IMAGE).thenAccept(image -> seen.add(
                        (image == picture ? "the picture " : "another ") + event.action()));
            }
        };
        Pair views = new Pair();
        views.put(view);
        views.put(target);
        scene = new Scene(views);
        scene.layoutPass(300, 120);

        press(10, 8, 0);
        move(40, 8);
        assertFalse(scene.isDragging(), "off unless asked: most pictures are decoration");
        release(40, 8);

        view.setDragEnabled(true);
        press(10, 8, 0);
        move(40, 8);
        assertTrue(scene.isDragging());
        move(200, 10);
        release(200, 10);
        assertEquals(List.of("the picture " + DropAction.COPY), seen);
    }
}
