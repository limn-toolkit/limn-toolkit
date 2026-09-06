package limn.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
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
                    AccessibleNode node = nodeOf(nodeId, context);
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
                slots.put("SetValue", (UiaCom.PP) (self, text) -> accepted(
                        context.perform(nodeId, Accessible.Action.SET_TEXT,
                                new Accessible.Argument.OfText(bstrOf(text)))));
                slots.put("get_Value", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = nodeOf(nodeId, context);
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
                    AccessibleNode node = nodeOf(nodeId, context);
                    if (node == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    MemoryUtil.memPutShort(out,
                            node.has(Accessible.State.READ_ONLY) ? UiaVariant.TRUE
                                    : UiaVariant.FALSE);
                    return UiaIds.S_OK;
                });
            }

            case UiaIds.RANGE_VALUE_PATTERN -> {
                slots.put("SetValue", (UiaCom.PD) (self, value) -> accepted(
                        context.perform(nodeId, Accessible.Action.SET_VALUE,
                                new Accessible.Argument.OfValue(value))));
                slots.put("get_Value", number(nodeId, context, node -> node.value().value()));
                slots.put("get_Maximum", number(nodeId, context, node -> node.value().max()));
                slots.put("get_Minimum", number(nodeId, context, node -> node.value().min()));
                slots.put("get_SmallChange", number(nodeId, context, node -> node.value().step()));
                // No separate large step in the model, so the two are the same number rather than
                // an invented multiple: a client offering "page up" moves what the widget's own
                // step moves, which is what its keyboard does.
                slots.put("get_LargeChange", number(nodeId, context, node -> node.value().step()));
                slots.put("get_IsReadOnly", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = nodeOf(nodeId, context);
                    if (node == null || node.value() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    MemoryUtil.memPutShort(out,
                            node.value().readOnly() ? UiaVariant.TRUE : UiaVariant.FALSE);
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
                    AccessibleNode node = nodeOf(nodeId, context);
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
                    AccessibleNode node = nodeOf(nodeId, context);
                    if (node == null || node.selectionItem() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    MemoryUtil.memPutShort(out,
                            node.selectionItem().selected() ? UiaVariant.TRUE : UiaVariant.FALSE);
                    return UiaIds.S_OK;
                });
                slots.put("get_SelectionContainer", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    int index = tree.indexOf(nodeId);
                    if (index < 0) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The nearest ancestor that carries a selection, which is the list or the group
                    // this item belongs to. Not simply the parent: a row inside a padding inside a
                    // list would name the padding.
                    long container = 0;
                    for (int at = tree.node(index).parent(); at != AccessibleNode.NONE;
                            at = tree.node(at).parent()) {
                        if (tree.node(at).selection() != null) {
                            container = context.elementFor(tree.node(at).id());
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

            default -> {
                return null;
            }
        }
        return slots;
    }

    /** A getter answering one double off the value facet. */
    private static CallbackI number(long nodeId, UiaProvider.Context context,
                                    java.util.function.ToDoubleFunction<AccessibleNode> of) {
        return (UiaCom.PP) (self, out) -> {
            AccessibleNode node = nodeOf(nodeId, context);
            if (node == null || node.value() == null) {
                return UiaIds.E_ELEMENT_NOT_AVAILABLE;
            }
            MemoryUtil.memPutDouble(out, of.applyAsDouble(node));
            return UiaIds.S_OK;
        };
    }

    private static AccessibleNode nodeOf(long nodeId, UiaProvider.Context context) {
        AccessibleTree tree = context.tree();
        int index = tree.indexOf(nodeId);
        return index < 0 ? null : tree.node(index);
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
