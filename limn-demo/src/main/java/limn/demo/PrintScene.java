package limn.demo;

import limn.backend.NativeWindow;
import limn.backend.PrintResult;
import limn.components.Button;
import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.print.Document;
import limn.print.Paper;
import limn.print.PrintPreview;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Printing: an order as a document, shown in a print preview, printed through the system's own
 * dialog or saved as PDF. The paper and the zoom are the page's own choices, ahead of the dialog,
 * since the dialog scales a document to other paper and never lays it out again.
 */
final class PrintScene {

    /** Every string the page shows. */
    static final class Text {
        static final I18nString ORDER = new I18nString("showcase.print.order", "Order 4512");
        static final I18nString PRINT = new I18nString("showcase.print.print", "Print…");
        static final I18nString SAVE = new I18nString("showcase.print.save", "Save as PDF…");
        static final I18nString FIT_WIDTH = new I18nString("showcase.print.fitWidth", "Fit width");
        static final I18nString FIT_PAGE = new I18nString("showcase.print.fitPage", "Whole page");
        static final I18nString COMPANY = new I18nString("showcase.print.company",
                "ACME Distribution — order 4512");
        static final I18nString CUSTOMER = new I18nString("showcase.print.customer",
                "Customer: Mercado São José, 12 Harbour Street");
        static final I18nString PAGE_OF = new I18nString("showcase.print.pageOf", "Page {0} of {1}");
        static final I18nString ITEM = new I18nString("showcase.print.item", "Item");
        static final I18nString QUANTITY = new I18nString("showcase.print.quantity", "Quantity");
        static final I18nString PRICE = new I18nString("showcase.print.price", "Price");
        static final I18nString TOTAL = new I18nString("showcase.print.total", "Total {0}");
        static final I18nString PRINTED = new I18nString("showcase.print.printed", "Sent to the printer.");
        static final I18nString CANCELLED = new I18nString("showcase.print.cancelled", "Printing cancelled.");
        static final I18nString UNAVAILABLE = new I18nString("showcase.print.unavailable",
                "There is no printing on this desktop.");
        static final I18nString FAILED = new I18nString("showcase.print.failed", "The system refused the job.");
        static final I18nString SAVED = new I18nString("showcase.print.saved", "{0} saved.");
        static final I18nString SAVE_TITLE = new I18nString("showcase.print.saveTitle", "Save the order as PDF");
        static final I18nString PDF = new I18nString("showcase.print.pdf", "PDF documents");
        static final I18nString FILE_NAME = new I18nString("showcase.print.fileName", "Order 4512.pdf");
        static final I18nString NO_WINDOW = new I18nString("showcase.print.noWindow",
                "Printing needs a window of its own.");

        private Text() {
        }
    }

    /** The products the order lists, with their quantity and unit price. */
    private record Line(String item, int quantity, double price) {
    }

    private static final String[] PRODUCTS = {"Arabica coffee, 1 kg", "Brown sugar, 5 kg",
            "Whole milk, 12 × 1 l", "Oat flakes, 500 g", "Olive oil, 6 × 500 ml", "Basmati rice, 5 kg",
            "Black beans, 1 kg", "Cassava flour, 1 kg", "Orange juice, 12 × 1 l", "Mineral water, 24 × 500 ml"};

    private static final java.util.Currency MONEY = java.util.Currency.getInstance("USD");

    private PrintScene() {
    }

    /** The page. */
    static Widget<?> content() {
        limn.scene.layout.Column page = Page.column();
        page.add(Page.firstSection(Text.ORDER));

        Paper[] papers = {Paper.A4, Paper.LETTER};
        int[] paper = {Paper.forLocale(Locale.getDefault()) == Paper.LETTER ? 1 : 0};
        PrintPreview.Zoom[] zoom = {PrintPreview.Zoom.FIT_WIDTH};
        // The preview is made again for another paper, since a document's paper is its own.
        limn.scene.layout.Column holder = new limn.scene.layout.Column();
        holder.crossAlignment(Flex.CrossAlignment.STRETCH);
        PrintPreview[] shown = {preview(papers[paper[0]], zoom[0])};
        holder.add(Expanded.of(shown[0], 1));

        SegmentedControl paperChoice = new SegmentedControl(List.of("A4", "Letter"));
        paperChoice.setSelectedIndex(paper[0]);
        paperChoice.onSelect(index -> {
            paper[0] = index;
            holder.remove(holder.children().get(0));
            shown[0] = preview(papers[index], zoom[0]);
            holder.add(Expanded.of(shown[0], 1));
        });
        SegmentedControl zoomChoice = new SegmentedControl(Text.FIT_WIDTH, Text.FIT_PAGE);
        zoomChoice.onSelect(index -> {
            zoom[0] = index == 0 ? PrintPreview.Zoom.FIT_WIDTH : PrintPreview.Zoom.FIT_PAGE;
            shown[0].setZoom(zoom[0]);
        });

        Button print = new Button(Text.PRINT).setStyle(Button.Style.PRIMARY);
        print.setIcon(Tabler.outline("printer"));
        print.onAction(() -> window(print).ifPresent(window -> order(papers[paper[0]]).print(window)
                .thenAccept(result -> Page.report(said(result)))));
        Button save = new Button(Text.SAVE).setStyle(Button.Style.SECONDARY);
        save.setIcon(Tabler.outline("file-type-pdf"));
        save.onAction(() -> window(save).ifPresent(window -> window.fileDialogs().saveFile(Text.SAVE_TITLE.get(),
                        Path.of(Text.FILE_NAME.get()), limn.backend.FileDialogs.Filter.of(Text.PDF.get(), "*.pdf"))
                .thenAccept(chosen -> chosen.ifPresent(file -> order(papers[paper[0]]).writePdf(file)
                        .onSuccess(saved -> {
                            Page.report(Text.SAVED.format(saved.getFileName()));
                            window.launcher().showInFolder(saved);
                        })
                        .onFailure(error -> Page.report(error.getMessage()))
                        .start()))));

        Row toolbar = new Row();
        toolbar.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.CENTER);
        toolbar.add(paperChoice);
        toolbar.add(zoomChoice);
        toolbar.add(Expanded.of(new Label(""), 1));
        toolbar.add(save);
        toolbar.add(print);
        page.add(toolbar);
        page.add(Expanded.of(holder, 1));
        return new Padding(Insets.symmetric(12, 0), page);
    }

    /** A preview of the order on one paper. */
    private static PrintPreview preview(Paper paper, PrintPreview.Zoom zoom) {
        return new PrintPreview(order(paper)).setZoom(zoom);
    }

    /** The order as a document: its widgets are its own, made afresh for each preview and print. */
    static Document order(Paper paper) {
        List<Line> lines = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < 64; i++) {
            Line line = new Line(PRODUCTS[i % PRODUCTS.length], 1 + (i * 7) % 12, 4.5 + (i % 9) * 3.25);
            lines.add(line);
            total += line.quantity() * line.price();
        }
        Table<Line> items = Table.of(
                Column.<Line>text(Text.ITEM, Line::item).width(300),
                Column.<Line>text(Text.QUANTITY, line -> String.valueOf(line.quantity())).width(110),
                Column.<Line>currency(Text.PRICE, Line::price, MONEY).width(130));
        items.setRows(lines);
        Label sum = new Label(Text.TOTAL.format(limn.i18n.NumberFormats.currency(MONEY).apply(total)))
                .setStrong(true);
        return Document.of(paper)
                .marginsMm(16, 16, 16, 16)
                .title(Text.ORDER.get())
                .header(info -> new Label(Text.COMPANY).setStrong(true))
                .footer(info -> new Label(Text.PAGE_OF.format(info.number(), info.count())).setMuted(true))
                .add(new Label(Text.CUSTOMER))
                .add(items)
                .keepTogether(sum);
    }

    private static String said(PrintResult result) {
        return switch (result) {
            case PRINTED -> Text.PRINTED.get();
            case CANCELLED -> Text.CANCELLED.get();
            case UNAVAILABLE -> Text.UNAVAILABLE.get();
            default -> Text.FAILED.get();
        };
    }

    /** The window a widget is in, or none (said in the status line). */
    private static java.util.Optional<NativeWindow> window(Widget<?> from) {
        Scene scene = from.scene();
        if (scene == null || scene.window() == null) {
            Page.report(Text.NO_WINDOW.get());
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(scene.window());
    }
}
