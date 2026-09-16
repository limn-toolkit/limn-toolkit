package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The column elements a table is vended with, which stand for no node, and the ways one goes away.
 *
 * <p><b>Why a table has them at all</b> (M4; decision 34 of 2026-09-13, "a native NSTableView
 * decides"): a native view-based NSTableView read on the macOS 26.6.2 guest (2026-09-15,
 * {@code scripts/a11y/macos/table-probe.swift}) answers {@code AXColumns} with one {@code AXColumn}
 * element per column, each answering its {@code AXIndex}, its {@code AXHeader} (the header button) and
 * its {@code AXRows} (that column's cells), and lists them among the table's children. The toolkit has
 * no column node and gains none — ADR 039 §1.12's roles are closed — so the bridge keeps a second,
 * bridge-only kind of element, keyed by the table's identifier and the shown column's index.
 *
 * <p><b>The same lifetime rules as a node's element, applied to a key that is not a node.</b> Minted
 * on the first ask, the same object on every ask after (§1.3's stability, for a client that kept one);
 * released at a frame's end when its table has left the tree or no longer shows that column, and all
 * at once on a rebind or a detach, each demoted before it is released (the factory's job), and never
 * from a reentrant publish (§3.2). User-interface thread only, as {@link AxElements} is.
 */
final class AxColumns {

    /** How a column element is made and unmade; the whole of this class's contact with Objective-C. */
    interface Factory {

        /**
         * @param tableId the table node's identifier
         * @param column  the shown column's index
         * @return a new, retained element
         */
        long newColumn(long tableId, int column);

        /**
         * Demotes and releases one; the pointer is dangling afterwards.
         *
         * @param element the element to release
         */
        void release(long element);
    }

    private final Factory factory;
    /** A table's identifier to its column elements by index, zero where none was minted. */
    private final Map<Long, long[]> byTable = new HashMap<>();
    /** An element to the table identifier and column it stands for. */
    private final Map<Long, long[]> byElement = new HashMap<>();

    AxColumns(Factory factory) {
        this.factory = factory;
    }

    /**
     * @param tableId the table node's identifier
     * @param column  the shown column's index, from zero
     * @return its element, minted on first ask
     */
    long elementFor(long tableId, int column) {
        long[] elements = byTable.get(tableId);
        if (elements == null || elements.length <= column) {
            long[] grown = new long[column + 1];
            if (elements != null) System.arraycopy(elements, 0, grown, 0, elements.length);
            elements = grown;
            byTable.put(tableId, elements);
        }
        if (elements[column] == 0) {
            long element = factory.newColumn(tableId, column);
            elements[column] = element;
            byElement.put(element, new long[] {tableId, column});
        }
        return elements[column];
    }

    /**
     * @param element an element
     * @return {@code {tableId, column}} for a column element this holds, or {@code null}
     */
    long[] keyOf(long element) {
        return byElement.get(element);
    }

    /**
     * Releases every column whose table is no longer in {@code tree}, no longer carries a table facet,
     * or no longer shows that column.
     *
     * @param tree the tree the frame ended with
     * @return how many were released
     */
    int reconcile(AccessibleTree tree) {
        if (byElement.isEmpty()) return 0;
        List<long[]> dead = new ArrayList<>();
        for (long[] key : byElement.values()) {
            AccessibleNode table = tree.find(key[0]);
            if (table == null || table.table() == null || key[1] >= table.table().columnCount()) dead.add(key);
        }
        for (long[] key : dead) release(key[0], (int) key[1]);
        return dead.size();
    }

    private void release(long tableId, int column) {
        long[] elements = byTable.get(tableId);
        long element = elements[column];
        elements[column] = 0;
        byElement.remove(element);
        boolean any = false;
        for (long e : elements) any |= e != 0;
        if (!any) byTable.remove(tableId);
        factory.release(element);
    }

    /**
     * Releases every column element: a rebind, or the window going away.
     *
     * @return how many were released
     */
    int empty() {
        int released = byElement.size();
        List<Long> elements = new ArrayList<>(byElement.keySet());
        byElement.clear();
        byTable.clear();
        for (long element : elements) factory.release(element);
        return released;
    }

    /** @return how many column elements are alive */
    int size() {
        return byElement.size();
    }

    /** @return every held element with its key, as {@code {element, tableId, column}} rows */
    List<long[]> held() {
        List<long[]> rows = new ArrayList<>(byElement.size());
        for (Map.Entry<Long, long[]> entry : byElement.entrySet()) {
            rows.add(new long[] {entry.getKey(), entry.getValue()[0], entry.getValue()[1]});
        }
        return rows;
    }
}
