package limn.components;

import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three looks a button is drawn in and the destructive tone over each: which fill, which
 * border and which ink each paints at rest, pressed and disabled, that the box is the same at every
 * style, and that a dialog's destructive button never answers Return.
 */
class ButtonStyleTest extends ComponentTestBase {

    /** What one frame of a lone button drew: its fills, its outlines and its caption's ink. */
    private static final class Recorder extends FakeCanvas {
        final List<Paint> fills = new ArrayList<>();
        final List<Paint> borders = new ArrayList<>();
        final List<Paint> inks = new ArrayList<>();

        Recorder() {
            super(200, 60);
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float r, Paint paint) {
            fills.add(paint);
        }

        @Override
        public void fillRoundRectWithBorder(float x, float y, float w, float h, float r,
                Paint fill, float borderWidth, Paint border) {
            fills.add(fill);
            borders.add(border);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            inks.add(paint);
        }

        Paint fill() {
            assertEquals(1, fills.size(), "one fill: " + fills);
            return fills.get(0);
        }

        Paint ink() {
            assertEquals(1, inks.size(), "one caption: " + inks);
            return inks.get(0);
        }
    }

    private Scene scene;

    private Button lone(Button.Style style, boolean destructive) {
        Button button = new Button("Delete").setStyle(style).setDestructive(destructive);
        scene = new Scene(button);
        scene.setTextRuler(RULER);
        scene.layoutPass(120, 40);
        return button;
    }

    private Recorder frame() {
        Recorder canvas = new Recorder();
        scene.requestRender();
        scene.renderFrame(canvas);
        return canvas;
    }

    private void press() {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        drive(scene).inputBatchEnded();
    }

    private static Theme theme() {
        return Theme.current();
    }

    @Test
    void primaryIsTheAccentFillWithItsInkAsBefore() {
        Button button = lone(Button.Style.PRIMARY, false);
        assertSame(Button.Style.PRIMARY, new Button("x").style(), "primary by default");
        Recorder rest = frame();
        assertEquals(theme().primary(), rest.fill());
        assertEquals(theme().onPrimary(), rest.ink());
        assertTrue(rest.borders.isEmpty(), "no outline");
        press();
        assertTrue(button.isArmed());
        assertEquals(theme().primaryPressed(), frame().fill());
    }

    @Test
    void aDestructivePrimaryIsFilledInDangerWithTheAccentsInk() {
        lone(Button.Style.PRIMARY, true);
        Recorder rest = frame();
        assertEquals(theme().danger(), rest.fill());
        assertEquals(theme().onPrimary(), rest.ink(),
                "onPrimary on danger: the contrast every built-in palette is tested for");
        press();
        assertEquals(theme().danger().lerp(theme().text(), 0.16f), frame().fill(),
                "pressed is danger moved toward the text colour by 16 %");
    }

    @Test
    void secondaryIsTheSurfaceOutlinedAndADestructiveOneWritesInDanger() {
        lone(Button.Style.SECONDARY, false);
        Recorder rest = frame();
        assertEquals(theme().surface(), rest.fill());
        assertEquals(List.of(theme().outline()), rest.borders);
        assertEquals(theme().text(), rest.ink());

        lone(Button.Style.SECONDARY, true);
        Recorder destructive = frame();
        assertEquals(theme().surface(), destructive.fill(), "the same box and fill as secondary");
        assertEquals(theme().danger(), destructive.ink());
    }

    @Test
    void tertiaryHasNoFillAtRestAndATintOfItsInkWhenPressed() {
        lone(Button.Style.TERTIARY, false);
        Recorder rest = frame();
        assertTrue(rest.fills.isEmpty(), "nothing behind a resting tertiary button: " + rest.fills);
        assertTrue(rest.borders.isEmpty(), "and no outline");
        assertEquals(theme().primaryText(), rest.ink(), "the caption carries the accent as text");
        press();
        Color ink = theme().primaryText();
        assertEquals(ink.withAlpha(ink.a() * 0.16f), frame().fill(), "pressed: the ink at 16 %");

        lone(Button.Style.TERTIARY, true);
        assertEquals(theme().danger(), frame().ink());
        press();
        Color danger = theme().danger();
        assertEquals(danger.withAlpha(danger.a() * 0.16f), frame().fill());
    }

    @Test
    void aDisabledTertiaryDrawsNoFillAndTheDisabledInk() {
        Button button = lone(Button.Style.TERTIARY, true);
        button.setEnabled(false);
        Recorder disabled = frame();
        assertTrue(disabled.fills.isEmpty(), "disabled, a tertiary button is still unfilled");
        assertEquals(theme().disabledText(), disabled.ink());
    }

    @Test
    void theBoxIsTheSameAtEveryStyleAndEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            Size expected = null;
            for (Button.Style style : Button.Style.values()) {
                for (boolean destructive : new boolean[] {false, true}) {
                    Button b = new Button("Delete").setStyle(style).setDestructive(destructive)
                            .setControlSize(step);
                    new Scene(b).setTextRuler(SCALED_RULER);
                    Size box = b.measure(Constraints.loose(1000, 1000));
                    if (expected == null) {
                        expected = box;
                    }
                    assertEquals(expected, box, step + " " + style + " destructive=" + destructive);
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    private Dialog dialog;

    private AtomicReference<String> open(Dialog d) {
        dialog = d;
        scene = new Scene(d.contentRoot());
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        AtomicReference<String> result = new AtomicReference<>("<open>");
        d.result().thenAccept(result::set);
        return result;
    }

    private void pressReturn() {
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).keyEvent(Keys.ENTER, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aDialogsDestructiveButtonIsNeverTheDefaultWhateverTheOrder() {
        // Added first, ahead of the primary answer: Return still gives the safe one.
        AtomicReference<String> result = open(new Dialog("Delete order?", "It cannot be undone.")
                .setContent(new TextField())
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Keep", "keep"));
        scene.focusTraverse(false);
        assertTrue(scene.focusedWidget() instanceof TextField, "sanity: the field holds the keyboard");
        pressReturn();
        assertEquals("keep", result.get());
    }

    @Test
    void aDialogWhoseOnlyAnswerIsDestructiveIgnoresReturn() {
        AtomicReference<String> result = open(new Dialog("Discard draft?", "It is not saved.")
                .setContent(new TextField())
                .addDestructiveButton("Discard", "discard"));
        scene.focusTraverse(false);
        pressReturn();
        assertEquals("<open>", result.get(), "Return does not delete");
    }

    @Test
    void aDialogsDestructiveButtonIsSecondaryInDanger() {
        open(new Dialog("Delete order?", "It cannot be undone.")
                .addButton("Cancel", "cancel")
                .addDestructiveButton("Delete", "delete"));
        Button delete = button(dialog.contentRoot(), "Delete");
        assertSame(Button.Style.SECONDARY, delete.style());
        assertTrue(delete.isDestructive());
        assertFalse(button(dialog.contentRoot(), "Cancel").isDestructive());
    }

    private static Button button(limn.scene.Widget<?> root, String caption) {
        if (root instanceof Button b && b.text().equals(caption)) {
            return b;
        }
        for (limn.scene.Widget<?> child : root.children()) {
            Button found = button(child, caption);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
