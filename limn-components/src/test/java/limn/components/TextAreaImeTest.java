package limn.components;

import limn.graphics.Paint;
import limn.graphics.TextMetrics;
import limn.scene.ControlSize;
import limn.scene.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IME composition (preedit) routing for the multiline {@link TextArea}: the
 * still-composing text is shown on the cursor's line but never enters the model
 * until committed as ordinary character input.
 */
class TextAreaImeTest extends ComponentTestBase {

    private TextArea area;
    private Scene scene;

    @BeforeEach
    void setUp() {
        area = new TextArea();
        scene = new Scene(area);
        scene.setTextRuler(RULER);
        scene.layoutPass(320, 140);
        scene.requestFocus(area);
    }

    private void preedit(String text, int[] blocks, int focusedBlock, int caret) {
        scene.preeditChanged(text, blocks, focusedBlock, caret);
        scene.inputBatchEnded();
    }

    private void commit(String text) {
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    @Test
    void editableAreaAcceptsTextInput() {
        assertTrue(area.acceptsTextInput());
    }

    @Test
    void preeditIsShownButNotCommitted() {
        preedit("に", new int[]{1}, 0, 1);
        assertEquals("に", area.composingText());
        assertEquals("", area.text(), "composition must not enter the model");
    }

    @Test
    void commitInsertsAndComposingClears() {
        preedit("にほn", new int[]{3}, 0, 3);
        commit("日本");
        preedit("", new int[]{}, -1, 0);
        assertEquals("日本", area.text());
        assertEquals("", area.composingText());
    }

    @Test
    void commitLandsOnTheCursorLine() {
        area.setText("first\nsecond");
        area.model().moveHome(false); // start of the last line ("second")
        preedit("あ", new int[]{1}, 0, 1);
        commit("亜");
        preedit("", new int[]{}, -1, 0);
        assertEquals("first\n亜second", area.text());
    }

    @Test
    void losingFocusDropsComposition() {
        preedit("に", new int[]{1}, 0, 1);
        scene.requestFocus(null);
        assertEquals("", area.composingText());
    }

    /**
     * Records horizontal strokes (the two preedit underlines) in the space the composing
     * line paints in. {@code translate} is a no-op on {@code FakeCanvas}, so the y that
     * arrives here is content space: line 0's top is 0.
     */
    private static final class UnderlineRecorder extends FakeCanvas {

        /** {@code {y, strokeWidth}} per horizontal stroke. */
        final List<float[]> underlines = new ArrayList<>();

        UnderlineRecorder(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            if (y1 == y2) { // the caret is vertical; only the underlines are flat
                underlines.add(new float[]{y1, strokeWidth});
            }
        }
    }

    /**
     * ADR 002 §10.2 #21, the executable form of §7.11 trap 3. The underline used to sit at
     * {@code baseline + 2}, i.e. <em>inside</em> the descender at every step: 1.42&nbsp;pt above
     * the ink edge at MEDIUM and 2.64&nbsp;pt at XLARGE, where it cut the descenders outright.
     * It now derives from the line's own anchor like TextField's does. Must run under
     * {@link #SCALED_RULER}: {@link #RULER} reports {@code ascent + descent == 10} and
     * {@code lineHeight == 12} for every font, which makes the old and new expressions
     * coincide by accident.
     */
    @Test
    void preeditUnderlineClearsTheDescenderAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            TextArea a = new TextArea();
            Scene host = new Scene(a);
            host.setTextRuler(SCALED_RULER);
            a.setControlSize(step);
            SizeTokens t = SizeTokens.of(step);
            host.layoutPass(t.areaWidth(), t.areaHeight());
            host.requestFocus(a);
            host.preeditChanged("にほn", new int[]{3}, 0, 3);
            host.inputBatchEnded();

            UnderlineRecorder canvas = new UnderlineRecorder(t.areaWidth(), t.areaHeight());
            host.renderFrame(canvas);

            TextMetrics m = SCALED_RULER.measure("Hg", t.body());
            assertEquals(2, canvas.underlines.size(),
                    step + " draws the resting underline and the converting one");
            for (float[] stroke : canvas.underlines) {
                float y = stroke[0];
                assertTrue(y >= m.height() - 1e-3f,
                        step + " underline at " + y + " must clear the ink bottom " + m.height());
                assertTrue(y <= m.lineHeight() + 1e-3f,
                        step + " underline at " + y + " must stay inside the line box "
                                + m.lineHeight());
                assertTrue(t.areaPad() + y < t.areaHeight() - t.areaPad(),
                        step + " underline must stay inside the padded viewport");
            }
            // The 1-vs-2 contrast is what says "this block is converting": locked at
            // every step, never scaled.
            assertEquals(List.of(Strokes.IME_UNDERLINE, Strokes.IME_UNDERLINE_ACTIVE),
                    canvas.underlines.stream().map(s -> s[1]).sorted().toList(),
                    step + " keeps both underline weights unscaled");
        }
    }
}
