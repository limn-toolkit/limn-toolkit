package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleNode;

/**
 * What {@code isAccessibilitySelectorAllowed:} answers for one node and one selector: which of the
 * implementations installed on the one element class this node actually offers.
 *
 * <p><b>AppKit honours a refusal for a getter too</b>, not only for an action. Read on the macOS
 * 26.6.2 guest on 2026-09-13 ({@code scripts/a11y/macos/selector-allowed-probe.swift},
 * readings/macos-summary.md §6): an attribute whose getter the gate refuses reads
 * {@code kAXErrorNoValue} and leaves {@code AXAttributeNames}, whether the getter is inherited or
 * overridden, and an overridden one is then never entered. So a selector installed class-wide for
 * the nodes that have an answer is refused here on every node that has none, instead of answering a
 * sentinel — a button with an {@code AXRows} of nil and an {@code AXRowCount} of zero, a table cell
 * with an {@code AXIndex} of −1.
 *
 * <p>Separate from {@link AxElementClass} for the reason {@link AxGrid} is: the closure that asks this
 * needs AppKit, and the answer does not.
 */
final class AxGate {

    private AxGate() {
    }

    /**
     * @param grid     the lookups the table and row selectors answer from
     * @param node     the node the element stands for
     * @param selector the selector AppKit is about to send it
     * @return whether the element offers it
     */
    static boolean allows(AxGrid grid, AccessibleNode node, String selector) {
        if (AxActions.isActionSelector(selector)) return AxActions.verbFor(node, selector) != null;
        return switch (selector) {
            // A table, an outline or a list: the containers whose items are rows (M2). A native
            // NSOutlineView answers AXRows, AXVisibleRows and AXSelectedRows, and no AXRowCount
            // (kAXErrorAttributeUnsupported, read on the guest 2026-09-15, outline-probe.swift).
            case "accessibilityRows", "accessibilityVisibleRows", "accessibilitySelectedRows" ->
                    grid.isRowContainer(node);
            case "accessibilityRowCount", "accessibilityColumnCount" -> node.table() != null;
            // A container's selection is read off the attribute of its shape and no other: a native
            // outline answers AXSelectedRows and no AXSelectedChildren (read 2026-09-15).
            case "accessibilitySelectedChildren" -> node.selection() != null
                    && grid.selectionShape(node) == AxGrid.SelectionShape.CHILDREN;
            case "accessibilitySelectedCells" -> node.selection() != null
                    && grid.selectionShape(node) == AxGrid.SelectionShape.CELLS;
            case "accessibilityIndex" -> grid.isRow(node);
            // Everything else this class implements is an attribute every node answers, and answering
            // false for one of those would hide the node's name.
            default -> true;
        };
    }
}
