package limn.demo.site;

import limn.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the text guide's counted sentence says for the counts the section quotes. */
class PluralExampleTest {

    @AfterEach
    void restoreTheLocale() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void theFormFollowsTheCountAndTheNumberIsWrittenTheWayTheLanguageWritesIt() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Documents: 1 file loaded", PluralExample.loaded("Documents", 1));
        assertEquals("Documents: 2 files loaded", PluralExample.loaded("Documents", 2));
        assertEquals("Documents: 1,000,000 files loaded", PluralExample.loaded("Documents", 1_000_000),
                "grouped as English groups, and inserted as text so nothing formats it again");

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Documents: 1.000.000 files loaded", PluralExample.loaded("Documents", 1_000_000),
                "a catalog with no entry for the key falls back to the English forms, and the "
                        + "number is still written the way the interface's language writes it");
    }
}
