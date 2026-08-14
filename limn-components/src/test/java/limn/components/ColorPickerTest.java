package limn.components;

import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker's behaviour, driven through a real scene with no GL.
 *
 * <p>{@link #hueSurvivesADragThroughBlack()} is the one to keep: it pins why the
 * widget holds hue itself instead of reading it back off the colour. Black carries
 * no hue, so a picker that re-derived would hand you red the moment you dragged
 * the value to zero: the classic way a colour picker feels broken.
 *
 * <p>{@link #cyanStaysWhereItIsPut()} is its counterpart for the numeric rows, and
 * pins the same argument one model further out: CMYK has four axes over a
 * three-axis colour, so a row re-derived from the colour is always the one with
 * {@code min(C,M,Y) = 0}, and a step of ink on a channel already at zero used to
 * come back somewhere else entirely, which reads as the press having landed on
 * another spinner.
 */
class ColorPickerTest extends ComponentTestBase {

    private ColorPicker picker;
    private Scene scene;
    private final List<Color> changes = new ArrayList<>();

    @BeforeEach
    void layOutAPicker() {
        picker = new ColorPicker();
        picker.onChange(changes::add);
        scene = new Scene(picker);
        scene.setTextRuler(RULER);
        // Tall enough for CMYK's four channel lines: a rail laid out past the
        // bottom of the scene is never pressed, and the test that pressed it
        // would report "no change" as though the rail were dead.
        scene.layoutPass(320, 520);
    }

    @Test
    void openingOnAColourSelectsIt() {
        picker.setInitialColor(Color.rgb(0x3366CC));
        Color chosen = picker.color();
        assertEquals(0x33 / 255f, chosen.r(), 0.01f);
        assertEquals(0x66 / 255f, chosen.g(), 0.01f);
        assertEquals(0xCC / 255f, chosen.b(), 0.01f);
    }

    @Test
    void hueSurvivesADragThroughBlack() {
        picker.setInitialColor(Color.rgb(0x00A0FF));
        float blue = picker.hue();
        assertTrue(blue > 180 && blue < 220, "expected a blue hue, got " + blue);

        // Past the bottom of the field, which clamps to value 0 (black), and back.
        dragFieldTo(60, 260);
        assertEquals(0, picker.color().value(), 1e-4f, "the drag should have reached black");
        dragFieldTo(60, 4);

        assertEquals(blue, picker.hue(), 0.5f, "the hue was lost on the way through black");
        assertTrue(picker.color().value() > 0.9f);
    }

    @Test
    void aHexCodeMovesTheSelection() {
        picker.setInitialColor(Color.BLACK);
        changes.clear();
        assertTrue(picker.applyHex("#FF8800"));
        assertEquals("#FF8800", picker.color().toHex());
        assertEquals(1, changes.size(), "a typed colour is reported once");
    }

    @Test
    void anUnparseableHexCodeChangesNothing() {
        picker.setInitialColor(Color.rgb(0x112233));
        changes.clear();
        assertFalse(picker.applyHex("#FF888"));
        assertFalse(picker.applyHex("#GG0000"));
        assertFalse(picker.applyHex("#"));
        assertEquals("#112233", picker.color().toHex(),
                "a half-typed field must leave the selection alone");
        assertTrue(changes.isEmpty(), "and must not report an edit");
    }

    @Test
    void draggingTheFieldPicksSaturationAndValue() {
        picker.setInitialColor(Color.rgb(0xFF0000));
        changes.clear();
        // The saturation/value field is the first thing in the column, so its
        // top-left corner is the widget's, and that corner is white.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 2, 2);
        scene.inputBatchEnded();

        Color chosen = picker.color();
        assertTrue(chosen.saturation() < 0.1f, "near-zero saturation at the left edge");
        assertTrue(chosen.value() > 0.9f, "near-full value at the top");
        assertFalse(changes.isEmpty());
    }

    @Test
    void aDragReportsExactlyOneCommitCarryingTheSettledColour() {
        List<Color> committed = new ArrayList<>();
        picker.onCommit(committed::add);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 20, 20);
        scene.mouseMoved(40, 40);
        scene.mouseMoved(60, 60);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 60, 60);
        scene.inputBatchEnded();
        assertEquals(1, committed.size(), "an undo gesture must close once, not once per move");
        // The decision, not a preview: what onCommit carries is what the picker is left showing,
        // so a caller can write it straight to a document without asking the widget again.
        assertEquals(picker.color(), committed.get(0));
        assertEquals(changes.get(changes.size() - 1), committed.get(0),
                "the commit must agree with the last preview rather than trail it");
    }

    /**
     * The set's majority answer for a null listener is NullPointerException; this picker used to
     * swap in a no-op instead, so "pass null to remove my listener" worked here and threw on nine
     * other widgets. One answer, and it is the loud one.
     */
    @Test
    void aNullListenerIsRefusedRatherThanSilentlyIgnored() {
        assertThrows(NullPointerException.class, () -> picker.onChange(null));
        assertThrows(NullPointerException.class, () -> picker.onCommit(null));
    }

    @Test
    void arrowKeysNudgeTheSelection() {
        picker.setInitialColor(Color.hsv(0, 0.5f, 0.5f, 1));
        changes.clear();
        picker.requestFocus();
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertFalse(changes.isEmpty(), "an arrow key must move the selection");
        assertTrue(picker.color().saturation() > 0.5f);
    }

    @Test
    void turningAlphaOffMakesEveryAnswerOpaque() {
        picker.setInitialColor(new Color(1f, 0f, 0f, 0.25f));
        assertEquals(0.25f, picker.color().a(), 1e-4f);

        picker.setAlphaEnabled(false);
        assertEquals(1f, picker.color().a(), "an alpha-less picker must not report one");
        // And a hex code carrying alpha is taken for its colour only.
        picker.applyHex("00FF0080");
        assertEquals(1f, picker.color().a());
        assertEquals("#00FF00", picker.color().toHex());
    }

    @Test
    void alphaComesBackWhenTheModeDoes() {
        picker.setInitialColor(new Color(1f, 0f, 0f, 0.25f));
        picker.setAlphaEnabled(false);
        picker.setAlphaEnabled(true);
        assertEquals(0.25f, picker.color().a(), 1e-4f, "the value was kept, only hidden");
    }

    @Test
    void everyFormatShowsTheSameColour() {
        // Switching models must not change the answer: a round trip through the
        // spinners' rounding is the only thing allowed to move, and only by a step.
        picker.setInitialColor(Color.rgb(0x3366CC));
        String expected = picker.color().toHex();
        for (ColorPicker.Format format : ColorPicker.Format.values()) {
            picker.setFormat(format);
            assertEquals(format, picker.format());
            assertEquals(expected, picker.color().toHex(), "switching to " + format);
        }
    }

    @Test
    void theHexFieldIsNotRewrittenWhileItIsBeingTyped() {
        // "112233FF" parses to an opaque colour whose canonical form is the shorter
        // "112233". Rewriting the field mid-word would delete the two characters
        // just typed, which is exactly what it used to do.
        picker.setInitialColor(Color.BLACK);
        assertTrue(picker.applyHex("112233FF"));
        assertEquals(1f, picker.color().a(), 1e-4f);
        assertEquals("#112233", picker.color().toHex());
    }

    // --- the numeric rows ----------------------------------------------------

    @Test
    void cyanStaysWhereItIsPut() {
        // An orange: red is its brightest channel, so the canonical separation puts
        // no cyan in it at all, which is precisely where the bug lived. A step up
        // on C used to be re-expressed as a step on K, and a step down did nothing,
        // because the row was rebuilt from the colour after every edit.
        showCmyk(Color.rgb(0xE6661A));
        assertRow(0, 56, 89, 10);

        changes.clear();
        nudgeChannel(0, Keys.UP);

        assertEquals(1, picker.channel(0).value(), 1e-9,
                "the ink went to another spinner");
        assertEquals(10, picker.channel(3).value(), 1e-9,
                "the key channel absorbed an edit to cyan");
        assertEquals(1, changes.size(), "one press is one reported change");
    }

    @Test
    void steppingCyanTwiceMovesItTwiceAndBackDownOnce() {
        showCmyk(Color.rgb(0xE6661A));
        nudgeChannel(0, Keys.UP);
        nudgeChannel(0, Keys.UP);
        assertEquals(2, picker.channel(0).value(), 1e-9, "the second step compounded elsewhere");
        nudgeChannel(0, Keys.DOWN);
        assertEquals(1, picker.channel(0).value(), 1e-9,
                "the arrow that undoes the edit must not be dead");
    }

    @Test
    void theCmykRowRebuildsTheColourOnScreen() {
        showCmyk(Color.rgb(0xE6661A));
        nudgeChannel(0, Keys.UP);
        assertEquals(rowAsColour().toHex(), picker.color().toHex(),
                "the picker must never report a colour its own numbers do not produce");
    }

    @Test
    void inkOnBlackIsKeptUntilTheKeyComesDown() {
        // Black is the one colour whose canonical row is all key, so every C/M/Y
        // edit used to be swallowed and the row was a dead end: no sequence of
        // presses on C could ever get cyan into it.
        showCmyk(Color.BLACK);
        assertRow(0, 0, 0, 100);

        nudgeChannel(0, Keys.UP);
        nudgeChannel(0, Keys.UP);
        nudgeChannel(0, Keys.UP);
        assertEquals(3, picker.channel(0).value(), 1e-9, "the ink was swallowed by the key");
        assertEquals("#000000", picker.color().toHex(),
                "cyan under a full key is still black: the colour is right, the row is now honest");

        nudgeChannel(3, Keys.DOWN);
        nudgeChannel(3, Keys.DOWN);
        assertEquals(3, picker.channel(0).value(), 1e-9, "lifting the key must not spend the cyan");
        assertTrue(picker.color().b() > picker.color().r(),
                "the cyan the user dialled in should surface as the key lifts");
    }

    @Test
    void aCmykRowSurvivesAnAlphaChangeAndAFormatRoundTrip() {
        showCmyk(Color.rgb(0xE6661A));
        nudgeChannel(0, Keys.UP);
        nudgeChannel(0, Keys.UP);

        picker.setAlphaEnabled(false);
        picker.setAlphaEnabled(true);
        picker.setFormat(ColorPicker.Format.RGB);
        picker.setFormat(ColorPicker.Format.CMYK);
        // Tall enough for CMYK's four channel lines: a rail laid out past the
        // bottom of the scene is never pressed, and the test that pressed it
        // would report "no change" as though the rail were dead.
        scene.layoutPass(320, 520);

        assertEquals(2, picker.channel(0).value(), 1e-9,
                "neither the alpha nor the tab moved the colour, so the inks must stand");
    }

    @Test
    void movingTheColourRestoresTheCanonicalSeparation() {
        showCmyk(Color.rgb(0xE6661A));
        nudgeChannel(0, Keys.UP);
        assertEquals(1, picker.channel(0).value(), 1e-9);

        // A colour that came from somewhere else has no separation of the user's to
        // keep, so the row must go back to the canonical one, which always has a
        // channel at zero. Otherwise a stale row would outlive the colour it described.
        assertTrue(picker.applyHex("#3366CC"));
        assertRow(75, 50, 0, 20);

        nudgeChannel(0, Keys.UP);
        dragFieldTo(60, 60);
        assertEquals(0, Math.min(picker.channel(0).value(),
                        Math.min(picker.channel(1).value(), picker.channel(2).value())), 1e-9,
                "dragging the field moves the colour, so the row must be rebuilt from it");
    }

    @Test
    void theSaturationSurvivesTypingTheValueToZero() {
        // The same defect one tab over: the HSV row used to take its saturation back
        // off the colour it had just built, and black has none to give.
        picker.setFormat(ColorPicker.Format.HSV);
        // Tall enough for CMYK's four channel lines: a rail laid out past the
        // bottom of the scene is never pressed, and the test that pressed it
        // would report "no change" as though the rail were dead.
        scene.layoutPass(320, 520);
        picker.setInitialColor(Color.hsv(200, 0.8f, 0.5f, 1f));
        assertRow(200, 80, 50);

        nudgeChannel(2, Keys.HOME); // value to its minimum: black
        assertEquals(80, picker.channel(1).value(), 1e-9,
                "the saturation was lost on the way to black");

        nudgeChannel(2, Keys.END);
        assertTrue(picker.color().saturation() > 0.75f,
                "the colour came back white instead of the blue it started as");
        assertEquals(200, picker.hue(), 0.5f);
    }

    // --- the format tabs -----------------------------------------------------

    @Test
    void switchingTheTabRenotatesTheColour() {
        // From the pane's side, which is the half setFormat does not cover: a
        // click lands there and comes back through onSelect.
        picker.setInitialColor(Color.rgb(0xE6661A));
        changes.clear();

        picker.tabs().setSelectedIndex(ColorPicker.Format.CMYK.ordinal());
        scene.layoutPass(320, 520);

        assertEquals(ColorPicker.Format.CMYK, picker.format(),
                "the pane moved and the picker stayed behind");
        assertRow(0, 56, 89, 10);
        assertTrue(changes.isEmpty(),
                "re-notating a colour is not changing it, so nothing should be reported");
    }

    @Test
    void setFormatAndTheTabAgreeWhicheverEndMovedFirst() {
        picker.setFormat(ColorPicker.Format.HSV);
        assertEquals(ColorPicker.Format.HSV.ordinal(), picker.tabs().selectedIndex(),
                "the tab did not follow the caller");

        picker.tabs().setSelectedIndex(ColorPicker.Format.RGB.ordinal());
        assertEquals(ColorPicker.Format.RGB, picker.format(),
                "the picker did not follow the tab");
    }

    // --- the channel rails ---------------------------------------------------

    @Test
    void aRailPutsItsChannelWhereItWasPressed() {
        picker.setInitialColor(Color.rgb(0x804020));
        changes.clear();

        // The centre of the rail is the centre of the channel's range, whatever
        // the thumb inset is: the travel is inset half a thumb at BOTH ends.
        pressRail(1, 0.5f);

        assertEquals(128, picker.channel(1).value(), 1e-9, "green did not land where it was pressed");
        assertEquals(0x80, picker.channel(0).value(), 1e-9, "red moved");
        assertEquals(0x20, picker.channel(2).value(), 1e-9, "blue moved");
        assertEquals(1, changes.size(), "one press is one reported change");
    }

    @Test
    void aRailReachesBothEndsOfItsChannel() {
        picker.setInitialColor(Color.rgb(0x804020));
        dragRail(0, 0.5f, -1f); // past the left edge
        assertEquals(0, picker.channel(0).value(), 1e-9, "the rail could not reach the minimum");
        dragRail(0, 0.5f, 2f);  // and past the right
        assertEquals(255, picker.channel(0).value(), 1e-9, "the rail could not reach the maximum");
    }

    @Test
    void aDragAlongOneStepIsOneChange() {
        picker.setInitialColor(Color.rgb(0x804020));
        changes.clear();
        // Three moves that all round to the same value: the rail is ~220pt over
        // 255 steps, so a point of travel is under a step.
        Widget rail = picker.rail(2);
        float y = rail.localToSceneY() + rail.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, railX(rail, 0.5f), y);
        scene.mouseMoved(railX(rail, 0.5f) + 0.2f, y);
        scene.mouseMoved(railX(rail, 0.5f) + 0.4f, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, railX(rail, 0.5f) + 0.4f, y);
        scene.inputBatchEnded();
        assertEquals(1, changes.size(), "a drag reports once per value, not once per move");
    }

    @Test
    void aRailDragIsOneUndoableGesture() {
        List<String> gestures = new ArrayList<>();
        picker.onCommit(color -> gestures.add("end"));
        picker.setInitialColor(Color.rgb(0x804020));

        dragRail(0, 0.2f, 0.8f);

        assertEquals(1, gestures.size(),
                "a caller closing an undo gesture on release must hear exactly one");
    }

    @Test
    void draggingACmykRailKeepsTheSeparationItBuilds() {
        showCmyk(Color.rgb(0xE6661A));
        assertRow(0, 56, 89, 10);

        pressRail(0, 0.25f);

        assertEquals(25, picker.channel(0).value(), 1e-9, "cyan did not land where it was pressed");
        assertRow(25, 56, 89, 10);
        assertEquals(rowAsColour().toHex(), picker.color().toHex(),
                "the rail must not report a colour its own row does not produce");
    }

    @Test
    void theAlphaRailSetsTheAlpha() {
        picker.setInitialColor(Color.rgb(0x3366CC));
        changes.clear();

        dragRail(picker.alphaRail(), 1f, 0.5f);

        assertEquals(0.5f, picker.color().a(), 0.005f, "the alpha rail did not move the alpha");
        assertEquals("#3366CC80", picker.color().toHex(),
                "the alpha rail must move the alpha and nothing else");
        assertEquals(1, changes.size());
    }

    @Test
    void alphaOffTakesTheWholeLineAway() {
        picker.setInitialColor(Color.rgb(0x3366CC).withAlpha(0.4f));
        picker.setAlphaEnabled(false);
        scene.layoutPass(320, 520);

        assertFalse(picker.alphaRail().isShowing(), "the rail outlived the mode that offers it");
        assertEquals(1f, picker.color().a(), 1e-6f, "alpha off must hand back an opaque colour");
    }

    @Test
    void aRailTakesTheFocusAndTheArrowsMoveIt() {
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(1);
        changes.clear();

        pressOnRail(Keys.RIGHT, 0);
        assertEquals(0x41, picker.channel(1).value(), 1e-9, "an arrow should move one unit");
        pressOnRail(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(0x37, picker.channel(1).value(), 1e-9, "Shift should move ten");
        pressOnRail(Keys.DOWN, 0);
        assertEquals(0x36, picker.channel(1).value(), 1e-9,
                "a rail is horizontal, but Down on a slider must still mean less");

        assertEquals(0x80, picker.channel(0).value(), 1e-9, "the keys moved another channel");
        assertEquals(3, changes.size(), "each press is one reported change");
    }

    @Test
    void aRailReachesItsEndsFromTheKeyboard() {
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(2);

        pressOnRail(Keys.END, 0);
        assertEquals(255, picker.channel(2).value(), 1e-9);
        pressOnRail(Keys.HOME, 0);
        assertEquals(0, picker.channel(2).value(), 1e-9);
    }

    @Test
    void theAlphaRailMovesByOnePercent() {
        picker.setInitialColor(Color.rgb(0x804020).withAlpha(0.5f));
        picker.alphaRail().requestFocus();
        assertEquals(picker.alphaRail(), scene.focusedWidget());

        pressOnRail(Keys.RIGHT, 0);
        assertEquals(0.51f, picker.color().a(), 0.005f, "one arrow is one percent");
    }

    @Test
    void pressingARailLeavesTheArrowsOnThatChannel() {
        // What a rail that could not take focus got wrong: the press landed on the
        // picker, and the arrows afterwards moved the saturation/value field.
        picker.setInitialColor(Color.rgb(0x804020));
        pressRail(0, 0.5f);
        assertEquals(picker.rail(0), scene.focusedWidget(), "the drag left the focus elsewhere");

        double after = picker.channel(0).value();
        pressOnRail(Keys.RIGHT, 0);
        assertEquals(after + 1, picker.channel(0).value(), 1e-9);
    }

    /** Focuses the rail at {@code index}, failing loudly if it is not a focus stop. */
    private void focusRail(int index) {
        picker.rail(index).requestFocus();
        assertEquals(picker.rail(index), scene.focusedWidget(),
                "the rail never took focus, so the key went nowhere");
    }

    private void pressOnRail(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
    }

    /** Presses and releases at {@code fraction} along the rail at {@code index}. */
    private void pressRail(int index, float fraction) {
        dragRail(index, fraction, fraction);
    }

    /** Press at {@code from}, move to {@code to}, release (both as fractions of the rail). */
    private void dragRail(int index, float from, float to) {
        dragRail(picker.rail(index), from, to);
    }

    private void dragRail(Widget rail, float from, float to) {
        float y = rail.localToSceneY() + rail.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, railX(rail, from), y);
        scene.mouseMoved(railX(rail, to), y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, railX(rail, to), y);
        scene.inputBatchEnded();
    }

    /**
     * A scene x at {@code fraction} of the rail's travel, thumb inset included. The
     * thumb is a tabled extent, so the inset is read from the step the scene runs
     * at rather than from a number copied out of the widget.
     */
    private float railX(Widget rail, float fraction) {
        float thumb = SizeTokens.of(rail.controlSize()).colorThumbW();
        return rail.localToSceneX() + thumb / 2 + fraction * (rail.width() - thumb);
    }

    /** Puts the picker on the CMYK tab, opened on {@code color}, laid out and ready. */
    private void showCmyk(Color color) {
        picker.setFormat(ColorPicker.Format.CMYK);
        // Tall enough for CMYK's four channel lines: a rail laid out past the
        // bottom of the scene is never pressed, and the test that pressed it
        // would report "no change" as though the rail were dead.
        scene.layoutPass(320, 520);
        picker.setInitialColor(color);
    }

    /** One arrow press on the channel spinner at {@code index} of the showing row. */
    private void nudgeChannel(int index, int key) {
        picker.channel(index).requestFocus();
        assertEquals(picker.channel(index), scene.focusedWidget(),
                "the spinner never took focus, so the key went nowhere");
        scene.keyEvent(key, true, false, 0);
        scene.inputBatchEnded();
    }

    private void assertRow(int... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], picker.channel(i).value(), 1e-9, "channel " + i);
        }
    }

    /** The colour the four CMYK spinners describe, independent of what the picker says. */
    private Color rowAsColour() {
        return Color.cmyk((float) (picker.channel(0).value() / 100.0),
                (float) (picker.channel(1).value() / 100.0),
                (float) (picker.channel(2).value() / 100.0),
                (float) (picker.channel(3).value() / 100.0), picker.color().a());
    }

    /** A press-drag-release inside the saturation/value field. */
    private void dragFieldTo(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, Math.min(y, 20));
        scene.mouseMoved(x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    @Test
    void thePickerFollowsTheControlSize() {
        // The premise: every component is sensitive to the step. The picker is a
        // composite, so what has to move is everything it owns (the canvas, the
        // rails and the gaps between them), which shows up as its natural height.
        float dense = pickerHeightAt(limn.scene.ControlSize.XSMALL);
        float medium = pickerHeightAt(limn.scene.ControlSize.MEDIUM);
        float roomy = pickerHeightAt(limn.scene.ControlSize.XLARGE);

        assertTrue(dense < medium && medium < roomy,
                "the picker measured " + dense + " / " + medium + " / " + roomy);
        assertEquals(SizeTokens.of(limn.scene.ControlSize.XLARGE).colorRailH(),
                railHeightAt(limn.scene.ControlSize.XLARGE), 0.01f,
                "the rail band itself has to come off the row");
    }

    @Test
    void switchingToCmykMakesThePickerTaller() {
        // Four channel rows where RGB and HSV have three, so the picker genuinely
        // changes size under the tab; this is the fact that a container has to
        // answer to, and a native Dialog did not until it learned to refit its
        // window. If a later change equalises the tabs' heights, this failing is the
        // prompt to go and read Dialog.DialogPanel.refitNativeWindow before deleting
        // it: the refit covers every content that resizes, not just this one.
        ColorPicker sized = sizedPicker(limn.scene.ControlSize.MEDIUM);
        limn.scene.Constraints room = limn.scene.Constraints.loose(320, 4000);

        sized.setFormat(ColorPicker.Format.RGB);
        float rgb = sized.measure(room).height();
        sized.setFormat(ColorPicker.Format.CMYK);
        float cmyk = sized.measure(room).height();

        assertTrue(cmyk > rgb, "CMYK measured " + cmyk + ", RGB " + rgb);
    }

    private float pickerHeightAt(limn.scene.ControlSize step) {
        return sizedPicker(step).measure(limn.scene.Constraints.loose(320, 4000)).height();
    }

    /** The painted rail band at {@code step}; the box is taller by the ring's room. */
    private float railHeightAt(limn.scene.ControlSize step) {
        return SizeTokens.of(step).colorRailH();
    }

    private ColorPicker sizedPicker(limn.scene.ControlSize step) {
        ColorPicker sized = new ColorPicker();
        sized.setControlSize(step);
        Scene host = new Scene(sized);
        host.setTextRuler(RULER);
        host.layoutPass(320, 900);
        return sized;
    }
}
