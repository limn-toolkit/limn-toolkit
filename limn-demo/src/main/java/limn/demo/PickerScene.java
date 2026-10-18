package limn.demo;

import limn.components.Accelerator;
import limn.components.Checkbox;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.components.Theme;
import limn.components.tree.Tree;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.TextMetrics;
import limn.i18n.I18nString;
import limn.i18n.PluralString;
import limn.icons.tabler.Tabler;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DropEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.DropAction;
import limn.transfer.Transfer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag and drop on one page: the columns of a report and its folders, rows moved within and
 * between lists and into an outline; a board of tasks whose cards move by pointer or keyboard,
 * one of them pinned and one column that takes only what is in progress; and a logo dragged into
 * the one area that takes files and pictures, from this window or from another program.
 * {@code --scene picker}.
 */
final class PickerScene {

    /** Every string the page shows. */
    static final class Text {
        static final I18nString REPORT = new I18nString("showcase.drag.report", "Report");
        static final I18nString AVAILABLE = new I18nString("showcase.drag.available", "Available columns");
        static final I18nString SHOWN = new I18nString("showcase.drag.shown", "Shown in the report");
        static final I18nString FOLDERS = new I18nString("showcase.drag.folders", "Report folders");
        static final I18nString TASKS = new I18nString("showcase.drag.tasks", "Tasks");
        static final I18nString TO_DO = new I18nString("showcase.drag.toDo", "To do");
        static final I18nString IN_PROGRESS = new I18nString("showcase.drag.inProgress", "In progress");
        static final I18nString APPROVED = new I18nString("showcase.drag.approved", "Approved");
        static final I18nString BOARD_HEADER = new I18nString("showcase.drag.boardHeader", "{0} · {1}");
        static final I18nString LOGO = new I18nString("showcase.drag.logo", "Logo");
        static final I18nString AS_FILE = new I18nString("showcase.drag.asFile", "Also as a file");
        static final I18nString ATTACHMENTS = new I18nString("showcase.drag.attachments", "Attachments");
        static final I18nString DROP_HERE = new I18nString("showcase.drag.dropHere", "Drop files or a picture here");
        static final I18nString KEYBOARD_HINT = new I18nString("showcase.drag.keyboardHint",
                "{0} picks up the selection; Tab chooses where, the arrows the place, Enter drops.");

        static final I18nString MOVED_TO = new I18nString("showcase.drag.movedTo", "{0} moved to “{1}”.");
        static final I18nString COPIED_TO = new I18nString("showcase.drag.copiedTo", "{0} copied to “{1}”.");
        static final I18nString ADDED_TO = new I18nString("showcase.drag.addedTo", "{0} added to “{1}”.");
        static final I18nString ADDED_TOP = new I18nString("showcase.drag.addedTop", "{0} added at the top level.");
        static final I18nString CARD_MOVED = new I18nString("showcase.drag.cardMoved", "“{0}” moved.");
        static final I18nString CARD_COPIED = new I18nString("showcase.drag.cardCopied", "“{0}” copied.");
        static final I18nString CARD_NOT_TAKEN = new I18nString("showcase.drag.cardNotTaken", "“{0}” stayed where it was.");
        static final I18nString PINNED = new I18nString("showcase.drag.pinned", "“{0}” is pinned and cannot be moved.");
        static final I18nString REFUSED = new I18nString("showcase.drag.refused",
                "“{0}” takes only cards that are in progress.");
        static final I18nString PLACE = new I18nString("showcase.drag.place", "{0}, place {1} of {2}");
        static final PluralString FILES_DROPPED = PluralString.of("showcase.drag.filesDropped",
                "{0} file dropped.", "{0} files dropped.");
        static final PluralString MORE = PluralString.of("showcase.drag.more", "and {0} more", "and {0} more");
        static final I18nString PICTURE_DROPPED = new I18nString("showcase.drag.pictureDropped", "Picture dropped.");

        private Text() {
        }
    }

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
        return scene;
    }

    static Widget<?> content() {
        Column page = Page.column();
        String key = Accelerator.command(Keys.D, Keys.MOD_SHIFT).display();
        Label keyboard = Page.hint(I18nString.EMPTY);
        Page.followLanguage(() -> keyboard.setText(Text.KEYBOARD_HINT.format(key)));
        page.add(keyboard);

        // ---- the report: its columns in two lists, and its folders in an outline
        List<Field> available = new ArrayList<>();
        for (String name : List.of("Customer", "Order number", "Due date", "Total", "Salesperson", "Region")) {
            available.add(new Field(name));
        }
        List<Field> chosen = new ArrayList<>(List.of(new Field("Invoice"), new Field("Paid on")));
        Row report = new Row();
        report.gap(16).crossAlignment(Flex.CrossAlignment.START);
        ListView<Field> availableList = list(available, Text.AVAILABLE);
        ListView<Field> chosenList = list(chosen, Text.SHOWN);
        Tree<Entry> folders = outline();
        report.add(Expanded.of(Page.field(Text.AVAILABLE, availableList, framed(availableList)), 1));
        report.add(Expanded.of(Page.field(Text.SHOWN, chosenList, framed(chosenList)), 1));
        report.add(Expanded.of(Page.field(Text.FOLDERS, folders, framed(folders)), 1));
        page.add(Page.firstSection(Text.REPORT));
        page.add(report);

        // ---- the tasks: three columns of cards
        Board todo = new Board(Text.TO_DO, null);
        Board doing = new Board(Text.IN_PROGRESS, null);
        Board approved = new Board(Text.APPROVED, doing);
        todo.cards.addAll(List.of(new Card("Invoice 1042"), new Card("Call Harbor Supply"),
                new Card("Renew the lease")));
        doing.cards.addAll(List.of(new Card("Audit Q3 invoices", true), new Card("Review the budget")));
        approved.cards.add(new Card("Pay Bayside Freight"));
        Row boards = new Row();
        boards.gap(16).crossAlignment(Flex.CrossAlignment.START);
        boards.add(Expanded.of(todo, 1));
        boards.add(Expanded.of(doing, 1));
        boards.add(Expanded.of(approved, 1));
        page.add(Page.section(Text.TASKS));
        page.add(boards);

        // ---- the logo, and the one area that takes what is dropped
        ImageView picture = new ImageView(logo()).setPreferredSize(96, 96).setDragEnabled(true);
        picture.setAccessibleName(Text.LOGO);
        picture.setFocusable(true);
        Checkbox asFile = new Checkbox(Text.AS_FILE);
        asFile.onChange(on -> picture.setDragFileName(on ? "logo.png" : null));
        Row logo = Page.row(16, picture, asFile);
        logo.add(Expanded.spacer(1));
        page.add(Page.section(Text.LOGO));
        page.add(logo);
        page.add(Page.section(Text.ATTACHMENTS));
        page.add(new DropZone());
        return page;
    }

    /** A list or an outline on a surface, so it reads as one; short enough to see the page whole. */
    private static Widget<?> framed(Widget<?> body) {
        return new SizedBox(SizedBox.UNSET, 200, new Panel(new Padding(Insets.all(6), body)));
    }

    /**
     * Above and below the words of a row, in the two lists and in the outline alike: the three
     * stand side by side, and with 7 in the lists and 6 in the outline their rows drifted apart,
     * the outline's fifth row 10 pt above the lists' fifth, measured.
     */
    private static final float ROW_PADDING = 7;

    /** One of the two lists: its rows reorder, leave for the other and arrive from it. */
    private static ListView<Field> list(List<Field> fields, I18nString name) {
        ListView<Field> list = new ListView<>(
                field -> new Padding(Insets.symmetric(ROW_PADDING, 10), new Label(field.name.get())));
        list.setItems(fields);
        list.setItemName(field -> field.name);
        list.setAccessibleName(name);
        list.setSelectionMode(SelectionMode.MULTI);
        list.setDragEnabled(true);
        list.onReorder((rows, index) -> {
            fields.removeAll(rows);
            fields.addAll(index, rows);
            Page.report(Text.MOVED_TO.format(names(rows), name.get()));
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
                    Page.report((copy ? Text.COPIED_TO : Text.MOVED_TO).format(names(arriving), name.get()));
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
                    Page.report(Text.ADDED_TO.format(names(arriving), name.get()));
                });
            }
        });
        return list;
    }

    /** The names of rows, as a list reads them: "Total, Region". */
    private static String names(List<?> rows) {
        StringBuilder out = new StringBuilder();
        for (Object row : rows) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(row);
        }
        return out.toString();
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

    /** The report's folders: moved within, and taking the lists' rows as new entries. */
    private static Tree<Entry> outline() {
        List<Entry> roots = new ArrayList<>(List.of(
                new Entry("Sales", new Entry("By region"), new Entry("Open invoices")),
                new Entry("Purchasing", new Entry("Suppliers")),
                new Entry("Archive", new Entry("2024"), new Entry("2025"))));
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
                Label label = new Label(entry.name.get());
                label.setIcon(Tabler.outline(entry.children == null ? "file-text" : "folder"));
                return new Padding(Insets.symmetric(ROW_PADDING, 6), label);
            }

            @Override
            public I18nString nameOf(Entry entry) {
                return entry.name;
            }
        });
        tree.setAccessibleName(Text.FOLDERS);
        tree.expand(roots.get(0));
        tree.setSelectionMode(SelectionMode.MULTI);
        tree.setDragEnabled(true);
        tree.onReorder((entries, parent, index) -> {
            for (Entry entry : entries) {
                detach(roots, entry);
            }
            (parent == null ? roots : parent.children).addAll(index, entries);
            Page.report(parent == null ? Text.ADDED_TOP.format(names(entries))
                    : Text.MOVED_TO.format(names(entries), parent.name.get()));
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
                Page.report(parent == null ? Text.ADDED_TOP.format(names(arriving))
                        : Text.ADDED_TO.format(names(arriving), parent.name.get()));
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

    // ------------------------------------------------------------------ tasks

    /** A card, by identity: two cards may read the same. A pinned one does not move. */
    static final class Card {
        final String text;
        final boolean pinned;

        Card(String text) {
            this(text, false);
        }

        Card(String text, boolean pinned) {
            this.text = text;
            this.pinned = pinned;
        }
    }

    static final DataFormat<Card> CARD = DataFormat.inProcess("application/x-limn-demo-card", Card.class);

    /**
     * A column of cards that are dragged out of it and dropped into it. A column given a
     * {@code feeder} takes cards only from that one, and says no to the others while they hover.
     */
    private static final class Board extends Widget<Board> {

        private static final float HEADER = 34;
        private static final float ROW = 36;
        private static final float PAD = 8;
        /** The rows a board is at least tall for; it grows when more cards arrive. */
        private static final int ROWS = 4;

        final List<Card> cards = new ArrayList<>();
        private final I18nString title;
        private final Board feeder;
        private int focusedCard;
        /** Where a drop would land, as an index among the cards, or -1 while nothing is over. */
        private int dropAt = -1;
        /** Whether something this board refuses is over it. */
        private boolean refusing;
        private boolean movedWithin;

        Board(I18nString title, Board feeder) {
            this.title = title;
            this.feeder = feeder;
            setFocusable(true);
            setAccessibleName(title);
            setAccessibleRole(limn.accessibility.Accessible.Role.LIST);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), HEADER + Math.max(ROWS, cards.size()) * ROW + PAD);
        }

        private int cardAt(float sceneX, float sceneY) {
            return (int) Math.floor((sceneToLocal(sceneX, sceneY).y() - HEADER) / ROW);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                int card = cardAt(event.x(), event.y());
                if (card >= 0 && card < cards.size()) {
                    focusedCard = card;
                    invalidate();
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.isPressed() && (event.key() == Keys.UP || event.key() == Keys.DOWN) && !cards.isEmpty()) {
                focusedCard = Math.max(0, Math.min(cards.size() - 1, focusedCard + (event.key() == Keys.UP ? -1 : 1)));
                invalidate();
                event.consume();
            }
        }

        @Override
        protected Drag onDragStart(DragStartEvent event) {
            int index = event.isKeyboard() ? focusedCard : cardAt(event.x(), event.y());
            if (index < 0 || index >= cards.size()) {
                return null;
            }
            Card card = cards.get(index);
            if (card.pinned) {
                Page.report(Text.PINNED.format(card.text));
                return null; // a pinned card does not leave its place
            }
            movedWithin = false;
            Vec2 held = sceneToLocal(event.x(), event.y());
            float holdX = event.isKeyboard() ? 0 : held.x() - PAD;
            float holdY = event.isKeyboard() ? 0 : held.y() - HEADER - index * ROW - 2;
            return Drag.of(Transfer.of(CARD, card).with(DataFormat.TEXT, card.text))
                    .allow(DropAction.MOVE, DropAction.COPY)
                    .describedAs(card.text)
                    // The card alone, on nothing: its rounded corners stay see-through wherever it goes.
                    .preview(width() - 2 * PAD, ROW - 4, holdX, holdY,
                            canvas -> paintCard(canvas, card, 0, 0, width() - 2 * PAD, false))
                    .onEnd(action -> {
                        if (action == DropAction.MOVE && !movedWithin) {
                            cards.remove(card);
                            focusedCard = Math.max(0, Math.min(focusedCard, cards.size() - 1));
                            markNeedsLayout();
                        }
                        Page.report((action == null ? Text.CARD_NOT_TAKEN
                                : action == DropAction.COPY ? Text.CARD_COPIED : Text.CARD_MOVED).format(card.text));
                        invalidate();
                    });
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (!event.offer().has(CARD)) {
                return;
            }
            if (feeder != null && event.source() != this && event.source() != feeder) {
                // Not accepted: the pointer says no, and the column says why.
                if (!refusing) {
                    refusing = true;
                    Page.report(Text.REFUSED.format(title.get()));
                    invalidate();
                }
                return;
            }
            if (event.isKeyboard()) {
                // The first time, the end of the board (or the card's own place in its own board);
                // then each arrow moves the place one card.
                if (dropAt < 0) {
                    dropAt = event.source() == this ? focusedCard : cards.size();
                }
                dropAt = Math.max(0, Math.min(cards.size(), dropAt + event.stepY()));
                event.describePlace(Text.PLACE.format(title.get(), dropAt + 1, cards.size() + 1));
            } else {
                float y = sceneToLocal(event.x(), event.y()).y() - HEADER;
                dropAt = Math.max(0, Math.min(cards.size(), Math.round(y / ROW)));
            }
            event.accept();
            invalidate();
        }

        @Override
        protected void onDragExit(DragEvent event) {
            dropAt = -1;
            refusing = false;
            invalidate();
        }

        @Override
        protected void onDrop(DropEvent event) {
            int at = dropAt;
            dropAt = -1;
            event.offer().read(CARD).thenAccept(card -> {
                int index = Math.max(0, Math.min(cards.size(), at));
                if (event.action() == DropAction.MOVE && event.source() == this) {
                    int from = cards.indexOf(card);
                    cards.remove(from);
                    index = from < index ? index - 1 : index;
                    cards.add(index, card);
                    ((Board) event.source()).movedWithin = true;
                } else {
                    cards.add(index, event.action() == DropAction.COPY ? new Card(card.text) : card);
                }
                focusedCard = index;
                markNeedsLayout();
                invalidate();
            });
        }

        /** One card, as it stands in the board and as it is dragged. */
        private void paintCard(Canvas canvas, Card card, float x, float y, float w, boolean focused) {
            Theme theme = Theme.current();
            Font font = theme.body();
            TextMetrics m = textRuler().measure("Hg", font);
            float h = ROW - 4;
            canvas.fillRoundRectWithBorder(x, y, w, h, theme.tokensFor(this).radiusMedium(), theme.surface(), 1,
                    focused ? theme.focusRing() : theme.outline());
            boolean rtl = isRightToLeft();
            float textWidth = textRuler().measure(card.text, font).width();
            float textX = rtl ? x + w - 12 - textWidth : x + 12;
            canvas.drawText(card.text, textX, y + (h - m.height()) / 2 + m.ascent(), font, theme.text());
            if (card.pinned) {
                float size = 16;
                float iconX = rtl ? x + 10 : x + w - 10 - size;
                Tabler.outline("pinned").paint(canvas, iconX, y + (h - size) / 2, size, theme.textMuted(),
                        theme.isDark());
            }
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float radius = theme.tokensFor(this).radiusMedium();
            Color border = refusing ? theme.danger() : isFocused() || dropAt >= 0 ? theme.focusRing() : theme.outline();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), radius, theme.surfaceRaised(),
                    refusing || dropAt >= 0 ? 2 : 1, border);
            Font font = theme.body();
            TextMetrics m = textRuler().measure("Hg", font);
            String header = Text.BOARD_HEADER.format(title.get(), cards.size());
            float headerWidth = textRuler().measure(header, font).width();
            boolean rtl = isRightToLeft();
            canvas.drawText(header, rtl ? width() - PAD - 4 - headerWidth : PAD + 4,
                    (HEADER - m.height()) / 2 + m.ascent(), font, theme.textMuted());
            if (refusing) {
                float size = 18;
                Tabler.outline("ban").paint(canvas, rtl ? PAD + 4 : width() - PAD - 4 - size,
                        (HEADER - size) / 2, size, theme.danger(), theme.isDark());
            }
            for (int i = 0; i < cards.size(); i++) {
                paintCard(canvas, cards.get(i), PAD, HEADER + i * ROW + 2, width() - 2 * PAD,
                        isFocused() && i == focusedCard);
            }
            if (dropAt >= 0) {
                float y = HEADER + Math.min(dropAt, cards.size()) * ROW;
                canvas.fillRect(PAD, y - 1, width() - 2 * PAD, 3, theme.focusRing());
            }
        }
    }

    // ------------------------------------------------------------ the drop zone

    /**
     * The one area that takes what is dropped: files from the file manager, or a picture from
     * this window or another program. Centred, dashed, with an icon; what came in replaces the
     * prompt.
     */
    private static final class DropZone extends Widget<DropZone> {

        private static final float HEIGHT = 150;
        private final List<String> names = new ArrayList<>();
        private Image dropped;
        private boolean over;

        DropZone() {
            setAccessibleName(Text.ATTACHMENTS);
            setAccessibleRole(limn.accessibility.Accessible.Role.GROUP);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), HEIGHT);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (event.offer().has(DataFormat.FILES) || event.offer().has(DataFormat.IMAGE)) {
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
            if (event.offer().has(DataFormat.FILES)) {
                event.offer().read(DataFormat.FILES).thenAccept(files -> {
                    names.clear();
                    dropped = null;
                    for (Path file : files) {
                        names.add(file.getFileName() != null ? file.getFileName().toString() : file.toString());
                    }
                    Page.report(Text.FILES_DROPPED.format(files.size()));
                    invalidate();
                });
            } else {
                event.offer().read(DataFormat.IMAGE).thenAccept(image -> {
                    names.clear();
                    dropped = image;
                    Page.report(Text.PICTURE_DROPPED.get());
                    invalidate();
                });
            }
            invalidate();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            Color line = over ? theme.focusRing() : theme.outline();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    over ? theme.surface() : theme.surfaceRaised());
            dashed(canvas, 1, 1, width() - 2, height() - 2, over ? 2 : 1.5f, line);
            if (dropped != null) {
                float scale = Math.min((width() - 24) / dropped.width(), (height() - 24) / dropped.height());
                float w = dropped.width() * scale;
                float h = dropped.height() * scale;
                canvas.drawImage(dropped, (width() - w) / 2, (height() - h) / 2, w, h);
                return;
            }
            Font font = theme.body();
            List<String> lines = new ArrayList<>();
            if (names.isEmpty()) {
                lines.add(Text.DROP_HERE.get());
            } else {
                int shown = Math.min(names.size(), 3);
                lines.addAll(names.subList(0, shown));
                if (names.size() > shown) {
                    lines.add(Text.MORE.format(names.size() - shown));
                }
            }
            TextMetrics m = textRuler().measure("Hg", font);
            float icon = 28;
            float lineHeight = m.height() + 4;
            float top = (height() - icon - 10 - lines.size() * lineHeight) / 2;
            Icon glyph = Tabler.outline(names.isEmpty() ? "file-upload" : "files");
            glyph.paint(canvas, (width() - icon) / 2, top, icon, over ? theme.focusRing() : theme.textMuted(),
                    theme.isDark());
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i);
                float w = textRuler().measure(text, font).width();
                canvas.drawText(text, (width() - w) / 2, top + icon + 10 + i * lineHeight + m.ascent(), font,
                        names.isEmpty() ? theme.textMuted() : theme.text());
            }
        }

        /** A rectangle drawn in dashes; the corners are left square, as the dashes meet there. */
        private static void dashed(Canvas canvas, float x, float y, float w, float h, float stroke, Color color) {
            float dash = 7;
            float gap = 5;
            for (float at = x; at < x + w; at += dash + gap) {
                float end = Math.min(at + dash, x + w);
                canvas.drawLine(at, y, end, y, stroke, color);
                canvas.drawLine(at, y + h, end, y + h, stroke, color);
            }
            for (float at = y; at < y + h; at += dash + gap) {
                float end = Math.min(at + dash, y + h);
                canvas.drawLine(x, at, x, end, stroke, color);
                canvas.drawLine(x + w, at, x + w, end, stroke, color);
            }
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
