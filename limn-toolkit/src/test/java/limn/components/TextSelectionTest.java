package limn.components;

import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The caret and selection an application sets on a text widget, which replaced reaching into the
 * editing model once that model stopped being API (ADR 046 §1).
 */
class TextSelectionTest extends ComponentTestBase {

    private static TextField field(String text) {
        TextField field = new TextField().setText(text);
        Scene scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 40);
        return field;
    }

    private static TextArea area(String text) {
        TextArea area = new TextArea().setText(text);
        Scene scene = new Scene(area);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 200);
        return area;
    }

    @Test
    void aSelectionHasAnAnchorAndACaretAndMayRunBackwards() {
        TextField field = field("Hello, Limn");
        field.select(7, 11);
        assertEquals("Limn", field.selectedText());
        assertEquals(11, field.caretPosition());

        field.select(5, 0);
        assertEquals("Hello", field.selectedText());
        assertEquals(0, field.caretPosition(), "the caret ends where the selection was made to");
        assertEquals(0, field.selectionStart());
        assertEquals(5, field.selectionEnd());

        field.setCaretPosition(3);
        assertEquals("", field.selectedText());
        assertEquals(3, field.selectionStart());
        assertEquals(3, field.selectionEnd());

        field.selectAll();
        assertEquals("Hello, Limn", field.selectedText());
        assertEquals(11, field.caretPosition());
    }

    @Test
    void anOffsetInsideAClusterMovesToItsStart() {
        String family = "👨‍👩‍👧"; // one grapheme, 8 chars
        TextField field = field("a" + family + "b");
        field.setCaretPosition(4);
        assertEquals(1, field.caretPosition(), "never between the halves of an emoji sequence");
    }

    @Test
    void anOffsetOutsideTheTextIsRefused() {
        TextField field = field("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> field.setCaretPosition(4));
        assertThrows(IndexOutOfBoundsException.class, () -> field.select(-1, 2));
        field.setCaretPosition(3);
        assertEquals(3, field.caretPosition(), "the end of the text is a caret position");
    }

    @Test
    void aMoveIsAnnouncedAsCodeAndReachesNoHandler() {
        TextField field = field("Hello");
        List<String> heard = new ArrayList<>();
        field.observeChanges((source, change) -> heard.add(change.aspect() + "/" + change.origin()));
        List<String> handled = new ArrayList<>();
        field.onChange(handled::add);

        field.select(0, 5);
        field.select(0, 5);
        assertEquals(List.of("SELECTION/CODE"), heard, "once, and not again for no move");
        assertEquals(List.of(), handled);
    }

    @Test
    void aTextAreaSelectsAcrossLines() {
        TextArea area = area("first\nsecond");
        area.select(2, 9);
        assertEquals("rst\nsec", area.selectedText());
        area.setCaretPosition(6);
        assertEquals(6, area.caretPosition());
        area.selectAll();
        assertEquals("first\nsecond", area.selectedText());
    }
}
