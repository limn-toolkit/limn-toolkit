package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every package a module exports has a {@code package-info.java}, and every public method and
 * constructor of its top-level types carries Javadoc. Read from the sources rather than asked of
 * {@code doclint}'s {@code missing} group, which would also demand an {@code @param} for every
 * record component, the {@code @param x the x} this repository's documentation rules forbid. An
 * {@code @Override} inherits its documentation and is not counted.
 */
class PublicApiDocumentedTest {

    private static final List<String> MODULES =
            List.of("limn-toolkit", "limn-backend-lwjgl", "limn-video-ffmpeg", "limn-test");

    private static final Pattern EXPORTS = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** A public member of a top-level type, which sits four spaces in. */
    private static final Pattern MEMBER = Pattern.compile(
            "^    public (?!(?:static |final |sealed |non-sealed |abstract )*(?:class|interface|enum|record) )"
                    + "[^=;]*\\(");

    @Test
    void everyExportedPackageAndEveryPublicMemberInItIsDocumented() throws IOException {
        List<String> found = new ArrayList<>();
        Path root = RepositoryRoot.find();
        for (String module : MODULES) {
            Path sources = root.resolve(module).resolve("src/main/java");
            Matcher exports = EXPORTS.matcher(Files.readString(sources.resolve("module-info.java")));
            while (exports.find()) {
                Path pkg = sources.resolve(exports.group(1).replace('.', '/'));
                if (!Files.exists(pkg.resolve("package-info.java"))) {
                    found.add(module + ": package " + exports.group(1) + " has no package-info.java");
                }
                try (Stream<Path> files = Files.list(pkg)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")
                            && !f.getFileName().toString().equals("package-info.java")).toList()) {
                        scan(root.relativize(file).toString(), Files.readAllLines(file), found);
                    }
                }
            }
        }
        assertEquals(List.of(), found, found.size() + " undocumented part(s) of the public API");
    }

    private static void scan(String name, List<String> lines, List<String> found) {
        for (int i = 0; i < lines.size(); i++) {
            if (!MEMBER.matcher(lines.get(i)).find()) {
                continue;
            }
            int above = i - 1;
            boolean overrides = false;
            while (above >= 0 && lines.get(above).strip().startsWith("@")) {
                overrides |= lines.get(above).strip().startsWith("@Override");
                above--;
            }
            if (!overrides && (above < 0 || !lines.get(above).strip().endsWith("*/"))) {
                found.add(name + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
    }
}
