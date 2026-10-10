package limn.demo;

import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.components.Theme;
import limn.components.tree.Tree;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.TextMetrics;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragEvent;
import limn.scene.event.DropEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.transfer.DataFormat;
import limn.transfer.DropAction;

import java.util.ArrayList;
import java.util.List;

/**
 * The ready-made widgets that drag: two lists whose rows are moved within each and between the
 * two, the picker every business form has; and a picture dragged out of a view into an area of
 * this window or into another program. {@code --scene picker}.
 */
final class PickerScene {

    /** A row, by identity: two may read the same. */
    static final class Field {
        final I18nString name;

        Field(String name) {
            this.name = I18nString.literal(name);
        }

        @Override
        public String toString() {
            return name.get();
        }
    }

    private PickerScene() {
    }

    /** Standalone {@code --scene picker}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(24), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    static Widget<?> content() {
        boolean mac = limn.backend.Platform.current().isMacOs();
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Lists that drag").setRole(Label.Role.TITLE));
        column.add(new Label("Drag rows to another place in their list or to the other list; "
                + (mac ? "Cmd" : "Ctrl") + " or Shift and a click select several, and they go together; "
                + (mac ? "Option" : "Ctrl") + " held at the drop copies. Text dragged from another program "
                + "becomes rows. By keyboard: Alt+Shift+Up and Down move the selected rows, and "
                + (mac ? "Cmd" : "Ctrl") + "+Shift+D picks them up (Tab changes list, the arrows choose the "
                + "place, Enter drops). In the outline a row dropped on a folder goes into it, near its edge "
                + "before or after it, and Alt+Shift+Left and Right move it out and in.").setMuted(true).setWrap(true));

        Label status = new Label("Nothing moved yet.").setMuted(true);
        List<Field> available = new ArrayList<>();
        for (String name : List.of("Customer", "Order number", "Order date", "Due date", "Status", "Total",
                "Discount", "Tax", "Salesperson", "Region", "Carrier", "Tracking code", "Notes")) {
            available.add(new Field(name));
        }
        List<Field> chosen = new ArrayList<>(List.of(new Field("Invoice"), new Field("Paid on")));
        Row lists = new Row();
        lists.gap(16).crossAlignment(Flex.CrossAlignment.START);
        lists.add(Expanded.of(titled("Available columns", list(available, "Available columns", status)), 1));
        lists.add(Expanded.of(titled("Shown in the report", list(chosen, "Shown in the report", status)), 1));

        lists.add(Expanded.of(titled("An outline that rearranges", outline(status)), 1));

        ImageView picture = new ImageView(logo()).setPreferredSize(96, 96).setDragEnabled(true);
        picture.setAccessibleName("Logo");
        picture.setFocusable(true);
        Column about = new Column();
        about.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        about.add(new Label("A picture that drags").setStrong(true));
        about.add(new Label("Its own transparency goes with it, to the area beside it and over another program.")
                .setMuted(true).setWrap(true));
        limn.components.Checkbox asFile = new limn.components.Checkbox("Also as a file (for the desktop, a folder)");
        asFile.onChange(on -> picture.setDragFileName(on ? "limn-logo.png" : null));
        about.add(asFile);
        Row pictures = new Row();
        pictures.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
        pictures.add(picture);
        pictures.add(Expanded.of(about, 1));
        pictures.add(Expanded.of(new PictureArea(), 1));
        column.add(lists);
        column.add(status);
        column.add(pictures);
        return column;
    }

    private static Widget<?> titled(String title, Widget<?> body) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(title).setStrong(true));
        column.add(new SizedBox(SizedBox.UNSET, 250, new Panel(new Padding(Insets.all(6), body))));
        return column;
    }

    /** One of the two lists: its rows reorder, leave for the other and arrive from it. */
    private static ListView<Field> list(List<Field> fields, String name, Label status) {
        ListView<Field> list = new ListView<>(
                field -> new Padding(Insets.symmetric(7, 10), new Label(field.name.get())));
        list.setItems(fields);
        list.setItemName(field -> field.name);
        list.setAccessibleName(name);
        list.setSelectionMode(SelectionMode.MULTI);
        list.setDragEnabled(true);
        list.onReorder((rows, index) -> {
            fields.removeAll(rows);
            fields.addAll(index, rows);
            status.setText(rows + " moved to place " + (index + 1) + " of \"" + name + "\".");
        });
        list.onMoveOut(rows -> {
            fields.removeAll(rows);
            list.refresh();
        });
        list.setDropFormats(ListView.ITEMS, DataFormat.TEXT);
        list.onDrop((event, index) -> {
            boolean copy = event.action() == DropAction.COPY;
            if (event.offer().has(ListView.ITEMS)) {
                event.offer().read(ListView.ITEMS).thenAccept(rows -> {
                    List<Field> arriving = new ArrayList<>();
                    for (Object row : rows) {
                        arriving.add(copy ? new Field(row.toString()) : (Field) row);
                    }
                    fields.addAll(Math.min(index, fields.size()), arriving);
                    list.refresh();
                    status.setText(arriving + (copy ? " copied to \"" : " moved to \"") + name + "\".");
                });
            } else {
                event.offer().read(DataFormat.TEXT).thenAccept(text -> {
                    List<Field> arriving = new ArrayList<>();
                    for (String line : text.split("\\R")) {
                        if (!line.isBlank()) {
                            arriving.add(new Field(line.strip()));
                        }
                    }
                    fields.addAll(Math.min(index, fields.size()), arriving);
                    list.refresh();
                    status.setText(arriving.size() + " row(s) made of text dropped on \"" + name + "\".");
                });
            }
        });
        return list;
    }

    /** A node of the outline, by identity; a folder holds others. */
    static final class Entry {
        final I18nString name;
        final List<Entry> children;

        Entry(String name, Entry... children) {
            this.name = I18nString.literal(name);
            this.children = new ArrayList<>(List.of(children));
        }

        Entry(String name) {
            this.name = I18nString.literal(name);
            this.children = null;
        }

        @Override
        public String toString() {
            return name.get();
        }
    }

    private static boolean detach(List<Entry> from, Entry entry) {
        if (from.remove(entry)) {
            return true;
        }
        for (Entry each : from) {
            if (each.children != null && detach(each.children, entry)) {
                return true;
            }
        }
        return false;
    }

    /** A tree whose nodes are moved within it, and which takes the lists' rows as new entries. */
    private static Tree<Entry> outline(Label status) {
        List<Entry> roots = new ArrayList<>(List.of(
                new Entry("Reports", new Entry("Sales by region"), new Entry("Open invoices"),
                        new Entry("Archive", new Entry("2024"), new Entry("2025"))),
                new Entry("Dashboards", new Entry("Daily")),
                new Entry("Empty folder", new Entry[0]),
                new Entry("Read me")));
        Tree<Entry> tree = new Tree<>(new Tree.Model<Entry>() {
            @Override
            public List<Entry> roots() {
                return roots;
            }

            @Override
            public List<Entry> children(Entry entry) {
                return entry.children == null ? List.of() : entry.children;
            }

            @Override
            public boolean isLeaf(Entry entry) {
                return entry.children == null;
            }

            @Override
            public Widget<?> cellFor(Entry entry) {
                return new Padding(Insets.symmetric(6, 6), new Label(entry.name.get()));
            }

            @Override
            public I18nString nameOf(Entry entry) {
                return entry.name;
            }
        });
        tree.setAccessibleName("Outline");
        tree.expand(roots.get(0));
        tree.setSelectionMode(SelectionMode.MULTI);
        tree.setDragEnabled(true);
        tree.onReorder((entries, parent, index) -> {
            for (Entry entry : entries) {
                detach(roots, entry);
            }
            (parent == null ? roots : parent.children).addAll(index, entries);
            status.setText(entries + " moved " + (parent == null ? "to the top level" : "into \"" + parent + "\"")
                    + ", place " + (index + 1) + ".");
        });
        tree.setDropFormats(ListView.ITEMS, DataFormat.TEXT);
        tree.onDrop((event, parent, index) -> {
            List<Entry> into = parent == null ? roots : parent.children;
            java.util.function.Consumer<List<String>> add = names -> {
                List<Entry> arriving = new ArrayList<>();
                for (String name : names) {
                    arriving.add(new Entry(name));
                }
                into.addAll(Math.min(index, into.size()), arriving);
                if (parent != null) {
                    tree.expand(parent);
                }
                tree.refresh();
                status.setText(arriving + " added " + (parent == null ? "at the top level" : "to \"" + parent + "\"")
                        + ".");
            };
            if (event.offer().has(ListView.ITEMS)) {
                event.offer().read(ListView.ITEMS).thenAccept(rows ->
                        add.accept(rows.stream().map(Object::toString).toList()));
            } else {
                event.offer().read(DataFormat.TEXT).thenAccept(text ->
                        add.accept(text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()));
            }
        });
        return tree;
    }

    /** A disc with a ring and a hole, on nothing: what is not painted is transparent. */
    static Image logo() {
        int size = 240;
        byte[] rgba = new byte[size * size * 4];
        float c = (size - 1) / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double d = Math.hypot(x - c, y - c) / c;
                double hole = Math.hypot(x - c * 1.3, y - c * 0.7) / c;
                int i = (y * size + x) * 4;
                if (d <= 1 && hole > 0.22) {
                    boolean ring = d > 0.82;
                    rgba[i] = (byte) (ring ? 30 : 60 + (int) (120 * x / (float) size));
                    rgba[i + 1] = (byte) (ring ? 60 : 130);
                    rgba[i + 2] = (byte) (ring ? 140 : 235 - (int) (90 * y / (float) size));
                    rgba[i + 3] = (byte) 255;
                }
            }
        }
        return new Image(size, size, rgba);
    }

    /** Takes a picture dropped on it and shows it. */
    private static final class PictureArea extends Widget<PictureArea> {

        private Image dropped;
        private boolean over;

        PictureArea() {
            setAccessibleName("Pictures dropped here");
            setAccessibleRole(limn.accessibility.Accessible.Role.GROUP);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 110);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (event.offer().has(DataFormat.IMAGE)) {
                event.accept();
                if (!over) {
                    over = true;
                    invalidate();
                }
            }
        }

        @Override
        protected void onDragExit(DragEvent event) {
            over = false;
            invalidate();
        }

        @Override
        protected void onDrop(DropEvent event) {
            over = false;
            event.offer().read(DataFormat.IMAGE).thenAccept(image -> {
                dropped = image;
                invalidate();
            });
            invalidate();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surfaceRaised(), over ? 2 : 1, over ? theme.focusRing() : theme.outline());
            if (dropped == null) {
                Font font = theme.body();
                TextMetrics m = textRuler().measure("Hg", font);
                canvas.drawText("Drop a picture here", 12, (height() - m.height()) / 2 + m.ascent(), font,
                        theme.textMuted());
                return;
            }
            float scale = Math.min((width() - 16) / dropped.width(), (height() - 16) / dropped.height());
            float w = dropped.width() * scale;
            float h = dropped.height() * scale;
            canvas.drawImage(dropped, (width() - w) / 2, (height() - h) / 2, w, h);
        }
    }

    /** A surface under a list, so it reads as a list. */
    private static final class Panel extends Widget<Panel> {

        private final Widget<?> child;

        Panel(Widget<?> child) {
            this.child = child;
            add(child);
            setAccessibleIgnored(true); // a frame: the list inside is what a reader is told of
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            child.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surface(), 1, theme.outline());
        }
    }
}
