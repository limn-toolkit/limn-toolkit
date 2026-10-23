package limn.print;

import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The user zooms a print preview with a pinch, with the wheel and Control, and with the keys: each
 * makes the zoom a percentage of the paper, announced as the user's change of the value.
 */
class PrintPreviewZoomTest {

    private HeadlessUi ui;
    private PrintPreview preview;
    private Scene scene;
    private final List<Change> changes = new ArrayList<>();

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
        preview = new PrintPreview(Document.of(Paper.A4).add(new SizedBox(400, 300)));
        preview.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                changes.add(change);
            }
        });
        scene = new Scene(preview);
        scene.layoutPass(800, 600);
    }

    @AfterEach
    void stop() {
        ui.close();
    }

    @Test
    void aPinchOverThePagesZoomsThemByItsFactor() {
        float fitted = preview.zoomPercent();
        drive(scene).zoomed(1.5f, 400, 300, true);
        drive(scene).inputBatchEnded();
        assertEquals(PrintPreview.Zoom.PERCENT, preview.zoom());
        assertEquals(fitted * 1.5f, preview.zoomPercent(), 0.01f);
        assertEquals(1, changes.size());
        assertEquals(Change.Origin.USER, changes.get(0).origin());
    }

    @Test
    void theWheelWithControlZoomsThePagesInsteadOfScrollingThem() {
        float fitted = preview.zoomPercent();
        drive(scene).keyEvent(Keys.LEFT_CONTROL, 0, true, false, Keys.MOD_CONTROL);
        drive(scene).scrolled(0, 1, 400, 300);
        drive(scene).inputBatchEnded();
        assertEquals(fitted * 1.1f, preview.zoomPercent(), 0.01f);
    }

    @Test
    void theKeysZoomTheFocusedPreview() {
        float fitted = preview.zoomPercent();
        scene.requestFocus(preview);
        drive(scene).keyEvent(Keys.EQUAL, '=', true, false, Keys.MOD_CONTROL);
        drive(scene).inputBatchEnded();
        assertEquals(fitted * 1.25f, preview.zoomPercent(), 0.01f);
        drive(scene).keyEvent(Keys.NUM_0, '0', true, false, Keys.MOD_CONTROL);
        drive(scene).inputBatchEnded();
        assertEquals(100, preview.zoomPercent(), 0.01f);
        assertTrue(changes.stream().allMatch(c -> c.origin() == Change.Origin.USER));
    }
}
