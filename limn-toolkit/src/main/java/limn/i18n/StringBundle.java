package limn.i18n;

import java.util.Locale;

/**
 * A source of translations. Registered with {@link I18n#addBundle}, asked for one
 * key at a time, and free to be backed by anything: a properties file
 * ({@link PropertyBundle}), a map, a downloaded table, a database.
 *
 * <p>A bundle answers only for what it owns: returning {@code null} passes the key
 * to the next bundle and, in the end, to the English the {@link I18nString} carries.
 * That is what lets an application register one bundle per screen without any of
 * them knowing about the others.
 */
@FunctionalInterface
public interface StringBundle {

    /**
     * The translation for {@code key} in {@code locale}, or {@code null} when this
     * bundle does not have one. Called on the UI thread during measure and paint, so
     * it must not block; see {@link #prepare}.
     */
    String lookup(String key, Locale locale);

    /**
     * Loads whatever answering {@code locale} will need. Called by
     * {@link I18n#setLocale}, {@link I18n#addBundle} and {@link I18n#retainLocale}
     * <em>before</em> listeners are notified, which is the whole point of the method:
     * without it a file-backed bundle would read from disk lazily, and the first read
     * would land inside the measure pass of the frame that switches the language.
     *
     * <p>More than one locale may be prepared at a time: the process locale, and every
     * {@linkplain I18n#retainLocale retained} subtree locale. A bundle that keeps
     * per-locale state keeps it for each until {@link #release} says otherwise.
     *
     * <p>Must not throw for a locale it cannot serve; a missing translation is
     * {@link #lookup} returning {@code null}, not a failure.
     */
    default void prepare(Locale locale) {
    }

    /**
     * Drops whatever {@link #prepare} loaded for {@code locale}: nothing reads it any
     * more. Called by {@link I18n} when the process moves off a language no subtree
     * retains, and when the last retain for a subtree language is released. A later
     * {@code prepare} for the same locale must work again. Default: nothing, the right
     * answer for a bundle that holds no per-locale state.
     */
    default void release(Locale locale) {
    }
}
