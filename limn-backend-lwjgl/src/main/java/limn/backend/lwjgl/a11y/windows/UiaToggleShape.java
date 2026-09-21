package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ToggleFacet;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postFirstAccepted;

/**
 * The {@code TOGGLE} shape's half of the Windows bridge (ADR 045 §5): a node that is on or off: Toggle and its state.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaToggleShape {

    private UiaToggleShape() {
    }

    /** The patterns this shape owns: TOGGLE_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
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
            default -> {
            }
        }
    }
}
