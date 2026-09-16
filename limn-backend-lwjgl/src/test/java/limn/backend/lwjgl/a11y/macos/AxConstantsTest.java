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
 * <p>The committed dump was regenerated once, at the end of the phase-3 macOS lane, on the macOS 26.6.2
 * guest (25G83) on 2026-09-15, so every selector and symbol the bridge names at that point is read
 * there; the sets of selectors and symbols owed to that regeneration, which this test carried until it
 * ran, are gone with it. A selector or symbol added later is read on a guest before it is used.
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
            if (!dump.encodings().containsKey(selector)) unread.add(selector);
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
            if (read == null) continue;   // an unread selector: the test above fails on it
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
        assertTrue(unheld.isEmpty(), "shapes no installed selector in the dump exercised: " + unheld);
    }

    /** Every symbol any table in this module names. */
    private static Set<String> allSymbols() {
        Set<String> symbols = new LinkedHashSet<>(AxRoles.symbols());
        symbols.addAll(AxNotifications.symbols());
        symbols.addAll(AxActions.actionSymbols());
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

    /**
     * The sites of this bridge that answer from a message or a value read off a guest rather than
     * from the committed dump: the file, the declaration, and every phrase the comment above it must
     * carry, lower-cased.
     *
     * <p>Kept explicit, as the Linux bridge's list is, because no assertion can tell a platform fact
     * from a toolkit one by reading the source. What it can do is hold the sites a reviewer has
     * already found, so none of them loses its citation again.
     *
     * <p>{@code AxBridge.java} entered the list on 2026-09-16: the phase-3 fix-round critic found
     * {@code windowElement}'s {@code -window} encoding cited as "read on the guest … 2026-09-15" with
     * no file behind it, and {@code AxBridge.java} the one file of this package with no
     * {@code readings/} citation at all — a legibility gap on the bridge whose own regeneration ratchet
     * checks selectors and symbols against the dump and is silent about prose.
     */
    private static final java.util.List<String[]> CITED = java.util.List.of(
            new String[] {"AxBridge.java", "long windowElement()", "readings/", "@16@0:8"},
            new String[] {"AxActions.java", "Map<String, String> ACTION_SYMBOL", "readings/"},
            new String[] {"AxGrid.java", "static boolean isHeaderCell(", "readings/"},
            new String[] {"AxGrid.java", "long sortDirection(", "readings/"},
            new String[] {"AxGate.java", "final class AxGate", "readings/"});

    /** A citation in a comment: {@code readings/<file>}, up to the first space or punctuation. */
    private static final Pattern CITATION = Pattern.compile("readings/([A-Za-z0-9._\\-]+)");

    /**
     * Every one of those sites cites the reading it came from, by its file under {@code readings/}
     * (ADR 039 §12.3's rule, as the Linux bridge's
     * {@code AtspiConstantsTest#thePlatformFactsWithoutOneReadingSayWhichHalfWasTakenAndWhy} holds it),
     * <b>and the file it names is a file that exists</b>.
     *
     * <p>A ratchet on the comment and not on a value: the value is what the dump and the two tests
     * above already hold, and what they cannot hold is a fact that lives in a guest's Objective-C
     * runtime rather than in AppKit's exported symbols. For those, the citation is the whole of the
     * evidence, and a citation nobody can follow costs the next reader a guest.
     *
     * <p><b>Which is why the substring is not enough</b> (the phase-3 fix round's review, 2026-09-16):
     * its first cut required only the token {@code readings/} above each site, so a citation naming a
     * file nobody ever wrote passed — exactly the failure the rule exists to prevent. Every citation is
     * now resolved against the readings trees themselves.
     *
     * <p><b>What this half cannot do, and says so rather than pretending.</b> The readings are not in
     * the repository: {@code .claude/} is ignored, so a clone and a continuous-integration checkout
     * have none, and a worktree has none of its own — it reaches the main checkout's only because it
     * lives under it. So the directories are searched for by walking up from the repository root
     * ({@code .claude/pending/<round>/readings}, any round, so a later round's tree resolves too), and
     * where none is found this half cannot run and the substring half stands alone. It runs where the
     * citations are written, which is where a wrong one is introduced. One consequence to know about:
     * a citation wrapped across two source lines in the middle of its file name reads here as a name
     * that resolves to nothing, so keep a {@code readings/<file>} on one line.
     *
     * <p>A second limit, older than this half and true of the whole case: the source files it reads
     * are not inputs Gradle tracks, so a commit that edits only a comment leaves the test task up to
     * date and this does not run. It runs whenever anything the task does track changes, and always
     * under the round's closing {@code check --rerun-tasks} — which is how its red proof was taken.
     */
    @Test
    void everyFactReadOffAGuestRatherThanOffTheDumpCitesItsReading() throws IOException {
        java.nio.file.Path source = limn.testing.RepositoryRoot.find()
                .resolve("limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/a11y/macos");
        java.util.List<java.nio.file.Path> readings = readingsDirectories();
        for (String[] site : CITED) {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(
                    source.resolve(site[0]), StandardCharsets.UTF_8);
            String above = commentAbove(lines, site[1]);
            String comment = above.toLowerCase(java.util.Locale.ROOT);
            for (int i = 2; i < site.length; i++) {
                assertTrue(comment.contains(site[i]), site[0] + ": " + site[1]
                        + ("readings/".equals(site[i])
                                ? " answers from a guest and not from the committed dump, so its"
                                        + " comment must cite the reading it came from, by its file"
                                        + " under readings/"
                                : " must quote what was read — \"" + site[i] + "\" is not in the"
                                        + " comment above it"));
            }
            if (readings.isEmpty()) continue;
            Matcher cited = CITATION.matcher(above);
            while (cited.find()) {
                String file = cited.group(1);
                assertTrue(readings.stream().anyMatch(at -> java.nio.file.Files.exists(at.resolve(file))),
                        site[0] + ": " + site[1] + " cites readings/" + file + ", and no such file is"
                                + " in " + readings + ". A citation nobody can follow costs the next"
                                + " reader a guest, which is what this list is for.");
            }
        }
    }

    /**
     * The readings trees this checkout can see: {@code .claude/pending/<round>/readings} under the
     * repository root or under any directory above it, which is how a worktree finds the main
     * checkout's. Empty where there are none, which the caller treats as "cannot run" and not as
     * "nothing is cited".
     *
     * @return the directories, in the order found
     * @throws IOException if a directory that exists cannot be listed
     */
    private static java.util.List<java.nio.file.Path> readingsDirectories() throws IOException {
        java.util.List<java.nio.file.Path> found = new java.util.ArrayList<>();
        for (java.nio.file.Path at = limn.testing.RepositoryRoot.find(); at != null; at = at.getParent()) {
            java.nio.file.Path pending = at.resolve(".claude").resolve("pending");
            if (!java.nio.file.Files.isDirectory(pending)) continue;
            try (java.util.stream.Stream<java.nio.file.Path> rounds = java.nio.file.Files.list(pending)) {
                rounds.map(round -> round.resolve("readings"))
                        .filter(java.nio.file.Files::isDirectory)
                        .forEach(found::add);
            }
        }
        return found;
    }

    /**
     * The comment block directly above a declaration, as one line, markers and wrapping taken out so
     * a phrase is found whether or not the author's line ended in the middle of it.
     *
     * @param lines       the source
     * @param declaration what the declaration's line contains
     * @return the block above it, unwrapped; the empty string when there is none
     */
    private static String commentAbove(java.util.List<String> lines, String declaration) {
        int at = -1;
        for (int i = 0; i < lines.size() && at < 0; i++) {
            if (lines.get(i).contains(declaration)) at = i;
        }
        assertTrue(at >= 0, "no declaration containing " + declaration);
        StringBuilder out = new StringBuilder();
        for (int i = at - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*")) break;
            out.insert(0, line.replaceFirst("^(//+|/\\*+|\\*+)", "") + " ");
        }
        return out.toString().replaceAll("\\s+", " ");
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
