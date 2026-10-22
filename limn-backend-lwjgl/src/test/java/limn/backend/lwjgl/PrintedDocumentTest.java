package limn.backend.lwjgl;

import limn.backend.spi.GlyphOutlines;
import limn.components.Badge;
import limn.components.Button;
import limn.components.Card;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.chart.DonutChart;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.TextRulers;
import limn.print.Document;
import limn.print.Paper;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document of real components printed with the backend's real fonts (ADR 078 §12, item 4): a
 * heading, accented Portuguese, an Arabic and a Japanese line, a card, a chart, a badge, a table of
 * a hundred orders, a header and a footer. The file it writes under {@code build/print-samples} is
 * what an oracle on the host opens to check that the text can be extracted and the pages look right.
 */
class PrintedDocumentTest {

    private record Order(int number, String customer, String status, double total) {
    }

    @Test
    void aDocumentOfRealComponentsIsWrittenWithItsText() throws Exception {
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            ShapingRuler ruler = new ShapingRuler(store);
            FontOutlines outlines = new FontOutlines(store);
            TextRulers.install(ruler);
            GlyphOutlines.install(outlines);
            try {
                byte[] pdf = write(sample(), ui);
                String text = new String(pdf, 0, 9, StandardCharsets.ISO_8859_1);
                assertEquals("%PDF-1.7\n", text);
                Path out = Path.of("build", "print-samples");
                Files.createDirectories(out);
                Files.write(out.resolve("document-sample.pdf"), pdf);
                assertTrue(pdf.length > 4_000, "a document of several pages: " + pdf.length);
            } finally {
                GlyphOutlines.uninstall(outlines);
                TextRulers.uninstall(ruler);
            }
        }
    }

    private static byte[] write(Document document, HeadlessUi ui) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<Object> done = new AtomicReference<>();
        document.writePdf(bytes).onSuccess(done::set).onFailure(done::set).start();
        ui.pumpUntil(() -> done.get() != null, 30_000_000_000L);
        if (done.get() instanceof Throwable failed) {
            throw new AssertionError(failed);
        }
        return bytes.toByteArray();
    }

    static Document sample() {
        List<Order> orders = new ArrayList<>();
        String[] names = {"Ana Conceição", "João Araújo", "Brasília Distribuição", "Mercado São José",
                "Panificadora Açaí", "Ótica Visão"};
        for (int i = 0; i < 100; i++) {
            orders.add(new Order(4500 + i, names[i % names.length], i % 3 == 0 ? "Aberto" : "Pago",
                    120.5 + i * 13.25));
        }
        Table<Order> table = Table.of(
                Column.<Order>text("Pedido", order -> String.valueOf(order.number())).width(90),
                Column.<Order>text("Cliente", Order::customer).width(260),
                Column.<Order>text("Situação", Order::status).width(110),
                Column.<Order>text("Total", order -> String.format(Locale.ROOT, "R$ %.2f", order.total())).width(120));
        table.setRows(orders);

        Card card = new Card(new Label("Entrega em até 5 dias úteis. Frete grátis acima de R$ 300,00.")
                .setWrap(true));
        card.setTitle("Condições");
        card.setSubtitle("Válidas para pedidos de outubro");

        limn.scene.layout.Row actions = new limn.scene.layout.Row();
        actions.add(new Badge("Novo"));
        actions.add(new Checkbox("Aceito os termos"));
        actions.add(new Button("Confirmar"));

        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            paragraph.append("Este relatório reúne os pedidos do mês, com a situação de cada um e o total ")
                    .append("cobrado; a ordenação segue o número do pedido. ");
        }
        return Document.of(Paper.A4)
                .title("Relatório de pedidos")
                .locale(Locale.forLanguageTag("pt-BR"))
                .header(page -> new Label("ACME Distribuição — Relatório de pedidos").setRole(Label.Role.TITLE))
                .footer(page -> new Label("Página " + page.number() + " de " + page.count()))
                .add(new Label("Pedidos de outubro").setRole(Label.Role.TITLE))
                .add(new Label(paragraph.toString().trim()).setWrap(true))
                .add(new Label("مرحبا بالعالم — العربية"))
                .add(new Label("日本語のテキスト"))
                .keepTogether(card)
                .add(actions)
                .add(DonutChart.of(List.of("Aberto", "Pago"), 34, 66))
                .add(table);
    }
}
