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
 * go of every realized row, so on a list refreshed every second the menu could not be used. A page
 * left afterwards is not held by the list's wait for the menu to close.
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
        return new Menu().addItem("Open " + order, () -> ran.add("open " + order.number))
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

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void setItemsWithTheSameOrdersKeepsTheMenuOpen(DisplayMode mode) {
        List<Widget<?>> built = new ArrayList<>();
        ListView<Order> list = regionList(built);
        list.setItems(orders(1041, 1042, 1043));
        open(list, mode);
        rightPress(row(list, 1043));

        for (int tick = 0; tick < 3; tick++) {
            list.setItems(orders(1041, 1042, 1043)); // a server push: new objects, the same orders
            settle();
        }

        assertTrue(menuOpen(mode), mode + ": a push with the same orders leaves the menu open");
        chooseFirst();
        assertEquals(List.of("open 1043"), ran, mode.name());
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

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aFieldCellsOwnMenuStaysOpenThroughARefreshAndActsOnThatField(DisplayMode mode) {
        List<Order> orders = orders(1041, 1042, 1043);
        ListView<Order> list = fieldList();
        list.setItems(orders);
        open(list, mode);
        TextField field = openFieldMenu(list, 1042, mode);

        for (int tick = 0; tick < 3; tick++) {
            list.refresh();
            settle();
        }

        assertTrue(menuOpen(mode), mode + ": the field's menu stays through the refreshes");
        assertSame(field, row(list, 1042), mode + ": the field is the one the menu acts on");
        drive(scene).press(Keys.END); // Select All, the last row
        drive(scene).press(Keys.ENTER);
        settle();
        assertEquals(0, field.selectionStart(), mode + ": Select All acted on the field");
        assertEquals(field.text().length(), field.selectionEnd(), mode.name());
        assertSame(field, scene.focusedWidget(), mode + ": which keeps the keyboard after the menu");
        assertSame(field, row(list, 1042), mode + ": and is not taken away as the menu closes");
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
