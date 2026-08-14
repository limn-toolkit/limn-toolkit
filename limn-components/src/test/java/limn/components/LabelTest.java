package limn.components;

import limn.graphics.Font;
import limn.graphics.Icon;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ellipsis and wrap math against the deterministic 10pt-per-glyph {@link #RULER}; the
 * typography ramp against {@link #SCALED_RULER}, which is the only one whose vertical
 * metrics track the font (the flat ruler reports lineHeight 12 at every size, so a step
 * assertion made under it would pin a number the real renderer never produces).
 */
class LabelTest extends ComponentTestBase {

    /** Never rasterized: these tests measure and lay out, they never paint. */
    private static final Icon ICON = (pixelSize, dark) -> {
        throw new UnsupportedOperationException("measure-only");
    };

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
}
