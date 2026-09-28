package limn.i18n.internal;

import limn.io.Resources;

import java.util.HashMap;
import java.util.Map;

/**
 * The region a language named alone most likely means, by CLDR's likely subtags: Germany for
 * {@code de}, Brazil for {@code pt}, Egypt for {@code ar}, Iraq for {@code ckb}, the world
 * ({@code 001}) for Esperanto. The JDK carries the same data and does not expose it:
 * {@link java.util.Locale} has no counterpart of ICU's {@code maximize}.
 *
 * <p>Measured, not listed from memory: the {@code likely} rows of {@code cldr-locale-facts.txt},
 * which {@code scripts/i18n/dump-cldr-locale-facts.mjs} asks ICU for over every two- and
 * three-letter language, some seven thousand of them, shipped beside this class as
 * {@code likely-regions.txt} and read the first time a language is asked about.
 *
 * <p>Keyed on the language as ICU reads it, so a code ICU replaces has no row of its own: read the
 * locale through {@link LanguageAliases} first, which also brings the region an alias carries
 * ({@code prs}, Dari, is {@code fa-AF} and means Afghanistan rather than Persian's Iran).
 */
public final class LikelyRegions {

    private LikelyRegions() {
    }

    /** The resource beside this class, found through the class so a named module reads its own. */
    private static final String RESOURCE = "likely-regions.txt";

    /** Read on first use and never again: the holder is initialized by the first lookup. */
    private static final class Table {
        static final Map<String, String> REGIONS = read();
    }

    /**
     * The region {@code language} named alone most likely means.
     *
     * @param language a language subtag, as ICU reads it
     * @return the region, two letters or a UN number such as {@code 001}, or {@code null} for a
     *         language CLDR gives none
     */
    public static String of(String language) {
        return Table.REGIONS.get(language);
    }

    private static Map<String, String> read() {
        String text = Resources.text(LikelyRegions.class.getResourceAsStream(RESOURCE),
                RESOURCE, "likely regions");
        Map<String, String> regions = new HashMap<>();
        for (String line : text.split("\n")) {
            if (!line.startsWith("likely ")) {
                continue;
            }
            for (String pair : line.substring("likely ".length()).strip().split(" ")) {
                int dash = pair.indexOf('-');
                regions.put(pair.substring(0, dash), pair.substring(dash + 1));
            }
        }
        return Map.copyOf(regions);
    }
}
