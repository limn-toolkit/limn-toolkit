package limn.demo.a11y;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The checked-in transcripts, and the one switch that rewrites them.
 *
 * <p>A golden is compared, never regenerated on its own: a test that rewrote its reference
 * answer whenever the answer moved would be a test of nothing. To take a new reading, run with
 * {@code -Dlimn.a11y.transcripts.update=true}, which writes every transcript into the resource
 * directory the build names and then compares against what it just wrote; the diff that was
 * about to fail is then in {@code git diff}, which is where a reviewer reads it aloud. Without the
 * switch a changed transcript fails, with the first differing line and the lines around it.
 */
public final class Goldens {

    /** The system property that turns a comparison into a rewrite. */
    public static final String UPDATE = "limn.a11y.transcripts.update";

    /** The system property the build sets to the resource directory the goldens live in. */
    public static final String DIRECTORY = "limn.a11y.transcripts.dir";

    private static final String RESOURCE_ROOT = "/limn/demo/a11y/";

    private Goldens() {
    }

    /**
     * Compares a transcript against its golden, or rewrites the golden when the switch is set.
     *
     * @param scene  the scene's name, which is the file's
     * @param actual the transcript the scene produced now
     */
    public static void check(String scene, String actual) {
        String resource = RESOURCE_ROOT + scene + ".txt";
        if (Boolean.getBoolean(UPDATE)) {
            String directory = System.getProperty(DIRECTORY);
            if (directory == null || directory.isEmpty()) {
                fail(UPDATE + " is set but " + DIRECTORY + " is not: the build names the "
                        + "directory the goldens are written to, and this JVM was not started by it");
            }
            Path file = Path.of(directory, scene + ".txt");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError("could not write " + file, e);
            }
            System.out.println("rewrote " + file + "; read it aloud before committing it");
            return;
        }
        String expected;
        try (InputStream in = Goldens.class.getResourceAsStream(resource)) {
            if (in == null) {
                fail("no golden transcript at " + resource + "; take the first reading with -D"
                        + UPDATE + "=true and review it before committing it");
                return;
            }
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
        if (expected.equals(actual)) {
            return;
        }
        fail(describeDifference(scene, expected, actual));
    }

    private static String describeDifference(String scene, String expected, String actual) {
        List<String> before = expected.lines().toList();
        List<String> after = actual.lines().toList();
        int first = 0;
        while (first < before.size() && first < after.size()
                && before.get(first).equals(after.get(first))) {
            first++;
        }
        StringBuilder out = new StringBuilder();
        out.append("the ").append(scene).append(" scene no longer sounds like its transcript (")
                .append(before.size()).append(" lines recorded, ").append(after.size())
                .append(" produced); first difference at file line ").append(first + 1)
                .append(", counting the window header:\n");
        int from = Math.max(0, first - 3);
        int to = Math.min(Math.max(before.size(), after.size()), first + 8);
        for (int line = from; line < to; line++) {
            String was = line < before.size() ? before.get(line) : null;
            String is = line < after.size() ? after.get(line) : null;
            if (was != null && was.equals(is)) {
                out.append("    ").append(was).append('\n');
                continue;
            }
            if (was != null) {
                out.append("  - ").append(was).append('\n');
            }
            if (is != null) {
                out.append("  + ").append(is).append('\n');
            }
        }
        out.append("if the new reading is right, rewrite the golden with -D").append(UPDATE)
                .append("=true and read the diff aloud before committing it");
        return out.toString();
    }
}
