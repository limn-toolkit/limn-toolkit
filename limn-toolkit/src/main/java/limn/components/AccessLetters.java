package limn.components;

import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The access letter a text marks with an ampersand, read where the text is set: "&amp;Arquivo"
 * shows "Arquivo" with its A as the letter, so each translation picks its own.
 *
 * <ul>
 *   <li>An {@code &} before a letter or a digit marks it, and is not shown.</li>
 *   <li>{@code &&} is one literal ampersand.</li>
 *   <li>An {@code &} before anything else, a space or the end, is a literal ampersand: "Terms &
 *       conditions" needs no escaping.</li>
 *   <li>Only the first mark counts; a second is shown as written and reported once.</li>
 *   <li>On macOS, where no application has access letters, a text keeps its words and loses its
 *       mark, and a trailing "(&amp;F)", the letter Japanese and Chinese strings carry for
 *       Windows, is dropped whole.</li>
 * </ul>
 */
final class AccessLetters {

    /**
     * A text read for its access letter.
     *
     * @param text   what is shown, the mark taken out
     * @param index  where the letter is in {@code text}, or {@code -1} for none
     * @param letter the letter, upper-cased, or {@code 0} for none
     */
    record Parsed(String text, int index, char letter) {
        static final Parsed EMPTY = new Parsed("", -1, (char) 0);
    }

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private AccessLetters() {
    }

    /** Whether this system has access letters at all: Windows and Linux do, macOS does not. */
    static boolean onThisSystem() {
        return limn.scene.internal.AccessLetterSystem.present();
    }

    /** Reads {@code raw} as this system shows it: see the class note. */
    static Parsed parse(@Nullable String raw) {
        return parse(raw, !limn.scene.internal.AccessLetterSystem.present());
    }

    /**
     * Reads {@code raw} as {@code mac} says the system shows it.
     *
     * @param raw the text as written, marks included; null reads as empty
     * @param mac whether to read it as macOS does: the words kept, no letter
     */
    static Parsed parse(@Nullable String raw, boolean mac) {
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
            System.getLogger(AccessLetters.class.getName()).log(System.Logger.Level.WARNING,
                    "\"{0}\" marks more than one access letter; the first counts", raw);
        }
        String text = out.toString();
        if (mac && index >= 0) {
            return new Parsed(withoutWindowsLetter(text, index), -1, (char) 0);
        }
        return new Parsed(text, index, letter);
    }

    /**
     * {@code text} without a trailing "(F)" whose F is the letter at {@code index}: the
     * parenthesised Latin letter CJK strings carry for Windows, which a Mac application does not
     * show. Any other text comes back as it is.
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
        if (!bracketed || !text.substring(close + 1).isBlank()) {
            return text;
        }
        return text.substring(0, open).stripTrailing();
    }

    /**
     * Whether {@code widget} draws its access letter now: while Alt is held in its scene, on a
     * system that has access letters.
     */
    static boolean shownIn(limn.scene.Widget<?> widget) {
        return onThisSystem() && limn.scene.internal.SceneAccess.accessLettersShown(widget.scene());
    }

    /**
     * How {@code letter}'s chord is said to a reader, "Alt+S", or {@code null} where it cannot be:
     * no letter, macOS, or a code outside printable ASCII, which Accelerator would name "Key1060".
     */
    static @Nullable String binding(char letter) {
        if (!onThisSystem() || letter <= 32 || letter >= 127) {
            return null;
        }
        return new Accelerator(letter, limn.input.Keys.MOD_ALT).display();
    }

    /**
     * A widget's text read for its mark, again only when the text changed: the language, or a new
     * caption. The witness changes with it, which is what an accessible name built from the shown
     * text is diffed by.
     */
    static final class Held {
        private String from;
        private Parsed parsed = Parsed.EMPTY;
        private long witness;
        private char boundLetter;
        private String binding;

        /** {@code raw} read for its mark. */
        Parsed of(String raw) {
            if (!raw.equals(from)) {
                from = raw;
                parsed = parse(raw);
                witness++;
            }
            return parsed;
        }

        private limn.i18n.I18nString shownText;
        private String shownFor;

        /**
         * {@code parsed}'s text as a localizable value: {@code source} itself when the text has no
         * mark, else a literal of the words, kept until they change.
         */
        limn.i18n.I18nString shownAsText(Parsed parsed, limn.i18n.I18nString source) {
            if (parsed.text().equals(from)) {
                return source; // no mark: the text is the words
            }
            if (!parsed.text().equals(shownFor)) {
                shownFor = parsed.text();
                shownText = limn.i18n.I18nString.literal(parsed.text());
            }
            return shownText;
        }

        /** Changes whenever the shown text does. */
        long witness() {
            return witness;
        }

        /** {@link AccessLetters#binding} of the last letter read, kept until the letter changes. */
        @Nullable String binding() {
            char letter = parsed.letter();
            if (letter != boundLetter) {
                boundLetter = letter;
                binding = AccessLetters.binding(letter);
            }
            return binding;
        }
    }

    /** See {@link limn.scene.internal.AccessLetterOwner#matches}. */
    static boolean matches(char letter, int key, int codePoint) {
        return limn.scene.internal.AccessLetterOwner.matches(letter, key, codePoint);
    }
}
