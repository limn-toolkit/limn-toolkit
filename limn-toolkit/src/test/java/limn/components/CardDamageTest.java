package limn.components;

import limn.graphics.Rect;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pressable {@link Card} repaints under partial rendering (ADR 073 §2.10): the pointer
 * over its area repaints the area and its parts and nothing of the footer, and the pointer in the
 * footer's band repaints nothing of the card at all, since nothing there answers to it.
 */
class CardDamageTest extends ComponentTestBase {

    private final AtomicLong nanos = new AtomicLong();
    private Scene scene;
    private RecordingTestCanvas canvas;
    private Card card;

    private void bindCard() {
        card = new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042")
                .setFooter(new Button("Approve"));
        card.onActivate(() -> { });
        Column column = new Column();
        column.add(new SizedBox(300, SizedBox.UNSET, card));
        scene = new Scene(new Padding(Insets.all(40), column), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(640, 480);
        settle();
    }

    /** Frames until one paints nothing, and every pass the frames before it painted. */
    private List<Rect> settle() {
        List<Rect> passes = new ArrayList<>();
        for (int frame = 0; frame < 120; frame++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return passes;
            }
            passes.addAll(canvas.passClips());
        }
        throw new AssertionError("never came to rest");
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    @Test
    void thePointerOverTheAreaRepaintsTheAreaAndNotTheFooter() {
        bindCard();
        float x = card.localToScene(0, 0).x();
        float y = card.localToScene(0, 0).y();
        move(x + 20, y + 20);
        List<Rect> passes = settle();
        assertTrue(!passes.isEmpty(), "the hover is drawn");
        float areaBottom = y + card.area().height();
        for (Rect pass : passes) {
            assertTrue(pass.y() + pass.height() <= areaBottom + 2, "the pass " + pass
                    + " reaches below the area, which ends at " + areaBottom + ", into the footer");
        }
    }

    @Test
    void thePointerInTheFootersBandRepaintsNothing() {
        bindCard();
        float x = card.localToScene(0, 0).x();
        float y = card.localToScene(0, 0).y();
        move(x + 4, y + card.height() - 4);
        assertEquals(List.of(), settle(), "nothing in the band answers to the pointer");
    }
}
