package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.putBool;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.refusal;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.accepted;

/**
 * The {@code SURFACE} shape's half of the Windows bridge (ADR 045 §5): a scrolling container: Scroll, answered from the scroll facet and performed through the node's own scroll bars' published verbs.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaSurfaceShape {

    private UiaSurfaceShape() {
    }

    /** The patterns this shape owns: SCROLL_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
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
            default -> {
            }
        }
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
     * either is posted, so a refused call scrolls neither. The two numbers themselves are the
     * guest's, read 2026-09-13 (readings/windows-dump-uia-hresults.txt): 0x80040200 for
     * {@link UiaIds#E_ELEMENT_NOT_ENABLED} and 0x80131509 for {@link UiaIds#E_INVALID_OPERATION}.
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
     * axes pass every check before either is posted. The first two numbers are the guest's, read
     * 2026-09-13 (readings/windows-dump-uia-hresults.txt): 0x80040200 and 0x80131509.
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
}
