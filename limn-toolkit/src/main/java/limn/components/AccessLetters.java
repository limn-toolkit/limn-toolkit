package limn.components;

import limn.i18n.internal.AccessMarks;
import limn.i18n.internal.AccessMarks.Parsed;
import org.jspecify.annotations.Nullable;

/**
 * The access letter a text marks with an ampersand, read where the text is set: "&amp;Arquivo"
 * shows "Arquivo" with its A as the letter, so each translation picks its own. The reading itself,
 * an {@code &&} and a literal {@code &} among it, is {@link AccessMarks}', the one parser the
 * controls and {@link limn.i18n.I18nString#withoutAccessLetter} share; this class adds what the
 * controls need around it.
 *
 * <p>On macOS, where no application has access letters, a text keeps its words and loses its
 * mark, and a trailing "(&amp;F)", the letter Japanese and Chinese strings carry for Windows, is
 * dropped whole, an ellipsis after it kept.
 */
final class AccessLetters {

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
        return AccessMarks.parse(raw, mac);
    }

    /**
     * Whether {@code widget} draws its access letter now: while Alt is held in its scene, on a
     * system that has access letters.
     */
    static boolean shownIn(limn.scene.Widget<?> widget) {
        return onThisSystem() && limn.scene.internal.SceneAccess.accessLettersShown(widget.scene());
    }

    /**
     * How {@code letter}'s chord is said to a reader, "Alt+S" or "Alt+Ф", or {@code null} where
     * there is none: no letter, or macOS. Written from the letter itself and not through an
     * {@link Accelerator}, which names a key by its code and would call a Cyrillic, Hebrew or
     * accented letter "Key1060".
     */
    static @Nullable String binding(char letter) {
        if (!onThisSystem() || letter == 0) {
            return null;
        }
        return Accelerator.modifiersDisplay(limn.input.Keys.MOD_ALT) + letter;
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
