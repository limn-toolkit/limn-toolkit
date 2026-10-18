package limn.demo;

import limn.backend.Clipboard;
import limn.components.Button;
import limn.components.ImageView;
import limn.components.Card;
import limn.components.Label;
import limn.components.TextField;
import limn.graphics.Image;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.i18n.PluralString;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * The clipboard: a line of an invoice copied, cut and pasted, here or in another program, and
 * what the clipboard held when it was pasted, in its formats and its picture.
 * {@code --scene transfer}.
 */
final class TransferScene {

    /** Every string the page shows. */
    static final class Text {
        static final I18nString INVOICE_LINE = new I18nString("showcase.clipboard.invoiceLine", "Invoice line");
        static final I18nString INVOICE = new I18nString("showcase.clipboard.invoice", "Invoice");
        static final I18nString CUSTOMER = new I18nString("showcase.clipboard.customer", "Customer");
        static final I18nString AMOUNT = new I18nString("showcase.clipboard.amount", "Amount");
        static final I18nString DUE = new I18nString("showcase.clipboard.due", "Due");
        static final I18nString COPY = new I18nString("showcase.clipboard.copy", "Copy");
        static final I18nString CUT = new I18nString("showcase.clipboard.cut", "Cut");
        static final I18nString PASTE = new I18nString("showcase.clipboard.paste", "Paste");
        static final I18nString RECEIVED = new I18nString("showcase.clipboard.received", "Last paste");
        static final I18nString NOTHING_YET = new I18nString("showcase.clipboard.nothingYet", "Nothing pasted yet.");
        static final I18nString EMPTY = new I18nString("showcase.clipboard.empty", "The clipboard is empty.");
        static final I18nString FORMAT_TEXT = new I18nString("showcase.clipboard.formatText", "Plain text");
        static final I18nString FORMAT_HTML = new I18nString("showcase.clipboard.formatHtml", "Formatted text");
        static final I18nString FORMAT_IMAGE = new I18nString("showcase.clipboard.formatImage", "Picture");
        static final I18nString FORMAT_FILES = new I18nString("showcase.clipboard.formatFiles", "Files");
        static final I18nString FORMAT_OTHER = new I18nString("showcase.clipboard.formatOther", "Other: {0}");
        static final I18nString COPIED = new I18nString("showcase.clipboard.copied",
                "Invoice {0} copied as text, a table and a picture.");
        static final I18nString CUT_DONE = new I18nString("showcase.clipboard.cutDone", "Invoice {0} cut.");
        static final I18nString PASTED = new I18nString("showcase.clipboard.pasted", "Invoice {0} pasted.");
        static final PluralString FORMATS = PluralString.of("showcase.clipboard.formats",
                "Pasted: {0} format.", "Pasted: {0} formats.");

        private Text() {
        }
    }

    private TransferScene() {
    }

    /** Standalone {@code --scene transfer}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(24), content()));
        return scene;
    }

    static Widget<?> content() {
        TextField invoice = new TextField().setText("1042");
        TextField customer = new TextField().setText("Harbor Supply");
        TextField amount = new TextField();
        TextField due = new TextField();
        // The amount and the due date as the language writes them, as every other page writes
        // its numbers and dates. The line is copied as the fields show it, which is also what a
        // spreadsheet in the same language reads as a number: "1.250,00" is one to a spreadsheet
        // in Portuguese, where "1,250.00" was text.
        writtenInLanguage(amount, () -> NumberFormats.decimals(2).apply(1250));
        writtenInLanguage(due, () -> Formats.date(LocalDate.of(2026, 11, 14)));
        TextField[] fields = {invoice, customer, amount, due};

        Row line = new Row();
        line.gap(12).crossAlignment(Flex.CrossAlignment.END);
        line.add(new SizedBox(90, SizedBox.UNSET, Page.field(Text.INVOICE, invoice)));
        line.add(Expanded.of(Page.field(Text.CUSTOMER, customer), 1));
        line.add(new SizedBox(120, SizedBox.UNSET, Page.field(Text.AMOUNT, amount)));
        line.add(new SizedBox(120, SizedBox.UNSET, Page.field(Text.DUE, due)));

        Column formats = new Column();
        formats.gap(6).crossAlignment(Flex.CrossAlignment.START);
        formats.add(new Label(Text.NOTHING_YET).setMuted(true));
        ImageView pasted = new ImageView((Image) null).setPreferredSize(160, 80);
        pasted.setVisible(false);

        Button copy = new Button(Text.COPY).setSecondary(true);
        copy.setIcon(Tabler.outline("copy"));
        Button cut = new Button(Text.CUT).setSecondary(true);
        cut.setIcon(Tabler.outline("scissors"));
        Button paste = new Button(Text.PASTE).setSecondary(true);
        paste.setIcon(Tabler.outline("clipboard"));

        copy.onAction(() -> {
            if (copy.scene() != null) {
                copy.scene().clipboard().set(line(fields));
                Page.report(Text.COPIED.format(invoice.text()));
            }
        });
        cut.onAction(() -> {
            if (cut.scene() != null) {
                cut.scene().clipboard().set(line(fields));
                Page.report(Text.CUT_DONE.format(invoice.text()));
                for (TextField field : fields) {
                    field.setText("");
                }
            }
        });
        paste.onAction(() -> {
            if (paste.scene() == null) {
                return;
            }
            Clipboard clipboard = paste.scene().clipboard();
            Offer offer = clipboard.content();
            for (Widget<?> old : new java.util.ArrayList<>(formats.children())) {
                formats.remove(old);
            }
            pasted.setImage(null);
            pasted.setVisible(false);
            if (offer.formats().isEmpty()) {
                formats.add(new Label(Text.EMPTY).setMuted(true));
                Page.report(Text.EMPTY.get());
                return;
            }
            for (DataFormat<?> format : offer.formats()) {
                formats.add(formatRow(format));
            }
            Page.report(Text.FORMATS.format(offer.formats().size()));
            if (offer.has(DataFormat.IMAGE)) {
                offer.read(DataFormat.IMAGE).thenAccept(image -> {
                    pasted.setImage(image);
                    pasted.setVisible(image != null);
                });
            }
            if (offer.has(DataFormat.TEXT)) {
                offer.read(DataFormat.TEXT).thenAccept(text -> {
                    // A line of this form, tab-separated, fills it; any other text goes to the
                    // customer, the field text is most often pasted into.
                    String[] cells = text.strip().split("\t");
                    if (cells.length == fields.length) {
                        for (int i = 0; i < cells.length; i++) {
                            fields[i].setText(cells[i]);
                        }
                        Page.report(Text.PASTED.format(cells[0]));
                    } else if (!text.isBlank()) {
                        customer.setText(text.strip().lines().findFirst().orElse(""));
                    }
                });
            }
        });

        Column received = new Column();
        received.gap(10).crossAlignment(Flex.CrossAlignment.START);
        received.add(formats);
        received.add(pasted);

        Column page = Page.column();
        page.add(Page.firstSection(Text.INVOICE_LINE));
        Column form = new Column();
        form.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(line);
        Row buttons = Page.row(8, copy, cut, paste);
        buttons.add(Expanded.spacer(1));
        form.add(buttons);
        page.add(Page.narrow(640, form));
        page.add(Page.section(Text.RECEIVED));
        page.add(Page.narrow(640, new Card(received)));
        return page;
    }

    /**
     * Fills {@code field} with {@code value}'s answer now and again after every change of language,
     * unless somebody has changed the field since: what they typed or pasted is theirs.
     */
    private static void writtenInLanguage(TextField field, Supplier<String> value) {
        String[] written = {null};
        Page.followLanguage(() -> {
            if (written[0] == null || field.text().equals(written[0])) {
                written[0] = value.get();
                field.setText(written[0]);
            }
        });
    }

    /** One format the clipboard held, in words and with its icon. */
    private static Widget<?> formatRow(DataFormat<?> format) {
        I18nString name;
        String icon;
        if (format.equals(DataFormat.TEXT)) {
            name = Text.FORMAT_TEXT;
            icon = "file-text";
        } else if (format.equals(DataFormat.HTML)) {
            name = Text.FORMAT_HTML;
            icon = "table";
        } else if (format.equals(DataFormat.IMAGE)) {
            name = Text.FORMAT_IMAGE;
            icon = "photo";
        } else if (format.equals(DataFormat.FILES)) {
            name = Text.FORMAT_FILES;
            icon = "files";
        } else {
            name = I18nString.literal(Text.FORMAT_OTHER.format(format.name()));
            icon = "file";
        }
        return new Label(name).setIcon(Tabler.outline(icon));
    }

    /** The invoice line in three formats: tab-separated text, an HTML table, and a picture. */
    private static Transfer line(TextField[] fields) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder("<table><tr>");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                text.append('\t');
            }
            text.append(fields[i].text());
            html.append("<td>").append(escape(fields[i].text())).append("</td>");
        }
        html.append("</tr></table>");
        return Transfer.of(DataFormat.TEXT, text.toString())
                .with(DataFormat.HTML, html.toString())
                // Made only when somebody pastes it.
                .withLazy(DataFormat.IMAGE, TransferScene::picture);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
}
