package limn.components;

import limn.components.date.DatePicker;
import limn.graphics.Rect;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An overlay drawn in the scene and left alone stops asking for frames.
 *
 * <p>An in-scene dialog re-placed its card on every paint, to follow the slide of its fade, by
 * laying the card out again, and the body's scroll view refreshed its bar on every layout, which
 * repainted the bar every time: every frame damaged the strip at the body's trailing edge and
 * asked for the next one, so a window showing a question rendered at the display's rate with
 * nothing on it changing, for as long as the question was up.
 *
 * <p>Each case opens an overlay in a scene bound to a window that counts the frames asked of it,
 * runs frames a fifth of a second apart until every fade has run out, and then holds that a run of
 * further frames paints nothing and asks for nothing.
 */
class OverlayIdleTest extends ComponentTestBase {

    private static final int W = 700;
    private static final int H = 500;

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;
    private OverlayHost window;

    private void mount(Widget<?> root) {
        nanos = new AtomicLong(1_000_000_000L);
        window = new OverlayHost();
        scene = new Scene(root, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        scene.bind(window);
        canvas = new RecordingTestCanvas(W, H);
        frame();
    }

    private void frame() {
        nanos.addAndGet(200_000_000L);
        canvas.reset();
        scene.renderFrame(canvas);
    }

    /**
     * Runs frames until one paints nothing and asks for no other, then holds that ten more do the
     * same; fails naming what the last frame that painted repainted.
     */
    private void assertGoesIdle(String what) {
        List<Rect> last = List.of();
        for (int i = 0; i < 50; i++) {
            int asked = window.framesRequested;
            frame();
            if (canvas.nothingPainted() && window.framesRequested == asked) {
                for (int k = 0; k < 10; k++) {
                    frame();
                    assertTrue(canvas.nothingPainted(), what + ": a frame after it settled "
                            + "repainted " + (canvas.cleared ? "the window" : canvas.passClips()));
                }
                assertEquals(asked, window.framesRequested,
                        what + ": frames were asked for after it settled");
                return;
            }
            last = canvas.cleared ? List.of(new Rect(0, 0, W, H)) : canvas.passClips();
        }
        throw new AssertionError(what + " never went idle: fifty frames on, each still "
                + "repainted " + last + " and asked for the next");
    }

    // ------------------------------------------------------------------ the dialog

    @Test
    void anInSceneDialogLeftAloneGoesIdle() {
        mount(new Column());
        Column content = new Column();
        content.add(new Checkbox("Remember this choice"));
        content.add(new Label("Applies to this folder only."));
        new Dialog("Rename", "The name is already taken.").setContent(content)
                .addButton("Cancel", "cancel").addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE).show(scene);
        assertGoesIdle("a dialog whose body fits");
    }

    /** A card capped by the window, whose body scrolls and whose bar flashes once as it opens. */
    @Test
    void anInSceneDialogWhoseBodyScrollsGoesIdleOnceItsBarHasFaded() {
        mount(new Column());
        Column content = new Column();
        for (int i = 0; i < 60; i++) {
            content.add(new Label("Line " + i));
        }
        Dialog dialog = new Dialog("Licence", "").setContent(content)
                .addPrimaryButton("Accept", "ok").setDisplayMode(DisplayMode.IN_SCENE);
        dialog.show(scene);
        assertGoesIdle("a dialog whose body scrolls");
    }

    // ------------------------------------------------------------------ the other overlays

    @Test
    void anInSceneComboListLeftAloneGoesIdle() {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"));
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(combo);
        mount(root);
        combo.open();
        assertTrue(combo.isInSceneForTest(), "the list is drawn in the scene");
        assertGoesIdle("a combo's list");
    }

    @Test
    void anInSceneComboListThatScrollsGoesIdle() {
        List<String> items = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            items.add("Item " + i);
        }
        ComboBox<String> combo = new ComboBox<>(items);
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(combo);
        mount(root);
        combo.open();
        assertGoesIdle("a combo's list that scrolls");
    }

    @Test
    void anInSceneCalendarLeftAloneGoesIdle() {
        DatePicker picker = new DatePicker().setClock(
                Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(picker);
        mount(root);
        picker.open();
        assertTrue(picker.isOpen());
        assertGoesIdle("a date picker's calendar");
    }

    @Test
    void anInScenePopupMenuLeftAloneGoesIdle() {
        Menu menu = new Menu().addItem("New", () -> { }).addItem("Open", () -> { })
                .addSubmenu("Export", new Menu().addItem("PNG", () -> { }));
        PopupMenu popup = new PopupMenu(menu).setDisplayMode(DisplayMode.IN_SCENE);
        Label anchor = new Label("anchor");
        Column root = new Column();
        root.add(new SizedBox(80, 20, anchor));
        mount(root);
        popup.showAt(anchor, 20, 20);
        assertGoesIdle("a popup menu");
    }
}
