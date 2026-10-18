package limn.demo.site;

import limn.backend.Clipboard;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.components.Theme;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DropEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.DropAction;
import limn.transfer.Offer;
import limn.transfer.Transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The worked examples of the drag-and-drop guide: a list that reorders, the two lists of a
 * picker, an outline that rearranges, a picture that leaves its view, a widget of the
 * application's own that is dragged and one that takes a drop, and the clipboard. Every region
 * marked here is a code block on that page, and this file is compiled by
 * {@code ./gradlew check}, so the sample a reader copies builds.
 */
public final class DragExample {

    private DragExample() {
    }

    /** A column of a report: what the picker's rows are. */
    public record Field(String name) {
    }

    // #region guide:drag-reorder
    static ListView<Field> reorderable(List<Field> fields) {
        ListView<Field> list = new ListView<>(
                field -> new Padding(Insets.symmetric(7, 10), new Label(field.name())));
        list.setItems(fields);
        list.setDragEnabled(true);
        // The list shows the move; the handler makes it, in the application's own list. The
        // index is counted among the rows that stay, so removing first is always right.
        list.onReorder((rows, index) -> {
            fields.removeAll(rows);
            fields.addAll(index, rows);
        });
        return list;
    }
    // #endregion

    // #region guide:drag-picker
    /** One side of a picker. Two of these, each over its own list, are the whole of it. */
    static ListView<Field> pickerSide(List<Field> fields) {
        ListView<Field> list = reorderable(fields);
        list.setSelectionMode(SelectionMode.MULTI); // several rows go together
        // Rows that were moved somewhere else leave this list.
        list.onMoveOut(rows -> {
            fields.removeAll(rows);
            list.refresh();
        });
        // Rows of another list arrive between two of this one's.
        list.setDropFormats(ListView.ITEMS);
        list.onDrop((event, index) -> event.offer().read(ListView.ITEMS).thenAccept(rows -> {
            List<Field> arriving = new ArrayList<>();
            for (Object row : rows) {
                arriving.add((Field) row);
            }
            fields.addAll(Math.min(index, fields.size()), arriving);
            list.refresh();
        }));
        return list;
    }
    // #endregion

    /** A node of an outline: a folder holds others, a leaf holds none. */
    public static final class Entry {
        final String name;
        final List<Entry> children = new ArrayList<>();
        final boolean folder;

        Entry(String name, Entry... children) {
            this.name = name;
            this.folder = true;
            this.children.addAll(List.of(children));
        }

        Entry(String name) {
            this.name = name;
            this.folder = false;
        }

        boolean take(Entry entry) {
            if (children.remove(entry)) {
                return true;
            }
            for (Entry child : children) {
                if (child.take(entry)) {
                    return true;
                }
            }
            return false;
        }
    }

    // #region guide:drag-tree
    static Tree<Entry> outline(Entry root) {
        Tree<Entry> tree = new Tree<>(new Tree.Model<Entry>() {
            @Override
            public List<Entry> roots() {
                return root.children;
            }

            @Override
            public List<Entry> children(Entry entry) {
                return entry.children;
            }

            @Override
            public boolean isLeaf(Entry entry) {
                return !entry.folder; // an empty folder still takes a drop
            }

            @Override
            public Widget<?> cellFor(Entry entry) {
                return new Padding(Insets.symmetric(6, 6), new Label(entry.name));
            }
        });
        tree.setDragEnabled(true);
        // A null parent is the top level. As in a list, the index is counted without the nodes
        // moved, so they are taken out of wherever they are before they are put in.
        tree.onReorder((entries, parent, index) -> {
            entries.forEach(root::take);
            (parent == null ? root : parent).children.addAll(index, entries);
        });
        return tree;
    }
    // #endregion

    // #region guide:drag-image
    static ImageView draggable(Image picture) {
        ImageView view = new ImageView(picture).setDragEnabled(true);
        view.setAccessibleName("Logo");
        view.setFocusable(true); // so that the keyboard can pick it up too
        // Not on by default, since it writes to disk: a PNG in the temporary directory, for
        // the targets that take a picture only as a file. The desktop is one, a folder another.
        view.setDragFileName("logo.png");
        return view;
    }
    // #endregion

    /** An order of the application's own. */
    public record Order(int number, String customer) {
    }

    // #region guide:drag-format
    /** The object itself, for the widgets of this program; no other program is offered it. */
    static final DataFormat<Order> ORDER =
            DataFormat.inProcess("application/x-example-order", Order.class);
    // #endregion

    // #region guide:drag-source
    /** A card that is dragged: it answers what it carries and hears what was done with it. */
    static final class OrderCard extends Widget<OrderCard> {
        private final Order order;
        private final Consumer<Order> remove;

        OrderCard(Order order, Consumer<Order> remove) {
            this.order = order;
            this.remove = remove;
            setFocusable(true); // the drag key picks up what has the focus
            setAccessibleName("Order " + order.number());
        }

        @Override
        protected Drag onDragStart(DragStartEvent event) {
            // The order for a widget of this program, and a line of text for any other.
            Transfer content = Transfer.of(ORDER, order)
                    .with(DataFormat.TEXT, "Order " + order.number() + ", " + order.customer());
            return Drag.of(content)
                    .allow(DropAction.MOVE, DropAction.COPY) // the first is what is proposed
                    .describedAs("Order " + order.number())
                    .onEnd(action -> {
                        if (action == DropAction.MOVE) {
                            remove.accept(order); // null: cancelled, or nothing took it
                        }
                    });
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(220, 56);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(),
                    theme.tokensFor(this).radiusMedium(), theme.surfaceRaised(), 1, theme.outline());
            canvas.drawText("Order " + order.number(), 12, 24, theme.body(), theme.text());
            canvas.drawText(order.customer(), 12, 44, theme.body(), theme.textMuted());
        }
    }
    // #endregion

    // #region guide:drag-target
    /** A lane that takes orders: it says yes while one hovers, shows it, and takes the drop. */
    static final class Lane extends Widget<Lane> {
        private final String name;
        private final List<Order> orders;
        private boolean over;

        Lane(String name, List<Order> orders) {
            this.name = name;
            this.orders = orders;
            setAccessibleName(name);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (!event.offer().has(ORDER)) {
                return; // says nothing: the parent is asked next
            }
            event.accept();
            if (event.isKeyboard()) {
                event.describePlace("At the end of " + name);
            }
            mark(true);
        }

        @Override
        protected void onDragExit(DragEvent event) {
            mark(false);
        }

        @Override
        protected void onDrop(DropEvent event) {
            mark(false); // no exit follows a drop
            event.offer().read(ORDER).thenAccept(order -> {
                orders.add(order);
                invalidate();
            });
        }

        private void mark(boolean now) {
            if (over != now) {
                over = now;
                invalidate();
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(240, 160);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(),
                    theme.tokensFor(this).radiusMedium(), theme.surface(),
                    over ? 2 : 1, over ? theme.focusRing() : theme.outline());
            float y = 24;
            for (Order order : orders) {
                canvas.drawText("Order " + order.number(), 12, y, theme.body(), theme.text());
                y += 22;
            }
        }
    }
    // #endregion

    // #region guide:drag-files
    /** A widget that opens the files dropped on it, from a file manager or the desktop. */
    static final class FileArea extends Widget<FileArea> {
        private final Consumer<java.nio.file.Path> open;

        FileArea(Consumer<java.nio.file.Path> open) {
            this.open = open;
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (event.offer().has(DataFormat.FILES)) {
                event.accept(DropAction.COPY); // the files stay where they are
            }
        }

        @Override
        protected void onDrop(DropEvent event) {
            event.offer().read(DataFormat.FILES).thenAccept(files -> files.forEach(open));
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(240, 120);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }
    // #endregion

    // #region guide:clipboard-copy
    /** A copy that a spreadsheet pastes as cells, an editor as text, and a paint program as a picture. */
    static void copy(Clipboard clipboard, String title, String html, Image chart) {
        clipboard.set(Transfer.of(DataFormat.TEXT, title)
                .with(DataFormat.HTML, html)
                .with(DataFormat.IMAGE, chart));
    }
    // #endregion

    // #region guide:clipboard-lazy
    /** The picture is rendered when somebody pastes it, and not at all if nobody does. */
    static void copyLazily(Clipboard clipboard, String title, java.util.function.Supplier<Image> render) {
        clipboard.set(Transfer.of(DataFormat.TEXT, title).withLazy(DataFormat.IMAGE, render));
    }
    // #endregion

    // #region guide:clipboard-paste
    static void paste(Clipboard clipboard, Consumer<Image> attach, Consumer<String> insert) {
        Offer there = clipboard.content(); // the formats are known at once
        if (there.has(DataFormat.IMAGE)) {
            there.read(DataFormat.IMAGE).thenAccept(attach); // the value arrives on the UI thread
        } else if (there.has(DataFormat.TEXT)) {
            there.read(DataFormat.TEXT).thenAccept(insert);
        }
    }
    // #endregion

    // #region guide:table-columns
    static <T> void arrange(Table<T> table, Runnable saveLayout) {
        table.setFrozenColumns(1); // the first column stays while the others scroll under it
        // The user moved or resized a column: columns() is the order as it stands now, and
        // each column answers its width, which is what to save.
        table.onColumnsChange(saveLayout);
        // The same move from code, when the saved layout is put back; it is not reported.
        table.moveColumn(table.columns().get(2), 1);
    }
    // #endregion

    /** The rows the guide's picture shows on the left. */
    static List<Field> available() {
        List<Field> fields = new ArrayList<>();
        for (String name : List.of("Customer", "Order number", "Order date", "Due date", "Status",
                "Total", "Discount", "Salesperson")) {
            fields.add(new Field(name));
        }
        return fields;
    }

    /** The rows the guide's picture shows on the right. */
    static List<Field> chosen() {
        return new ArrayList<>(List.of(new Field("Invoice"), new Field("Paid on")));
    }

    /** The picker as a screen: the two lists side by side, each under its title. */
    public static Widget<?> screen() {
        Row row = new Row();
        row.gap(20).crossAlignment(Flex.CrossAlignment.START);
        ListView<Field> left = pickerSide(available());
        left.setSelectedIndex(2); // a row chosen, as one is before it is dragged
        row.add(titled("Available columns", left));
        row.add(titled("Shown in the report", pickerSide(chosen())));
        return row;
    }

    private static Widget<?> titled(String title, ListView<Field> list) {
        list.setAccessibleName(title);
        Column column = new Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(title).setStrong(true));
        column.add(new SizedBox(260, 300, list));
        return column;
    }

    /**
     * The scene the guide's picture is taken from, centred in the window for
     * {@code FormExample}'s reason.
     */
    public static Scene scene() {
        Row centred = new Row();
        centred.mainAlignment(Flex.MainAlignment.CENTER).crossAlignment(Flex.CrossAlignment.CENTER);
        centred.add(screen());
        Scene scene = new Scene(new Padding(Insets.all(28), centred));
        return scene;
    }
}
