package limn.scene;

import limn.i18n.CldrAliasRows;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-to-left script table, against the JDK's own Unicode data and against ICU's.
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
 *
 * <p>The JDK's Unicode is a version or two behind ICU's, so a script Unicode added since is one
 * the JDK cannot name at all, and the table is also held to the {@code rtl-script} rows of
 * {@code cldr-locale-facts.txt}, which {@code scripts/i18n/dump-cldr-locale-facts.mjs} asks ICU
 * for. That half found Garay (Unicode 16) and Sidetic (Unicode 17) missing on 2026-09-28.
 */
class LayoutDirectionScriptsTest {

    /**
     * Every script Unicode writes right to left is in the table, and nothing else is.
     *
     * <p>{@code Aran} is expected to be in the table and absent from the Unicode side: it is
     * Nastaliq, a style of {@code Arab} with a script code of its own and no code points of its
     * own. So is a script Unicode added after this JDK's version, which the half below holds to
     * ICU instead.
     */
    @Test
    void theRightToLeftScriptsAreEveryScriptUnicodeWritesThatWay() throws Exception {
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
                assertTrue(code.equals("Aran") || icuRightToLeftScripts().contains(code),
                        code + " is not a script code this JDK knows, nor one ICU runs right to "
                                + "left");
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

    /**
     * Every script ICU runs right to left is in the table, and nothing else is but {@code Aran}.
     * ICU names scripts the JDK's older Unicode does not, Garay and Sidetic among them, and those
     * only this half can see.
     *
     * <p>{@code Aran}, Nastaliq, is the table's own decision and not ICU's: ICU runs
     * {@code und-Aran} left to right, and a locale that names it is Urdu or Persian written in the
     * Nastaliq style of Arabic, which runs right to left as every Arabic does.
     */
    @Test
    void theRightToLeftScriptsAreIcusAndNastaliq() throws Exception {
        Set<String> icu = icuRightToLeftScripts();
        assertTrue(icu.size() > 30,
                "the dump carries only " + icu.size() + " right-to-left scripts");
        assertTrue(!icu.contains("Aran"), "ICU now runs Aran right to left: drop the exception");

        Set<String> expected = new TreeSet<>(icu);
        expected.add("Aran");
        assertEquals(expected, new TreeSet<>(LayoutDirection.rightToLeftScripts()),
                "the right-to-left scripts and ICU's");
    }

    /** The scripts the {@code rtl-script} rows of the dump name. */
    private static Set<String> icuRightToLeftScripts() throws Exception {
        Set<String> scripts = new TreeSet<>();
        for (String line : Files.readAllLines(CldrAliasRows.FACTS, StandardCharsets.UTF_8)) {
            if (line.startsWith("rtl-script ")) {
                scripts.addAll(List.of(line.substring("rtl-script ".length()).split(" ")));
            }
        }
        return scripts;
    }
}
