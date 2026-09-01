package limn.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * A piece of user-visible text, carried as a value instead of resolved into a
 * {@code String} at the point of use.
 *
 * <p>A component stores one of these where it would otherwise store a
 * {@code String}, and reads {@link #get()} when it measures or paints. Holding the
 * value is what makes keeping it correct: a lookup call whose result must never be
 * kept in a field fails silently, leaving one widget in yesterday's language.
 *
 * <pre>{@code
 * private static final I18nString PLACEHOLDER =
 *         new I18nString("limn.searchField.placeholder", "Search…");
 * ...
 * canvas.drawText(PLACEHOLDER.get(), x, baseline, font, colour);
 * }</pre>
 *
 * <p><b>English is not a translation.</b> The second argument is both the default
 * text and the fallback for every key a bundle does not answer, so an application
 * that registers nothing behaves exactly as it did before this type existed, and
 * a missing translation degrades to English rather than to a key name on screen.
 *
 * <p><b>The cache lives here.</b> {@link #get()} memoises its resolution against
 * {@link I18n#epoch()}, so nothing subscribes and nothing leaks: the re-resolution
 * rides the relayout a language change already causes.
 *
 * <p>Declare them {@code static final}, so the cache is one slot per key for the whole
 * process rather than one per widget; a list showing five hundred rows of the same
 * label resolves once per language change, not five hundred times.
 *
 * <p>Immutable in every respect a caller can observe; the memo is mutated on the UI
 * thread like the rest of the toolkit's state.
 */
public final class I18nString {

    /** The empty literal: a text field's placeholder before anyone sets one. */
    public static final I18nString EMPTY = literal("");

    /** {@code null} for a literal, which is what makes {@link #get()} skip the registry. */
    private final String key;
    private final String english;

    private String cached;
    /** Valid iff {@code cachedEpoch == I18n.epoch()} and the locale matches; 0 is "never resolved". */
    private long cachedEpoch;
    /**
     * The locale {@link #cached} was resolved under. Part of the memo's key since ADR 035,
     * because the same string can be read from two subtrees in two languages: inside a
     * widget pass {@link I18n#locale()} answers that widget's effective locale, and a memo
     * that could not see it would hand a Hebrew pane the label its Latin neighbour resolved
     * a frame earlier. One slot still: a static string actually read in two languages every
     * frame re-resolves per alternation, which is a bundle walk of hash gets and is paid
     * only where two languages are genuinely on one screen.
     */
    private Locale cachedLocale;

    /**
     * Declares a localizable string.
     *
     * @param key     the lookup key, dotted and globally unique; prefix it with the
     *                screen or component that owns it ({@code settings.title}, not
     *                {@code title}), because bundles share one namespace
     * @param english the text in the default language, used whenever no registered
     *                bundle answers the key
     * @throws IllegalStateException if the key was already declared with different
     *                               English: one key with two meanings is a bug that
     *                               would otherwise surface as a mistranslation
     */
    public I18nString(String key, String english) {
        this.key = Objects.requireNonNull(key, "key");
        this.english = Objects.requireNonNull(english, "english");
        I18n.declare(key, english);
    }

    private I18nString(String literal) {
        this.key = null;
        this.english = literal;
    }

    /**
     * Text that is not localizable and resolves to itself: what
     * {@code setText(String)} wraps a caller's literal in, so a component can hold
     * one field instead of two and never has to decide which of them wins.
     */
    public static I18nString literal(String text) {
        return new I18nString(Objects.requireNonNull(text, "text"));
    }

    /**
     * The text in the {@linkplain I18n#locale() locale in effect here}, or the English
     * when nothing translates it. Inside a widget's measure, layout, paint or event
     * dispatch that locale is the widget's own effective one, so the same declaration
     * reads correctly from every subtree without the caller doing anything.
     */
    public String get() {
        if (key == null) {
            return english;
        }
        long epoch = I18n.epoch();
        Locale locale = I18n.locale();
        if (cachedEpoch != epoch || !locale.equals(cachedLocale)) {
            cached = I18n.resolve(key, english, locale);
            cachedEpoch = epoch;
            cachedLocale = locale;
        }
        return cached;
    }

    /**
     * The text with {@code {0}}-style arguments substituted, formatted by
     * {@link MessageFormat} under the current locale. Never cached: the arguments
     * vary per call, and caching them would be caching the wrong thing.
     *
     * <p>Only parameterized keys pay MessageFormat's rules, which is why {@link #get()}
     * does not route through it: an apostrophe in an ordinary string needs no
     * escaping. In a <em>parameterized</em> one it does (double it), and so does a
     * stray brace; a pattern a translator got wrong falls back to the English rather
     * than throwing in the middle of a paint.
     *
     * <p>Numbers are formatted for the locale, so pass {@code Integer.toString(n)}
     * where ASCII digits are required; digits the text pipeline cannot draw are
     * worse than an unlocalised number.
     */
    public String format(Object... args) {
        String pattern = get();
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return new MessageFormat(pattern, I18n.locale()).format(args);
        } catch (IllegalArgumentException malformedPattern) {
            try {
                return new MessageFormat(english, I18n.locale()).format(args);
            } catch (IllegalArgumentException ourOwnPatternIsBroken) {
                return english;
            }
        }
    }

    /** The lookup key, or {@code null} for a {@linkplain #literal literal}. */
    public String key() {
        return key;
    }

    /** The default-language text: also the fallback, and never {@code null}. */
    public String english() {
        return english;
    }

    /** Whether this is a literal, which no bundle can ever change. */
    public boolean isLiteral() {
        return key == null;
    }

    /** The resolved text, so logging and debugging read as the user sees it. */
    @Override
    public String toString() {
        return get();
    }

    /**
     * Value equality on key and English: two literals of the same text are the same
     * string, and a component's "did this actually change" check keeps working when
     * its field stops being a {@code String}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof I18nString other)) {
            return false;
        }
        return Objects.equals(key, other.key) && english.equals(other.english);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, english);
    }
}
