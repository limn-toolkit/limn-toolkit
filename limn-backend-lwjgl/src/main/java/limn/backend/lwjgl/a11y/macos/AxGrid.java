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
 * <p>A table's rows are its {@code ROW} children, so the elements handed back are the ones AppKit
 * already holds for those nodes. <b>A cell is found by its own cell facet</b> (decision 8; semantics
 * 2): cell (r, c) of table T is the node under one of T's {@code ROW} children whose {@code CellFacet}
 * is (r, c) and whose nearest table ancestor is T — a widget cell included, since it hangs under its
 * row (decision 3) — and a row's index is its cells' row, never its selection position, so a calendar
 * week that carries no selection item is found and numbered like any table row (MACOS-NEW-4,
 * MACOS-NEW-10). <b>The header is matched by column, not by position</b> (semantics 3): the header cell
 * of column c is the child with {@code CellFacet(−1, c)} of one of T's direct group children, a footer
 * cell (row −2) never is, and a table with no such child has no header rather than its footer
 * (MACOS-NEW-9). A cell asked for by column and row is answered only for a row the walk published,
 * which is the degradation ADR 039 §4.1 accepts.
 *
 * <p><b>The three splits semantics 2 and 3 closed after phase 3</b> (2026-09-15), each of which this
 * bridge had one side of: a row's index is read off a cell whose nearest table is the table the row
 * is a row of, so a nested table's cells cannot number the row that holds them; the table-level
 * header list is the union over <em>every</em> direct group child carrying a header cell, not the
 * first such group alone; and a cell's own column header is answered for a data cell and for a footer
 * cell, and never for a header cell, which would answer itself. None of the three shows a difference
 * with today's Table, which publishes one header group and no nested tables; each is a shape another
 * widget or another application's cells may take, and the three bridges now read it the same way.
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
 * none of them; the outline and list rows came after it, and the cell, row and header lookups were
 * rewritten to the settled semantics after that, the same day, and a table's columns (M4) after those.
 *
 * <p><b>A table's columns are elements that stand for no node</b> (decision 34): one per shown column,
 * as a native NSTableView vends (read on the macOS 26.6.2 guest, 2026-09-15,
 * {@code scripts/a11y/macos/table-probe.swift}), each answering its index, its header cell and its
 * cells; {@link AxColumns} keeps them.
 */
final class AxGrid {

    /**
     * {@code NSNotFound} and a zero length: the range of a cell that is not in the grid, and the
     * index of a row whose number is unknown.
     *
     * <p>{@code NSNotFound} is {@code NSIntegerMax}, which on a 64-bit {@code NSInteger} is exactly
     * {@link Long#MAX_VALUE} — read on the macOS 26.6.2 guest (25G83), 2026-09-15,
     * {@code scripts/a11y/macos/list-probe.swift}: the running Foundation printed
     * {@code NSNotFound=9223372036854775807 hex=0x7fffffffffffffff NSIntegerMax=9223372036854775807
     * equal=true}, and an {@code NSAccessibilityElement} answering {@code NSNotFound} for its
     * {@code accessibilityIndex} — vended off a plain view's children, as this bridge vends one — was
     * read by an out-of-process client as {@code AXIndex=9223372036854775807}, {@code objCType=q}.
     * The value was used here before that run without a reading behind it (the phase-3 critic).
     */
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

    // ---- disclosure: an outline's rows open and close (M1) ----------------------------------------

    /**
     * @param node the node asked
     * @return whether it is a row of an outline carrying the hierarchy facet, which is what every
     *         disclosure attribute is answered from
     */
    boolean isOutlineRow(AccessibleNode node) {
        if (node.hierarchy() == null) return false;
        AccessibleNode container = containerOf(node);
        return container != null && container.role() == Accessible.Role.TREE
                && container.selection() != null;
    }

    /**
     * @param node the node asked
     * @return {@code isAccessibilityDisclosed}: an outline row that is open
     */
    boolean disclosed(AccessibleNode node) {
        return isOutlineRow(node) && node.expand() != null && node.expand().expanded();
    }

    /**
     * {@code accessibilityDisclosureLevel}: zero at a root. The model's level is one-based (semantics
     * 6) and a native NSOutlineView's rows answer AXDisclosureLevel 0 at the top, 1 below, 2 below that
     * (read on the macOS 26.6.2 guest, 2026-09-15, {@code scripts/a11y/macos/outline-probe.swift}).
     *
     * @param node the node asked
     * @return the level less one, or {@code 0} for a node with no level, which the gate refuses
     */
    long disclosureLevel(AccessibleNode node) {
        return node.hierarchy() == null || node.hierarchy().level() <= 0 ? 0
                : node.hierarchy().level() - 1;
    }

    /**
     * {@code accessibilityDisclosedByRow}: the row that opened this one, found by walking up the
     * outline's rows from this row over realized rows whose flat row numbers run without a gap, to
     * the first one level shallower. A gap means the rows between were never realized and the parent
     * is not in the snapshot, which answers nothing rather than a grandparent (ADR 039 §4.1).
     *
     * @param node the node asked
     * @return its element, or zero at a root, at a gap and for anything that is not an outline row
     */
    long disclosedByRow(AccessibleNode node) {
        if (!isOutlineRow(node)) return 0;
        int level = node.hierarchy().level();
        if (level <= 1) return 0;
        AccessibleNode outline = containerOf(node);
        long[] rows = rows(outline);
        int at = indexAmong(rows, node);
        int expected = node.hierarchy().row() - 1;
        for (int i = at - 1; i >= 0; i--, expected--) {
            AccessibleNode above = source.nodeFor(rows[i]);
            if (above == null || above.hierarchy() == null || above.hierarchy().row() != expected) return 0;
            if (above.hierarchy().level() < level) {
                return above.hierarchy().level() == level - 1 ? rows[i] : 0;
            }
        }
        return 0;
    }

    /**
     * {@code accessibilityDisclosedRows}: the rows this one opened, one level deeper, found by walking
     * down the outline's rows from this row over realized rows whose flat row numbers run without a
     * gap, until a row at this row's level or shallower. A native outline answers them for an open
     * row and an empty array for a leaf or a closed row.
     *
     * @param node the node asked
     * @return their elements, empty when there are none; {@code null} for anything that is not an
     *         outline row
     */
    long[] disclosedRows(AccessibleNode node) {
        if (!isOutlineRow(node)) return null;
        int level = node.hierarchy().level();
        long[] rows = rows(containerOf(node));
        int at = indexAmong(rows, node);
        long[] found = new long[0];
        int expected = node.hierarchy().row() + 1;
        for (int i = at + 1; at >= 0 && i < rows.length; i++, expected++) {
            AccessibleNode below = source.nodeFor(rows[i]);
            if (below == null || below.hierarchy() == null || below.hierarchy().row() != expected
                    || below.hierarchy().level() <= level) break;
            if (below.hierarchy().level() == level + 1) {
                found = java.util.Arrays.copyOf(found, found.length + 1);
                found[found.length - 1] = rows[i];
            }
        }
        return found;
    }

    private int indexAmong(long[] elements, AccessibleNode node) {
        for (int i = 0; i < elements.length; i++) {
            AccessibleNode at = source.nodeFor(elements[i]);
            if (at != null && at.id() == node.id()) return i;
        }
        return -1;
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
     * @param container a container whose selection is its rows
     * @return whether a realized row among its children takes a selection verb now; allocates nothing,
     *         because the gate asks it whenever a client asks whether the selected rows are settable
     */
    boolean aRowTakesASelectionVerb(AccessibleNode container) {
        AccessibleTree tree = source.tree();
        int at = tree.indexOf(container.id());
        if (at == AccessibleNode.NONE) return false;
        for (int child = tree.node(at).firstChild(); child != AccessibleNode.NONE;
                child = tree.node(child).nextSibling()) {
            AccessibleNode row = tree.node(child);
            if (row.selectionContainer() != at) continue;
            if (row.accepts(Accessible.Action.SELECT) || row.accepts(Accessible.Action.ADD_TO_SELECTION)
                    || row.accepts(Accessible.Action.DESELECT)) return true;
        }
        return false;
    }

    /**
     * @param container a container whose selection is its rows
     * @return the realized members of its selection among its children, in order: the rows a
     *         selected-rows write can name
     */
    java.util.List<AccessibleNode> selectionRows(AccessibleNode container) {
        AccessibleTree tree = source.tree();
        int at = tree.indexOf(container.id());
        java.util.List<AccessibleNode> rows = new java.util.ArrayList<>();
        if (at == AccessibleNode.NONE) return rows;
        for (int child = tree.node(at).firstChild(); child != AccessibleNode.NONE;
                child = tree.node(child).nextSibling()) {
            if (tree.node(child).selectionContainer() == at) rows.add(tree.node(child));
        }
        return rows;
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

    // ---- columns: elements that stand for no node (M4; decision 34) --------------------------------

    /**
     * @param node the node asked
     * @return {@code accessibilityColumns}: a table's column elements, one per shown column in order,
     *         minted on the ask; {@code null} for anything else
     */
    long[] columns(AccessibleNode node) {
        if (node.table() == null) return null;
        long[] columns = new long[Math.max(0, node.table().columnCount())];
        for (int c = 0; c < columns.length; c++) columns[c] = source.columnElementFor(node, c);
        return columns;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityVisibleColumns}: those columns whose header cell is showing, or, with
     *         no header cell, one of whose realized data cells is; {@code null} for anything else
     */
    long[] visibleColumns(AccessibleNode node) {
        if (node.table() == null) return null;
        AccessibleTree tree = source.tree();
        int count = Math.max(0, node.table().columnCount());
        long[] found = new long[count];
        int kept = 0;
        for (int c = 0; c < count; c++) {
            int header = headerCellInColumnOf(node, c);
            boolean showing = header != AccessibleNode.NONE
                    ? tree.node(header).has(Accessible.State.SHOWING)
                    : columnCells(node, c, true).length > 0;
            if (showing) found[kept++] = source.columnElementFor(node, c);
        }
        return java.util.Arrays.copyOf(found, kept);
    }

    /**
     * @param node the node asked
     * @return {@code accessibilitySelectedColumns}: none, for a table, whose selection is its rows — a
     *         native table answers an empty array there; {@code null} for anything else
     */
    long[] selectedColumns(AccessibleNode node) {
        return node.table() == null ? null : new long[0];
    }

    /**
     * @param element a column element
     * @return the table it is a column of, in the tree being answered from, or {@code null} when that
     *         table has left it or no longer shows the column — a column a client held across a change
     */
    AccessibleNode tableOfColumn(long element) {
        long[] key = source.columnKeyOf(element);
        if (key == null) return null;
        AccessibleNode table = source.tree().find(key[0]);
        return table == null || table.table() == null || key[1] >= table.table().columnCount() ? null : table;
    }

    /**
     * @param element a column element
     * @return its column's index, or {@code -1} for an element that is none
     */
    int columnOf(long element) {
        long[] key = source.columnKeyOf(element);
        return key == null ? -1 : (int) key[1];
    }

    /**
     * A column's {@code AXRows}: the realized data cells in that column, in row order — what a native
     * table's column answers under that attribute, which names cells and not rows.
     *
     * @param table       the table
     * @param column      the shown column
     * @param showingOnly whether to keep only the cells that are showing, for {@code AXVisibleRows}
     * @return their elements
     */
    long[] columnCells(AccessibleNode table, int column, boolean showingOnly) {
        AccessibleTree tree = source.tree();
        int[] rows = new int[4];
        long[] ids = new long[4];
        int count = 0;
        for (int child = table.firstChild(); child != AccessibleNode.NONE; child = tree.node(child).nextSibling()) {
            if (tree.node(child).role() != Accessible.Role.ROW) continue;
            for (int at = tree.node(child).firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
                AccessibleNode cell = tree.node(at);
                if (!isDataCell(cell) || cell.cell().column() != column
                        || (showingOnly && !cell.has(Accessible.State.SHOWING))) continue;
                if (count == ids.length) {
                    ids = java.util.Arrays.copyOf(ids, count * 2);
                    rows = java.util.Arrays.copyOf(rows, count * 2);
                }
                int i = count++;
                // In row order, whatever order the realized rows were published in.
                while (i > 0 && rows[i - 1] > cell.cell().row()) {
                    rows[i] = rows[i - 1];
                    ids[i] = ids[i - 1];
                    i--;
                }
                rows[i] = cell.cell().row();
                ids[i] = cell.id();
            }
        }
        long[] elements = new long[count];
        for (int i = 0; i < count; i++) elements[i] = source.elementFor(ids[i]);
        return elements;
    }

    /**
     * @param table  the table
     * @param column the shown column
     * @return a column's {@code AXHeader}: the element of its header cell, or zero when it has none,
     *         which a native headerless table's column answers as no value
     */
    long columnHeader(AccessibleNode table, int column) {
        int header = headerCellInColumnOf(table, column);
        return header == AccessibleNode.NONE ? 0 : source.elementFor(source.tree().node(header).id());
    }

    /**
     * @return the index of the header cell of {@code column} in {@code table}, or {@code NONE};
     *         allocates nothing, because the column's gate asks it
     */
    int headerCellInColumnOf(AccessibleNode table, int column) {
        AccessibleTree tree = source.tree();
        int at = tree.indexOf(table.id());
        return at == AccessibleNode.NONE ? AccessibleNode.NONE : headerCellInColumn(tree, at, column);
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityHeader}: the group holding a table's header cells, or zero for
     *         anything else and for a table that has none, a native headerless table answering no
     *         header (read on the macOS 26.6.2 guest, 2026-09-15, {@code scripts/a11y/macos/table-probe.swift})
     */
    long header(AccessibleNode node) {
        int group = headerGroupOf(node);
        return group == AccessibleNode.NONE ? 0 : source.elementFor(source.tree().node(group).id());
    }

    /**
     * @param node the node asked
     * @return whether {@link #header} has an answer; allocates nothing, because the gate asks it
     */
    boolean hasHeader(AccessibleNode node) {
        return headerGroupOf(node) != AccessibleNode.NONE;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumnHeaderUIElements}: for a table, its header cells in order,
     *         over every direct group child that holds one; for a data cell or a footer cell, the one
     *         header cell of its column, and never for a header cell itself; otherwise, or when there
     *         is no header to name, {@code null}
     */
    long[] columnHeaderElements(AccessibleNode node) {
        AccessibleTree tree = source.tree();
        if (node.table() != null) {
            // The union over EVERY direct group child that carries a header cell, not the first
            // group alone (semantics 3, settled after phase 3; Windows' GetColumnHeaders is the
            // union, and this read only the first). A table that splits its headers over two groups
            // — frozen columns beside scrolling ones — named half of them.
            //
            // No headerGroupOf guard above this walk: the guard passed exactly when this walk finds
            // a cell, so an attribute AppKit asks per element walked the table's groups twice to
            // learn what it was about to say. Finding none is the guard's answer, null. Collected as
            // identifiers and minted at the end, like columnCells above, so the growth copies no
            // element.
            long[] ids = new long[4];
            int count = 0;
            for (int group = node.firstChild(); group != AccessibleNode.NONE;
                    group = tree.node(group).nextSibling()) {
                if (tree.node(group).role() != Accessible.Role.GROUP) continue;
                for (int child = tree.node(group).firstChild(); child != AccessibleNode.NONE;
                        child = tree.node(child).nextSibling()) {
                    AccessibleNode cell = tree.node(child);
                    if (cell.cell() == null || cell.cell().row() != HEADER_ROW) continue;
                    if (count == ids.length) ids = java.util.Arrays.copyOf(ids, count * 2);
                    ids[count++] = cell.id();
                }
            }
            if (count == 0) return null;
            long[] found = new long[count];
            for (int i = 0; i < count; i++) found[i] = source.elementFor(ids[i]);
            return found;
        }
        int header = headerCellOf(node);
        return header == AccessibleNode.NONE ? null : new long[] {source.elementFor(tree.node(header).id())};
    }

    /**
     * @param node the node asked
     * @return whether {@link #columnHeaderElements} has an answer; allocates nothing
     */
    boolean hasColumnHeaders(AccessibleNode node) {
        return node.table() != null ? headerGroupOf(node) != AccessibleNode.NONE
                : headerCellOf(node) != AccessibleNode.NONE;
    }

    /**
     * {@code accessibilityRowCount}: the table facet's count.
     *
     * <p>Read in passing on 2026-09-15 ({@code scripts/a11y/macos/list-probe.swift}) and left as it
     * is: a native {@code NSTableView} answers <em>no</em> {@code AXRowCount} at all
     * ({@code kAXErrorAttributeUnsupported}), so serving it is more than the platform's own tables
     * offer rather than less. Whether to keep serving it is the owner's open pick (this lane's
     * "AXRowCount/AXColumnCount/AXColumnHeaderUIElements"), and nothing here decides it.
     *
     * @param node the node asked
     * @return the table facet's count, or zero
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
     * with, so an unrealized row above it still counts. A table's row by its cells' row (semantics 2),
     * never its selection position, which a calendar week does not carry; an outline's by the hierarchy
     * facet's flat row (decision 4), never its place among its siblings; a list's by its position in the
     * set. Zero-based, as a native outline's and a native table's rows are (AXIndex 0, 1, 2…, read on
     * the macOS 26.6.2 guest, 2026-09-15).
     *
     * @param node the node asked
     * @return {@code accessibilityIndex}; {@code NSNotFound} for a row whose number is unknown — a
     *         table row with no data cell, an outline or list row with no number; {@code -1} for
     *         anything that is not a row
     */
    long index(AccessibleNode node) {
        if (node.role() == Accessible.Role.ROW) {
            AccessibleTree tree = source.tree();
            int me = tree.indexOf(node.id());
            // The table this row is a row OF: the nearest one above it, never the row itself, which
            // would be a nested table of its own. A cell numbers this row only if that same table is
            // the nearest one above the cell (semantics 2, settled after phase 3; Linux's rowIndexOf
            // checked it and this did not), so a nested table's cells cannot number the row that
            // holds them.
            int owner = me == AccessibleNode.NONE ? AccessibleNode.NONE
                    : tableAtOrAbove(tree, tree.node(me).parent());
            for (int child = node.firstChild(); child != AccessibleNode.NONE;
                    child = tree.node(child).nextSibling()) {
                AccessibleNode cell = tree.node(child);
                if (cell.cell() == null || cell.cell().row() < 0) continue;
                if (tableAtOrAbove(tree, cell.parent()) != owner) continue;
                return cell.cell().row();
            }
            return NOT_FOUND[0];
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
     * @return whether it is a cell of a data row, which is what answers the two index ranges: a native
     *         table's header buttons answer neither (read on the guest, 2026-09-15), and a footer cell
     *         is in no data row
     */
    static boolean isDataCell(AccessibleNode node) {
        return node.cell() != null && node.cell().row() >= 0;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityRowIndexRange}: a range of one at a data cell's row, or
     *         {@link #NOT_FOUND}
     */
    long[] rowIndexRange(AccessibleNode node) {
        return isDataCell(node) ? new long[] {node.cell().row(), 1} : NOT_FOUND;
    }

    /**
     * @param node the node asked
     * @return {@code accessibilityColumnIndexRange}: a range of one at a data cell's column, or
     *         {@link #NOT_FOUND}
     */
    long[] columnIndexRange(AccessibleNode node) {
        return isDataCell(node) ? new long[] {node.cell().column(), 1} : NOT_FOUND;
    }

    /**
     * {@code accessibilityCellForColumn:row:} (semantics 2): the node whose cell facet is (row, column)
     * under one of this table's {@code ROW} children, whose nearest table ancestor is this table.
     *
     * @param node   the node asked
     * @param column the shown column, from zero
     * @param row    the data row as shown, from zero
     * @return its element, or zero: for a row the walk did not publish, a column the table does not
     *         show, and anything that is not a table
     */
    long cellAt(AccessibleNode node, long column, long row) {
        if (node.table() == null || row < 0 || column < 0) return 0;
        AccessibleTree tree = source.tree();
        for (int child = node.firstChild(); child != AccessibleNode.NONE; child = tree.node(child).nextSibling()) {
            if (tree.node(child).role() != Accessible.Role.ROW) continue;
            // The nearest table above the row is the cell's too; a row that carried a table facet of
            // its own would make its cells another table's.
            int owner = tableAtOrAbove(tree, child);
            if (owner == AccessibleNode.NONE || tree.node(owner).id() != node.id()) continue;
            for (int at = tree.node(child).firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
                AccessibleNode cell = tree.node(at);
                if (cell.cell() != null && cell.cell().row() == row && cell.cell().column() == column) {
                    return source.elementFor(cell.id());
                }
            }
        }
        return 0;
    }

    /** A header cell's row in its cell facet (ADR 041 §7); a footer cell's is {@code -2}. */
    static final int HEADER_ROW = -1;

    /**
     * The index of the direct group child of a table that holds its header cells: the first one with a
     * child whose cell facet's row is {@link #HEADER_ROW}. Allocates nothing.
     *
     * @return the index, or {@code NONE} for a node that is no table and a table with no header cell
     */
    private int headerGroupOf(AccessibleNode table) {
        if (table.table() == null) return AccessibleNode.NONE;
        AccessibleTree tree = source.tree();
        for (int child = table.firstChild(); child != AccessibleNode.NONE; child = tree.node(child).nextSibling()) {
            if (tree.node(child).role() != Accessible.Role.GROUP) continue;
            for (int at = tree.node(child).firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
                AccessibleNode cell = tree.node(at);
                if (cell.cell() != null && cell.cell().row() == HEADER_ROW) return child;
            }
        }
        return AccessibleNode.NONE;
    }

    /**
     * The index of a data cell's or a footer cell's header cell (semantics 3): under its nearest table
     * ancestor, the child with {@code CellFacet(−1, c)} of one of the table's direct group children, c
     * being the cell's column. Matched by column, never by place, so a column with no header cell has
     * none, and a header cell answers none rather than itself. Allocates nothing.
     *
     * @param cell the cell asked
     * @return the index, or {@code NONE}
     */
    int headerCellOf(AccessibleNode cell) {
        // A data cell and a footer cell each have a column and a header above it; the header cell
        // itself does not answer itself (semantics 3, settled after phase 3). Windows answered it
        // for a header cell too, and this bridge answered it for data cells only, so a footer cell —
        // the summary a table pins under its rows — was in no column as far as a reader could tell.
        if (cell.cell() == null || cell.cell().row() == HEADER_ROW) return AccessibleNode.NONE;
        AccessibleTree tree = source.tree();
        int at = tableAtOrAbove(tree, cell.parent());
        if (at == AccessibleNode.NONE) return AccessibleNode.NONE;
        return headerCellInColumn(tree, at, cell.cell().column());
    }

    /**
     * @return the index of the child with {@code CellFacet(−1, column)} of one of the direct group
     *         children of the table at {@code table}, or {@code NONE}
     */
    static int headerCellInColumn(AccessibleTree tree, int table, int column) {
        for (int child = tree.node(table).firstChild(); child != AccessibleNode.NONE;
                child = tree.node(child).nextSibling()) {
            if (tree.node(child).role() != Accessible.Role.GROUP) continue;
            for (int at = tree.node(child).firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
                AccessibleNode header = tree.node(at);
                if (header.cell() != null && header.cell().row() == HEADER_ROW
                        && header.cell().column() == column) return at;
            }
        }
        return AccessibleNode.NONE;
    }

    /**
     * @param from where to start, inclusive; {@code NONE} answers {@code NONE}
     * @return the index of the nearest node at or above {@code from} carrying a table facet, or
     *         {@code NONE}
     */
    static int tableAtOrAbove(AccessibleTree tree, int from) {
        for (int at = from; at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (tree.node(at).table() != null) return at;
        }
        return AccessibleNode.NONE;
    }
}
