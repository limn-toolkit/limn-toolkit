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
     * {@link I18n#setLocale} and {@link I18n#addBundle} <em>before</em> listeners are
     * notified, which is the whole point of the method: without it a file-backed
     * bundle would read from disk lazily, and the first read would land inside the
     * measure pass of the frame that switches the language.
     *
     * <p>Must not throw for a locale it cannot serve; a missing translation is
     * {@link #lookup} returning {@code null}, not a failure.
     */
    default void prepare(Locale locale) {
    }
}
