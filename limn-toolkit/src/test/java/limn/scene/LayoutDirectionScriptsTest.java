package limn.scene;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-to-left script table, against the JDK's own Unicode data.
 *
 * <p>Its sibling {@code limn.i18n.CldrLocaleFactsTest} checks the language half against CLDR
 * through a dump. This half needs no dump and cannot go stale, because Unicode <em>is</em> the
 * definition rather than a copy of it: a script runs right to left exactly when it has
 * characters of bidi class R or AL, which {@link Character#getDirectionality} answers for every
 * code point there is.
 *
 * <p>It found four missing scripts the day it was written (2026-09-21) — Old North Arabian,
 * Elymaic, Chorasmian and Old Uyghur — each of which the toolkit would have laid out left to
 * right.
 */
class LayoutDirectionScriptsTest {

    /**
     * Every script Unicode writes right to left is in the table, and nothing else is.
     *
     * <p>{@code Aran} is the one code expected to be in the table and absent from the Unicode
     * side: it is Nastaliq, a style of {@code Arab} with a script code of its own and no code
     * points of its own.
     */
    @Test
    void theRightToLeftScriptsAreEveryScriptUnicodeWritesThatWay() {
        Set<Character.UnicodeScript> rtl = new TreeSet<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (!Character.isDefined(cp)) {
                continue;
            }
            byte direction = Character.getDirectionality(cp);
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rtl.add(Character.UnicodeScript.of(cp));
            }
        }
        // COMMON holds the shared punctuation and marks, which belong to no script and are
        // written in whichever one surrounds them.
        rtl.remove(Character.UnicodeScript.COMMON);
        assertTrue(rtl.size() > 30, "only " + rtl.size() + " scripts: is the JDK's data missing?");

        Set<Character.UnicodeScript> covered = new TreeSet<>();
        for (String code : LayoutDirection.rightToLeftScripts()) {
            Character.UnicodeScript script;
            try {
                script = Character.UnicodeScript.forName(code);
            } catch (IllegalArgumentException unknownToThisJdk) {
                assertEquals("Aran", code, code + " is not a script code this JDK knows");
                continue;
            }
            covered.add(script);
            assertTrue(rtl.contains(script),
                    code + " is in the table and Unicode has no right-to-left character in it");
        }
        Set<Character.UnicodeScript> missed = new TreeSet<>(rtl);
        missed.removeAll(covered);
        assertEquals(Set.of(), missed,
                "scripts Unicode writes right to left and the table misses");
    }
}
