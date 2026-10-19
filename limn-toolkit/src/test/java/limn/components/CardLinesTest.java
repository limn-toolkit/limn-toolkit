package limn.components;

import limn.accessibility.Accessible;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Card#setTitleLines(int)} and {@link Card#setSubtitleLines(int)}: a long name read whole on
 * a narrow card, the header growing to the lines it takes, one line and its ellipsis by default, and
 * a reader hearing the whole title either way. On the 10-points-a-glyph ruler, the header of a card
 * 300 wide with a prefix of 36 and a suffix of 70 leaves the text 138 points.
 */
class CardLinesTest extends AccessibleComponentTestBase {

    private static final SizeTokens T = SizeTokens.MEDIUM;
    private static final float PAD = T.cardPad();
    private static final float GAP = T.spacingMedium();
    private static final float LINE = 12; // the ruler's line height

    private Card card;

    /** A card 300 wide with a prefix, a title, a subtitle, a suffix and a content, bound. */
    private Card bound(Card.Style style) {
        card = new Card(new CardTest.Block(100, 20)).setStyle(style)
                .setHeaderPrefix(new CardTest.Block(36, 36))
                .setTitle("Harbor Lane Supplies")
                .setSubtitle("Customer since 2019")
                .setHeaderSuffix(new CardTest.Block(70, 20));
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(new SizedBox(300, SizedBox.UNSET, card));
        frame();
        return card;
    }

    private Label title() {
        return (Label) card.area().children().get(1);
    }

    private Label subtitle() {
        return (Label) card.area().children().get(2);
    }

    private static float y(Widget<?> w) {
        return w.localToScene(0, 0).y();
    }

    @Test
    void oneLineIsTheDefaultAndTheNameIsCutWithAnEllipsis() {
        bound(Card.Style.ELEVATED);
        assertEquals(1, card.titleLines());
        assertEquals(1, card.subtitleLines());
        assertEquals(List.of("Harbor Lane …"), title().displayedLines());
        assertEquals(LINE, title().height(), 0.01f);
    }

    @Test
    void twoTitleLinesReadTheNameWholeAndTheHeaderGrows() {
        bound(Card.Style.ELEVATED);
        float before = card.height();
        card.setTitleLines(2);
        frame();
        assertEquals(List.of("Harbor Lane", "Supplies"), title().displayedLines());
        assertEquals(2 * LINE, title().height(), 0.01f);
        assertEquals(y(title()) + 2 * LINE, y(subtitle()), 0.01f, "the subtitle under the title's two lines");
        assertEquals(before, card.height(), 0.01f, "three lines of text still fit the prefix's 36");
        assertEquals(2, card.titleLines());

        // A third line takes the text past the prefix, and the header, and the card, grow by it.
        card.setTitleLines(3).setTitle("Harbor Lane Supplies and Office Furniture");
        frame();
        assertEquals(3, title().displayedLines().size(), title().displayedLines().toString());
        assertEquals(before + (4 * LINE - 36), card.height(), 0.01f, "the card as much taller as the text");
    }

    @Test
    void aTitleLongerThanItsLinesEndsTheLastWithTheEllipsis() {
        bound(Card.Style.ELEVATED);
        card.setTitleLines(2).setTitle("Harbor Lane Supplies and Office Furniture");
        frame();
        List<String> lines = title().displayedLines();
        assertEquals(2, lines.size());
        assertEquals("Harbor Lane", lines.get(0));
        assertTrue(lines.get(1).endsWith("…"), lines.toString());
    }

    @Test
    void theLinesSetBeforeTheTitleApplyWhenItComes() {
        card = new Card(new CardTest.Block(100, 20)).setTitleLines(2).setSubtitleLines(3)
                .setHeaderPrefix(new CardTest.Block(36, 36))
                .setHeaderSuffix(new CardTest.Block(70, 20));
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(new SizedBox(300, SizedBox.UNSET, card));
        card.setTitle("Harbor Lane Supplies").setSubtitle("Customer since 2019, every Monday");
        frame();
        assertEquals(2, title().maxLines());
        assertEquals(3, subtitle().maxLines());
        assertEquals(List.of("Harbor Lane", "Supplies"), title().displayedLines());
    }

    @Test
    void twoSubtitleLinesBreakTheSubtitle() {
        bound(Card.Style.ELEVATED);
        card.setSubtitleLines(2);
        frame();
        assertEquals(List.of("Customer", "since 2019"), subtitle().displayedLines());
        assertEquals(1, title().displayedLines().size(), "the title keeps its one line");
    }

    @Test
    void rightToLeftTheLinesBreakTheSameAndStandAtTheRight() {
        card = new Card(new CardTest.Block(100, 20)).setTitleLines(2)
                .setHeaderPrefix(new CardTest.Block(36, 36))
                .setTitle("Harbor Lane Supplies")
                .setHeaderSuffix(new CardTest.Block(70, 20));
        card.setLayoutDirection(LayoutDirection.RTL);
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(new SizedBox(300, SizedBox.UNSET, card));
        frame();
        assertEquals(List.of("Harbor Lane", "Supplies"), title().displayedLines());
        assertEquals(PAD + 70 + GAP, title().localToScene(0, 0).x() - card.localToScene(0, 0).x(), 0.01f,
                "the text block between the suffix, trailing on the left, and the prefix on the right");
    }

    @Test
    void belowOneIsRefused() {
        Card fresh = new Card();
        assertThrows(IllegalArgumentException.class, () -> fresh.setTitleLines(0));
        assertThrows(IllegalArgumentException.class, () -> fresh.setSubtitleLines(0));
        assertEquals(1, fresh.titleLines());
        assertEquals(1, fresh.subtitleLines());
    }

    @Test
    void aReaderHearsTheWholeTitleWhateverTheLinesCut() {
        bound(Card.Style.ELEVATED);
        card.setTitleLines(2).setTitle("Harbor Lane Supplies and Office Furniture");
        frame();
        assertEquals("Harbor Lane Supplies and Office Furniture", node(Accessible.Role.GROUP).name(),
                describe(tree()));
        card.onActivate(() -> { });
        frame();
        assertEquals("Harbor Lane Supplies and Office Furniture", node(Accessible.Role.BUTTON).name(),
                describe(tree()));
    }
}
