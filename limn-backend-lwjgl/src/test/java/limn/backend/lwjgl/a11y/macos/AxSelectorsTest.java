package limn.backend.lwjgl.a11y.macos;

import limn.testing.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** The install helpers that fix a closure's shape by their own signature. */
    private static final Map<String, AxSelectors.Kind> HELPERS = Map.of(
            "addId", AxSelectors.Kind.ID,
            "addBool", AxSelectors.Kind.BOOL,
            "addLong", AxSelectors.Kind.INTEGER,
            "addRange", AxSelectors.Kind.RANGE);

    /** The closure classes a direct {@code addMethod} call may be handed, and their shapes. */
    private static final Map<String, AxSelectors.Kind> CLOSURES = Map.of(
            "IdGetter", AxSelectors.Kind.ID,
            "BoolGetter", AxSelectors.Kind.BOOL,
            "LongGetter", AxSelectors.Kind.INTEGER,
            "RangeGetter", AxSelectors.Kind.RANGE,
            "AttributeGetter", AxSelectors.Kind.ID_OF_ID,
            "SelectorGate", AxSelectors.Kind.BOOL_OF_SELECTOR,
            "CellAt", AxSelectors.Kind.ID_OF_TWO_INTEGERS,
            "HitTest", AxSelectors.Kind.ID_OF_POINT);

    /**
     * What {@code AxElementClass}'s source installs, selector to closure shape, read off the source.
     *
     * <p>Every call to an install method outside the install methods' own bodies must be one of three
     * forms, or the scan fails naming it: a helper with a literal selector ({@code addBool("…", …)}),
     * {@code addMethod(target, "…", variable)} with the variable declared in the same file as
     * {@code Closure variable = new Closure()}, or the one loop over {@code AxActions.selectors()}
     * whose body hands its loop variable to a helper. A selector passed through any other variable
     * would escape the literal match and be refused only at run time, on a Mac.
     */
    static Map<String, AxSelectors.Kind> installedBy(String source) {
        // The install methods' own bodies forward a parameter; they are the funnel, not install sites.
        String sites = Pattern.compile("(?ms)^    private (?:<[^>]+> )?(?:void|boolean) add\\w*\\(.*?^    \\}\\n")
                .matcher(source).replaceAll("");
        Map<String, AxSelectors.Kind> installed = new java.util.LinkedHashMap<>();
        List<String> unread = new java.util.ArrayList<>();
        Matcher call = Pattern.compile("(?<![\\w.])(add(?:Id|Bool|Long|Range|Method))\\(([^\\n]*)")
                .matcher(sites);
        Pattern helperLiteral = Pattern.compile("^\\s*\"([A-Za-z:]+)\"\\s*,");
        Pattern directLiteral = Pattern.compile("^\\s*\\w+\\s*,\\s*\"([A-Za-z:]+)\"\\s*,\\s*(\\w+)\\s*\\)");
        Matcher loop = Pattern.compile("for \\(String (\\w+) : AxActions\\.selectors\\(\\)\\) \\{\\s*"
                + "(add(?:Id|Bool|Long|Range))\\(\\1,").matcher(sites);
        java.util.Set<Integer> loopSites = new java.util.HashSet<>();
        while (loop.find()) {
            loopSites.add(loop.start(2));
            for (String action : AxActions.selectors()) {
                installed.put(action, HELPERS.get(loop.group(2)));
            }
        }
        while (call.find()) {
            String method = call.group(1);
            String rest = call.group(2);
            if (loopSites.contains(call.start(1))) continue;
            if (HELPERS.containsKey(method)) {
                Matcher literal = helperLiteral.matcher(rest);
                if (literal.find()) {
                    installed.put(literal.group(1), HELPERS.get(method));
                    continue;
                }
            } else {
                Matcher literal = directLiteral.matcher(rest);
                if (literal.find()) {
                    Matcher declared = Pattern.compile("\\b(\\w+) " + literal.group(2) + " = new \\1\\(")
                            .matcher(sites);
                    AxSelectors.Kind kind = declared.find() ? CLOSURES.get(declared.group(1)) : null;
                    if (kind != null) {
                        installed.put(literal.group(1), kind);
                        continue;
                    }
                }
            }
            unread.add(method + "(" + rest.strip());
        }
        assertTrue(unread.isEmpty(), "install calls whose selector or closure shape the scan cannot "
                + "read, so no test holds them against the list: " + unread);
        return installed;
    }

    /**
     * The element class runs only where AppKit is, so off a Mac this is the one check that it installs
     * exactly what the list names, each with the closure shape the list names, and that the one call to
     * {@code class_addMethod} is the funnel's.
     */
    @Test
    void theElementClassInstallsExactlyTheListedSelectorsWithTheListedShapes() throws IOException {
        String source = Files.readString(RepositoryRoot.find().resolve(ELEMENT_CLASS),
                StandardCharsets.UTF_8);
        Map<String, AxSelectors.Kind> installed = installedBy(source);
        assertEquals(AxSelectors.all(), installed.keySet(),
                "AxSelectors must list exactly what AxElementClass installs, or a selector reaches "
                        + "a Mac that no test has held against the dump");
        for (Map.Entry<String, AxSelectors.Kind> entry : installed.entrySet()) {
            assertEquals(AxSelectors.kindOf(entry.getKey()), entry.getValue(),
                    "-" + entry.getKey() + " is installed with a closure of another shape than listed");
        }
        Matcher calls = Pattern.compile("ObjCRuntime\\.class_addMethod\\(").matcher(source);
        int count = 0;
        while (calls.find()) count++;
        assertEquals(1, count, "every install goes through addMethod, the one class_addMethod call, "
                + "which refuses an unlisted selector and skips one AppKit lacks");
    }

    private static final Path DUMP_SCRIPT = Path.of("scripts/a11y/macos/dump-appkit-constants.swift");

    /**
     * The dump script's copy of what the bridge installs is what the guest prints an encoding line for,
     * so a selector installed and missing from it is one the regeneration would not show.
     */
    @Test
    void theDumpScriptsCopyOfTheInstalledSelectorsIsTheList() throws IOException {
        String script = Files.readString(RepositoryRoot.find().resolve(DUMP_SCRIPT), StandardCharsets.UTF_8);
        int start = script.indexOf("let installedByTheBridge");
        int end = script.indexOf("\n]", start);
        assertTrue(start >= 0 && end > start, "the script's installedByTheBridge list was not found");
        Matcher entry = Pattern.compile("\\(\"([A-Za-z:]+)\", \"([a-z ]+)\"\\)")
                .matcher(script.substring(start, end));
        java.util.Set<String> onElement = new java.util.LinkedHashSet<>();
        java.util.Set<String> onView = new java.util.LinkedHashSet<>();
        while (entry.find()) {
            (entry.group(2).equals("element") ? onElement : onView).add(entry.group(1));
        }
        assertEquals(java.util.Set.copyOf(AxSelectors.ON_ELEMENT), onElement,
                "scripts/a11y/macos/dump-appkit-constants.swift's installedByTheBridge, element entries");
        assertEquals(java.util.Set.copyOf(AxSelectors.ON_VIEW), onView,
                "and its content-view entries");
    }

    @Test
    void theScanReadsTheLoopAndRefusesASelectorItCannotRead() {
        String loopOnly = """
                    private void installActions() {
                        for (String selector : AxActions.selectors()) {
                            addBool(selector, new BoolGetter() {
                            });
                        }
                    }
                """;
        Map<String, AxSelectors.Kind> installed = installedBy(loopOnly);
        List<String> actions = new java.util.ArrayList<>();
        AxActions.selectors().forEach(actions::add);
        assertEquals(actions, List.copyOf(installed.keySet()), "the loop is an install site");
        assertEquals(java.util.Set.of(AxSelectors.Kind.BOOL), java.util.Set.copyOf(installed.values()));

        assertTrue(installedBy("""
                    private void installActions() {
                        for (String selector : AxActions.selectors()) {
                        }
                    }
                """).isEmpty(), "a loop that installs nothing installs nothing");

        String throughAVariable = """
                    private void install() {
                        String name = "accessibilityRole";
                        addId(name, get(node -> 0));
                    }
                """;
        AssertionError refused = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> installedBy(throughAVariable));
        assertTrue(refused.getMessage().contains("addId(name"), refused.getMessage());
    }

    @Test
    void aSelectorIsRefusedUnlistedOrWithAClosureOfAnotherShape() {
        assertNull(AxSelectors.refusal("accessibilityRowCount", AxSelectors.Kind.INTEGER));
        String wrongShape = AxSelectors.refusal("accessibilityRowCount", AxSelectors.Kind.BOOL);
        assertTrue(wrongShape != null && wrongShape.contains("INTEGER") && wrongShape.contains("BOOL"),
                "a BOOL closure under an NSInteger getter misreads the register: " + wrongShape);
        String unlisted = AxSelectors.refusal("accessibilityPerformPick", AxSelectors.Kind.BOOL);
        assertTrue(unlisted != null && unlisted.contains("not in AxSelectors"), unlisted);
        for (String selector : AxSelectors.all()) {
            assertNotNull(AxSelectors.kindOf(selector), selector + " is listed with no shape");
        }
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
