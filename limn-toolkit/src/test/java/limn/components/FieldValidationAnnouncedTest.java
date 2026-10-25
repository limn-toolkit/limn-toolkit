package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A message set about a control while the keyboard is in it is said politely, once: a reader
 * hears a control's description when it arrives there, and a message set as the user types was
 * otherwise heard only on coming back to the control.
 */
class FieldValidationAnnouncedTest extends AccessibleComponentTestBase {

    private TextField email;
    private TextField other;
    private Field field;

    private void build() {
        email = new TextField();
        other = new TextField();
        field = new Field("Email", email);
        Column page = new Column();
        page.add(field);
        page.add(other);
        bind(page);
        frame();
    }

    private List<AccessibleEvent> announced() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                found.add(event);
            }
        }
        return found;
    }

    @Test
    void aMessageSetWhileTheUserIsInTheControlIsSaidOncePolitely() {
        build();
        scene.requestFocus(email);
        field.setValidation(Validation.ERROR, "Enter an email address");
        frame();
        field.setValidation(Validation.ERROR, "Enter an email address");
        frame();
        List<AccessibleEvent> said = announced();
        assertEquals(1, said.size(), "once: " + said);
        assertEquals("Enter an email address", String.valueOf(said.get(0).newValue()));
        assertEquals(Accessible.Politeness.POLITE, said.get(0).politeness());

        field.setValidation(Validation.ERROR, "That address has no domain");
        frame();
        assertEquals(2, announced().size(), "a new message is said too");
    }

    @Test
    void aMessageSetWhileTheUserIsElsewhereIsNotSaid() {
        build();
        scene.requestFocus(other);
        field.setValidation(Validation.ERROR, "Enter an email address");
        frame();
        assertEquals(0, announced().size(), "heard as the control's description on arriving there");
    }
}
