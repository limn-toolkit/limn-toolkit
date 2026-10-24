package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context menu opened on a row of a list, a table or a tree stays open through the refreshes a
 * timer or a server push makes while the row's item is still there, and acts on that item as the
 * cell was built for it; it closes when the item is gone or its row now shows another item. Before,
 * a menu hung from a row closed on every {@code refresh()} and {@code setItems()}, since those let
 * go of every realized row, so on a list refreshed every second the menu could not be used. A
 * field's own menu still closes with its row, which is realized afresh for the item the list holds,
 * and a page left afterwards is not held by the list's wait for the menu to close.
 */
class RowMenusOutliveARefreshTest extends ComponentTestBase {

    /** An order, the same order whatever its status says: equal by number alone. */
    private static final class Order {
        final int number;
        String status;
        /** What an editable row writes. */
        String note;

        Order(int number, String status) {
            this.number = number;
            this.status = status;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Order o && o.number == number;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(number);
        }

        @Override
        public String toString() {
            return "Order " + number;
        }
    }

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private final List<String> ran = new ArrayList<>();
    /** The order object each menu row acted on, in turn. */
    private final List<Order> actedOn = new ArrayList<>();
    private DisplayMode modeBefore;
    private HeadlessBackend backend;
    private Scene scene;
    private int windows;

    private void open(Widget<?> content, DisplayMode mode) {
        modeBefore = PopupMenu.defaultDisplayMode();
        PopupMenu.setDefaultDisplayMode(mode);
        backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Orders", 600, 400);
        scene = new Scene(content, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.frame();
        windows = openWindows();
    }

    @AfterEach
    void restoreMode() {
        if (modeBefore != null) {
            PopupMenu.setDefaultDisplayMode(modeBefore);
        }
    }

    private int openWindows() {
        return (int) backend.windows().stream().filter(w -> !w.isClosed()).count();
    }

    /** A second of a timer: the clock moves, and every window draws its frames. */
    private void settle() {
        for (int i = 0; i < 4; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }

    /** In the scene, the open menu holds the keyboard on a layer above the page; else it has a window. */
    private boolean menuOpen(DisplayMode mode) {
        return mode == DisplayMode.IN_SCENE
                ? scene.focusedWidget() != null && !isInside(scene.focusedWidget(), scene.root())
                : openWindows() > windows;
    }

    private static boolean isInside(Widget<?> widget, Widget<?> container) {
        for (Widget<?> w = widget; w != null; w = w.parent()) {
            if (w == container) {
                return true;
            }
        }
        return false;
    }

    /** A right press on {@code row}, as a person asks for its menu. */
    private void rightPress(Widget<?> row) {
        Vec2 at = row.localToScene(4, 4);
        drive(scene).mouseButton(Keys.MOUSE_RIGHT, true, 0, at.x(), at.y(), 1);
        drive(scene).mouseButton(Keys.MOUSE_RIGHT, false, 0, at.x(), at.y(), 1);
        drive(scene).inputBatchEnded();
        settle();
    }

    /** Chooses the menu's first row from the keyboard, which the open menu holds. */
    private void chooseFirst() {
        drive(scene).press(Keys.HOME);
        drive(scene).press(Keys.ENTER);
        settle();
    }

    private Menu menuFor(Order order) {
        return new Menu().addItem("Open " + order, () -> {
                    ran.add("open " + order.number);
                    actedOn.add(order);
                })
                .addItem("Cancel " + order, () -> ran.add("cancel " + order.number));
    }

    private static List<Order> orders(int... numbers) {
        List<Order> out = new ArrayList<>();
        for (int n : numbers) {
            out.add(new Order(n, "open"));
        }
        return out;
    }

    private static Widget<?> realizedRowOf(Widget<?> list, Function<Widget<?>, Boolean> matches) {
        for (Widget<?> child : list.children()) {
            if (!(child instanceof ScrollBar) && matches.apply(child)) {
                return child;
            }
        }
        throw new AssertionError("no realized row matches");
    }

    private ListView<Order> regionList(List<Widget<?>> built) {
        return new ListView<>(order -> {
            Widget<?> region = ContextMenus.attach(new Label(order + " " + order.status), () -> menuFor(order));
            region.setId("row-" + order.number);
            built.add(region);
            return region;
        });
    }

    private static Widget<?> row(Widget<?> list, int number) {
        return realizedRowOf(list, w -> Objects.equals(w.id(), "row-" + number));
    }

    // ------------------------------------------------------------------ ListView, a region per row

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aRowsMenuStaysOpenThroughATimersRefreshesAndActsOnItsItem(DisplayMode mode) {
        List<Widget<?>> built = new ArrayList<>();
        ListView<Order> list = regionList(built);
        List<Order> orders = orders(1041, 1042, 1043);
        list.setItems(orders);
        open(list, mode);
        Widget<?> asked = row(list, 1042);
        rightPress(asked);
        assertTrue(menuOpen(mode), mode + ": the right press opened the row's menu");

        // A refresh every second, as a timer makes: an order arrives above, the statuses move.
        for (int tick = 0; tick < 3; tick++) {
            orders.add(0, new Order(2000 + tick, "new"));
            orders.forEach(o -> o.status = "paid");
            list.refresh();
            settle();
            assertTrue(menuOpen(mode), mode + ": still open after refresh " + (tick + 1));
        }
        assertSame(asked, row(list, 1042), mode + ": the row is the one the menu was opened on");
        assertEquals(4, orders.indexOf(new Order(1042, "")));
        assertEquals(4, indexOfRealized(list, asked), mode + ": at its order's new index");

        chooseFirst();
        assertEquals(List.of("open 1042"), ran, mode + ": the menu acted on the order it was opened for");
        assertFalse(menuOpen(mode), mode.name());
        settle();
        Widget<?> afterwards = row(list, 1042);
        assertNotSame(asked, afterwards, mode + ": realized afresh once the menu closed");
        assertEquals("Order 1042 paid", ((Label) afterwards.children().get(0)).text(),
                mode + ": and showing what the list holds now");
    }

    /**
     * A push of new objects equal to the old leaves the menu open, acting on the order its row was
     * built for: the object the cell factory was given, equal to the list's but not the list's,
     * which is what the documentation tells an application to act through the key for.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void setItemsWithTheSameOrdersKeepsTheMenuOpen(DisplayMode mode) {
        List<Widget<?>> built = new ArrayList<>();
        ListView<Order> list = regionList(built);
        List<Order> first = orders(1041, 1042, 1043);
        list.setItems(first);
        open(list, mode);
        rightPress(row(list, 1043));

        for (int tick = 0; tick < 3; tick++) {
            list.setItems(orders(1041, 1042, 1043)); // a server push: new objects, the same orders
            settle();
        }

        assertTrue(menuOpen(mode), mode + ": a push with the same orders leaves the menu open");
        chooseFirst();
        assertEquals(List.of("open 1043"), ran, mode.name());
        assertSame(first.get(2), actedOn.get(0), mode + ": on the object its row was built for");
        assertNotSame(list.items().get(2), actedOn.get(0), mode + ": which is not the list's now");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theMenuClosesWhenItsOrderIsGone(DisplayMode mode) {
        List<Widget<?>> built = new ArrayList<>();
        ListView<Order> list = regionList(built);
        List<Order> orders = orders(1041, 1042, 1043);
        list.setItems(orders);
        open(list, mode);
        rightPress(row(list, 1042));

        orders.remove(1);
        list.refresh();
        settle();

        assertFalse(menuOpen(mode), mode + ": the order it was opened for is gone");
        assertEquals(windows, openWindows(), mode + ": no window of the menu's left open");
        chooseFirst();
        assertEquals(List.of(), ran, mode + ": nothing acted");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theMenuClosesWhenItsRowNowShowsAnotherOrder(DisplayMode mode) {
        List<Widget<?>> built = new ArrayList<>();
        ListView<Order> list = regionList(built);
        list.setItems(orders(1041, 1042, 1043));
        open(list, mode);
        rightPress(row(list, 1042));

        list.setItems(orders(1041, 1099, 1043)); // the second row is another order now

        settle();
        assertFalse(menuOpen(mode), mode + ": its row shows another order");
        chooseFirst();
        assertEquals(List.of(), ran, mode.name());
    }

    // ---------------------------------------------------------------- ListView, a field per row

    /** A list of editable orders: each row a field, whose edits write the order's note. */
    private static ListView<Order> fieldList() {
        return new ListView<>(order -> {
            TextField field = new TextField().setText(order.status + " " + order.number);
            field.onChange(text -> order.note = text);
            field.setId("row-" + order.number);
            return field;
        });
    }

    /** Puts the keyboard in the row's field and opens the field's own menu, as Shift+F10 does. */
    private TextField openFieldMenu(ListView<Order> list, int number, DisplayMode mode) {
        TextField field = (TextField) row(list, number);
        scene.requestFocus(field);
        field.setCaretPosition(0);
        settle();
        drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
        settle();
        assertTrue(menuOpen(mode), mode + ": Shift+F10 opened the field's menu");
        return field;
    }

    /**
     * A field cell's own Cut, Copy and Paste menu closes when the list refreshes, as it did before
     * rows were kept for their menus, and the row shows the order as it is now. Kept, the field
     * went on showing what it showed before the refresh for as long as nothing refreshed again,
     * with the keyboard gone elsewhere too, and a reader read that.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aFieldCellsOwnMenuClosesOnARefreshAndTheRowShowsTheOrderAsItIsNow(DisplayMode mode) {
        List<Order> orders = orders(1041, 1042, 1043);
        ListView<Order> list = fieldList();
        list.setItems(orders);
        open(list, mode);
        TextField field = openFieldMenu(list, 1042, mode);

        orders.forEach(o -> o.status = "paid");
        list.refresh(); // one push, and no timer after it
        settle();

        assertFalse(menuOpen(mode), mode + ": the field's menu closed with its row");
        assertEquals(windows, openWindows(), mode + ": no window of the menu's left open");
        TextField now = (TextField) row(list, 1042);
        assertNotSame(field, now, mode + ": the row is realized afresh");
        assertEquals("paid 1042", now.text(), mode + ": showing the order as it is now");
        assertSame(list, scene.focusedWidget(),
                mode + ": and the keyboard falls back to the list, as a refresh has it do from a row");
    }

    /**
     * A push of new objects equal to the old ones (a server's reply) while a field's own menu is
     * open: kept, the field stayed bound to the object its row was built for, which is no longer in
     * the list, so an edit made in it (which the menu's Paste invites) went to that departed order
     * and the application's handler wrote it where nothing reads it. A field cannot be bound to
     * another object than the one its row was built for, so its row goes with the push, and the
     * edit lands in the order the list holds.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anEditAfterAPushOfNewObjectsLandsInTheOrderTheListHolds(DisplayMode mode) {
        ListView<Order> list = fieldList();
        List<Order> before = orders(1041, 1042, 1043);
        list.setItems(before);
        open(list, mode);
        openFieldMenu(list, 1042, mode);

        List<Order> pushed = orders(1041, 1042, 1043);
        pushed.forEach(o -> o.status = "paid");
        list.setItems(pushed);
        settle();
        drive(scene).press(Keys.ESCAPE);
        settle();
        TextField field = (TextField) row(list, 1042);
        scene.requestFocus(field);
        field.setCaretPosition(field.text().length());
        settle();
        drive(scene).type("!");
        settle();

        assertEquals(null, before.get(1).note, mode + ": nothing was written to the order that left");
        assertEquals("paid 1042!", pushed.get(1).note, mode + ": the edit is the listed order's");
    }

    // ----------------------------------------------- a row's own controls (R17-1, R17-4)

    /** The open popup menus, read by the test: a "..." button's menu is the application's own. */
    private final List<PopupMenu> shown = new ArrayList<>();

    /** A list whose rows are a label, a "..." button opening the row's menu, and a note field. */
    private ListView<Order> buttonList() {
        return new ListView<>(order -> {
            Button dots = new Button("...");
            dots.setId("dots-" + order.number);
            dots.onAction(() -> {
                PopupMenu menu = new PopupMenu(menuFor(order));
                shown.add(menu);
                menu.showAnchored(dots, 0, 0, dots.width(), dots.height());
            });
            TextField note = new TextField().setText(order.status + " " + order.number);
            note.setId("note-" + order.number);
            note.onChange(text -> order.note = text);
            limn.scene.layout.Row row = new limn.scene.layout.Row();
            row.add(new Label(order.toString()));
            row.add(dots);
            row.add(note);
            row.setId("row-" + order.number);
            return row;
        });
    }

    private static Widget<?> find(Widget<?> root, String id) {
        if (id.equals(root.id())) {
            return root;
        }
        for (Widget<?> child : root.children()) {
            Widget<?> found = find(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * R17-1: a menu opened from a "..." button in a row, the usual business-app row, stays open
     * through a timer's refreshes, as one opened from the row's label does. The rule that lets a
     * row go when the keyboard is in it is for a field's typed text; a press on the button put the
     * keyboard on it, and the menu closed within a second, every time.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuOpenedFromAButtonInTheRowStaysOpenThroughATimersRefreshes(DisplayMode mode) {
        List<Order> orders = orders(1041, 1042, 1043);
        ListView<Order> list = buttonList();
        list.setItems(orders);
        open(list, mode);
        Button dots = (Button) find(row(list, 1042), "dots-1042");
        drive(scene).click(dots);
        settle();
        assertTrue(shown.get(0).isOpen(), mode + ": the button opened the row's menu");

        for (int refresh = 0; refresh < 3; refresh++) {
            orders.forEach(o -> o.status = "paid");
            list.refresh();
            settle();
            assertTrue(shown.get(0).isOpen(), mode + ": open after refresh " + (refresh + 1));
        }
        chooseFirst();
        assertEquals(List.of("open 1042"), ran, mode.name());
    }

    /**
     * R17-4: a click into the note field of a row kept for its menu closes the menu and lets the
     * row go, and the keyboard is in the note field of the row realized afresh, bound to the order
     * the list holds; before, it went to the list, the click was undone, and the next letters
     * typed moved the selection.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aClickIntoAFieldOfARowKeptForItsMenuKeepsTheKeyboardInThatField(DisplayMode mode) {
        List<Order> before = orders(1041, 1042, 1043);
        ListView<Order> list = buttonList();
        list.setItems(before);
        open(list, mode);
        Button dots = (Button) find(row(list, 1042), "dots-1042");
        drive(scene).click(dots);
        settle();
        List<Order> pushed = orders(1041, 1042, 1043);
        pushed.forEach(o -> o.status = "paid");
        list.setItems(pushed); // the row stays for its menu, bound to the order it was built for
        settle();
        assertTrue(shown.get(0).isOpen(), mode + ": kept for its menu");

        shown.get(0).close(); // as the click into the field closes it, in either display mode
        TextField note = (TextField) find(row(list, 1042), "note-1042");
        scene.requestFocus(note);
        settle();

        Widget<?> focused = scene.focusedWidget();
        assertTrue(focused instanceof TextField && "note-1042".equals(focused.id()),
                mode + ": the keyboard is in the row's note field, not on the list: " + focused);
        assertTrue(focused != note, mode + ": of the row realized afresh");
        drive(scene).type("!");
        settle();
        assertEquals("paid 1042!", pushed.get(1).note, mode + ": and what is typed is the listed order's");
        assertEquals(null, before.get(1).note, mode.name());
    }

    // ------------------------------------------------------------- a page left keeps nothing

    /** Collects until {@code ref} is gone, or gives up: whether nothing holds it any more. */
    private static boolean collected(WeakReference<?> ref) {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get() == null;
    }

    /** Opens {@code content} on a page of its own, and answers the page weakly. */
    private WeakReference<Widget<?>> onAPage(Widget<?> content, DisplayMode mode) {
        limn.scene.layout.Column page = new limn.scene.layout.Column();
        page.add(content);
        open(page, mode);
        return new WeakReference<>(page);
    }

    /**
     * The case rows are kept for: a menu open on a row through a timer's refresh, and then the
     * server removes that order, so the menu closes with its row. The list had asked the scene to
     * tell it when a popup in it let go, and the row left the list before its menu closed, so it
     * was never told: the scene held the list, and the whole page with it, for as long as the
     * window lived, one page for every such visit.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPageLeftAfterItsRowsMenuOutlivedARefreshAndThenItsOrderIsCollected(DisplayMode mode) {
        WeakReference<Widget<?>> page = listPageWhoseMenuOutlivedARefreshAndThenItsOrder(mode);
        scene.setRoot(new limn.scene.layout.Column());
        settle();
        assertTrue(collected(page), mode + ": nothing holds the page that left");
    }

    private WeakReference<Widget<?>> listPageWhoseMenuOutlivedARefreshAndThenItsOrder(DisplayMode mode) {
        ListView<Order> list = regionList(new ArrayList<>());
        List<Order> orders = orders(1041, 1042, 1043);
        list.setItems(orders);
        WeakReference<Widget<?>> page = onAPage(list, mode);
        rightPress(row(list, 1042));
        orders.forEach(o -> o.status = "paid");
        list.refresh();
        settle();
        assertTrue(menuOpen(mode), mode + ": kept through the refresh");
        orders.remove(1);
        list.refresh();
        settle();
        assertFalse(menuOpen(mode), mode + ": closed with its order");
        return page;
    }

    /** The same on a tree, whose node goes on a later refresh. */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aTreePageLeftAfterItsRowsMenuOutlivedARefreshAndThenItsNodeIsCollected(DisplayMode mode) {
        WeakReference<Widget<?>> page = treePageWhoseMenuOutlivedARefreshAndThenItsNode(mode);
        scene.setRoot(new limn.scene.layout.Column());
        settle();
        assertTrue(collected(page), mode + ": nothing holds the page that left");
    }

    private WeakReference<Widget<?>> treePageWhoseMenuOutlivedARefreshAndThenItsNode(DisplayMode mode) {
        List<Order> roots = orders(1041, 1042, 1043);
        Tree<Order> tree = orderTree(roots);
        WeakReference<Widget<?>> page = onAPage(tree, mode);
        rightPress(realizedRowOf(tree, w -> Objects.equals(w.id(), "row-1043")));
        roots.add(0, new Order(2000, "new"));
        tree.refresh();
        settle();
        assertTrue(menuOpen(mode), mode + ": kept through the refresh");
        roots.remove(new Order(1043, ""));
        tree.refresh();
        settle();
        assertFalse(menuOpen(mode), mode + ": closed with its node");
        return page;
    }

    /**
     * A field's own menu open across a refresh, closed with Escape, and the page left after the
     * next refresh: the list had asked again to be told of a popup letting go while none was open
     * in it, and the scene held the page. (The next refresh takes the field out of the page: a
     * field holding the keyboard has its caret's blink queued on the test's clock, which never
     * reaches it.)
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPageLeftAfterAFieldsMenuWasOpenAcrossARefreshIsCollected(DisplayMode mode) {
        WeakReference<Widget<?>> page = fieldPageWhoseMenuWasOpenAcrossARefresh(mode);
        scene.setRoot(new limn.scene.layout.Column());
        settle();
        assertTrue(collected(page), mode + ": nothing holds the page that left");
    }

    private WeakReference<Widget<?>> fieldPageWhoseMenuWasOpenAcrossARefresh(DisplayMode mode) {
        List<Order> orders = orders(1041, 1042, 1043);
        ListView<Order> list = fieldList();
        list.setItems(orders);
        WeakReference<Widget<?>> page = onAPage(list, mode);
        openFieldMenu(list, 1042, mode);
        orders.forEach(o -> o.status = "paid");
        list.refresh();
        settle();
        drive(scene).press(Keys.ESCAPE);
        settle();
        list.refresh();
        settle();
        return page;
    }

    // ------------------------------------------------------------------------------- Table

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuOnATableCellStaysOpenThroughRefreshAndSetRows(DisplayMode mode) {
        List<Order> orders = orders(1041, 1042, 1043);
        Table<Order> table = Table.of(
                Column.<Order, Integer>of("Number", o -> o.number, String::valueOf),
                Column.<Order>widget("Actions", order -> {
                    Widget<?> region = ContextMenus.attach(new Label("…"), () -> menuFor(order));
                    region.setId("row-" + order.number);
                    return region;
                }));
        table.setRows(orders);
        open(table, mode);
        Widget<?> asked = realizedRowOf(table, w -> Objects.equals(w.id(), "row-1042"));
        rightPress(asked);
        assertTrue(menuOpen(mode), mode.name());

        orders.add(0, new Order(2000, "new"));
        table.refresh();
        settle();
        table.setRows(orders(2000, 1041, 1042, 1043));
        settle();

        assertTrue(menuOpen(mode), mode + ": open through refresh() and setRows()");
        assertSame(asked, realizedRowOf(table, w -> Objects.equals(w.id(), "row-1042")), mode.name());
        chooseFirst();
        assertEquals(List.of("open 1042"), ran, mode.name());
    }

    /**
     * The table's case of the push above: a widget cell's field with its own menu open when
     * {@code setRows} hands the table new objects equal to the old. Kept, the field stayed bound
     * to the order its column factory was given, the keyboard came back into it as the menu
     * closed, and an edit made there went to an order the table no longer held while the table's
     * own order kept the server's note (R17-2). The row goes with the push, as a list's does.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anEditInATableFieldAfterAPushOfNewObjectsLandsInTheOrderTheTableHolds(DisplayMode mode) {
        Table<Order> table = Table.of(
                Column.<Order, Integer>of("Number", o -> o.number, String::valueOf),
                Column.<Order>widget("Note", order -> {
                    TextField field = new TextField().setText(order.status + " " + order.number);
                    field.onChange(text -> order.note = text);
                    field.setId("row-" + order.number);
                    return field;
                }));
        List<Order> before = orders(1041, 1042, 1043);
        table.setRows(before);
        open(table, mode);
        TextField field = (TextField) realizedRowOf(table, w -> Objects.equals(w.id(), "row-1042"));
        scene.requestFocus(field);
        field.setCaretPosition(0);
        settle();
        drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
        settle();
        assertTrue(menuOpen(mode), mode + ": Shift+F10 opened the field's menu");

        List<Order> pushed = orders(1041, 1042, 1043);
        pushed.forEach(o -> o.status = "paid");
        table.setRows(pushed);
        settle();
        drive(scene).press(Keys.ESCAPE);
        settle();
        TextField now = (TextField) realizedRowOf(table, w -> Objects.equals(w.id(), "row-1042"));
        assertEquals("paid 1042", now.text(), mode + ": the row shows the order the table holds");
        scene.requestFocus(now);
        now.setCaretPosition(now.text().length());
        settle();
        drive(scene).type("!");
        settle();

        assertEquals(null, before.get(1).note, mode + ": nothing was written to the order that left");
        assertEquals("paid 1042!", pushed.get(1).note, mode + ": the edit is the table's order's");
    }

    // -------------------------------------------------------------------------------- Tree

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuOnATreeRowStaysOpenThroughRefreshAndClosesWhenItsNodeIsGone(DisplayMode mode) {
        List<Order> roots = orders(1041, 1042, 1043);
        Tree<Order> tree = orderTree(roots);
        open(tree, mode);
        Widget<?> asked = realizedRowOf(tree, w -> Objects.equals(w.id(), "row-1043"));
        rightPress(asked);

        roots.add(0, new Order(2000, "new"));
        tree.refresh();
        settle();
        assertTrue(menuOpen(mode), mode + ": open through the refresh");
        assertSame(asked, realizedRowOf(tree, w -> Objects.equals(w.id(), "row-1043")), mode.name());

        roots.remove(new Order(1043, ""));
        tree.refresh();
        settle();
        assertFalse(menuOpen(mode), mode + ": closed once its node is gone");
        assertEquals(windows, openWindows(), mode.name());
    }

    /** A flat tree of orders, each row a region with the order's menu. */
    private Tree<Order> orderTree(List<Order> roots) {
        return new Tree<>(new Tree.Model<Order>() {
            @Override
            public List<Order> roots() {
                return roots;
            }

            @Override
            public List<Order> children(Order node) {
                return List.of();
            }

            @Override
            public Widget<?> cellFor(Order node) {
                Widget<?> region = ContextMenus.attach(new Label(node.toString()), () -> menuFor(node));
                region.setId("row-" + node.number);
                return region;
            }
        });
    }

    private static int indexOfRealized(ListView<?> list, Widget<?> row) {
        int bars = 0;
        for (Widget<?> child : list.children()) {
            if (child instanceof ScrollBar) {
                bars++;
            }
        }
        return list.children().indexOf(row) - bars + list.firstVisibleIndex();
    }
}
