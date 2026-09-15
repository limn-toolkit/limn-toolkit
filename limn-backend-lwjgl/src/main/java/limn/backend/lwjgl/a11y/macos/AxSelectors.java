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
 * {@code NSAccessibilityElement}'s own implementation, or not at all — together with whatever is
 * installed only with it ({@link #REQUIRES}: the actions go with the gate), and the bridge logs a
 * warning naming each. Guessing an encoding is still never an option.
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

    /**
     * What a selector is installed only together with: when any selector named here is not installed,
     * neither is the key.
     *
     * <p><b>The gate and the actions are one unit.</b> AppKit builds the action list a client is shown
     * out of what an object responds to, and every element is one class, so without
     * {@code isAccessibilitySelectorAllowed:} every element would advertise every action — a button
     * "increment", a slider "show menu" (semantics 5). An action selector installed while the gate is
     * not is therefore worse than no action at all, and is withheld with it. The converse does not
     * hold: a gate with one action missing only answers for the actions that are there.
     *
     * <p><b>The two legacy entry points are one unit</b>: {@code accessibilityAttributeNames} would
     * advertise {@code AXElementBusy} that a lone value getter could not answer, and a lone value
     * getter would answer an attribute nothing advertises.
     */
    static final Map<String, List<String>> REQUIRES;

    static {
        Map<String, List<String>> requires = new LinkedHashMap<>();
        for (String action : AxActions.selectors()) {
            requires.put(action, List.of("isAccessibilitySelectorAllowed:"));
        }
        requires.put("accessibilityAttributeValue:", List.of("accessibilityAttributeNames"));
        requires.put("accessibilityAttributeNames", List.of("accessibilityAttributeValue:"));
        REQUIRES = Collections.unmodifiableMap(requires);
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
     * @param encodings every listed selector that will be installed, with its encoding
     * @param missing   every listed selector the running AppKit declares nothing for, in list order
     * @param withheld  every listed selector that has an encoding and is still not installed, because
     *                  something it is installed only with ({@link #REQUIRES}) is not, in list order
     */
    record Resolution(Map<String, String> encodings, List<String> missing, List<String> withheld) {

        /**
         * @param selector a listed selector
         * @return its encoding, or {@code null} when it is not to be installed: the running AppKit
         *         declares none, or it is withheld with a selector it needs
         */
        String encodingOf(String selector) {
            return encodings.get(selector);
        }

        /** @return the warning the bridge logs, or {@code null} when everything is installed. */
        String warning() {
            if (missing.isEmpty() && withheld.isEmpty()) return null;
            String said = "macOS accessibility is serving this window without " + named(missing)
                    + ": the running AppKit declares no such selector, so each is left uninstalled "
                    + "rather than given a guessed encoding.";
            if (!withheld.isEmpty()) {
                said += " Also left uninstalled, because each is installed only together with one "
                        + "of those: " + named(withheld) + ".";
            }
            return said + " Re-run scripts/a11y/macos/dump-appkit-constants.swift on this macOS.";
        }

        private static String named(List<String> selectors) {
            StringBuilder names = new StringBuilder();
            for (String selector : selectors) {
                if (!names.isEmpty()) names.append(", ");
                names.append('-').append(selector);
            }
            return names.toString();
        }
    }

    /**
     * Resolves every listed selector at once, before anything is installed.
     *
     * @param encodingOrNull the running runtime's answer for one selector, {@code null} for none
     * @return the encodings to install, the selectors without one, and those withheld with them
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
        // To a fixed point, so that a unit of more than two holds whichever member is absent.
        List<String> withheld = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String selector : all()) {
                if (!encodings.containsKey(selector)) continue;
                for (String needed : REQUIRES.getOrDefault(selector, List.of())) {
                    if (!encodings.containsKey(needed)) {
                        encodings.remove(selector);
                        withheld.add(selector);
                        changed = true;
                        break;
                    }
                }
            }
        }
        List<String> inListOrder = new ArrayList<>(all());
        inListOrder.retainAll(withheld);
        return new Resolution(Collections.unmodifiableMap(encodings), List.copyOf(missing),
                List.copyOf(inListOrder));
    }
}
