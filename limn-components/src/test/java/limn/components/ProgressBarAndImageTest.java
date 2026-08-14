package limn.components;

import limn.graphics.Image;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressBarAndImageTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong();

    /**
     * A canvas exactly the size the bar asks for at the step in force: derived, not baked,
     * because the height is now {@code progressThickness} (4/6/8/10/12) rather than a literal 8.
     * The bar measures no text, so {@link #RULER}'s font-blind lineHeight never enters here.
     */
    private static FakeCanvas barCanvas(ProgressBar bar) {
        Size preferred = bar.measure(Constraints.loose(1000, 1000));
        return new FakeCanvas(preferred.width(), preferred.height());
    }

    @Test
    void thicknessFollowsTheStepAndTheLongAxisDoesNot() {
        // The bar's entire size axis is one token; MEDIUM reproduces today's literal 8.
        // The 220 pt length is a FREE axis (ADR 002 8.3): equal at every step and equal to
        // Slider's, which is why ProgressBar is exempt from strict width monotonicity.
        for (ControlSize step : ControlSize.values()) {
            Size size = new ProgressBar().withControlSize(step)
                    .measure(Constraints.loose(500, 500));
            assertEquals(SizeTokens.of(step).progressThickness(), size.height(), 1e-6,
                    "thickness at " + step);
            assertEquals(220, size.width(), 1e-6, "free axis at " + step);
        }
    }

    @Test
    void anExplicitThicknessLatchesOverTheStep() {
        // KitchenSinkScene's pinned bar: setThickness(6) must survive every later step change
        // (ADR 002 10.2 #19), and UNSET must hand the dimension back to the step.
        ProgressBar bar = new ProgressBar().setThickness(6).setPreferredWidth(120);
        bar.setControlSize(ControlSize.XLARGE);
        assertEquals(6, bar.measure(Constraints.loose(500, 500)).height(), 1e-6,
                "an author's pin beats the step");
        assertEquals(120, bar.measure(Constraints.loose(500, 500)).width(), 1e-6,
                "the pinned free axis is untouched too");

        bar.setThickness(ProgressBar.UNSET);
        assertEquals(SizeTokens.of(ControlSize.XLARGE).progressThickness(),
                bar.measure(Constraints.loose(500, 500)).height(), 1e-6,
                "UNSET returns the thickness to the step");
    }

    @Test
    void progressClampsToUnitRange() {
        ProgressBar bar = new ProgressBar();
        bar.setProgress(0.5f);
        assertEquals(0.5f, bar.progress(), 1e-6);
        bar.setProgress(-1);
        assertEquals(0, bar.progress(), 1e-6);
        bar.setProgress(3);
        assertEquals(1, bar.progress(), 1e-6);
    }

    @Test
    void settingProgressLeavesIndeterminate() {
        ProgressBar bar = new ProgressBar();
        bar.setIndeterminate(true);
        assertTrue(bar.isIndeterminate());
        bar.setProgress(0.3f);
        assertFalse(bar.isIndeterminate(), "an explicit value switches back to determinate");
    }

    @Test
    void indeterminateSweepAdvancesWithTheFrameClock() {
        ProgressBar bar = new ProgressBar();
        Scene scene = new Scene(bar, clock::get);
        scene.setTextRuler(RULER);
        FakeCanvas canvas = barCanvas(bar);
        scene.renderFrame(canvas);
        bar.setIndeterminate(true);
        // The sweep ticker keeps requesting frames while indeterminate.
        scene.renderFrame(canvas); // register + first tick
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        scene.renderFrame(canvas);
        // No exception, still animating; turning it off stops the sweep.
        bar.setIndeterminate(false);
        assertFalse(bar.isIndeterminate());
    }

    @Test
    void indeterminateBeforeAttachStillAnimates() {
        // Regression (code review): the natural configure-then-add order left a
        // permanently frozen empty track (startSweep no-oped with no scene and
        // nothing re-armed it on attach).
        ProgressBar bar = new ProgressBar().setIndeterminate(true);
        Scene scene = new Scene(bar, clock::get);
        scene.setTextRuler(RULER);
        FakeCanvas canvas = barCanvas(bar);
        scene.renderFrame(canvas); // register + first tick (dt 0)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        scene.renderFrame(canvas);
        assertTrue(bar.sweepPhase() > 0, "the sweep must advance after attach");
    }

    @Test
    void hiddenIndeterminateBarPausesAndResumes() {
        // Regression (code review): a bar in a hidden tab kept the whole window
        // repainting at full frame rate forever.
        ProgressBar bar = new ProgressBar();
        Scene scene = new Scene(bar, clock::get);
        scene.setTextRuler(RULER);
        FakeCanvas canvas = barCanvas(bar);
        scene.renderFrame(canvas);
        bar.setIndeterminate(true);
        scene.renderFrame(canvas);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        scene.renderFrame(canvas);
        double running = bar.sweepPhase();
        assertTrue(running > 0);

        bar.setVisible(false);
        scene.renderFrame(canvas); // the ticker notices and unregisters
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);
        assertEquals(running, bar.sweepPhase(), 1e-9, "hidden: the sweep must not advance");

        bar.setVisible(true);
        scene.renderFrame(canvas); // onPaint re-arms
        scene.renderFrame(canvas); // first tick after re-arm (dt 0)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        scene.renderFrame(canvas);
        assertTrue(bar.sweepPhase() != running, "visible again: the sweep resumes");
    }

    @Test
    void imageViewMeasuresToNaturalSizeOrPreferred() {
        Image image = new Image(32, 16, new byte[32 * 16 * 4]);
        ImageView view = new ImageView(image);
        Size natural = view.measure(Constraints.loose(500, 500));
        assertEquals(32, natural.width(), 1e-6);
        assertEquals(16, natural.height(), 1e-6);

        view.setPreferredSize(64, 64);
        view.markNeedsLayout();
        Size preferred = view.measure(Constraints.loose(500, 500));
        assertEquals(64, preferred.width(), 1e-6);
        assertEquals(64, preferred.height(), 1e-6);
    }

    @Test
    void imageValidatesDimensions() {
        assertEquals(4, new Image(1, 1, new byte[4]).pixels().length);
        try {
            new Image(2, 2, new byte[4]); // needs 16 bytes
            org.junit.jupiter.api.Assertions.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
