package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Card} laid out (ADR 073 §2.3, §2.4, §2.9): every part optional and leaving no room or gap
 * when it is not set, the extra height to the content and the footer at the bottom, the media at
 * each fit in both orientations, an icon as media sized and centred, and every placement turned
 * round reading right to left.
 */
class CardTest extends AccessibleComponentTestBase {

    private static final SizeTokens T = SizeTokens.MEDIUM;
    private static final float PAD = T.cardPad();
    private static final float GAP = T.spacingMedium();
    private static final float W = 300;

    /** A box of a fixed size: what the parts are measured as. */
    static final class Block extends Widget<Block> {
        private final float w;
        private final float h;

        Block(float w, float h) {
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
        }
    }

    /** An icon that records where it was asked to paint. */
    static final class RecordingIcon implements Icon {
        final List<float[]> painted = new ArrayList<>();
        final List<Color> tints = new ArrayList<>();
        private final boolean tintable;

        RecordingIcon(boolean tintable) {
            this.tintable = tintable;
        }

        @Override
        public Image image(int pixelSize, boolean dark) {
            return new Image(1, 1, new byte[4]);
        }

        @Override
        public boolean tintable() {
            return tintable;
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark, boolean mirrored) {
            painted.add(new float[] {x, y, size});
            tints.add(tint);
        }
    }

    private Card bindCard(Card card) {
        bindMeasured(new SizedBox(W, SizedBox.UNSET, card));
        return card;
    }

    /**
     * Binds an empty window, gives it the test's ruler, and only then puts {@code content} in it:
     * a label measured once under the ruler a window starts with keeps that answer while its
     * constraints hold, and the card would lay out one part by one ruler and another by the other.
     */
    private void bindMeasured(Widget<?> content) {
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(content);
        frame();
    }

    private static float x(Widget<?> w) {
        return w.localToScene(0, 0).x();
    }

    private static float y(Widget<?> w) {
        return w.localToScene(0, 0).y();
    }

    @Test
    void aContentAloneSitsInsideThePaddingAndNothingElseTakesRoom() {
        Block content = new Block(100, 40);
        Card card = bindCard(new Card(content));
        assertEquals(PAD, x(content) - x(card), 0.01f);
        assertEquals(PAD, y(content) - y(card), 0.01f);
        assertEquals(W - 2 * PAD, content.width(), 0.01f, "as wide as the card inside its padding");
        assertEquals(40 + 2 * PAD, card.height(), 0.01f, "and nothing else is there");
    }

    @Test
    void eachPartTakesItsGapWhenSetAndGivesItBackWhenCleared() {
        Block content = new Block(100, 40);
        Card card = bindCard(new Card(content));
        float alone = card.height();

        card.setTitle("Harbor Lane Supplies");
        frame();
        float title = RULER.measure("Hg", T.body()).lineHeight();
        assertEquals(PAD + title + GAP, y(content) - y(card), 0.5f, "the title, then the gap");
        card.setSubtitle("Customer since 2019");
        frame();
        assertEquals(PAD + 2 * title + GAP, y(content) - y(card), 0.5f, "the subtitle under the title, no gap");

        Block media = new Block(60, 30);
        card.setMedia(media);
        frame();
        assertEquals(PAD, y(media) - y(card), 0.01f);
        assertEquals(PAD + 30 + GAP + 2 * title + GAP, y(content) - y(card), 0.5f);

        Block action = new Block(50, 24);
        card.setFooter(action);
        frame();
        assertEquals(y(card) + card.height() - PAD - 24, y(action), 0.5f, "the footer at the bottom");
        assertEquals(y(content) + 40 + GAP, y(action), 0.5f, "a gap under the content");

        card.setMedia((Widget<?>) null).setTitle((String) null).setSubtitle((String) null).setFooter();
        frame();
        assertEquals(alone, card.height(), 0.01f, "every part cleared, the card is as it was");
        assertEquals(PAD, y(content) - y(card), 0.01f);
    }

    @Test
    void aHiddenPartTakesNoRoomAndNoGap() {
        Block content = new Block(100, 40);
        Block media = new Block(60, 30);
        Block action = new Block(50, 24);
        Card card = bindCard(new Card(content).setTitle("Payroll").setMedia(media).setFooter(action));
        float all = card.height();
        media.setVisible(false);
        frame();
        assertEquals(all - 30 - GAP, card.height(), 0.5f, "the media hidden: its room and its gap go");
        action.setVisible(false);
        frame();
        assertEquals(all - 30 - GAP - 24 - GAP, card.height(), 0.5f, "a footer of hidden actions is no footer");
    }

    @Test
    void theExtraHeightGoesToTheContentAndFootersLineUpInARow() {
        Block shortContent = new Block(80, 20);
        Block tallContent = new Block(80, 90);
        Block left = new Block(40, 24);
        Block right = new Block(40, 24);
        Card a = new Card(shortContent).setTitle("Short").setFooter(left);
        Card b = new Card(tallContent).setTitle("Tall").setFooter(right);
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        row.add(Expanded.of(a, 1));
        row.add(Expanded.of(b, 1));
        bindMeasured(new SizedBox(380, SizedBox.UNSET, row));
        assertEquals(a.height(), b.height(), 0.01f, "stretched to one height");
        assertEquals(y(left), y(right), 0.01f, "the footers on one line");
        assertEquals(90, shortContent.height(), 0.5f, "the shorter card's content took the extra");
    }

    @Test
    void theMediaAtEachFitVertical() {
        Block media = new Block(60, 30);
        Card card = bindCard(new Card().setMedia(media).setMediaAspectRatio(2));
        assertEquals(PAD, x(media) - x(card), 0.01f, "natural: inside the padding, at the leading edge");
        assertEquals(60, media.width(), 0.01f);
        assertEquals(30, media.height(), 0.01f);

        card.setMediaFit(Card.MediaFit.STRETCH);
        frame();
        assertEquals(PAD, x(media) - x(card), 0.01f, "stretched: inside the padding");
        assertEquals(W - 2 * PAD, media.width(), 0.01f);
        assertEquals((W - 2 * PAD) / 2, media.height(), 0.01f, "its height from the ratio");

        card.setMediaFit(Card.MediaFit.COVER);
        frame();
        assertEquals(0, x(media) - x(card), 0.01f, "covering: out to the edges");
        assertEquals(0, y(media) - y(card), 0.01f);
        assertEquals(W, media.width(), 0.01f);
        assertEquals(W / 2, media.height(), 0.01f);
        assertEquals(W / 2, card.height(), 0.01f, "alone, it is the whole card: no padding under it");
    }

    @Test
    void theMediaAtEachFitHorizontal() {
        Block media = new Block(400, 200);
        Block content = new Block(50, 120);
        Card card = bindCard(new Card(content).setMedia(media).setOrientation(Card.Orientation.HORIZONTAL)
                .setMediaFit(Card.MediaFit.COVER));
        assertEquals(0, x(media) - x(card), 0.01f, "out to the leading edge");
        assertEquals(0, y(media) - y(card), 0.01f, "and to the top");
        assertEquals(W / 3, media.width(), 0.01f, "a third of the card at most");
        assertEquals(card.height(), media.height(), 0.01f, "and as tall as the card, with no footer");
        assertEquals(W / 3 + PAD, x(content) - x(card), 0.01f, "the text beside it, inside the padding");

        card.setMediaFit(Card.MediaFit.STRETCH);
        frame();
        assertEquals(PAD, x(media) - x(card), 0.01f);
        assertEquals(card.height() - 2 * PAD, media.height(), 0.01f, "the column's full inner height");
    }

    @Test
    void theFooterOfAHorizontalCardRunsTheWholeWidth() {
        Block media = new Block(400, 200);
        Block action = new Block(40, 24);
        Card card = bindCard(new Card(new Block(50, 60)).setMedia(media)
                .setOrientation(Card.Orientation.HORIZONTAL).setMediaFit(Card.MediaFit.COVER)
                .setFooter(action));
        Widget<?> bar = card.footerBar();
        assertEquals(PAD, x(bar) - x(card), 0.01f, "under the media as well as the text");
        assertEquals(W - 2 * PAD, bar.width(), 0.01f);
        assertTrue(y(media) + media.height() <= y(action) - GAP / 2 + 0.01f, "the picture ends above it");
        assertEquals(x(card) + W - PAD - 40, x(action), 0.01f, "the action at the trailing edge");
    }

    @Test
    void theHeaderCentresItsSidePartsOnTheTextAndTheTextTakesTheRest() {
        Block prefix = new Block(36, 36);
        Block suffix = new Block(70, 20);
        Card card = bindCard(new Card().setHeaderPrefix(prefix).setTitle("Harbor Lane Supplies")
                .setHeaderSuffix(suffix));
        assertEquals(PAD, x(prefix) - x(card), 0.01f);
        assertEquals(W - PAD - 70, x(suffix) - x(card), 0.01f);
        float middle = y(prefix) + 18;
        assertEquals(middle, y(suffix) + 10, 0.01f, "the suffix centred where the prefix is");
        Label title = (Label) card.area().children().get(1);
        assertEquals(PAD + 36 + GAP, x(title) - x(card), 0.01f);
        assertEquals(W - 2 * PAD - 36 - 70 - 2 * GAP, title.width(), 0.01f, "the text takes what is left");
        assertEquals(middle, y(title) + title.height() / 2, 0.5f);
    }

    @Test
    void rightToLeftTurnsEveryPlacementRound() {
        Block prefix = new Block(36, 36);
        Block suffix = new Block(70, 20);
        Block action = new Block(40, 24);
        Card card = new Card(new Block(50, 20)).setHeaderPrefix(prefix).setTitle("Title")
                .setHeaderSuffix(suffix).setFooter(action);
        card.setLayoutDirection(LayoutDirection.RTL);
        bindCard(card);
        assertEquals(W - PAD - 36, x(prefix) - x(card), 0.01f, "the prefix leads, on the right");
        assertEquals(PAD, x(suffix) - x(card), 0.01f, "the suffix trails, on the left");
        assertEquals(PAD, x(action) - x(card), 0.01f, "the action at the trailing edge, on the left");

        Block media = new Block(400, 200);
        card.setMedia(media).setOrientation(Card.Orientation.HORIZONTAL).setMediaFit(Card.MediaFit.COVER);
        frame();
        assertEquals(W - W / 3, x(media) - x(card), 0.01f, "the media column on the leading side, the right");
    }

    @Test
    void anIconAsMediaIsSizedAndCentredAsTheFitSays() {
        RecordingIcon icon = new RecordingIcon(true);
        Card card = bindCard(new Card().setMedia(icon).setTitle("Payroll"));
        float[] at = icon.painted.get(icon.painted.size() - 1);
        assertEquals(2 * T.iconBox(), at[2], 0.01f, "natural: twice the step's icon box");
        assertEquals(x(card) + PAD, at[0], 0.01f);
        assertSame(Theme.current().primary(), icon.tints.get(icon.tints.size() - 1), "tinted in the accent");

        card.setMediaFit(Card.MediaFit.STRETCH).setMediaAspectRatio(3);
        icon.painted.clear();
        frame();
        scene.root().invalidate();
        frame();
        at = icon.painted.get(icon.painted.size() - 1);
        float boxW = W - 2 * PAD;
        float boxH = boxW / 3;
        float side = Math.round(boxH * 0.4f);
        assertEquals(side, at[2], 0.01f, "four tenths of the shorter side, a whole point");
        assertEquals(x(card) + PAD + (boxW - side) / 2, at[0], 0.01f, "centred across");
        assertEquals(y(card) + PAD + (boxH - side) / 2, at[1], 0.01f, "and down");

        RecordingIcon authored = new RecordingIcon(false);
        card.setMedia(authored);
        frame();
        assertNull(authored.tints.get(authored.tints.size() - 1), "an icon that is not tintable is drawn as authored");
    }

    @Test
    void aWidgetSetAsMediaReplacesTheIconAndTheOtherWayRound() {
        RecordingIcon icon = new RecordingIcon(true);
        Block picture = new Block(40, 40);
        Card card = bindCard(new Card().setMedia(icon));
        card.setMedia(picture);
        assertNull(card.mediaIcon());
        assertSame(picture, card.media());
        card.setMedia(icon);
        assertNull(card.media());
        assertNull(picture.parent(), "the widget is given back");
    }
}
