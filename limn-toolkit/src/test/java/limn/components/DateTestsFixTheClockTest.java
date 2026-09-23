package limn.components;

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
 * Every date widget a test builds is given a fixed clock, so that the test passes or fails by its
 * code and not by the day it runs on. A date field, a date picker and a calendar read today from a
 * clock, the system's unless told otherwise, and today decides where a calendar's cursor starts,
 * which century a two-digit year falls in, what an empty segment becomes on its first step and
 * which cell is marked and announced as today. A test that left the system clock in place passed
 * on most days of the month and failed on the others: a calendar test that pressed Right and Down
 * from today crossed into the next month from the 23rd on, and was only found because somebody ran
 * it on the 23rd.
 *
 * <p>This reads the test sources of the toolkit and of the demo and names, by file and line,
 * every construction of one of the three widgets (a {@code new}, a static factory such as
 * {@code ofTime} or {@code ofRange}, or a constructor reference) that is not given a clock. A
 * construction is given one when {@code setClock} is called in the chain that starts at it, or
 * when its statement assigns a variable, directly or through a helper that hands the widget
 * back, and that statement or one of the next few lines calls {@code setClock} on the variable.
 * A test that wants the real clock says so on the construction's line, with a trailing
 * {@code // system clock on purpose: } and the reason.
 *
 * <p>The reading is textual: comments and the contents of string literals are blanked before the
 * constructions are looked for, and a construction the rules cannot follow, such as one passed
 * straight into a helper that sets the clock itself, is reported rather than trusted.
 */
class DateTestsFixTheClockTest {

    /** The modules whose tests are read. */
    private static final List<String> MODULES = List.of("limn-toolkit", "limn-backend-lwjgl", "limn-demo");

    private static final String WIDGET = "(?:limn\\.components\\.date\\.)?(?:DateField|DatePicker|CalendarView)";

    /** A {@code new}, a static call on one of the classes (each one today is a factory), a reference. */
    private static final Pattern CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+" + WIDGET + "\\s*\\("
                    + "|\\b" + WIDGET + "\\s*\\.\\s*[a-z]\\w*\\s*\\("
                    + "|\\b" + WIDGET + "\\s*::\\s*new\\b");

    /**
     * The start of a statement that assigns a variable, {@code DateField field =} or
     * {@code calendar =} and not {@code ==}, on the line a construction is on and before it.
     */
    private static final Pattern ASSIGNED = Pattern.compile(
            "^\\s*(?:final\\s+)?(?:[\\w.]+(?:<[^=]*>)?(?:\\[\\])*\\s+)?(?:this\\s*\\.\\s*)?(\\w+)\\s*=(?!=)");

    private static final Pattern ON_PURPOSE = Pattern.compile("//\\s*system clock on purpose:\\s*\\S");

    /** How many lines after a construction's statement may still give its variable a clock. */
    private static final int NEXT_LINES = 3;

    @Test
    void everyDateWidgetATestBuildsIsGivenAFixedClock() throws IOException {
        List<String> found = new ArrayList<>();
        Path root = RepositoryRoot.find();
        for (String module : MODULES) {
            Path sources = root.resolve(module).resolve("src/test/java");
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    scan(root.relativize(file).toString(), Files.readString(file), found);
                }
            }
        }
        assertEquals(List.of(), found, found.size()
                + " date widget(s) built on the system clock; give each setClock(a fixed clock), or say"
                + " why not with a trailing // system clock on purpose: <reason>");
    }

    private static void scan(String name, String source, List<String> found) {
        String code = blankCommentsAndStrings(source);
        String[] rawLines = source.split("\n", -1);
        String[] codeLines = code.split("\n", -1);
        int[] lineStarts = lineStarts(code);
        Matcher construction = CONSTRUCTION.matcher(code);
        while (construction.find()) {
            int line = lineOf(lineStarts, construction.start());
            if (ON_PURPOSE.matcher(rawLines[line]).find()) {
                continue;
            }
            boolean reference = construction.group().endsWith("new");
            int chainEnd = reference ? construction.end() : chainSetsClock(code, construction.end() - 1);
            if (chainEnd < 0) {
                continue; // the chain calls setClock
            }
            String before = code.substring(lineStarts[line], construction.start());
            Matcher assigned = ASSIGNED.matcher(before);
            if (assigned.find() && variableGetsClock(codeLines, lineOf(lineStarts, chainEnd), assigned.group(1))) {
                continue;
            }
            found.add(name + ":" + (line + 1) + ": " + rawLines[line].strip());
        }
    }

    /**
     * Follows the call chain that starts with the parenthesis at {@code open}. Returns {@code -1}
     * if a link of it is {@code setClock}, and otherwise the offset where the chain ends.
     */
    private static int chainSetsClock(String code, int open) {
        int at = closing(code, open);
        while (at >= 0) {
            int next = skipSpace(code, at + 1);
            if (next >= code.length() || code.charAt(next) != '.') {
                return at;
            }
            int start = skipSpace(code, next + 1);
            int end = start;
            while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
                end++;
            }
            if (code.substring(start, end).equals("setClock")) {
                return -1;
            }
            int paren = skipSpace(code, end);
            if (paren >= code.length() || code.charAt(paren) != '(') {
                return end; // a field read ends what this can follow
            }
            at = closing(code, paren);
        }
        return code.length();
    }

    private static boolean variableGetsClock(String[] codeLines, int fromLine, String variable) {
        Pattern call = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*\\.\\s*setClock\\s*\\(");
        for (int i = fromLine; i < Math.min(codeLines.length, fromLine + NEXT_LINES + 1); i++) {
            if (call.matcher(codeLines[i]).find()) {
                return true;
            }
        }
        return false;
    }

    /** The offset of the parenthesis that closes the one at {@code open}, or {@code -1}. */
    private static int closing(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipSpace(String code, int from) {
        int i = from;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int[] lineStarts(String code) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (lineStarts[middle] <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * The source with every comment and the inside of every string, text block and character
     * literal replaced by spaces, line breaks kept, so offsets and line numbers still match.
     */
    private static String blankCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    out.setCharAt(i++, ' ');
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                i = blank(source, out, i, end);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end;
                i = blank(source, out, i + 3, end) + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = blank(source, out, i + 1, Math.min(j, n)) + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static int blank(String source, StringBuilder out, int from, int to) {
        for (int i = from; i < to; i++) {
            if (source.charAt(i) != '\n') {
                out.setCharAt(i, ' ');
            }
        }
        return to;
    }
}
