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
}
