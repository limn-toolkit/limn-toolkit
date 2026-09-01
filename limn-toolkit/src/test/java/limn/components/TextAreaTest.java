package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAreaTest extends ComponentTestBase {

    /**
     * alef, bet, gimel: three strong right-to-left characters, one char apiece. The only literal
     * right-to-left text in this file, so no source line here mixes directions and reorders in an
     * editor; every fixture below builds from this one constant.
     */
    private static final String HEB = "אבג";

    /**
     * The horizontal inset {@code build()}'s area uses, and the origin every content x below is
     * quoted from. {@code build()} lays out at the process default step.
     */
    private static final float PAD_X = SizeTokens.of(ControlSize.MEDIUM).fieldPadH();

    private TextArea area;
    private Scene scene;
    private TextFieldTest.MockClipboard clipboard;

    /**
     * {@link ComponentTestBase#RULER}'s geometry with the one thing it cannot express: a
     * <b>measured</b> width that is not the sum of its clusters' widths. A space costs 10 on its
     * own and 9 inside a longer string, so {@code shape} &mdash; which is the per-cluster walk
     * &mdash; comes out one point <em>wider</em> than {@code measure} per space, and the gap grows
     * with the line.
     *
     * <p>That is the direction that bites and it is not hypothetical: the shipping backend measures
     * per code point, resolving a face per character, and shapes per run, letting a neutral keep
     * the company it is in &mdash; so a space between two Hebrew words is measured in the Latin
     * primary and shaped in the Hebrew face, and a 200-character line of it shapes some ten points
     * wider than it measures. Under {@link ComponentTestBase#RULER} the two agree by construction,
     * which is exactly why a test written on it cannot see the difference.
     */
    private static final TextRuler SEAM_RULER = (text, font) -> {
        int codePoints = (int) text.codePoints().count();
        long seams = codePoints > 1 ? text.chars().filter(c -> c == ' ').count() : 0;
        return new TextMetrics(10f * codePoints - seams, 8, 2, 12);
    };

    private void build(String text) {
        build(text, RULER);
    }

    private void build(String text, TextRuler ruler) {
        area = new TextArea();
        scene = new Scene(area);
        scene.setTextRuler(ruler);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(200, 100);
        scene.requestFocus(area);
        area.setText(text);
    }

    private void key(int keyCode, int mods) {
        scene.keyEvent(keyCode, true, false, mods);
        scene.keyEvent(keyCode, false, false, mods);
        scene.inputBatchEnded();
    }

    /** A left-button press {@code contentX} points into line 0's text, through the two pads. */
    private void pressOnFirstLine(float contentX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0,
                PAD_X + contentX, SizeTokens.of(ControlSize.MEDIUM).areaPad() + 1);
        scene.inputBatchEnded();
    }

    /** Where the caret is painted, as a content x: {@link TextArea#caretRect()} less the inset. */
    private float caretContentX() {
        return area.caretRect().x() - PAD_X + area.scrollXOffset();
    }

    @Test
    void altGrDoesNotTriggerCtrlShortcuts() {
        // Windows reports AltGr as Ctrl+Alt: a printable AltGr combo must not
        // fire the Ctrl letter shortcuts (select-all/undo/cut...).
        build("abc");
        key(Keys.A, Keys.MOD_CONTROL | Keys.MOD_ALT);
        scene.charTyped('ą');
        scene.inputBatchEnded();
        assertEquals("abcą", area.text());
    }

    @Test
    void enterInsertsNewlinesAndArrowsNavigateLines() {
        build("");
        scene.charTyped('a');
        key(Keys.ENTER, 0);
        scene.charTyped('b');
        scene.inputBatchEnded();
        assertEquals("a\nb", area.text());

        key(Keys.UP, 0);
        assertEquals(0, area.model().lineOf(area.model().cursor()));
        key(Keys.DOWN, 0);
        assertEquals(1, area.model().lineOf(area.model().cursor()));
    }

    @Test
    void selectionSpansLinesAndCopies() {
        build("first\nsecond\nthird");
        key(Keys.HOME, 0);
        // cursor at start of "third"? setText puts cursor at end; HOME → line start.
        area.model().setCursor(0, false);
        key(Keys.DOWN, Keys.MOD_SHIFT);
        key(Keys.END, Keys.MOD_SHIFT);
        assertEquals("first\nsecond", area.model().selectedText());
        key(Keys.C, Keys.MOD_CONTROL);
        assertEquals("first\nsecond", clipboard.value);
    }

    @Test
    void wheelScrollsAndClampsVertically() {
        // 30 lines x 12pt = 360pt of content in a 100pt-high area.
        build("line\n".repeat(30).trim());
        assertEquals(0, area.scrollYOffset(), 1e-3);
        scene.scrolled(0, -1, 50, 50);
        scene.inputBatchEnded();
        // A detent is a DEVICE unit and has no five-column row; read it from Strokes rather
        // than re-baking the 48, so this line is a guard on the lock instead of a duplicate.
        assertEquals(Strokes.WHEEL_STEP, area.scrollYOffset(), 1e-3);
        scene.scrolled(0, -100, 50, 50);
        scene.inputBatchEnded();
        assertTrue(area.scrollYOffset() < 360, "clamped to content");
        scene.scrolled(0, +1000, 50, 50);
        scene.inputBatchEnded();
        assertEquals(0, area.scrollYOffset(), 1e-3, "clamped to top");
    }

    @Test
    void longLinesScrollHorizontally() {
        // 600pt wide line in a 176pt viewport (200 - 2 x fieldPadH: the horizontal inset is
        // TextField's, which is what puts a field and an area on the same text column).
        build("x".repeat(60));
        key(Keys.END, 0);
        assertTrue(area.scrollXOffset() > 0, "END on a long line scrolls right");
        key(Keys.HOME, 0);
        assertEquals(0, area.scrollXOffset(), 1e-3);
    }

    @Test
    void draggableVerticalScrollbarThumb() {
        build("line\n".repeat(30).trim());
        // The shared ScrollBar occupies the right strip; its thumb starts at the
        // top. Grab it and drag down (the bar is ALWAYS-visible on a TextArea).
        // ScrollBar does not participate in the size axis, so this strip is 15pt at
        // every step; see the class javadoc on what that costs at XSMALL.
        float thumbX = 200 - 4; // inside the right-edge scrollbar strip
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, thumbX, 6);
        scene.inputBatchEnded();
        scene.mouseMoved(thumbX, 55);
        scene.inputBatchEnded();
        assertTrue(area.scrollYOffset() > 0, "dragging the thumb scrolls: " + area.scrollYOffset());
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, thumbX, 55);
        scene.inputBatchEnded();
    }

    @Test
    void pasteMultilineText() {
        build("");
        clipboard.value = "a\nbb\nccc";
        key(Keys.V, Keys.MOD_SUPER);
        assertEquals("a\nbb\nccc", area.text());
        assertEquals(3, area.model().lineCount());
    }

    // ------------------------------------------------- shaped-line geometry
    //
    // Every expected number below was produced by RULER through TextRuler's degraded shape(),
    // which needs no native, no font file and no GPU: 10 pt per code point, so "abc" + HEB draws
    //
    //     visual:     a[0,10)  b[10,20)  c[20,30) | gimel[30,40)  bet[40,50)  alef[50,60)
    //     charIndex:     0        1         2     |     5             4           3
    //
    // and the pure right-to-left line HEB draws alef[20,30) bet[10,20) gimel[0,10). Logical order
    // in, expected visual positions out: none of these assertions is a screenshot.

    @Test
    void aClickEitherSideOfADirectionBoundaryLandsOnTheTwoInsertionPointsSharingThatPixel() {
        // The seam between "abc" and the Hebrew sits at x = 30, and TWO insertion points draw
        // there: index 3 (after "c") and index 6 (after the Hebrew). A binary search over caret x
        // values cannot tell them apart, because both ARE 30; resolving through the cluster under
        // the point can, and the two clicks are only 2 pt apart.
        build("abc" + HEB);

        pressOnFirstLine(29); // the trailing half of "c", which for LTR is its right half
        assertEquals(3, area.model().cursor());
        assertEquals(ShapedText.Affinity.UPSTREAM, area.model().caret().affinity());
        assertEquals(30, caretContentX(), 1e-3);

        pressOnFirstLine(31); // the trailing half of gimel, which for RTL is its LEFT half
        assertEquals(6, area.model().cursor());
        assertEquals(ShapedText.Affinity.UPSTREAM, area.model().caret().affinity());
        assertEquals(30, caretContentX(), 1e-3, "the same pixel, a different insertion point");
    }

    @Test
    void aClickPastTheEndOfALineGoesToItsLogicalEndAndNotToTheNearestCluster() {
        // The line is 60 pt wide and ends in Hebrew, so the cluster nearest the right edge is
        // alef, which is char 3 — NOT the last character. Clamping to the nearest cluster would
        // put the caret in the middle of the string; empty space to the right of a line means the
        // line's logical end.
        build("abc" + HEB);
        pressOnFirstLine(70);
        assertEquals(6, area.model().cursor(), "past the end is the logical end, not alef");
    }

    @Test
    void aSelectionCrossingTheDirectionBoundaryPaintsTheBoxesItReallyCovers() {
        // Chars 2, 3, 4 are c, alef and bet. c draws at [20,30) and alef+bet at [40,60); gimel,
        // which is char 5 and NOT selected, draws at [30,40) between them. One rectangle covering
        // both would highlight gimel, which the user did not select.
        build("abc" + HEB);
        area.model().setCursor(2, false);
        area.model().setCursor(5, true);

        FillRecorder canvas = new FillRecorder(200, 100);
        scene.renderFrame(canvas);

        assertEquals(List.of("20.0..30.0", "40.0..60.0"), canvas.bandsOnLine(0, 12),
                "two boxes, with the unselected gimel untouched between them");
    }

    @Test
    void leftArrowAtTheVisualLeftEdgeEntersThePreviousLineAtItsRightEdge() {
        // Line 0 is "cd" + alef bet: an LTR paragraph whose LAST characters read right to left,
        // so its visual right edge (x = 40) is char 2 and not char 4. Entering the line at "index
        // length" would land at x = 20, a whole run away from where the key pointed.
        build("cd" + HEB.substring(0, 2) + "\nxy");
        area.model().setCursor(5, false); // the first char of line 1

        key(Keys.LEFT, 0);

        assertEquals(2, area.model().cursor());
        assertEquals(ShapedText.Affinity.DOWNSTREAM, area.model().caret().affinity());
        assertEquals(40, caretContentX(), 1e-3, "the previous line's visual RIGHT edge");
    }

    @Test
    void rightArrowAtTheVisualRightEdgeEntersTheNextLineAtItsLeftEdge() {
        // The mirror, and the sharper case: line 1 is a pure right-to-left paragraph, so its
        // visual LEFT edge is its LOGICAL END, char 3 of that line. Entering at "index 0" would
        // put the caret at x = 30, the far side of a line the caret has only just reached.
        build("xy\n" + HEB);
        area.model().setCursor(2, false); // the end of line 0

        key(Keys.RIGHT, 0);

        assertEquals(6, area.model().cursor(), "line 1 starts at 3 and its left edge is its end");
        assertEquals(0, caretContentX(), 1e-3, "the next line's visual LEFT edge");
    }

    @Test
    void leftArrowIsVisualAndWordLeftIsLogicalAndTheyDisagreeInRightToLeftText() {
        // The whole shape of the split, on one line: Left is named for a direction on the screen,
        // Ctrl+Left has to land on the end of a contiguous range because deleteWordBackward
        // removes one. In right-to-left text those are opposite directions, exactly as on Windows
        // and in GTK, and this is the assertion that says so on purpose.
        build(HEB);

        area.model().setCursor(1, false);
        assertEquals(20, caretContentX(), 1e-3);
        key(Keys.LEFT, 0);
        assertEquals(2, area.model().cursor());
        assertEquals(10, caretContentX(), 1e-3, "Left moves LEFT: 20 -> 10");

        area.model().setCursor(1, false);
        key(Keys.LEFT, Keys.MOD_CONTROL);
        assertEquals(0, area.model().cursor());
        assertEquals(30, caretContentX(), 1e-3, "Ctrl+Left moves back in the STRING: 20 -> 30");
    }

    @Test
    void homeAndEndInARightToLeftLineLandOnTheParagraphsOwnEdges() {
        // Home is logical, so in a right-to-left paragraph it goes to the visual RIGHT. Every
        // platform makes this split — Windows edit controls, GTK's DISPLAY_LINE_ENDS movement and
        // Cocoa's moveToBeginningOfLine: are all logical while the arrows are visual — and it is
        // forced anyway, since Shift+Home must produce one contiguous range of the string.
        build(HEB);

        key(Keys.HOME, 0);
        assertEquals(0, area.model().cursor());
        assertEquals(30, caretContentX(), 1e-3, "Home draws at the line's visual RIGHT");

        key(Keys.END, 0);
        assertEquals(3, area.model().cursor());
        assertEquals(0, caretContentX(), 1e-3, "End draws at the line's visual LEFT");
    }

    @Test
    void aVisualArrowStepsOverAWholeClusterAndNeverIntoOne() {
        // A surrogate pair is one caret stop, so the arrow crosses it in a single press: 4 -> 3
        // -> 1, never 4 -> 3 -> 2. The stops come from the shaping, so there is no second rule
        // for finding cluster boundaries that could drift from the first.
        build("a🌈b"); // a, rainbow, b
        area.model().setCursor(4, false);

        key(Keys.LEFT, 0);
        assertEquals(3, area.model().cursor());
        key(Keys.LEFT, 0);
        assertEquals(1, area.model().cursor(), "the pair is one step, not two");
    }

    @Test
    void anEmptyLineInsideASelectionStillShowsTheBandThatSaysTheBreakIsSelected() {
        // selection(i, i) is an empty list — a caret is not a zero-width selection — so an empty
        // line inside a multi-line selection has no box of its own and the newline hint IS its
        // band. Drawn once, not twice: two translucent quads over the same pixels would blend to
        // a darker band on exactly the line that has the least ink to hide it.
        build("a\n\nb");
        area.model().selectAll();

        FillRecorder canvas = new FillRecorder(200, 100);
        scene.renderFrame(canvas);

        float hint = SizeTokens.of(ControlSize.MEDIUM).newlineHint();
        assertEquals(List.of("0.0.." + hint), canvas.bandsOnLine(12, 12),
                "one band on the empty line, the width of the newline hint");
        assertEquals(List.of("0.0..10.0", "10.0.." + (10 + hint)), canvas.bandsOnLine(0, 12),
                "line 0's own box, then the hint at its logical end edge");
    }

    @Test
    void theNewlineHintOnARightToLeftLineSitsLeftOfTheOriginWhereTheNextLineContinues() {
        // The hint is anchored at the line's LOGICAL end edge and extends in the paragraph's
        // reading direction, which for a right-to-left line puts it LEFT of x=0 -- the side the
        // next line continues from, and the only side on which it can mean "the break is
        // selected". Appending it to the last box, the way the single-rect band used to, would
        // put it past the line's visual RIGHT edge: against the line's FIRST character, which is
        // where the selection began rather than where it runs on.
        build(HEB + "\n" + HEB);
        area.model().selectAll();

        FillRecorder canvas = new FillRecorder(200, 100);
        scene.renderFrame(canvas);

        float hint = SizeTokens.of(ControlSize.MEDIUM).newlineHint();
        assertEquals(List.of((-hint) + ".." + 0.0f, "0.0..30.0"), canvas.bandsOnLine(0, 12),
                "the hint left of the origin, then the line's own box");
        assertEquals(List.of("0.0..30.0"), canvas.bandsOnLine(12, 12),
                "the last line's break is not selected, so it gets the box and no hint");
    }

    @Test
    void endOnTheWidestLineScrollsToExactlyWhatTheScrollBarCanReach() {
        // The trap the widest-line cache sets: contentWidth() scans the DOCUMENT and therefore
        // asks scanWidth rather than shaping, while the caret's x is shaped. If the two disagreed
        // this number would move. They agree, so the only difference left is the clamp: the caret asks
        // for CLIP_CLEARANCE of daylight past the right edge and the scroll extent has none to
        // give, which is the documented residue and is why CLIP_CLEARANCE is a whole point.
        build("x".repeat(60));
        key(Keys.END, 0);

        float viewWidth = 200 - 2 * PAD_X; // the overlay bars reserve no strip
        assertEquals(600 - viewWidth, area.scrollXOffset(), 1e-3);
        assertEquals(600, caretContentX(), 1e-3, "the caret is where the shaping put it");
    }

    @Test
    void theScrollExtentReachesTheSHAPEDEndOfALineAndNotItsMeasuredOne() {
        // The case the test above structurally cannot see. Its ruler makes shape() and measure()
        // the same number, so the scan that finds the widest line and the shaping that places the
        // caret agree by construction; SEAM_RULER makes them disagree in the direction that hurts,
        // with shape() a point WIDER per space. Nineteen spaces here, so nineteen points — the
        // caret would be painted 19pt past a clip that allows 2, which is not a hairline, it is a
        // caret that vanishes when the user presses End.
        String line = "ab ".repeat(20).trim(); // 59 chars, 19 spaces
        Font f = SizeTokens.of(ControlSize.MEDIUM).body();
        assertEquals(571, SEAM_RULER.measure(line, f).width(), 1e-3);
        assertEquals(590, SEAM_RULER.shape(line, f).metrics().width(), 1e-3,
                "the fixture is only a test if the two rulers actually disagree");

        build(line, SEAM_RULER);
        key(Keys.END, 0);

        float viewWidth = 200 - 2 * PAD_X;
        // The scroll the caret needs, and the whole finding in one number: 571 is what the scan
        // measured and 590 is where the shaping put the caret, so an extent taken from the scan
        // alone stops 19pt short of it and clampScroll refuses the rest.
        assertEquals(590 - viewWidth, area.scrollXOffset(), 1e-3,
                "the extent has to reach the shaped end, not the 571 the scan measured");
        // caretRect() clamps the painted caret into the padded viewport, so this reads back the
        // shaped x only while the scroll can actually reach it; short of it, the caret is pinned to
        // the right edge and this saturates at the extent — which is the vanishing caret, seen.
        assertEquals(590, caretContentX(), 1e-3, "the caret is drawn where the shaping put it");

        // And the same extent from the other consumer: scrolling to the far right — by wheel, or by
        // dragging the horizontal thumb to its end, which reads the same model — brings the last of
        // the line's ink to the viewport edge instead of stopping 19pt short of it.
        area.scrollBy(10_000, 0);
        assertEquals(590 - viewWidth, area.scrollXOffset(), 1e-3,
                "the last of the line can never be scrolled into view");
    }

    /**
     * {@link ComponentTestBase#RULER}, counting the two questions apart: how many strings it was
     * asked to <b>shape</b>, and how many it was asked to scan.
     *
     * <p>{@code measure} answers from {@code shape} here on purpose, because the shipping backend's
     * does. A counter that let {@code measure} pass uncounted would report a document-wide scan
     * through {@code measure} as costing nothing, which is the exact regression the test below
     * exists to catch, and the test would pass while the widget re-shaped ten thousand lines per
     * keystroke.
     */
    private static final class CountingRuler implements TextRuler {

        private int shapes;
        private int scans;

        @Override
        public TextMetrics measure(String text, Font font) {
            return shape(text, font).metrics();
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            shapes++;
            // Delegated to RULER rather than to this interface's own default, which would call
            // THIS measure once per cluster and recur forever.
            return RULER.shape(text, font, base);
        }

        @Override
        public float scanWidth(String text, Font font) {
            scans++;
            return RULER.measure(text, font).width();
        }
    }

    /** What one keystroke asked of the ruler, in an area holding {@code lines} lines. */
    private record Keystroke(int shapes, int scans) {
    }

    private Keystroke costOfOneKeystroke(int lines) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            document.append("a line of prose, number ").append(i).append('\n');
        }
        CountingRuler ruler = new CountingRuler();
        build(document.toString(), ruler);
        FakeCanvas canvas = new FakeCanvas(200, 100);
        scene.renderFrame(canvas); // the first frame is allowed to resolve everything
        ruler.shapes = 0;
        ruler.scans = 0;
        scene.charTyped('x');
        scene.inputBatchEnded();
        scene.renderFrame(canvas);
        return new Keystroke(ruler.shapes, ruler.scans);
    }

    /**
     * What typing costs as a function of how much text the document holds: nothing, in the one
     * currency that matters.
     *
     * <p>Every edit invalidates the widest-line cache and the {@code ensureCursorVisible} that
     * follows reads it straight back, so the document is scanned once per character typed. That
     * scan must not be a scan of <em>shapings</em>. A shaping ruler memoizes, and this loop is the
     * worst client a memo can have: it walks every line in the same cyclic order, so past the
     * memo's depth it misses on every line every time, and the memo is process-wide, so it evicts
     * the captions of widgets that did nothing and they repaint cold. Measured on the shipping
     * backend before this was fixed, a 1000-line document cost 5.8&nbsp;ms per keystroke against
     * 0.3&nbsp;ms for the same scan taken without shaping &mdash; a dropped frame per character.
     *
     * <p>So the assertion is an equality and not a threshold: a keystroke in a 1000-line document
     * hands the ruler exactly as many strings to shape as one in a 100-line document, because what
     * gets shaped is what gets <em>drawn</em>. The scan is still O(lines) and still runs, which the
     * second half checks &mdash; it has moved to the cheap question, not disappeared.
     */
    @Test
    void typingDoesNotReshapeTheDocumentItIsTypedInto() {
        Keystroke small = costOfOneKeystroke(100);
        Keystroke large = costOfOneKeystroke(1000);

        assertEquals(small.shapes(), large.shapes(),
                "a keystroke shaped more strings in the longer document: the widest-line scan is "
                        + "shaping the document again");
        assertTrue(large.shapes() < 100,
                "even the short document is being scanned through the shaper");

        assertTrue(large.scans() >= 1000 && small.scans() >= 100,
                "the widest-line scan stopped happening; the extent is no longer the widest line");
    }

    /**
     * Records selection bands. {@code translate} is a no-op on {@link FakeCanvas}, so the x and y
     * that arrive here are already content space: line {@code n}'s top is {@code n * lineHeight}.
     * The widget's own background is a round rect, so every plain {@code fillRect} is a band.
     */
    private static final class FillRecorder extends FakeCanvas {

        private final List<float[]> rects = new ArrayList<>();

        FillRecorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            rects.add(new float[]{x, y, w, h});
        }

        /** The bands covering the line box at {@code top}, left to right, as {@code x0..x1}. */
        List<String> bandsOnLine(float top, float lineHeight) {
            return rects.stream()
                    .filter(r -> Math.abs(r[1] - top) < 1e-3 && Math.abs(r[3] - lineHeight) < 1e-3)
                    .sorted((a, b) -> Float.compare(a[0], b[0]))
                    .map(r -> r[0] + ".." + (r[0] + r[2]))
                    .toList();
        }
    }

    // ------------------------------------------------------- control sizes

    /** A fresh area alone in its own scene, at {@code step}, under the given ruler. */
    private static TextArea areaAt(ControlSize step, limn.graphics.TextRuler ruler) {
        TextArea a = new TextArea();
        Scene host = new Scene(a);
        host.setTextRuler(ruler);
        a.setControlSize(step);
        return a;
    }

    @Test
    void preferredSizeFollowsTheStep() {
        // MEDIUM must still measure 320 x 140: the literals the field initializers carried.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            SizeTokens t = SizeTokens.of(step);
            Size measured = a.measure(Constraints.loose(1000, 1000));
            assertEquals(t.areaWidth(), measured.width(), 1e-3, step + " width");
            assertEquals(t.areaHeight(), measured.height(), 1e-3, step + " height");
        }
    }

    @Test
    void explicitPreferredSizeOverridesPerAxis() {
        // FormsScene passes 0 for the width (let the column stretch it); a negative value is
        // the "unset" sentinel, so pinning one axis leaves the other on its token.
        TextArea a = areaAt(ControlSize.LARGE, SCALED_RULER);
        a.setPreferredSize(-1, 150);
        Size measured = a.measure(Constraints.loose(1000, 1000));
        assertEquals(SizeTokens.of(ControlSize.LARGE).areaWidth(), measured.width(), 1e-3);
        assertEquals(150, measured.height(), 1e-3);
    }

    @Test
    void baselineIsTheFirstLineBaselineAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            SizeTokens t = SizeTokens.of(step);
            a.measure(Constraints.loose(1000, 1000));
            a.layoutBox(0, 0, t.areaWidth(), t.areaHeight());
            float expected = t.areaPad() + SCALED_RULER.measure("Hg", t.body()).ascent();
            assertEquals(expected, a.baselineOffset(), 1e-3,
                    step + " aligns on line 0's baseline, not on the bottom edge");
        }
    }

    @Test
    void wheelDetentIsPixelLockedAtEveryStep() {
        // The point of locking WHEEL_STEP: one physical flick travels the same physical
        // distance in a dense editor and a roomy one.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, RULER);
            Scene host = a.scene();
            host.layoutPass(200, 100);
            a.setText("line\n".repeat(30).trim());
            host.scrolled(0, -1, 50, 50);
            host.inputBatchEnded();
            assertEquals(Strokes.WHEEL_STEP, a.scrollYOffset(), 1e-3, step + " detent");
        }
    }

    @Test
    void clickMapsThroughTheAnisotropicPadAtEveryStep() {
        // The one thing a measure/paint token split breaks silently: RULER puts code-point
        // boundaries at 0/10/20/30..., so a press 26pt into the text must land on index 3 at
        // every step, but only if the hit test insets by the SAME pads the paint does, and
        // those are two different tokens: fieldPadH across, areaPad down. Feeding
        // areaPad to the x axis here would land on index 2 at MEDIUM (26 - 4 = 22 -> boundary
        // 2), which is exactly the drift the split can cause.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, RULER);
            Scene host = a.scene();
            host.layoutPass(200, 100);
            a.setText("abcdef");
            SizeTokens t = SizeTokens.of(step);
            host.mouseButton(Keys.MOUSE_LEFT, true, 0, t.fieldPadH() + 26, t.areaPad() + 1);
            host.inputBatchEnded();
            assertEquals(3, a.model().cursor(), step + " maps the press through the two pads");
        }
    }

    @Test
    void textStartsOnTheSameColumnAsATextFieldAtEveryStep() {
        // The whole point of the shared inset: a field and an area stacked in a form put their first
        // character on one x column. Both are scene roots at (0,0), so the recorded absolute x
        // IS the inset, and the area hides its inset in a translate, which is why the canvas
        // tracks the transform rather than reading drawText's argument.
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);

            TextArea a = areaAt(step, RULER);
            Scene areaHost = a.scene();
            areaHost.layoutPass(200, 100);
            a.setText("abc");
            a.model().setCursor(0, false); // caret at the start: its x is the inset too
            GeometryCanvas areaCanvas = new GeometryCanvas(200, 100);
            areaHost.renderFrame(areaCanvas);

            TextField field = new TextField();
            Scene fieldHost = new Scene(field);
            fieldHost.setTextRuler(RULER);
            field.setControlSize(step);
            fieldHost.layoutPass(200, 100);
            field.setText("abc");
            GeometryCanvas fieldCanvas = new GeometryCanvas(200, 100);
            fieldHost.renderFrame(fieldCanvas);

            assertEquals(t.fieldPadH(), areaCanvas.firstTextX(), 1e-3,
                    step + " area insets its text by fieldPadH");
            assertEquals(fieldCanvas.firstTextX(), areaCanvas.firstTextX(), 1e-3,
                    step + " field and area share the text column");
            // caretRect is scene-absolute and the area is at the origin, so this reads the
            // same inset from the IME/candidate-window path, which clamps against padX.
            assertEquals(t.fieldPadH(), a.caretRect().x(), 1e-3,
                    step + " the caret clamp follows the same pad");
        }
    }

    @Test
    void textClipTakesTheAaBleedAcrossOnly() {
        // The horizontal clip carries TextField's -AA_BLEED / +2*AA_BLEED allowance so the
        // first and last glyph keep their antialiasing fringe. The VERTICAL clip stays tight on
        // the pad: it is a scroll boundary against the border, and bleeding it would let a
        // half-scrolled line's ink sit on the rounded border.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            Scene host = a.scene();
            host.layoutPass(320, 140);
            a.setText("one\ntwo");
            GeometryCanvas canvas = new GeometryCanvas(320, 140);
            host.renderFrame(canvas);

            SizeTokens t = SizeTokens.of(step);
            float viewW = 320 - 2 * t.fieldPadH();
            float viewH = 140 - 2 * t.areaPad();
            assertTrue(canvas.hasClip(t.fieldPadH() - Strokes.AA_BLEED, t.areaPad(),
                            viewW + 2 * Strokes.AA_BLEED, viewH),
                    step + " clips the text run at the bled pad; saw " + canvas.clipsAsText());
        }
    }

    @Test
    void everyStrokeIsIdenticalAtEveryStep() {
        // The pixel-lock rule, checked mechanically. Painted UNFOCUSED so the animated
        // BORDER -> FOCUS_RING width is settled at exactly BORDER; a mid-fade recording is
        // fractional and flaky by construction. ScrollBar draws no strokes at all.
        List<Float> mediumWidths = null;
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            Scene host = a.scene();
            host.layoutPass(320, 140);
            a.setText("one\ntwo");
            StrokeRecordingCanvas canvas = new StrokeRecordingCanvas(320, 140);
            host.renderFrame(canvas);
            List<Float> widths = canvas.widths();
            assertEquals(List.of(Strokes.BORDER), widths,
                    step + " paints one unscaled border and nothing else");
            if (mediumWidths == null) {
                mediumWidths = widths;
            }
            assertEquals(mediumWidths, widths, step + " matches the first step's multiset");
        }
    }

    /**
     * Records what the paint pass actually asks for, in SCENE coordinates. {@link FakeCanvas}
     * ignores the transform, and TextArea puts its whole content inset in a {@code translate}
     * while drawing every line at {@code x == 0}, so the translate stack has to be tracked
     * here or "where does the first glyph land" is unanswerable from the recorded arguments.
     */
    private static final class GeometryCanvas extends FakeCanvas {

        private final List<float[]> clips = new ArrayList<>();
        private final List<float[]> texts = new ArrayList<>();
        private final Deque<float[]> stack = new ArrayDeque<>();
        private float tx;
        private float ty;

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void save() {
            stack.push(new float[]{tx, ty});
        }

        @Override
        public void restore() {
            if (!stack.isEmpty()) { // lenient: a widget that throws mid-paint must not mask itself
                float[] saved = stack.pop();
                tx = saved[0];
                ty = saved[1];
            }
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            clips.add(new float[]{tx + x, ty + y, w, h});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new float[]{tx + x, ty + y});
        }

        float firstTextX() {
            assertTrue(!texts.isEmpty(), "nothing was drawn");
            return texts.get(0)[0];
        }

        boolean hasClip(float x, float y, float w, float h) {
            return clips.stream().anyMatch(c -> Math.abs(c[0] - x) < 1e-3
                    && Math.abs(c[1] - y) < 1e-3
                    && Math.abs(c[2] - w) < 1e-3
                    && Math.abs(c[3] - h) < 1e-3);
        }

        String clipsAsText() {
            return clips.stream().map(Arrays::toString).toList().toString();
        }
    }

    @Test
    void aReservedBarNarrowsTheTextColumn() {
        // Observable through the reach: the column loses the bar's width, so the
        // same long line has exactly that much more to scroll past.
        float overlaid = maxScrollXWith(ScrollGutters.Layout.OVERLAY);
        float reserved = maxScrollXWith(ScrollGutters.Layout.RESERVED);

        assertEquals(overlaid + ScrollBar.thickness(), reserved, 0.5f,
                "the reserved strip did not come out of the text column");
    }

    /** How far right the area can scroll a long line at {@code layout}. */
    private float maxScrollXWith(ScrollGutters.Layout layout) {
        TextArea area = new TextArea();
        area.setBarLayout(layout);
        // Long AND many: the column only loses width to the vertical bar, so the
        // text has to overflow downwards before there is a strip to lose it to.
        area.setText(("a line long enough to overflow any sane column ".repeat(6) + "\n")
                .repeat(30));
        Scene host = new Scene(area);
        host.setTextRuler(RULER);
        host.setClipboard(new TextFieldTest.MockClipboard());
        host.layoutPass(240, 120);
        area.scrollBy(10_000, 0);
        host.layoutPass(240, 120);
        return area.scrollXOffset();
    }

    /**
     * Page keys used to fall through to {@code handled = false} and do nothing at all (not even
     * scroll), leaving a keyboard user in a long document holding Down or jumping to an edge.
     */
    @Test
    void pageKeysMoveTheCaretAViewportAtATime() {
        StringBuilder document = new StringBuilder();
        for (int line = 0; line < 60; line++) {
            document.append("line ").append(line).append('\n');
        }
        build(document.toString());
        area.model().setCursor(0, false);

        key(Keys.PAGE_DOWN, 0);
        int afterOnePage = area.model().lineOf(area.model().cursor());
        assertTrue(afterOnePage > 1,
                "a page must be more than one line; it moved " + afterOnePage);
        assertTrue(afterOnePage < 60, "a page must be less than the whole document");

        key(Keys.PAGE_DOWN, 0);
        assertEquals(2 * afterOnePage, area.model().lineOf(area.model().cursor()),
                "two pages must move twice as far as one");

        key(Keys.PAGE_UP, 0);
        assertEquals(afterOnePage, area.model().lineOf(area.model().cursor()));
    }

    @Test
    void shiftPageExtendsTheSelectionRatherThanMovingPastIt() {
        StringBuilder document = new StringBuilder();
        for (int line = 0; line < 60; line++) {
            document.append("line ").append(line).append('\n');
        }
        build(document.toString());
        area.model().setCursor(0, false);

        key(Keys.PAGE_DOWN, Keys.MOD_SHIFT);
        assertTrue(area.model().hasSelection(), "Shift+Page is how a screenful is taken");
        assertTrue(area.model().selectedText().startsWith("line 0"),
                "the selection must run from where the caret was");
    }

    // ------------------------------------------------------------- soft wrap
    //
    // Every fixture below runs in the 200 pt build(): the text column is 200 - 2 × PAD_X =
    // 176 pt, so under RULER (10 pt per code point) 17 characters fit a row and the 18th does
    // not. Rows and their char offsets are stated in the tests as facts of that arithmetic.

    private static final float PAD_Y = SizeTokens.of(ControlSize.MEDIUM).areaPad();
    private static final float EPS = 1e-3f;

    private void buildWrapped(String text) {
        build("");
        area.setSoftWrap(true);
        area.setText(text);
    }

    @Test
    void softWrapBreaksAtWordBoundariesAndHangsTheSpace() {
        buildWrapped("aaaa bbbb cccc dddd eeee"); // 240 pt against a 176 pt column
        // fitEnd lands mid-"dddd"; the break backs up to the boundary after "cccc ", and the
        // space hangs: rows are [0, 15), [15, 24). setText leaves the caret at the end.
        assertEquals(PAD_X + 90, area.caretRect().x(), EPS);  // after "dddd eeee"
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS);  // on the second row
        // A caret inside the hung space clamps to its row's drawn end, on the first row.
        area.model().setCursor(14, false);
        assertEquals(PAD_X + 140, area.caretRect().x(), EPS); // after "aaaa bbbb cccc"
        assertEquals(PAD_Y, area.caretRect().y(), EPS);
        assertEquals(0, area.scrollXOffset(), EPS);
    }

    @Test
    void softWrapPinsTheHorizontalAxisAndScrollsTheVerticalOne() {
        buildWrapped("a".repeat(400)); // 24 cluster-broken rows of 17 against a 7-row viewport
        key(Keys.END, Keys.MOD_CONTROL);
        assertEquals(0, area.scrollXOffset(), EPS);
        assertEquals(24 * 12 - 84, area.scrollYOffset(), EPS); // the caret's row revealed
        assertEquals(100 - PAD_Y - 12, area.caretRect().y(), EPS); // on the viewport's last row
    }

    @Test
    void togglingSoftWrapResetsTheHorizontalScroll() {
        build("a".repeat(40));
        key(Keys.END, Keys.MOD_CONTROL);
        assertTrue(area.scrollXOffset() > 0, "unwrapped, End travels the long line");
        area.setSoftWrap(true);
        assertEquals(0, area.scrollXOffset(), EPS);
        key(Keys.END, Keys.MOD_CONTROL);
        assertEquals(0, area.scrollXOffset(), EPS);
        area.setSoftWrap(false);
        key(Keys.END, Keys.MOD_CONTROL);
        assertTrue(area.scrollXOffset() > 0, "the axis is live again");
    }

    @Test
    void softWrapUpDownStepByVisualRowOnAStickyGoalX() {
        buildWrapped("a".repeat(40)); // no break opportunity: cluster rows [0, 17), [17, 34), [34, 40)
        area.model().setCursor(16, false); // first row, x = 160
        key(Keys.DOWN, 0);
        assertEquals(33, area.model().cursor(), "one row down, same x");
        key(Keys.DOWN, 0);
        assertEquals(40, area.model().cursor(), "the last row is short: its logical end");
        key(Keys.UP, 0);
        assertEquals(33, area.model().cursor(), "the goal x survived the short row");
        key(Keys.UP, 0);
        assertEquals(16, area.model().cursor());
        key(Keys.UP, 0);
        assertEquals(0, area.model().cursor(), "no row above: the document's start");
    }

    @Test
    void softWrapCaretOnASoftBreakIsTwoPlacesAndTheSideSaysWhich() {
        buildWrapped("a".repeat(40));
        area.model().setCaret(new ShapedText.Position(17, ShapedText.Affinity.UPSTREAM), false);
        assertEquals(PAD_X + 170, area.caretRect().x(), EPS); // the end of the first row
        assertEquals(PAD_Y, area.caretRect().y(), EPS);
        area.model().setCaret(new ShapedText.Position(17, ShapedText.Affinity.DOWNSTREAM), false);
        assertEquals(PAD_X, area.caretRect().x(), EPS);       // the start of the second
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS);
    }

    @Test
    void softWrapClickLandsOnTheRowUnderThePointer() {
        buildWrapped("a".repeat(40));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, PAD_X + 22, PAD_Y + 12 + 1);
        scene.inputBatchEnded();
        assertEquals(19, area.model().cursor(), "the second row starts at 17; 22 pt is its third char");
    }

    /** Records selection bands: the fillRects exactly one line box tall, translate included. */
    private static final class BandCanvas extends FakeCanvas {
        private float tx;
        private float ty;
        final List<float[]> bands = new ArrayList<>();

        BandCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            if (h == 12) {
                bands.add(new float[]{tx + x, ty + y, w});
            }
        }
    }

    @Test
    void softWrapSelectionPaintsOneBandPerRow() {
        buildWrapped("a".repeat(40));
        area.model().setCursor(10, false);
        area.model().setCursor(30, true);
        BandCanvas canvas = new BandCanvas(200, 100);
        scene.renderFrame(canvas);
        assertEquals(2, canvas.bands.size(), "a range crossing one soft break is two bands");
        assertArrayEquals(new float[]{PAD_X + 100, PAD_Y, 70}, canvas.bands.get(0), EPS);
        assertArrayEquals(new float[]{PAD_X, PAD_Y + 12, 130}, canvas.bands.get(1), EPS);
    }

    @Test
    void softWrapRewrapsTheEditedLineOnEachKeystroke() {
        buildWrapped("aaaa bbbb");
        for (int i = 0; i < 10; i++) {
            scene.charTyped('c');
        }
        scene.inputBatchEnded();
        assertEquals("aaaa bbbbcccccccccc", area.text());
        // The unbreakable tail moved whole to a second row: [0, 5), [5, 19).
        assertEquals(PAD_X + 140, area.caretRect().x(), EPS);
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS);
    }

    @Test
    void softWrapSplicesEditsIntoAMultiLineDocument() {
        buildWrapped("aaa\nbbbb bbbb bbbb bbbb bbbb\nccc"); // middle line wraps to two rows
        key(Keys.END, Keys.MOD_CONTROL);
        assertEquals(PAD_Y + 3 * 12, area.caretRect().y(), EPS); // rows 1 + 2 + 1
        area.model().setCursor(28, false); // end of the middle line
        for (int i = 0; i < 8; i++) {
            key(Keys.BACKSPACE, 0);
        }
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS); // the middle line fits again
        key(Keys.END, Keys.MOD_CONTROL);
        assertEquals(PAD_Y + 2 * 12, area.caretRect().y(), EPS);
    }

    /** Records where every drawn string lands, translate included. */
    private static final class TextCanvas extends FakeCanvas {
        private float tx;
        private float ty;
        final List<Object[]> texts = new ArrayList<>();

        TextCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new Object[]{text, tx + x, ty + y});
        }
    }

    @Test
    void softWrapComposingGrowsARowAndTheLinesBelowMoveHonestly() {
        buildWrapped("aaaa bbbb cccc\nzzz"); // both lines fit: one row each
        area.model().setCursor(14, false);   // end of the first line
        scene.preeditChanged("ddddd", new int[]{5}, 0, 5);
        scene.inputBatchEnded();
        // The composed first line, "aaaa bbbb ccccddddd" (190 pt), wraps to [0, 10), [10, 19):
        // the caret sits after the preedit on the second row, and "zzz" paints a row lower.
        assertEquals(PAD_X + 90, area.caretRect().x(), EPS);
        assertEquals(PAD_Y + 12, area.caretRect().y(), EPS);
        TextCanvas composing = new TextCanvas(200, 100);
        scene.renderFrame(composing);
        assertEquals(PAD_Y + 2 * 12 + 8, yOf(composing, "zzz"), EPS);
        // The composition ends: the committed line is one row again and "zzz" moves back up.
        scene.preeditChanged("", new int[]{}, -1, 0);
        scene.inputBatchEnded();
        TextCanvas committed = new TextCanvas(200, 100);
        scene.renderFrame(committed);
        assertEquals(PAD_Y + 12 + 8, yOf(committed, "zzz"), EPS);
    }

    private static float yOf(TextCanvas canvas, String text) {
        for (Object[] drawn : canvas.texts) {
            if (text.equals(drawn[0])) {
                return (float) drawn[2];
            }
        }
        throw new AssertionError("'" + text + "' was not drawn; saw " + canvas.texts.size());
    }
}
