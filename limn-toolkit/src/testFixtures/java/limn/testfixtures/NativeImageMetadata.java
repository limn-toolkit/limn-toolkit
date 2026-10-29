package limn.testfixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The resource patterns a module ships for GraalVM's native image, held against the resources the
 * module ships. A resource no pattern names is left out of every image built from the module, and
 * nothing says so until the page that reads it fails on somebody's machine: that is how a shader
 * the hardware video path reads was missing while every virtual machine played the clip.
 */
public final class NativeImageMetadata {

    private static final Pattern GLOB = Pattern.compile("\"glob\"\\s*:\\s*\"([^\"]+)\"");

    private NativeImageMetadata() {
    }

    /** The module's {@code reachability-metadata.json}, under its own resources. */
    public static Path file(Path module, String artifact) {
        return module.resolve("src/main/resources/META-INF/native-image/io.github.limn-toolkit/" + artifact
                + "/reachability-metadata.json");
    }

    /** @return every {@code "glob"} in {@code metadata}, in the order written */
    public static List<String> globs(Path metadata) {
        try {
            Matcher matcher = GLOB.matcher(Files.readString(metadata));
            List<String> globs = new ArrayList<>();
            while (matcher.find()) {
                globs.add(matcher.group(1));
            }
            return globs;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Whether {@code path} matches {@code glob} as native image reads one: {@code *} within one
     * directory level, {@code **} across any number of them.
     */
    public static boolean matches(String glob, String path) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return path.matches(regex.toString());
    }

    /**
     * Every file under the module's {@code src/main/resources} (its own {@code META-INF} aside) that
     * no pattern of its metadata covers, except {@code notNeeded}: files that ship for a reader of
     * the jar, such as a licence, and that nothing reads at run time.
     *
     * @return the uncovered resource names, sorted; empty when the metadata covers them all
     */
    public static Set<String> uncoveredResources(Path module, String artifact, Set<String> notNeeded) {
        List<String> globs = globs(file(module, artifact));
        Path root = module.resolve("src/main/resources");
        TreeSet<String> uncovered = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(name -> !name.startsWith("META-INF/"))
                    .filter(name -> !notNeeded.contains(name))
                    .filter(name -> globs.stream().noneMatch(glob -> matches(glob, name)))
                    .forEach(uncovered::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return uncovered;
    }
}
