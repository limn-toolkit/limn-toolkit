package limn.scene;

import limn.input.Keys;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A pinch, and the wheel with the zoom's key, reach the widget under the pointer as a
 * {@link MouseEvent.Type#ZOOM}: queued pinch events merge into their product, a gesture stays with
 * the widget that took its first event until the next begins or a pause, and the wheel with
 * Control is a zoom where a widget zooms and a scroll where none does.
 */
class SceneZoomTest extends SceneTestBase {

    private final List<String> log = new ArrayList<>();
    private final long[] now = {0};
    private SceneInputTest.Recorder a;
    private SceneInputTest.Recorder b;
    private Scene scene;

    /** Column [a (0..100), b (100..200)] inside a 200x200 scene, on a clock the test moves. */
    private void build() {
        Column column = new Column();
        a = new SceneInputTest.Recorder("a", log, 200, 100);
        b = new SceneInputTest.Recorder("b", log, 200, 100);
        column.add(a);
        column.add(b);
        scene = new Scene(column, () -> now[0]);
        scene.layoutPass(200, 200);
    }

    /** What the widgets heard of the wheel and the zoom, hover and keys left out. */
    private List<String> heard() {
        return log.stream().filter(e -> e.endsWith(":ZOOM") || e.endsWith(":WHEEL")).toList();
    }

    private void pinch(float scale, float x, float y, boolean began) {
        drive(scene).zoomed(scale, x, y, began);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aPinchReachesTheWidgetUnderThePointerAsAZoom() {
        build();
        pinch(1.25f, 10, 150, true);
        assertEquals(List.of("b:ZOOM"), log);
        assertEquals(1.25f, b.lastZoom.scale(), 1e-6);
        assertEquals(150, b.lastZoom.y(), 1e-6);
    }

    @Test
    void queuedPinchEventsMergeIntoTheirProductButNeverAcrossABeginning() {
        build();
        drive(scene).zoomed(1.1f, 10, 10, true);
        drive(scene).zoomed(1.2f, 10, 10, false);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:ZOOM"), log);
        assertEquals(1.32f, a.lastZoom.scale(), 1e-5);

        log.clear();
        drive(scene).zoomed(0.9f, 10, 10, false);
        drive(scene).zoomed(0.5f, 10, 10, true);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:ZOOM", "a:ZOOM"), log);
        assertEquals(0.5f, a.lastZoom.scale(), 1e-6);
    }

    @Test
    void aPinchStaysWithTheWidgetThatTookItsFirstEvent() {
        build();
        a.consumeType = MouseEvent.Type.ZOOM;
        pinch(1.1f, 10, 10, true);
        pinch(1.1f, 10, 150, false); // the fingers carried the pointer over b
        assertEquals(List.of("a:ZOOM", "a:ZOOM"), log);

        log.clear();
        pinch(1.1f, 10, 150, true); // a new pinch chooses afresh
        assertEquals(List.of("b:ZOOM"), log);
    }

    @Test
    void aPauseEndsAPinchAsAGestureEnds() {
        build();
        a.consumeType = MouseEvent.Type.ZOOM;
        pinch(1.1f, 10, 10, true);
        now[0] += TimeUnit.MILLISECONDS.toNanos(400);
        pinch(1.1f, 10, 150, false);
        assertEquals(List.of("a:ZOOM", "b:ZOOM"), log);
    }

    @Test
    void theWheelWithControlIsAZoomWhereAWidgetZooms() {
        build();
        a.consumeType = MouseEvent.Type.ZOOM;
        drive(scene).keyEvent(Keys.LEFT_CONTROL, 0, true, false, Keys.MOD_CONTROL);
        drive(scene).scrolled(0, 2, 10, 10);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:ZOOM"), heard());
        assertEquals((float) Math.pow(1.1, 2), a.lastZoom.scale(), 1e-5);
    }

    @Test
    void theWheelWithControlIsAScrollWhereNothingZooms() {
        build();
        drive(scene).keyEvent(Keys.LEFT_CONTROL, 0, true, false, Keys.MOD_CONTROL);
        drive(scene).scrolled(0, -3, 10, 10);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:ZOOM", "a:WHEEL"), heard());
        assertEquals(-3, a.lastWheel.scrollY(), 1e-6);
    }

    @Test
    void theWheelAloneIsOnlyAScroll() {
        build();
        a.consumeType = MouseEvent.Type.ZOOM;
        drive(scene).scrolled(0, 1, 10, 10);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:WHEEL"), heard());
    }

    @Test
    void theWheelWithAltAndControlIsNotAZoom() {
        build();
        a.consumeType = MouseEvent.Type.ZOOM;
        drive(scene).keyEvent(Keys.LEFT_CONTROL, 0, true, false, Keys.MOD_CONTROL);
        drive(scene).keyEvent(Keys.LEFT_ALT, 0, true, false, Keys.MOD_CONTROL | Keys.MOD_ALT);
        drive(scene).scrolled(0, 1, 10, 10);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("a:WHEEL"), heard());
    }

    @Test
    void aZoomIsAPositiveFiniteFactor() {
        assertThrows(IllegalArgumentException.class, () -> MouseEvent.zoom(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MouseEvent.zoom(0, 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> MouseEvent.zoom(0, 0, Float.POSITIVE_INFINITY, 0));
        assertEquals(1f, new MouseEvent(MouseEvent.Type.WHEEL, 0, 0, -1, 0, 1, 0).scale());
    }
}
