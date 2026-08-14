package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless ComboBox: the open/highlight/commit state machine without a native popup. */
class ComboBoxTest extends ComponentTestBase {

    private ComboBox combo;
    private Scene scene;
    private final AtomicInteger selected = new AtomicInteger(-1);

    /** Reaches the protected hook the way only a subclass can. */
    private static final class BaselineProbe extends ComboBox {
        BaselineProbe(List<String> items) {
            super(items);
        }

        float baseline() {
            return baselineOffset();
        }
    }

    private void build() {
        combo = new ComboBox(List.of("one", "two", "three"));
        combo.onSelect(selected::set);
        scene = new Scene(combo);
        scene.setTextRuler(RULER);
        // Tight, deliberately: 32 is also what the field measures at MEDIUM under both rulers,
        // so the geometry-coupled assertions below read the same box the layout gives it.
        scene.layoutPass(200, 32);
        scene.requestFocus(combo);
    }

    private void key(int keyCode) {
        scene.keyEvent(keyCode, true, false, 0);
        scene.keyEvent(keyCode, false, false, 0);
        scene.inputBatchEnded();
    }

    @Test
    void osFocusLossClosesThePopup() {
        // A press in another window/app never reaches observePresses; losing
        // OS focus must dismiss the (always-on-top) popup instead.
        build();
        combo.open();
        assertTrue(combo.isOpen());
        scene.windowFocusChanged(false);
        scene.inputBatchEnded();
        runtime.drain(); // the dismiss decision is deferred one loop turn
        assertFalse(combo.isOpen());
    }

    @Test
    void detachClosesTheOpenPopup() {
        // A programmatically-opened combo has no focus to lose: removal from
        // the tree must still close the popup or it is stranded on screen.
        combo = new ComboBox(java.util.List.of("one", "two"));
        limn.scene.layout.Column root = new limn.scene.layout.Column();
        root.add(combo);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 100);
        combo.open();
        assertTrue(combo.isOpen());

        root.remove(combo);
        assertFalse(combo.isOpen());
    }

    @Test
    void keyboardOpensNavigatesAndCommits() {
        build();
        assertFalse(combo.isOpen());
        key(Keys.DOWN);
        assertTrue(combo.isOpen());
        assertEquals(0, combo.highlightedIndex());

        key(Keys.DOWN);
        key(Keys.DOWN);
        assertEquals(2, combo.highlightedIndex());
        key(Keys.DOWN);
        assertEquals(2, combo.highlightedIndex(), "clamped at the last item");

        key(Keys.ENTER);
        assertFalse(combo.isOpen());
        assertEquals(2, combo.selectedIndex());
        assertEquals("three", combo.selectedItem());
        assertEquals(2, selected.get());
    }

    @Test
    void escapeClosesWithoutSelecting() {
        build();
        key(Keys.SPACE);
        key(Keys.DOWN);
        key(Keys.ESCAPE);
        assertFalse(combo.isOpen());
        assertEquals(0, combo.selectedIndex(), "selection untouched");
        assertEquals(-1, selected.get(), "no callback");
    }

    @Test
    void clickTogglesOpenState() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(combo.isOpen());
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertFalse(combo.isOpen());
    }

    @Test
    void losingFocusClosesThePopup() {
        build();
        key(Keys.DOWN);
        assertTrue(combo.isOpen());
        scene.requestFocus(null);
        assertFalse(combo.isOpen());
    }

    @Test
    void highlightStartsAtTheSelectedItem() {
        build();
        combo.setSelectedIndex(1);
        key(Keys.DOWN);
        assertEquals(1, combo.highlightedIndex());
    }

    /**
     * The setter used to be silent and to clamp, so a screen bound to {@code onSelect} missed
     * every change it made itself, and an index computed from a lookup that missed quietly
     * selected the nearest item instead.
     */
    @Test
    void aProgrammaticSelectionIsAnnouncedAndANonItemIsRefused() {
        build();

        combo.setSelectedIndex(2);
        assertEquals(2, selected.get(), "code and a pick from the popup reach the listener alike");

        selected.set(-1);
        assertThrows(IndexOutOfBoundsException.class, () -> combo.setSelectedIndex(3));
        assertThrows(IndexOutOfBoundsException.class, () -> combo.setSelectedIndex(-1));
        assertEquals(2, combo.selectedIndex(), "a refused index moves nothing");
        assertEquals(-1, selected.get(), "and announces nothing");
    }

    /** The listener reports the selection, so a commit that moved nothing reports nothing. */
    @Test
    void reCommittingTheItemAlreadySelectedClosesThePopupAndSaysNothing() {
        build();
        combo.setSelectedIndex(1);
        selected.set(-1);

        combo.open(); // the highlight opens on the current selection
        key(Keys.ENTER);

        assertFalse(combo.isOpen());
        assertEquals(1, combo.selectedIndex());
        assertEquals(-1, selected.get());
    }

    @Test
    void pressingANonFocusableSiblingClosesTheOpenPopup() {
        // Regression (M6 review): clicking a Label / empty area never moves
        // focus, so focus-loss alone did not dismiss the popup. The scene
        // press-observer must close it.
        limn.scene.layout.Column root = new limn.scene.layout.Column();
        combo = new ComboBox(List.of("one", "two"));
        limn.scene.Widget filler = new limn.scene.Widget() {
            @Override
            protected limn.scene.Size onMeasure(limn.scene.Constraints c) {
                return c.constrain(200, 40);
            }
        }; // not focusable
        root.add(combo);
        root.add(filler);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 100);
        scene.requestFocus(combo);

        key(Keys.DOWN);
        assertTrue(combo.isOpen());
        // Derived, not a literal: the press used to sit at y = 70, which was *past* the
        // filler's bottom edge (28 + 40) and dismissed for the wrong reason. The combo is
        // 32 tall now, so 70 lands inside the filler by luck rather than by construction.
        float insideTheFiller = combo.height() + filler.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 20, insideTheFiller);
        scene.inputBatchEnded();
        assertFalse(combo.isOpen(), "outside press must dismiss the popup");
    }

    // ------------------------------------------------------------- the ramp

    /** A combo alone in a scene under the font-faithful ruler, measured unbounded. */
    private static Size measureAt(ControlSize step, ComboBox box) {
        Scene host = new Scene(box);
        host.setTextRuler(SCALED_RULER);
        box.setControlSize(step);
        return box.measure(Constraints.loose(1000, 1000));
    }

    @Test
    void mediumLosesExactlyTheFractionDecisionThreeRemoves() {
        // The one sanctioned MEDIUM change: the height was lineHeight + 2*(spacingSmall + 2)
        // = 16.40625 + 16 = 32.40625, and is now max(controlHeight, lineHeight + 2*padV)
        // = max(32, 30.40625) = 32. Nothing else about the field moves at MEDIUM.
        Size size = measureAt(ControlSize.MEDIUM, new ComboBox(List.of("one", "two", "three")));
        assertEquals(32f, size.height(), "32, not 32.40625");
        // "three" = 5 code points x 0.6 x 14 = 42, plus 2 x fieldPadH 12, plus caret gutter 24.
        // Delta because the ruler's 0.6 em advance is not exact in binary, not because the
        // token arithmetic is approximate.
        assertEquals(90f, size.width(), 0.001f);
    }

    @Test
    void theBoxBindsTheHeightAtEveryStep() {
        // lineHeight + 2*padV is below controlHeight at all five steps, so the resolved height
        // IS the ramp: 24 / 28 / 32 / 40 / 50, every one even (the parity rule).
        float[] expected = {24, 28, 32, 40, 50};
        int i = 0;
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            float height = measureAt(step, new ComboBox(List.of("one", "two", "three"))).height();
            assertEquals(expected[i], height, step + " resolves to its control height");
            assertEquals(t.controlHeight(), height, step + " is the tabled height, not a formula");
            assertEquals(0f, height % 2, step + " height is even");
            i++;
        }
    }

    @Test
    void theWidthIsTextPlusTwoPaddingsPlusTheCaretGutter() {
        // 3 x body (5 code points at 0.6 em) + 2 x fieldPadH + comboCaretGutter.
        float[] expected = {65, 76, 90, 106, 127};
        int i = 0;
        for (ControlSize step : ControlSize.values()) {
            float width = measureAt(step, new ComboBox(List.of("one", "two", "three"))).width();
            assertEquals(expected[i], width, 0.001f, step + " width");
            i++;
        }
    }

    @Test
    void theCaretClearsTheBorderAndKeepsItsAngleAtEveryStep() {
        // The chevron is drawn at width() - comboCaretCenterX with half-width chevronHalfW;
        // its half-height is half the half-width, so the arrow angle is invariant. The clip
        // that keeps the label off it is the gutter + 2 at every step.
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            assertTrue(t.comboCaretCenterX() - t.chevronHalfW() >= 4,
                    step + " chevron clears the border");
            assertEquals(t.comboCaretGutter() + 2, t.comboTextClip(),
                    step + " keeps the deliberate 2pt overhang");
        }
    }

    @Test
    void theBaselineIsWhatThePaintUses() {
        // Flex.CrossAlignment.BASELINE reads this to align a row that mixes steps; the values
        // are ADR 002 3.7's table, which is (height - lineHeight)/2 + ascent.
        float[] expected = {15.759766f, 18.101563f, 20.785156f, 25.468750f, 31.494141f};
        int i = 0;
        for (ControlSize step : ControlSize.values()) {
            BaselineProbe box = new BaselineProbe(List.of("one", "two", "three"));
            Size size = measureAt(step, box);
            box.layoutBox(0, 0, size.width(), size.height());
            assertEquals(expected[i], box.baseline(), 0.0005f, step + " baseline");
            i++;
        }
    }

    @Test
    void everyStrokeTheFieldPaintsIsIdenticalAtEveryStep() {
        // The pixel-lock rule, checked mechanically: the border (1) and the caret pen (1.8)
        // are the only strokes, and neither has a five-column row. Painted unfocused, so the
        // animated BORDER -> FOCUS_RING width is settled at exactly BORDER.
        List<Float> mediumWidths = null;
        for (ControlSize step : ControlSize.values()) {
            ComboBox box = new ComboBox(List.of("one", "two", "three"));
            Scene host = new Scene(box);
            host.setTextRuler(SCALED_RULER);
            box.setControlSize(step);
            host.layoutPass(200, 60);
            StrokeRecordingCanvas canvas = new StrokeRecordingCanvas(200, 60);
            host.renderFrame(canvas);
            List<Float> widths = canvas.widths();
            assertEquals(List.of(Strokes.BORDER, Strokes.ARROW_PEN), widths,
                    step + " paints one border and one caret pen, both unscaled");
            if (mediumWidths == null) {
                mediumWidths = widths;
            }
            assertEquals(mediumWidths, widths, step + " matches the first step's multiset");
        }
    }

    /** A combo over names that share first letters, which is where type-ahead earns its keep. */
    private ComboBox typeAheadCombo() {
        ComboBox box = new ComboBox(List.of("Alpha", "Bravo", "Bengal", "Beta", "Charlie"));
        Scene typeScene = new Scene(box);
        typeScene.setTextRuler(RULER);
        typeScene.layoutPass(200, 32);
        typeScene.requestFocus(box);
        this.scene = typeScene;
        return box;
    }

    private void type(String text) {
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    /**
     * Typing letters jumps to what they spell. Without this the only way through a long clamped
     * list is one arrow press per item.
     */
    @Test
    void typingJumpsToTheItemTheLettersSpell() {
        ComboBox box = typeAheadCombo();
        box.open();

        type("c");
        assertEquals(4, box.highlightedIndex(), "one letter jumps to the first match");

        box.close();
        box.open();
        type("be");
        assertEquals(2, box.highlightedIndex(),
                "a longer prefix must beat the shorter one it extends");
    }

    /**
     * The behaviour that does not fall out of prefix matching: one letter pressed again moves to
     * the NEXT item starting with it, which is the only way a list of things sharing a first
     * letter is navigable.
     */
    @Test
    void repeatingOneLetterCyclesThroughItsMatches() {
        ComboBox box = typeAheadCombo();
        box.open();

        type("b");
        assertEquals(1, box.highlightedIndex(), "Bravo");
        type("b");
        assertEquals(2, box.highlightedIndex(), "Bengal");
        type("b");
        assertEquals(3, box.highlightedIndex(), "Beta");
        type("b");
        assertEquals(1, box.highlightedIndex(), "and round again");
    }

    /** Nothing spells this, so the highlight must stay where the user left it. */
    @Test
    void aPrefixThatMatchesNothingLeavesTheHighlightAlone() {
        ComboBox box = typeAheadCombo();
        box.open();
        type("c");
        int before = box.highlightedIndex();

        type("zz");
        assertEquals(before, box.highlightedIndex());

        // And the dead prefix must not poison the next keystroke.
        type("a");
        assertEquals(0, box.highlightedIndex(), "Alpha");
    }

    /** A closed combo is not a search box: letters must not silently change the selection. */
    @Test
    void typingIntoAClosedComboChangesNothing() {
        ComboBox box = typeAheadCombo();
        int before = box.selectedIndex();

        type("c");
        assertEquals(before, box.selectedIndex());
        assertFalse(box.isOpen());
    }

    /**
     * Page keys were falling through to {@code handled = false}. Headless there is no native
     * popup to measure, so the step is the documented floor of one row; what this pins is that
     * the keys are answered at all, which is what they were not.
     */
    @Test
    void pageKeysMoveTheHighlightInsteadOfBeingIgnored() {
        ComboBox box = typeAheadCombo();
        box.open();
        box.setSelectedIndex(0);

        scene.keyEvent(Keys.PAGE_DOWN, true, false, 0);
        scene.keyEvent(Keys.PAGE_DOWN, false, false, 0);
        scene.inputBatchEnded();
        assertTrue(box.highlightedIndex() > 0, "Page Down must move the highlight");

        int reached = box.highlightedIndex();
        scene.keyEvent(Keys.PAGE_UP, true, false, 0);
        scene.keyEvent(Keys.PAGE_UP, false, false, 0);
        scene.inputBatchEnded();
        assertTrue(box.highlightedIndex() < reached, "Page Up must move it back");
    }
}
