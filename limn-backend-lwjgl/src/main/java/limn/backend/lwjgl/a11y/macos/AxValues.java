package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleNode;

/**
 * What {@code accessibilityValue} answers for a node, decided where AppKit is not needed: a string, a
 * whole number, or nothing.
 *
 * <p>Three facets share the one attribute. A toggle answers a number, 0, 1 or 2, as AppKit's own check
 * boxes do, the mixed state being why it is not a boolean. A text answers its text. A value answers the
 * text it displays when it has one — a slider showing "40%" is not read as "0.4" — and its number
 * otherwise.
 *
 * <p><b>A value with no number answers no number</b> (decision 16; CRIT-4's macOS half): a date segment
 * nobody has typed into publishes {@link limn.accessibility.ValueFacet#empty()} with its minimum in the
 * number, because two other platforms must answer a number there, and a word in its text. This platform
 * has no attribute that demands a number — {@code AXValue} is an object — so an empty value answers its
 * word, and with no text at all nothing, and never the minimum as if it had been typed. A change of the
 * text alone is a {@code VALUE_CHANGED} in the model, which this bridge posts as a value change like any
 * other.
 */
final class AxValues {

    private AxValues() {
    }

    /**
     * @param node the node asked
     * @return the string {@code AXValue} answers, or {@code null} when it answers a number or nothing
     */
    static String textOf(AccessibleNode node) {
        if (node.toggle() != null) return null;
        if (node.text() != null) return node.text().text();
        return node.value() == null ? null : node.value().text();
    }

    /**
     * @param node the node asked
     * @return whether {@code AXValue} answers a number: a toggle, and a value with no text that holds one
     */
    static boolean hasNumber(AccessibleNode node) {
        if (node.toggle() != null) return true;
        if (node.text() != null || node.value() == null) return false;
        return node.value().text() == null && !node.value().empty();
    }

    /**
     * @param node a node {@link #hasNumber} holds for
     * @return the number: a toggle's 0, 1 or 2, or the value truncated to a whole number
     */
    static long numberOf(AccessibleNode node) {
        if (node.toggle() != null) {
            return switch (node.toggle().state()) {
                case OFF -> 0;
                case ON -> 1;
                case MIXED -> 2;
            };
        }
        return (long) node.value().value();
    }
}
