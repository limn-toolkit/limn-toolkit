package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.putBool;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postSetter;

/**
 * The {@code VALUE} shape's half of the Windows bridge (ADR 045 §5): a number in a range: RangeValue, its setter, its bounds and its step.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaValueShape {

    private UiaValueShape() {
    }

    /** The patterns this shape owns: RANGE_VALUE_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
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
            default -> {
            }
        }
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
}
