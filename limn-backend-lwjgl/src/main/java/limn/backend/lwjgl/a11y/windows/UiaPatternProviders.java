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
        if (bar.accepts(step)) {
            return UiaIds.S_OK;
        }
        return bar.has(Accessible.State.ENABLED) ? UiaIds.E_INVALID_OPERATION
                : UiaIds.E_ELEMENT_NOT_ENABLED;
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
        if (bar.accepts(Accessible.Action.SET_VALUE)) {
            return UiaIds.S_OK;
        }
        return bar.has(Accessible.State.ENABLED) ? UiaIds.E_INVALID_OPERATION
                : UiaIds.E_ELEMENT_NOT_ENABLED;
    }

    /** @return the bar's value that percent of the way from its minimum to its maximum */
    private static double valueAtPercent(AccessibleNode bar, double percent) {
        double min = bar.value().min();
        return min + (bar.value().max() - min) * percent / 100;
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
     *         there almost always is); {@code E_INVALID_OPERATION} for a node that publishes none
     *         of the candidates
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
        return UiaIds.E_INVALID_OPERATION;
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
