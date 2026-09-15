package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

/**
 * What NSAccessibilityTable, NSAccessibilityRow and NSAccessibilityCell ask, answered in Java
 * values from the table and cell facets and from the tree's own shape; ADR 041 §7.
 *
 * <p><b>Separate from {@link AxElementClass} so that it can be tested where AppKit is not.</b> The
 * element class wraps each answer in an {@code NSArray} or an {@code NSNumber} inside a libffi
 * closure, and nothing inside a closure can run on a machine with no Objective-C runtime; every
 * lookup here takes a {@link AxElementClass.Source} — which the platform-free bridge implements —
 * and hands back element pointers, numbers and {@code null}, so a test can pin it.
 *
 * <p>Rows are the table's {@code ROW} children and the header is its first group child, so the
 * elements handed back are the ones AppKit already holds for those nodes. Columns are none: the
 * toolkit has no column node, and a column index range on every cell is what VoiceOver reads
 * "column 2 of 3" from. A cell asked for by column and row is answered only for a row the walk
 * published, which is the degradation ADR 039 §4.1 accepts.
 *
 * <p>This is the 2026-09-15 extraction of those answers out of the element class, and it changed
 * none of them. The lookups it inherited still have the defects the audit recorded — the first group
 * as the header (MACOS-NEW-9), a row located by its selection position (MACOS-NEW-4), no rows for an
 * outline or a list (M2), no columns (M4) — and {@code AxGridTest} pins today's answers so that each
 * of those fixes turns a named case red on purpose.
 */
final class AxGrid {

    /** {@code NSNotFound} and a zero length: the range of a cell that is not in the grid. */
    static final long[] NOT_FOUND = {Long.MAX_VALUE, 0};

    private final AxElementClass.Source source;

    AxGrid(AxElementClass.Source source) {
        this.source = source;
    }

    private interface NodeFilter {
        boolean keep(AccessibleNode child);
    }

    /** The elements of {@code node}'s children that {@code filter} keeps, in order. */
    private long[] childrenOf(AccessibleNode node, NodeFilter filter) {
        long[] children = source.childElementsOf(node);
        long[] kept = new long[children.length];
        int count = 0;
        for (long child : children) {
            AccessibleNode childNode = source.nodeFor(child);
            if (childNode != null && filter.keep(childNode)) kept[count++] = child;
        }
        return count == kept.length ? kept : java.util.Arrays.copyOf(kept, count);
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityRows}: the elements of a table's {@code ROW} children, or
     *         {@code null} for a node with no table facet
     */
    long[] rows(AccessibleNode node) {
        return node.table() == null ? null
                : childrenOf(node, child -> child.role() == Accessible.Role.ROW);
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityVisibleRows}: those rows that are {@code SHOWING}, or {@code null}
     */
    long[] visibleRows(AccessibleNode node) {
        return node.table() == null ? null
                : childrenOf(node, child -> child.role() == Accessible.Role.ROW
                        && child.has(Accessible.State.SHOWING));
    }

    /**
     * @param node the node asked
     * @return {@code accessibilitySelectedRows}: those rows that are {@code SELECTED}, or
     *         {@code null}
     */
    long[] selectedRows(AccessibleNode node) {
        return node.table() == null ? null
                : childrenOf(node, child -> child.role() == Accessible.Role.ROW
                        && child.has(Accessible.State.SELECTED));
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumns}: empty for a table, {@code null} for anything else
     */
    long[] columns(AccessibleNode node) {
        return node.table() == null ? null : new long[0];
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityHeader}: a table's header group, or zero
     */
    long header(AccessibleNode node) {
        return node.table() == null ? 0 : headerOf(node);
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumnHeaderUIElements}: for a table, the header group's children;
     *         for a cell in a data row, the one header cell above it; otherwise, or when there is no
     *         header to name, {@code null}
     */
    long[] columnHeaderElements(AccessibleNode node) {
        if (node.table() != null) {
            long header = headerOf(node);
            AccessibleNode group = header == 0 ? null : source.nodeFor(header);
            return group == null ? null : childrenOf(group, child -> true);
        }
        if (node.cell() != null && node.cell().row() >= 0) {
            long header = columnHeaderOf(node);
            return header == 0 ? null : new long[] {header};
        }
        return null;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityRowCount}: the table facet's count, or zero
     */
    long rowCount(AccessibleNode node) {
        return node.table() == null ? 0 : node.table().rowCount();
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumnCount}: the table facet's count, or zero
     */
    long columnCount(AccessibleNode node) {
        return node.table() == null ? 0 : node.table().columnCount();
    }

    /**
     * NSAccessibilityRow's index: the row's place among the data rows, from the facet the walk
     * numbered it with, so an unrealized row above it still counts.
     *
     * @param node the node asked
     * @return {@code accessibilityIndex}, or {@code -1} for anything but a row with a selection item
     */
    long index(AccessibleNode node) {
        return node.role() == Accessible.Role.ROW && node.selectionItem() != null
                ? node.selectionItem().positionInSet() - 1 : -1;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityRowIndexRange}: a range of one at a data cell's row, or
     *         {@link #NOT_FOUND}
     */
    long[] rowIndexRange(AccessibleNode node) {
        return node.cell() == null || node.cell().row() < 0
                ? NOT_FOUND : new long[] {node.cell().row(), 1};
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumnIndexRange}: a range of one at a cell's column, or
     *         {@link #NOT_FOUND}
     */
    long[] columnIndexRange(AccessibleNode node) {
        return node.cell() == null ? NOT_FOUND : new long[] {node.cell().column(), 1};
    }

    /**
     * {@code accessibilityCellForColumn:row:}.
     *
     * @param node   the node asked
     * @param column the column
     * @param row    the data row, from zero
     * @return the element of the cell at that column in the realized {@code ROW} whose position is
     *         {@code row + 1}, or zero
     */
    long cellAt(AccessibleNode node, long column, long row) {
        if (node.table() == null) return 0;
        for (long rowElement : source.childElementsOf(node)) {
            AccessibleNode rowNode = source.nodeFor(rowElement);
            if (rowNode == null || rowNode.role() != Accessible.Role.ROW
                    || rowNode.selectionItem() == null
                    || rowNode.selectionItem().positionInSet() != row + 1) continue;
            for (long cell : source.childElementsOf(rowNode)) {
                AccessibleNode cellNode = source.nodeFor(cell);
                if (cellNode != null && cellNode.cell() != null
                        && cellNode.cell().column() == column) return cell;
            }
            return 0;
        }
        return 0;
    }

    /** The element of the table's header group: its first child with the group role, or zero. */
    private long headerOf(AccessibleNode table) {
        for (long child : source.childElementsOf(table)) {
            AccessibleNode childNode = source.nodeFor(child);
            if (childNode != null && childNode.role() == Accessible.Role.GROUP) return child;
        }
        return 0;
    }

    /** The element of the header cell above {@code cell}, found by structure, or zero. */
    private long columnHeaderOf(AccessibleNode cell) {
        long parent = source.parentElementOf(cell);              // the row
        AccessibleNode row = parent == 0 ? null : source.nodeFor(parent);
        long tableElement = row == null ? 0 : source.parentElementOf(row);
        AccessibleNode table = tableElement == 0 ? null : source.nodeFor(tableElement);
        if (table == null || table.table() == null) return 0;
        long header = headerOf(table);
        AccessibleNode group = header == 0 ? null : source.nodeFor(header);
        if (group == null) return 0;
        long[] headers = source.childElementsOf(group);
        int column = cell.cell().column();
        return column >= 0 && column < headers.length ? headers[column] : 0;
    }
}
