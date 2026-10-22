package limn.print;

import limn.backend.spi.GlyphOutlines;
import limn.components.Label;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextRulers;
import limn.print.internal.Outlines;
import limn.print.internal.Recording;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document cut into pages (ADR 078 §6): every row of a table printed once, its header on every
 * page; a paragraph cut between its lines; a block kept together moved whole; a page break; the
 * footer told the page's number and the count.
 */
class DocumentTest {

    private HeadlessUi ui;

    /** Each character drawn as its own square glyph, so a run's text reaches the page. */
    private static final GlyphOutlines LETTERS = new GlyphOutlines() {
        @Override
        public int unitsPerEm(int faceId) {
            return faceId == 7 ? 1000 : 0;
        }

        @Override
        public String faceName(int faceId) {
            return "Letters";
        }

        @Override
        public boolean isSyntheticBold(int faceId) {
            return false;
        }

        @Override
        public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
            out.moveTo(0, 0);
            out.lineTo(500, 0);
            out.lineTo(500, 700);
            out.close();
            return faceId == 7;
        }

        @Override
        public List<Piece> cluster(String characters, Font font) {
            List<Piece> pieces = new ArrayList<>();
            characters.codePoints().forEach(cp -> pieces.add(new Glyph(7, cp, 0.6f)));
            return pieces;
        }
    };

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
        TextRulers.install(TestRulers.FIXED);
        Outlines.install(LETTERS);
    }

    @AfterEach
    void stop() {
        Outlines.uninstall(LETTERS);
        TextRulers.uninstall(TestRulers.FIXED);
        ui.close();
    }

    /** A line of text a page drew, and where. */
    private record Drawn(int page, String text, float y) {
    }

    /** Every line of text each page draws, with its baseline on the page. */
    private static List<Drawn> texts(List<Recording> pages) {
        List<Drawn> drawn = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            int page = i + 1;
            pages.get(i).replay(new NoopCanvas(1000, 1000) {
                private final List<float[]> stack = new ArrayList<>();
                private float dy;

                @Override
                public void save() {
                    super.save();
                    stack.add(new float[] {dy});
                }

                @Override
                public void restore() {
                    super.restore();
                    if (!stack.isEmpty()) {
                        dy = stack.remove(stack.size() - 1)[0];
                    }
                }

                @Override
                public void translate(float x, float y) {
                    dy += y;
                }

                @Override
                public void drawText(ShapedText text, float x, float y, Paint paint) {
                    drawn.add(new Drawn(page, text.text(), dy + y));
                }
            });
        }
        return drawn;
    }

    private record Order(int number, String customer) {
    }

    @Test
    void aTableIsCutBetweenRowsAndItsHeaderIsOnEveryPage() {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            orders.add(new Order(i, "Customer " + i));
        }
        Table<Order> table = Table.of(
                Column.<Order>text("Number", order -> "N" + order.number()).width(120),
                Column.<Order>text("Customer", Order::customer).width(300));
        table.setRows(orders);
        Document document = Document.of(Paper.A4).add(new Label("Orders")).add(table);
        List<Recording> pages = document.pages();
        assertTrue(pages.size() >= 2, "120 rows do not fit one page: " + pages.size());
        List<Drawn> drawn = texts(pages);
        for (int i = 0; i < 120; i++) {
            String number = "N" + i;
            long times = drawn.stream().filter(d -> d.text().equals(number)).count();
            assertEquals(1, times, number + " is printed once");
        }
        for (int page = 1; page <= pages.size(); page++) {
            int p = page;
            assertTrue(drawn.stream().anyMatch(d -> d.page() == p && d.text().equals("Customer")),
                    "the header is on page " + page);
            float pageH = Paper.A4.height() * Pages.UNITS_PER_POINT;
            assertTrue(drawn.stream().filter(d -> d.page() == p).allMatch(d -> d.y() > 0 && d.y() < pageH),
                    "everything on page " + page + " is on the paper");
        }
    }

    @Test
    void aParagraphIsCutBetweenItsLines() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            words.append("word").append(i).append(' ');
        }
        Label paragraph = new Label(words.toString().trim()).setWrap(true);
        Document document = Document.of(Paper.A5).add(paragraph);
        List<Recording> pages = document.pages();
        assertTrue(pages.size() >= 2, "the paragraph runs to several pages");
        List<Drawn> drawn = texts(pages);
        StringBuilder printed = new StringBuilder();
        for (Drawn line : drawn) {
            printed.append(line.text()).append(' ');
        }
        assertEquals(words.toString().trim().replaceAll("\\s+", " "),
                printed.toString().trim().replaceAll("\\s+", " "), "every line once, none cut in half");
    }

    @Test
    void aBlockKeptTogetherMovesWholeAndAPageBreakEndsThePage() {
        Document document = Document.of(Paper.A6)
                .add(new Label("First"))
                .pageBreak()
                .add(new Label("Second"));
        List<Drawn> drawn = texts(document.pages());
        assertEquals(1, drawn.stream().filter(d -> d.text().equals("First")).findFirst().orElseThrow().page());
        assertEquals(2, drawn.stream().filter(d -> d.text().equals("Second")).findFirst().orElseThrow().page());
    }

    @Test
    void theFooterKnowsThePageAndTheCount() {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            orders.add(new Order(i, "Customer " + i));
        }
        Table<Order> table = Table.of(Column.<Order>text("Customer", Order::customer).width(300));
        table.setRows(orders);
        Document document = Document.of(Paper.A5)
                .footer(page -> new Label(page.number() + " / " + page.count()))
                .add(table);
        List<Recording> pages = document.pages();
        List<Drawn> drawn = texts(pages);
        for (int page = 1; page <= pages.size(); page++) {
            String expected = page + " / " + pages.size();
            int p = page;
            assertTrue(drawn.stream().anyMatch(d -> d.page() == p && d.text().equals(expected)),
                    "page " + page + " says " + expected);
        }
    }

    @Test
    void writesAPdfFile() throws Exception {
        Path file = Files.createTempFile("limn-document", ".pdf");
        try {
            Document document = Document.of(Paper.A4).title("Sample").add(new Label("Hello"));
            AtomicReference<Path> saved = new AtomicReference<>();
            document.writePdf(file).onSuccess(saved::set).start();
            ui.pumpUntil(() -> saved.get() != null, 10_000_000_000L);
            byte[] bytes = Files.readAllBytes(file);
            String head = new String(bytes, 0, 9, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertEquals("%PDF-1.7\n", head);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
