package limn.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code alias} rows of {@code cldr-locale-facts.txt}: every two- and three-letter code ICU
 * reads as another language, with the tag it reads it as ({@code pes fa}, {@code prs fa-AF},
 * {@code sh sr-Latn}); and its {@code region} rows, every numeric region ICU reads as a two-letter
 * one ({@code 364 IR}, and {@code uz-810 UZ} where the language picks among several), then every
 * retired two-letter region it reads as a current one ({@code SU RU}, {@code uz-SU UZ}).
 * {@code scripts/i18n/dump-cldr-locale-facts.mjs} asks ICU for both.
 *
 * <p>Read by every test that holds a decision made by language or region to what it decides for
 * the replacement, in whichever package the decision lives.
 */
public final class CldrAliasRows {

    /** The dump, relative to the module directory Gradle runs the tests in. */
    public static final Path FACTS = Path.of("src/test/resources/limn/i18n/cldr-locale-facts.txt");

    private CldrAliasRows() {
    }

    /**
     * Each row as the code and the tag ICU replaces it with, in the dump's order.
     *
     * @return the rows; the dump carries over four hundred
     * @throws IOException when the dump cannot be read
     */
    public static List<String[]> read() throws IOException {
        List<String[]> rows = rows("alias ");
        if (rows.size() < 400) {
            throw new IOException("the dump carries only " + rows.size() + " aliases");
        }
        return rows;
    }

    /**
     * Each {@code region} row as the tag and the two-letter region ICU reads its code as, in the
     * dump's order: {@code 364} alone for the number read with no language, then
     * {@code language-code} and {@code language-Script-code} where the language, or its script,
     * picks another of the regions that replaced the number; then the same for the retired
     * two-letter regions, {@code SU} alone and {@code uz-SU}.
     *
     * @return the rows; the dump carries over three hundred
     * @throws IOException when the dump cannot be read
     */
    public static List<String[]> regions() throws IOException {
        List<String[]> rows = rows("region ");
        if (rows.size() < 300) {
            throw new IOException("the dump carries only " + rows.size() + " numeric regions");
        }
        return rows;
    }

    private static List<String[]> rows(String kind) throws IOException {
        if (!Files.exists(FACTS)) {
            throw new IOException("the CLDR dump is missing: " + FACTS.toAbsolutePath());
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(FACTS, StandardCharsets.UTF_8)) {
            if (line.startsWith(kind)) {
                String[] parts = line.split(" ");
                rows.add(new String[]{parts[1], parts[2]});
            }
        }
        return rows;
    }
}
