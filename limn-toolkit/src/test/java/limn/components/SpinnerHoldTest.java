package limn.components;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Press-and-hold auto-repeat on the Spinner's up/down buttons. Uses a manual
 * clock (like {@code UiRuntimeTest}) so the delayed repeat ticks fire
 * deterministically instead of on wall-clock time.
 */
class SpinnerHoldTest {

    /**
     * The suite's 10pt/glyph ruler, delegated rather than re-declared: a second copy is
     * a second thing to forget when the shared one changes.
     */
    private static final limn.graphics.TextRuler RULER = ComponentTestBase.RULER;

    /** The laid-out MEDIUM box and the stepper coordinates, derived rather than baked in. */
    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    private static final float BOX_W = MEDIUM.spinnerWidth();          // 140
    private static final float BOX_H = MEDIUM.controlHeight();         // 32
    private static final float BUTTON_X = BOX_W - MEDIUM.spinnerButtonW() / 2; // 127
    private static final float UP_Y = BOX_H / 4;                       // 8
    private static final float DOWN_Y = BOX_H * 3 / 4;                 // 24

    private final AtomicLong clock = new AtomicLong();
    private ExecutorService workers;
    private UiRuntime runtime;
    private Spinner spinner;
    private Scene scene;

    @BeforeEach
    void setup() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(clock::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Theme.setCurrent(Theme.dark());
        // This class builds its own runtime instead of extending ComponentTestBase, so it
        // needs the same process-default reset; otherwise a sibling test that changed it
        // leaks in through the static.
        limn.scene.ControlSize.setProcessDefault(limn.scene.ControlSize.MEDIUM);
        spinner = new Spinner(0, 1000, 1).setValue(0);
        scene = new Scene(spinner);
        scene.setTextRuler(RULER);
        // spinnerWidth 140 x controlHeight 32 at MEDIUM; up button: x in [114,140], y < 16.
        scene.layoutPass(BOX_W, BOX_H);
    }

    @AfterEach
    void teardown() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** Advances the manual clock and runs whatever delayed tasks are now due. */
    private void advanceMs(long ms) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
        runtime.drain();
    }

    @Test
    void holdingTheUpButtonKeepsIncrementingUntilRelease() {
        // Press (steps once immediately) and keep holding: no release yet.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, UP_Y);
        scene.inputBatchEnded();
        assertEquals(1.0, spinner.value(), "press steps once immediately");

        // Nothing repeats before the initial hold delay (350 ms) elapses.
        advanceMs(300);
        assertEquals(1.0, spinner.value(), "no repeat before the initial hold delay");

        // Just past the initial delay: the first repeat fires.
        advanceMs(100); // clock now 400 ms > 350
        assertEquals(2.0, spinner.value(), "first repeat after the initial delay");

        // Then it keeps ticking at the steady cadence (55 ms).
        advanceMs(55);
        advanceMs(55);
        advanceMs(55);
        assertEquals(5.0, spinner.value(), "repeats at the steady cadence while held");

        // Release stops it: further time advances change nothing.
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, BUTTON_X, UP_Y);
        scene.inputBatchEnded();
        double atRelease = spinner.value();
        advanceMs(500);
        assertEquals(atRelease, spinner.value(), "no repeat after release");
    }

    @Test
    void holdOnTheDownButtonRepeatsDownward() {
        spinner.setValue(500);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, DOWN_Y); // down button (y >= mid 16)
        scene.inputBatchEnded();
        assertEquals(499.0, spinner.value());

        advanceMs(400); // past initial delay → one repeat
        advanceMs(55);  // → another
        assertEquals(497.0, spinner.value(), "held down button steps downward");

        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, BUTTON_X, DOWN_Y);
        scene.inputBatchEnded();
    }

    @Test
    void holdStopsAutomaticallyAtTheBoundInsteadOfBusyRepeating() {
        spinner.setValue(999); // one below max
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, UP_Y); // +1 → 1000 (max)
        scene.inputBatchEnded();
        assertEquals(1000.0, spinner.value());

        // Hold well past several repeat intervals.
        advanceMs(400);
        advanceMs(55);
        advanceMs(55);
        assertEquals(1000.0, spinner.value(), "clamped at max, no overshoot");
        assertEquals(-1, runtime.nanosUntilNextDeadline(),
                "the repeat chain stopped scheduling once it reached the bound");
    }
}
