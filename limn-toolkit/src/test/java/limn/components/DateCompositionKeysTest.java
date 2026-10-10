package limn.components;

import limn.backend.Clipboard;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Keys pressed while an input method is composing belong to the input method, in a
 * {@link DateField} as in a {@link TextField}: a date field is a text-input widget, so the scene
 * turns the input method on for it and a digit typed through one arrives as a composition first
 * (a Japanese input method in kana mode composes {@code ２} and commits it on Return). While that
 * composition is up the field consumes every key press and every auto-repeat without acting on it,
 * so nothing above it acts either &mdash; not a picker's own keys (Alt+Down and F4 open its
 * calendar), not the dialog around it (Return for the default button, Escape for cancel), not a
 * scene's shortcut handlers, not Tab traversal.
 *
 * <p>Why the field has to do it: macOS reports no key while marked text exists and Windows drops
 * the keys the IME took, but X11 hands a key to the application even when the input method's
 * filter consumed it, so the Return that commits the composition reached the field as an ordinary
 * key, went past it and answered the dialog.
 *
 * <p>Every chord runs on a fresh fixture: a field (or a picker's field) inside a dialog with a
 * default button, holding 09/09/2026 with the caret on the month, a clipboard holding another date
 * a paste would take, and a composition of {@code ２} in progress. After the press, two
 * auto-repeats and the release, the text, the segment, the value, the clipboard, the focus, the
 * dialog's result, whether the calendar is open and what the scene's shortcut handlers were offered
 * must all be as they were.
 */
class DateCompositionKeysTest extends ComponentTestBase {

    private static final Clock IN_2026 =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    /** One key and the modifiers held with it. */
    private record Chord(String name, int key, int mods) {
    }

    private static final int CTRL = Keys.MOD_CONTROL;
    private static final int CMD = Keys.MOD_SUPER;
    private static final int SHIFT = Keys.MOD_SHIFT;
    private static final int ALT = Keys.MOD_ALT;

    private static final List<Chord> CHORDS = List.of(
            new Chord("Enter", Keys.ENTER, 0),
            new Chord("Escape", Keys.ESCAPE, 0),
            new Chord("Tab", Keys.TAB, 0),
            new Chord("Shift+Tab", Keys.TAB, SHIFT),
            new Chord("Backspace", Keys.BACKSPACE, 0),
            new Chord("Delete", Keys.DELETE, 0),
            new Chord("Left", Keys.LEFT, 0),
            new Chord("Right", Keys.RIGHT, 0),
            new Chord("Up", Keys.UP, 0),
            new Chord("Down", Keys.DOWN, 0),
            new Chord("Alt+Down", Keys.DOWN, ALT),
            new Chord("F4", Keys.F4, 0),
            new Chord("Home", Keys.HOME, 0),
            new Chord("End", Keys.END, 0),
            new Chord("Page Up", Keys.PAGE_UP, 0),
            new Chord("Page Down", Keys.PAGE_DOWN, 0),
            new Chord("Ctrl+C", Keys.C, CTRL),
            new Chord("Cmd+C", Keys.C, CMD),
            new Chord("Ctrl+V", Keys.V, CTRL),
            new Chord("Cmd+V", Keys.V, CMD),
            new Chord("Menu", Keys.MENU, 0),
            new Chord("Shift+F10", Keys.F10, SHIFT),
            new Chord("F10", Keys.F10, 0),
            new Chord("K", Keys.K, 0),
            new Chord("Space", Keys.SPACE, 0));

    /** A field on its own or a picker's, composing inside a dialog, and everything a key could change. */
    private static final class Fixture {
        DateField field;
        DatePicker picker;
        Scene scene;
        final AtomicReference<String> result = new AtomicReference<>("<open>");
        final List<String> offered = new ArrayList<>();
        final Clipboard clipboard = TextFieldTest.MockClipboard.holding("31/12/2025");

        /** Everything a key could have changed, as one string to compare. */
        String state() {
            return "text=" + field.text() + " segment=" + field.focusedSegment()
                    + " date=" + field.date() + " clipboard=" + clipboard.text()
                    + " focused=" + (scene.focusedWidget() == field) + " result=" + result.get()
                    + " open=" + (picker != null && picker.isOpen());
        }
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private Fixture fixture(boolean inPicker) {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        Fixture f = new Fixture();
        Widget<?> content;
        if (inPicker) {
            f.picker = new DatePicker().setClock(IN_2026);
            f.field = f.picker.field();
            content = f.picker;
        } else {
            f.field = new DateField().setClock(IN_2026);
            content = f.field;
        }
        Dialog dialog = new Dialog("Schedule", "Date:")
                .setContent(content)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok")
                .setCancelResult("cancel");
        f.scene = new Scene(dialog.contentRoot());
        f.scene.setTextRuler(RULER);
        f.scene.layoutPass(400, 300);
        f.scene.setClipboard(f.clipboard);
        dialog.result().thenAccept(f.result::set);
        f.scene.addShortcutHandler(event -> {
            if (event.isPressed()) {
                f.offered.add(describe(event));
            }
            return false;
        });
        f.field.setDate(LocalDate.of(2026, 9, 9));
        f.scene.requestFocus(f.field);
        drive(f.scene).press(Keys.RIGHT); // onto the month, so both ends have somewhere to go
        assertEquals(1, f.field.focusedSegment(), "the fixture's caret is on the month");
        preedit(f, "２");
        return f;
    }

    private static void preedit(Fixture f, String text) {
        drive(f.scene).preeditChanged(text, text.codePointCount(0, text.length()), 0,
                text.isEmpty() ? 0 : 1);
        drive(f.scene).inputBatchEnded();
    }

    private static String describe(KeyEvent event) {
        return event.key() + (event.isRepeat() ? " repeat" : " press") + " mods " + event.modifiers();
    }

    /** A press, two auto-repeats and the release, as a held key delivers them. */
    private static void hold(Fixture f, Chord chord) {
        drive(f.scene).keyEvent(chord.key(), true, false, chord.mods());
        drive(f.scene).keyEvent(chord.key(), true, true, chord.mods());
        drive(f.scene).keyEvent(chord.key(), true, true, chord.mods());
        drive(f.scene).keyEvent(chord.key(), false, false, chord.mods());
        drive(f.scene).inputBatchEnded();
    }

    private void assertEveryKeyIsTheCompositions(boolean inPicker) {
        List<String> offenders = new ArrayList<>();
        for (Chord chord : CHORDS) {
            Fixture f = fixture(inPicker);
            String before = f.state();
            hold(f, chord);
            String after = f.state();
            if (!before.equals(after)) {
                offenders.add(chord.name() + " changed " + before + " into " + after);
            } else if (!f.offered.isEmpty()) {
                offenders.add(chord.name() + " went on to the shortcut handlers as " + f.offered);
            }
        }
        assertEquals(List.of(), offenders, "keys pressed during a composition acted, or went on "
                + "past the " + (inPicker ? "picker's field" : "field") + " to act somewhere else");
    }

    @Test
    void aComposingDateFieldSwallowsEveryKeyAndNothingAboveItActs() {
        assertEveryKeyIsTheCompositions(false);
    }

    @Test
    void aComposingPickerFieldSwallowsEveryKeyAndNothingAboveItActs() {
        assertEveryKeyIsTheCompositions(true);
    }

    /**
     * The other half: the swallowing lasts exactly as long as the composition. Once the input
     * method commits (the digit arrives as a character, then the composition clears) the same keys
     * step, move and answer the dialog again.
     */
    @Test
    void aDateFieldActsOnKeysAgainOnceTheCompositionIsCommitted() {
        Fixture f = fixture(false);
        drive(f.scene).type("２");
        preedit(f, "");
        assertEquals(LocalDate.of(2026, 2, 9), f.field.date(), "the committed digit is the month");

        String before = f.field.text();
        drive(f.scene).press(Keys.UP);
        assertNotEquals(before, f.field.text(), "Up steps a segment again");
        drive(f.scene).press(Keys.HOME);
        assertEquals(0, f.field.focusedSegment(), "Home walks the caret again");
        drive(f.scene).press(Keys.ENTER);
        assertEquals("ok", f.result.get(), "Return in the field answers the dialog again");
    }

    @Test
    void aPickerOpensAgainOnceTheCompositionIsCommitted() {
        Fixture f = fixture(true);
        drive(f.scene).type("２");
        preedit(f, "");
        drive(f.scene).press(Keys.F4);
        assertTrue(f.picker.isOpen(), "F4 opens the calendar again");
    }

    /** A composition the user abandons gives the keyboard back the same way a commit does. */
    @Test
    void aCancelledCompositionGivesTheKeysBack() {
        Fixture f = fixture(false);
        preedit(f, "");
        assertEquals("09/09/2026", f.field.text(), "nothing was committed");
        drive(f.scene).press(Keys.BACKSPACE);
        assertEquals(null, f.field.date(), "Backspace clears the month it left alone while composing");
        drive(f.scene).press(Keys.TAB);
        assertTrue(f.scene.focusedWidget() instanceof Button, "and Tab moves on to the buttons");
    }

    /**
     * A composition does not outlive the focus: the scene tells the platform to drop it when the
     * focus leaves a text widget, and a field that came back to find its keys still swallowed
     * would wait for a composition nobody is making.
     */
    @Test
    void aCompositionEndsWithTheFocus() {
        Fixture f = fixture(false);
        f.scene.focusTraverse(false);
        f.scene.requestFocus(f.field);
        String before = f.field.text();
        drive(f.scene).press(Keys.UP);
        assertNotEquals(before, f.field.text(), "Up steps a segment in a field focused again");
    }
}
