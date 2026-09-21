package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.putBool;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postSetter;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.bstrOf;

/**
 * The {@code TEXT} shape's half of the Windows bridge (ADR 045 §5): an editable string, and a value's spoken form: Value, its setter and its read-only bit (a spinner's "07:30" and a combo's item vend it too, because a reader prefers a string to a number where both are offered).
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaTextShape {

    private UiaTextShape() {
    }

    /** The patterns this shape owns: VALUE_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
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
            default -> {
            }
        }
    }
}
