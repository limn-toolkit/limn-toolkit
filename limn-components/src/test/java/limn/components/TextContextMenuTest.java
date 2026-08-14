package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which rows the text widgets' context menu offers, and that a right press raises it.
 *
 * <p>The rows are asserted through {@link TextContextMenu#rowsFor} rather than by opening a menu:
 * a native popup needs a real window and a display, and this suite has neither. What the gesture
 * tests below pin is the half that <em>is</em> observable headless: that the press is answered
 * and lands the focus where the menu's edits will apply.
 */
class TextContextMenuTest extends ComponentTestBase {

    /** A host whose every answer is set by the test, so one rule is exercised at a time. */
    private static final class FakeHost implements TextContextMenu.Host {
        boolean selection;
        boolean editable = true;
        boolean copyAllowed = true;
        boolean empty;
        String clipboard = "";

        @Override
        public boolean hasSelection() {
            return selection;
        }

        @Override
        public boolean isEditable() {
            return editable;
        }

        @Override
        public boolean allowsCopy() {
            return copyAllowed;
        }

        @Override
        public boolean isEmpty() {
            return empty;
        }

        @Override
        public String clipboardText() {
            return clipboard;
        }

        @Override
        public void cut() {
        }

        @Override
        public void copy() {
        }

        @Override
        public void paste() {
        }

        @Override
        public void selectAll() {
        }
    }

    @Test
    void cutAndCopyNeedASelectionAndPasteNeedsAClipboard() {
        FakeHost host = new FakeHost();
        TextContextMenu.Rows empty = TextContextMenu.rowsFor(host);
        assertFalse(empty.cut());
        assertFalse(empty.copy());
        assertFalse(empty.paste(), "nothing on the clipboard is nothing to paste");
        assertTrue(empty.selectAll(), "there is text to take");

        host.selection = true;
        host.clipboard = "hello";
        TextContextMenu.Rows ready = TextContextMenu.rowsFor(host);
        assertTrue(ready.cut());
        assertTrue(ready.copy());
        assertTrue(ready.paste());
    }

    /**
     * The one that matters for {@link PasswordField}: refusing Copy must also refuse Cut, or the
     * menu offers a way around the refusal that leaks exactly what Copy would have.
     */
    @Test
    void aFieldThatRefusesCopyAlsoRefusesCut() {
        FakeHost host = new FakeHost();
        host.selection = true;
        host.copyAllowed = false;

        TextContextMenu.Rows rows = TextContextMenu.rowsFor(host);
        assertFalse(rows.copy());
        assertFalse(rows.cut(), "cutting to the clipboard leaks what copying would");
    }

    /** A disabled field may still be read from, so Copy survives while the edits do not. */
    @Test
    void aDisabledFieldOffersCopyButNeitherCutNorPaste() {
        FakeHost host = new FakeHost();
        host.selection = true;
        host.editable = false;
        host.clipboard = "hello";

        TextContextMenu.Rows rows = TextContextMenu.rowsFor(host);
        assertTrue(rows.copy());
        assertFalse(rows.cut());
        assertFalse(rows.paste());
    }

    /** Nothing to offer at all: the caller must skip the menu rather than raise four dead rows. */
    @Test
    void anEmptyDisabledFieldOverAnEmptyClipboardOffersNothing() {
        FakeHost host = new FakeHost();
        host.editable = false;
        host.empty = true;

        assertFalse(TextContextMenu.rowsFor(host).any());
    }

    /**
     * Right-click used to be dead in every widget: every press handler gated on MOUSE_LEFT. The
     * press must now be answered, and must focus the field: the menu's Cut and Paste act on it,
     * and an unfocused field would edit while the caret lives somewhere else.
     */
    @Test
    void aRightPressOnAFieldTakesFocusAndIsConsumed() {
        TextField field = new TextField();
        Scene scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 32);
        field.setText("hello");

        assertFalse(field.isFocused());
        scene.mouseMoved(20, 10);
        scene.mouseButton(Keys.MOUSE_RIGHT, true, 0, 20, 10);
        scene.mouseButton(Keys.MOUSE_RIGHT, false, 0, 20, 10);
        scene.inputBatchEnded();

        assertTrue(field.isFocused(), "the menu's edits apply here, so the caret must be here");
    }

    /** And the same gesture in the multi-line editor, which shares the menu. */
    @Test
    void aRightPressOnAnAreaTakesFocus() {
        TextArea area = new TextArea();
        Scene scene = new Scene(area);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 100);
        area.setText("hello\nthere");

        scene.mouseMoved(20, 20);
        scene.mouseButton(Keys.MOUSE_RIGHT, true, 0, 20, 20);
        scene.mouseButton(Keys.MOUSE_RIGHT, false, 0, 20, 20);
        scene.inputBatchEnded();

        assertTrue(area.isFocused());
    }
}
