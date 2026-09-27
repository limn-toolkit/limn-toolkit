package limn.i18n;

import java.util.Locale;

/**
 * A key no registered bundle translates into a language, so the text fell back to the English its
 * declaration carries: a typo in a key, a key left behind by a rename, or a file that has not
 * caught up with the code. {@link I18n#observeUnresolvedKeys} hands one of these to its listeners,
 * once per key and locale.
 *
 * <p>A record so that what a report carries can grow without breaking the listeners that read it.
 *
 * @param key     the key as it was looked up; a plural sentence with no form in the language at
 *                all reports its {@code .other} key
 * @param english the English that was shown instead
 * @param locale  the locale the key was looked up in
 */
public record UnresolvedKey(String key, String english, Locale locale) {
}
