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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        final List<float[]> circles = new ArrayList<>();
        Rect clip;

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

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
            circles.add(new float[] {cx, cy, radius, strokeWidth});
        }

        @Override
        public Rect clipBounds() {
            return clip;
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
    void theDeeperBulletsAreDrawnBecauseNoBundledFaceHasThem() {
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET, 0)).append("a")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET, 1)).append("b")
                .paragraph(ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET, 2)).append("c")
                .build();
        assertEquals("\u2022 a\n\u25E6 b\n\u25AA c", text.readerText(), "a reader still hears the three");
        Recorder canvas = paint(layout(text, 200), 200);
        assertEquals(List.of("\u2022", "a", "b", "c"), canvas.drawn.stream().map(Drawn::chars).toList(),
                "the first level's bullet is the face's glyph; the two deeper are not glyphs");
        // Ten-point text: level 1's text starts at 35, the gap is 4, the drawn bullet's advance 3.37.
        assertEquals(1, canvas.circles.size(), "a ring for the second level");
        float[] ring = canvas.circles.get(0);
        assertEquals(35 - 4 - 3.37f / 2, ring[0], 1e-3);
        assertEquals(20 - 3.68f, ring[1], 1e-3, "on the second line, at the bullet's height");
        assertEquals((2 - 0.45f) / 2, ring[2], 1e-3);
        Filled square = canvas.filled.get(0);
        assertEquals(52.5f - 4 - 3.37f / 2 - 0.9f, square.x(), 1e-3, "a square for the third level");
        assertEquals(1.8f, square.width(), 1e-3);
        assertEquals(32 - 3.68f - 0.9f, square.y(), 1e-3);
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

    // ------------------------------------------------------------------ found by the review

    /** The fixed ruler, counting the characters it is asked to shape and keeping what it was given. */
    private static final class CountingRuler implements TextRuler {
        int shaped;
        final List<String> texts = new ArrayList<>();
        TextDecorations decorations;

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            return RULER.shape(text, font, base);
        }

        @Override
        public ShapedText shapeRun(String paragraph, int start, int end, Font font,
                                   ShapedText.Direction direction) {
            shaped += end - start;
            texts.add(paragraph.substring(start, end));
            return RULER.shapeRun(paragraph, start, end, font, direction);
        }

        @Override
        public TextDecorations decorations(Font font) {
            return decorations != null ? decorations : RULER.decorations(font);
        }
    }

    @Test
    void aLineThatFittedWhereItWasMeasuredFitsWhenLaidOutThere() {
        // A text whose width uses every bit (tracking of an odd fraction of an em after each of
        // five letters) and an indent whose sum with it loses the last one: (s + w) - s < w.
        float indent = 0;
        float tracking = 0;
        search:
        for (int t = 1; t < 400; t++) {
            float ems = t * 0.000731f;
            float w = 50 + 5 * (ems * 10);
            for (float s = 0.5f; s < 40; s += 0.5f) {
                if ((s + w) - s < w) {
                    indent = s;
                    tracking = ems;
                    break search;
                }
            }
        }
        assertTrue(indent > 0, "the fixture: a text and an indent the float arithmetic rounds");
        StyledText text = StyledText.builder().paragraph(ParagraphStyle.of().indent(indent, 0))
                .append("abcde", TextStyle.of().tracking(tracking)).build();
        float natural = StyledLayout.of(text, BASE, StyledLayout.UNBOUNDED, 1, true, RULER).width();
        StyledLayout atIt = StyledLayout.of(text, BASE, natural, 1, true, RULER);
        assertFalse(atIt.isTruncated(), "laid out at its own measure, nothing is cut");
        assertEquals(1, StyledLayout.of(text, BASE, natural, Integer.MAX_VALUE, true, RULER).lineCount());
    }

    @Test
    void aParagraphOfManyColoursInOneFontIsShapedOnce() {
        StyledText.Builder builder = StyledText.builder();
        Color[] inks = {INK, RED, new Color(0, 0, 1, 1)};
        for (int i = 0; i < 300; i++) {
            builder.append("tok" + i + " ", TextStyle.of().ink(inks[i % 3]));
        }
        StyledText text = builder.build();
        CountingRuler ruler = new CountingRuler();
        StyledLayout.of(text, BASE, StyledLayout.UNBOUNDED, 1, false, ruler);
        assertEquals(text.text().length(), ruler.shaped, "each character once, not once per colour after it");
    }

    @Test
    void aLineSeparatorIsNeverAskedOfTheShaper() {
        CountingRuler ruler = new CountingRuler();
        StyledLayout laid = StyledLayout.of(StyledText.of("one\u2028two\u2028\u2028three"), BASE,
                StyledLayout.UNBOUNDED, Integer.MAX_VALUE, false, ruler);
        assertEquals(4, laid.lineCount(), "the breaks still break, the empty line included");
        for (String shaped : ruler.texts) {
            assertEquals(-1, shaped.indexOf('\u2028'), "no face has the character: " + ruler.texts);
        }
    }

    @Test
    void aJustifiedUnderlineRunsUnbrokenAcrossTheWidenedSpaces() {
        StyledText text = StyledText.builder()
                .paragraph(ParagraphStyle.of().alignment(ParagraphStyle.Alignment.JUSTIFY))
                .append("aa bb cc dd ee ff", TextStyle.of().underline()).build();
        StyledLayout laid = StyledLayout.of(text, BASE, 100, Integer.MAX_VALUE, false, RULER);
        assertTrue(laid.lineCount() > 1, "the fixture: a justified first line");
        Recorder canvas = paint(laid, 100);
        float lineTop = RULER.decorations(Font.of(10)).underlineOffset() + laid.firstBaseline();
        List<Filled> first = canvas.filled.stream().filter(f -> Math.abs(f.y() - lineTop) < 1e-3).toList();
        assertTrue(first.size() > 1, "pieces of the first line's underline: " + canvas.filled);
        for (int i = 1; i < first.size(); i++) {
            assertEquals(first.get(i - 1).x() + first.get(i - 1).width(), first.get(i).x(), 1e-3,
                    "each piece starts where the one before ended");
        }
        assertEquals(100, first.get(first.size() - 1).x() + first.get(first.size() - 1).width(), 1e-3,
                "and the line runs to the justified edge");
    }

    @Test
    void aDottedOrWavyUnderlineFarAlongALineEnds() {
        for (TextStyle.Underline kind : List.of(TextStyle.Underline.DOTTED, TextStyle.Underline.WAVY)) {
            StyledText text = StyledText.builder().paragraph(ParagraphStyle.of().indent(4e7f, 0))
                    .append("far", TextStyle.of().underline(kind)).build();
            StyledLayout laid = StyledLayout.of(text, BASE, StyledLayout.UNBOUNDED, 1, false, RULER);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                    () -> paint(laid, laid.width()), kind + ": a step a float cannot add must not hang");
        }
    }

    @Test
    void aFaceThatAnswersNoNumbersForItsRaisedTextStillLaysOut() {
        CountingRuler ruler = new CountingRuler();
        ruler.decorations = new TextDecorations(1, 1, 3, 1, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NaN, Float.NaN);
        StyledText text = StyledText.builder().append("x").append("2", TextStyle.of().superscript())
                .append("y", TextStyle.of().subscript()).build();
        StyledLayout laid = StyledLayout.of(text, BASE, StyledLayout.UNBOUNDED, 1, false, ruler);
        assertTrue(Float.isFinite(laid.height()) && laid.height() > 0);
    }

    @Test
    void scalesThatMultiplyPastAnyFontStillLayOut() {
        StyledText huge = StyledText.builder()
                .paragraph(ParagraphStyle.of().text(TextStyle.of().scale(1e20f)).lineSpacing(1e30f))
                .append("a", TextStyle.of().scale(1e20f)).build();
        StyledLayout laid = StyledLayout.of(huge, BASE, StyledLayout.UNBOUNDED, 1, false, RULER);
        assertTrue(Float.isFinite(laid.height()), "a line's height stays a number");
        StyledText tiny = StyledText.builder().paragraph(ParagraphStyle.of().text(TextStyle.of().scale(1e-30f)))
                .append("a", TextStyle.of().scale(1e-30f)).build();
        StyledLayout.of(tiny, BASE, StyledLayout.UNBOUNDED, 1, false, RULER);
    }

    @Test
    void onlyTheLinesTheClipShowsArePainted() {
        StyledText text = StyledText.of("l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9");
        StyledLayout laid = StyledLayout.of(text, BASE, StyledLayout.UNBOUNDED, Integer.MAX_VALUE, false, RULER);
        Recorder canvas = new Recorder();
        canvas.clip = new Rect(0, 30, 100, 20); // lines of 12: the third, fourth and fifth show
        laid.paint(canvas, 0, 0, 100);
        assertEquals(List.of("l2", "l3", "l4"), canvas.drawn.stream().map(Drawn::chars).toList());
    }

    @Test
    void aWidthBelowZeroIsRefusedAndZeroBreaksAfterEveryCluster() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StyledLayout.of(StyledText.of("ab"), BASE, -1, 1, false, RULER));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StyledLayout.of(StyledText.of("ab"), BASE, Float.NaN, 1, false, RULER));
        assertEquals(3, StyledLayout.of(StyledText.of("abc"), BASE, 0, Integer.MAX_VALUE, false, RULER)
                .lineCount(), "a box of no width: a cluster a line, as a label breaks one");
    }

    @Test
    void anItemsOwnNumberIsItsAloneAndZeroCountsOn() {
        ParagraphStyle three = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER).listNumber(3);
        assertEquals("3. a\n4. b", StyledText.builder().paragraph(three).append("a").paragraph().append("b")
                .build().readerText(), "paragraph() repeats the style and counts on");
        assertEquals("3. a\n4. b", StyledText.builder().paragraph(three).append("a\nb").build().readerText(),
                "a line break in a piece counts on");
        ParagraphStyle nested = three.list(ParagraphStyle.ListMarker.NUMBER, 1).listNumber(0);
        assertEquals("3. a\na. x\nb. y", StyledText.builder().paragraph(three).append("a")
                .paragraph(nested).append("x").paragraph(nested).append("y").build().readerText(),
                "a style derived from a numbered one, its number taken off");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> three.listNumber(-1));
        ParagraphStyle top = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER)
                .listNumber(ParagraphStyle.MAX_LIST_NUMBER);
        assertEquals(ParagraphStyle.MAX_LIST_NUMBER + ". a\n" + ParagraphStyle.MAX_LIST_NUMBER + ". b",
                StyledText.builder().paragraph(top).append("a").paragraph().append("b").build().readerText(),
                "the count stays at the largest number, never wraps negative");
    }

    @Test
    void aListNumberIsNeverGrouped() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.NUMBER).listNumber(1000);
        assertEquals("1000. a", StyledText.builder().paragraph(item).append("a").build().readerText());
    }

    @Test
    void aPrivateUseCharacterInAPatternIsTextAndNotAnArgument() {
        StyledText text = StyledText.of(I18nString.literal("\uE001 {0} of {1}"),
                StyledText.span("2", TextStyle.of().bold()), StyledText.span("9", TextStyle.of()));
        assertEquals("\uE001 2 of 9", text.text(), "an icon font's glyph stays where it was");
    }

    @Test
    void aStyleThatSetsNoFontLeavesTheBaseFontAsItIsAndTheLineInksPrint() {
        Font base = Font.of(10);
        assertSame(base, TextStyle.of().ink(RED).underline().fontOver(base));
        TextStyle style = TextStyle.of().bold();
        assertSame(style.fontOver(base), style.fontOver(base), "one style over one base: one font");
        assertTrue(TextStyle.of().underline(TextStyle.Underline.SINGLE, RED).toString().contains("underlineInk"));
        assertTrue(TextStyle.of().strikethrough(RED).toString().contains("strikethroughInk"));
    }

    @Test
    void boldIsAtLeastBoldSoABoldWordInABlackHeadingStaysBlack() {
        Font regular = Font.of(10);
        Font black = regular.withWeight(900);
        assertEquals(700, TextStyle.of().bold().fontOver(regular).weight());
        assertEquals(900, TextStyle.of().bold().fontOver(black).weight(), "never lighter than around it");
        assertEquals(900, TextStyle.of().bold().over(TextStyle.of().weight(900)).fontOver(regular).weight());
        assertEquals(700, TextStyle.of().bold().over(TextStyle.of().weight(300)).fontOver(black).weight());
        assertEquals(500, TextStyle.of().weight(500).fontOver(black).weight(), "a weight is exact");
        assertTrue(TextStyle.of().bold().isAtLeastBold());
        assertEquals(700, TextStyle.of().bold().weightOrZero());
        assertFalse(TextStyle.of().bold().equals(TextStyle.of().weight(700)), "two different requests");
    }

    @Test
    void textsPutTogetherKeepTheFirstParagraphsStyle() {
        ParagraphStyle item = ParagraphStyle.of().list(ParagraphStyle.ListMarker.BULLET);
        StyledText section = StyledText.builder().paragraph(item).append("one").paragraph().append("two").build();
        StyledText joined = StyledText.builder().append(section).build();
        assertEquals(section, joined, "into an empty builder, nothing of it is lost");
        assertEquals("\u2022 one\n\u2022 two", joined.readerText());
        StyledText after = StyledText.builder().append("Intro: ").append(section).build();
        assertEquals("Intro: one\n\u2022 two", after.readerText(),
                "after text, its first paragraph still continues the open one");
    }

    @Test
    void theBuilderLaysOutAsItsDefaultsSayAndTakesEachLimit() {
        StyledText text = StyledText.of("aaa bbb ccc");
        StyledLayout whole = StyledLayout.builder(text, Font.of(10), INK).build(RULER);
        assertEquals(1, whole.lineCount(), "no width limit by default");
        assertEquals(110, whole.width(), 1e-3);
        StyledLayout broken = StyledLayout.builder(text, Font.of(10), INK).maxWidth(75).build(RULER);
        assertEquals(2, broken.lineCount());
        StyledLayout cut = StyledLayout.builder(text, Font.of(10), INK).maxWidth(75).maxLines(1).build(RULER);
        assertTrue(cut.isTruncated());
        assertEquals("\u2026", paint(cut, 75).drawn.get(1).chars(), "an ellipsis by default");
        StyledLayout clipped = StyledLayout.builder(text, Font.of(10), INK).maxWidth(75).maxLines(1)
                .ellipsis(false).build(RULER);
        assertEquals(1, paint(clipped, 75).drawn.size(), "left whole for a clip to cut");
        assertThrows(IllegalArgumentException.class,
                () -> StyledLayout.builder(text, Font.of(10), INK).maxWidth(-1));
        assertThrows(IllegalArgumentException.class,
                () -> StyledLayout.builder(text, Font.of(10), INK).maxLines(0));
    }

    @Test
    void theBuildersDirectionIsWhatANeutralParagraphReadsAndWhereItsStartIs() {
        StyledText digits = StyledText.of("123");
        StyledLayout ltr = StyledLayout.builder(digits, Font.of(10), INK).maxWidth(100).build(RULER);
        StyledLayout rtl = StyledLayout.builder(digits, Font.of(10), INK).maxWidth(100)
                .direction(ShapedText.Direction.RTL).build(RULER);
        assertEquals(0, paint(ltr, 100).drawn.get(0).x(), 1e-3, "start is the left");
        assertEquals(70, paint(rtl, 100).drawn.get(0).x(), 1e-3, "start is the right");
        StyledLayout centred = StyledLayout.builder(digits, Font.of(10), INK).maxWidth(100)
                .alignment(ParagraphStyle.Alignment.CENTER).build(RULER);
        assertEquals(35, paint(centred, 100).drawn.get(0).x(), 1e-3);
    }

    @Test
    void aCanvasDrawsALayoutAtTheTopLeftOfItsBox() {
        StyledText text = StyledText.of("abc");
        Recorder canvas = new Recorder();
        StyledLayout unbounded = StyledLayout.builder(text, Font.of(10), INK)
                .alignment(ParagraphStyle.Alignment.END).build(RULER);
        canvas.drawText(unbounded, 5, 7);
        assertEquals(5, canvas.drawn.get(0).x(), 1e-3, "without a limit the box is the widest line");
        assertEquals(7 + unbounded.firstBaseline(), canvas.drawn.get(0).y(), 1e-3);

        Recorder boxed = new Recorder();
        boxed.drawText(StyledLayout.builder(text, Font.of(10), INK).maxWidth(100)
                .alignment(ParagraphStyle.Alignment.CENTER).build(RULER), 5, 7);
        assertEquals(5 + 35, boxed.drawn.get(0).x(), 1e-3, "aligned in the width it was laid out at");
    }

    @Test
    void aCanvasLaysStyledTextOutAndDrawsItInOneCallWithTheInstalledRuler() {
        StyledText text = StyledText.builder().append("aaa ").append("bbb", TextStyle.of().ink(RED)).build();
        Recorder canvas = new Recorder();
        TextRulers.install(RULER);
        try {
            canvas.drawText(text, 10, 20, 35, Font.of(10), INK);
        } finally {
            TextRulers.uninstall(RULER);
        }
        assertEquals("aaa", canvas.drawn.get(0).chars(), "the space at the break is not drawn");
        assertEquals(INK, canvas.drawn.get(0).paint());
        assertEquals("bbb", canvas.drawn.get(1).chars(), "broken at the width");
        assertEquals(RED, canvas.drawn.get(1).paint());
        assertEquals(20 + 8, canvas.drawn.get(0).y(), 1e-3, "the first baseline below the top");
        assertEquals(20 + 12 + 8, canvas.drawn.get(1).y(), 1e-3, "the second line under it");
    }
}
