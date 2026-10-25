package limn.print;

import limn.backend.spi.GlyphOutlines;
import limn.components.Label;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextRulers;
import limn.print.internal.Outlines;
import limn.print.internal.Recording;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    private static Table<Order> tableOf(int count) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(new Order(i, "Customer " + i));
        }
        Table<Order> table = Table.of(
                Column.<Order>text("Number", order -> "N" + order.number()).width(120),
                Column.<Order>text("Customer", Order::customer).width(300));
        table.setRows(orders);
        return table;
    }

    /**
     * The performance budget's rule for a document: a page of a table costs what a page costs,
     * whatever the table holds, since a page's worth of rows is realized at a time.
     */
    @Test
    void aPageOfALongTableCostsWhatAPageOfAShortOneDoes() {
        List<Recording> short_ = Document.of(Paper.A4).add(tableOf(80)).pages();
        List<Recording> long_ = Document.of(Paper.A4).add(tableOf(2000)).pages();
        assertEquals(short_.get(0).size(), long_.get(0).size(), "the first page's drawing calls");
        assertEquals(short_.get(1).size(), long_.get(1).size(), "the second page's drawing calls");
        assertTrue(long_.size() > short_.size() * 10, "the long table does run to more pages");
    }

    @Test
    void theSameDocumentIsCutForEachPaper() {
        List<Drawn> onA4 = texts(Document.of(Paper.A4).add(tableOf(150)).pages());
        List<Drawn> onLetter = texts(Document.of(Paper.LETTER).add(tableOf(150)).pages());
        for (List<Drawn> drawn : List.of(onA4, onLetter)) {
            for (int i = 0; i < 150; i++) {
                String number = "N" + i;
                assertEquals(1, drawn.stream().filter(d -> d.text().equals(number)).count(), number);
            }
        }
    }

    @Test
    void thePaperIsTheLightPaletteOfTheThemesFamily() {
        limn.components.Theme was = limn.components.Theme.current();
        try {
            limn.components.Theme.setCurrent(limn.components.Theme.dark());
            assertEquals(limn.components.Theme.light(), Document.of(Paper.A4).palette());
            limn.components.Theme.setCurrent(limn.components.Theme.limn());
            assertEquals(limn.components.Theme.limnLight(), Document.of(Paper.A4).palette());
            assertEquals(limn.components.Theme.dark(),
                    Document.of(Paper.A4).theme(limn.components.Theme.dark()).palette(), "one set is kept");
        } finally {
            limn.components.Theme.setCurrent(was);
        }
    }

    /** A leaf as tall as it is made: a letterhead, a disclaimer, a block with nowhere to cut. */
    private static final class Tall extends Widget<Tall> {
        private final float tall;

        Tall(float tall) {
            this.tall = tall;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(10, tall);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    /**
     * A header and a footer that together fill the content box: A6 on its side keeps 252.9 units
     * between its default margins, and a letterhead and a disclaimer of 130 each leave nothing.
     * Refused at once, by every door that records the pages, naming what to change; the cut went
     * on forever, an empty page a turn on the UI thread until the heap ran out. Default margins
     * on a tag smaller than they are leave nothing either, with no header at all. Run on a thread
     * of its own with a deadline, so that a regression fails instead of hanging the build.
     */
    @Test
    void aContentBoxTooSmallToLayAnythingOutIsRefusedAtOnce() {
        List<String> refusals;
        try {
            refusals = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                ui.runtime().bindToCurrentThread();
                List<String> messages = new ArrayList<>();
                Document letterhead = Document.of(Paper.A6.landscape())
                        .header(page -> new Tall(130))
                        .footer(page -> new Tall(130))
                        .add(new Label("Invoice 4512"));
                messages.add(assertThrows(IllegalStateException.class, letterhead::pageCount).getMessage());
                messages.add(assertThrows(IllegalStateException.class,
                        () -> letterhead.writePdf(new ByteArrayOutputStream())).getMessage());
                messages.add(assertThrows(IllegalStateException.class, () -> new PrintPreview(letterhead))
                        .getMessage());
                Document tag = Document.of(Paper.ofPoints("Tag", 120, 100)).add(new Label("A-17"));
                messages.add(assertThrows(IllegalStateException.class, tag::pageCount).getMessage());
                return messages;
            });
        } finally {
            ui.runtime().bindToCurrentThread();
        }
        for (String letterhead : refusals.subList(0, 3)) {
            assertTrue(letterhead.startsWith("A6 (419.5 x 297.6 pt) with margins of 54.0, 54.0, 54.0 and 54.0 pt"),
                    letterhead);
            assertTrue(letterhead.contains("a header 130.0 units tall and a footer 130.0 units tall"), letterhead);
            assertTrue(letterhead.contains("x -7.1 units") && letterhead.contains("quarter inch"), letterhead);
        }
        String tag = refusals.get(3);
        assertTrue(tag.startsWith("Tag (120.0 x 100.0 pt) with margins of 54.0, 54.0, 54.0 and 54.0 pt"), tag);
        assertTrue(tag.contains("no header and no footer") && tag.contains("16.0 x -10.7 units"), tag);
    }

    /**
     * What a header and a footer leave, however little above the quarter inch, still lays the
     * document out: a block that cannot be cut is sliced by that room, page after page, and runs
     * to as many pages as the room divides it into.
     */
    @Test
    void aBlockWithNowhereToCutIsSlicedByTheRoomTheHeaderAndFooterLeave() {
        Paper paper = Paper.A6.landscape();
        float room = (paper.height() - 2 * 54) * Pages.UNITS_PER_POINT - 2 * 114;
        assertTrue(room >= Pages.MIN_CONTENT && room < Pages.MIN_CONTENT + 1, "just above the floor: " + room);
        Document document = Document.of(paper)
                .header(page -> new Tall(114))
                .footer(page -> new Tall(114))
                .add(new Tall(300));
        assertEquals((int) Math.ceil(300 / room), document.pageCount());
    }

    /** A leaf as tall as it is made, filled in one colour, so a page shows where it is drawn. */
    private static final class Filled extends Widget<Filled> {
        private final float tall;
        private final limn.graphics.Color colour;

        Filled(float tall, limn.graphics.Color colour) {
            this.tall = tall;
            this.colour = colour;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(10, tall);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), colour);
        }
    }

    /** What of a fill a page shows, from its top to its foot on the page, under the clip it was drawn in. */
    private record Shown(int page, limn.graphics.Color colour, float top, float foot) {
    }

    /** Every fill each page shows, cut by its clip, in the page's own units. */
    private static List<Shown> shown(List<Recording> pages) {
        List<Shown> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            int page = i + 1;
            pages.get(i).replay(new NoopCanvas(1000, 1000) {
                private final List<float[]> stack = new ArrayList<>();
                private float dy;
                private float clipTop = -Float.MAX_VALUE;
                private float clipFoot = Float.MAX_VALUE;

                @Override
                public void save() {
                    super.save();
                    stack.add(new float[] {dy, clipTop, clipFoot});
                }

                @Override
                public void restore() {
                    super.restore();
                    if (!stack.isEmpty()) {
                        float[] was = stack.remove(stack.size() - 1);
                        dy = was[0];
                        clipTop = was[1];
                        clipFoot = was[2];
                    }
                }

                @Override
                public void translate(float x, float y) {
                    dy += y;
                }

                @Override
                public void clipRect(float x, float y, float w, float h) {
                    clipTop = Math.max(clipTop, dy + y);
                    clipFoot = Math.min(clipFoot, dy + y + h);
                }

                @Override
                public void fillRect(float x, float y, float w, float h, Paint paint) {
                    float top = Math.max(clipTop, dy + y);
                    float foot = Math.min(clipFoot, dy + y + h);
                    if (foot > top && paint instanceof limn.graphics.Color colour) {
                        out.add(new Shown(page, colour, top, foot));
                    }
                }
            });
        }
        return out;
    }

    /**
     * A header that grows on every pass still leaves the content under it: the last cut is made at
     * the heights the pages are drawn with. A4 keeps 978.5 units between its default margins, and a
     * block of 93,000 units with nowhere to cut runs to 97 pages under a header of 10, 98 under 20,
     * 99 under 30 and 100 under 40; this header is 10 units taller for each of those counts, so it
     * grew on each of the three passes. The passes stopped at the last measure, and the pages were
     * cut for a header of 30 and drawn with one of 40 over the top of the content. One that would
     * grow a fourth time, to 50 at 100 pages, is drawn in the 40 the last cut kept, cut at its foot.
     */
    @Test
    void aHeaderStillGrowingOnTheLastPassNeverRunsOverTheContent() {
        limn.graphics.Color headerColour = new limn.graphics.Color(1, 0, 0, 1);
        limn.graphics.Color contentColour = new limn.graphics.Color(0, 0, 1, 1);
        for (int growths : new int[] {3, 4}) {
            Document document = Document.of(Paper.A4)
                    .header(page -> new Filled(10 + 10 * Math.max(0, Math.min(growths, page.count() - 96)),
                            headerColour))
                    .add(new Filled(93_000, contentColour));
            List<Recording> pages = document.pages();
            List<Shown> fills = shown(pages);
            for (int page = 1; page <= pages.size(); page++) {
                int p = page;
                Shown header = fills.stream().filter(f -> f.page() == p && f.colour().equals(headerColour))
                        .findFirst().orElseThrow();
                for (Shown content : fills) {
                    if (content.page() == p && content.colour().equals(contentColour)) {
                        assertTrue(content.top() >= header.foot() - 0.01f, growths + " growths, page " + page
                                + ": the content begins at " + content.top() + ", under the header's foot at "
                                + header.foot());
                    }
                }
                assertEquals(40, header.foot() - header.top(), 0.01f,
                        growths + " growths: the header of page " + page + " has the room the last cut kept");
            }
            assertEquals(100, pages.size(), growths + " growths: cut for the header the pages are drawn with");
        }
    }

    /**
     * A table's last page shows no sliver of the row before it. The page lays the table out as tall
     * as its header and the rows it took, summed from the header down, and the table's rows sum
     * them again from the top of their box; with rows 32.537 units tall plus their padding, the last
     * of A5's three pages came out a hundred-thousandth taller than its rows, which read as a scroll
     * past the end: the previous page's last row was mounted above them, its stripe shown in a band
     * 0.000015 units tall at the top of the rows.
     */
    @Test
    void aTablesLastPageShowsNoSliverOfTheRowBefore() {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            orders.add(new Order(i, "Customer " + i));
        }
        Table<Order> table = Table.of(
                Column.<Order>text("Number", order -> "N" + order.number()).width(120),
                Column.<Order>widget("Box", order -> new Tall(32.537f)).width(100));
        table.setRows(orders);
        List<Recording> pages = Document.of(Paper.A5).add(table).pages();
        assertEquals(3, pages.size());
        for (Shown fill : shown(pages)) {
            assertTrue(fill.foot() - fill.top() >= 0.01f, "page " + fill.page() + " shows a fill "
                    + (fill.foot() - fill.top()) + " units tall at " + fill.top());
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
