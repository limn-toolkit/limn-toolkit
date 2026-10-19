package limn.components;

import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The footer of a {@link Card} that is clicked is a band of its own under the divider, padded as the
 * card is on all four sides: the actions a card padding under the divider, from the card's leading
 * and trailing edges and from its bottom, at every size step and both ways round. The divider and the
 * end of the part that is clicked are one line, half a gap under the last part as before. A card that
 * is not clicked keeps its footer as its last part, a gap under the one before.
 */
class CardFooterBandTest extends AccessibleComponentTestBase {

    private static final float W = 320;

    private Card card;
    private Widget<?> content;

    private void bindCard(ControlSize step, LayoutDirection direction, boolean clicked) {
        bindCard(step, direction, clicked, SizedBox.UNSET);
    }

    private void bindCard(ControlSize step, LayoutDirection direction, boolean clicked, float height) {
        content = new CardTest.Block(100, 30);
        card = new Card(content).setTitle("Invoice 1042").setFooter(new CardTest.Block(60, 24));
        if (clicked) {
            card.onActivate(() -> { });
        }
        card.setControlSize(step);
        card.setLayoutDirection(direction);
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(new SizedBox(W, height, card));
        frame();
    }

    private static float y(Widget<?> w) {
        return w.localToScene(0, 0).y();
    }

    private static float x(Widget<?> w) {
        return w.localToScene(0, 0).x();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ControlSize.class)
    void theBandIsPaddedAlikeOnAllFourSidesLeftToRight(ControlSize step) {
        theBandIsPaddedAlike(step, LayoutDirection.LTR);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ControlSize.class)
    void theBandIsPaddedAlikeOnAllFourSidesRightToLeft(ControlSize step) {
        theBandIsPaddedAlike(step, LayoutDirection.RTL);
    }

    private void theBandIsPaddedAlike(ControlSize step, LayoutDirection direction) {
        bindCard(step, direction, true);
        SizeTokens t = SizeTokens.of(step);
        float pad = t.cardPad();
        Widget<?> bar = card.footerBar();
        float divider = y(card) + card.area().height();
        assertEquals(pad, y(bar) - divider, 0.01f, "the band's top, from the divider to the actions");
        assertEquals(pad, x(bar) - x(card), 0.01f, "its leading side, in line with the text above");
        assertEquals(pad, x(card) + card.width() - x(bar) - bar.width(), 0.01f, "its trailing side");
        assertEquals(pad, y(card) + card.height() - y(bar) - bar.height(), 0.01f, "its bottom");
        assertEquals(x(content), x(bar), 0.01f, "the actions' edge on the text's edge");
        assertEquals(t.spacingMedium() / 2, divider - (y(content) + content.height()), 0.01f,
                "the divider, which is where the part that is clicked ends, half a gap under the content");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ControlSize.class)
    void aCardNotClickedKeepsItsFooterAGapUnderTheContent(ControlSize step) {
        bindCard(step, LayoutDirection.LTR, false);
        SizeTokens t = SizeTokens.of(step);
        Widget<?> bar = card.footerBar();
        assertEquals(t.spacingMedium(), y(bar) - (y(content) + content.height()), 0.01f);
        assertEquals(t.cardPad(), y(card) + card.height() - y(bar) - bar.height(), 0.01f);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ControlSize.class)
    void aCardGivenMoreHeightKeepsTheBandAsItIs(ControlSize step) {
        bindCard(step, LayoutDirection.LTR, true, 260);
        float pad = SizeTokens.of(step).cardPad();
        assertEquals(260, card.height(), 0.01f, "the fixture gives the card more than it needs");
        Widget<?> bar = card.footerBar();
        assertEquals(pad, y(bar) - (y(card) + card.area().height()), 0.01f);
        assertEquals(pad, y(card) + card.height() - y(bar) - bar.height(), 0.01f);
    }
}
