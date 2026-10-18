package limn.components;

import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
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
 * way, so that the overlay is never taken away from under a card still partly drawn.
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
        Widget<?> card = dialog.contentRoot();
        float[] cardWhenItWent = {-1};
        dialog.show(scene).thenAccept(r -> cardWhenItWent[0] = card.opacity());
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
}
