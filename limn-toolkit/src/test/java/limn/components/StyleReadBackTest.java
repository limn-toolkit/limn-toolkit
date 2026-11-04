package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Ink;
import limn.graphics.LinearGradient;
import limn.graphics.ParagraphStyle;
import limn.graphics.ShapedText;
import limn.graphics.StyledLayout;
import limn.graphics.StyledText;
import limn.graphics.TextStyle;
import limn.i18n.I18nString;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Styles read back from outside the graphics package, as an application inspecting or saving them
 * would: what a style sets, and nothing where it inherits; and inks of equal paints equal.
 */
class StyleReadBackTest extends ComponentTestBase {

    private static final Color RED = new Color(1, 0, 0, 1);
    private static final Color BLUE = new Color(0, 0, 1, 1);

    @Test
    void aTextStyleReadsBackWhatItSetsAndNothingWhereItInherits() {
        TextStyle none = TextStyle.of();
        assertNull(none.family());
        assertTrue(Float.isNaN(none.size()));
        assertTrue(Float.isNaN(none.scale()));
        assertEquals(0, none.weight());
        assertFalse(none.isAtLeastBold());
        assertNull(none.isItalic());
        assertNull(none.ink());
        assertNull(none.background());
        assertNull(none.underlineKind());
        assertNull(none.underlineInk());
        assertNull(none.isStruckThrough());
        assertNull(none.strikethroughInk());
        assertNull(none.baseline());
        assertTrue(Float.isNaN(none.tracking()));

        TextStyle all = TextStyle.of().family("Serif").size(14).scale(1.5f).weight(300).italic(false)
                .ink(RED).background(BLUE).underline(TextStyle.Underline.WAVY, BLUE)
                .strikethrough(RED).superscript().tracking(0.1f);
        assertEquals("Serif", all.family());
        assertEquals(14, all.size());
        assertEquals(1.5f, all.scale());
        assertEquals(300, all.weight());
        assertEquals(Boolean.FALSE, all.isItalic(), "upright set, not inherited");
        assertEquals(RED, all.ink());
        assertEquals(BLUE, all.background());
        assertEquals(TextStyle.Underline.WAVY, all.underlineKind());
        assertEquals(BLUE, all.underlineInk());
        assertEquals(Boolean.TRUE, all.isStruckThrough());
        assertEquals(RED, all.strikethroughInk());
        assertEquals(TextStyle.Baseline.SUPERSCRIPT, all.baseline());
        assertEquals(0.1f, all.tracking());

        TextStyle bold = TextStyle.of().bold();
        assertEquals(Font.BOLD, bold.weight());
        assertTrue(bold.isAtLeastBold());
        assertEquals(TextStyle.Underline.NONE, TextStyle.of().underline(TextStyle.Underline.NONE)
                .underlineKind(), "taking an underline away is set, not inherited");
    }

    @Test
    void aParagraphStyleReadsBackWhatItSets() {
        ParagraphStyle none = ParagraphStyle.of();
        assertNull(none.alignment());
        assertEquals(0, none.startIndent());
        assertEquals(1, none.lineSpacing());
        assertNull(none.list());
        assertNull(none.direction());
        assertEquals(TextStyle.of(), none.textStyle());

        TextStyle heading = TextStyle.of().bold();
        ParagraphStyle all = ParagraphStyle.of().alignment(ParagraphStyle.Alignment.CENTER)
                .indent(12, 8).firstLineIndent(4).space(6, 10).lineSpacing(1.25f)
                .list(ParagraphStyle.ListMarker.NUMBER, 1).listNumber(7)
                .direction(ShapedText.Direction.RTL).text(heading);
        assertEquals(ParagraphStyle.Alignment.CENTER, all.alignment());
        assertEquals(12, all.startIndent());
        assertEquals(8, all.endIndent());
        assertEquals(4, all.firstLineIndent());
        assertEquals(6, all.spaceBefore());
        assertEquals(10, all.spaceAfter());
        assertEquals(1.25f, all.lineSpacing());
        assertEquals(ParagraphStyle.ListMarker.NUMBER, all.list());
        assertEquals(1, all.level());
        assertEquals(7, all.listNumber());
        assertEquals(ShapedText.Direction.RTL, all.direction());
        assertSame(heading, all.textStyle());
    }

    @Test
    void aSpanReadsBackItsTextAsItReadsNowAndItsStyle() {
        TextStyle italic = TextStyle.of().italic();
        StyledText.Span word = StyledText.span("whole", italic);
        assertEquals("whole", word.text());
        assertSame(italic, word.style());
        assertEquals("7", StyledText.span(7, italic).text());
        assertEquals("piece", StyledText.span(I18nString.literal("piece"), italic).text());
    }

    @Test
    void inksOfEqualPaintsAreEqualSoStylesBuiltAlikeAre() {
        LinearGradient fade = new LinearGradient(0, 0, 10, 0, RED, BLUE);
        assertEquals(Ink.of(fade), Ink.of(new LinearGradient(0, 0, 10, 0, RED, BLUE)));
        assertEquals(Ink.of(fade).hashCode(), Ink.of(new LinearGradient(0, 0, 10, 0, RED, BLUE)).hashCode());
        assertNotEquals(Ink.of(fade), Ink.of(new LinearGradient(0, 0, 20, 0, RED, BLUE)));
        assertSame(RED, Ink.of(RED), "a colour is its own ink");
        assertEquals(TextStyle.of().ink(Ink.of(fade)),
                TextStyle.of().ink(Ink.of(new LinearGradient(0, 0, 10, 0, RED, BLUE))));
    }

    @Test
    void aLabelGivenStyledTextEqualToItsOwnKeepsItAndItsLayout() {
        StyledText first = StyledText.of("Overdue",
                TextStyle.of().ink(Ink.of(new LinearGradient(0, 0, 10, 0, RED, BLUE))));
        StyledText again = StyledText.of("Overdue",
                TextStyle.of().ink(Ink.of(new LinearGradient(0, 0, 10, 0, RED, BLUE))));
        Label label = new Label("").setText(first);
        label.measure(limn.scene.Constraints.loose(500, 500));
        label.setText(again);
        assertSame(first, label.styledText(), "the equal text is taken as no change");
    }

    @Test
    void aLayoutsBoxWidthIsItsLimitOrItsWidestLine() {
        StyledText text = StyledText.of("aaa bb");
        StyledLayout limited = StyledLayout.builder(text, Font.of(10), RED).maxWidth(200)
                .build(TestRulers.FIXED);
        assertEquals(200, limited.boxWidth());
        StyledLayout free = StyledLayout.builder(text, Font.of(10), RED).build(TestRulers.FIXED);
        assertEquals(free.width(), free.boxWidth());
        assertEquals(60, free.boxWidth(), "six characters of ten points");
    }
}
