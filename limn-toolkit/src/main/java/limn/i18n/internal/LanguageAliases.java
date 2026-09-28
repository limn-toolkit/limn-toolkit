package limn.i18n.internal;

import limn.io.Resources;

import java.util.HashMap;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The language a locale's code stands for, read as ICU reads it before it looks anything up.
 *
 * <p>A language can have several codes. {@code pes}, the ISO 639-3 code for Iranian Persian, and
 * {@code fas} and {@code per}, its ISO 639-2 codes, are all {@code fa} to ICU; {@code fra} and
 * {@code fre} are {@code fr}, {@code cmn} is {@code zh}, {@code sh} is {@code sr-Latn}. Java's
 * {@link Locale} replaces only {@code iw}, {@code ji} and {@code in} on its own and hands every
 * other code over as it was written, so a table keyed on the language subtag, the toolkit's own or
 * the platform's, answers such a locale as a language nobody listed: English's plural forms, no
 * translation, the United States' week, the root locale's separators, order and case. Whatever
 * decides by language reads the locale through here first.
 *
 * <p>The codes are the {@code alias} rows of {@code cldr-locale-facts.txt}, every two- and
 * three-letter code ICU replaces, shipped beside this class as {@code language-aliases.txt} and
 * read the first time a locale is asked about. A replacement that carries a script or a region
 * fills in only what the locale leaves out, which is how ICU replaces it: {@code prs}, Dari, is
 * {@code fa-AF}, and {@code prs-IR} is {@code fa-IR}; {@code sh} is {@code sr-Latn}, and
 * {@code sh-Cyrl} is {@code sr-Cyrl}.
 *
 * <p>The rows include {@code iw}, {@code ji} and {@code in}, which {@code Locale} still answers
 * for Hebrew, Yiddish and Indonesian on a JVM started with
 * {@code -Djava.locale.useOldISOCodes=true}. {@link #language(String)} maps them to the current
 * codes, so a table read through it needs only those; a locale {@link #resolve} builds is still
 * spelled the old way under that flag, since {@code Locale} applies it to every instance.
 */
public final class LanguageAliases {

    private LanguageAliases() {
    }

    /** The resource beside this class, found through the class so a named module reads its own. */
    private static final String RESOURCE = "language-aliases.txt";

    /** What a code is read as: a language, and the script and region it carries, each maybe empty. */
    private record Replacement(String language, String script, String region) {
    }

    /** Read on first use and never again: the holder is initialized by the first lookup. */
    private static final class Table {
        static final Map<String, Replacement> ALIASES = read();
    }

    /**
     * The locales already resolved, bounded by the aliased locales an application actually uses.
     * A formatter asks on every number it writes, and building the locale again each time would
     * allocate on a paint path for exactly the locales this class exists for.
     */
    private static final Map<Locale, Locale> RESOLVED = new ConcurrentHashMap<>();

    /**
     * The language subtag ICU reads {@code language} as: {@code fa} for {@code pes},
     * {@code fr} for {@code fra}, {@code sr} for {@code sh}, {@code he} for the legacy {@code iw}.
     *
     * @param language a language subtag as {@link Locale#getLanguage()} answers it
     * @return the language it stands for, or {@code language} itself when ICU reads it as itself
     */
    public static String language(String language) {
        Replacement replacement = Table.ALIASES.get(language);
        return replacement == null ? language : replacement.language();
    }

    /**
     * {@code locale} with its language read as ICU reads it, and the script and region the
     * replacement carries filled in where {@code locale} names none: {@code pes} is {@code fa},
     * {@code prs} is {@code fa-AF}, {@code prs-IR} is {@code fa-IR}, {@code sh-RS} is
     * {@code sr-Latn-RS}. Variants and extensions are kept: {@code pes-u-nu-latn} is
     * {@code fa-u-nu-latn}.
     *
     * <p>A variant only {@code Locale}'s constructors accept, which {@link Locale.Builder}
     * refuses, is kept too, and the replacement's script is then dropped, since such a locale
     * cannot carry one.
     *
     * @param locale the locale to read
     * @return the locale ICU reads, or {@code locale} itself when its language is no alias
     */
    public static Locale resolve(Locale locale) {
        Replacement replacement = Table.ALIASES.get(locale.getLanguage());
        if (replacement == null) {
            return locale;
        }
        return RESOLVED.computeIfAbsent(locale, asked -> replace(asked, replacement));
    }

    private static Locale replace(Locale locale, Replacement replacement) {
        String script = locale.getScript().isEmpty() ? replacement.script() : locale.getScript();
        String region = locale.getCountry().isEmpty() ? replacement.region() : locale.getCountry();
        try {
            return new Locale.Builder().setLocale(locale).setLanguage(replacement.language())
                    .setScript(script).setRegion(region).build();
        } catch (IllformedLocaleException legacy) {
            return new Locale(replacement.language(), region, locale.getVariant());
        }
    }

    private static Map<String, Replacement> read() {
        String text = Resources.text(LanguageAliases.class.getResourceAsStream(RESOURCE),
                RESOURCE, "language aliases");
        Map<String, Replacement> aliases = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] row = line.strip().split(" ");
            if (row.length != 3 || !row[0].equals("alias")) {
                continue;
            }
            String[] subtags = row[2].split("-");
            String script = "";
            String region = "";
            for (int i = 1; i < subtags.length; i++) {
                if (subtags[i].length() == 4) {
                    script = subtags[i];
                } else {
                    region = subtags[i];
                }
            }
            aliases.put(row[1], new Replacement(subtags[0], script, region));
        }
        return Map.copyOf(aliases);
    }
}
