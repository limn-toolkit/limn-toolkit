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

    /**
     * The review of macos-A: skipping each absent selector on its own could leave the actions
     * installed without the gate, and then every element advertises every action (semantics 5).
     */
    @Test
    void anAppKitWithNoGateGetsNoActionSelectorEitherAndIsToldWhy() {
        String gate = "isAccessibilitySelectorAllowed:";
        AxSelectors.Resolution resolution = AxSelectors.resolve(selector ->
                selector.equals(gate) ? null : "B16@0:8");
        assertEquals(List.of(gate), resolution.missing());
        List<String> actions = new java.util.ArrayList<>();
        AxActions.selectors().forEach(actions::add);
        assertEquals(actions, resolution.withheld(),
                "every action goes with the gate, or a button advertises increment");
        for (String action : actions) assertNull(resolution.encodingOf(action), action);
        assertEquals("B16@0:8", resolution.encodingOf("isAccessibilityFocused"),
                "and nothing that is not an action goes with it");
        String warning = resolution.warning();
        assertTrue(warning.contains("-" + gate) && warning.contains("-accessibilityPerformPress")
                        && warning.contains("installed only together"),
                "the warning names the gate and every action withheld with it: " + warning);
    }

    @Test
    void aMissingActionCostsThatActionAndNotTheGate() {
        AxSelectors.Resolution resolution = AxSelectors.resolve(selector ->
                selector.equals("accessibilityPerformShowMenu") ? null : "B16@0:8");
        assertEquals(List.of("accessibilityPerformShowMenu"), resolution.missing());
        assertTrue(resolution.withheld().isEmpty(), "a gate with fewer actions still gates them");
        assertEquals("B16@0:8", resolution.encodingOf("isAccessibilitySelectorAllowed:"));
        assertEquals("B16@0:8", resolution.encodingOf("accessibilityPerformPress"));
    }

    @Test
    void theTwoLegacyEntryPointsAreInstalledTogetherOrNotAtAll() {
        for (String absent : List.of("accessibilityAttributeValue:", "accessibilityAttributeNames")) {
            AxSelectors.Resolution resolution = AxSelectors.resolve(selector ->
                    selector.equals(absent) ? null : "@16@0:8");
            assertNull(resolution.encodingOf("accessibilityAttributeValue:"), absent);
            assertNull(resolution.encodingOf("accessibilityAttributeNames"), absent);
            assertEquals(1, resolution.withheld().size(), absent);
        }
    }

    @Test
    void everyActionSelectorIsInstalledOnlyWithTheGate() {
        for (String action : AxActions.selectors()) {
            assertEquals(List.of("isAccessibilitySelectorAllowed:"), AxSelectors.REQUIRES.get(action),
                    action);
        }
        for (String selector : AxSelectors.REQUIRES.keySet()) {
            assertTrue(AxSelectors.isListed(selector), selector);
            for (String needed : AxSelectors.REQUIRES.get(selector)) {
                assertTrue(AxSelectors.isListed(needed), needed);
            }
        }
    }

    @Test
    void nothingMissingIsNothingToSay() {
        AxSelectors.Resolution resolution = AxSelectors.resolve(selector -> "B16@0:8");
        assertTrue(resolution.missing().isEmpty());
        assertNull(resolution.warning());
        assertEquals(Map.copyOf(resolution.encodings()).size(), AxSelectors.all().size());
    }
}
