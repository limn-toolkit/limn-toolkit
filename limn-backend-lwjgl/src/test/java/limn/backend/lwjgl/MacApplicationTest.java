package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.libc.LibCLocale;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The C library's locale and the language the macOS backend leaves the process in. */
class MacApplicationTest {

    /**
     * Numbers written the way a terminal's {@code LANG=pt_BR.UTF-8} writes them hid the system's
     * check marks and submenu arrows; the backend puts them back in {@code "C"}, and the other
     * categories stay the environment's.
     */
    @Test
    void numbersGoBackToTheCLocaleAndTheRestStays() {
        assumeTrue(limn.backend.Platform.current().isMacOs(), "the category is set on macOS only");
        String numbers = MacApplication.cLocale(LibCLocale.LC_NUMERIC);
        String characters = MacApplication.cLocale(LibCLocale.LC_CTYPE);
        try {
            String comma = LibCLocale.setlocale(LibCLocale.LC_NUMERIC, "pt_BR.UTF-8");
            assumeTrue(comma != null, "this machine has no pt_BR locale to start from");
            LibCLocale.setlocale(LibCLocale.LC_CTYPE, "pt_BR.UTF-8");

            assertEquals("C", MacApplication.numbersInTheCLocale());
            assertEquals("C", MacApplication.cLocale(LibCLocale.LC_NUMERIC));
            assertEquals("pt_BR.UTF-8", MacApplication.cLocale(LibCLocale.LC_CTYPE));
        } finally {
            LibCLocale.setlocale(LibCLocale.LC_NUMERIC, numbers == null ? "C" : numbers);
            LibCLocale.setlocale(LibCLocale.LC_CTYPE, characters == null ? "C" : characters);
        }
    }

    /**
     * A native image started with no language in its environment, as the system starts a
     * {@code .app}, adopts the system's preferred language; one given a language by a terminal or
     * on its command line, and the JVM, which reads the system's choice itself, are left as they are.
     */
    @Test
    void onlyAnImageWithNoLanguageChosenFollowsTheSystem() {
        Locale portuguese = Locale.forLanguageTag("pt-BR");
        Map<String, String> empty = Map.of();
        List<String> none = List.of();
        assertEquals(portuguese, MacApplication.languageToFollow("runtime", empty::get, "en", none, () -> portuguese));
        assertNull(MacApplication.languageToFollow(null, empty::get, "en", none, () -> portuguese),
                "the JVM reads the system's choice itself");
        for (String variable : new String[] {"LANG", "LC_MESSAGES", "LC_ALL"}) {
            Map<String, String> terminal = Map.of(variable, "en_US.UTF-8");
            assertNull(MacApplication.languageToFollow("runtime", terminal::get, "en", none, () -> portuguese), variable);
        }
        Map<String, String> blank = Map.of("LANG", "");
        assertEquals(portuguese, MacApplication.languageToFollow("runtime", blank::get, "en", none, () -> portuguese),
                "an empty LANG names no language");
        assertNull(MacApplication.languageToFollow("runtime", empty::get, "en",
                List.of("./app", "-Duser.language=en"), () -> portuguese), "English asked for on the command line");
        assertNull(MacApplication.languageToFollow("runtime", empty::get, "en",
                List.of("-Duser.country=GB"), () -> portuguese), "a region asked for on the command line");
        assertNull(MacApplication.languageToFollow("runtime", empty::get, "de", none, () -> portuguese),
                "German chosen where the command line could not be read");
        assertNull(MacApplication.languageToFollow("runtime", empty::get, "en", none, () -> null),
                "nothing to adopt when the system's choice cannot be read");
    }

    /** The system's choice is read as a language, with a region whether or not the choice names one. */
    @Test
    void theSystemsPreferredLanguageIsRead() {
        assumeTrue(limn.backend.Platform.current().isMacOs(), "read from the system on macOS only");
        Locale preferred = MacApplication.preferredLocale();
        assertNotNull(preferred);
        assertFalse(preferred.getLanguage().isEmpty(), preferred.toString());
        assertFalse(preferred.getCountry().isEmpty(), preferred.toString());
    }
}
