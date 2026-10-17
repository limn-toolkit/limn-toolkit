package limn.i18n.internal;

import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one reading of the access letter a text marks with an ampersand: "&amp;Arquivo" shows
 * "Arquivo" with its A as the letter, so each translation picks its own. The controls read it
 * through {@code limn.components.AccessLetters}, and {@link limn.i18n.I18nString#withoutAccessLetter}
 * reads it for the words alone, which is why it lives below both.
 *
 * <ul>
 *   <li>An {@code &} before a letter or a digit marks it, and is not shown.</li>
 *   <li>{@code &&} is one literal ampersand.</li>
 *   <li>An {@code &} before anything else, a space or the end, is a literal ampersand: "Terms &
 *       conditions" needs no escaping.</li>
 *   <li>Only the first mark counts; a second is shown as written and reported once.</li>
 *   <li>Read for its words alone, as macOS shows it and as a sentence quotes it, a text loses its
 *       mark, and a trailing "(&amp;F)", the letter Japanese and Chinese strings carry for
 *       Windows, is dropped whole, an ellipsis after it kept.</li>
 * </ul>
 */
public final class AccessMarks {

    /**
     * A text read for its access letter.
     *
     * @param text   what is shown, the mark taken out
     * @param index  where the letter is in {@code text}, or {@code -1} for none
     * @param letter the letter, upper-cased, or {@code 0} for none
     */
    public record Parsed(String text, int index, char letter) {
        /** The empty text, with no letter. */
        public static final Parsed EMPTY = new Parsed("", -1, (char) 0);
    }

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private AccessMarks() {
    }

    /**
     * Reads {@code raw} for its mark.
     *
     * @param raw       the text as written, marks included; null reads as empty
     * @param wordsOnly whether to read the words alone, with no letter: as macOS shows a control's
     *                  text, and as a sentence quotes one on every system
     * @return what is shown, and the letter unless {@code wordsOnly}
     */
    public static Parsed parse(@Nullable String raw, boolean wordsOnly) {
        if (raw == null || raw.isEmpty()) {
            return Parsed.EMPTY;
        }
        if (raw.indexOf('&') < 0) {
            return new Parsed(raw, -1, (char) 0);
        }
        StringBuilder out = new StringBuilder(raw.length());
        int index = -1;
        char letter = 0;
        boolean second = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '&' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(i + 1);
            if (next == '&') {
                out.append('&');
                i++;
            } else if (Character.isLetterOrDigit(next)) {
                if (index < 0) {
                    index = out.length();
                    letter = Character.toUpperCase(next);
                    // The mark goes; the letter is shown as written, in its own case.
                } else {
                    second = true;
                    out.append('&'); // a second mark is a slip in the string: shown as written
                }
            } else {
                out.append('&');
            }
        }
        if (second && REPORTED.add(raw)) {
            System.getLogger(AccessMarks.class.getName()).log(System.Logger.Level.WARNING,
                    "\"{0}\" marks more than one access letter; the first counts", raw);
        }
        String text = out.toString();
        if (wordsOnly && index >= 0) {
            return new Parsed(withoutWindowsLetter(text, index), -1, (char) 0);
        }
        return new Parsed(text, index, letter);
    }

    /**
     * {@code text} without a trailing "(F)" whose F is the letter at {@code index}: the
     * parenthesised Latin letter CJK strings carry for Windows, which a Mac application does not
     * show and a sentence does not quote. An ellipsis after it stays, since Windows writes a command
     * that asks for more as "プロパティ(&amp;P)…" and a Mac one as "プロパティ…". Any other text comes
     * back as it is.
     */
    private static String withoutWindowsLetter(String text, int index) {
        int open = index - 1;
        int close = index + 1;
        if (open < 0 || close >= text.length()) {
            return text;
        }
        char o = text.charAt(open);
        char c = text.charAt(close);
        boolean bracketed = (o == '(' || o == '（') && (c == ')' || c == '）');
        String after = text.substring(close + 1).strip();
        if (!bracketed || !(after.isEmpty() || after.equals("…") || after.equals("..."))) {
            return text;
        }
        return text.substring(0, open).stripTrailing() + after;
    }
}
