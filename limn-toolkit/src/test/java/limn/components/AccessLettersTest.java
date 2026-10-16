package limn.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a text marks its access letter (ADR 070 §3.1): an ampersand before a letter or a digit, the
 * first one only; two ampersands for one shown; any other ampersand shown as written; and on
 * macOS, which has no access letters, the words kept and a Windows "(&amp;F)" dropped.
 */
class AccessLettersTest {

    private static AccessLetters.Parsed elsewhere(String raw) {
        return AccessLetters.parse(raw, false);
    }

    @Test
    void anAmpersandBeforeALetterMarksItAndIsNotShown() {
        assertEquals(new AccessLetters.Parsed("Arquivo", 0, 'A'), elsewhere("&Arquivo"));
        assertEquals(new AccessLetters.Parsed("Salvar", 2, 'L'), elsewhere("Sa&lvar"));
        assertEquals(new AccessLetters.Parsed("Page 2", 5, '2'), elsewhere("Page &2"));
    }

    @Test
    void twoAmpersandsAreOneShownAndAnyOtherAmpersandIsText() {
        assertEquals(new AccessLetters.Parsed("AT&T", -1, (char) 0), elsewhere("AT&&T"));
        assertEquals(new AccessLetters.Parsed("Terms & conditions", -1, (char) 0),
                elsewhere("Terms & conditions"));
        assertEquals(new AccessLetters.Parsed("Save &", -1, (char) 0), elsewhere("Save &"));
        assertEquals(new AccessLetters.Parsed("R&D &Notes", 0, 'R'), elsewhere("&R&&D &Notes"),
                "the first mark counts; a second is shown as written");
    }

    @Test
    void aLetterOfAnyScriptIsALetter() {
        assertEquals(new AccessLetters.Parsed("Файл", 0, 'Ф'), elsewhere("&Файл"));
        assertEquals(new AccessLetters.Parsed("ファイル(F)", 5, 'F'), elsewhere("ファイル(&F)"),
                "the CJK convention: the English letter in parentheses, underlined on Windows");
    }

    @Test
    void macOSKeepsTheWordsAndNoLetter() {
        assertEquals(new AccessLetters.Parsed("Arquivo", -1, (char) 0), AccessLetters.parse("&Arquivo", true));
        assertEquals(new AccessLetters.Parsed("ファイル", -1, (char) 0), AccessLetters.parse("ファイル(&F)", true),
                "a Japanese Mac application writes the bare word");
        assertEquals(new AccessLetters.Parsed("文件", -1, (char) 0), AccessLetters.parse("文件（&F）", true),
                "full-width parentheses too");
        assertEquals(new AccessLetters.Parsed("AT&T", -1, (char) 0), AccessLetters.parse("AT&&T", true));
    }

    /**
     * The Windows form of a command that asks for more, "プロパティ(&amp;P)…", loses its letter on
     * macOS and keeps its ellipsis: only a bare trailing "(P)" was dropped, and twelve of the
     * Showcase's menu rows read "プロパティ(P)…" on a Mac.
     */
    @Test
    void macOSDropsTheWindowsLetterBeforeAnEllipsisToo() {
        assertEquals(new AccessLetters.Parsed("プロパティ…", -1, (char) 0), AccessLetters.parse("プロパティ(&P)…", true));
        assertEquals(new AccessLetters.Parsed("名前の変更...", -1, (char) 0), AccessLetters.parse("名前の変更(&R)...", true));
        assertEquals(new AccessLetters.Parsed("속성…", -1, (char) 0), AccessLetters.parse("속성(&P) …", true));
        assertEquals(new AccessLetters.Parsed("(P) später", -1, (char) 0), AccessLetters.parse("(&P) später", true),
                "words after the parentheses are not the Windows letter's form, and stay");
        assertEquals(new AccessLetters.Parsed("プロパティ(P)…", 6, 'P'), elsewhere("プロパティ(&P)…"),
                "and on Windows and Linux the letter stays, underlined");
    }

    @Test
    void aKeyIsTheLetterPrintedOnItOrTheOneTheLayoutTypes() {
        assertTrue(AccessLetters.matches('A', 'A', -1));
        assertTrue(AccessLetters.matches('Ф', 'A', 'ф'), "a Cyrillic layout types ф on the A key");
        assertEquals(false, AccessLetters.matches((char) 0, 'A', 'a'));
    }
}
