package limn.graphics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The half of a backdrop effect that has nothing to do with a GPU: what the parameters accept, and
 * what a renderer that cannot draw one does instead.
 */
class BackdropEffectTest {

    @Test
    void aRendererWithoutBackdropSupportFillsTheTintInstead() {
        // The degradation is the default method, not a backend feature, so it is what every
        // Canvas in this repository does until one overrides it, and it is worth pinning: a
        // renderer that silently drew nothing would leave a hole where a panel belongs.
        FillRecordingCanvas canvas = new FillRecordingCanvas();
        Color tint = new Color(0.1f, 0.2f, 0.3f, 0.5f);
        RoundRect shape = RoundRect.of(4, 6, 100, 40, 8);

        canvas.fillBackdropRoundRect(shape, new BackdropEffect.Clear(tint, 12f, 0.4f));

        assertEquals(1, canvas.fills.size());
        assertEquals(shape, canvas.fills.get(0).shape());
        assertSame(tint, canvas.fills.get(0).paint(), "the tint is the fallback fill");
    }

    @Test
    void theFallbackKeepsTheGeometryOfTheConvenienceForm() {
        FillRecordingCanvas canvas = new FillRecordingCanvas();

        canvas.fillBackdropRoundRect(2, 3, 20, 10, 5, new BackdropEffect.Wash(Color.BLACK, 0f));

        assertEquals(RoundRect.of(2, 3, 20, 10, 5), canvas.fills.get(0).shape());
    }

    @Test
    void aDegenerateSizeDrawsNothing() {
        FillRecordingCanvas canvas = new FillRecordingCanvas();

        canvas.fillBackdropRoundRect(0, 0, 0, 10, 0, new BackdropEffect.Wash(Color.BLACK, 1f));
        canvas.fillBackdropRoundRect(0, 0, 10, -1, 0, new BackdropEffect.Wash(Color.BLACK, 1f));

        assertEquals(List.of(), canvas.fills);
    }

    @Test
    void parametersAreValidatedWhereTheyAreWritten() {
        // A bad value here is a shader that draws something meaningless, with nothing to blame it
        // on, so the record refuses it at construction rather than at the next frame.
        assertThrows(IllegalArgumentException.class,
                () -> new BackdropEffect.Clear(Color.WHITE, -1f, 0f));
        assertThrows(IllegalArgumentException.class,
                () -> new BackdropEffect.Clear(Color.WHITE, 8f, 1.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new BackdropEffect.Wash(Color.WHITE, -0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new BackdropEffect.Pixelate(Color.WHITE, 0.5f));
        assertThrows(NullPointerException.class,
                () -> new BackdropEffect.Pixelate(null, 4f));
    }

    @Test
    void theDefaultGlassIsAPaneWithARimAndAFringe() {
        BackdropEffect.Clear glass = new BackdropEffect.Clear(Color.WHITE);

        assertEquals(12f, glass.thickness());
        assertEquals(0.35f, glass.dispersion());
    }

    /** Records the fills the backdrop fallback makes; everything else stays a stub. */
    private static final class FillRecordingCanvas extends SurfaceRecordingCanvas {

        record Fill(RoundRect shape, Paint paint) {
        }

        private final List<Fill> fills = new ArrayList<>();

        FillRecordingCanvas() {
            super(200, 100);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            fills.add(new Fill(roundRect, paint));
        }
    }
}
