package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postFirstAccepted;

/**
 * The {@code POPUP_OWNER} shape's half of the Windows bridge: a node that opens something:
 * ExpandCollapse and its state.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaPopupOwnerShape {

    private UiaPopupOwnerShape() {
    }

    /** The patterns this shape owns: EXPAND_COLLAPSE_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
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
            default -> {
            }
        }
    }
}
