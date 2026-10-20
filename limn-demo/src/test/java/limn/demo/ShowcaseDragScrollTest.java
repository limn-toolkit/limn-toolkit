package limn.demo;

import limn.components.ImageView;
import limn.components.Label;
import limn.components.ScrollView;
import limn.graphics.Rect;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drag and drop page, as the Showcase scrolls it: the logo dragged down toward the drop area,
 * whose top shows at the bottom of the window, scrolls the page down until the area is in view,
 * and is dropped there. The page scrolled UP instead, and away from the pointer, whenever the
 * pointer entered the area's top, so the logo could not be dropped without scrolling first and
 * dragging from outside the page (the owner's run, 2026-10-20).
 */
class ShowcaseDragScrollTest {

    private static final float W = 900;
    private static final float H = 620;

    @Test
    void theLogoDraggedDownScrollsThePageToTheDropAreaAndIsDroppedThere() {
        long[] nanos = {1_000_000_000L};
        Locale before = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
        try (HeadlessUi ui = new HeadlessUi(() -> nanos[0])) {
            Label status = new Label("");
            Page.reportTo(status);
            ScrollView page = new ScrollView(new Padding(Insets.all(12), PickerScene.content())); // AppScene.scrolling
            Scene scene = new Scene(page, () -> nanos[0]);
            NoopCanvas canvas = new NoopCanvas((int) W, (int) H);
            scene.layoutPass(W, H);
            scene.renderFrame(canvas);
            ImageView logo = find(page, ImageView.class);
            Widget<?> area = findNamed(page, "DropZone");
            assertNotNull(logo);
            assertNotNull(area);
            // Scrolled so the logo shows whole and the area only its top 20 points.
            page.scrollTo(0, onScreen(area).y() - H + 20);
            scene.layoutPass(W, H);
            scene.renderFrame(canvas);
            assertTrue(page.offsetY() > 0, "the page starts scrolled a little");
            Rect from = onScreen(logo);
            assertTrue(from.y() >= 0 && from.bottom() <= H, "the logo shows: " + from);

            float x = from.x() + from.width() / 2;
            float y = from.y() + from.height() / 2;
            drive(scene).mouseMoved(x, y);
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
            drive(scene).inputBatchEnded();
            float lowest = page.offsetY();
            // Down to the bottom of the window, a few points a frame, and then resting there.
            for (float to = y + 6; to <= H - 4; to += 6) {
                drive(scene).mouseMoved(x, to);
                drive(scene).inputBatchEnded();
                nanos[0] += 16_666_667L;
                scene.layoutPass(W, H);
                scene.renderFrame(canvas);
                assertTrue(page.offsetY() >= lowest, "never up, with the pointer at " + to);
                lowest = page.offsetY();
            }
            assertTrue(scene.isDragging());
            for (int i = 0; i < 300 && onScreen(area).bottom() > H; i++) {
                nanos[0] += 16_666_667L;
                scene.layoutPass(W, H);
                scene.renderFrame(canvas);
                assertTrue(page.offsetY() >= lowest, "never up while it rests at the bottom");
                lowest = page.offsetY();
            }
            Rect seen = onScreen(area);
            assertTrue(seen.y() >= 0 && seen.bottom() <= H, "the drop area came into view whole: " + seen);

            float dropX = seen.x() + seen.width() / 2;
            float dropY = seen.y() + seen.height() / 2;
            drive(scene).mouseMoved(dropX, dropY);
            drive(scene).inputBatchEnded();
            drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, dropX, dropY, 1);
            drive(scene).inputBatchEnded();
            ui.pumpUntil(() -> !status.text().isEmpty());
            assertEquals("Picture dropped.", status.text());
        } finally {
            Page.reportTo(null);
            I18n.setLocale(before);
        }
    }

    private static Rect onScreen(Widget<?> widget) {
        return widget.localToSceneBounds(0, 0, widget.width(), widget.height());
    }

    private static <T> T find(Widget<?> widget, Class<T> type) {
        if (type.isInstance(widget)) {
            return type.cast(widget);
        }
        for (Widget<?> child : widget.children()) {
            T found = find(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Widget<?> findNamed(Widget<?> widget, String simpleName) {
        if (widget.getClass().getSimpleName().equals(simpleName)) {
            return widget;
        }
        for (Widget<?> child : widget.children()) {
            Widget<?> found = findNamed(child, simpleName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
