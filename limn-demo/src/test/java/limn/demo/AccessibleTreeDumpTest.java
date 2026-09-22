package limn.demo;

import limn.accessibility.AccessibleTree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.testing.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.testing.AccessibleTrees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes the tree every gallery entry publishes to a directory, one file per entry and palette,
 * so that a refactor of the widgets' accessibility hooks can be held to "the published tree did
 * not change" by a plain {@code diff} of two dumps, entry by entry, rather than by the four
 * goldens alone (which cover four scenes) or by memory.
 *
 * <p>Not a test of anything: it runs only when {@code -Dlimn.a11y.trees.dir=<directory>} names
 * where to write, which {@code limn-demo/build.gradle.kts} forwards from the command line the way
 * it forwards the transcript switch. Without the property it is skipped, and {@code check} never
 * writes a file. Each file carries two views of the same tree: {@link AccessibleTrees#describe},
 * one line per node with role, name, states, box and parent, and {@link Transcript#of}, the
 * reader-facing form the goldens use. Node ids are deliberately absent from both, because they are
 * minted per process and would make every diff noisy.
 *
 * <p>ADR 045 §0 records the dump this was first taken against (48051d19, 41 entries) and the
 * rule it serves: a phase of that record is done when its dump is byte-for-byte the floor's,
 * except where a numbered decision changed something and the record lists the exception.
 */
class AccessibleTreeDumpTest {

    @Test
    @EnabledIfSystemProperty(named = "limn.a11y.trees.dir", matches = ".+")
    void dumpEveryGalleryEntry() throws IOException {
        Path dir = Path.of(System.getProperty("limn.a11y.trees.dir"));
        Files.createDirectories(dir);
        List<Entry> entries = AccessibilityGallery.entries();
        int index = 0;
        for (Entry entry : entries) {
            index++;
            for (Palette palette : Palette.values()) {
                String file = String.format(Locale.ROOT, "%02d-%s.%s.txt", index,
                        slug(entry.name()), palette.name().toLowerCase(Locale.ROOT));
                Files.writeString(dir.resolve(file), dump(entry, palette),
                        StandardCharsets.UTF_8);
            }
        }
        Files.writeString(dir.resolve("COUNT"), entries.size() + " entries\n",
                StandardCharsets.UTF_8);
    }

    private static String dump(Entry entry, Palette palette) {
        StringBuilder out = new StringBuilder();
        try (Harness harness = new Harness(palette)) {
            List<HeadlessWindow> windows = harness.show(entry);
            for (HeadlessWindow window : windows) {
                AccessibleTree tree = window.bridge().tree();
                out.append("== window \"").append(window.title()).append("\" ==\n");
                out.append("-- nodes --").append(AccessibleTrees.describe(tree));
                out.append("-- transcript --\n").append(Transcript.of(tree));
                if (out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    /** {@code "Combo box, open"} becomes {@code "combo-box-open"}: a file name and nothing else. */
    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
