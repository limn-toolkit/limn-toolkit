package limn.components;

import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ellipsis and wrap math against the deterministic 10pt-per-glyph {@link #RULER}; the
 * typography ramp against {@link #SCALED_RULER}, which is the only one whose vertical
 * metrics track the font (the flat ruler reports lineHeight 12 at every size, so a step
 * assertion made under it would pin a number the real renderer never produces).
 *
 * <p>The wrap and ellipsis cases are stated as <b>logical order in, expected visual positions
 * out</b>, which is the only form in which a bidi answer can be checked at all: a screenshot of
 * mixed text looks plausible while being wrong, and the numbers here come from the stop table
 * rather than from an eye.
 */
class LabelTest extends ComponentTestBase {

    /** Never rasterized: these tests measure and lay out, they never paint. */
    private static final Icon ICON = (pixelSize, dark) -> {
        throw new UnsupportedOperationException("measure-only");
    };

    /**
     * Alef, bet, gimel, as escapes and in one place. Written this way so no source line in this
     * file mixes directions and reorders under an editor, which is how a fixture silently stops
     * being the fixture the assertion was written against.
     */
    private static final String HEBREW = "אבג";

    /** Five unspaced ideographs and kana: the case with no space to break at. */
    private static final String CJK = "今日は世界";

    /**
     * "sawatdi chao lok" &mdash; twelve Thai characters in ten grapheme clusters, written as the
     * three words it is so the boundaries the assertions use are visible in the fixture: 6 and 9,
     * with no space anywhere and nothing but a dictionary to find them.
     */
    private static final String THAI = "สวัสดี" + "ชาว" + "โลก";

    /**
     * {@link #RULER} plus one thing it cannot express: an ellipsis that costs more when something
     * precedes it. That is the shape of what a joining script does at a cut — the forms on both
     * sides of the cut change, so the kept prefix beside an ellipsis is not the prefix that was
     * measured — and it is invisible to a measurement of the ellipsis alone, which is exactly the
     * blind spot {@code ellipsize}'s step-back loop covers. Left to right only; the reordering
     * cases use {@link #RULER}.
     */
    private static final TextRuler JOINING_RULER = new TextRuler() {
        @Override
        public TextMetrics measure(String text, Font font) {
            return new TextMetrics(shape(text, font).metrics().width(), 8, 2, 12);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            int length = text.length();
            ShapedText.Builder builder = ShapedText.builder(text, font, base, length)
                    .lineMetrics(8, 2, 12);
            if (length == 0) {
                return builder.build();
            }
            builder.run(0, 0, length, 0);
            for (int i = 0; i < length; i = text.offsetByCodePoints(i, 1)) {
                builder.glyph(ShapedText.NO_GLYPH, i,
                        i > 0 && text.startsWith("…", i) ? 20 : 10, 0, 0);
            }
            return builder.build();
        }
    };

    private Locale locale;

    @BeforeEach
    void rememberLocale() {
        locale = I18n.locale();
    }

    /** The line breaker reads the UI language, so a case that sets it has to put it back. */
    @AfterEach
    void restoreLocale() {
        I18n.setLocale(locale);
    }

    private Scene sceneWith(Label label, float width, float height) {
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, height);
        return scene;
    }

    /** Scene on the font-tracking ruler, unlaid-out: for measure and font assertions. */
    private Scene scaledScene(Label label) {
        Scene scene = new Scene(label);
        scene.setTextRuler(SCALED_RULER);
        return scene;
    }

    private static Size measured(Label label) {
        return label.measure(Constraints.loose(500, 500));
    }

    /**
     * The repaint-only path in {@code setText} skips the layout pass, and the layout pass
     * is what fills the line list that {@code onPaint} draws. Without rebuilding it there,
     * a label repaints the string it no longer holds, and it fails on exactly the case the
     * fast path exists for: a counter whose digits are the same width every tick.
     */
    @Test
    void aSameWidthTextIsActuallyPaintedAndNotJustStored() {
        Label label = new Label("8.0 pt");
        sceneWith(label, 200, 12);
        assertEquals(List.of("8.0 pt"), label.displayedLines());

        label.setText("8.8 pt"); // identical width under RULER: the repaint-only path
        assertEquals("8.8 pt", label.text());
        assertEquals(List.of("8.8 pt"), label.displayedLines(),
                "the label is still painting the text it was told to replace");
    }

    /** The two paths have to agree, or a text set one way would break differently. */
    @Test
    void theRepaintOnlyPathEllipsizesLikeALayoutWould() {
        Label label = new Label("0123456789ABCDEF");
        sceneWith(label, 95, 12);
        List<String> afterLayout = List.copyOf(label.displayedLines());

        Label other = new Label("****************"); // same 16 glyphs, same width
        sceneWith(other, 95, 12);
        other.setText("0123456789ABCDEF");
        assertEquals(afterLayout, other.displayedLines());
    }

    @Test
    void textThatFitsIsNotTouched() {
        Label label = new Label("12345");
        sceneWith(label, 200, 12);
        assertEquals(List.of("12345"), label.displayedLines());
    }

    @Test
    void overflowIsEllipsizedByRealMeasurement() {
        // 16 glyphs = 160pt in a 95pt box; "…" costs 10pt → 85pt budget →
        // 8 glyphs (80pt) survive.
        Label label = new Label("0123456789ABCDEF");
        sceneWith(label, 95, 12);
        assertEquals(List.of("01234567…"), label.displayedLines());
    }

    @Test
    void boxNarrowerThanTheEllipsisShowsJustTheEllipsis() {
        Label label = new Label("0123456789");
        sceneWith(label, 8, 12);
        assertEquals(List.of("…"), label.displayedLines());
    }

    @Test
    void clipOverflowKeepsTheFullText() {
        Label label = new Label("0123456789ABCDEF");
        label.setOverflow(Label.Overflow.CLIP);
        sceneWith(label, 95, 12);
        assertEquals(List.of("0123456789ABCDEF"), label.displayedLines());
    }

    @Test
    void surrogatePairsAreNeverSplitByTheEllipsis() {
        // "𝔸" is one glyph (two chars): 4 glyphs of budget must keep pairs whole.
        Label label = new Label("𝔸𝔸𝔸𝔸𝔸𝔸𝔸𝔸");
        sceneWith(label, 55, 12); // budget 45 → 4 code points
        assertEquals(List.of("𝔸𝔸𝔸𝔸…"), label.displayedLines());
    }

    @Test
    void wrapBreaksOnSpacesGreedily() {
        Label label = new Label("aaa bbb ccc");
        label.setWrap(true);
        sceneWith(label, 100, 100);
        // "aaa bbb" = 7 glyphs = 70pt fits; adding " ccc" = 110pt overflows.
        assertEquals(List.of("aaa bbb", "ccc"), label.displayedLines());
    }

    @Test
    void wrapMeasuresHeightByLineCount() {
        Label label = new Label("aaa bbb ccc");
        label.setWrap(true);
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        Size size = label.measure(Constraints.loose(100, 1000));
        assertEquals(24, size.height(), 1e-3, "two lines x lineHeight 12");
        assertEquals(70, size.width(), 1e-3, "widest line");
    }

    @Test
    void overlongWordsHardBreak() {
        Label label = new Label("abcdefghijkl");
        label.setWrap(true);
        sceneWith(label, 50, 100);
        assertEquals(List.of("abcde", "fghij", "kl"), label.displayedLines());
    }

    // ------------------------------------------------- shaped wrap and shaped ellipsis

    @Test
    void aTrailingSpaceHangsPastTheMarginInsteadOfBreakingTheLine() {
        // metrics().width() keeps a trailing space's advance, exactly as measure() does and
        // deliberately so, which means the fit test has to measure the TRIMMED candidate instead.
        // "aaa bbb" is 70pt of ink and 80pt of string; without the trim the space at index 7 —
        // which is never drawn and never seen — spends the budget, the line breaks after "aaa",
        // and a reader sees a ragged margin with room to spare beside it.
        Label label = new Label("aaa bbb ccc");
        label.setWrap(true);
        sceneWith(label, 75, 100);
        assertEquals(List.of("aaa bbb", "ccc"), label.displayedLines());

        Label measured = new Label("aaa bbb ccc");
        measured.setWrap(true);
        Scene scene = new Scene(measured);
        scene.setTextRuler(RULER);
        assertEquals(70, measured.measure(Constraints.loose(75, 1000)).width(), 1e-3,
                "the trimmed width, not 80: the space it broke at is not part of the line");
    }

    @Test
    void aWrappedLineDoesNotCarryTheSpaceItBrokeAt() {
        // The companion to the case above, on ordinary prose: a line that ends in the space that
        // caused its break shifts a CENTER- or END-aligned line by a space that is not there.
        Label label = new Label("the quick brown fox jumps over the lazy dog");
        label.setWrap(true);
        sceneWith(label, 155, 100);
        assertTrue(label.displayedLines().size() > 1, "the fixture has to actually wrap");
        for (String line : label.displayedLines()) {
            assertFalse(line.endsWith(" "), "a wrapped line kept the space it broke at: " + line);
        }
    }

    @Test
    void unspacedCjkWrapsAtEveryIdeographWithoutTheHardBreak() {
        // Every ideograph is a break opportunity in every locale — measured, not assumed — so
        // unspaced CJK goes through the same greedy walk as prose. It used to wrap only because a
        // "word" wider than the box was cut per code point, and that loop is gone; this is the
        // case that would stop wrapping altogether if the walk did not answer it.
        Label label = new Label(CJK);
        label.setWrap(true);
        sceneWith(label, 30, 100);
        assertEquals(List.of(CJK.substring(0, 3), CJK.substring(3)), label.displayedLines());

        Label measured = new Label(CJK);
        measured.setWrap(true);
        Scene scene = new Scene(measured);
        scene.setTextRuler(RULER);
        Size size = measured.measure(Constraints.loose(30, 1000));
        assertEquals(30, size.width(), 1e-3, "the widest line, three ideographs");
        assertEquals(24, size.height(), 1e-3, "two lines x lineHeight 12");
    }

    @Test
    void thaiWrapsUnderAThaiLocaleAndFallsBackToTheClusterCutUnderOthers() {
        // The JDK's Thai dictionary is reachable ONLY through a Thai locale, and that is the whole
        // reason the break iterator takes I18n.locale() rather than Locale.ROOT: under th these
        // twelve characters break at 6 and 9, and under en they offer no opportunity at all.
        // A Thai UI is exactly when Thai text is on screen, so the string and the rule that breaks
        // it come from one source.
        I18n.setLocale(Locale.forLanguageTag("th"));
        Label thai = new Label(THAI);
        thai.setWrap(true);
        sceneWith(thai, 100, 100);
        assertEquals(List.of(THAI.substring(0, 9), THAI.substring(9)), thai.displayedLines(),
                "the dictionary boundary at 9, not the ten clusters that fit");

        // Under a locale with no rule for the script the walk finds nothing and falls to the
        // cluster cut — character by character, which is precisely what the old hard break did.
        // No regression, and the fix is a locale away.
        I18n.setLocale(Locale.ENGLISH);
        Label english = new Label(THAI);
        english.setWrap(true);
        sceneWith(english, 100, 100);
        assertEquals(List.of(THAI.substring(0, 10), THAI.substring(10)),
                english.displayedLines(), "fitEnd's cut, one cluster past the word boundary");
    }

    @Test
    void aLeadingSpaceDoesNotBecomeABlankFirstLine() {
        // " abcdefgh ij" offers a break opportunity at index 1, so the first segment the greedy
        // walk takes is the lone leading space — and trimming it leaves nothing. Emitting that
        // nothing painted a blank row with a full lineHeight in it: every line of text pushed
        // down one, and a measured height one row taller than the text needs. The pre-shaping
        // algorithm never saw this because split(" ", -1) dropped its own empty tokens; the walk
        // has to drop them deliberately.
        Label label = new Label(" abcdefgh ij");
        label.setWrap(true);
        sceneWith(label, 45, 100);
        assertEquals(List.of("abcd", "efgh", "ij"), label.displayedLines(),
                "no blank line in front of the text");

        Label measured = new Label(" abcdefgh ij");
        measured.setWrap(true);
        Scene scene = new Scene(measured);
        scene.setTextRuler(RULER);
        Size size = measured.measure(Constraints.loose(45, 1000));
        assertEquals(36, size.height(), 1e-3, "three lines x lineHeight 12, not four");
        assertEquals(40, size.width(), 1e-3, "the widest line is four glyphs");
    }

    @Test
    void theFirstWrappedLineIsPaintedOnTheFirstRow() {
        // The half of the defect a reader actually sees. displayedLines() is a list and an empty
        // entry in it looks harmless; onPaint advances the baseline by a whole lineHeight for it
        // all the same, so every line of text is drawn one row lower than the box was sized for
        // and the last one falls out the bottom. Recorded from the paint rather than inferred,
        // because the list and the rows are two different things and only the rows are the bug.
        Label label = new Label(" abcdefgh ij");
        label.setWrap(true);
        Scene scene = sceneWith(label, 45, 36);
        BaselineRecorder canvas = new BaselineRecorder(45, 36);
        scene.renderFrame(canvas);

        // vAlign is CENTER and the box is exactly three rows, so the text starts at the top:
        // ascent 8 into the first 12pt row, then one row per line.
        assertEquals(List.of("abcd@8.0", "efgh@20.0", "ij@32.0"), canvas.drawn);
    }

    @Test
    void aParagraphOfNothingButWhitespaceStillMeasuresOneLine() {
        // The partner the skip needs. Every segment here is whitespace, so every one of them is
        // skipped and the walk emits nothing at all — but onMeasure takes the height straight
        // from the line count and has no floor of its own, so a Label of spaces would collapse to
        // zero height and stop reserving the row a caller sized around it expects.
        Label label = new Label("     ");
        label.setWrap(true);
        sceneWith(label, 45, 100);
        assertEquals(List.of(""), label.displayedLines());

        Label measured = new Label("     ");
        measured.setWrap(true);
        Scene scene = new Scene(measured);
        scene.setTextRuler(RULER);
        Size size = measured.measure(Constraints.loose(45, 1000));
        assertEquals(12, size.height(), 1e-3, "one line x lineHeight 12");
        assertEquals(0, size.width(), 1e-3, "an empty line has no width");
    }

    @Test
    void noWrappedLineIsEverEmptyUnlessThereIsNothingToDraw() {
        // The invariant behind the three cases above, swept rather than sampled, because the shape
        // of the defect is "which break opportunity the budget happens to land on" and that is a
        // relationship between the text and the width, not a property of either. A blank line is
        // only ever the right answer for a paragraph with no ink in it at all.
        //
        // Fixed seed: this is a net, not a source of new numbers, and a suite that fails on a
        // different input every run is a suite nobody can bisect. The alphabet is the one that
        // makes the case reachable — letters, spaces, and a tab, which is whitespace AND its own
        // break opportunity, so segments that trim to nothing turn up on their own.
        Random random = new Random(7);
        char[] alphabet = {'a', 'b', 'c', ' ', ' ', '\t'};
        for (int trial = 0; trial < 4000; trial++) {
            StringBuilder builder = new StringBuilder();
            for (int i = 1 + random.nextInt(14); i > 0; i--) {
                builder.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String text = builder.toString();
            Label label = new Label(text);
            label.setWrap(true);
            sceneWith(label, 10 + 5 * random.nextInt(20), 200);

            List<String> drawn = label.displayedLines();
            if (text.isBlank()) {
                assertEquals(List.of(""), drawn, "a blank paragraph is exactly one empty line");
            } else {
                assertFalse(drawn.contains(""),
                        "a whitespace-only segment became a blank row: [" + text + "] -> " + drawn);
            }
        }
    }

    @Test
    void theEllipsisIsCutLogicallyAndDrawnOnTheVisualLeftOfARightToLeftLine() {
        // Logical order in, visual position out. The characters an ellipsis drops are the
        // logically last ones, whatever end of the line they happened to be drawn at, so the
        // ellipsis is logically last too — and in a right-to-left paragraph that puts it on the
        // visual LEFT with no branch anywhere. It falls out of shaping the concatenation; a
        // toolkit that positions two pieces instead has to know which end to put it at, and gets
        // it wrong for exactly the paragraphs nobody on the team reads.
        Font f = Font.of(12);
        ShapedText rtl = RULER.shape(HEBREW + "abcdef", f);
        assertEquals(ShapedText.Direction.RTL, rtl.baseDirection(), "first strong char is Hebrew");

        ShapedText shown = Label.ellipsize(rtl, 55, RULER);
        assertEquals(HEBREW + "a" + "…", shown.text(),
                "cut in logical order: the last five characters go, wherever they were drawn");
        assertEquals(50, shown.metrics().width(), 1e-3);
        // The ellipsis is the LAST character of the string and the FIRST box on the line.
        assertEquals(0, shown.selection(4, 5).get(0).x0(), 1e-3);

        // The mirror, so the assertion above cannot pass by accident: the same budget over a
        // left-to-right paragraph cuts the same number of characters and draws the ellipsis at
        // the other end of the line.
        ShapedText ltr = RULER.shape("abcdef" + HEBREW, f);
        ShapedText shownLtr = Label.ellipsize(ltr, 55, RULER);
        assertEquals("abcd…", shownLtr.text());
        assertEquals(40, shownLtr.selection(4, 5).get(0).x0(), 1e-3);
    }

    @Test
    void theEllipsisCutIsNeverWiderThanTheBox() {
        // fitEnd says WHERE to cut, against the budget of the UNCUT shaping; it never says what
        // the cut will cost. Re-shaping the kept prefix beside an ellipsis can join, ligate or
        // kern differently and come out wider than the budget promised, and a Label that
        // overflows its box by a hair is a Label whose ellipsis is clipped. JOINING_RULER makes
        // that happen on demand: the ellipsis costs 10 alone and 20 with anything before it.
        Label label = new Label("0123456789");
        Scene scene = new Scene(label);
        scene.setTextRuler(JOINING_RULER);
        scene.layoutPass(55, 12);
        // Budget 45 admits four glyphs, but "0123…" re-shapes to 60 in a 55pt box, so the loop
        // steps back one cluster. Under the plain RULER the answer would be "0123…".
        assertEquals(List.of("012…"), label.displayedLines());
        assertTrue(JOINING_RULER.shape("012…", label.resolvedFont()).metrics().width() <= 55,
                "the line that is drawn has to fit the box it is drawn in");
    }

    @Test
    void noWrapMeasureIsTextWidthClampedByConstraints() {
        Label label = new Label("0123456789"); // 100pt
        Scene scene = new Scene(label);
        scene.setTextRuler(RULER);
        assertEquals(100, label.measure(Constraints.loose(500, 50)).width(), 1e-3);
        label.markNeedsLayout();
        assertEquals(60, label.measure(Constraints.loose(60, 50)).width(), 1e-3);
    }

    // ---------------------------------------------------------------- size steps

    @Test
    void theIconTakesTheTabledBoxAndTheLabelGap() {
        // MEDIUM: iconBox 18 + gapLabel 6 + 2 glyphs at 0.6 x 14. The box is an integer at
        // MEDIUM, and every cell of the ICON_BOX row is an even integer, so a centred icon
        // square lands on the pixel grid at every step.
        //
        // The box drives the WIDTH only: there it displaces text. On the height axis the rule makes
        // it overhang instead (see theIconOverhangsTheRowInsteadOfGrowingIt).
        Label label = new Label("ab").setIcon(ICON);
        scaledScene(label);
        Size size = measured(label);
        assertEquals(SizeTokens.MEDIUM.iconBox() + 6 + 16.8f, size.width(), 1e-3);
        assertEquals(1.171875f * 14, size.height(), 1e-3, "the line box, exactly as with no icon");
    }

    @Test
    void theIconBoxAndTheGapFollowTheStep() {
        Label label = new Label("ab").setIcon(ICON);
        Scene scene = scaledScene(label);

        scene.setControlSize(ControlSize.XSMALL);   // iconBox 14, gapLabel 4, body 11
        Size xs = measured(label);
        assertEquals(SizeTokens.of(ControlSize.XSMALL).iconBox() + 4 + 13.2f, xs.width(), 1e-3);
        assertEquals(1.171875f * 11, xs.height(), 1e-3, "the 14 box overhangs the 12.890625 line");

        scene.setControlSize(ControlSize.XLARGE);   // iconBox 24, gapLabel 10, body 19
        Size xl = measured(label);
        assertEquals(SizeTokens.of(ControlSize.XLARGE).iconBox() + 10 + 22.8f, xl.width(), 1e-3);
        assertEquals(1.171875f * 19, xl.height(), 1e-3);
    }

    @Test
    void theIconOverhangsTheRowInsteadOfGrowingIt() {
        // The whole point: a bare Label and an icon Label measure the SAME height, so a form
        // column mixing them stops being ragged. The icon square is deliberately bigger than the
        // line box (ICON_OPTICAL_BUMP exists because icon glyphs have no ascender slack), so
        // the difference is paid as an overhang, half of it above the row and half below.
        Label bare = new Label("ab");
        Label withIcon = new Label("ab").setIcon(ICON);
        Scene bareScene = scaledScene(bare);
        Scene scene = scaledScene(withIcon);
        assertEquals(measured(bare).height(), measured(withIcon).height(), 1e-3);

        // Laid out at exactly the measured row, the MEDIUM 18 box hangs (18 - 16.40625)/2 out
        // of each edge, and paintOutset must declare it or partial rendering leaves a stale
        // crescent of the old glyph behind.
        scene.layoutPass(200, 1.171875f * 14);
        assertEquals((SizeTokens.MEDIUM.iconBox() - 1.171875f * 14) / 2,
                withIcon.paintOutset(), 1e-3);

        // Nothing to declare once the parent gives the row more height than the icon square.
        scene.layoutPass(200, 40);
        assertEquals(0, withIcon.paintOutset(), 1e-3);

        bareScene.layoutPass(200, 1.171875f * 14);
        assertEquals(0, bare.paintOutset(), 1e-3, "no icon, no ink outside the box");
    }

    @Test
    void theLabelStaysContentTightAtEveryStep() {
        // No control-height floor and no padding: the height is exactly the line box, so a
        // Label in a Row lets the Button beside it set the row height.
        Label label = new Label("ab");
        Scene scene = scaledScene(label);
        assertEquals(1.171875f * 14, measured(label).height(), 1e-3);
        scene.setControlSize(ControlSize.XSMALL);
        assertEquals(1.171875f * 11, measured(label).height(), 1e-3);
        scene.setControlSize(ControlSize.XLARGE);
        assertEquals(1.171875f * 19, measured(label).height(), 1e-3);
    }

    @Test
    void theRolePicksTheTokenAndTheStepPicksItsSize() {
        Label label = new Label("ab").setRole(Label.Role.TITLE);
        Scene scene = scaledScene(label);
        assertEquals(20, label.resolvedFont().size(), 1e-3, "MEDIUM title");
        assertEquals(1.171875f * 20, measured(label).height(), 1e-3);

        scene.setControlSize(ControlSize.LARGE);
        assertEquals(24, label.resolvedFont().size(), 1e-3, "LARGE title");
        assertEquals(1.171875f * 24, measured(label).height(), 1e-3);

        label.setRole(Label.Role.LABEL);
        assertEquals(14, label.resolvedFont().size(), 1e-3, "LARGE label token");
    }

    @Test
    void anExplicitFontBeatsBothTheRoleAndTheStep() {
        // The escape hatch, and the reason Role exists: pinning 14pt inside an XLARGE
        // subtree must also stop the icon growing to the XLARGE box of 24.
        Label label = new Label("ab").setRole(Label.Role.TITLE).setIcon(ICON).setFont(Font.of(14));
        Scene scene = scaledScene(label);
        scene.setControlSize(ControlSize.XLARGE);
        assertEquals(14, label.resolvedFont().size(), 1e-3);
        Size size = measured(label);
        // The box shows up on the width axis only: the height is the pinned font's
        // line box either way, which is exactly why the width assertion carries the pin now.
        assertEquals(18.40625f + 10 + 16.8f, size.width(), 1e-3,
                "font-derived box, not the XLARGE 24; but the gap is still XLARGE");
        assertEquals(1.171875f * 14, size.height(), 1e-3);

        label.setFont(null).setRole(Label.Role.BODY); // releases the pin
        assertEquals(19, label.resolvedFont().size(), 1e-3);
        Size released = measured(label);
        assertEquals(SizeTokens.of(ControlSize.XLARGE).iconBox() + 10 + 22.8f,
                released.width(), 1e-3, "back on the tabled XLARGE box");
        assertEquals(1.171875f * 19, released.height(), 1e-3);
    }

    @Test
    void aRoleWithNoTabledBoxDerivesTheIconFromItsOwnFont() {
        // The tabled box is the body square; handing it to a 20pt title would leave the
        // icon visibly smaller than the words it labels. 25.4375 is what Dialog's
        // setFont(theme.title) produces today, so MEDIUM is unmoved either way.
        //
        // Read on the width axis: the derived box does not show in the height, so asserting
        // the height here would pass just as well with the 18 body square.
        Label label = new Label("ab").setRole(Label.Role.TITLE).setIcon(ICON);
        scaledScene(label);
        Size size = measured(label);
        assertEquals(1.171875f * 20 + Strokes.ICON_OPTICAL_BUMP + 6 + 24, size.width(), 1e-3);
        assertEquals(1.171875f * 20, size.height(), 1e-3, "the title's line box, icon or not");
    }

    @Test
    void baselineOffsetIsTheExpressionThePaintUses() {
        Label label = new Label("ab");
        Scene scene = new Scene(label);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(200, 40); // a box far taller than the 16.40625 line
        // CENTER: top = (40 - 16.40625) / 2, then ascent 0.927734375 x 14.
        assertEquals(11.796875f + 12.98828125f, label.baselineOffset(), 1e-3);

        label.setAlign(Label.HAlign.START, Label.VAlign.TOP);
        assertEquals(12.98828125f, label.baselineOffset(), 1e-3);
    }

    @Test
    void theDerivedFontIsIdentityStableSoTheGlyphCacheHits() {
        // Font.bold() allocates, and FontStore keys on identity: re-deriving per pass
        // missed the glyph memo on every measure, layout and paint.
        Label label = new Label("ab").setStrong(true).setItalic(true);
        Scene scene = scaledScene(label);
        Font first = label.resolvedFont();
        assertSame(first, label.resolvedFont());
        assertTrue(first.isBold() && first.isItalic());
        assertEquals(14, first.size(), 1e-3);

        scene.setControlSize(ControlSize.LARGE); // a new base must break the memo
        Font larger = label.resolvedFont();
        assertEquals(16, larger.size(), 1e-3);
        assertTrue(larger.isBold() && larger.isItalic());
        assertSame(larger, label.resolvedFont());
    }

    /**
     * Records what a wrapped Label actually draws and where, as {@code text@baseline}. The Label
     * is the only widget in these scenes and it draws nothing else, so every {@code drawText} is
     * a line of it; {@code translate} is a no-op on {@link FakeCanvas}, so the y that arrives is
     * already the baseline in the Label's own space.
     */
    private static final class BaselineRecorder extends FakeCanvas {

        private final List<String> drawn = new ArrayList<>();

        BaselineRecorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            drawn.add(text + "@" + y);
        }
    }
}
