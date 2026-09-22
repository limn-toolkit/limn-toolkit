package limn.testing.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Action;
import limn.accessibility.Accessible.State;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.CellFacet;
import limn.accessibility.Shape;
import limn.concurrent.UiRuntime;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every widget of the {@code GRID} shape owes a reader beyond what its rows do, as named
 * cases over a {@link GridSubject} (ADR 045 §4; decision 99, 2026-09-22):
 * <ul>
 *   <li>the container is of the {@code GRID} shape and carries a {@code TableFacet} whose column
 *       count is the header's;</li>
 *   <li>the four invariants hold, unfocused and focused;</li>
 *   <li>the header cells sit at row −1, one per column, in order, each carrying the direction
 *       its column is sorted in (decision 36) and {@code PRESS} exactly where the column
 *       sorts;</li>
 *   <li>{@code PRESS} on a sortable header sorts by it and the facet says so, and a second press
 *       changes the direction the facet reports;</li>
 *   <li>a data cell is a {@code CELL} at a row and a column inside the counts;</li>
 *   <li>{@code FOCUS} on a cell that offers it moves the cursor onto that cell, which is then
 *       the one active node, and selects nothing;</li>
 *   <li>a header cell keeps its id across a sort.</li>
 * </ul>
 */
public final class GridContract {

    private GridContract() {
    }

    /**
     * The cases, in the order above.
     *
     * @param subject the widget under contract
     * @param runtime the installed runtime the harness drains verbs through
     * @return the cases; a test runs each as a dynamic test
     */
    public static List<ContractCase> cases(GridSubject subject, UiRuntime runtime) {
        List<ContractCase> cases = new ArrayList<>();
        cases.add(new ContractCase("the container is a GRID whose column count is the header's",
                () -> theContainerIsAGrid(subject, runtime)));
        cases.add(new ContractCase("the four invariants hold, unfocused and focused",
                () -> theInvariantsHold(subject, runtime)));
        cases.add(new ContractCase("the header cells sit at row -1 in column order, with their "
                + "sort and PRESS where the column sorts", () -> theHeaderCells(subject, runtime)));
        cases.add(new ContractCase("PRESS on a sortable header sorts by it, and the facet says so",
                () -> pressSorts(subject, runtime)));
        cases.add(new ContractCase("a data cell is a CELL at a row and a column inside the counts",
                () -> theDataCells(subject, runtime)));
        cases.add(new ContractCase("FOCUS on a cell moves the cursor onto it and selects nothing",
                () -> focusMovesTheCursor(subject, runtime)));
        cases.add(new ContractCase("a header cell keeps its id across a sort",
                () -> headerIdsSurviveASort(subject, runtime)));
        return cases;
    }

    // ------------------------------------------------------------------------------ the cases

    private static void theContainerIsAGrid(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        check(Shape.of(b.grid) == Shape.GRID, b, "the container is of the GRID shape, not "
                + Shape.of(b.grid));
        check(b.grid.role() == Accessible.Role.TABLE, b, "and a TABLE, not " + b.grid.role());
        check(b.grid.table().columnCount() == subject.columnNames().size(), b,
                "the facet's column count is the header's: " + b.grid.table().columnCount()
                        + " against " + subject.columnNames());
        check(b.grid.table().rowCount() > 0, b, "and it shows rows: " + b.grid.table().rowCount());
    }

    private static void theInvariantsHold(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<String> before = AccessibleInvariants.violations("unfocused", b.harness.tree());
        check(before.isEmpty(), b, String.join("\n  ", before));
        b.harness.focus(subject.widget());
        List<String> after = AccessibleInvariants.violations("focused", b.harness.tree());
        check(after.isEmpty(), b, String.join("\n  ", after));
    }

    private static void theHeaderCells(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<AccessibleNode> headers = b.headers();
        check(headers.size() == subject.columnNames().size(), b, "one header cell per column: "
                + headers.size() + " for " + subject.columnNames());
        for (int c = 0; c < headers.size(); c++) {
            AccessibleNode header = headers.get(c);
            check(header.role() == Accessible.Role.COLUMN_HEADER, b, "a header cell is a "
                    + "COLUMN_HEADER: \"" + header.name() + "\"");
            check(Shape.of(header) == Shape.GRID, b, "and of the GRID shape: \"" + header.name() + "\"");
            check(header.cell().column() == c, b, "header \"" + header.name() + "\" is at column "
                    + header.cell().column() + ", expected " + c);
            check(header.name().equals(subject.columnNames().get(c)), b, "the header cells are "
                    + "named in column order: \"" + header.name() + "\" at " + c + ", expected \""
                    + subject.columnNames().get(c) + "\"");
            check(header.cell().sort() == subject.sortOf(c), b, "the facet carries the direction "
                    + "the column is sorted in: \"" + header.name() + "\" says "
                    + header.cell().sort() + ", the widget " + subject.sortOf(c));
            check(offers(header, Action.PRESS) == subject.isSortable(c), b, subject.isSortable(c)
                    ? "a header that sorts offers PRESS: \"" + header.name() + "\""
                    : "a header that does not sort offers no PRESS: \"" + header.name() + "\"");
        }
    }

    private static void pressSorts(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int c = b.firstSortable();
        if (c < 0) {
            for (AccessibleNode header : b.headers()) {
                check(header.cell().sort() == CellFacet.Sort.NONE, b, "with no sortable column "
                        + "no header carries a direction: \"" + header.name() + "\"");
            }
            return;
        }
        check(b.perform(b.headers().get(c), Action.PRESS), b, "PRESS on the header is accepted");
        CellFacet.Sort first = subject.sortOf(c);
        check(first != CellFacet.Sort.NONE, b, "the rows are sorted by the column: " + first);
        check(b.headers().get(c).cell().sort() == first, b, "and the facet says so: "
                + b.headers().get(c).cell().sort());
        check(b.perform(b.headers().get(c), Action.PRESS), b, "a second PRESS is accepted");
        CellFacet.Sort second = subject.sortOf(c);
        check(second != first, b, "and changes the direction: " + first + " then " + second);
        check(b.headers().get(c).cell().sort() == second, b, "which the facet follows: "
                + b.headers().get(c).cell().sort());
    }

    private static void theDataCells(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        List<AccessibleNode> cells = b.dataCells();
        check(!cells.isEmpty(), b, "the grid publishes data cells");
        for (AccessibleNode cell : cells) {
            check(cell.role() == Accessible.Role.CELL, b, "a data cell is a CELL: \"" + cell.name() + "\"");
            check(Shape.of(cell) == Shape.GRID, b, "and of the GRID shape: \"" + cell.name() + "\"");
            check(cell.cell().row() < b.grid.table().rowCount(), b, "its row is inside the "
                    + "count: \"" + cell.name() + "\" at row " + cell.cell().row() + " of "
                    + b.grid.table().rowCount());
            check(cell.cell().column() < b.grid.table().columnCount(), b, "its column is inside "
                    + "the count: \"" + cell.name() + "\" at column " + cell.cell().column()
                    + " of " + b.grid.table().columnCount());
        }
    }

    private static void focusMovesTheCursor(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        b.harness.focus(subject.widget());
        AccessibleNode target = null;
        for (AccessibleNode cell : b.dataCells()) {
            if (offers(cell, Action.FOCUS) && cell.has(State.ENABLED)) {
                target = cell; // the last one that offers it, so the move is a real one
            }
        }
        if (target == null) {
            return;
        }
        int selectedBefore = b.selectedCount();
        int row = target.cell().row();
        int column = target.cell().column();
        check(b.perform(target, Action.FOCUS), b, "FOCUS on the cell is accepted");
        int[] cursor = subject.cursor();
        check(cursor != null && cursor[0] == row && cursor[1] == column, b,
                "FOCUS moves the cursor onto the cell addressed: " + java.util.Arrays.toString(cursor)
                        + ", expected [" + row + ", " + column + "]");
        AccessibleNode after = b.harness.node(target.id());
        check(after.has(State.ACTIVE), b, "which is the active node: \"" + after.name() + "\"");
        check(b.harness.tree().activeDescendant() == after.id(), b, "and the tree's one cursor");
        check(b.selectedCount() == selectedBefore, b, "and selects nothing: " + selectedBefore
                + " selected before, " + b.selectedCount() + " after");
    }

    private static void headerIdsSurviveASort(GridSubject subject, UiRuntime rt) {
        Bound b = Bound.of(subject, rt);
        int c = b.firstSortable();
        if (c < 0) {
            return;
        }
        Map<String, Long> before = b.headerIds();
        b.perform(b.headers().get(c), Action.PRESS);
        Map<String, Long> after = b.headerIds();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long now = after.get(e.getKey());
            check(now != null && now.equals(e.getValue()), b, "header \"" + e.getKey()
                    + "\" kept its id across a sort: was " + e.getValue() + ", is " + now);
        }
    }

    // ---------------------------------------------------------------------------- the reading

    private static boolean offers(AccessibleNode node, Action verb) {
        return node.actions() != null && node.actions().has(verb);
    }

    private static void check(boolean condition, Bound b, String rule) {
        if (!condition) {
            throw new AssertionError(rule + "\nthe tree published:" + b.harness.describe());
        }
    }

    /** A subject built and bound, with its grid read off the tree. */
    private static final class Bound {
        final GridSubject subject;
        final AccessibleHarness harness;
        AccessibleNode grid;

        private Bound(GridSubject subject, AccessibleHarness harness) {
            this.subject = subject;
            this.harness = harness;
            grid = grid();
        }

        static Bound of(GridSubject subject, UiRuntime runtime) {
            return new Bound(subject, new AccessibleHarness(runtime, subject.build()));
        }

        /** The one node carrying a table facet, re-read from the tree published last. */
        AccessibleNode grid() {
            AccessibleTree tree = harness.tree();
            AccessibleNode found = null;
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.table() != null) {
                    if (found != null) {
                        throw new AssertionError("two nodes carry a table facet; the subject must "
                                + "publish one grid:" + harness.describe());
                    }
                    found = node;
                }
            }
            if (found == null) {
                throw new AssertionError("no node carries a table facet:" + harness.describe());
            }
            grid = found;
            return found;
        }

        /** The cells at row −1 below the grid, in column order. */
        List<AccessibleNode> headers() {
            List<AccessibleNode> headers = new ArrayList<>(cellsWhere(-1));
            headers.sort((x, y) -> Integer.compare(x.cell().column(), y.cell().column()));
            return headers;
        }

        /** The cells at a row of zero or more below the grid, in tree order. */
        List<AccessibleNode> dataCells() {
            return cellsWhere(Integer.MIN_VALUE);
        }

        private List<AccessibleNode> cellsWhere(int row) {
            AccessibleTree tree = harness.tree();
            AccessibleNode g = grid();
            List<AccessibleNode> cells = new ArrayList<>();
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.cell() == null || !under(tree, node, g)) {
                    continue;
                }
                if (row == Integer.MIN_VALUE ? node.cell().row() >= 0 : node.cell().row() == row) {
                    cells.add(node);
                }
            }
            return cells;
        }

        private static boolean under(AccessibleTree tree, AccessibleNode node, AccessibleNode top) {
            for (AccessibleNode n = node; n != null;
                    n = n.parent() == AccessibleNode.NONE ? null : tree.node(n.parent())) {
                if (n.id() == top.id()) {
                    return true;
                }
            }
            return false;
        }

        int firstSortable() {
            for (int c = 0; c < subject.columnNames().size(); c++) {
                if (subject.isSortable(c)) {
                    return c;
                }
            }
            return -1;
        }

        int selectedCount() {
            AccessibleTree tree = harness.tree();
            int count = 0;
            for (int i = 0; i < tree.nodeCount(); i++) {
                if (tree.node(i).has(State.SELECTED)) {
                    count++;
                }
            }
            return count;
        }

        boolean perform(AccessibleNode node, Action verb) {
            return harness.perform(node.id(), verb, Accessible.Argument.NONE);
        }

        Map<String, Long> headerIds() {
            Map<String, Long> ids = new HashMap<>();
            for (AccessibleNode header : headers()) {
                ids.put(header.name(), header.id());
            }
            return ids;
        }
    }
}
