package limn.backend.lwjgl.a11y;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing under {@code scripts/} names a machine in the verification lab.
 *
 * <p>Decision 17 of the 2026-09-13 accessibility pass draws the line this asserts. A <b>platform
 * script</b> reads a platform and runs on any machine of that operating system &mdash; the constants
 * dumps, the out-of-process clients, the probes &mdash; and is versioned, because a second person
 * with a second machine can re-run it and get an answer. A <b>lab runner</b> brings up one
 * particular guest: its address, its login, the paths under its home directory, the order that one
 * virtual machine is built, copied to, started and photographed in. Those are lab notes. They rot
 * the day a guest is rebuilt, nobody outside this machine can run them, and a reader who finds one
 * in the tree reasonably concludes it is supported. The seven macOS {@code guest-*.sh} runners and
 * {@code scripts/a11y/linux/run-tree-reader.sh} left on 2026-09-16 for exactly that reason; their
 * copies live outside the repository.
 *
 * <p>The line is easy to cross again, because the way a lab fact enters is never a decision: it is a
 * path pasted into a working script that then gets committed with everything else. So this is a
 * grep, not a review. It refuses three shapes and only three, each of which is a fact about one
 * machine and can be nothing else:
 *
 * <ul>
 * <li><b>A private IPv4 literal</b> &mdash; {@code 10.x}, {@code 172.16-31.x}, {@code 192.168.x}.
 * The private ranges only: a public address in a script would be a URL, and the loopback and
 * link-local ones name no guest.</li>
 * <li><b>A lab login</b> &mdash; see {@link #LAB_LOGINS}.</li>
 * <li><b>An absolute {@code /Users/<name>} path</b> &mdash; a home directory on a macOS machine.
 * {@code /Users/} alone is not enough to refuse: the literal without a name after it is a sentence
 * about macOS, not a fact about a guest.</li>
 * </ul>
 *
 * <p>What this deliberately does <b>not</b> refuse is the guest's operating-system version, which is
 * the thing a constant's comment must cite: "read on the macOS 26.6.2 guest, 2026-09-13" is a
 * repository fact and the whole point of committing the dumps beside the readings.
 */
class ScriptsCarryNoLabFactsTest {

    /**
     * Account names used on the lab guests.
     *
     * <p>Spelled out rather than matched by shape, because a login is an ordinary word: a pattern
     * wide enough to catch one would refuse half the prose in these scripts. The cost of the
     * explicit list is that a new guest with a new login needs a line here; the cost of the clever
     * alternative is that it never goes green.
     */
    private static final List<String> LAB_LOGINS = List.of("activities");

    /** {@code 10.x}, {@code 172.16-31.x} and {@code 192.168.x}, and nothing else. */
    private static final Pattern PRIVATE_IPV4 = Pattern.compile(
            "\\b(?:10\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])|192\\.168)\\.\\d{1,3}\\.\\d{1,3}\\b");

    /** A macOS home directory: {@code /Users/} followed by an actual name. */
    private static final Pattern USERS_PATH = Pattern.compile("/Users/[A-Za-z0-9._-]+");

    @Test
    void noScriptNamesAMachineInTheLab() throws IOException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Path script : scripts()) {
            String name = RepositoryRoot.find().relativize(script).toString();
            String text = Files.readString(script, StandardCharsets.UTF_8);
            List<String> hits = new ArrayList<>();
            collect(PRIVATE_IPV4.matcher(text), hits);
            collect(USERS_PATH.matcher(text), hits);
            for (String login : LAB_LOGINS) {
                if (text.contains(login)) {
                    hits.add(login);
                }
            }
            if (!hits.isEmpty()) {
                found.put(name, hits);
            }
        }
        assertTrue(found.isEmpty(),
                "scripts/ names a machine in the verification lab: " + found
                        + " — a script that reads a platform belongs here and must carry no fact "
                        + "about one guest; a runner that brings a guest up belongs outside the "
                        + "repository (decision 17, ADR 039 §12.2)");
    }

    /**
     * The scripts themselves, so a failure names the file rather than the tree.
     *
     * @return every regular file under {@code scripts/}
     * @throws IOException if the tree cannot be read
     */
    private static List<Path> scripts() throws IOException {
        Path root = RepositoryRoot.find().resolve("scripts");
        assertTrue(Files.isDirectory(root), "scripts/ is gone");
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> files = tree.filter(Files::isRegularFile).sorted().toList();
            assertTrue(files.size() > 20, "found almost nothing under scripts/; is the root right?");
            return files;
        }
    }

    /**
     * Appends every match to the list.
     *
     * @param matcher the matcher to drain
     * @param hits where to put what it finds
     */
    private static void collect(Matcher matcher, List<String> hits) {
        while (matcher.find()) {
            hits.add(matcher.group());
        }
    }

}
