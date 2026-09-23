package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every public mutator of the widgets, the layouts and the scene checks that it runs on the UI
 * thread (API-11): {@code Ui} says every mutation checks, and a mutation that does not is a race
 * that nothing reports. Read from the sources, so a setter added without the check fails here
 * rather than in a user's crash report.
 *
 * <p>A method passes when its body calls {@code checkUiThread}, or when its first statement
 * delegates to a method of the same class that passes. The exceptions are {@link #ALLOWED}, each
 * with its reason.
 */
class UiThreadContractTest {

    /** Verbs that change state. A getter, a predicate and a static factory are not in scope. */
    private static final Pattern MUTATOR = Pattern.compile(
            "(set|add|remove|clear|select|deselect|open|close|refresh|activate|toggle|expand|collapse"
                    + "|scroll|replace|insert|push|pop|show|hide|apply|reset|replay|announce|request"
                    + "|dismiss|restart|seek|play|pause|stop)([A-Z]\\w*)?");

    /** Mutators only when they take an argument: a handler slot, and the layouts' builder names. */
    private static final Pattern WITH_ARGUMENT = Pattern.compile(
            "on[A-Z]\\w*|gap|mainAlignment|crossAlignment");

    /** Class#method, and why it need not check. */
    private static final Map<String, String> ALLOWED = Map.of(
            "TextArea#scrollXOffset", "a getter whose name starts with a verb",
            "TextArea#scrollYOffset", "a getter whose name starts with a verb");

    private static final Pattern METHOD = Pattern.compile(
            "\\n    public (?!static )(?!abstract )(?:final )?(?:<[^>]+>\\s+)?[\\w<>?,.\\[\\] ]+\\s+(\\w+)\\(");

    @Test
    void everyPublicMutatorChecksTheUiThread() throws IOException {
        Path sources = RepositoryRoot.find().resolve("limn-toolkit/src/main/java/limn");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> components = Files.walk(sources.resolve("components"))) {
            components.filter(f -> f.toString().endsWith(".java") && !f.toString().contains("internal"))
                    .forEach(files::add);
        }
        try (Stream<Path> layout = Files.list(sources.resolve("scene/layout"))) {
            layout.filter(f -> f.toString().endsWith(".java")).forEach(files::add);
        }
        files.add(sources.resolve("scene/Scene.java"));
        files.add(sources.resolve("scene/Widget.java"));

        List<String> missing = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file);
            if (!source.contains("public final class") && !source.contains("public class")
                    && !source.contains("public abstract class")) {
                continue;
            }
            String type = file.getFileName().toString().replace(".java", "");
            Map<String, List<String>> bodies = new HashMap<>();
            Set<String> takesArgument = new HashSet<>();
            Matcher m = METHOD.matcher(source);
            while (m.find()) {
                if (source.charAt(m.end()) != ')') {
                    takesArgument.add(m.group(1));
                }
                int open = source.indexOf('{', m.end());
                int semicolon = source.indexOf(';', m.end());
                if (open < 0 || (semicolon >= 0 && semicolon < open)) {
                    continue; // an interface method or an abstract one
                }
                bodies.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(body(source, open));
            }
            Set<String> checked = new HashSet<>();
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Map.Entry<String, List<String>> e : bodies.entrySet()) {
                    if (checked.contains(e.getKey())) {
                        continue;
                    }
                    boolean anyDirect = e.getValue().stream().anyMatch(b -> b.contains("checkUiThread"));
                    boolean all = true;
                    for (String body : e.getValue()) {
                        all &= body.contains("checkUiThread") || delegatesToChecked(body, checked)
                                || (anyDirect && delegatesTo(body, e.getKey()));
                    }
                    if (all) {
                        checked.add(e.getKey());
                        grew = true;
                    }
                }
            }
            for (String name : bodies.keySet()) {
                boolean mutator = MUTATOR.matcher(name).matches()
                        || (WITH_ARGUMENT.matcher(name).matches() && takesArgument.contains(name));
                if (mutator && !checked.contains(name)
                        && !ALLOWED.containsKey(type + "#" + name)) {
                    missing.add(type + "#" + name);
                }
            }
        }
        missing.sort(null);
        assertTrue(missing.isEmpty(), missing.size() + " public mutators do not check the UI thread: "
                + missing);
    }

    private static boolean delegatesToChecked(String body, Set<String> checked) {
        Matcher first = Pattern.compile("^\\s*(?:return\\s+)?(?:this\\.|super\\.)?(\\w+)\\(").matcher(body);
        return first.find() && checked.contains(first.group(1));
    }

    /** Whether the first statement calls {@code name}: an overload handing on to its sibling. */
    private static boolean delegatesTo(String body, String name) {
        Matcher first = Pattern.compile("^\\s*(?:return\\s+)?(?:this\\.)?(\\w+)\\(").matcher(body);
        return first.find() && first.group(1).equals(name);
    }

    private static String body(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        return "";
    }
}
