package limn.components;

import limn.components.internal.AnchoredPopup;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.Insets;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the toolkit's in-scene fades end when they are closed before they have finished opening
 * (ADR 071 §2.7): what is on screen when the surface goes, and how long the way out takes.
 *
 * <p>A dialog's card and its veil end together, on the same frame and at the same value all the
 * way, so that the overlay is never taken away from under a card still partly drawn; and a popup
 * closed halfway through its fade in fades out from where it had got to, over the part of the
 * window animation that brings it back to nothing, and not over the whole of it.
 */
class InSceneFadeEndsTest {

    private static final int W = 400;
    private static final int H = 300;
    /** A frame of a 60 Hz display, in milliseconds. */
    private static final long FRAME = 16;

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private HeadlessUi ui;
    private Scene scene;

    @BeforeEach
    void start() {
        ui = new HeadlessUi(() -> nanos[0]);
        Theme.setCurrent(Theme.dark());
        limn.scene.ControlSize.setProcessDefault(limn.scene.ControlSize.MEDIUM);
    }

    @AfterEach
    void stop() {
        ui.close();
    }

    private void bind(Widget<?> root, limn.backend.NativeWindow window) {
        scene = new Scene(root, () -> nanos[0]);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.bind(window);
        frame();
    }

    private void frame() {
        scene.renderFrame(new NoopCanvas(W, H));
    }

    private void advance(long millis) {
        nanos[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        ui.runtime().drain();
        frame();
    }

    @Test
    void aDialogClosedWhileItFadesInTakesItsCardAndItsVeilAwayTogether() {
        bind(new Label("behind"), new limn.testing.HeadlessBackend(ui.runtime()).open("Owner", W, H));
        Dialog dialog = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE);
        float[] cardWhenItWent = {-1};
        java.util.concurrent.CompletionStage<String> answered = dialog.show(scene);
        // The card in the container that slides it, in the container that fades it.
        Opacity card = (Opacity) dialog.contentRoot().parent().parent();
        answered.thenAccept(r -> cardWhenItWent[0] = card.opacity());
        frame(); // the fade's first frame is its start
        advance(3 * FRAME);
        float opened = card.opacity();
        assertTrue(opened > 0f && opened < 1f, "fading in: " + opened);

        dialog.dismiss("closed"); // Escape, a moment after it opened
        List<String> apart = new ArrayList<>();
        for (int i = 0; i < 40 && cardWhenItWent[0] < 0; i++) {
            advance(FRAME);
            if (cardWhenItWent[0] < 0 && Math.abs(card.opacity() - dialog.fadeLevel()) > 1e-6f) {
                apart.add(i + ": card " + card.opacity() + ", veil " + dialog.fadeLevel());
            }
        }
        assertTrue(cardWhenItWent[0] >= 0, "the dialog closed");
        assertEquals(0f, cardWhenItWent[0], "the card is gone when the overlay goes, not drawn for a frame "
                + "more over what the veil uncovered");
        assertEquals(List.of(), apart, "the card and the veil fade as one, frame by frame");
    }

    @Test
    void aPopupClosedWhileItFadesInFadesOutFromWhereItHadGot() throws ReflectiveOperationException {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"));
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        bind(new Padding(Insets.all(10), combo), new StubWindow(true));
        combo.open();
        frame(); // the fade's first frame is its start
        advance(2 * FRAME);
        java.lang.reflect.Field popup = ComboBox.class.getDeclaredField("popup");
        popup.setAccessible(true);
        Widget<?> panel = ((AnchoredPopup) popup.get(combo)).panel();
        assertTrue(panel != null && panel.scene() == scene, "the list is in the scene");
        Opacity list = (Opacity) panel.parent(); // the container that fades the list
        float shown = list.opacity();
        assertTrue(shown > 0f && shown < 0.5f, "a moment after it opened, hardly shown yet: " + shown);

        combo.close();
        int frames = 0;
        while (list.scene() != null && frames < 40) {
            advance(FRAME);
            frames++;
        }
        double animWindow = Theme.current().animWindow;
        // From where the fade in had got to: its share of the window animation, and a frame for the
        // fade's start, and not all ten frames of the whole of it.
        double took = frames * FRAME / 1000.0;
        assertTrue(took <= shown * animWindow + 2 * FRAME / 1000.0,
                "the fade out took " + frames + " frames of " + FRAME + " ms from " + shown
                        + "; the whole window animation is " + animWindow + " s");
    }

    /**
     * Under less motion the toolkit's own closers ask for no time, so what they run when the
     * surface has gone, the result of a dialog and the removal of a popup's layer, comes on the
     * next turn of the UI queue and not after the window animation: a fade that goes straight there
     * after asking for seconds keeps its onArrive for those seconds.
     */
    @Test
    void underLessMotionADialogAndAPopupInAWindowCloseOnTheNextTurn() throws ReflectiveOperationException {
        try (limn.testing.TestPreferences system = new limn.testing.TestPreferences()) {
            system.reduceMotion(true);
            bind(new Label("behind"), new limn.testing.HeadlessBackend(ui.runtime()).open("Owner", W, H));
            Dialog dialog = new Dialog("Confirm", "Proceed?")
                    .addPrimaryButton("OK", "ok")
                    .setDisplayMode(DisplayMode.IN_SCENE);
            String[] result = {null};
            dialog.show(scene).thenAccept(r -> result[0] = r);
            frame();
            dialog.dismiss("closed");
            assertEquals(null, result[0], "never inside the call");
            ui.runtime().drain(); // the queue's clock does not move: nothing waits for seconds
            assertEquals("closed", result[0], "the dialog resolves on the next turn");

            ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"));
            combo.setDisplayMode(DisplayMode.IN_SCENE);
            bind(new Padding(Insets.all(10), combo), new StubWindow(true));
            combo.open();
            frame();
            java.lang.reflect.Field popup = ComboBox.class.getDeclaredField("popup");
            popup.setAccessible(true);
            Widget<?> panel = ((AnchoredPopup) popup.get(combo)).panel();
            assertTrue(panel != null && panel.scene() == scene, "the list is in the scene");
            combo.close();
            ui.runtime().drain();
            frame();
            assertEquals(null, panel.scene(), "the list's layer is gone on the next turn");
        }
    }

    /**
     * A dialog in a scene with no window shows nothing to fade, so it asks for no time: it resolves
     * on the next turn, as it did before a fade with seconds kept its arrival for them.
     */
    @Test
    void aDialogInASceneWithNoWindowResolvesOnTheNextTurn() {
        scene = new Scene(new Label("behind"), () -> nanos[0]);
        scene.setTextRuler(ComponentTestBase.RULER);
        frame();
        Dialog dialog = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE);
        String[] result = {null};
        dialog.show(scene).thenAccept(r -> result[0] = r);
        frame();
        dialog.dismiss("closed");
        ui.runtime().drain();
        assertEquals("closed", result[0], "resolved on the next turn, with the queue's clock where it was");
    }

    /**
     * A picker closed and opened again before its close has finished, in one handler or two clicks
     * in one batch: the close's end hands the grid back, and it must not take it out of the panel
     * the reopen built, which then stayed open and empty. Under less motion the close ends on the
     * next turn; with motion, when its fade does.
     */
    @Test
    void aDatePickerReopenedBeforeItsCloseEndsKeepsItsCalendar() {
        for (boolean reduced : new boolean[] {false, true}) {
            try (limn.testing.TestPreferences system = new limn.testing.TestPreferences()) {
                system.reduceMotion(reduced);
                limn.components.date.DatePicker picker = new limn.components.date.DatePicker()
                        .setDisplayMode(DisplayMode.IN_SCENE);
                bind(new Padding(Insets.all(10), picker), new StubWindow(true));
                String what = reduced ? "less motion" : "with motion";
                picker.open();
                frame();
                for (int i = 0; i < 30; i++) {
                    advance(FRAME);
                }
                assertTrue(picker.calendar().isShowing(), what + ": open, the calendar shows");

                picker.close();
                if (!reduced) {
                    advance(2 * FRAME); // the fade out under way
                }
                picker.open();
                for (int i = 0; i < 30; i++) {
                    advance(FRAME); // past the end of the close, whichever way it ends
                }
                assertTrue(picker.isOpen(), what + ": open again");
                assertTrue(picker.calendar().parent() != null && picker.calendar().isShowing(),
                        what + ": the reopened popup keeps its calendar");

                picker.close();
                for (int i = 0; i < 30; i++) {
                    advance(FRAME);
                }
                assertEquals(null, picker.calendar().parent(), what + ": a close nobody reopens hands it back");
            }
        }
    }
}
