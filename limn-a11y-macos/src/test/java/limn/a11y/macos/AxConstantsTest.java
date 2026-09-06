package limn.a11y.macos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private record Dump(Set<String> exported, Set<String> missing) {
    }

    private static Dump read() {
        Set<String> exported = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        try (InputStream in = AxConstantsTest.class.getResourceAsStream("appkit-constants.txt")) {
            if (in == null) {
                throw new IllegalStateException("appkit-constants.txt is missing; regenerate it with "
                        + "scripts/a11y/macos/dump-appkit-constants.swift on the guest");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
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
        return new Dump(exported, missing);
    }

    @Test
    void theDumpItselfIsReadable() {
        Dump dump = read();
        // A parser that silently matched nothing would make every assertion below vacuous, which is
        // the way a test like this fails without failing.
        assertTrue(dump.exported().size() > 50,
                "only " + dump.exported().size() + " symbols parsed out of the dump; the format changed");
        assertTrue(dump.exported().contains("NSAccessibilityButtonRole"), "the dump has no button role");
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
