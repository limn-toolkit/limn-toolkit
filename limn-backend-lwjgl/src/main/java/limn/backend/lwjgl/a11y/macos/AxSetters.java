package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The setter half: which of the toolkit's verbs a reader's write to an attribute means, and on which
 * nodes the attribute is settable at all (MACOS-NEW-11; semantics 5).
 *
 * <p><b>Settable is the gate's answer for the setter.</b> Read on the macOS 26.6.2 guest, 2026-09-13
 * ({@code scripts/a11y/macos/selector-allowed-probe.swift}): {@code AXUIElementIsAttributeSettable}
 * asks {@code isAccessibilitySelectorAllowed:} about the <em>setter</em> selector, and with a gate that
 * says yes to it every attribute that has an {@code NSAccessibilityElement} setter reports settable —
 * {@code AXRole} included — and a write lands in {@code NSAccessibilityElement}'s own storage, where
 * nothing of the toolkit ever reads it. So a setter installed here is offered exactly where its verb
 * is accepted, and every other {@code setAccessibility…} selector is refused on every node
 * ({@link AxGate}).
 *
 * <p><b>A write is posted, never waited for</b> (§1.9): the setter returns nothing, and a verb the node
 * does not accept is not posted. The check is made again on the write itself, because a client may
 * send the setter without asking whether the attribute is settable first.
 */
final class AxSetters {

    private AxSetters() {
    }

    /** {@code setAccessibilityFocused:}: YES moves the keyboard, or the cursor, here. */
    static final String FOCUSED = "setAccessibilityFocused:";
    /** {@code setAccessibilitySelected:}: YES selects, NO deselects. */
    static final String SELECTED = "setAccessibilitySelected:";
    /** {@code setAccessibilityDisclosed:}: an outline row's AXDisclosing; YES opens, NO closes. */
    static final String DISCLOSED = "setAccessibilityDisclosed:";
    /** {@code setAccessibilityExpanded:}: anything else's AXExpanded; YES opens, NO closes. */
    static final String EXPANDED = "setAccessibilityExpanded:";
    /** {@code setAccessibilityValue:}: a text, or a value by number or by its text. */
    static final String VALUE = "setAccessibilityValue:";
    /** {@code setAccessibilitySelectedRows:}: a table's, an outline's or a list's selection, row by row. */
    static final String SELECTED_ROWS = "setAccessibilitySelectedRows:";

    /** The four whose argument is a {@code BOOL}, in the order they are installed. */
    static final List<String> BOOL_SETTERS = List.of(FOCUSED, SELECTED, DISCLOSED, EXPANDED);

    private static final List<String> SELECTORS = List.of(FOCUSED, SELECTED, DISCLOSED, EXPANDED, VALUE,
            SELECTED_ROWS);

    /**
     * @return every setter this bridge installs; the same list every time, because the gate asks it on
     *         every selector AppKit checks and a list built per ask would be an allocation per ask
     */
    static List<String> selectors() {
        return SELECTORS;
    }

    /**
     * A verb and what it carries.
     *
     * @param action   the verb
     * @param argument its argument
     */
    record Setting(Accessible.Action action, Accessible.Argument argument) {
    }

    /**
     * What {@code isAccessibilitySelectorAllowed:} answers for one of these setters, which is what a
     * client reads as "settable".
     *
     * @param grid     the row lookups, for telling an outline row from anything else that opens
     * @param node     the node
     * @param selector one of {@link #selectors()}
     * @return whether a write to its attribute would post something now
     */
    static boolean offers(AxGrid grid, AccessibleNode node, String selector) {
        return switch (selector) {
            case FOCUSED -> node.accepts(Accessible.Action.FOCUS);
            case SELECTED -> node.accepts(Accessible.Action.SELECT) || node.accepts(Accessible.Action.DESELECT);
            // Settable only on a row that can open, as a native outline's AXDisclosing is (read on the
            // guest, 2026-09-15, outline-probe.swift); a row publishes EXPAND or COLLAPSE only then.
            case DISCLOSED -> grid.isOutlineRow(node) && opens(node);
            case EXPANDED -> !grid.isOutlineRow(node) && opens(node);
            case VALUE -> node.accepts(Accessible.Action.SET_TEXT) || node.accepts(Accessible.Action.SET_VALUE);
            // Settable where the container's selection is its rows and a realized row takes a selection
            // verb: a native outline's AXSelectedRows read settable in both selection modes (read on the
            // guest, 2026-09-15, outline-probe.swift and selection-writes-probe.swift).
            case SELECTED_ROWS -> node.selection() != null && grid.isRowContainer(node)
                    && grid.selectionShape(node) == AxGrid.SelectionShape.ROWS && grid.aRowTakesASelectionVerb(node);
            default -> false;
        };
    }

    private static boolean opens(AccessibleNode node) {
        return node.accepts(Accessible.Action.EXPAND) || node.accepts(Accessible.Action.COLLAPSE);
    }

    /**
     * @param grid     the row lookups
     * @param node     the node written to
     * @param selector one of {@link #BOOL_SETTERS}
     * @param on       the {@code BOOL} written
     * @return the verb to post, or {@code null} when the node does not accept the one the write means
     */
    static Setting forBool(AxGrid grid, AccessibleNode node, String selector, boolean on) {
        if (!offers(grid, node, selector)) return null;
        Accessible.Action action = switch (selector) {
            // Focus cannot be written away: a NO has no verb, and moving the focus elsewhere is a
            // write to the element it goes to.
            case FOCUSED -> on ? Accessible.Action.FOCUS : null;
            // YES is a click in either selection mode: AXSelected YES on a second row of a native
            // multi-select outline replaced the selection rather than extending it (read on the guest,
            // 2026-09-15, selection-writes-probe.swift), which is SELECT and not ADD_TO_SELECTION.
            case SELECTED -> on ? Accessible.Action.SELECT : Accessible.Action.DESELECT;
            case DISCLOSED, EXPANDED -> on ? Accessible.Action.EXPAND : Accessible.Action.COLLAPSE;
            default -> null;
        };
        return action != null && node.accepts(action) ? new Setting(action, Accessible.Argument.NONE) : null;
    }

    /**
     * One verb on one row, of the several a selected-rows write may post.
     *
     * @param nodeId the row
     * @param action the verb
     */
    record RowSetting(long nodeId, Accessible.Action action) {
    }

    /**
     * What {@code setAccessibilitySelectedRows:} posts: the verbs that leave exactly the written rows
     * selected, or nothing at all.
     *
     * <p>Read on the macOS 26.6.2 guest, 2026-09-15 ({@code scripts/a11y/macos/selection-writes-probe.swift}):
     * a native outline's selection becomes exactly the rows written, in either mode — one row replaces
     * whatever was selected, two rows in a multi-select outline become the selection, an empty array
     * empties it — and a single-select outline refuses two rows with {@code kAXErrorIllegalArgument}
     * and changes nothing. So one row is a click, {@code SELECT} (decision 10), which replaces; more
     * than one, or none, is the difference from what is selected now, {@code DESELECT} on each selected
     * row not written and {@code ADD_TO_SELECTION} on each written row not selected, and the write is
     * refused whole unless the row accepts every verb it needs (semantics 5). A single-select
     * container's rows publish no {@code ADD_TO_SELECTION} and no {@code DESELECT} (decision 20), so
     * there two rows are refused, as natively, and an empty array too, where a native outline that
     * allows an empty selection clears it.
     *
     * <p>The rows compared are the realized ones, the only ones a client can name: a selected row the
     * container has not realized is not deselected by a write that leaves it out (ADR 039 §4.1).
     *
     * @param grid      the row lookups
     * @param container the table, outline or list written to
     * @param written   the nodes the written elements stand for, {@code null} for one that stands for none
     * @return the verbs to post, in order, empty when the selection already is the written rows; or
     *         {@code null} when the write is refused
     */
    static List<RowSetting> forSelectedRows(AxGrid grid, AccessibleNode container, List<AccessibleNode> written) {
        if (!offers(grid, container, SELECTED_ROWS)) return null;
        List<AccessibleNode> rows = grid.selectionRows(container);
        for (AccessibleNode row : written) {
            if (row == null || !contains(rows, row)) return null;   // not a row of this container
        }
        if (written.size() == 1) {
            AccessibleNode row = written.get(0);
            if (row.accepts(Accessible.Action.SELECT)) {
                return List.of(new RowSetting(row.id(), Accessible.Action.SELECT));
            }
            return alreadyExactly(rows, written) ? List.of() : null;
        }
        List<RowSetting> settings = new ArrayList<>();
        for (AccessibleNode row : rows) {
            boolean wanted = contains(written, row);
            boolean selected = row.has(Accessible.State.SELECTED);
            if (selected && !wanted) {
                if (!row.accepts(Accessible.Action.DESELECT)) return null;
                settings.add(new RowSetting(row.id(), Accessible.Action.DESELECT));
            }
        }
        for (AccessibleNode row : rows) {
            if (!contains(written, row) || row.has(Accessible.State.SELECTED)) continue;
            if (!row.accepts(Accessible.Action.ADD_TO_SELECTION)) return null;
            settings.add(new RowSetting(row.id(), Accessible.Action.ADD_TO_SELECTION));
        }
        return settings;
    }

    private static boolean contains(List<AccessibleNode> nodes, AccessibleNode node) {
        for (AccessibleNode each : nodes) {
            if (each != null && each.id() == node.id()) return true;
        }
        return false;
    }

    private static boolean alreadyExactly(List<AccessibleNode> rows, List<AccessibleNode> written) {
        for (AccessibleNode row : rows) {
            if (row.has(Accessible.State.SELECTED) != contains(written, row)) return false;
        }
        return true;
    }

    /**
     * What {@code setAccessibilityValue:} posts. A text node takes a string as its whole new text; a
     * node with a writable value takes a number as the value and a string as the value's text, which
     * the widget parses (semantics 5: "a ValueFacet's SetValue posts SET_VALUE OfText"). A string goes
     * to the text first where a node has both.
     *
     * @param node   the node written to
     * @param text   the written object's text when it is a string, else {@code null}
     * @param number the written object's number when it is a number, else {@code null}
     * @return the verb to post, or {@code null} when the node accepts no write of that kind
     */
    static Setting forValue(AccessibleNode node, String text, Double number) {
        if (text != null && node.accepts(Accessible.Action.SET_TEXT)) {
            return new Setting(Accessible.Action.SET_TEXT, new Accessible.Argument.OfText(text));
        }
        if (!node.accepts(Accessible.Action.SET_VALUE)) return null;
        if (text != null) return new Setting(Accessible.Action.SET_VALUE, new Accessible.Argument.OfText(text));
        if (number != null && Double.isFinite(number)) {
            return new Setting(Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(number));
        }
        return null;
    }
}
