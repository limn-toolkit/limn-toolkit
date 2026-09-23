package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import org.lwjgl.system.CallbackI;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postFirstAccepted;

/**
 * The {@code LEAF_ACTION} shape's half of the Windows bridge: a pressable leaf: Invoke, posted only
 * while the node publishes PRESS.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaLeafShape {

    private UiaLeafShape() {
    }

    /** The patterns this shape owns: INVOKE_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
            case UiaIds.INVOKE_PATTERN -> slots.put("Invoke",
                    (UiaCom.P) self -> postFirstAccepted(context, nodeId, Accessible.Action.PRESS));
            default -> {
            }
        }
    }
}
