package limn.demo;

import limn.backend.Clipboard;
import limn.components.Button;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.TextMetrics;
import limn.input.Keys;
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
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.DropAction;
import limn.transfer.Offer;
import limn.transfer.Transfer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Drag and drop and the clipboard on one screen: two boards whose cards are dragged within and
 * between them, by pointer or by keyboard; an area that takes files dragged from the file
 * manager; and a copy and a paste of text, HTML, a picture and files. {@code --scene transfer}.
 */
final class TransferScene {

    /** A card, by identity: two cards may read the same. */
    static final class Card {
        final String text;

        Card(String text) {
            this.text = text;
        }
    }

    static final DataFormat<Card> CARD = DataFormat.inProcess("application/x-limn-demo-card", Card.class);

    private TransferScene() {
    }

    /** Standalone {@code --scene transfer}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(24), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    static Widget<?> content() {
        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Drag and drop").setRole(Label.Role.TITLE));
        column.add(new Label("Drag a card to another place or to the other board.")
                .setMuted(true).setWrap(true));

        Label status = new Label("Nothing dragged yet.").setMuted(true);
        Board todo = new Board("To do", status);
        todo.cards.addAll(List.of(new Card("Invoice 1042"), new Card("Call Ana"), new Card("Renew contract"),
                new Card("Ship order 77"), new Card("Review budget")));
        Board done = new Board("Done", status);
        done.cards.addAll(List.of(new Card("Pay supplier"), new Card("Send report")));
        Row boards = new Row();
        boards.gap(16).crossAlignment(Flex.CrossAlignment.START);
        boards.add(Expanded.of(todo, 1));
        boards.add(Expanded.of(done, 1));
        boards.add(Expanded.of(new FileArea(), 1));
        column.add(boards);
        column.add(status);

        column.add(new Label("Clipboard").setRole(Label.Role.TITLE));
        Label clip = new Label("Copy here and paste in another program, or the other way round.").setMuted(true).setWrap(true);
        ImageView pasted = new ImageView((limn.graphics.Image) null).setPreferredSize(160, 100);
        Row buttons = new Row();
        buttons.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        Button copy = new Button("Copy");
        copy.onAction(() -> {
            Clipboard clipboard = copy.scene().clipboard();
            clipboard.set(Transfer.of(DataFormat.TEXT, "Invoice 1042\tR$ 1.250,00")
                    .with(DataFormat.HTML, "<table><tr><td><b>Invoice 1042</b></td><td>R$ 1.250,00</td></tr></table>")
                    .withLazy(DataFormat.IMAGE, TransferScene::picture)
                    .with(DataFormat.FILES, List.of(Path.of(System.getProperty("user.home")),
                            Path.of(System.getProperty("java.home")))));
            clip.setText("Copied: text, an HTML table, a picture (made when somebody pastes it) and two folders.");
        });
        Button paste = new Button("Paste");
        paste.onAction(() -> {
            Offer offer = paste.scene().clipboard().content();
            StringJoiner formats = new StringJoiner(", ");
            offer.formats().forEach(format -> formats.add(format.name()));
            clip.setText(offer.formats().isEmpty() ? "The clipboard is empty." : "On the clipboard: " + formats);
            pasted.setImage(null);
            if (offer.has(DataFormat.IMAGE)) {
                offer.read(DataFormat.IMAGE).thenAccept(pasted::setImage);
            }
            if (offer.has(DataFormat.FILES)) {
                offer.read(DataFormat.FILES).thenAccept(files ->
                        clip.setText(clip.text() + ". Files: " + files));
            } else if (offer.has(DataFormat.TEXT)) {
                offer.read(DataFormat.TEXT).thenAccept(text ->
                        clip.setText(clip.text() + ". Text: " + (text.length() > 80 ? text.substring(0, 80) + "…" : text)));
            }
        });
        buttons.add(copy);
        buttons.add(paste);
        column.add(buttons);
        column.add(clip);
        column.add(pasted);
        return column;
    }

    /** A picture made in code: bands of colour, so that a paste elsewhere is recognisable. */
    static Image picture() {
        int w = 240;
        int h = 120;
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int band = x * 4 / w;
                rgba[i] = (byte) (band == 0 ? 220 : band == 3 ? 240 : 40);
                rgba[i + 1] = (byte) (band == 1 ? 180 : band == 3 ? 200 : 60);
                rgba[i + 2] = (byte) (band == 2 ? 220 : 50);
                rgba[i + 3] = (byte) (y < h / 2 ? 255 : 160);
            }
        }
        return new Image(w, h, rgba);
    }

    /** A column of cards that are dragged out of it and dropped into it. */
    private static final class Board extends Widget<Board> {

        private static final float HEADER = 34;
        private static final float ROW = 32;
        private static final float PAD = 8;

        final List<Card> cards = new ArrayList<>();
        private final String title;
        private final Label status;
        private int focusedCard;
        /** Where a drop would land, as an index among the cards, or -1 while nothing is over. */
        private int dropAt = -1;
        private boolean movedWithin;

        Board(String title, Label status) {
            this.title = title;
            this.status = status;
            setFocusable(true);
            setAccessibleName(title);
            setAccessibleRole(limn.accessibility.Accessible.Role.LIST);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), HEADER + 8 * ROW + PAD);
        }

        private int indexAt(float sceneY) {
            float y = sceneY - localToSceneY() - HEADER;
            return Math.max(0, Math.min(cards.size(), Math.round(y / ROW)));
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                int card = (int) Math.floor((event.y() - localToSceneY() - HEADER) / ROW);
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
            int index = event.isKeyboard() ? focusedCard
                    : (int) Math.floor((event.y() - localToSceneY() - HEADER) / ROW);
            if (index < 0 || index >= cards.size()) {
                return null;
            }
            Card card = cards.get(index);
            movedWithin = false;
            return Drag.of(Transfer.of(CARD, card).with(DataFormat.TEXT, card.text))
                    .allow(DropAction.MOVE, DropAction.COPY)
                    .describedAs(card.text)
                    // The card alone, on nothing: its rounded corners stay see-through wherever it goes.
                    .preview(width() - 2 * PAD, ROW - 4,
                            event.isKeyboard() ? 0 : event.x() - localToSceneX() - PAD,
                            event.isKeyboard() ? 0 : event.y() - localToSceneY() - HEADER - index * ROW - 2,
                            canvas -> paintCard(canvas, card, 0, 0, width() - 2 * PAD, false))
                    .onEnd(action -> {
                        if (action == DropAction.MOVE && !movedWithin) {
                            cards.remove(card);
                            focusedCard = Math.max(0, Math.min(focusedCard, cards.size() - 1));
                        }
                        status.setText(action == null ? "\"" + card.text + "\": nothing took it."
                                : "\"" + card.text + "\": " + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
                        invalidate();
                    });
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (!event.offer().has(CARD)) {
                return;
            }
            if (event.isKeyboard()) {
                // The first time, the end of the board (or the card's own place in its own board);
                // then each arrow moves the place one card.
                if (dropAt < 0) {
                    dropAt = event.source() == this ? focusedCard : cards.size();
                }
                dropAt = Math.max(0, Math.min(cards.size(), dropAt + event.stepY()));
                event.describePlace(title + ", place " + (dropAt + 1) + " of " + (cards.size() + 1)
                        + (dropAt < cards.size() ? ", before " + cards.get(dropAt).text : ", at the end"));
            } else {
                dropAt = indexAt(event.y());
            }
            event.accept();
            invalidate();
        }

        @Override
        protected void onDragExit(DragEvent event) {
            dropAt = -1;
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
            canvas.drawText(card.text, x + 10, y + (h - m.height()) / 2 + m.ascent(), font, theme.text());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float radius = theme.tokensFor(this).radiusMedium();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), radius, theme.surfaceRaised(), 1,
                    isFocused() ? theme.focusRing() : theme.outline());
            Font font = theme.body();
            TextMetrics m = textRuler().measure("Hg", font);
            canvas.drawText(title + " (" + cards.size() + ")", PAD + 4, (HEADER - m.height()) / 2 + m.ascent(),
                    font, theme.textMuted());
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

    /** Takes files dragged in from the file manager, and says their names. */
    private static final class FileArea extends Widget<FileArea> {

        private final List<String> names = new ArrayList<>();

        FileArea() {
            setAccessibleName("Files dropped here");
            setAccessibleRole(limn.accessibility.Accessible.Role.GROUP);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 34 + 8 * 32 + 8);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (event.offer().has(DataFormat.FILES)) {
                event.accept();
            }
        }

        @Override
        protected void onDrop(DropEvent event) {
            event.offer().read(DataFormat.FILES).thenAccept(files -> {
                names.clear();
                for (Path file : files) {
                    names.add(file.getFileName() != null ? file.getFileName().toString() : file.toString());
                }
                invalidate();
            });
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    theme.surfaceRaised(), 1, theme.outline());
            Font font = theme.body();
            TextMetrics m = textRuler().measure("Hg", font);
            canvas.drawText(names.isEmpty() ? "Drop files here" : "Dropped (" + names.size() + ")",
                    12, (34 - m.height()) / 2 + m.ascent(), font, theme.textMuted());
            for (int i = 0; i < Math.min(names.size(), 8); i++) {
                canvas.drawText(names.get(i), 12, 34 + i * 32 + (32 - m.height()) / 2 + m.ascent(), font,
                        theme.text());
            }
        }
    }
}
