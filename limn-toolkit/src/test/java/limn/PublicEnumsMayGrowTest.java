package limn;

import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p>Read from the sources, as {@link PublicApiDocumentedTest} reads them, but through tokens and
 * not lines: a declaration can carry its annotations and its doc comment on its own line, and a
 * plain comment above it is not its Javadoc. An enum is public when it is declared {@code public},
 * or is a member of an interface, inside types that are public too; one inside a method or an
 * initializer is local and never is. {@link #theScanSeesEveryShapeADeclarationCanTake} plants the
 * shapes a line-based reading missed.
 */
class PublicEnumsMayGrowTest {

    private static final List<String> MODULES =
            List.of("limn-toolkit", "limn-backend-lwjgl", "limn-video-ffmpeg", "limn-test");

    private static final String SENTENCE = "A 1.x minor release may add constants to this enum";

    private static final Pattern EXPORTS = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

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
                        enums += scan(root.relativize(file).toString(), Files.readString(file), found);
                    }
                }
            }
        }
        // The published modules hold about a hundred public enums; a scan that finds a handful has
        // stopped reading declarations, not found the API clean.
        assertTrue(enums > 90, "only " + enums + " public enums found; the scan no longer reads them");
        assertEquals(List.of(), found, found.size() + " public enum(s) whose Javadoc does not say it may grow");
    }

    /**
     * The guard is only as good as its reading of a declaration: an annotation on the enum's own
     * line, a doc comment on the same line, a plain comment where the Javadoc should be (which a
     * reading by lines took for the Javadoc of the member above), braces in a string or an
     * annotation's argument, a local enum and an interface's implicitly public member.
     */
    @Test
    void theScanSeesEveryShapeADeclarationCanTake() {
        String source = """
                package p;

                /** Doc. */
                public class Holder {
                    /** Doc. A 1.x minor release may add constants to this enum: one. */
                    public enum First { ONE }
                    /* A plain comment. */ public enum PlainComment { ONE }
                    // A line comment.
                    public enum LineComment { ONE }
                    /** Doc. */
                    @Deprecated public enum AnnotatedOnItsLine { ONE }
                    /** Doc. */ public enum DocOnItsLine { ONE }
                    /** Doc. */
                    @SuppressWarnings({"unused", "{"})
                    public enum BracesInAnAnnotation { ONE }
                    private final String brace = "}{";
                    private final char other = '}';
                    private final String block = \"""
                            }
                            \""";
                    /** Doc. A 1.x minor release may add constants to this enum. */ @Deprecated public enum Fine { A }
                    private enum Hidden { ONE }
                    interface PackagePrivate { enum Member { ONE } }
                    public interface Open { /** Doc. */ enum ImplicitlyPublic { ONE } }
                    public record Row(int a) { public enum InRecord { ONE } }
                    public void method() { enum Local { ONE } Runnable r = () -> { }; }
                    public static final Object ANONYMOUS = new Object() { public String toString() { return "{"; } };
                    /** Doc. */
                    public enum WithBodies { A { void f() { } }, B; void f() { } }
                    public enum AfterBodies { ONE }
                }
                """;
        List<String> found = new ArrayList<>();
        int enums = scan("Holder.java", source, found);

        assertEquals(List.of(
                "Holder.java:7: enum PlainComment",
                "Holder.java:9: enum LineComment",
                "Holder.java:11: enum AnnotatedOnItsLine",
                "Holder.java:12: enum DocOnItsLine",
                "Holder.java:15: enum BracesInAnAnnotation",
                "Holder.java:24: enum ImplicitlyPublic",
                "Holder.java:25: enum InRecord",
                "Holder.java:29: enum WithBodies",
                "Holder.java:30: enum AfterBodies"), found);
        assertEquals(11, enums, "First and Fine pass; Hidden, Member and Local are not public");
    }

    /** A token: a word, a doc comment's text, or one character of punctuation. */
    private record Token(char kind, String text, int line) {
        static final char WORD = 'w';
        static final char DOC = 'd';
        static final char SYMBOL = 's';

        boolean is(String symbol) {
            return kind == SYMBOL && text.equals(symbol);
        }
    }

    /** A brace the scan is inside: a type's body, or any other block. */
    private record Frame(String kind, boolean reachable) {
        boolean isInterface() {
            return kind.equals("interface") || kind.equals("@interface");
        }
    }

    /** Counts the public enums of one source, adding to {@code found} each whose Javadoc lacks the sentence. */
    private static int scan(String name, String source, List<String> found) {
        List<Token> tokens = tokenize(source);
        Deque<Frame> stack = new ArrayDeque<>();
        Set<String> modifiers = new HashSet<>();
        String doc = null;
        Frame opening = null;
        int enums = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == Token.DOC) {
                doc = t.text();
            } else if (t.is("(")) {
                i = closing(tokens, i, "(", ")");
            } else if (t.is("@") && i + 1 < tokens.size() && !tokens.get(i + 1).text().equals("interface")) {
                // An annotation: its name, dotted, and its arguments, which may hold braces.
                i++;
                while (i + 2 < tokens.size() && tokens.get(i + 1).is(".")) {
                    i += 2;
                }
                if (i + 1 < tokens.size() && tokens.get(i + 1).is("(")) {
                    i = closing(tokens, i + 1, "(", ")");
                }
            } else if (t.is("{")) {
                stack.push(opening != null ? opening : new Frame("block", false));
                opening = null;
                modifiers.clear();
                doc = null;
            } else if (t.is("}") || t.is(";")) {
                if (t.is("}") && !stack.isEmpty()) {
                    stack.pop();
                }
                opening = null;
                modifiers.clear();
                doc = null;
            } else if (t.kind() == Token.WORD || t.is("@")) {
                String kind = typeKeyword(tokens, i);
                if (kind == null) {
                    modifiers.add(t.text());
                    continue;
                }
                if (kind.equals("@interface")) {
                    i++;
                }
                Frame outer = stack.peek();
                boolean reachable;
                if (outer == null) {
                    reachable = modifiers.contains("public");
                } else if (outer.kind().equals("block")) {
                    reachable = false;
                } else {
                    reachable = outer.reachable() && (modifiers.contains("public")
                            || (outer.isInterface() && !modifiers.contains("private")));
                }
                opening = new Frame(kind, reachable);
                Token typeName = tokens.get(i + 1);
                if (kind.equals("enum") && reachable) {
                    enums++;
                    if (doc == null || !doc.contains(SENTENCE)) {
                        found.add(name + ":" + t.line() + ": enum " + typeName.text());
                    }
                }
                i++;
            }
        }
        return enums;
    }

    /** The kind of type the token at {@code i} declares, or null when it declares none. */
    private static String typeKeyword(List<Token> tokens, int i) {
        Token t = tokens.get(i);
        boolean afterDot = i > 0 && tokens.get(i - 1).is(".");
        if (afterDot || i + 1 >= tokens.size()) {
            return null; // Foo.class
        }
        Token next = tokens.get(i + 1);
        if (t.is("@")) {
            return next.text().equals("interface") ? "@interface" : null;
        }
        switch (t.text()) {
            case "class", "interface", "enum" -> {
                return next.kind() == Token.WORD ? t.text() : null;
            }
            case "record" -> {
                // A contextual keyword: a variable may be called record.
                boolean declares = next.kind() == Token.WORD && i + 2 < tokens.size()
                        && (tokens.get(i + 2).is("(") || tokens.get(i + 2).is("<"));
                return declares ? "record" : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The index of the token that closes the one at {@code open}. */
    private static int closing(List<Token> tokens, int open, String opener, String closer) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is(opener)) {
                depth++;
            } else if (tokens.get(i).is(closer) && --depth == 0) {
                return i;
            }
        }
        return tokens.size() - 1;
    }

    /**
     * Words, doc comments and punctuation; whitespace, plain comments and the contents of string,
     * text-block and character literals are dropped, so a brace inside one counts for nothing.
     */
    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int start = i;
            if (c == '/' && s.startsWith("//", i)) {
                i = s.indexOf('\n', i);
                i = i < 0 ? s.length() : i;
            } else if (c == '/' && s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                String comment = s.substring(i, end);
                if (comment.startsWith("/**") && !comment.equals("/**/")) {
                    tokens.add(new Token(Token.DOC, docText(comment), line));
                }
                i = end;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                while (end > 0 && s.charAt(end - 1) == '\\') {
                    end = s.indexOf("\"\"\"", end + 1);
                }
                i = end < 0 ? s.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != c) {
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Token.WORD, s.substring(start, i), line));
            } else {
                if (!Character.isWhitespace(c)) {
                    tokens.add(new Token(Token.SYMBOL, String.valueOf(c), line));
                }
                i++;
            }
            for (int k = start; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                }
            }
        }
        return tokens;
    }

    /** A doc comment's text on one line: no delimiters, no leading asterisks, single spaces. */
    private static String docText(String comment) {
        String body = comment.substring(3, comment.length() - 2);
        StringBuilder text = new StringBuilder();
        for (String part : body.split("\n")) {
            text.append(part.strip().replaceFirst("^\\*", "").strip()).append(' ');
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }
}
