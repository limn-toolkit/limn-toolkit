package limn.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A marked string written into a sentence of the status line reads as its words: the mark goes,
 * and so does the parenthesised letter Japanese, Korean and Chinese strings carry for it, which
 * read "並べ替え: 名前(N)。" in the status line while only the ampersand was taken out.
 */
class PagePlainTest {

    @Test
    void aMarkedStringReadsAsItsWords() {
        assertEquals("Open", Page.plain("&Open"));
        assertEquals("Save as", Page.plain("Save &as"));
        assertEquals("R&D", Page.plain("R&&D"), "a doubled ampersand is one shown");
        assertEquals("Terms & conditions", Page.plain("Terms & conditions"));
    }

    @Test
    void theParenthesisedLetterOfACjkStringGoesWhole() {
        assertEquals("名前", Page.plain("名前(&N)"));
        assertEquals("ドキュメント", Page.plain("ドキュメント(&D)"));
        assertEquals("会社", Page.plain("会社（&Y）"), "full-width parentheses too");
        assertEquals("プロパティ…", Page.plain("プロパティ(&P)…"), "an ellipsis after it stays");
    }
}
