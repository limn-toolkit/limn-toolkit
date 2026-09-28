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
 * {@code sh sr-Latn}). {@code scripts/i18n/dump-cldr-locale-facts.mjs} asks ICU for them.
 *
 * <p>Read by every test that holds a decision made by language to what it decides for the
 * replacement, in whichever package the decision lives.
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
        if (!Files.exists(FACTS)) {
            throw new IOException("the CLDR dump is missing: " + FACTS.toAbsolutePath());
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(FACTS, StandardCharsets.UTF_8)) {
            if (line.startsWith("alias ")) {
                String[] parts = line.split(" ");
                rows.add(new String[]{parts[1], parts[2]});
            }
        }
        if (rows.size() < 400) {
            throw new IOException("the dump carries only " + rows.size() + " aliases");
        }
        return rows;
    }
}
