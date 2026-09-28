package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every platform service that chooses by language is asked through
 * {@code limn.i18n.internal.LocaleServices}, which reads the locale's code as ICU reads it first,
 * or, for numbers, through {@code NumberLocale.inEffect()}, which does the same. Read from the
 * sources of this module and of the backend, so that a date format, a day name, a line breaker
 * or a collator built straight from a widget's locale fails here: under {@code fra} or {@code deu}
 * the platform, which knows each language by one code, answers the root locale's words, patterns
 * and order, and nothing on screen says why.
 *
 * <p>The accessibility bridges tell a reader which language a node is in, and a reader picks a
 * voice by that tag, so the bridges hand it over through the same class and never write
 * {@code toLanguageTag()} themselves.
 */
class LocaleServicesContractTest {

    private static final String FUNNEL = "limn/i18n/internal/LocaleServices.java";
    private static final String NUMBER_FUNNEL = "limn/i18n/internal/NumberLocale.java";

    /** Locales a machine format names outright, which no reader sees as language. */
    private static final Set<String> MACHINE = Set.of("Locale.ROOT", "Locale.US", "Locale.ENGLISH",
            "java.util.Locale.ROOT", "java.util.Locale.US", "java.util.Locale.ENGLISH");

    /** The locale expressions a number format may be handed: the number funnel's answer. */
    private static final Set<String> NUMBERS = Set.of("numbers", "NumberLocale.inEffect()");

    /** Services that choose by language and are asked only through the funnel. */
    private static final List<Pattern> FUNNEL_ONLY = List.of(
            Pattern.compile("\\bDateTimeFormatter\\s*\\.\\s*of(Pattern|Localized\\w*)\\s*\\("),
            Pattern.compile("\\.\\s*(withLocale|localizedBy)\\s*\\("),
            Pattern.compile("\\bgetLocalizedDateTimePattern\\s*\\("),
            Pattern.compile("\\.\\s*getDisplayName\\s*\\("),
            Pattern.compile("\\bBreakIterator\\s*\\.\\s*get(Line|Word|Sentence)Instance\\s*\\("),
            Pattern.compile("\\bChronology\\s*\\.\\s*ofLocale\\s*\\("),
            Pattern.compile("\\bCollator\\s*\\.\\s*getInstance\\s*\\("),
            Pattern.compile("\\bDecimalStyle\\s*\\.\\s*of\\s*\\("),
            Pattern.compile("\\bDateFormatSymbols\\b"),
            Pattern.compile("\\bDateFormat\\s*\\.\\s*get\\w*Instance\\s*\\("),
            Pattern.compile("\\bnew\\s+SimpleDateFormat\\s*\\("));

    /** One argument is a locale; two are a first day and a minimal count, which choose nothing. */
    private static final Pattern WEEK_FIELDS = Pattern.compile("\\bWeekFields\\s*\\.\\s*of\\s*\\(");

    /** The receiver, which a case mapping of one character or the toolkit's own excuses. */
    private static final Pattern CASE = Pattern.compile("(\\w+|\\))\\s*\\.\\s*to(Upper|Lower)Case\\s*\\(");
    private static final Set<String> CASE_RECEIVERS = Set.of("Character", "I18n", "LocaleServices");

    /**
     * Number formats, each with the argument that is its locale: 0 the first, -1 the last of two.
     * A {@code DecimalFormat} names no locale and takes one from the symbols it is handed, which
     * {@code DecimalFormatSymbols.getInstance} made and this list checks, so only one built without
     * symbols, in the default locale, is refused.
     */
    private static final List<Object[]> NUMBER_CALLS = List.of(
            new Object[] {Pattern.compile("\\bString\\s*\\.\\s*format\\s*\\("), 0},
            new Object[] {Pattern.compile("\\bDecimalFormatSymbols\\s*\\.\\s*getInstance\\s*\\("), 0},
            new Object[] {Pattern.compile("\\bNumberFormat\\s*\\.\\s*get\\w*Instance\\s*\\("), 0},
            new Object[] {Pattern.compile("\\bnew\\s+MessageFormat\\s*\\("), -1},
            new Object[] {Pattern.compile("\\bnew\\s+DecimalFormat\\s*\\("), -2},
            new Object[] {Pattern.compile("\\.\\s*getSymbol\\s*\\("), 0});

    private static final Pattern LANGUAGE_TAG = Pattern.compile("\\.\\s*toLanguageTag\\s*\\(");

    @Test
    void everyServiceThatChoosesByLanguageIsAskedThroughTheFunnel() throws IOException {
        Path root = RepositoryRoot.find();
        List<Path> files = new ArrayList<>();
        for (String module : List.of("limn-toolkit", "limn-backend-lwjgl")) {
            try (Stream<Path> walk = Files.walk(root.resolve(module).resolve("src/main/java"))) {
                walk.filter(f -> f.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertTrue(files.size() > 300, "only " + files.size() + " sources were found");

        List<String> wrong = new ArrayList<>();
        for (Path file : files) {
            String name = root.relativize(file).toString().replace('\\', '/');
            boolean funnel = name.endsWith(FUNNEL);
            boolean numberFunnel = name.endsWith(NUMBER_FUNNEL);
            boolean bridge = name.contains("/limn/backend/lwjgl/a11y/");
            String code = blankCommentsAndLiterals(Files.readString(file));

            if (!funnel) {
                for (Pattern call : FUNNEL_ONLY) {
                    Matcher m = call.matcher(code);
                    while (m.find()) {
                        wrong.add(where(name, code, m.start()) + " asks the platform directly");
                    }
                }
                Matcher weeks = WEEK_FIELDS.matcher(code);
                while (weeks.find()) {
                    if (arguments(code, weeks.end() - 1).size() == 1) {
                        wrong.add(where(name, code, weeks.start()) + " reads a week from a locale");
                    }
                }
                Matcher cases = CASE.matcher(code);
                while (cases.find()) {
                    List<String> args = arguments(code, cases.end() - 1);
                    if (!CASE_RECEIVERS.contains(cases.group(1)) && args.size() == 1
                            && !MACHINE.contains(args.get(0))) {
                        wrong.add(where(name, code, cases.start()) + " case-maps in a locale");
                    }
                }
            }
            if (!numberFunnel) {
                for (Object[] number : NUMBER_CALLS) {
                    Matcher m = ((Pattern) number[0]).matcher(code);
                    while (m.find()) {
                        List<String> args = arguments(code, m.end() - 1);
                        int index = (int) number[1];
                        if (index == 0 && !args.isEmpty() && args.get(0).startsWith("\"")) {
                            continue; // a format string first: no locale is named
                        }
                        if (index == -2) {
                            if (args.size() < 2) {
                                wrong.add(where(name, code, m.start())
                                        + " formats a number in the default locale");
                            }
                            continue;
                        }
                        String locale = args.isEmpty() || (index == -1 && args.size() < 2) ? null
                                : args.get(index < 0 ? args.size() - 1 : index);
                        if (locale == null || !(NUMBERS.contains(locale) || MACHINE.contains(locale))) {
                            wrong.add(where(name, code, m.start())
                                    + " formats a number in " + locale);
                        }
                    }
                }
            }
            if (bridge) {
                Matcher m = LANGUAGE_TAG.matcher(code);
                while (m.find()) {
                    wrong.add(where(name, code, m.start()) + " hands a reader a tag as written");
                }
            }
        }
        assertEquals(List.of(), wrong, "ask these through limn.i18n.internal.LocaleServices, or a "
                + "number through NumberLocale.inEffect(), so the language is read as its code "
                + "stands for");
    }

    /** {@code file:line} and the call. */
    private static String where(String file, String code, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        int end = code.indexOf('\n', at);
        return file.substring(file.indexOf("/src/main/java/") + "/src/main/java/".length()) + ':'
                + line + " " + code.substring(at, end < 0 ? code.length() : end).strip();
    }

    /** The top-level arguments of the call whose opening parenthesis is at {@code open}, trimmed. */
    private static List<String> arguments(String code, int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    String last = code.substring(start, i).strip();
                    if (!last.isEmpty() || !args.isEmpty()) {
                        args.add(last);
                    }
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(code.substring(start, i).strip());
                start = i + 1;
            }
        }
        return args;
    }

    /**
     * The source with every comment and the inside of every string and character literal
     * replaced by spaces, line breaks kept, so a javadoc that names a call is not a call and a
     * line number still points at the source.
     */
    static String blankCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(source, i, end, out);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append("\"\"\"");
                blank(source, i + 3, end - 3, out);
                out.append("\"\"\"");
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n - 1);
                out.append(c);
                blank(source, i + 1, j, out);
                out.append(c);
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }
}
