package limn.backend.lwjgl.a11y.macos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Every selector the bridge hands {@code class_addMethod}, in one list, and what becomes of one the
 * running AppKit does not declare.
 *
 * <p><b>One list, so that a test can tie each selector to the dump.</b> An encoding is read out of
 * the running AppKit and never written down (§12.3), which leaves the committed dump of
 * {@code scripts/a11y/macos/dump-appkit-constants.swift} as the only place a selector is checked
 * before it reaches a Mac. The selectors used to be literals scattered over the element class and
 * the action table, and two of them were not in the dump at all (MACOS-NEW-6). {@code AxConstantsTest}
 * now asserts every name here has an encoding in the dump, {@code AxSelectorsTest} that the element
 * class installs exactly these, and {@link AxElementClass} refuses to install a selector that is not
 * listed.
 *
 * <p><b>A listed selector the running AppKit does not declare is left out, loudly.</b> It was a
 * thrown {@code IllegalStateException} out of the element class's constructor, which
 * {@code Bridges.openFor} turns into {@code AccessibilityBridge.NONE}: one misspelt or withdrawn
 * selector took the whole window's accessibility away and nothing said so. Now the encodings are
 * resolved first, every selector with none is skipped — its attribute or action answered by
 * {@code NSAccessibilityElement}'s own implementation, or not at all — and the bridge logs a warning
 * naming each. Guessing an encoding is still never an option.
 */
final class AxSelectors {

    private AxSelectors() {
    }

    /** Installed on the element class every node is vended as, in the order they are installed. */
    static final List<String> ON_ELEMENT;

    /** Installed on the one content-view subclass, where AppKit asks where the focus is (§13.22). */
    static final List<String> ON_VIEW = List.of("accessibilityFocusedUIElement");

    static {
        List<String> element = new ArrayList<>(List.of(
                "accessibilityRole", "accessibilitySubrole", "accessibilityTitle",
                "accessibilityLabel", "accessibilityHelp", "accessibilityRoleDescription",
                "accessibilityValue", "accessibilityIdentifier", "accessibilityChildren",
                "accessibilityParent", "accessibilityLinkedUIElements", "isAccessibilityElement",
                "isAccessibilityEnabled", "isAccessibilityFocused",
                "accessibilityHitTest:", "accessibilityFocusedUIElement"));
        for (String action : AxActions.selectors()) element.add(action);
        element.addAll(List.of(
                "isAccessibilitySelectorAllowed:",
                "accessibilityRows", "accessibilityVisibleRows", "accessibilitySelectedRows",
                "accessibilityColumns", "accessibilityHeader", "accessibilityColumnHeaderUIElements",
                "accessibilityRowCount", "accessibilityColumnCount", "accessibilityIndex",
                "accessibilityRowIndexRange", "accessibilityColumnIndexRange",
                "accessibilityCellForColumn:row:", "isAccessibilitySelected",
                "accessibilityAttributeValue:", "accessibilityAttributeNames"));
        ON_ELEMENT = Collections.unmodifiableList(element);
    }

    /** @return every selector installed anywhere, each once, element class first. */
    static Set<String> all() {
        Set<String> all = new LinkedHashSet<>(ON_ELEMENT);
        all.addAll(ON_VIEW);
        return Collections.unmodifiableSet(all);
    }

    /**
     * @param selector a selector name
     * @return whether the bridge is allowed to install it
     */
    static boolean isListed(String selector) {
        return ON_ELEMENT.contains(selector) || ON_VIEW.contains(selector);
    }

    /**
     * What the running AppKit declared for each listed selector.
     *
     * @param encodings every listed selector that has an encoding, with it
     * @param missing   every listed selector that has none, in list order
     */
    record Resolution(Map<String, String> encodings, List<String> missing) {

        /**
         * @param selector a listed selector
         * @return its encoding, or {@code null} when the running AppKit declares none
         */
        String encodingOf(String selector) {
            return encodings.get(selector);
        }

        /** @return the warning the bridge logs, or {@code null} when nothing is missing. */
        String warning() {
            if (missing.isEmpty()) return null;
            StringBuilder names = new StringBuilder();
            for (String selector : missing) {
                if (!names.isEmpty()) names.append(", ");
                names.append('-').append(selector);
            }
            return "macOS accessibility is serving this window without " + names
                    + ": the running AppKit declares no such selector, so each is left uninstalled "
                    + "rather than given a guessed encoding. Re-run "
                    + "scripts/a11y/macos/dump-appkit-constants.swift on this macOS.";
        }
    }

    /**
     * Resolves every listed selector at once, before anything is installed.
     *
     * @param encodingOrNull the running runtime's answer for one selector, {@code null} for none
     * @return the encodings found and the selectors without one
     */
    static Resolution resolve(Function<String, String> encodingOrNull) {
        Map<String, String> encodings = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String selector : all()) {
            String encoding = encodingOrNull.apply(selector);
            if (encoding == null) {
                missing.add(selector);
            } else {
                encodings.put(selector, encoding);
            }
        }
        return new Resolution(Collections.unmodifiableMap(encodings), List.copyOf(missing));
    }
}
