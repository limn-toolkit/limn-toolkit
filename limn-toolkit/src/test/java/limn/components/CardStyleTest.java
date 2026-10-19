package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each of a {@link Card}'s four looks paints of its own: the raised one a shadow and the
 * surface, the outlined one the surface and a line, the filled one the surface and nothing else,
 * and the plain one nothing at all, while a plain card that is clicked keeps its hover and its ring.
 */
class CardStyleTest extends ComponentTestBase {

    /** Every rounded fill, every rounded stroke and every shadow a frame asks for. */
    private static final class Painted extends FakeCanvas {
        final List<Paint> fills = new ArrayList<>();
        final List<Paint> strokes = new ArrayList<>();
        int shadows;

        Painted() {
            super(640, 480);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            fills.add(paint);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            strokes.add(paint);
        }

        @Override
        public void drawShadow(RoundRect shape, Shadow shadow) {
            shadows++;
        }
    }

    private final AtomicLong nanos = new AtomicLong();
    private Scene scene;
    private Card card;

    private Painted frame(Card.Style style, boolean clicked) {
        card = new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042").setStyle(style);
        if (clicked) {
            card.onActivate(() -> { });
        }
        Column column = new Column();
        column.add(new SizedBox(300, SizedBox.UNSET, card));
        scene = new Scene(new Padding(Insets.all(40), column), nanos::get);
        scene.setTextRuler(RULER);
        return render();
    }

    /** Every frame until the scene comes to rest, what they painted put together. */
    private Painted render() {
        Painted canvas = new Painted();
        for (int i = 0; i < 40; i++) {
            nanos.addAndGet(50_000_000L);
            scene.renderFrame(canvas);
        }
        return canvas;
    }

    private static boolean has(List<Paint> paints, Color color) {
        for (Paint paint : paints) {
            if (paint instanceof Color c && c.r() == color.r() && c.g() == color.g() && c.b() == color.b()
                    && c.a() > 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theRaisedCardCastsAShadowUnderTheSurface() {
        Theme theme = Theme.current();
        Painted painted = frame(Card.Style.ELEVATED, false);
        assertTrue(painted.shadows > 0);
        assertTrue(has(painted.fills, theme.surface()));
    }

    @Test
    void theOutlinedCardDrawsALineAndNoShadow() {
        Theme theme = Theme.current();
        Painted painted = frame(Card.Style.OUTLINED, false);
        assertEquals(0, painted.shadows);
        assertTrue(has(painted.fills, theme.surface()));
        assertTrue(has(painted.strokes, theme.outline()));
    }

    @Test
    void theFilledCardIsTheSurfaceAloneAndNotTheRaisedTone() {
        Theme theme = Theme.current();
        Painted painted = frame(Card.Style.FILLED, false);
        assertEquals(0, painted.shadows);
        assertTrue(has(painted.fills, theme.surface()), "the surface, the step every palette holds off its page");
        assertFalse(has(painted.fills, theme.surfaceRaised()),
                "not the raised tone, which is the Light palette's page itself");
        assertFalse(has(painted.strokes, theme.outline()), "no line");
    }

    @Test
    void thePlainCardPaintsNothingOfItsOwn() {
        Theme theme = Theme.current();
        Painted painted = frame(Card.Style.PLAIN, false);
        assertEquals(0, painted.shadows);
        assertTrue(painted.fills.isEmpty(), "no fill: " + painted.fills);
        assertTrue(painted.strokes.isEmpty(), "no line: " + painted.strokes);
        assertEquals(0, card.paintOutset(), 1e-6, "nothing is drawn outside it");
        assertEquals(Card.Style.PLAIN, card.style());
        assertFalse(has(painted.fills, theme.surface()));
    }

    @Test
    void aPlainCardThatIsClickedKeepsItsHoverAndItsRing() {
        Theme theme = Theme.current();
        frame(Card.Style.PLAIN, true);
        float x = card.localToScene(0, 0).x() + 20;
        float y = card.localToScene(0, 0).y() + 10;
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        Painted hovered = render();
        assertTrue(has(hovered.fills, theme.text()), "the hover, the text colour laid over the page");

        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).keyEvent(Keys.TAB, false, false, 0);
        drive(scene).inputBatchEnded();
        Painted focused = render();
        assertTrue(card.area().isFocused(), "the card took the keyboard");
        assertTrue(has(focused.strokes, theme.focusRing()), "the ring round it");
        assertTrue(card.paintOutset() > 0, "the ring is drawn outside the card, and declared");
    }
}
