package limn.backend.lwjgl.a11y.macos;

import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of installed selectors is the list the element class installs, and a selector the running
 * AppKit lacks is left out by name rather than taking the window's accessibility with it
 * (MACOS-NEW-6). Whether each listed selector has an encoding in the dump is {@code AxConstantsTest}'s.
 */
class AxSelectorsTest {

    private static final Path ELEMENT_CLASS = Path.of(
            "limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/a11y/macos/AxElementClass.java");

    /**
     * The element class runs only where AppKit is, so off a Mac this is the one check that it installs
     * nothing the list does not name: every selector literal handed to one of its install helpers is
     * read out of the source, and the one call to {@code class_addMethod} must be the funnel's.
     */
    @Test
    void theElementClassInstallsExactlyTheListedSelectors() throws IOException {
        String source = Files.readString(RepositoryRoot.find().resolve(ELEMENT_CLASS),
                StandardCharsets.UTF_8);
        Matcher literal = Pattern.compile(
                "\\badd(?:Id|Bool|Long|Range|Method)\\(\\s*(?:\\w+,\\s*)?\"([A-Za-z:]+)\"")
                .matcher(source);
        Set<String> installed = new LinkedHashSet<>();
        while (literal.find()) installed.add(literal.group(1));
        for (String action : AxActions.selectors()) installed.add(action);   // installed in a loop

        assertEquals(AxSelectors.all(), installed,
                "AxSelectors must list exactly what AxElementClass installs, or a selector reaches "
                        + "a Mac that no test has held against the dump");
        Matcher calls = Pattern.compile("ObjCRuntime\\.class_addMethod\\(").matcher(source);
        int count = 0;
        while (calls.find()) count++;
        assertEquals(1, count, "every install goes through addMethod, the one class_addMethod call, "
                + "which refuses an unlisted selector and skips one AppKit lacks");
    }

    @Test
    void everyActionSelectorIsListedOnTheElementClass() {
        for (String action : AxActions.selectors()) {
            assertTrue(AxSelectors.ON_ELEMENT.contains(action), action);
        }
        assertTrue(AxSelectors.isListed("accessibilityFocusedUIElement"));
        assertTrue(!AxSelectors.isListed("accessibilityPerformPick"),
                "a selector the bridge deliberately does not install is not listed");
    }

    @Test
    void aSelectorTheRunningAppKitLacksIsLeftOutByNameWhileEveryOtherResolves() {
        AxSelectors.Resolution resolution = AxSelectors.resolve(selector ->
                selector.equals("accessibilityColumnIndexRange") ? null : "@16@0:8");
        assertEquals(List.of("accessibilityColumnIndexRange"), resolution.missing());
        assertNull(resolution.encodingOf("accessibilityColumnIndexRange"));
        assertEquals(AxSelectors.all().size() - 1, resolution.encodings().size(),
                "one absent selector costs that selector, not the window's accessibility");
        assertEquals("@16@0:8", resolution.encodingOf("accessibilityRole"));
        String warning = resolution.warning();
        assertTrue(warning != null && warning.contains("-accessibilityColumnIndexRange"),
                "and it is said, naming the selector: " + warning);
    }

    @Test
    void nothingMissingIsNothingToSay() {
        AxSelectors.Resolution resolution = AxSelectors.resolve(selector -> "B16@0:8");
        assertTrue(resolution.missing().isEmpty());
        assertNull(resolution.warning());
        assertEquals(Map.copyOf(resolution.encodings()).size(), AxSelectors.all().size());
    }
}
