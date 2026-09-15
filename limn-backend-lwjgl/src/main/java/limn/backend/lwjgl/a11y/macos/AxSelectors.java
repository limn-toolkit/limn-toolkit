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
 * class installs exactly these, each with the closure shape listed ({@link Kind}), and
 * {@link AxElementClass} refuses to install a selector that is not listed or a closure of another
 * shape.
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

    /**
     * The shape of the libffi closure a selector is installed with: its return and its arguments
     * after {@code self} and {@code _cmd}.
     *
     * <p>The encoding handed to {@code class_addMethod} is read from the running AppKit, but the
     * closure under it is written in {@link AxElementClass}, and nothing in the encoding stops a
     * closure of another shape from being installed under it: a {@code BOOL} closure under an
     * {@code NSInteger} getter compiles, passes every test off a Mac, and misreads a return register
     * on one. So each selector names its shape here; the element class refuses a closure of any other
     * shape, and {@code AxConstantsTest} holds each shape against the encodings the dump read.
     */
    enum Kind {
        /** {@code (id, SEL) -> id}. */
        ID,
        /** {@code (id, SEL) -> BOOL}. */
        BOOL,
        /** {@code (id, SEL) -> NSInteger}. */
        INTEGER,
        /** {@code (id, SEL) -> NSRange}. */
        RANGE,
        /** {@code (id, SEL, id) -> id}. */
        ID_OF_ID,
        /** {@code (id, SEL, SEL) -> BOOL}. */
        BOOL_OF_SELECTOR,
        /** {@code (id, SEL, NSInteger, NSInteger) -> id}. */
        ID_OF_TWO_INTEGERS,
        /** {@code (id, SEL, CGPoint) -> id}. */
        ID_OF_POINT,
        /** {@code (id, SEL, BOOL) -> void}. */
        VOID_OF_BOOL,
        /** {@code (id, SEL, id) -> void}. */
        VOID_OF_ID
    }

    /** Installed on the element class every node is vended as, in the order they are installed. */
    static final List<String> ON_ELEMENT;

    /** Installed on the one content-view subclass, where AppKit asks where the focus is (§13.22). */
    static final List<String> ON_VIEW = List.of("accessibilityFocusedUIElement");

    /** Every listed selector's closure shape. */
    private static final Map<String, Kind> KINDS;

    static {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String selector : List.of("accessibilityRole", "accessibilitySubrole",
                "accessibilityTitle", "accessibilityLabel", "accessibilityHelp",
                "accessibilityRoleDescription", "accessibilityValue", "accessibilityIdentifier",
                "accessibilityChildren", "accessibilityParent", "accessibilityLinkedUIElements")) {
            kinds.put(selector, Kind.ID);
        }
        for (String selector : List.of("isAccessibilityElement", "isAccessibilityEnabled",
                "isAccessibilityFocused")) {
            kinds.put(selector, Kind.BOOL);
        }
        kinds.put("accessibilityHitTest:", Kind.ID_OF_POINT);
        kinds.put("accessibilityFocusedUIElement", Kind.ID);
        for (String action : AxActions.selectors()) kinds.put(action, Kind.BOOL);
        kinds.put("isAccessibilitySelectorAllowed:", Kind.BOOL_OF_SELECTOR);
        kinds.put("accessibilityActionNames", Kind.ID);
        kinds.put("accessibilityPerformAction:", Kind.VOID_OF_ID);
        for (String setter : AxSetters.BOOL_SETTERS) kinds.put(setter, Kind.VOID_OF_BOOL);
        kinds.put(AxSetters.VALUE, Kind.VOID_OF_ID);
        kinds.put(AxSetters.SELECTED_ROWS, Kind.VOID_OF_ID);
        for (String selector : List.of("accessibilityRows", "accessibilityVisibleRows",
                "accessibilitySelectedRows", "accessibilitySelectedChildren",
                "accessibilitySelectedCells", "accessibilityColumns", "accessibilityHeader",
                "accessibilityColumnHeaderUIElements")) {
            kinds.put(selector, Kind.ID);
        }
        for (String selector : List.of("accessibilityRowCount", "accessibilityColumnCount",
                "accessibilityIndex")) {
            kinds.put(selector, Kind.INTEGER);
        }
        kinds.put("accessibilityRowIndexRange", Kind.RANGE);
        kinds.put("accessibilityColumnIndexRange", Kind.RANGE);
        kinds.put("accessibilityCellForColumn:row:", Kind.ID_OF_TWO_INTEGERS);
        kinds.put("isAccessibilitySelected", Kind.BOOL);
        kinds.put("isAccessibilityDisclosed", Kind.BOOL);
        kinds.put("accessibilityDisclosureLevel", Kind.INTEGER);
        kinds.put("accessibilityDisclosedByRow", Kind.ID);
        kinds.put("accessibilityDisclosedRows", Kind.ID);
        kinds.put("isAccessibilityExpanded", Kind.BOOL);
        kinds.put("accessibilityAttributeValue:", Kind.ID_OF_ID);
        kinds.put("accessibilityAttributeNames", Kind.ID);
        ON_ELEMENT = List.copyOf(kinds.keySet());
        KINDS = Collections.unmodifiableMap(kinds);
    }

    /**
     * @param selector a listed selector
     * @return the shape of closure it is installed with, or {@code null} when it is not listed
     */
    static Kind kindOf(String selector) {
        return KINDS.get(selector);
    }

    /**
     * The check the element class makes before installing a closure, off a Mac as well as on one.
     *
     * @param selector the selector about to be installed
     * @param closure  the shape of the closure about to be installed under it
     * @return {@code null} when it may be installed; otherwise why not, naming the mistake in the
     *         element class it is
     */
    static String refusal(String selector, Kind closure) {
        Kind listed = KINDS.get(selector);
        if (listed == null) {
            return "-" + selector + " is not in AxSelectors, so nothing ties it to the dump of "
                    + "AppKit's encodings; list it there";
        }
        if (listed != closure) {
            return "-" + selector + " is listed as a " + listed + " closure and was about to be "
                    + "installed with a " + closure + " one, which would read the wrong registers";
        }
        return null;
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
        // A setter without the gate is settable on every element (read 2026-09-13: settable is the
        // gate's answer for the setter), which is worse than no setter.
        for (String setter : AxSetters.selectors()) {
            requires.put(setter, List.of("isAccessibilitySelectorAllowed:"));
        }
        // The legacy action pair is a unit, and goes with the gate: a list of names nothing performs,
        // or a perform of names nothing lists, is an action a reader offers and a user finds dead.
        requires.put("accessibilityActionNames",
                List.of("isAccessibilitySelectorAllowed:", "accessibilityPerformAction:"));
        requires.put("accessibilityPerformAction:",
                List.of("isAccessibilitySelectorAllowed:", "accessibilityActionNames"));
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
        return KINDS.containsKey(selector);
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
