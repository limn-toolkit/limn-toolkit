package limn.i18n;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A localizable sentence with a count in it: "{@code Documents, 12 items}".
 *
 * <p>Its own type rather than a mode of {@link I18nString}, for a reason a caller feels. An
 * {@code I18nString} is a value a widget <em>holds</em> and reads every frame, and its memo is
 * built for exactly that; this cannot be held resolved, because it is not one string — it is one
 * per grammatical form, chosen when the count is known. Declaring it separately is what keeps
 * {@code I18nString}'s own rule true: one key, one English, resolvable with no arguments.
 *
 * <pre>{@code
 * private static final PluralString LOADED = PluralString.of(
 *         "limn.tree.loadedAnnouncement", "{0}, {1} item", "{0}, {1} items");
 * ...
 * scene.announce(LOADED.format(children.size(), branchName), Politeness.POLITE);
 * }</pre>
 *
 * <p><b>The count is the last argument.</b> Above, {@code {0}} is the branch name the caller
 * passed and {@code {1}} is the count, inserted after the caller's arguments. It is last rather
 * than first so that a sentence reads in the order a translator writes it and the caller's
 * arguments keep the numbering they would have had without a count.
 *
 * <p><b>In the catalog, one key per form.</b> The declared key gains a suffix — {@code
 * limn.tree.loadedAnnouncement.one}, {@code .few}, {@code .other} — and a language carries
 * exactly the forms it uses, which is {@link PluralRules#integerCategories}: one line for
 * Japanese, three for Russian, six for Arabic. A form a catalog leaves out falls back to that
 * language's {@code other}, and then to the English declared here, so a half-translated catalog
 * degrades to a readable sentence rather than to a key name.
 *
 * <p><b>The number is written in the locale's own digits</b> ({@link I18n#localizeDigits}), the
 * same treatment a calendar's day numbers get, and inserted as text so that
 * {@link MessageFormat} does not reformat it under a second set of rules.
 *
 * @see PluralRules
 */
public final class PluralString {

    private final String key;
    private final Map<PluralRules.Category, String> english;

    private PluralString(String key, Map<PluralRules.Category, String> english) {
        this.key = key;
        this.english = english;
    }

    /**
     * Declares a counted sentence with the two forms English has.
     *
     * <p>Every category is declared against the catalog, not only these two: a translation into
     * a language with six forms has six keys to fill, and each of them must be a key some
     * component declared or the catalog is carrying an orphan. The English for a form English
     * does not have is its plural, which is the right thing for a catalog that answers nothing.
     *
     * @param key     the lookup key, dotted and globally unique, <b>without</b> a form suffix
     * @param one     the English singular, with {@code {0}}-style arguments and the count last
     * @param other   the English plural, the same way
     * @return the declaration
     * @throws IllegalStateException if a key was already declared with different English
     */
    public static PluralString of(String key, String one, String other) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        Map<PluralRules.Category, String> english = new LinkedHashMap<>();
        for (PluralRules.Category category : PluralRules.Category.values()) {
            String text = category == PluralRules.Category.ONE ? one : other;
            english.put(category, text);
            I18n.declare(key + "." + category.suffix(), text);
        }
        return new PluralString(key, Map.copyOf(english));
    }

    /**
     * The sentence for {@code count}, in the {@linkplain I18n#locale() locale in effect here}.
     *
     * <p>A pattern a translator got wrong falls back to the English of the same form rather than
     * throwing in the middle of a paint, exactly as {@link I18nString#format} does.
     *
     * @param count how many; it chooses the form and is appended to {@code args} as text
     * @param args  the caller's arguments, which keep their own positions
     * @return the formatted sentence
     */
    public String format(long count, Object... args) {
        Locale locale = I18n.locale();
        PluralRules.Category category = PluralRules.select(locale, count);
        String fallback = english.get(category);
        String pattern = I18n.resolve(key + "." + category.suffix(), null, locale);
        if (pattern == null) {
            // The language's own OTHER before the English: a catalog that translated the plural
            // and not the dual is still that language, and reading a Hebrew sentence in English
            // because one form is missing is the worse of the two answers.
            pattern = I18n.resolve(key + "." + PluralRules.Category.OTHER.suffix(), fallback,
                    locale);
        }
        Object[] all = new Object[(args == null ? 0 : args.length) + 1];
        if (args != null) {
            System.arraycopy(args, 0, all, 0, args.length);
        }
        all[all.length - 1] = I18n.localizeDigits(Long.toString(count));
        try {
            return new MessageFormat(pattern, locale).format(all);
        } catch (IllegalArgumentException malformedPattern) {
            try {
                return new MessageFormat(fallback, locale).format(all);
            } catch (IllegalArgumentException ourOwnPatternIsBroken) {
                return fallback;
            }
        }
    }

    /** The lookup key, without a form suffix. */
    public String key() {
        return key;
    }

    /**
     * The English for one form: the fallback, and never {@code null}.
     *
     * @param category the form
     * @return its English
     */
    public String english(PluralRules.Category category) {
        return english.get(category);
    }
}
