package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every public enum of an exported package says, in its own Javadoc, that a 1.x minor release may
 * add constants to it, because the compatibility promise lets every one of them grow: an
 * application that switches over one without a {@code default} branch stops compiling when a
 * constant arrives, and the place a reader looks before writing that switch is the enum itself.
 * Read from the sources, as {@link PublicApiDocumentedTest} reads them: an enum is public when it
 * is declared {@code public}, or is a member of an interface, inside types that are public too.
 */
class PublicEnumsMayGrowTest {

    private static final List<String> MODULES =
            List.of("limn-toolkit", "limn-backend-lwjgl", "limn-video-ffmpeg", "limn-test");

    private static final String SENTENCE = "A 1.x minor release may add constants to this enum";

    private static final Pattern EXPORTS = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern TYPE = Pattern.compile(
            "^(\\s*)((?:(?:public|private|protected|static|sealed|non-sealed|final|abstract|strictfp)\\s+)*)"
                    + "(class|interface|enum|record|@interface)\\s+(\\w+)");

    private record Enclosing(int indent, String kind, boolean reachable) {}

    @Test
    void everyPublicEnumSaysItMayGainConstantsInAMinor() throws IOException {
        List<String> found = new ArrayList<>();
        int enums = 0;
        Path root = RepositoryRoot.find();
        for (String module : MODULES) {
            Path sources = root.resolve(module).resolve("src/main/java");
            Matcher exports = EXPORTS.matcher(Files.readString(sources.resolve("module-info.java")));
            while (exports.find()) {
                Path pkg = sources.resolve(exports.group(1).replace('.', '/'));
                try (Stream<Path> files = Files.list(pkg)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")
                            && !f.getFileName().toString().equals("package-info.java")).sorted().toList()) {
                        enums += scan(root.relativize(file).toString(), Files.readAllLines(file), found);
                    }
                }
            }
        }
        // The published modules hold about a hundred public enums; a scan that finds a handful has
        // stopped reading declarations, not found the API clean.
        assertTrue(enums > 90, "only " + enums + " public enums found; the scan no longer reads them");
        assertEquals(List.of(), found, found.size() + " public enum(s) whose Javadoc does not say it may grow");
    }

    private static int scan(String name, List<String> lines, List<String> found) {
        Deque<Enclosing> stack = new ArrayDeque<>();
        int enums = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            Matcher type = TYPE.matcher(line);
            if (!type.find()) {
                continue;
            }
            int indent = type.group(1).length();
            List<String> modifiers = List.of(type.group(2).strip().split("\\s+"));
            String kind = type.group(3);
            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                stack.pop();
            }
            boolean reachable;
            if (stack.isEmpty()) {
                reachable = modifiers.contains("public");
            } else {
                boolean interfaceMember = stack.peek().kind().endsWith("interface") && !modifiers.contains("private");
                reachable = stack.peek().reachable() && (modifiers.contains("public") || interfaceMember);
            }
            stack.push(new Enclosing(indent, kind, reachable));
            if (!kind.equals("enum") || !reachable) {
                continue;
            }
            enums++;
            if (!javadocAbove(lines, i).contains(SENTENCE)) {
                found.add(name + ":" + (i + 1) + ": " + trimmed);
            }
        }
        return enums;
    }

    /** The Javadoc right above a declaration, past its annotations, as one line; empty when none. */
    private static String javadocAbove(List<String> lines, int declaration) {
        int end = declaration - 1;
        while (end >= 0 && lines.get(end).strip().startsWith("@")) {
            end--;
        }
        if (end < 0 || !lines.get(end).strip().endsWith("*/")) {
            return "";
        }
        int start = end;
        while (start > 0 && !lines.get(start).strip().startsWith("/**")) {
            start--;
        }
        StringBuilder text = new StringBuilder();
        for (int k = start; k <= end; k++) {
            String part = lines.get(k).strip().replaceFirst("^/\\*\\*", "").replaceFirst("\\*/$", "")
                    .replaceFirst("^\\*", "").strip();
            text.append(part).append(' ');
        }
        return text.toString().replaceAll("\\s+", " ");
    }
}
