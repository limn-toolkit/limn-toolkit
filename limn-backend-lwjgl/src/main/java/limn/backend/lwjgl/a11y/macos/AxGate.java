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
 * with an {@code AXIndex} of −1, a headerless table with an {@code AXHeader} of nil, a button with an
 * {@code AXColumnIndexRange} of {@code NSNotFound}.
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
        if (AxSetters.selectors().contains(selector)) return AxSetters.offers(grid, node, selector);
        // Every other setter is NSAccessibilityElement's stored one: settable to a client and read by
        // nothing of ours, AXRole included (read 2026-09-13). Refused everywhere.
        if (selector.startsWith("setAccessibility")) return false;
        return switch (selector) {
            // A table, an outline or a list: the containers whose items are rows (M2). A native
            // NSOutlineView answers AXRows, AXVisibleRows and AXSelectedRows, and no AXRowCount
            // (kAXErrorAttributeUnsupported, read on the guest 2026-09-15, outline-probe.swift).
            case "accessibilityRows", "accessibilityVisibleRows", "accessibilitySelectedRows" ->
                    grid.isRowContainer(node);
            case "accessibilityRowCount", "accessibilityColumnCount" -> node.table() != null;
            // A container's selection is read off the attribute of its shape and no other: a native
            // outline answers AXSelectedRows and no AXSelectedChildren (read 2026-09-15). It also
            // answers AXSelectedCells, with the AXCell each of its selected rows holds, and that one
            // is deliberately not vended on an outline or a list: a Limn row holds no cell element —
            // its children are the application's own widgets, a label or a row of them — so the
            // answer would name the rows again under a cell's attribute or name an arbitrary widget,
            // and the native outline told its selection only as AXSelectedRowsChanged. A grid whose
            // members are cells, a calendar, answers it (CELLS).
            case "accessibilitySelectedChildren" -> node.selection() != null
                    && grid.selectionShape(node) == AxGrid.SelectionShape.CHILDREN;
            case "accessibilitySelectedCells" -> node.selection() != null
                    && grid.selectionShape(node) == AxGrid.SelectionShape.CELLS;
            case "accessibilityIndex" -> grid.isRow(node);
            // A table's header, and a header to name, only where there is one (semantics 3): a native
            // headerless table answers no AXHeader (read on the macOS 26.6.2 guest, 2026-09-15,
            // table-probe.swift), and a table whose only group is its footer has no header at all.
            case "accessibilityHeader" -> grid.hasHeader(node);
            case "accessibilityColumnHeaderUIElements" -> grid.hasColumnHeaders(node);
            case "accessibilityCellForColumn:row:" -> node.table() != null;
            // A table's columns (M4): elements standing for no node, as a native NSTableView vends.
            case "accessibilityColumns", "accessibilityVisibleColumns", "accessibilitySelectedColumns" ->
                    node.table() != null;
            // A cell of a data row: the native table's header buttons answer neither range (read the
            // same day), and a footer cell is in no data row.
            case "accessibilityRowIndexRange", "accessibilityColumnIndexRange" -> AxGrid.isDataCell(node);
            // An outline row's disclosure, and nobody else's: a native outline row lists all four,
            // leaves included, and no AXExpanded (read on the guest 2026-09-15). A level of zero
            // publishes nothing (semantics 6), which here is the getter refused.
            case "isAccessibilityDisclosed", "accessibilityDisclosedByRow",
                 "accessibilityDisclosedRows" -> grid.isOutlineRow(node);
            case "accessibilityDisclosureLevel" -> grid.isOutlineRow(node)
                    && node.hierarchy().level() > 0;
            case "isAccessibilityExpanded" -> node.expand() != null && !grid.isOutlineRow(node);
            // Everything else this class implements is an attribute every node answers, and answering
            // false for one of those would hide the node's name.
            default -> true;
        };
    }

    /**
     * What a table's column element offers (M4): every stored setter refused, as on a node's element,
     * and its header only where the column has a header cell, a native headerless table's column
     * answering no header (read on the guest, 2026-09-15, table-probe.swift).
     *
     * @param grid     the lookups
     * @param table    the table the column is one of
     * @param column   the column's index
     * @param selector the selector AppKit is about to send it
     * @return whether the column element offers it
     */
    static boolean allowsOnColumn(AxGrid grid, AccessibleNode table, int column, String selector) {
        if (selector.startsWith("setAccessibility")) return false;
        if (selector.equals("accessibilityHeader")) {
            return grid.headerCellInColumnOf(table, column) != AccessibleNode.NONE;
        }
        return true;
    }
}
