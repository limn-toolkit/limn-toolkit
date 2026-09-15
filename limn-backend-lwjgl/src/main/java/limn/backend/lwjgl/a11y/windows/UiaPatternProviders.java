package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pattern interfaces themselves: what a client calls when it operates a control.
 *
 * <p>Everything a pattern <em>reads</em> comes from the facet the walk published, and everything it
 * <em>does</em> goes back through the scene's own dispatcher to the widget's own private path — the
 * one a click takes, with the widget's own enabled guard and the widget's own notification. No
 * component gains a public {@code click()}, which is §1.5's rule and the reason an assistive
 * technology's toggle tells an application exactly what a user's does.
 *
 * <p><b>A verb answers as soon as it is accepted.</b> The call cannot wait for the user-interface
 * thread: a client calls on an RPC thread and blocking it is how a screen reader stops responding
 * to the person using it. So {@code S_OK} here means the request was understood and posted, and
 * what the widget did about it arrives as an event.
 */
final class UiaPatternProviders {

    private UiaPatternProviders() {
    }

    /**
     * @param patternId one of {@link UiaIds}' pattern ids
     * @return the interface that pattern is served through, or {@code null} for one this bridge
     *         does not serve
     */
    static UiaInterfaces.Vtable interfaceFor(int patternId) {
        return switch (patternId) {
            case UiaIds.INVOKE_PATTERN -> UiaInterfaces.INVOKE_PROVIDER;
            case UiaIds.TOGGLE_PATTERN -> UiaInterfaces.TOGGLE_PROVIDER;
            case UiaIds.VALUE_PATTERN -> UiaInterfaces.VALUE_PROVIDER;
            case UiaIds.RANGE_VALUE_PATTERN -> UiaInterfaces.RANGE_VALUE_PROVIDER;
            case UiaIds.SELECTION_PATTERN -> UiaInterfaces.SELECTION_PROVIDER;
            case UiaIds.EXPAND_COLLAPSE_PATTERN -> UiaInterfaces.EXPAND_COLLAPSE_PROVIDER;
            case UiaIds.SCROLL_PATTERN -> UiaInterfaces.SCROLL_PROVIDER;
            case UiaIds.SELECTION_ITEM_PATTERN -> UiaInterfaces.SELECTION_ITEM_PROVIDER;
            case UiaIds.SCROLL_ITEM_PATTERN -> UiaInterfaces.SCROLL_ITEM_PROVIDER;
            case UiaIds.GRID_PATTERN -> UiaInterfaces.GRID_PROVIDER;
            case UiaIds.GRID_ITEM_PATTERN -> UiaInterfaces.GRID_ITEM_PROVIDER;
            case UiaIds.TABLE_PATTERN -> UiaInterfaces.TABLE_PROVIDER;
            case UiaIds.TABLE_ITEM_PATTERN -> UiaInterfaces.TABLE_ITEM_PROVIDER;
            default -> null;
        };
    }

    /**
     * @param patternId which pattern
     * @param nodeId    which node
     * @param context   what the slots read and act through
     * @return that pattern's slots by name, or {@code null} for a pattern this bridge does not
     *         serve
     */
    static Map<String, CallbackI> slotsFor(int patternId, long nodeId,
                                           UiaProvider.Context context) {
        Map<String, CallbackI> slots = new LinkedHashMap<>();
        switch (patternId) {
            // Semantics 5: every verb below is posted only when the node publishes it now, read
            // through AccessibleNode#accepts on the snapshot of the call, because the pointer a
            // client holds outlives the snapshot that vended the pattern (a button disabled since,
            // a row under an overlay). Until 2026-09-15 each was posted whatever the node published
            // and the client was told S_OK for a verb the widget then refused (W6, WINDOWS-NEW-10).
            case UiaIds.INVOKE_PATTERN -> slots.put("Invoke",
                    (UiaCom.P) self -> postFirstAccepted(context, nodeId, Accessible.Action.PRESS));

            case UiaIds.TOGGLE_PATTERN -> {
                slots.put("Toggle", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.TOGGLE));
                slots.put("get_ToggleState", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.toggle() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The platform's numbering: off, on, then the third state, which the toolkit
                    // calls MIXED and every check box with a partially chosen group carries.
                    ToggleFacet.State state = node.toggle().state();
                    MemoryUtil.memPutInt(out, switch (state) {
                        case OFF -> 0;
                        case ON -> 1;
                        case MIXED -> 2;
                    });
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.VALUE_PATTERN -> {
                slots.put("SetValue", (UiaCom.PP) (self, text) -> {
                    // Semantics 5 (CRIT-7): the whole text of a text facet is SET_TEXT; the
                    // spoken form of a value facet -- a spinner's "07:30", a combo's item -- is
                    // SET_VALUE by text, which the widget parses. Until 2026-09-15 both posted
                    // SET_TEXT, which a value widget refuses.
                    AccessibleNode node = context.tree().find(nodeId);
                    Accessible.Action setter = node != null && node.text() != null
                            ? Accessible.Action.SET_TEXT : Accessible.Action.SET_VALUE;
                    return postSetter(context, nodeId, setter,
                            new Accessible.Argument.OfText(bstrOf(text)));
                });
                slots.put("get_Value", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The text facet's contents, or the value facet's spoken form -- the mask and
                    // never the secret on a password field, which is the facet's own doing.
                    String value = node.text() != null ? node.text().text()
                            : node.value() != null ? node.value().text() : "";
                    MemoryUtil.memPutAddress(out,
                            context.strings().allocate(value == null ? "" : value));
                    return UiaIds.S_OK;
                });
                slots.put("get_IsReadOnly", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The facet's writability, the fact the setter gate reads: the READ_ONLY state
                    // is a text facet's own flag and, for a value facet, derived from its readOnly
                    // (Accessibility#value sets the state with it), so one read answers both.
                    putBool(out, node.has(Accessible.State.READ_ONLY));
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.RANGE_VALUE_PATTERN -> {
                slots.put("SetValue", (UiaCom.PD) (self, value) -> postSetter(context, nodeId,
                        Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(value)));
                slots.put("get_Value", number(nodeId, context, node -> node.value().value()));
                slots.put("get_Maximum", number(nodeId, context, node -> node.value().max()));
                slots.put("get_Minimum", number(nodeId, context, node -> node.value().min()));
                slots.put("get_SmallChange", number(nodeId, context, node -> node.value().step()));
                // No separate large step in the model, so the two are the same number rather than
                // an invented multiple: a client offering "page up" moves what the widget's own
                // step moves, which is what its keyboard does.
                slots.put("get_LargeChange", number(nodeId, context, node -> node.value().step()));
                slots.put("get_IsReadOnly", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.value() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.value().readOnly());
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.EXPAND_COLLAPSE_PATTERN -> {
                // The pattern stays vended from the facet, because its state is what a reader reads;
                // the two verbs are the node's to publish by state (EXPAND on a closed node,
                // COLLAPSE on an open one) and a verb it does not publish is refused.
                slots.put("Expand", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.EXPAND));
                slots.put("Collapse", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.COLLAPSE));
                slots.put("get_ExpandCollapseState", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.expand() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // Collapsed, expanded. Never LeafNode: this bridge publishes the pattern only
                    // where the facet is, and a widget with nothing to expand has no facet.
                    MemoryUtil.memPutInt(out, node.expand().expanded() ? 1 : 0);
                    return UiaIds.S_OK;
                });
            }

            // ISelectionProvider (W1's Selection half; semantics 1): the container's members are the
            // realized nodes whose selection container, resolved once at publish, is this node.
            case UiaIds.SELECTION_PATTERN -> {
                slots.put("GetSelection", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    int container = tree.indexOf(nodeId);
                    if (container < 0 || tree.node(container).selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    long[] pointers = selectedMembersOf(tree, container, context);
                    MemoryUtil.memPutAddress(out, context.unknownArray(pointers));
                    return UiaIds.S_OK;
                });
                slots.put("get_CanSelectMultiple", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selection().multiSelectable());
                    return UiaIds.S_OK;
                });
                slots.put("get_IsSelectionRequired", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selection().required());
                    return UiaIds.S_OK;
                });
            }

            // Decision 10 and semantics 5's candidate lists: Select is a click, SELECT; "add" is
            // ADD_TO_SELECTION where the container offers it and a click where it does not (a
            // single-select container's only way to add is to select); remove is DESELECT. The
            // first verb the node publishes is posted, and a node publishing none is refused
            // synchronously rather than told S_OK for a verb its widget will refuse.
            case UiaIds.SELECTION_ITEM_PATTERN -> {
                slots.put("Select", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.SELECT));
                slots.put("AddToSelection", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.ADD_TO_SELECTION, Accessible.Action.SELECT));
                slots.put("RemoveFromSelection", (UiaCom.P) self -> postFirstAccepted(context,
                        nodeId, Accessible.Action.DESELECT));
                slots.put("get_IsSelected", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selectionItem() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selectionItem().selected());
                    return UiaIds.S_OK;
                });
                slots.put("get_SelectionContainer", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode item = tree.find(nodeId);
                    if (item == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The member's container by semantics 1, resolved once at publish: the nearest
                    // ancestor with a selection facet, climbed to through synthetic ancestors only
                    // (a calendar day's grid past its week row), and none for a member that
                    // declared itself containerless or whose climb met a widget first. The same
                    // rule GetSelection, the model's SELECTION_CHANGED and the other two bridges
                    // read. Until 2026-09-15 this climbed through any ancestor.
                    int at = item.selectionContainer();
                    // The simple interface: get_SelectionContainer's declared out type.
                    MemoryUtil.memPutAddress(out, at == AccessibleNode.NONE ? 0
                            : context.simpleElementFor(tree.node(at).id()));
                    return UiaIds.S_OK;
                });
            }

            // Vended only on a node that publishes the verb (UiaPatterns), and gated on it here as
            // well, because the pointer a client holds outlives the snapshot that vended it.
            case UiaIds.SCROLL_ITEM_PATTERN -> slots.put("ScrollIntoView",
                    (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                            Accessible.Action.SCROLL_INTO_VIEW));

            // IScrollProvider (W1's Scroll half; decision 39). What it reads is the scroll facet;
            // what it does is the node's own scroll bars' published verbs, because the model has no
            // verb that scrolls a container by an amount. The getters and the refusals follow the
            // platform's own ScrollViewerAutomationPeer, read as IL on the guest 2026-09-15
            // (readings/windows-dump-uia-provider-conventions.txt §1): a percent is NoScroll on an
            // axis that cannot scroll, a view size is a percent whatever the axis.
            case UiaIds.SCROLL_PATTERN -> {
                slots.put("Scroll", (UiaCom.PII) (self, horizontal, vertical) ->
                        scroll(context, nodeId, horizontal, vertical));
                slots.put("SetScrollPercent", (UiaCom.PDD) (self, horizontal, vertical) ->
                        setScrollPercent(context, nodeId, horizontal, vertical));
                slots.put("get_HorizontalScrollPercent", scrollNumber(nodeId, context,
                        s -> s.horizontallyScrollable() ? s.horizontalPercent() * 100
                                : UiaIds.SCROLL_NO_SCROLL));
                slots.put("get_VerticalScrollPercent", scrollNumber(nodeId, context,
                        s -> s.verticallyScrollable() ? s.verticalPercent() * 100
                                : UiaIds.SCROLL_NO_SCROLL));
                // The facet already holds a view size to one and says one for nothing to scroll,
                // which is the platform's 100 for an empty extent.
                slots.put("get_HorizontalViewSize", scrollNumber(nodeId, context,
                        s -> s.horizontalViewSize() * 100));
                slots.put("get_VerticalViewSize", scrollNumber(nodeId, context,
                        s -> s.verticalViewSize() * 100));
                slots.put("get_HorizontallyScrollable", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.scroll() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.scroll().horizontallyScrollable());
                    return UiaIds.S_OK;
                });
                slots.put("get_VerticallyScrollable", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.scroll() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.scroll().verticallyScrollable());
                    return UiaIds.S_OK;
                });
            }

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
                return null;
            }
        }
        return slots;
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

    /**
     * Writes a {@code BOOL*} out-parameter: four bytes, as the guest read them
     * ({@link UiaIds#BOOL_TRUE}). Never {@link UiaVariant#TRUE}, which is a {@code VARIANT}'s.
     */
    static void putBool(long out, boolean value) {
        MemoryUtil.memPutInt(out, value ? UiaIds.BOOL_TRUE : UiaIds.BOOL_FALSE);
    }

    /** A getter answering one double off the value facet. */
    private static CallbackI number(long nodeId, UiaProvider.Context context,
                                    java.util.function.ToDoubleFunction<AccessibleNode> of) {
        return (UiaCom.PP) (self, out) -> {
            AccessibleNode node = context.tree().find(nodeId);
            if (node == null || node.value() == null) {
                return UiaIds.E_ELEMENT_NOT_AVAILABLE;
            }
            MemoryUtil.memPutDouble(out, of.applyAsDouble(node));
            return UiaIds.S_OK;
        };
    }

    /** A getter answering one double off the scroll facet. */
    private static CallbackI scrollNumber(long nodeId, UiaProvider.Context context,
                                          java.util.function.ToDoubleFunction<limn.accessibility.ScrollFacet> of) {
        return (UiaCom.PP) (self, out) -> {
            AccessibleNode node = context.tree().find(nodeId);
            if (node == null || node.scroll() == null) {
                return UiaIds.E_ELEMENT_NOT_AVAILABLE;
            }
            MemoryUtil.memPutDouble(out, of.applyAsDouble(node.scroll()));
            return UiaIds.S_OK;
        };
    }

    /**
     * The scroll bar a scrolling node publishes for one axis: its direct {@code SCROLL_BAR} child
     * carrying that orientation, which is where every scrolling widget hangs its bars (a bar with
     * nothing to scroll publishes no node at all).
     *
     * @return the bar, or {@code null} when the node publishes none for that axis
     */
    static AccessibleNode scrollBarOf(AccessibleTree tree, AccessibleNode node, boolean vertical) {
        Accessible.State orientation = vertical ? Accessible.State.VERTICAL
                : Accessible.State.HORIZONTAL;
        for (int child = node.firstChild(); child != AccessibleNode.NONE;
                child = tree.node(child).nextSibling()) {
            AccessibleNode candidate = tree.node(child);
            if (candidate.role() == Accessible.Role.SCROLL_BAR && candidate.has(orientation)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code IScrollProvider::Scroll} (decision 39): each axis's {@code ScrollAmount} becomes the
     * stepping verb that axis's scroll bar publishes, and the call is refused synchronously when an
     * axis asked to move has no bar or its bar publishes no such verb.
     *
     * <p>A small step is {@code INCREMENT} or {@code DECREMENT}. A large one is the bar's page verb
     * "if published", and the model has none ({@link Accessible.Action} has no page verb), so it is
     * refused; {@code NoAmount} leaves the axis alone. The order of the refusals is the platform's
     * own provider's, read as IL (readings/windows-dump-uia-provider-conventions.txt §1,
     * {@code ScrollViewerAutomationPeer.Scroll}): a node that is not enabled first
     * ({@code UIA_E_ELEMENTNOTENABLED}), then an axis asked to move that cannot scroll, then an
     * amount it cannot perform ({@code InvalidOperationException}); both axes are checked before
     * either is posted, so a refused call scrolls neither.
     *
     * @return {@code S_OK} when every step asked for was posted and accepted
     */
    static int scroll(UiaProvider.Context context, long nodeId, int horizontal, int vertical) {
        AccessibleTree tree = context.tree();
        AccessibleNode node = tree.find(nodeId);
        if (node == null || node.scroll() == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!node.has(Accessible.State.ENABLED)) {
            return UiaIds.E_ELEMENT_NOT_ENABLED;
        }
        boolean moveH = horizontal != UiaIds.SCROLL_AMOUNT_NO_AMOUNT;
        boolean moveV = vertical != UiaIds.SCROLL_AMOUNT_NO_AMOUNT;
        if ((moveH && !node.scroll().horizontallyScrollable())
                || (moveV && !node.scroll().verticallyScrollable())) {
            return UiaIds.E_INVALID_OPERATION;
        }
        AccessibleNode barH = moveH ? scrollBarOf(tree, node, false) : null;
        AccessibleNode barV = moveV ? scrollBarOf(tree, node, true) : null;
        Accessible.Action stepH = moveH ? stepFor(horizontal) : null;
        Accessible.Action stepV = moveV ? stepFor(vertical) : null;
        int refused = refusedStep(moveH, barH, stepH);
        if (refused == UiaIds.S_OK) {
            refused = refusedStep(moveV, barV, stepV);
        }
        if (refused != UiaIds.S_OK) {
            return refused;
        }
        boolean accepted = true;
        if (moveH) {
            accepted = context.perform(barH.id(), stepH, Accessible.Argument.NONE);
        }
        if (moveV) {
            accepted &= context.perform(barV.id(), stepV, Accessible.Argument.NONE);
        }
        return accepted(accepted);
    }

    /** @return the model verb a {@code ScrollAmount} steps by, or {@code null} for none */
    private static Accessible.Action stepFor(int amount) {
        return switch (amount) {
            case UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT -> Accessible.Action.INCREMENT;
            case UiaIds.SCROLL_AMOUNT_SMALL_DECREMENT -> Accessible.Action.DECREMENT;
            // LargeIncrement and LargeDecrement: the page verbs decision 39 routes them to do not
            // exist in the model, so nothing publishes them. Anything else is not an amount.
            default -> null;
        };
    }

    /** @return {@code S_OK} when an axis asked to move has a bar that accepts its step now */
    private static int refusedStep(boolean move, AccessibleNode bar, Accessible.Action step) {
        if (!move) {
            return UiaIds.S_OK;
        }
        if (bar == null || step == null) {
            return UiaIds.E_INVALID_OPERATION;
        }
        return bar.accepts(step) ? UiaIds.S_OK : refusal(bar);
    }

    /**
     * {@code IScrollProvider::SetScrollPercent} (decision 39): each axis's percent becomes a
     * {@code SET_VALUE} on that axis's scroll bar, the bar's own range scaled by the percent, where
     * the bar accepts one now (a writable value on an enabled bar, semantics 5).
     *
     * <p>{@code NoScroll} leaves an axis alone. The refusals come in the platform's own provider's
     * order (readings/windows-dump-uia-provider-conventions.txt §1,
     * {@code ScrollViewerAutomationPeer.SetScrollPercent}): not enabled
     * ({@code UIA_E_ELEMENTNOTENABLED}); an axis given a percent that cannot scroll
     * ({@code InvalidOperationException}); a percent outside 0..100 ({@code
     * ArgumentOutOfRangeException}, §2's 0x80131502; a percent that is not a number is refused the
     * same way here, where the platform's unordered comparison lets it through to a scroll of
     * nothing); then, this bridge's own, an axis with no bar or a bar that takes no value. Both
     * axes pass every check before either is posted.
     *
     * @return {@code S_OK} when every value asked for was posted and accepted
     */
    static int setScrollPercent(UiaProvider.Context context, long nodeId, double horizontal,
                                double vertical) {
        AccessibleTree tree = context.tree();
        AccessibleNode node = tree.find(nodeId);
        if (node == null || node.scroll() == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!node.has(Accessible.State.ENABLED)) {
            return UiaIds.E_ELEMENT_NOT_ENABLED;
        }
        boolean moveH = horizontal != UiaIds.SCROLL_NO_SCROLL;
        boolean moveV = vertical != UiaIds.SCROLL_NO_SCROLL;
        if ((moveH && !node.scroll().horizontallyScrollable())
                || (moveV && !node.scroll().verticallyScrollable())) {
            return UiaIds.E_INVALID_OPERATION;
        }
        if ((moveH && !(horizontal >= 0 && horizontal <= 100))
                || (moveV && !(vertical >= 0 && vertical <= 100))) {
            return UiaIds.E_ARGUMENT_OUT_OF_RANGE;
        }
        AccessibleNode barH = moveH ? scrollBarOf(tree, node, false) : null;
        AccessibleNode barV = moveV ? scrollBarOf(tree, node, true) : null;
        int refused = refusedValue(moveH, barH);
        if (refused == UiaIds.S_OK) {
            refused = refusedValue(moveV, barV);
        }
        if (refused != UiaIds.S_OK) {
            return refused;
        }
        boolean accepted = true;
        if (moveH) {
            accepted = context.perform(barH.id(), Accessible.Action.SET_VALUE,
                    new Accessible.Argument.OfValue(valueAtPercent(barH, horizontal)));
        }
        if (moveV) {
            accepted &= context.perform(barV.id(), Accessible.Action.SET_VALUE,
                    new Accessible.Argument.OfValue(valueAtPercent(barV, vertical)));
        }
        return accepted(accepted);
    }

    /** @return {@code S_OK} when an axis given a percent has a bar that accepts a value now */
    private static int refusedValue(boolean move, AccessibleNode bar) {
        if (!move) {
            return UiaIds.S_OK;
        }
        if (bar == null || bar.value() == null) {
            return UiaIds.E_INVALID_OPERATION;
        }
        return bar.accepts(Accessible.Action.SET_VALUE) ? UiaIds.S_OK : refusal(bar);
    }

    /** @return the bar's value that percent of the way from its minimum to its maximum */
    private static double valueAtPercent(AccessibleNode bar, double percent) {
        double min = bar.value().min();
        return min + (bar.value().max() - min) * percent / 100;
    }

    /**
     * Posts a setter the node accepts now, and refuses it synchronously otherwise (semantics 5 as
     * amended 2026-09-15, through {@link AccessibleNode#accepts}): a writable value facet implies
     * {@code SET_VALUE} and a text facet on a node that is not {@code READ_ONLY} implies
     * {@code SET_TEXT}, each only on an {@code ENABLED} node. Replaces fix round 2e's
     * {@code refusedSetter}, which refused on the enabled bit alone and posted a setter to a
     * read-only facet.
     *
     * <p>A node that is not {@code ENABLED} is refused with {@code UIA_E_ELEMENTNOTENABLED}, before
     * anything else, as the platform's own providers do ({@link #refusal}); its
     * {@code IsReadOnly} stays the facet's truth (ADR 039 §1.2: enabled and read-only are never
     * conflated).
     *
     * @param context what to read and post through
     * @param nodeId  the node the pattern was vended for
     * @param setter  {@code SET_VALUE} or {@code SET_TEXT}
     * @param arg     what it carries
     * @return {@code S_OK} when posted and accepted by the scene, otherwise the refusal
     */
    static int postSetter(UiaProvider.Context context, long nodeId, Accessible.Action setter,
                          Accessible.Argument arg) {
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!node.accepts(setter)) {
            return refusal(node);
        }
        return accepted(context.perform(nodeId, setter, arg));
    }

    /**
     * What a verb or a setter the node does not accept is refused with: {@code
     * UIA_E_ELEMENTNOTENABLED} (0x80040200) on a node that is not {@code ENABLED} -- disabled, under
     * a disabled ancestor, or outside the layer that owns input -- and {@code 0x80131509} on one
     * that is enabled and simply does not offer it.
     *
     * <p>The order is the platform's own providers', read as IL on the guest 2026-09-15
     * (readings/windows-dump-uia-provider-conventions.txt §1b): {@code ButtonAutomationPeer.Invoke},
     * {@code ToggleButtonAutomationPeer.Toggle}, {@code ExpanderAutomationPeer} and
     * {@code TreeViewItemAutomationPeer}'s {@code Expand}/{@code Collapse},
     * {@code SelectorItemAutomationPeer}'s {@code Select}/{@code AddToSelection}/
     * {@code RemoveFromSelection}, {@code TextBoxAutomationPeer.SetValue} and
     * {@code RangeBaseAutomationPeer.SetValue} all begin {@code call AutomationPeer::IsEnabled();
     * brtrue; newobj ElementNotEnabledException; throw}, and only then throw
     * {@code InvalidOperationException} for what they cannot do.
     *
     * <p>{@code SetFocus} and {@code ScrollIntoView} were read separately, the same day
     * (readings/windows-dump-uia-focus-and-scroll-item.txt, {@code
     * scripts/a11y/windows/dump-uia-focus-and-scroll-item.ps1}), and the platform's providers do not
     * agree on them. The client-side proxies of the Win32 controls follow the order above:
     * {@code ProxySimple}'s {@code IRawElementProviderFragment.SetFocus} throws
     * {@code ElementNotEnabledException} when the window is not enabled and
     * {@code InvalidOperationException} when the element is not keyboard-focusable, and
     * {@code ListViewItem}'s and {@code WindowsTabItem}'s {@code ScrollIntoView} throw
     * {@code ElementNotEnabledException} before {@code InvalidOperationException} for a container
     * that cannot scroll. WPF checks no enabled bit for either: {@code ElementProxy.SetFocus}
     * reaches {@code UIElementAutomationPeer.SetFocusCore}, which throws
     * {@code InvalidOperationException} when {@code UIElement.Focus()} refuses, and its item peers'
     * {@code ScrollIntoView} scroll whatever the item's state, as do the list-box and tree-view item
     * proxies. This bridge answers both the way it answers every other verb, which is the Win32
     * proxies' order.
     */
    static int refusal(AccessibleNode node) {
        return node.has(Accessible.State.ENABLED) ? UiaIds.E_INVALID_OPERATION
                : UiaIds.E_ELEMENT_NOT_ENABLED;
    }

    /**
     * The simple pointers of a container's realized selected members, in reading order: every node
     * of the snapshot carrying a selected {@code SelectionItemFacet} whose resolved selection
     * container is this one. A selected member the widget has not realized (a row scrolled far
     * away) has no node and is not listed, the degradation ADR 039 §4.1 accepts.
     */
    private static long[] selectedMembersOf(AccessibleTree tree, int container,
                                            UiaProvider.Context context) {
        int count = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.selectionContainer() == container && node.selectionItem().selected()) {
                count++;
            }
        }
        long[] pointers = new long[count];
        int at = 0;
        for (int i = 0; i < tree.nodeCount() && at < count; i++) {
            AccessibleNode node = tree.node(i);
            if (node.selectionContainer() == container && node.selectionItem().selected()) {
                pointers[at++] = context.simpleElementFor(node.id());
            }
        }
        return pointers;
    }

    /**
     * Posts the first of an ordered candidate list the node accepts now (semantics 5, read through
     * {@link AccessibleNode#accepts}), and refuses synchronously when it accepts none.
     *
     * @param context    what to read and post through
     * @param nodeId     the node the pattern was vended for
     * @param candidates the verbs in the order the platform entry point maps them
     * @return {@code S_OK} when posted and accepted by the scene; {@code E_ELEMENT_NOT_AVAILABLE}
     *         for a node gone from the snapshot (or refused by the scene, which is what a refusal
     *         there almost always is); for a node that publishes none of the candidates,
     *         {@link #refusal}'s answer: {@code E_ELEMENT_NOT_ENABLED} when it is not
     *         {@code ENABLED}, {@code E_INVALID_OPERATION} otherwise
     */
    static int postFirstAccepted(UiaProvider.Context context, long nodeId,
                                 Accessible.Action... candidates) {
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        for (Accessible.Action candidate : candidates) {
            if (node.accepts(candidate)) {
                return accepted(context.perform(nodeId, candidate, Accessible.Argument.NONE));
            }
        }
        return refusal(node);
    }

    /**
     * @param wasAccepted whether the scene took the request
     * @return {@code S_OK}, or the code §1.3 gives a client holding an element for a node that has
     *         gone — which is what a refusal here almost always is
     */
    private static int accepted(boolean wasAccepted) {
        return wasAccepted ? UiaIds.S_OK : UiaIds.E_ELEMENT_NOT_AVAILABLE;
    }

    /** Reads a {@code BSTR} a client passed in, which is length-prefixed UTF-16. */
    private static String bstrOf(long bstr) {
        if (bstr == 0) {
            return "";
        }
        int bytes = MemoryUtil.memGetInt(bstr - 4);
        StringBuilder text = new StringBuilder(Math.max(0, bytes / 2));
        for (int i = 0; i < bytes / 2; i++) {
            text.append((char) MemoryUtil.memGetShort(bstr + (long) i * 2));
        }
        return text.toString();
    }
}
