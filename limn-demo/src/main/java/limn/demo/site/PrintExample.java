package limn.demo.site;

import limn.backend.FileDialogs;
import limn.backend.NativeWindow;
import limn.components.Label;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.print.Document;
import limn.print.Paper;
import limn.print.PrintPreview;
import limn.scene.Widget;

import java.nio.file.Path;
import java.util.Currency;
import java.util.List;

/**
 * The worked examples of the printing guide: an order as a document, written as PDF, printed, and
 * shown in a preview, and a PDF the application already has printed. Every region marked here is a
 * code block on that page, and this file is compiled by {@code ./gradlew check}, so the sample a
 * reader copies builds.
 */
public final class PrintExample {

    private PrintExample() {
    }

    /** One line of an order. */
    public record Line(String item, int quantity, double price) {
    }

    // #region guide:print-document
    static Document order(List<Line> lines, double total) {
        Table<Line> items = Table.of(
                Column.<Line>text("Item", Line::item).width(300),
                Column.<Line>text("Quantity", line -> String.valueOf(line.quantity())).width(100),
                Column.<Line>currency("Price", Line::price, Currency.getInstance("EUR")).width(120));
        items.setRows(lines);

        return Document.of(Paper.A4)
                .marginsMm(18, 15, 18, 15)
                .title("Order 4512")
                .header(page -> new Label("ACME Distribution").setStrong(true))
                .footer(page -> new Label("Page " + page.number() + " of " + page.count()).setMuted(true))
                .add(new Label("Customer: Mercado São José"))
                .add(items)                                           // cut between rows
                .keepTogether(new Label("Total € " + total).setStrong(true));
    }
    // #endregion

    // #region guide:print-save
    static void saveAsPdf(NativeWindow window, Document order) {
        window.fileDialogs()
                .saveFile("Save the order", Path.of("Order 4512.pdf"), FileDialogs.Filter.of("PDF", "*.pdf"))
                .thenAccept(chosen -> chosen.ifPresent(file -> order.writePdf(file)
                        .onSuccess(saved -> window.launcher().showInFolder(saved))
                        .onFailure(error -> System.err.println("not saved: " + error))
                        .start()));
    }
    // #endregion

    // #region guide:print-print
    static void print(NativeWindow window, Document order, Label status) {
        order.print(window).thenAccept(result -> status.setText(switch (result) {
            case PRINTED -> "Sent to the printer.";
            case CANCELLED -> "Printing cancelled.";
            case UNAVAILABLE -> "There is no printing on this desktop.";
            default -> "The system refused the job.";
        }));
    }
    // #endregion

    // #region guide:print-preview
    static Widget<?> preview(Document order) {
        return new PrintPreview(order).setZoom(PrintPreview.Zoom.FIT_WIDTH);
    }
    // #endregion

    // #region guide:print-received
    static void printReceived(NativeWindow window, Path invoice) {
        if (window.printer().isAvailable()) {
            window.printer().print(invoice, "Invoice 0091");
        }
    }
    // #endregion
}
