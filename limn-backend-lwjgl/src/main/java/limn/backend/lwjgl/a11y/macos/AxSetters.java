package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The setter half: which of the toolkit's verbs a reader's write to an attribute means, and on
 * which nodes the attribute is settable at all.
 *
 * <p><b>Settable is NOT the gate's answer alone: a client is told settable unless the modern gate
 * AND the legacy {@code accessibilityIsAttributeSettable:} BOTH refuse.</b> Measured on the
 * guest 2026-09-16 in two passes ({@code readings/macos-gate-setter-probe-read.txt}, then
 * {@code readings/macos-settable-mechanism-serve.txt} and {@code -read.txt}: seven elements in one
 * window, every ask logged). The modern gate alone cannot say no, because when the class itself
 * <em>implements</em> the setter AppKit <b>discards the gate's NO and reports settable anyway</b> —
 * which is all six setters here, so a leaf row and a static text with no actions both answered
 * settable for every one of them. The legacy selector alone cannot say no either: the element whose
 * gate refused nothing was never asked it for a setter attribute at all, because a gate YES ends
 * the question. Only where both refuse does a client read {@code no}. So <b>the legacy selector is
 * what makes the telling equal the gate</b>, and it is answered from {@link AxGate#allows} so the
 * two cannot drift apart — not the modern gate doing it after all, which is the claim the design
 * and this javadoc withdrew earlier the same day.
 *
 * <p>The proof that per-element settability is expressible at all — which is why this is a hook and
 * not one Objective-C class per shape — is two instances of <b>one</b> class in that reading,
 * differing in nothing but the answers their nodes give: {@code AXDisclosing settable=no} on the
 * leaf, {@code settable=YES} on the branch, and {@code AXFocused settable=no} on both. That is what
 * a native {@code NSOutlineView}'s rows answer, one class and all (leaf {@code row#2} and
 * {@code row#5} false, collapsed branch {@code row#4} and open branches {@code row#0}/{@code row#1}
 * true, {@code readings/macos-outline-probe.txt}). The write path is untouched by it: in the same
 * pass the branch's {@code AXDisclosing} write entered {@code setAccessibilityDisclosed:} and the
 * leaf's did not.
 *
 * <p><b>Delivery was already right and still is</b>: a refused write returns {@code AXError(0)} and
 * the setter is never entered (a leaf's {@code AXDisclosing=YES} opened nothing while a branch's
 * {@code AXDisclosing=NO} really closed it, {@code macos-axwrite-outline.txt}). What was wrong was
 * only what a client was TOLD before it wrote, and that is what the hook fixes.
 *
 * <p>The 2026-09-13 reading this paragraph used to cite was not wrong about what it saw: every
 * element it read left the setters to {@code NSAccessibilityElement}, which is the one case AppKit
 * honours. Read on the macOS 26.6.2 guest, 2026-09-13
 * ({@code scripts/a11y/macos/selector-allowed-probe.swift}): {@code AXUIElementIsAttributeSettable}
 * asks {@code isAccessibilitySelectorAllowed:} about the <em>setter</em> selector, and with a gate that
 * says yes to it every attribute that has an {@code NSAccessibilityElement} setter reports settable —
 * {@code AXRole} included — and a write lands in {@code NSAccessibilityElement}'s own storage, where
 * nothing of the toolkit ever reads it. So a setter installed here is offered where its verb is
 * accepted <em>and</em> the attribute is one a native element of that shape carries, and every other
 * {@code setAccessibility…} selector is refused on every node ({@link AxGate}). The second half is not
 * pedantry: a row whose {@code AXFocused} was settable because the row accepts {@code FOCUS} — while a
 * native row carries no {@code AXFocused} at all — was measured as inviting the write VoiceOver makes
 * (2026-09-16; see {@link #offers}).
 *
 * <p><b>It did not stop the cursor fight, and the record says so.</b> Re-measured the same day with
 * a jar carrying this refusal: nine unrequested focus moves before, nine after. The write this
 * refuses is genuinely inert now, and VoiceOver never used it — every revert begins with
 * {@code AXSelectedRowsChanged} on the outline, through {@code setAccessibilitySelectedRows:},
 * which stays offered because a native container offers it. What drags the cursor is that a
 * {@code SELECT} moves it here and does not on AppKit, which is a model question and is open
 * ({@code readings/phase5-hear-the-fixes/macos-findings.txt}).
 *
 * <p><b>A write is posted, never waited for</b>: the setter returns nothing, and a verb the node
 * does not accept is not posted. The check is made again on the write itself, because a client may
 * send the setter without asking whether the attribute is settable first.
 */
final class AxSetters {

    private AxSetters() {
    }

    /** {@code setAccessibilityFocused:}: YES moves the keyboard, or the cursor, here — never on a row. */
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
     * The attribute a client asks {@code AXUIElementIsAttributeSettable} about, and the setter a
     * write to it would send — keyed by AppKit's exported symbol for the name, never by the name
     * itself, which {@link AxElementClass} resolves off the running AppKit and
     * {@code AxConstantsTest} holds against the dump.
     *
     * <p>That AppKit asks {@code accessibilityIsAttributeSettable:} with exactly these names, and
     * with the attribute name rather than the selector, is read and not assumed: the probe's log
     * carries {@code AXDisclosing}, {@code AXSelected}, {@code AXValue}, {@code AXFocused},
     * {@code AXExpanded} and {@code AXSelectedRows}, each asked of the element whose gate had just
     * refused its setter ({@code readings/macos-settable-mechanism-serve.txt}, 2026-09-16,
     * macOS 26.6.2).
     *
     * <p><b>An attribute that is not here is not settable</b>, and that is a measured answer rather
     * than a default. The same log shows AppKit asking this selector for {@code AXPosition} and
     * {@code AXElementBusy} on every element, settable or not — attributes with no modern setter
     * behind them — and the element that answered YES to those reported them settable, while the
     * control with no hook at all reported both {@code no}. So a hook that said yes to what it does
     * not know would hand a client two new lies in exchange for the ones it removes.
     */
    static final java.util.Map<String, String> ATTRIBUTE_SYMBOLS = java.util.Map.of(
            "NSAccessibilityFocusedAttribute", FOCUSED,
            "NSAccessibilitySelectedAttribute", SELECTED,
            "NSAccessibilityDisclosingAttribute", DISCLOSED,
            "NSAccessibilityExpandedAttribute", EXPANDED,
            "NSAccessibilityValueAttribute", VALUE,
            "NSAccessibilitySelectedRowsAttribute", SELECTED_ROWS);

    /** @return every AppKit symbol this class names, for {@code AxConstantsTest} to hold to the dump. */
    static java.util.Set<String> symbols() {
        return ATTRIBUTE_SYMBOLS.keySet();
    }

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
            // Everywhere the node takes the verb EXCEPT on a row, where a native row has no AXFocused
            // at all: an NSOutlineView row answers kAXErrorAttributeUnsupported for both the value and
            // its settability while the outline itself answers AXFocused=1 settable=true
            // (readings/macos-outline-probe.txt, every row of every pass), and an NSTableView row's
            // AXAttributeNames carries AXSelected and no AXFocused while the table's does carry it
            // (readings/macos-table-probe.txt). The view takes focus; its rows are selected. Offering
            // it on a row let VoiceOver's cursor sync write its own previous row back 40 ms after every
            // key and drag the application's cursor with it, so no tree script got past row index 2
            // (readings/phase5-macos/). A reader still moves the cursor the native way, by
            // writing AXSelected on the row or AXSelectedRows on the container, which both post SELECT.
            case FOCUSED -> node.accepts(Accessible.Action.FOCUS) && !grid.isRow(node);
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
     * <p>Read on the macOS 26.6.2 guest, 2026-09-15
     * ({@code scripts/a11y/macos/selection-writes-probe.swift}): a native outline's selection
     * becomes exactly the rows written, in either mode — one row replaces whatever was selected,
     * two rows in a multi-select outline become the selection, an empty array empties it — and a
     * single-select outline refuses two rows with {@code kAXErrorIllegalArgument} and changes
     * nothing. So one row is a click, {@code SELECT}, which replaces; more than one, or none, is
     * the difference from what is selected now, {@code DESELECT} on each selected row not written
     * and {@code ADD_TO_SELECTION} on each written row not selected, and the write is refused whole
     * unless the row accepts every verb it needs. A single-select container's rows publish no
     * {@code ADD_TO_SELECTION} and no {@code DESELECT}, so there two rows are refused, as natively,
     * and an empty array too, where a native outline that allows an empty selection clears it.
     *
     * <p>The rows compared are the realized ones, the only ones a client can name: a selected row the
     * container has not realized is not deselected by a write that leaves it out.
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
     * What {@code setAccessibilityValue:} posts. A text node takes a string as its whole new text;
     * a node with a writable value takes a number as the value and a string as the value's text,
     * which the widget parses. A string goes to the text first where a node has both.
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
