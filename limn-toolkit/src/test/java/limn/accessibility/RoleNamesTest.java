package limn.accessibility;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The role vocabulary a screen reader speaks, and the rules that keep it worth speaking. */
class RoleNamesTest {

    @Test
    void everyRoleHasAPhrase() {
        Set<String> missing = new TreeSet<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            if (RoleNames.englishOf(role) == null) missing.add(role.name());
        }
        assertTrue(missing.isEmpty(),
                "roles a reader would have no noun for: " + missing
                        + ". §1.12 requires one before a role is added.");
    }

    @Test
    void noTwoRolesSayTheSameWord() {
        // Two roles with one word are two different controls a reader describes identically, which
        // is the whole failure §1.12 keeps the enum closed to prevent. Where a PLATFORM has one
        // word for two of our roles that is the platform's limit and is recorded in its own table;
        // here it would be ours, and there is no reason for it.
        Set<String> seen = new TreeSet<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            String phrase = RoleNames.englishOf(role);
            assertTrue(seen.add(phrase), role + " says \"" + phrase + "\", which another role "
                    + "already says");
        }
    }

    @Test
    void aPhraseIsAWordAndNotASentence() {
        for (Accessible.Role role : Accessible.Role.values()) {
            String phrase = RoleNames.englishOf(role);
            assertTrue(!phrase.isBlank(), role + " has a blank phrase");
            // It is spoken immediately after the node's name, every time the node is reached. A
            // reader saying "Save, a button that can be activated" once is helpful and forty times
            // is why people turn verbosity down.
            assertTrue(phrase.split("\\s+").length <= 3,
                    role + " says \"" + phrase + "\", which is a sentence and not a noun");
            assertEquals(phrase.strip(), phrase, role + " has a phrase with edge whitespace");
            assertEquals(phrase.toLowerCase(Locale.ROOT), phrase,
                    role + " is capitalised; the reader decides sentence case, not this table");
        }
    }

    @Test
    void theKeyIsDerivedFromTheRoleAndNotWrittenTwice() {
        assertEquals("role.check_box", RoleNames.keyFor(Accessible.Role.CHECK_BOX));
        for (Accessible.Role role : Accessible.Role.values()) {
            assertNotNull(RoleNames.keyFor(role));
        }
    }

    @Test
    void anUntranslatedLocaleAnswersTheEnglishRatherThanNothing() {
        // A reader saying "button" in the wrong language is a reader a user can still work with.
        // Saying nothing is not.
        assertEquals(RoleNames.englishOf(Accessible.Role.BUTTON),
                RoleNames.of(Accessible.Role.BUTTON, Locale.forLanguageTag("und")));
    }
}
