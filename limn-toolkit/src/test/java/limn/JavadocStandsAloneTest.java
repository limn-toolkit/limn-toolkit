package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The published Javadoc stands on its own, as {@code docs/design/README.md} requires: it names no
 * decision record, no numbered decision and no tracking id, because a reader with the artifact and
 * no repository cannot follow any of them. A comment, which only a contributor with the repository
 * reads, may cite a record or a decision, but not a tracking id, which resolves nowhere that is
 * versioned.
 */
class JavadocStandsAloneTest {

    private static final Pattern RECORD = Pattern.compile("\\bADRs? \\d{3}|\\b[Dd]ecisions? \\d+");
    private static final Pattern TRACKING_ID = Pattern.compile(
            "\\b(?:[A-Z]+-)*(?:NEW|CRIT|MISS|PF|API|DOC|P5[WMLU])-\\d+\\b");

    @Test
    void theJavadocCitesNothingItDoesNotShipAndNoCommentCitesATrackingId() throws IOException {
        List<String> found = new ArrayList<>();
        Path root = RepositoryRoot.find();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(m -> m.getFileName().toString().startsWith("limn-")).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        scan(root.relativize(file).toString(), Files.readAllLines(file), found);
                    }
                }
            }
        }
        assertEquals(List.of(), found, found.size() + " citation(s) the published Javadoc or a comment must not carry");
    }

    private static void scan(String name, List<String> lines, List<String> found) {
        boolean inJavadoc = false;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            boolean javadocLine = inJavadoc || trimmed.startsWith("/**");
            boolean commentLine = inBlockComment || trimmed.startsWith("/*") || line.contains("//");
            if (javadocLine && RECORD.matcher(line).find()) {
                found.add(name + ":" + (i + 1) + ": " + trimmed);
            } else if ((javadocLine || commentLine) && TRACKING_ID.matcher(line).find()) {
                found.add(name + ":" + (i + 1) + ": " + trimmed);
            }
            if (!inJavadoc && !inBlockComment) {
                if (trimmed.startsWith("/**")) {
                    inJavadoc = !trimmed.substring(3).contains("*/");
                } else if (trimmed.startsWith("/*")) {
                    inBlockComment = !trimmed.substring(2).contains("*/");
                }
            } else if (trimmed.contains("*/")) {
                inJavadoc = false;
                inBlockComment = false;
            }
        }
    }
}
