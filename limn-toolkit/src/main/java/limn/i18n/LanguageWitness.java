package limn.i18n;

import java.util.Locale;

/**
 * Whether the language moved since a derived string was last built: the two facts a memo of
 * formatted text has to compare, {@link I18n#epoch()} and {@link I18n#locale()}, held as one.
 *
 * <p>A spinner's displayed value, a video view's clock, a colour picker's spoken description:
 * each is a string built from a value and the language, and each is rebuilt only when one of
 * the two moved, because building it inside a describe hook would allocate on every damaged
 * frame. Three widgets kept the epoch and the locale as two fields and two comparisons apiece;
 * this is the pair once, with the refresh folded into the question.
 */
public final class LanguageWitness {

    private long epoch = -1;
    private Locale locale;

    /**
     * Whether the language moved since the last call, and if it did, remembers where it is now.
     * Asked <em>first</em>, before the memo's own keys, so that the answer is taken even when
     * another key forces the rebuild anyway; otherwise the next frame would report a move that
     * had already been built for.
     *
     * @return whether the epoch or the locale differs from the last call; always true on the first
     */
    public boolean moved() {
        long now = I18n.epoch();
        Locale current = I18n.locale();
        if (now == epoch && current.equals(locale)) {
            return false;
        }
        epoch = now;
        locale = current;
        return true;
    }

    /** @return the locale the last built string was made under, or {@code null} before the first */
    public Locale locale() {
        return locale;
    }
}
