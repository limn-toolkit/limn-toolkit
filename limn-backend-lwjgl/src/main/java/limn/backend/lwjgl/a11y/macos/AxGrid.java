package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

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
 * <p>A table's rows are its {@code ROW} children and the header is its first group child, so the
 * elements handed back are the ones AppKit already holds for those nodes. Columns are none: the
 * toolkit has no column node, and a column index range on every cell is what VoiceOver reads
 * "column 2 of 3" from. A cell asked for by column and row is answered only for a row the walk
 * published, which is the degradation ADR 039 §4.1 accepts.
 *
 * <p><b>An outline and a list are tables of rows too</b> (M2; semantics 1 and 2): their rows are the
 * realized members of their selection — the children whose selection container is the outline or
 * the list, whatever role an application's cell kept — and a row's index is where it stands among
 * every row the widget shows, not among the realized ones: the hierarchy facet's flat row for an
 * outline row, the position in the set for a list row, each less one. Zero-based because a native
 * NSOutlineView's rows answer AXIndex 0, 1, 2… down the visible outline (read on the macOS 26.6.2
 * guest, 2026-09-15, {@code scripts/a11y/macos/outline-probe.swift}); a row whose number is unknown
 * answers {@code NSNotFound}. An outline answers no row count, as the native one answers none.
 *
 * <p>This is the 2026-09-15 extraction of those answers out of the element class, and it changed
 * none of them; the outline and list rows came after it. The table lookups it inherited still have
 * the defects the audit recorded — the first group as the header (MACOS-NEW-9), a row located by its
 * selection position (MACOS-NEW-4), no columns (M4) — and {@code AxGridTest} pins today's answers so
 * that each of those fixes turns a named case red on purpose.
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
     * @return whether it answers as a table of rows: a table, or an outline or a list holding a
     *         selection, which is what makes its items rows (semantics 1)
     */
    boolean isRowContainer(AccessibleNode node) {
        return node.table() != null || isOutlineOrList(node);
    }

    private static boolean isOutlineOrList(AccessibleNode node) {
        return (node.role() == Accessible.Role.TREE || node.role() == Accessible.Role.LIST)
                && node.selection() != null;
    }

    /**
     * @param node the node asked
     * @return whether it is a row: a table's {@code ROW}, or a member of an outline's or a list's
     *         selection
     */
    boolean isRow(AccessibleNode node) {
        if (node.role() == Accessible.Role.ROW) return true;
        AccessibleNode container = containerOf(node);
        return container != null && isOutlineOrList(container);
    }

    /** The container a member's selection belongs to, in the tree being answered from, or null. */
    private AccessibleNode containerOf(AccessibleNode node) {
        int at = node.selectionContainer();
        AccessibleTree tree = source.tree();
        if (at == AccessibleNode.NONE || at >= tree.nodeCount()) return null;
        return tree.node(at);
    }

    /** Keeps the children that are rows of {@code container}, which {@link #isRowContainer} holds. */
    private NodeFilter rowOf(AccessibleNode container) {
        if (container.table() != null) return child -> child.role() == Accessible.Role.ROW;
        int at = source.tree().indexOf(container.id());
        return child -> at != AccessibleNode.NONE && child.selectionContainer() == at;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityRows}: the elements of a table's {@code ROW} children, or of an
     *         outline's or a list's realized members; {@code null} for any other node
     */
    long[] rows(AccessibleNode node) {
        return isRowContainer(node) ? childrenOf(node, rowOf(node)) : null;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityVisibleRows}: those rows that are {@code SHOWING}, or {@code null}
     */
    long[] visibleRows(AccessibleNode node) {
        if (!isRowContainer(node)) return null;
        NodeFilter row = rowOf(node);
        return childrenOf(node, child -> row.keep(child) && child.has(Accessible.State.SHOWING));
    }

    /**
     * @param node the node asked
     * @return {@code accessibilitySelectedRows}: those rows that are {@code SELECTED}, or
     *         {@code null}
     */
    long[] selectedRows(AccessibleNode node) {
        if (!isRowContainer(node)) return null;
        NodeFilter row = rowOf(node);
        return childrenOf(node, child -> row.keep(child) && child.has(Accessible.State.SELECTED));
    }

    /**
     * What a selection container's members are, which decides the attribute AppKit reads its selection
     * from and the notification a change of it is posted as.
     */
    enum SelectionShape {
        /** An outline, a list, or a table of rows: {@code AXSelectedRows}, {@code AXSelectedRowsChanged}. */
        ROWS,
        /** A grid whose members are its cells, a calendar's days: {@code AXSelectedCells}. */
        CELLS,
        /** Anything else holding a selection, a tab strip or a radio group: {@code AXSelectedChildren}. */
        CHILDREN
    }

    /**
     * @param container a node carrying a selection facet
     * @return what its members are: rows for an outline or a list, and for a table whose members are
     *         not cells; cells for a table whose members carry a cell facet; children otherwise
     */
    SelectionShape selectionShape(AccessibleNode container) {
        if (isOutlineOrList(container)) return SelectionShape.ROWS;
        if (container.table() == null) return SelectionShape.CHILDREN;
        AccessibleTree tree = source.tree();
        int at = tree.indexOf(container.id());
        for (int i = at + 1; at != AccessibleNode.NONE && i < tree.nodeCount(); i++) {
            AccessibleNode member = tree.node(i);
            if (member.selectionContainer() == at) {
                return member.cell() != null ? SelectionShape.CELLS : SelectionShape.ROWS;
            }
        }
        return SelectionShape.ROWS;
    }

    /**
     * The realized members of a container's selection that are selected, wherever they hang under it
     * (semantics 1: a calendar's day under its week row, a tab under its strip), in reading order.
     *
     * @param node the node asked
     * @return their elements, or {@code null} for a node holding no selection
     */
    long[] selectedMembers(AccessibleNode node) {
        if (node.selection() == null) return null;
        AccessibleTree tree = source.tree();
        int at = tree.indexOf(node.id());
        if (at == AccessibleNode.NONE) return null;
        long[] found = new long[4];
        int count = 0;
        for (int i = at + 1; i < tree.nodeCount(); i++) {
            AccessibleNode member = tree.node(i);
            if (member.selectionContainer() != at || !member.has(Accessible.State.SELECTED)) continue;
            if (count == found.length) found = java.util.Arrays.copyOf(found, count * 2);
            found[count++] = source.elementFor(member.id());
        }
        return java.util.Arrays.copyOf(found, count);
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
     * NSAccessibilityRow's index: the row's place among the rows, from the facet the walk numbered it
     * with, so an unrealized row above it still counts. A table's row by its position in the set; an
     * outline's by the hierarchy facet's flat row (decision 4), never its place among its siblings; a
     * list's by its position in the set (semantics 2). Zero-based, as a native outline's rows are.
     *
     * @param node the node asked
     * @return {@code accessibilityIndex}; {@code NSNotFound} for an outline or list row whose number
     *         is unknown; {@code -1} for anything that is not a row, and for a table row with no
     *         selection item
     */
    long index(AccessibleNode node) {
        if (node.role() == Accessible.Role.ROW) {
            return node.selectionItem() != null ? node.selectionItem().positionInSet() - 1 : -1;
        }
        AccessibleNode container = containerOf(node);
        if (container == null || !isOutlineOrList(container)) return -1;
        int oneBased = container.role() == Accessible.Role.TREE
                ? (node.hierarchy() == null ? 0 : node.hierarchy().row())
                : node.selectionItem().positionInSet();
        return oneBased > 0 ? oneBased - 1 : NOT_FOUND[0];
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
