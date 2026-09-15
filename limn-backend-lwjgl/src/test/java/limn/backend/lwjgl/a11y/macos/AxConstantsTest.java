package limn.backend.lwjgl.a11y.macos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §12.3's constants rule, in the only form that can run on a machine with no AppKit.
 *
 * <p>The rule is that a platform constant is read off the platform and asserted in a test, never
 * recalled into source. On the other two platforms the reading and the asserting can happen in the
 * same place. Here they cannot: the values live in a framework that exists on one operating system,
 * and a test that only ran there would be a test that never ran.
 *
 * <p>So the reading is {@code scripts/a11y/macos/dump-appkit-constants.swift}, which dlsyms every
 * symbol against the running AppKit, and its output is checked in beside this test. This test is
 * the asserting: <b>every symbol {@link AxRoles} names must appear in that output as exported.</b>
 * A symbol invented at a desk fails here, on any machine, in a second — which is the failure the
 * dump script already caught once by hand, when the separator subrole this record was about to use
 * turned out not to exist.
 *
 * <p>What this cannot catch is AppKit changing a value after the file was captured, and nothing
 * short of running on the machine can. That is what the probe run's own startup assertions are for;
 * this is the gate that runs on every commit.
 */
class AxConstantsTest {

    private static final Pattern EXPORTED = Pattern.compile("^\\s{2}(\\w+) = \"(.+)\"$");
    private static final Pattern NOT_EXPORTED = Pattern.compile("^\\s*!! NOT EXPORTED BY THIS APPKIT: (.+)$");
    /** A line of the dump's "selector type encodings" section: {@code   -name  encoding  (from Class)}. */
    private static final Pattern ENCODING = Pattern.compile("^\\s{2}-(\\S+)\\s{2}(\\S+)\\s{2}\\(from (\\w+)\\)$");
    private static final String ENCODINGS_SECTION = "==== selector type encodings ====";

    private record Dump(Set<String> exported, Set<String> missing, Map<String, String> encodings) {
    }

    private static Dump read() {
        Set<String> exported = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        Map<String, String> encodings = new TreeMap<>();
        boolean inEncodings = false;
        try (InputStream in = AxConstantsTest.class.getResourceAsStream("appkit-constants.txt")) {
            if (in == null) {
                throw new IllegalStateException("appkit-constants.txt is missing; regenerate it with "
                        + "scripts/a11y/macos/dump-appkit-constants.swift on the guest");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("==== ")) inEncodings = line.equals(ENCODINGS_SECTION);
                Matcher encoded = ENCODING.matcher(line);
                if (inEncodings && encoded.matches()) {
                    encodings.put(encoded.group(1), encoded.group(2));
                    continue;
                }
                Matcher hit = EXPORTED.matcher(line);
                if (hit.matches()) {
                    exported.add(hit.group(1));
                    continue;
                }
                Matcher gone = NOT_EXPORTED.matcher(line);
                if (gone.matches()) {
                    for (String name : gone.group(1).split(",")) missing.add(name.strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Dump(exported, missing, encodings);
    }

    @Test
    void theDumpItselfIsReadable() {
        Dump dump = read();
        // A parser that silently matched nothing would make every assertion below vacuous, which is
        // the way a test like this fails without failing.
        assertTrue(dump.exported().size() > 50,
                "only " + dump.exported().size() + " symbols parsed out of the dump; the format changed");
        assertTrue(dump.exported().contains("NSAccessibilityButtonRole"), "the dump has no button role");
        assertTrue(dump.encodings().size() > 50,
                "only " + dump.encodings().size() + " selector encodings parsed; the format changed");
        assertEquals("@16@0:8", dump.encodings().get("accessibilityRole"),
                "the encodings section did not parse as AxObjC reads it");
    }

    /**
     * The two selectors f4bc544 installed for AXElementBusy that the committed dump predates.
     *
     * <p>Read on the macOS 26.6.2 guest on 2026-09-13 by this same script, with these very selectors
     * already in its list (readings/macos-appkit-constants.txt: {@code -accessibilityAttributeValue:
     * @24@0:8@16} and {@code -accessibilityAttributeNames @16@0:8}, both from
     * {@code NSAccessibilityElement}); the committed resource is regenerated once, at the end of the
     * macOS lane, and that regeneration empties this set — the test below fails until it does.
     */
    private static final Set<String> OWED_TO_THE_REGENERATION =
            Set.of("accessibilityAttributeValue:", "accessibilityAttributeNames");

    /**
     * MACOS-NEW-6: every selector the bridge installs has an encoding in the dump. An encoding is read
     * from the running AppKit at run time and never written into source (§12.3), so this is the only
     * check a selector gets before it reaches a Mac: a name no AppKit declares, or a new one nobody
     * read off the guest, fails here on any machine.
     */
    @Test
    void everySelectorTheBridgeInstallsHasAnEncodingInTheDump() {
        Dump dump = read();
        Set<String> unread = new LinkedHashSet<>();
        for (String selector : AxSelectors.all()) {
            if (!dump.encodings().containsKey(selector) && !OWED_TO_THE_REGENERATION.contains(selector)) {
                unread.add(selector);
            }
        }
        assertTrue(unread.isEmpty(), "the bridge installs selectors the dump has no encoding for: "
                + unread + ". Add each to scripts/a11y/macos/dump-appkit-constants.swift's selector "
                + "list and read it on the guest before installing it.");
    }

    @Test
    void theSelectorsOwedToTheRegenerationAreInstalledAndStillOwed() {
        Dump dump = read();
        for (String owed : OWED_TO_THE_REGENERATION) {
            assertTrue(AxSelectors.all().contains(owed),
                    owed + " is no longer installed, so it is owed nothing: drop it from the set");
            assertFalse(dump.encodings().containsKey(owed),
                    "the dump now reads " + owed + ": the regeneration has paid it, drop it from the set");
        }
    }

    /** Every symbol any table in this module names. */
    private static Set<String> allSymbols() {
        Set<String> symbols = new LinkedHashSet<>(AxRoles.symbols());
        symbols.addAll(AxNotifications.symbols());
        return symbols;
    }

    @Test
    void everySymbolTheTablesNameIsExportedByAppKit() {
        Dump dump = read();
        Set<String> invented = new LinkedHashSet<>();
        for (String symbol : allSymbols()) {
            if (!dump.exported().contains(symbol)) invented.add(symbol);
        }
        assertTrue(invented.isEmpty(),
                "this module names symbols the running AppKit does not export: " + invented
                        + ". A role constant is a string on this platform, so this is a null pointer "
                        + "at run time and not a compile error.");
    }

    @Test
    void theSymbolsTheDumpRecordsAsAbsentAreNotUsed() {
        Dump dump = read();
        // The dump names what it looked for and did not find. Those are exactly the plausible
        // inventions, so they are worth an assertion of their own rather than only being covered by
        // the one above: this is the list that says WHY, next time someone reaches for one.
        assertFalse(dump.missing().isEmpty(),
                "the dump records nothing as absent, which means the script stopped looking for the "
                        + "symbols that do not exist; that list is the point of it");
        for (String absent : dump.missing()) {
            assertFalse(allSymbols().contains(absent),
                    "this module uses " + absent + ", which the dump records as not exported by AppKit. "
                            + "The three announcement priorities are on that list permanently and on "
                            + "purpose: they are a C enum, and AxNotifications carries the numbers "
                            + "with §12.3's exception written beside them.");
        }
    }
}
