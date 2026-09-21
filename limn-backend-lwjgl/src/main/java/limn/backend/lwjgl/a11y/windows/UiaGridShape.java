package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Map;


/**
 * The {@code GRID} shape's half of the Windows bridge (ADR 045 §5): a table and its cells: Grid and Table on the container, GridItem and TableItem on a cell; a cell is found by its own facet and a column's header is the CellFacet(-1, c) child of one of the table's direct groups (ADR 041 §7).
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaGridShape {

    private UiaGridShape() {
    }

    /** The patterns this shape owns: GRID_PATTERN, TABLE_PATTERN, GRID_ITEM_PATTERN, TABLE_ITEM_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
            // ADR 041 §7; semantics 2 and 3. Cells are answered from what the walk published: a row
            // the table has not realized has no node, so GetItem on it answers null, the degradation
            // ADR 039 §4.1 accepts. A cell is found by its own CellFacet, never by a row's position;
            // a column's header is the CellFacet(-1, c) child of one of the table's direct groups,
            // a footer cell (row -2) never; row headers are none.
            case UiaIds.GRID_PATTERN -> {
                slots.put("GetItem", (UiaCom.PIIP) (self, row, column, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode table = tree.find(nodeId);
                    if (table == null || table.table() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    AccessibleNode cell = cellAt(tree, table, row, column);
                    MemoryUtil.memPutAddress(out,
                            cell == null ? 0 : context.simpleElementFor(cell.id()));
                    return UiaIds.S_OK;
                });
                slots.put("get_RowCount", (UiaCom.PP) (self, out) -> {
                    AccessibleNode table = context.tree().find(nodeId);
                    if (table == null || table.table() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    MemoryUtil.memPutInt(out, table.table().rowCount());
                    return UiaIds.S_OK;
                });
                slots.put("get_ColumnCount", (UiaCom.PP) (self, out) -> {
                    AccessibleNode table = context.tree().find(nodeId);
                    if (table == null || table.table() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    MemoryUtil.memPutInt(out, table.table().columnCount());
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.TABLE_PATTERN -> {
                slots.put("GetRowHeaders", (UiaCom.PP) (self, out) -> {
                    MemoryUtil.memPutAddress(out, context.unknownArray(new long[0]));
                    return UiaIds.S_OK;
                });
                slots.put("GetColumnHeaders", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode table = tree.find(nodeId);
                    if (table == null || table.table() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    List<AccessibleNode> headers = columnHeadersOf(tree, table);
                    long[] pointers = new long[headers.size()];
                    for (int i = 0; i < pointers.length; i++) {
                        pointers[i] = context.simpleElementFor(headers.get(i).id());
                    }
                    MemoryUtil.memPutAddress(out, context.unknownArray(pointers));
                    return UiaIds.S_OK;
                });
                slots.put("get_RowOrColumnMajor", (UiaCom.PP) (self, out) -> {
                    MemoryUtil.memPutInt(out, UiaIds.ROW_OR_COLUMN_MAJOR_ROW_MAJOR);
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.GRID_ITEM_PATTERN -> {
                slots.put("get_Row", cellInt(nodeId, context, cell -> cell.row()));
                slots.put("get_Column", cellInt(nodeId, context, cell -> cell.column()));
                slots.put("get_RowSpan", cellInt(nodeId, context, cell -> 1));
                slots.put("get_ColumnSpan", cellInt(nodeId, context, cell -> 1));
                slots.put("get_ContainingGrid", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode cell = tree.find(nodeId);
                    if (cell == null || cell.cell() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    AccessibleNode table = tableOf(tree, cell);
                    MemoryUtil.memPutAddress(out,
                            table == null ? 0 : context.simpleElementFor(table.id()));
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.TABLE_ITEM_PATTERN -> {
                slots.put("GetRowHeaderItems", (UiaCom.PP) (self, out) -> {
                    MemoryUtil.memPutAddress(out, context.unknownArray(new long[0]));
                    return UiaIds.S_OK;
                });
                slots.put("GetColumnHeaderItems", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode cell = tree.find(nodeId);
                    if (cell == null || cell.cell() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    AccessibleNode table = tableOf(tree, cell);
                    // Matched by column, never by the header's place among its siblings (semantics
                    // 3): until 2026-09-15 the header at the cell's column index was answered, which
                    // is another column's header once the group holds anything else first.
                    //
                    // Answered for a data cell and for a footer cell (row -2), and NEVER for the
                    // header cell itself (row -1), which until the phase-3 fix round answered
                    // itself: a header is not under its own column's header, and a client walking
                    // the array from a header would walk back to where it started. The settled
                    // split of semantics 3 (2026-09-15) takes the reading Linux already had;
                    // macOS answers data cells only, and the footer half is this bridge's, where
                    // the model's footer row is a summary of the column above it.
                    AccessibleNode header = table == null || cell.cell().row() == -1 ? null
                            : headerOf(tree, table, cell.cell().column());
                    long[] pointers = header == null ? new long[0]
                            : new long[] {context.simpleElementFor(header.id())};
                    MemoryUtil.memPutAddress(out, context.unknownArray(pointers));
                    return UiaIds.S_OK;
                });
            }
            default -> {
            }
        }
    }

    private interface CellInt {
        int of(limn.accessibility.CellFacet cell);
    }

    /** A getter answering one integer off the cell facet. */
    private static CallbackI cellInt(long nodeId, UiaProvider.Context context, CellInt body) {
        return (UiaCom.PP) (self, out) -> {
            AccessibleNode node = context.tree().find(nodeId);
            if (node == null || node.cell() == null) {
                return UiaIds.E_ELEMENT_NOT_AVAILABLE;
            }
            MemoryUtil.memPutInt(out, body.of(node.cell()));
            return UiaIds.S_OK;
        };
    }

    /**
     * The table a cell belongs to: the nearest ancestor of {@code cell} carrying a
     * {@code TableFacet}, <b>starting at its parent</b> (semantics 2, the minor split settled
     * 2026-09-15), and null when none.
     *
     * <p>Until then the climb started at the cell itself, so a node carrying both facets — a table
     * nested inside a cell of another — was its own containing grid, and the outer table's
     * {@code GetItem} could not find it at all ({@link #cellAt} rejects a cell whose table is not
     * the one asked). Linux (`belongsTo`) and macOS (`tableAtOrAbove`) already started at the
     * parent; this is the third bridge, and the rule now reads the same on all of them.
     */
    private static AccessibleNode tableOf(AccessibleTree tree, AccessibleNode cell) {
        int parent = cell.parent();
        for (AccessibleNode at = parent == AccessibleNode.NONE ? null : tree.node(parent);
                at != null; ) {
            if (at.table() != null) {
                return at;
            }
            int above = at.parent();
            at = above == AccessibleNode.NONE ? null : tree.node(above);
        }
        return null;
    }

    /**
     * The table's column headers in reading order (semantics 3, the settled header-group rule): every
     * node with a {@code CellFacet} of row {@code -1} among the children of the table's direct
     * {@code GROUP} children. A footer cell (row {@code -2}) is never one, and a group that holds
     * no header cell (a toolbar, a footer) contributes nothing. Until 2026-09-15 the children of the
     * table's first group were answered, whatever they were.
     */
    private static List<AccessibleNode> columnHeadersOf(AccessibleTree tree, AccessibleNode table) {
        List<AccessibleNode> headers = new java.util.ArrayList<>();
        for (int group = table.firstChild(); group != AccessibleNode.NONE;
                group = tree.node(group).nextSibling()) {
            if (tree.node(group).role() != Accessible.Role.GROUP) {
                continue;
            }
            for (int at = tree.node(group).firstChild(); at != AccessibleNode.NONE;
                    at = tree.node(at).nextSibling()) {
                AccessibleNode candidate = tree.node(at);
                if (candidate.cell() != null && candidate.cell().row() == -1) {
                    headers.add(candidate);
                }
            }
        }
        return headers;
    }

    /** @return the header of {@code column} by {@link #columnHeadersOf}'s rule, or null for none */
    private static AccessibleNode headerOf(AccessibleTree tree, AccessibleNode table, int column) {
        for (int group = table.firstChild(); group != AccessibleNode.NONE;
                group = tree.node(group).nextSibling()) {
            if (tree.node(group).role() != Accessible.Role.GROUP) {
                continue;
            }
            for (int at = tree.node(group).firstChild(); at != AccessibleNode.NONE;
                    at = tree.node(at).nextSibling()) {
                AccessibleNode candidate = tree.node(at);
                if (candidate.cell() != null && candidate.cell().row() == -1
                        && candidate.cell().column() == column) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * The realized cell at {@code row}, {@code column} of a table (semantics 2, decision 8): the
     * node whose {@code CellFacet} is that pair, among the children of the table's {@code ROW}
     * children, whose nearest table is this one. A widget cell hangs under its synthetic row
     * (decision 3) and is found there like a synthetic one; a calendar's week rows carry no
     * position, which is why the row's {@code SelectionItemFacet} is never read. Until 2026-09-15 a
     * row was matched by its position in set, which found no day in a calendar (WINDOWS-NEW-8).
     *
     * @return the cell, or null when that row is not realized or the pair names no data cell
     */
    private static AccessibleNode cellAt(AccessibleTree tree, AccessibleNode table, int row,
                                         int column) {
        if (row < 0) {
            return null;
        }
        for (int at = table.firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
            if (tree.node(at).role() != Accessible.Role.ROW) {
                continue;
            }
            for (int child = tree.node(at).firstChild(); child != AccessibleNode.NONE;
                    child = tree.node(child).nextSibling()) {
                AccessibleNode cell = tree.node(child);
                AccessibleNode owner = cell.cell() == null ? null : tableOf(tree, cell);
                if (cell.cell() != null && cell.cell().row() == row
                        && cell.cell().column() == column
                        && owner != null && owner.id() == table.id()) {
                    return cell;
                }
            }
        }
        return null;
    }
}
