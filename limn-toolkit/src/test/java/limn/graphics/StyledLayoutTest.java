package limn.graphics;

import java.util.ArrayList;
import java.util.List;
import limn.i18n.I18nString;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Styled text laid out and painted against the fixed ruler (ten points per character, ascent 8,
 * descent 2, line 12), so every width, line and position is an exact number.
 */
class StyledLayoutTest {

    private static final TextRuler RULER = TestRulers.FIXED;
    private static final Color INK = new Color(0, 0, 0, 1);
    private static final Color RED = new Color(1, 0, 0, 1);
    private static final StyledLayout.Base BASE = new StyledLayout.Base(Font.of(10), INK,
            ParagraphStyle.Alignment.START, ShapedText.Direction.LTR);

    /** One call that drew text: the shaped run, its range, where, in what. */
    private record Drawn(ShapedText text, int start, int end, float x, float y, Paint paint) {
        String chars() {
            return text.text().substring(start, end);
        }
    }

    private record Filled(float x, float y, float width, float height, Paint paint) {
    }

    private static final class Recorder extends NoopCanvas {
        final List<Drawn> drawn = new ArrayList<>();
        final List<Filled> filled = new ArrayList<>();
        final List<float[]> lines = new ArrayList<>();

        Recorder() {
            super(1000, 1000);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            drawn.add(new Drawn(text, 0, text.text().length(), x, y, paint));
        }

        @Override
        public void drawText(ShapedText text, int charStart, int charEnd, float x, float y, Paint paint) {
            drawn.add(new Drawn(text, charStart, charEnd, x, y, paint));
        }

        @Override
        public void fillRect(float x, float y, float width, float height, Paint paint) {
            filled.add(new Filled(x, y, width, height, paint));
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            lines.add(new float[] {x1, y1, x2, y2});
        }
    }

    private static StyledLayout layout(StyledText text, float width) {
        return StyledLayout.of(text, BASE, width, Integer.MAX_VALUE, true, RULER);
    }

    private static Recorder paint(StyledLayout layout, float boxWidth) {
        Recorder canvas = new Recorder();
        layout.paint(canvas, 0, 0, boxWidth);
        return canvas;
    }

    @Test
    void oneLineMeasuresAsItsCharacters() {
        StyledLayout one = layout(StyledText.of("abcdef"), StyledLayout.UNBOUNDED);
        assertEquals(60, one.width());
        assertEquals(12, one.height());
        assertEquals(1, one.lineCount());
        assertEquals(8, one.firstBaseline());
        assertFalse(one.isTruncated());
    }

    @Test
    void linesBreakAtTheLanguagesOpportunities() {
        StyledLayout two = layout(StyledText.of("aaa bbb ccc"), 75);
        assertEquals(2, two.lineCount());
        assertEquals(24, two.height());
        assertArrayEquals(new float[] {12}, two.lineCuts());
        List<String> shown = paint(two, 75).drawn.stream().map(Drawn::chars).toList();
        assertEquals(List.of("aaa bbb", "ccc"), shown);
    }

    @Test
    void aColourChangedInsideAWordPaintsOneShapingInThreeRanges() {
        StyledText text = StyledText.builder().append("ab").append("cd", TextStyle.of().ink(RED))
                .append("ef").build();
        Recorder canvas = paint(layout(text, StyledLayout.UNBOUNDED), 100);
        assertEquals(3, canvas.drawn.size());
        ShapedText shaping = canvas.drawn.get(0).text();
        for (Drawn d : canvas.drawn) {
            assertSame(shaping, d.text(), "one shaping for the whole word");
        }
        assertEquals("abcdef", shaping.text());
        assertEquals(RED, canvas.drawn.get(1).paint());
        assertEquals(INK, canvas.drawn.get(0).paint());
        assertEquals("cd", canvas.drawn.get(1).chars());
    }

    @Test
    void aWeightChangeIsANewShapingAndTheLineGoesOnFromIt() {
        StyledText text = StyledText.builder().append("ab").append("cd", TextStyle.of().bold()).build();
        Recorder canvas = paint(layout(text, StyledLayout.UNBOUNDED), 100);
        assertEquals(2, canvas.drawn.size());
        assertNotSame(canvas.drawn.get(0).text(), canvas.drawn.get(1).text());
        assertEquals(700, canvas.drawn.get(1).text().font().weight());
        // The second shaping starts where the first ends on the line.
        assertEquals(20, canvas.drawn.get(1).x(), 1e-3);
    }

    @Test
    void aCutLastLineEndsWithAnEllipsisThatFits() {
        StyledLayout cut = StyledLayout.of(StyledText.of("aaa bbb ccc"), BASE, 75, 1, true, RULER);
        assertEquals(1, cut.lineCount());
        assertTrue(cut.isTruncated());
        assertEquals(70, cut.width(), "\"aaa bb\" and the ellipsis");
        List<String> shown = paint(cut, 75).drawn.stream().map(Drawn::chars).toList();
        assertEquals(List.of("aaa bb", TextRuler.ELLIPSIS), shown);
    }

    @Test
    void aLimitThatLeavesParagraphsOutIsATruncation() {
        StyledText two = StyledText.builder().append("one").paragraph().append("two").build();
        StyledLayout first = StyledLayout.of(two, BASE, 200, 1, true, RULER);
        assertEquals(1, first.lineCount());
        assertTrue(first.isTruncated());
        assertEquals(List.of("one", TextRuler.ELLIPSIS),
                paint(first, 200).drawn.stream().map(Drawn::chars).toList());
    }

    @Test
    void alignmentPlacesALineInTheBox() {
        float[] xs = new float[3];
        ParagraphStyle.Alignment[] kinds = {ParagraphStyle.Alignment.START, ParagraphStyle.Alignment.CENTER,
                ParagraphStyle.Alignment.END};
        for (int i = 0; i < kinds.length; i++) {
            StyledText text = StyledText.builder().paragraph(ParagraphStyle.of().alignment(kinds[i]))
                    .append("abc").build();
            xs[i] = paint(layout(text, 100), 100).drawn.get(0).x();
        }
        assertArrayEquals(new float[] {0, 35, 70}, xs, 1e-3f);
    }

    @Test
    void aParagraphsOwnStartIsItsDirectionsAndTheBasesIsTheInterfaces() {
        StyledText own = StyledText.builder()
                .paragraph(ParagraphStyle.of().alignment(ParagraphStyle.Alignment.START)).append("אבג").build();
        assertEquals(70, paint(layout(own, 100), 100).drawn.get(0).x(), 1e-3);
        // The base's START, in a left-to-right interface: the left edge, as a plain label has it.
        assertEquals(0, paint(layout(StyledText.of("אבג"), 100), 100).drawn.get(0).x(), 1e-3);
        StyledLayout.Base rtl = new StyledLayout.Base(Font.of(10), INK, ParagraphStyle.Alignment.START,
                ShapedText.Direction.RTL);
        StyledLayout latin = StyledLayout.of(StyledText.of("abc"), rtl, 100, Integer.MAX_VALUE, true, RULER);
        assertEquals(70, paint(latin, 100).drawn.get(0).x(), 1e-3);
    }

    @Test
    void aJustifiedLineSpreadsAtItsSpacesAndItsLastLineDoesNot() {
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().alignment(ParagraphStyle.Alignment.JUSTIFY))
                .append("aa bb cc").build();
        Recorder canvas = paint(layout(text, 75), 75);
        // "aa bb" is 50 wide in 75: the one space takes the 25.
        Drawn bb = canvas.drawn.stream().filter(d -> d.chars().equals("bb")).findFirst().orElseThrow();
        assertEquals(30 + 25, bb.x() + bb.text().advanceTo(bb.start()), 1e-3);
        Drawn cc = canvas.drawn.stream().filter(d -> d.chars().equals("cc")).findFirst().orElseThrow();
        assertEquals(0, cc.x() + cc.text().advanceTo(cc.start()), 1e-3, "the last line starts at the start");
    }

    @Test
    void lineSpacingCentresTheExtraRoomOnTheLine() {
        StyledText text = StyledText.builder().paragraph(ParagraphStyle.of().lineSpacing(1.5f))
                .append("abc").build();
        StyledLayout spaced = layout(text, 100);
        assertEquals(18, spaced.height());
        assertEquals(11, spaced.firstBaseline());
    }

    @Test
    void spaceGoesBetweenParagraphsNotAroundThem() {
        ParagraphStyle spaced = ParagraphStyle.of().space(5, 7);
        StyledText text = StyledText.builder().paragraph(spaced).append("one")
                .paragraph(spaced).append("two").build();
        StyledLayout laid = layout(text, 100);
        assertEquals(12 + 7 + 5 + 12, laid.height());
        assertArrayEquals(new float[] {24}, laid.lineCuts());
    }

    @Test
    void anUnderlineAndAStrikeSitWhereTheFaceSays() {
        StyledText text = StyledText.of("abc", TextStyle.of().underline().strikethrough());
        Recorder canvas = paint(layout(text, 100), 100);
        TextDecorations face = TextDecorations.fallback(10);
        assertEquals(2, canvas.filled.size());
        assertEquals(8 + face.underlineOffset(), canvas.filled.get(0).y(), 1e-3);
        assertEquals(30, canvas.filled.get(0).width(), 1e-3);
        assertEquals(8 - face.strikeoutOffset(), canvas.filled.get(1).y(), 1e-3);
    }

    @Test
    void aWavyUnderlineIsDrawnAsAWave() {
        StyledText text = StyledText.of("abc", TextStyle.of().underline(TextStyle.Underline.WAVY));
        Recorder canvas = paint(layout(text, 100), 100);
        assertTrue(canvas.lines.size() > 3, "segments of a wave");
        float[] first = canvas.lines.get(0);
        float[] second = canvas.lines.get(1);
        assertTrue(first[3] < first[1] && second[3] > second[1], "up, then down");
    }

    @Test
    void aSuperscriptIsRaisedAndSmallerFromTheFace() {
        StyledText text = StyledText.builder().append("x").append("2", TextStyle.of().superscript()).build();
        Recorder canvas = paint(layout(text, 100), 100);
        TextDecorations face = TextDecorations.fallback(10);
        Drawn raised = canvas.drawn.get(1);
        assertEquals(face.superscriptSize(), raised.text().font().size(), 1e-3);
        assertEquals(canvas.drawn.get(0).y() - face.superscriptOffset(), raised.y(), 1e-3);
    }

    @Test
    void aBackgroundIsPaintedUnderTheGlyphs() {
        StyledText text = StyledText.builder().append("ab").append("cd", TextStyle.of().background(RED)).build();
        Recorder canvas = paint(layout(text, 100), 100);
        assertEquals(1, canvas.filled.size());
        Filled behind = canvas.filled.get(0);
        assertEquals(20, behind.x(), 1e-3);
        assertEquals(20, behind.width(), 1e-3);
        assertEquals(0, behind.y(), 1e-3);
        assertEquals(10, behind.height(), 1e-3);
    }

    @Test
    void anInkIsAskedOnEveryPaint() {
        Paint[] now = {RED};
        Ink changing = () -> now[0];
        StyledLayout laid = layout(StyledText.of("abc", TextStyle.of().ink(changing)), 100);
        assertEquals(RED, paint(laid, 100).drawn.get(0).paint());
        now[0] = INK;
        assertEquals(INK, paint(laid, 100).drawn.get(0).paint(), "the palette changed; the layout did not");
    }

    @Test
    void numberedParagraphsCountTogetherAndALevelCountsApart() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER);
        ParagraphStyle sub = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER, 1);
        StyledText text = StyledText.builder()
                .paragraph(item).append("first")
                .paragraph(sub).append("detail")
                .paragraph(sub).append("more")
                .paragraph(item).append("second")
                .paragraph(ParagraphStyle.of()).append("after")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET)).append("dot")
                .build();
        assertEquals("1. first\na. detail\nb. more\n2. second\nafter\n• dot", text.readerText());
        assertEquals("first\ndetail\nmore\nsecond\nafter\ndot", text.text());
    }

    @Test
    void aListMarkerHangsInTheIndentBeforeItsText() {
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)).append("abc").build();
        Recorder canvas = paint(layout(text, 200), 200);
        Drawn marker = canvas.drawn.get(0);
        Drawn body = canvas.drawn.get(1);
        assertEquals("1.", marker.chars());
        // This ruler's "1." is 20 wide, more than the 17.5 of indent less the gap of 4 (a face's
        // is under 1 em): the indent grows to 24, and the marker starts at the box's edge.
        assertEquals(24, body.x(), 1e-3);
        assertEquals(0, marker.x(), 1e-3);
        assertTrue(marker.x() + 20 <= body.x(), "the marker ends before the text starts");
    }

    @Test
    void aTranslatedPatternPutsItsStyledArgumentWhereThePlaceholderIs() {
        I18nString pattern = I18nString.literal("Total: {0}, due {1}");
        StyledText text = StyledText.of(pattern,
                StyledText.span("R$ 10", TextStyle.of().bold()),
                StyledText.span("today", TextStyle.of().ink(RED)));
        assertEquals("Total: R$ 10, due today", text.text());
        List<StyledText.Run> runs = text.paragraphs().get(0).runs();
        assertEquals(new StyledText.Run(7, 12, TextStyle.of().bold()), runs.get(1));
        assertEquals(new StyledText.Run(18, 23, TextStyle.of().ink(RED)), runs.get(3));

        // The same arguments in a sentence that puts them the other way round.
        StyledText reordered = StyledText.of(I18nString.literal("{1}: {0}"),
                StyledText.span("R$ 10", TextStyle.of().bold()),
                StyledText.span("today", TextStyle.of().ink(RED)));
        assertEquals("today: R$ 10", reordered.text());
        assertEquals(TextStyle.of().ink(RED), reordered.paragraphs().get(0).runs().get(0).style());
    }

    @Test
    void aLineBreakInAPieceIsANewParagraph() {
        StyledText text = StyledText.of("one\ntwo");
        assertEquals(2, text.paragraphs().size());
        assertEquals(2, layout(text, 200).lineCount());
    }

    @Test
    void aStyleInheritsWhatItDoesNotSet() {
        TextStyle parent = TextStyle.of().family("Serif").weight(300).ink(RED).scale(2);
        TextStyle child = TextStyle.of().bold().scale(1.5f);
        TextStyle both = child.over(parent);
        Font font = both.fontOver(Font.of(10));
        assertEquals("Serif", font.family());
        assertEquals(700, font.weight());
        assertEquals(30, font.size(), 1e-3, "scales multiply");
        assertEquals(RED, both.inkOrNull());
    }

    @Test
    void trackingAddsRoomAfterEveryCharacter() {
        StyledLayout tracked = layout(StyledText.of("abc", TextStyle.of().tracking(0.1f)), StyledLayout.UNBOUNDED);
        assertEquals(33, tracked.width(), 1e-3);
        Recorder canvas = paint(tracked, 100);
        assertEquals(3, canvas.drawn.size(), "one draw per character");
        assertEquals(11, canvas.drawn.get(1).x() + canvas.drawn.get(1).text().advanceTo(1), 1e-3);
    }

    /** Where {@code d}'s glyphs stand on the line: the left of its first box, the right of its last. */
    private static float[] extent(Drawn d) {
        float[] boxes = new float[8];
        int n = d.text().selection(d.start(), d.end(), boxes);
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            left = Math.min(left, d.x() + boxes[2 * k]);
            right = Math.max(right, d.x() + boxes[2 * k + 1]);
        }
        return new float[] {left, right};
    }

    @Test
    void aJustifiedRightToLeftLineFillsTheBoxAndNoMore() {
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().alignment(ParagraphStyle.Alignment.JUSTIFY))
                .append("אא בב גג").build();
        Recorder canvas = paint(layout(text, 75), 75);
        // "אא בב" is 50 wide in 75. Before: started 25 in and ended 25 past the right edge, the
        // room after the wrong side of its space.
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (Drawn d : canvas.drawn) {
            if (d.chars().contains("גג")) {
                continue;
            }
            float[] e = extent(d);
            left = Math.min(left, e[0]);
            right = Math.max(right, e[1]);
        }
        assertEquals(0, left, 1e-3, "the line's left end at the left edge");
        assertEquals(75, right, 1e-3, "and its right end at the right edge");
        Drawn bb = canvas.drawn.stream().filter(d -> d.chars().equals("בב")).findFirst().orElseThrow();
        assertEquals(0, extent(bb)[0], 1e-3, "the second word at the left, read last");
    }

    @Test
    void aListItemReadInTheOtherDirectionKeepsItsMarkerBesideItsText() {
        // A Hebrew item in a left-to-right interface: aligned to the left, as the base says, and
        // its bullet with it, not hung at the right edge of the box.
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET)).append("אבג").build();
        Recorder canvas = paint(layout(text, 200), 200);
        Drawn marker = canvas.drawn.get(0);
        float[] body = extent(canvas.drawn.get(1));
        float indent = ParagraphStyle.LIST_INDENT_EMS * 10;
        assertEquals(indent, body[0], 1e-3, "the text after the indent at the left");
        assertTrue(marker.x() >= 0 && marker.x() + 10 <= indent, "the bullet in that indent: " + marker.x());
    }

    @Test
    void aMarkerWiderThanTheIndentWidensEveryItemsIndentAlike() {
        StyledText.Builder builder = StyledText.builder();
        for (int i = 1; i <= 10; i++) {
            builder.paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)).append("item");
        }
        Recorder canvas = paint(layout(builder.build(), 300), 300);
        // "10." is 30 wide; the indent of 17.5 less the gap of 4 left it 13.5, so it began at -16.5.
        List<Drawn> markers = canvas.drawn.stream().filter(d -> d.chars().endsWith(".")).toList();
        List<Drawn> bodies = canvas.drawn.stream().filter(d -> d.chars().equals("item")).toList();
        for (Drawn marker : markers) {
            assertTrue(marker.x() >= -1e-3, marker.chars() + " at " + marker.x());
        }
        assertEquals(34, bodies.get(0).x(), 1e-3, "30 + the gap of 4");
        assertEquals(bodies.get(0).x(), bodies.get(9).x(), 1e-3, "the items still start together");
    }

    @Test
    void aTypedPlaceholderFormatsItsStyledArgument() {
        I18nString pattern = I18nString.literal("Total: {0,number,#,##0.00}");
        StyledText text = StyledText.of(pattern, StyledText.span(1234.5, TextStyle.of().bold()));
        String expected = pattern.format(1234.5);
        assertEquals(expected, text.text(), "before: the raw pattern, the amount lost");
        assertEquals(new StyledText.Run(7, expected.length(), TextStyle.of().bold()),
                text.paragraphs().get(0).runs().get(1));

        I18nString files = I18nString.literal("{0,choice,0#no files|1#one file|1<{0} files} left");
        assertEquals("3 files left", StyledText.of(files, StyledText.span(3, TextStyle.of().bold())).text());
        assertEquals("one file left", StyledText.of(files, StyledText.span(1, TextStyle.of().bold())).text());
    }

    @Test
    void anEmptyLineInAListIsNoItem() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER);
        StyledText text = StyledText.builder().paragraph(item).append("Keep a copy.\n")
                .paragraph(item).append("Sign it.").build();
        assertEquals("1. Keep a copy.\n\n2. Sign it.", text.readerText());
    }

    @Test
    void anAbsoluteSizeIsNotScaledByWhatItInherits() {
        TextStyle piece = TextStyle.of().size(10).over(TextStyle.of().scale(1.5f));
        assertEquals(10, piece.fontOver(Font.of(14)).size(), 1e-3);
        TextStyle scaled = TextStyle.of().size(10).scale(2).over(TextStyle.of().scale(1.5f));
        assertEquals(20, scaled.fontOver(Font.of(14)).size(), 1e-3, "its own scale still does");
    }

    @Test
    void aRightToLeftLineIsPaintedInReadingOrder() {
        StyledText text = StyledText.builder().append("אב ").append("גד", TextStyle.of().bold())
                .append(" הו").build();
        List<String> order = paint(layout(text, 200), 200).drawn.stream().map(Drawn::chars).toList();
        assertEquals(List.of("אב ", "גד", " הו"), order, "a page written from it reads in this order");
    }

    @Test
    void backgroundsCanBeResolvedApartFromTheGlyphs() {
        StyledText text = StyledText.of("abc", TextStyle.of().background(RED));
        Recorder canvas = new Recorder();
        Color fill = new Color(0.5f, 0.5f, 0.5f, 1);
        layout(text, 100).paint(canvas, 0, 0, 100, ink -> INK, ink -> fill);
        assertEquals(fill, canvas.filled.get(0).paint());
        assertEquals(INK, canvas.drawn.get(0).paint());
    }

    @Test
    void aParagraphIsReadInTheDirectionItSetsWhateverItsFirstLetter() {
        // A line of code whose first word is Hebrew: detected, it reads right to left and the
        // Latin word stands at the left; set left to right, the Hebrew stays first, at the left.
        ParagraphStyle code = ParagraphStyle.of().direction(ShapedText.Direction.LTR);
        StyledText set = StyledText.builder().paragraph(code).append("אבג abc").build();
        Drawn hebrew = paint(layout(set, 200), 200).drawn.get(0);
        assertEquals(0, extent(hebrew)[0], 1e-3, "set: the Hebrew at the left, read first");

        float[] detected = new float[] {Float.MAX_VALUE};
        for (Drawn d : paint(layout(StyledText.of("אבג abc"), 200), 200).drawn) {
            if (d.chars().contains("א")) {
                detected = extent(d);
            }
        }
        assertEquals(30, detected[0], 1e-3, "detected: right to left, the Hebrew (and the space it takes) after \"abc\"");
    }

    @Test
    void aLineSeparatorBreaksTheLineAndKeepsTheParagraph() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)
                .space(5, 7).firstLineIndent(10);
        StyledText text = StyledText.builder().paragraph(item).append("one\u2028two")
                .paragraph(item).append("three").build();
        StyledLayout laid = layout(text, 200);
        assertEquals(3, laid.lineCount());
        assertEquals(12 + 12 + 7 + 5 + 12, laid.height(), "the spacing between the two items only");
        assertEquals("1. one\ntwo\n2. three", text.readerText());
        assertEquals("one\ntwo\nthree", text.text());
        Recorder canvas = paint(laid, 200);
        assertEquals(2, canvas.drawn.stream().filter(d -> d.chars().endsWith(".")).count(), "one marker per item");
        Drawn one = canvas.drawn.stream().filter(d -> d.chars().equals("one")).findFirst().orElseThrow();
        Drawn two = canvas.drawn.stream().filter(d -> d.chars().equals("two")).findFirst().orElseThrow();
        assertEquals(10, extent(one)[0] - extent(two)[0], 1e-3, "the first-line indent on the first line only");
    }

    @Test
    void twoLineSeparatorsLeaveAnEmptyLineAndAJustifiedLineBeforeOneIsNotSpread() {
        assertEquals(3, layout(StyledText.of("a\u2028\u2028b"), 200).lineCount());
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().alignment(ParagraphStyle.Alignment.JUSTIFY))
                .append("aa bb\u2028cc dd ee ff gg").build();
        Drawn bb = paint(layout(text, 100), 100).drawn.stream().filter(d -> d.chars().contains("bb"))
                .findFirst().orElseThrow();
        assertEquals(50, extent(bb)[1], 1e-3, "\"aa bb\" ends where it ends, not at the right edge");
    }

    @Test
    void aListNumberSetsTheCountAndTheItemsAfterItGoOn() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER);
        StyledText text = StyledText.builder()
                .paragraph(item).append("a")
                .paragraph(item.listNumber(5)).append("b")
                .paragraph(item).append("c")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER, 1).listNumber(3)).append("d")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET).listNumber(9)).append("e")
                .build();
        assertEquals("1. a\n5. b\n6. c\nc. d\n• e", text.readerText());
    }
}
