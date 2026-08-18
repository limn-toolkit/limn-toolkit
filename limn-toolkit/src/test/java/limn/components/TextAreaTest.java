package limn.components;

import limn.graphics.Font;
import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAreaTest extends ComponentTestBase {

    private TextArea area;
    private Scene scene;
    private TextFieldTest.MockClipboard clipboard;

    private void build(String text) {
        area = new TextArea();
        scene = new Scene(area);
        scene.setTextRuler(RULER);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(200, 100);
        scene.requestFocus(area);
        area.setText(text);
    }

    private void key(int keyCode, int mods) {
        scene.keyEvent(keyCode, true, false, mods);
        scene.keyEvent(keyCode, false, false, mods);
        scene.inputBatchEnded();
    }

    @Test
    void altGrDoesNotTriggerCtrlShortcuts() {
        // Windows reports AltGr as Ctrl+Alt: a printable AltGr combo must not
        // fire the Ctrl letter shortcuts (select-all/undo/cut...).
        build("abc");
        key(Keys.A, Keys.MOD_CONTROL | Keys.MOD_ALT);
        scene.charTyped('ą');
        scene.inputBatchEnded();
        assertEquals("abcą", area.text());
    }

    @Test
    void enterInsertsNewlinesAndArrowsNavigateLines() {
        build("");
        scene.charTyped('a');
        key(Keys.ENTER, 0);
        scene.charTyped('b');
        scene.inputBatchEnded();
        assertEquals("a\nb", area.text());

        key(Keys.UP, 0);
        assertEquals(0, area.model().lineOf(area.model().cursor()));
        key(Keys.DOWN, 0);
        assertEquals(1, area.model().lineOf(area.model().cursor()));
    }

    @Test
    void selectionSpansLinesAndCopies() {
        build("first\nsecond\nthird");
        key(Keys.HOME, 0);
        // cursor at start of "third"? setText puts cursor at end; HOME → line start.
        area.model().setCursor(0, false);
        key(Keys.DOWN, Keys.MOD_SHIFT);
        key(Keys.END, Keys.MOD_SHIFT);
        assertEquals("first\nsecond", area.model().selectedText());
        key(Keys.C, Keys.MOD_CONTROL);
        assertEquals("first\nsecond", clipboard.value);
    }

    @Test
    void wheelScrollsAndClampsVertically() {
        // 30 lines x 12pt = 360pt of content in a 100pt-high area.
        build("line\n".repeat(30).trim());
        assertEquals(0, area.scrollYOffset(), 1e-3);
        scene.scrolled(0, -1, 50, 50);
        scene.inputBatchEnded();
        // A detent is a DEVICE unit and has no five-column row; read it from Strokes rather
        // than re-baking the 48, so this line is a guard on the lock instead of a duplicate.
        assertEquals(Strokes.WHEEL_STEP, area.scrollYOffset(), 1e-3);
        scene.scrolled(0, -100, 50, 50);
        scene.inputBatchEnded();
        assertTrue(area.scrollYOffset() < 360, "clamped to content");
        scene.scrolled(0, +1000, 50, 50);
        scene.inputBatchEnded();
        assertEquals(0, area.scrollYOffset(), 1e-3, "clamped to top");
    }

    @Test
    void longLinesScrollHorizontally() {
        // 600pt wide line in a 176pt viewport (200 - 2 x fieldPadH: the horizontal inset is
        // TextField's, which is what puts a field and an area on the same text column).
        build("x".repeat(60));
        key(Keys.END, 0);
        assertTrue(area.scrollXOffset() > 0, "END on a long line scrolls right");
        key(Keys.HOME, 0);
        assertEquals(0, area.scrollXOffset(), 1e-3);
    }

    @Test
    void draggableVerticalScrollbarThumb() {
        build("line\n".repeat(30).trim());
        // The shared ScrollBar occupies the right strip; its thumb starts at the
        // top. Grab it and drag down (the bar is ALWAYS-visible on a TextArea).
        // ScrollBar does not participate in the size axis, so this strip is 15pt at
        // every step; see the class javadoc on what that costs at XSMALL.
        float thumbX = 200 - 4; // inside the right-edge scrollbar strip
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, thumbX, 6);
        scene.inputBatchEnded();
        scene.mouseMoved(thumbX, 55);
        scene.inputBatchEnded();
        assertTrue(area.scrollYOffset() > 0, "dragging the thumb scrolls: " + area.scrollYOffset());
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, thumbX, 55);
        scene.inputBatchEnded();
    }

    @Test
    void pasteMultilineText() {
        build("");
        clipboard.value = "a\nbb\nccc";
        key(Keys.V, Keys.MOD_SUPER);
        assertEquals("a\nbb\nccc", area.text());
        assertEquals(3, area.model().lineCount());
    }

    // ------------------------------------------------------- control sizes

    /** A fresh area alone in its own scene, at {@code step}, under the given ruler. */
    private static TextArea areaAt(ControlSize step, limn.graphics.TextRuler ruler) {
        TextArea a = new TextArea();
        Scene host = new Scene(a);
        host.setTextRuler(ruler);
        a.setControlSize(step);
        return a;
    }

    @Test
    void preferredSizeFollowsTheStep() {
        // MEDIUM must still measure 320 x 140: the literals the field initializers carried.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            SizeTokens t = SizeTokens.of(step);
            Size measured = a.measure(Constraints.loose(1000, 1000));
            assertEquals(t.areaWidth(), measured.width(), 1e-3, step + " width");
            assertEquals(t.areaHeight(), measured.height(), 1e-3, step + " height");
        }
    }

    @Test
    void explicitPreferredSizeOverridesPerAxis() {
        // FormsScene passes 0 for the width (let the column stretch it); a negative value is
        // the "unset" sentinel, so pinning one axis leaves the other on its token.
        TextArea a = areaAt(ControlSize.LARGE, SCALED_RULER);
        a.setPreferredSize(-1, 150);
        Size measured = a.measure(Constraints.loose(1000, 1000));
        assertEquals(SizeTokens.of(ControlSize.LARGE).areaWidth(), measured.width(), 1e-3);
        assertEquals(150, measured.height(), 1e-3);
    }

    @Test
    void baselineIsTheFirstLineBaselineAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            SizeTokens t = SizeTokens.of(step);
            a.measure(Constraints.loose(1000, 1000));
            a.layoutBox(0, 0, t.areaWidth(), t.areaHeight());
            float expected = t.areaPad() + SCALED_RULER.measure("Hg", t.body()).ascent();
            assertEquals(expected, a.baselineOffset(), 1e-3,
                    step + " aligns on line 0's baseline, not on the bottom edge");
        }
    }

    @Test
    void wheelDetentIsPixelLockedAtEveryStep() {
        // The point of locking WHEEL_STEP: one physical flick travels the same physical
        // distance in a dense editor and a roomy one.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, RULER);
            Scene host = a.scene();
            host.layoutPass(200, 100);
            a.setText("line\n".repeat(30).trim());
            host.scrolled(0, -1, 50, 50);
            host.inputBatchEnded();
            assertEquals(Strokes.WHEEL_STEP, a.scrollYOffset(), 1e-3, step + " detent");
        }
    }

    @Test
    void clickMapsThroughTheAnisotropicPadAtEveryStep() {
        // The one thing a measure/paint token split breaks silently: RULER puts code-point
        // boundaries at 0/10/20/30..., so a press 26pt into the text must land on index 3 at
        // every step, but only if the hit test insets by the SAME pads the paint does, and
        // those are two different tokens: fieldPadH across, areaPad down. Feeding
        // areaPad to the x axis here would land on index 2 at MEDIUM (26 - 4 = 22 -> boundary
        // 2), which is exactly the drift the split can cause.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, RULER);
            Scene host = a.scene();
            host.layoutPass(200, 100);
            a.setText("abcdef");
            SizeTokens t = SizeTokens.of(step);
            host.mouseButton(Keys.MOUSE_LEFT, true, 0, t.fieldPadH() + 26, t.areaPad() + 1);
            host.inputBatchEnded();
            assertEquals(3, a.model().cursor(), step + " maps the press through the two pads");
        }
    }

    @Test
    void textStartsOnTheSameColumnAsATextFieldAtEveryStep() {
        // The whole point of the shared inset: a field and an area stacked in a form put their first
        // character on one x column. Both are scene roots at (0,0), so the recorded absolute x
        // IS the inset, and the area hides its inset in a translate, which is why the canvas
        // tracks the transform rather than reading drawText's argument.
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);

            TextArea a = areaAt(step, RULER);
            Scene areaHost = a.scene();
            areaHost.layoutPass(200, 100);
            a.setText("abc");
            a.model().setCursor(0, false); // caret at the start: its x is the inset too
            GeometryCanvas areaCanvas = new GeometryCanvas(200, 100);
            areaHost.renderFrame(areaCanvas);

            TextField field = new TextField();
            Scene fieldHost = new Scene(field);
            fieldHost.setTextRuler(RULER);
            field.setControlSize(step);
            fieldHost.layoutPass(200, 100);
            field.setText("abc");
            GeometryCanvas fieldCanvas = new GeometryCanvas(200, 100);
            fieldHost.renderFrame(fieldCanvas);

            assertEquals(t.fieldPadH(), areaCanvas.firstTextX(), 1e-3,
                    step + " area insets its text by fieldPadH");
            assertEquals(fieldCanvas.firstTextX(), areaCanvas.firstTextX(), 1e-3,
                    step + " field and area share the text column");
            // caretRect is scene-absolute and the area is at the origin, so this reads the
            // same inset from the IME/candidate-window path, which clamps against padX.
            assertEquals(t.fieldPadH(), a.caretRect().x(), 1e-3,
                    step + " the caret clamp follows the same pad");
        }
    }

    @Test
    void textClipTakesTheAaBleedAcrossOnly() {
        // The horizontal clip carries TextField's -AA_BLEED / +2*AA_BLEED allowance so the
        // first and last glyph keep their antialiasing fringe. The VERTICAL clip stays tight on
        // the pad: it is a scroll boundary against the border, and bleeding it would let a
        // half-scrolled line's ink sit on the rounded border.
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            Scene host = a.scene();
            host.layoutPass(320, 140);
            a.setText("one\ntwo");
            GeometryCanvas canvas = new GeometryCanvas(320, 140);
            host.renderFrame(canvas);

            SizeTokens t = SizeTokens.of(step);
            float viewW = 320 - 2 * t.fieldPadH();
            float viewH = 140 - 2 * t.areaPad();
            assertTrue(canvas.hasClip(t.fieldPadH() - Strokes.AA_BLEED, t.areaPad(),
                            viewW + 2 * Strokes.AA_BLEED, viewH),
                    step + " clips the text run at the bled pad; saw " + canvas.clipsAsText());
        }
    }

    @Test
    void everyStrokeIsIdenticalAtEveryStep() {
        // The pixel-lock rule, checked mechanically. Painted UNFOCUSED so the animated
        // BORDER -> FOCUS_RING width is settled at exactly BORDER; a mid-fade recording is
        // fractional and flaky by construction. ScrollBar draws no strokes at all.
        List<Float> mediumWidths = null;
        for (ControlSize step : ControlSize.values()) {
            TextArea a = areaAt(step, SCALED_RULER);
            Scene host = a.scene();
            host.layoutPass(320, 140);
            a.setText("one\ntwo");
            StrokeRecordingCanvas canvas = new StrokeRecordingCanvas(320, 140);
            host.renderFrame(canvas);
            List<Float> widths = canvas.widths();
            assertEquals(List.of(Strokes.BORDER), widths,
                    step + " paints one unscaled border and nothing else");
            if (mediumWidths == null) {
                mediumWidths = widths;
            }
            assertEquals(mediumWidths, widths, step + " matches the first step's multiset");
        }
    }

    /**
     * Records what the paint pass actually asks for, in SCENE coordinates. {@link FakeCanvas}
     * ignores the transform, and TextArea puts its whole content inset in a {@code translate}
     * while drawing every line at {@code x == 0}, so the translate stack has to be tracked
     * here or "where does the first glyph land" is unanswerable from the recorded arguments.
     */
    private static final class GeometryCanvas extends FakeCanvas {

        private final List<float[]> clips = new ArrayList<>();
        private final List<float[]> texts = new ArrayList<>();
        private final Deque<float[]> stack = new ArrayDeque<>();
        private float tx;
        private float ty;

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void save() {
            stack.push(new float[]{tx, ty});
        }

        @Override
        public void restore() {
            if (!stack.isEmpty()) { // lenient: a widget that throws mid-paint must not mask itself
                float[] saved = stack.pop();
                tx = saved[0];
                ty = saved[1];
            }
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            clips.add(new float[]{tx + x, ty + y, w, h});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new float[]{tx + x, ty + y});
        }

        float firstTextX() {
            assertTrue(!texts.isEmpty(), "nothing was drawn");
            return texts.get(0)[0];
        }

        boolean hasClip(float x, float y, float w, float h) {
            return clips.stream().anyMatch(c -> Math.abs(c[0] - x) < 1e-3
                    && Math.abs(c[1] - y) < 1e-3
                    && Math.abs(c[2] - w) < 1e-3
                    && Math.abs(c[3] - h) < 1e-3);
        }

        String clipsAsText() {
            return clips.stream().map(Arrays::toString).toList().toString();
        }
    }

    @Test
    void aReservedBarNarrowsTheTextColumn() {
        // Observable through the reach: the column loses the bar's width, so the
        // same long line has exactly that much more to scroll past.
        float overlaid = maxScrollXWith(ScrollGutters.Layout.OVERLAY);
        float reserved = maxScrollXWith(ScrollGutters.Layout.RESERVED);

        assertEquals(overlaid + ScrollBar.thickness(), reserved, 0.5f,
                "the reserved strip did not come out of the text column");
    }

    /** How far right the area can scroll a long line at {@code layout}. */
    private float maxScrollXWith(ScrollGutters.Layout layout) {
        TextArea area = new TextArea();
        area.setBarLayout(layout);
        // Long AND many: the column only loses width to the vertical bar, so the
        // text has to overflow downwards before there is a strip to lose it to.
        area.setText(("a line long enough to overflow any sane column ".repeat(6) + "\n")
                .repeat(30));
        Scene host = new Scene(area);
        host.setTextRuler(RULER);
        host.setClipboard(new TextFieldTest.MockClipboard());
        host.layoutPass(240, 120);
        area.scrollBy(10_000, 0);
        host.layoutPass(240, 120);
        return area.scrollXOffset();
    }

    /**
     * Page keys used to fall through to {@code handled = false} and do nothing at all (not even
     * scroll), leaving a keyboard user in a long document holding Down or jumping to an edge.
     */
    @Test
    void pageKeysMoveTheCaretAViewportAtATime() {
        StringBuilder document = new StringBuilder();
        for (int line = 0; line < 60; line++) {
            document.append("line ").append(line).append('\n');
        }
        build(document.toString());
        area.model().setCursor(0, false);

        key(Keys.PAGE_DOWN, 0);
        int afterOnePage = area.model().lineOf(area.model().cursor());
        assertTrue(afterOnePage > 1,
                "a page must be more than one line; it moved " + afterOnePage);
        assertTrue(afterOnePage < 60, "a page must be less than the whole document");

        key(Keys.PAGE_DOWN, 0);
        assertEquals(2 * afterOnePage, area.model().lineOf(area.model().cursor()),
                "two pages must move twice as far as one");

        key(Keys.PAGE_UP, 0);
        assertEquals(afterOnePage, area.model().lineOf(area.model().cursor()));
    }

    @Test
    void shiftPageExtendsTheSelectionRatherThanMovingPastIt() {
        StringBuilder document = new StringBuilder();
        for (int line = 0; line < 60; line++) {
            document.append("line ").append(line).append('\n');
        }
        build(document.toString());
        area.model().setCursor(0, false);

        key(Keys.PAGE_DOWN, Keys.MOD_SHIFT);
        assertTrue(area.model().hasSelection(), "Shift+Page is how a screenful is taken");
        assertTrue(area.model().selectedText().startsWith("line 0"),
                "the selection must run from where the caret was");
    }
}
