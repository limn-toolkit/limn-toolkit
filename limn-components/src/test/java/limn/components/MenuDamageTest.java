package limn.components;

import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Partial rendering inside menus: moving the hover between items must damage
 * only the two affected rows, not the whole cascade (the popup used to fall
 * back to full frames because of its translucent background, and every hover
 * repainted the entire menu).
 */
class MenuDamageTest extends ComponentTestBase {

    /**
     * Slack over the two damaged rows: the damage rect is snapped out to whole device pixels
     * and carries the usual AA margin. The <em>rows</em> are not a constant: a private copy of
     * 28 was silently the MEDIUM row and turned this into a failing test at LARGE (34) and
     * XLARGE (40), so the ceiling is derived from the row the popup actually used.
     */
    private static final float DAMAGE_SLOP = 6;

    @Test
    void menuHoverDamagesOnlyTheAffectedRows() {
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);

        Menu menu = new Menu()
                .addItem("One", () -> { })
                .addItem("Two", () -> { })
                .addItem("Three", () -> { });
        PopupMenu popup = new PopupMenu(menu);
        popup.showInSceneForTest(scene, 20, 20, 0, 0); // in-scene overlay, fade snapped
        // The cascade is hosted on the scene root, so this is the step the rows were built at.
        final float itemH = popup.tokensForTest().menuRowHeight();

        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        scene.renderFrame(canvas); // overlay push → layout → full frame

        // Probe downward for the first Y where the hover reaches row 1; one
        // item-height above that is safely inside row 0 (rows are contiguous).
        float yRow1 = -1;
        for (float y = 21; y < 280 && yRow1 < 0; y += 2) {
            scene.mouseMoved(40, y);
            scene.inputBatchEnded();
            if (popup.highlightForTest(0) == 1) {
                yRow1 = y;
            }
        }
        assertTrue(yRow1 > 0, "probe found row 1");
        float yRow0 = yRow1 - itemH;

        // Park the hover on row 0 and drain all pending damage/history.
        scene.mouseMoved(40, yRow0);
        scene.inputBatchEnded();
        assertTrue(popup.highlightForTest(0) == 0, "parked on row 0");
        boolean settled = false;
        for (int i = 0; i < 10 && !settled; i++) {
            canvas.reset();
            scene.renderFrame(canvas);
            settled = canvas.nothingPainted();
        }
        assertTrue(settled, "an open, idle menu must not keep painting");

        // Hover moves one row down: the frame must clip to ~two rows.
        scene.mouseMoved(40, yRow1);
        scene.inputBatchEnded();
        canvas.reset();
        scene.renderFrame(canvas);
        if (canvas.cleared || canvas.firstClip == null) {
            fail("hover move must be a partial frame, got cleared=" + canvas.cleared);
        }
        assertNotNull(canvas.firstClip);
        assertTrue(canvas.firstClip.height() <= 2 * itemH + DAMAGE_SLOP,
                "damage must cover just the two affected rows, got " + canvas.firstClip);
        assertTrue(canvas.firstClip.width() < 300,
                "damage must be one column wide, got " + canvas.firstClip);
    }
}
