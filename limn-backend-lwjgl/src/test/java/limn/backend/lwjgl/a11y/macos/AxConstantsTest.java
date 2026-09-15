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
    private static final Set<String> OWED_TO_THE_REGENERATION = Set.of(
            "accessibilityAttributeValue:", "accessibilityAttributeNames",
            // M1 and MACOS-NEW-5, read the same day by the same script (its encodings table):
            // -isAccessibilityDisclosed B16@0:8, -accessibilityDisclosedByRow @16@0:8,
            // -accessibilityDisclosedRows @16@0:8, -isAccessibilityExpanded B16@0:8, each from
            // NSAccessibilityElement. OWED_SHAPES below holds each against its closure.
            "isAccessibilityDisclosed", "accessibilityDisclosedByRow", "accessibilityDisclosedRows",
            "isAccessibilityExpanded",
            // MACOS-NEW-11, the same reading: -setAccessibilityDisclosed: v20@0:8B16,
            // -setAccessibilityExpanded: v20@0:8B16, -setAccessibilityValue: v24@0:8@16, each from
            // NSAccessibilityElement.
            "setAccessibilityDisclosed:", "setAccessibilityExpanded:", "setAccessibilityValue:");

    /**
     * The encoding the 2026-09-13 guest reading gave each owed selector, copied from
     * readings/macos-appkit-constants.txt, so that a closure installed under an owed selector is held
     * against its shape before the regeneration puts the line in the committed dump.
     */
    private static final Map<String, String> OWED_SHAPES = Map.of(
            "accessibilityAttributeValue:", "@24@0:8@16",
            "accessibilityAttributeNames", "@16@0:8",
            "isAccessibilityDisclosed", "B16@0:8",
            "accessibilityDisclosedByRow", "@16@0:8",
            "accessibilityDisclosedRows", "@16@0:8",
            "isAccessibilityExpanded", "B16@0:8",
            "setAccessibilityDisclosed:", "v20@0:8B16",
            "setAccessibilityExpanded:", "v20@0:8B16",
            "setAccessibilityValue:", "v24@0:8@16");

    @Test
    void everyOwedSelectorIsReadAndItsReadEncodingIsTheShapeOfItsClosure() {
        assertEquals(OWED_TO_THE_REGENERATION, OWED_SHAPES.keySet(),
                "an owed selector with no encoding copied from the reading is held against nothing");
        for (Map.Entry<String, String> owed : OWED_SHAPES.entrySet()) {
            AxSelectors.Kind kind = AxSelectors.kindOf(owed.getKey());
            assertEquals(SHAPES.get(kind), owed.getValue(),
                    "-" + owed.getKey() + " is listed as a " + kind + " closure, and the guest read " + owed.getValue());
        }
    }

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

    /**
     * The encoding each closure shape is written for, in the dump's own spelling. These are not
     * handed to {@code class_addMethod} — the running AppKit's are — but what the closure in
     * {@code AxElementClass} was written to read; a selector whose dump encoding is not its listed
     * shape's would be installed with a closure that reads the wrong registers.
     */
    private static final Map<AxSelectors.Kind, String> SHAPES = Map.of(
            AxSelectors.Kind.ID, "@16@0:8",
            AxSelectors.Kind.BOOL, "B16@0:8",
            AxSelectors.Kind.INTEGER, "q16@0:8",
            AxSelectors.Kind.RANGE, "{_NSRange=QQ}16@0:8",
            AxSelectors.Kind.ID_OF_ID, "@24@0:8@16",
            AxSelectors.Kind.BOOL_OF_SELECTOR, "B24@0:8:16",
            AxSelectors.Kind.ID_OF_TWO_INTEGERS, "@32@0:8q16q24",
            AxSelectors.Kind.ID_OF_POINT, "@32@0:8{CGPoint=dd}16",
            AxSelectors.Kind.VOID_OF_BOOL, "v20@0:8B16",
            AxSelectors.Kind.VOID_OF_ID, "v24@0:8@16");

    /**
     * The review of MACOS-NEW-6: tying a selector to the dump by name says it exists, not that the
     * closure installed under it has its shape. Every listed selector the dump reads must have exactly
     * the encoding of the shape {@link AxSelectors} lists for it.
     */
    @Test
    void everyInstalledSelectorsEncodingInTheDumpIsTheShapeOfItsClosure() {
        Dump dump = read();
        assertEquals(java.util.EnumSet.allOf(AxSelectors.Kind.class), java.util.EnumSet.copyOf(SHAPES.keySet()),
                "a closure shape with no encoding to hold it against checks nothing");
        Map<String, String> misshapen = new TreeMap<>();
        Set<AxSelectors.Kind> held = new java.util.HashSet<>();
        for (String selector : AxSelectors.all()) {
            String read = dump.encodings().get(selector);
            if (read == null) continue;   // an owed selector: the test above accounts for it
            AxSelectors.Kind kind = AxSelectors.kindOf(selector);
            held.add(kind);
            if (!read.equals(SHAPES.get(kind))) {
                misshapen.put(selector, "listed " + kind + " (" + SHAPES.get(kind) + "), dump " + read);
            }
        }
        assertTrue(misshapen.isEmpty(), "selectors whose closure is not the shape AppKit declares: "
                + misshapen);
        Set<AxSelectors.Kind> unheld = java.util.EnumSet.allOf(AxSelectors.Kind.class);
        unheld.removeAll(held);
        // A shape only owed selectors have (ID_OF_ID: the owed accessibilityAttributeValue:) is held by
        // everyOwedSelectorIsReadAndItsReadEncodingIsTheShapeOfItsClosure until the regeneration.
        unheld.removeIf(kind -> AxSelectors.all().stream()
                .filter(selector -> AxSelectors.kindOf(selector) == kind)
                .allMatch(OWED_TO_THE_REGENERATION::contains));
        assertTrue(unheld.isEmpty(), "shapes no installed selector in the dump exercised: " + unheld);
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

    /**
     * The symbols the bridge names that the committed dump predates, each with the value the guest
     * read for it.
     *
     * <p>Read on the macOS 26.6.2 guest (25G83) on 2026-09-13 by this same script, extended to ask for
     * them (sha256 90e6d928…, readings/macos-appkit-constants.txt, "notifications and announcement
     * keys" and "action names"). Symbols are resolved by {@code dlsym} at run time and never written
     * into source, so what is owed is only the dump's line; the one regeneration at the end of the
     * macOS lane empties this map, and the companion test fails until it does.
     */
    private static final Map<String, String> SYMBOLS_OWED_TO_THE_REGENERATION = Map.of(
            "NSAccessibilitySelectedCellsChangedNotification", "AXSelectedCellsChanged",
            "NSAccessibilityRowExpandedNotification", "AXRowExpanded",
            "NSAccessibilityRowCollapsedNotification", "AXRowCollapsed");

    @Test
    void everySymbolTheTablesNameIsExportedByAppKit() {
        Dump dump = read();
        Set<String> invented = new LinkedHashSet<>();
        for (String symbol : allSymbols()) {
            if (!dump.exported().contains(symbol)
                    && !SYMBOLS_OWED_TO_THE_REGENERATION.containsKey(symbol)) {
                invented.add(symbol);
            }
        }
        assertTrue(invented.isEmpty(),
                "this module names symbols the running AppKit does not export: " + invented
                        + ". A role constant is a string on this platform, so this is a null pointer "
                        + "at run time and not a compile error.");
    }

    @Test
    void theSymbolsOwedToTheRegenerationAreNamedAndStillOwed() {
        Dump dump = read();
        for (String owed : SYMBOLS_OWED_TO_THE_REGENERATION.keySet()) {
            assertTrue(allSymbols().contains(owed),
                    owed + " is no longer named by any table, so it is owed nothing: drop it from the map");
            assertFalse(dump.exported().contains(owed) || dump.missing().contains(owed),
                    "the dump now reads " + owed + ": the regeneration has paid it, drop it from the map");
        }
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
