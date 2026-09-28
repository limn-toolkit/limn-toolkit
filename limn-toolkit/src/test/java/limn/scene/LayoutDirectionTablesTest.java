package limn.scene;

import limn.i18n.internal.LanguageAliases;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language and region tables {@link LayoutDirection#forLocale} reads, against the resolver it
 * reads a locale through first.
 *
 * <p>{@code limn.i18n.CldrLocaleFactsTest} holds what the tables answer to CLDR; this holds what
 * they are keyed on. A locale's language is read as ICU reads it before any table is asked, so a
 * key the resolver replaces, {@code pes} or {@code pan}, is a row no locale can reach: it answers
 * nothing, and it says the table decides something the resolver does. Lives in this package
 * because the tables are not public.
 */
class LayoutDirectionTablesTest {

    @Test
    void noTableIsKeyedOnACodeTheResolverReplaces() {
        List<String> languages = LayoutDirection.tableLanguages();
        assertTrue(languages.size() > 200,
                "the tables name only " + languages.size() + " languages");

        List<String> replaced = new ArrayList<>();
        for (String language : languages) {
            String read = LanguageAliases.language(language);
            if (!read.equals(language)) {
                replaced.add(language + " is read as " + read);
            }
        }
        assertEquals(List.of(), replaced, "table keys the resolver replaces before they are asked");
    }
}
