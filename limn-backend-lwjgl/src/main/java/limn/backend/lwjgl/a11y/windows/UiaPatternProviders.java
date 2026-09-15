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
            case UiaIds.EXPAND_COLLAPSE_PATTERN -> UiaInterfaces.EXPAND_COLLAPSE_PROVIDER;
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
            case UiaIds.INVOKE_PATTERN -> slots.put("Invoke",
                    (UiaCom.P) self -> accepted(context.perform(nodeId, Accessible.Action.PRESS,
                            Accessible.Argument.NONE)));

            case UiaIds.TOGGLE_PATTERN -> {
                slots.put("Toggle", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.TOGGLE,
                                Accessible.Argument.NONE)));
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
                    // Fix round 2e's minimal refusal; phase 3 replaces it with the full gate.
                    int refused = refusedSetter(context.tree().find(nodeId));
                    return refused != UiaIds.S_OK ? refused : accepted(
                            context.perform(nodeId, Accessible.Action.SET_TEXT,
                                    new Accessible.Argument.OfText(bstrOf(text))));
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
                    putBool(out, node.has(Accessible.State.READ_ONLY));
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.RANGE_VALUE_PATTERN -> {
                slots.put("SetValue", (UiaCom.PD) (self, value) -> {
                    // Fix round 2e's minimal refusal; phase 3 replaces it with the full gate.
                    int refused = refusedSetter(context.tree().find(nodeId));
                    return refused != UiaIds.S_OK ? refused : accepted(
                            context.perform(nodeId, Accessible.Action.SET_VALUE,
                                    new Accessible.Argument.OfValue(value)));
                });
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
                slots.put("Expand", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.EXPAND,
                                Accessible.Argument.NONE)));
                slots.put("Collapse", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.COLLAPSE,
                                Accessible.Argument.NONE)));
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

            case UiaIds.SELECTION_ITEM_PATTERN -> {
                slots.put("Select", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.SELECT,
                                Accessible.Argument.NONE)));
                slots.put("AddToSelection", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.SELECT,
                                Accessible.Argument.NONE)));
                slots.put("RemoveFromSelection", (UiaCom.P) self -> accepted(
                        context.perform(nodeId, Accessible.Action.DESELECT,
                                Accessible.Argument.NONE)));
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
                    // The nearest ancestor that carries a selection, which is the list or the group
                    // this item belongs to. Not simply the parent: a row inside a padding inside a
                    // list would name the padding.
                    long container = 0;
                    for (int at = item.parent(); at != AccessibleNode.NONE;
                            at = tree.node(at).parent()) {
                        if (tree.node(at).selection() != null) {
                            // The simple interface: get_SelectionContainer's declared out type.
                            container = context.simpleElementFor(tree.node(at).id());
                            break;
                        }
                    }
                    MemoryUtil.memPutAddress(out, container);
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.SCROLL_ITEM_PATTERN -> slots.put("ScrollIntoView",
                    (UiaCom.P) self -> accepted(context.perform(nodeId,
                            Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE)));

            // ADR 041 §7. Cells are answered from what the walk published: a row the table has not
            // realized has no node, so GetItem on it answers null, the degradation ADR 039 §4.1
            // accepts. Column headers are the header group's children; row headers are none.
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
                    List<AccessibleNode> headers = table == null ? List.of()
                            : columnHeadersOf(tree, table);
                    int column = cell.cell().column();
                    long[] pointers = column >= 0 && column < headers.size()
                            ? new long[] {context.simpleElementFor(headers.get(column).id())}
                            : new long[0];
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

    /** The nearest ancestor of {@code node} that is a table, itself included; null when none. */
    private static AccessibleNode tableOf(AccessibleTree tree, AccessibleNode node) {
        for (AccessibleNode at = node; at != null; ) {
            if (at.table() != null) {
                return at;
            }
            int parent = at.parent();
            at = parent == AccessibleNode.NONE ? null : tree.node(parent);
        }
        return null;
    }

    /** The header group's children: the table's first group child's children, in order. */
    private static List<AccessibleNode> columnHeadersOf(AccessibleTree tree, AccessibleNode table) {
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.GROUP) {
                return tree.children(child);
            }
        }
        return List.of();
    }

    /** The realized cell shown at {@code row}, {@code column}; null when the row is unrealized. */
    private static AccessibleNode cellAt(AccessibleTree tree, AccessibleNode table, int row,
                                         int column) {
        for (AccessibleNode child : tree.children(table)) {
            if (child.role() == Accessible.Role.ROW && child.selectionItem() != null
                    && child.selectionItem().positionInSet() == row + 1) {
                for (AccessibleNode cell : tree.children(child)) {
                    if (cell.cell() != null && cell.cell().column() == column) {
                        return cell;
                    }
                }
                return null;
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

    /**
     * The minimal setter refusal fix round 2e owes this bridge ahead of phase 3 (semantics 5,
     * amended 2026-09-15): a node that is not {@code ENABLED} &mdash; disabled, under a disabled
     * ancestor, or outside the layer that owns input &mdash; accepts no setter, and the snapshot
     * says so with that bit alone, because its {@code IsReadOnly} stays the facet's truth (ADR 039
     * §1.2: enabled and read-only are never conflated). Phase 3 replaces this with the full
     * candidate gate ({@code AccessibleNode#accepts}: {@code Value.SetValue} posting
     * {@code SET_VALUE} by text on a value facet and {@code SET_TEXT} on a text facet, each gated
     * on the facet's writability as well); until then a writable-facet test is not added here, so
     * nothing that works on an operable node today changes.
     *
     * @param node the node the pattern was vended for, as the snapshot has it now
     * @return {@link UiaIds#S_OK} when the setter may be posted, otherwise the error to answer
     */
    private static int refusedSetter(AccessibleNode node) {
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        return node.has(Accessible.State.ENABLED) ? UiaIds.S_OK : UiaIds.E_INVALID_OPERATION;
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
