package limn.backend.lwjgl.a11y;

import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Limn name {@code docs/design/accessibility.md} puts in backticks still exists in the source.
 *
 * <p>{@code docs/design/README.md}'s own rule is that nothing in that directory may state a fact the
 * code can change without anyone noticing, and that a claim which must stay true is asserted in a
 * test rather than written down twice. Most of a design note is prose and cannot be checked &mdash;
 * but the names are the part that rots first and rots silently: rename {@code AxRoles} or
 * {@code paintsDecoration} and the note goes on saying it, correctly formatted, in a directory
 * nobody compiles.
 *
 * <p><b>It reads the repository's Java sources rather than its own classpath</b>, for two reasons
 * that are the same reason. The note spans three source sets &mdash; the model and the widget hooks
 * are {@code limn-toolkit}'s, the bridges are this module's, and the tests it cites as guarantees
 * are in test sets no other module can see &mdash; and an identifier that no longer appears in any
 * of them is exactly what a rename leaves behind. It is a spell check against the code, not a
 * compiler: a name that resolves here is present, not necessarily present in the sense the sentence
 * around it claims.
 *
 * <p>A name belonging to a platform rather than to this repository &mdash; an AppKit selector, a
 * Win32 message, a D-Bus member, an LWJGL entry point &mdash; is listed in {@link #NOT_OURS}, which
 * is the second thing this checks: putting a foreign name in the prose means naming it there too.
 */
class DesignNoteTest {

    /**
     * Names in the note that are somebody else's. Each is a deliberate entry and not a wildcard,
     * because "it is probably a platform thing" is how a typo survives a gate like this.
     */
    private static final Set<String> NOT_OURS = Set.of(
            // AppKit, and the Objective-C runtime
            "NSAccessibilityElement", "NSAccessibilityPriority", "NSAccessibilityButtonRole",
            "AXValue", "accessibilityTitle", "accessibilityLabel", "accessibilityRoleDescription",
            "accessibilityFocusedUIElement", "class_getInstanceMethod", "method_getTypeEncoding",
            "dlsym", "FocusedUIElementChanged",
            // UI Automation and Win32
            "IToggleProvider", "TextPattern", "GetFocus", "UiaClientsAreListening", "WM_GETOBJECT",
            // AT-SPI2 and D-Bus
            "ACTIVE", "CHECKED", "libatspi",
            // LWJGL and GLFW
            "Platform", "GLFW_PLATFORM_UNAVAILABLE", "glfwGetPlatform", "glfwGetX11Window",
            // Java itself
            "HashMap", "ClassValue", "volatile", "protected", "boolean");

    /** Fenced spans that are not names at all: paths, keys, literals, prose fragments. */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "^(?:[\"/].*|.*[/ {].*|.*\\.(?:properties|md)|limn[-.].*|org\\..*|\\*.*|.*\\*)$");

    /** A name, optionally with a receiver and optionally with an argument list. */
    private static final Pattern REFERENCE = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_]*)(?:[.#]([A-Za-z][A-Za-z0-9_]*))?(?:\\(.*\\))?$");

    @Test
    void everyNameTheNoteUsesStillExists() throws IOException {
        Set<String> declared = identifiersInTheSource();
        List<String> gone = new ArrayList<>();
        for (String span : backtickedSpans()) {
            Matcher reference = REFERENCE.matcher(span);
            if (NOT_A_NAME.matcher(span).matches() || !reference.matches()) {
                continue;
            }
            for (int part = 1; part <= reference.groupCount(); part++) {
                String name = reference.group(part);
                if (name == null || NOT_OURS.contains(name) || declared.contains(name)) {
                    continue;
                }
                gone.add(span);
                break;
            }
        }
        assertTrue(gone.isEmpty(),
                "docs/design/accessibility.md names things no source declares any more: " + gone
                        + " — rename them in the note, or add them to NOT_OURS if they turn out to "
                        + "belong to a platform");
    }

    /**
     * Every identifier that appears in a Java source file anywhere in the repository, main and
     * test alike.
     *
     * <p>Every identifier and not every <em>declaration</em>: parsing Java to find the difference
     * would be a compiler's work for an answer that is not better here. What this catches is the
     * failure that actually happens &mdash; a name renamed everywhere and left standing in the
     * note &mdash; and what it cannot catch is a name that is still spelled somewhere for an
     * unrelated reason, which is a weaker gate rather than a wrong one.
     *
     * @return the identifiers, as a set
     * @throws IOException if the tree cannot be read
     */
    private static Set<String> identifiersInTheSource() throws IOException {
        Set<String> words = new HashSet<>(1 << 16);
        Pattern identifier = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        try (Stream<Path> tree = Files.walk(RepositoryRoot.find())) {
            List<Path> sources = tree
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/"))
                    .toList();
            assertTrue(sources.size() > 100, "found almost no Java sources; is the root right?");
            for (Path source : sources) {
                Matcher m = identifier.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (m.find()) {
                    words.add(m.group());
                }
            }
        }
        return words;
    }

    /** @return every span between backticks in the note, in order, without duplicates */
    private static Set<String> backtickedSpans() throws IOException {
        Path note = RepositoryRoot.find().resolve("docs/design/accessibility.md");
        assertTrue(Files.exists(note), "docs/design/accessibility.md is gone");
        String text = Files.readString(note, StandardCharsets.UTF_8);
        Set<String> spans = new LinkedHashSet<>();
        Matcher m = Pattern.compile("`([^`\n]+)`").matcher(text);
        while (m.find()) {
            spans.add(m.group(1).trim());
        }
        assertTrue(spans.size() > 20, "the note suddenly names almost nothing; is it still there?");
        return spans;
    }

}
