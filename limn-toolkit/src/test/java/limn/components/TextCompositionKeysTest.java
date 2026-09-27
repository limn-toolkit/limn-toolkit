package limn.components;

import limn.backend.Clipboard;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Keys pressed while an input method is composing belong to the input method: a
 * {@link TextField} or {@link TextArea} with a composition up consumes every key press and every
 * auto-repeat without acting on it, so nothing above it acts either &mdash; not the dialog around
 * it (Return for the default button, Escape for cancel), not a scene's shortcut handlers, not Tab
 * traversal.
 *
 * <p>Why the widget has to do it: on macOS the window system reports no key at all while marked
 * text exists, Command chords included, and on Windows the keys the IME took arrive as
 * {@code VK_PROCESSKEY} and are dropped before they reach the toolkit, but X11 hands a key to the
 * application even when the input method's filter consumed it, so Return, Backspace and the arrows
 * the user pressed to steer the composition reach the field as ordinary keys. The web platform
 * marks such events {@code isComposing} and code 229 for an application to ignore; this makes X11
 * behave like the other two by ignoring them in the one place that knows a composition is up.
 *
 * <p>Every chord runs on a fresh fixture: a field inside a dialog with a default button, holding
 * {@code "abcde"} with the {@code e} typed by the user (so there is an edit to undo) and
 * {@code "bc"} selected (so there is something to copy, cut, delete, collapse and replace), a
 * clipboard holding {@code "zz"}, and a composition of {@code "か"} in progress. After the press,
 * two auto-repeats and the release, the text, the selection, the clipboard, the focus, the dialog's
 * result, the composition and what the scene's shortcut handlers were offered must all be as they
 * were.
 */
class TextCompositionKeysTest extends ComponentTestBase {

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
            new Chord("Ctrl+Backspace", Keys.BACKSPACE, CTRL),
            new Chord("Delete", Keys.DELETE, 0),
            new Chord("Left", Keys.LEFT, 0),
            new Chord("Right", Keys.RIGHT, 0),
            new Chord("Shift+Right", Keys.RIGHT, SHIFT),
            new Chord("Alt+Left", Keys.LEFT, ALT),
            new Chord("Cmd+Left", Keys.LEFT, CMD),
            new Chord("Up", Keys.UP, 0),
            new Chord("Down", Keys.DOWN, 0),
            new Chord("Home", Keys.HOME, 0),
            new Chord("End", Keys.END, 0),
            new Chord("Ctrl+Home", Keys.HOME, CTRL),
            new Chord("Page Up", Keys.PAGE_UP, 0),
            new Chord("Page Down", Keys.PAGE_DOWN, 0),
            new Chord("Ctrl+A", Keys.A, CTRL),
            new Chord("Cmd+A", Keys.A, CMD),
            new Chord("Ctrl+C", Keys.C, CTRL),
            new Chord("Ctrl+X", Keys.X, CTRL),
            new Chord("Ctrl+V", Keys.V, CTRL),
            new Chord("Cmd+V", Keys.V, CMD),
            new Chord("Ctrl+Z", Keys.Z, CTRL),
            new Chord("Cmd+Shift+Z", Keys.Z, CMD | SHIFT),
            new Chord("Ctrl+Y", Keys.Y, CTRL),
            new Chord("Menu", Keys.MENU, 0),
            new Chord("Shift+F10", Keys.F10, SHIFT),
            new Chord("F10", Keys.F10, 0),
            new Chord("K", Keys.K, 0),
            new Chord("Space", Keys.SPACE, 0));

    /** A field or an area, composing inside a dialog, and everything a key could change. */
    private static final class Fixture {
        Widget<?> text;
        Scene scene;
        final AtomicReference<String> result = new AtomicReference<>("<open>");
        final List<String> offered = new ArrayList<>();
        final Clipboard clipboard = new Clipboard() {
            private String value = "zz";

            @Override
            public String get() {
                return value;
            }

            @Override
            public void set(String text) {
                value = text == null ? "" : text;
            }
        };

        String text() {
            return text instanceof TextField f ? f.text() : ((TextArea) text).text();
        }

        int selectionStart() {
            return text instanceof TextField f ? f.selectionStart() : ((TextArea) text).selectionStart();
        }

        int selectionEnd() {
            return text instanceof TextField f ? f.selectionEnd() : ((TextArea) text).selectionEnd();
        }

        String composing() {
            return text instanceof TextField f ? f.composingText() : ((TextArea) text).composingText();
        }

        /** Everything a key could have changed, as one string to compare. */
        String state() {
            return "text=" + text() + " selection=" + selectionStart() + ".." + selectionEnd()
                    + " clipboard=" + clipboard.get() + " focused=" + (scene.focusedWidget() == text)
                    + " result=" + result.get() + " composing=" + composing();
        }
    }

    private Fixture fixture(boolean area) {
        Fixture f = new Fixture();
        f.text = area ? new TextArea() : new TextField();
        Dialog dialog = new Dialog("Rename", "New name:")
                .setContent(f.text)
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
        f.scene.requestFocus(f.text);
        if (area) {
            ((TextArea) f.text).setText("abcd").setCaretPosition(4);
        } else {
            ((TextField) f.text).setText("abcd").setCaretPosition(4);
        }
        drive(f.scene).type("e"); // the user's own edit, so an undo has something to undo
        if (area) {
            ((TextArea) f.text).select(1, 3);
        } else {
            ((TextField) f.text).select(1, 3);
        }
        preedit(f, "か");
        return f;
    }

    private static void preedit(Fixture f, String text) {
        drive(f.scene).preeditChanged(text, text.isEmpty() ? new int[0] : new int[] {1},
                text.isEmpty() ? -1 : 0, text.codePointCount(0, text.length()));
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

    private void assertEveryKeyIsTheCompositions(boolean area) {
        List<String> offenders = new ArrayList<>();
        for (Chord chord : CHORDS) {
            Fixture f = fixture(area);
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
                + "past the " + (area ? "area" : "field") + " to act somewhere else");
    }

    @Test
    void aComposingFieldSwallowsEveryKeyAndNothingAboveItActs() {
        assertEveryKeyIsTheCompositions(false);
    }

    @Test
    void aComposingAreaSwallowsEveryKeyAndNothingAboveItActs() {
        assertEveryKeyIsTheCompositions(true);
    }

    /**
     * The other half: the swallowing lasts exactly as long as the composition. Once the input
     * method commits (the text arrives as characters, then the composition clears) the same keys
     * edit, move and answer the dialog again.
     */
    @Test
    void aFieldActsOnKeysAgainOnceTheCompositionIsCommitted() {
        Fixture f = fixture(false);
        drive(f.scene).type("下");
        preedit(f, "");
        assertEquals("a下de", f.text(), "the commit replaced the selection");

        hold(f, new Chord("Left", Keys.LEFT, 0));
        assertEquals(0, f.selectionStart(), "Left walks the caret again, and its repeats with it");
        hold(f, new Chord("Delete", Keys.DELETE, 0));
        assertEquals("e", f.text(), "Delete deletes again, once for the press and each repeat");
        drive(f.scene).press(Keys.ENTER);
        assertEquals("ok", f.result.get(), "Return in the field answers the dialog again");
    }

    @Test
    void anAreaActsOnKeysAgainOnceTheCompositionIsCommitted() {
        Fixture f = fixture(true);
        drive(f.scene).type("下");
        preedit(f, "");
        assertEquals("a下de", f.text());

        drive(f.scene).press(Keys.ENTER);
        assertEquals("a下\nde", f.text(), "Return is a newline in an area again");
        drive(f.scene).press(Keys.BACKSPACE);
        assertEquals("a下de", f.text(), "and Backspace takes it back");
        drive(f.scene).press(Keys.ESCAPE);
        assertEquals("cancel", f.result.get(), "Escape reaches the dialog again");
    }

    /**
     * A search field takes Return for its search before the field it extends sees the key, so it
     * has to leave a composing Return to the field as well: the search ran on the text beside the
     * composition, before the input method had committed anything into it.
     */
    @Test
    void aComposingSearchFieldDoesNotSearch() {
        SearchField search = new SearchField();
        List<String> searched = new ArrayList<>();
        search.onSubmit(searched::add);
        Fixture f = new Fixture();
        f.text = search;
        f.scene = new Scene(search);
        f.scene.setTextRuler(RULER);
        f.scene.layoutPass(400, 40);
        f.scene.requestFocus(search);
        search.setText("abc").setCaretPosition(3);
        preedit(f, "か");

        hold(f, new Chord("Enter", Keys.ENTER, 0));
        assertEquals(List.of(), searched, "Return steered the composition and searched nothing");
        assertEquals("か", search.composingText(), "and the composition is still up");

        drive(f.scene).type("下");
        preedit(f, "");
        drive(f.scene).press(Keys.ENTER);
        assertEquals(List.of("abc下"), searched, "once committed, Return searches for what was typed");
    }

    /** A composition the user abandons gives the keyboard back the same way a commit does. */
    @Test
    void aCancelledCompositionGivesTheKeysBack() {
        Fixture f = fixture(false);
        preedit(f, "");
        assertEquals("abcde", f.text(), "nothing was committed");
        drive(f.scene).press(Keys.BACKSPACE);
        assertEquals("ade", f.text(), "Backspace deletes the selection it left alone while composing");
        drive(f.scene).press(Keys.TAB);
        assertTrue(f.scene.focusedWidget() instanceof Button, "and Tab moves on to the buttons");
    }
}
