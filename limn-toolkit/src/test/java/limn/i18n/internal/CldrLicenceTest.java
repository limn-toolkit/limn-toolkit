package limn.i18n.internal;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLDR data the toolkit ships travels with CLDR's licence.
 *
 * <p>Two resources beside {@link LanguageAliases} are rows of a dump of CLDR's data, and three
 * classes ({@code LayoutDirection}, {@code NumberingSystem}, {@code PluralRules}) write out
 * tables taken from the same data. CLDR is under the Unicode License v3, which lets anyone copy
 * its data on one condition: its copyright and permission notice appear with every copy. The jar
 * is the copy an application redistributes, and until 2026-10-25 it carried neither the notice
 * nor a word about it, and NOTICE named no Unicode data at all.
 *
 * <p>The licence is CLDR's own file, at the release the dumps were asked of, byte for byte. A
 * dump regenerated under a newer ICU carries newer CLDR data, whose licence may name other years:
 * the last case fails until NOTICE has been read against the new release and the text taken again.
 */
class CldrLicenceTest {

    /** The resource beside the tables, as the jar carries it. */
    private static final String LICENCE = "CLDR-LICENSE.txt";

    /**
     * SHA-256 of {@code LICENSE} at CLDR's {@code release-48} tag
     * (https://raw.githubusercontent.com/unicode-org/cldr/release-48/LICENSE), the CLDR that
     * ICU 78.3 carries, with its line ends as they are there.
     */
    private static final String CLDR_48_LICENCE =
            "b4c0ae8ef04f7059f96ce5bbe0467f9fe6f6d81bbe13517701dfeb961fb4d0b6";

    private static final List<String> SHIPPED_TABLES = List.of(
            "language-aliases.txt", "likely-regions.txt");

    private static final List<String> DUMPS = List.of(
            "/limn/i18n/cldr-locale-facts.txt", "/limn/i18n/cldr-cardinal.txt");

    @Test
    void theLicenceShipsBesideTheTablesAsCldrPublishesIt() throws Exception {
        String licence = licence();
        assertTrue(licence.startsWith("UNICODE LICENSE V3\n"), "not the Unicode License v3");
        // A checkout that turns line ends into CRLF still holds CLDR's text, so the digest is of
        // the text with LF line ends, which is how CLDR's repository holds it.
        assertEquals(CLDR_48_LICENCE, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(licence.getBytes(StandardCharsets.UTF_8))),
                LICENCE + " is not CLDR's LICENSE at release-48");
    }

    @Test
    void everyShippedTableNamesTheLicenceAndItsCopyright() throws IOException {
        String copyright = copyrightLine(licence());
        for (String table : SHIPPED_TABLES) {
            String header = header(text(LanguageAliases.class, table, table));
            assertTrue(header.contains(copyright), table + " does not carry \"" + copyright + "\"");
            assertTrue(header.contains(LICENCE), table + " does not name " + LICENCE);
        }
    }

    @Test
    void theNoticeNamesTheLicenceAndTheIcuTheDumpsWereAskedOf() throws IOException {
        String notice = Files.readString(RepositoryRoot.find().resolve("NOTICE"), StandardCharsets.UTF_8);
        assertTrue(notice.contains("limn-toolkit/src/main/resources/limn/i18n/internal/" + LICENCE),
                "NOTICE does not say where CLDR's licence is");
        assertTrue(notice.contains(copyrightLine(licence())), "NOTICE does not carry CLDR's copyright");
        Pattern icu = Pattern.compile("ICU (\\d+(?:\\.\\d+)*)");
        for (String dump : DUMPS) {
            Matcher found = icu.matcher(header(text(CldrLicenceTest.class, dump, dump)));
            assertTrue(found.find(), dump + " names no ICU in its header");
            assertTrue(notice.contains("as ICU " + found.group(1) + " carries it"),
                    dump + " was asked of ICU " + found.group(1) + ", and NOTICE names another: read"
                            + " CLDR's licence at the release that ICU carries, then NOTICE");
        }
    }

    private static String licence() throws IOException {
        return text(LanguageAliases.class, LICENCE, LICENCE + " beside LanguageAliases");
    }

    /** The licence's own copyright line: "Copyright © 2004-2025 Unicode, Inc." at release-48. */
    private static String copyrightLine(String licence) {
        for (String line : licence.split("\n")) {
            if (line.startsWith("Copyright ")) {
                return line.strip();
            }
        }
        throw new AssertionError(LICENCE + " carries no copyright line");
    }

    /** A file's leading comment, the lines starting with '#' and with that sign and one space off. */
    private static String header(String text) {
        StringBuilder header = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.startsWith("#")) {
                break;
            }
            header.append(line.replaceFirst("^# ?", "")).append(' ');
        }
        return header.toString().replaceAll("\\s+", " ");
    }

    private static String text(Class<?> owner, String resource, String what) throws IOException {
        try (InputStream in = owner.getResourceAsStream(resource)) {
            assertNotNull(in, what + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
