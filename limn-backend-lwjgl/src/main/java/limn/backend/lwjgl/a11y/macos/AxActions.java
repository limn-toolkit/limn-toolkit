package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of the toolkit's verbs each of AppKit's action selectors means, and which of them an element
 * actually offers.
 *
 * <p><b>Both halves are needed and the second is the one that is easy to miss.</b> The perform
 * selectors are installed on one class, so every element of ours responds to all of them — and
 * AppKit derives the action list a client is offered from what an object responds to. Left there, a
 * button would advertise "increment" and a slider "show menu". {@code isAccessibilitySelectorAllowed:}
 * is the platform's own answer to that, and it is what makes the list per node rather than per class.
 *
 * <p><b>One selector can mean several verbs, in order.</b> A press is "activate this", and what
 * activating means is the widget's: a button is pressed, a check box is toggled, a radio button is
 * selected. So the table lists candidates and the node picks — which keeps this a translation rather
 * than a second vocabulary, and keeps §1.9's rule that a widget performs its own action through its
 * own guards.
 *
 * <p><b>{@code accessibilityPerformPick} is deliberately absent.</b> AppKit means "choose this from
 * a list" by it, and everything that would answer it here already answers a press — a combo box's
 * item advertises {@code SELECT} and {@code PRESS} together. A second name for one outcome is a
 * second action a reader offers and a user has to choose between for no reason.
 */
final class AxActions {

    private AxActions() {
    }

    /** Selector name to the verbs it may mean, most specific first. */
    private static final Map<String, List<Accessible.Action>> BY_SELECTOR = new LinkedHashMap<>();

    static {
        // Activation. What it does is the widget's to say, so every candidate is offered and the
        // node's published verbs decide (semantics 5): a button is pressed, a check box toggled, a
        // radio button selected, and a node whose only activation is opening or closing -- a combo
        // box, a menu title, a date field, a tree row -- opens when closed and closes when open,
        // which is exactly the one of EXPAND and COLLAPSE it publishes at that moment.
        List<Accessible.Action> activate = List.of(
                Accessible.Action.PRESS, Accessible.Action.TOGGLE, Accessible.Action.SELECT,
                Accessible.Action.EXPAND, Accessible.Action.COLLAPSE);
        BY_SELECTOR.put("accessibilityPerformPress", activate);
        // Confirm is Return on a control that has one, and on everything this toolkit publishes the
        // thing Return does is the activation. Two selectors reaching one verb is the same shape as
        // two roles sharing one control type: the platform draws a distinction the toolkit does not.
        // Semantics 5 names the one list for both, so a confirm opens a closed combo box as a press
        // does, on purpose (MACOS-NEW-5, correction 2).
        BY_SELECTOR.put("accessibilityPerformConfirm", activate);

        BY_SELECTOR.put("accessibilityPerformIncrement", List.of(Accessible.Action.INCREMENT));
        BY_SELECTOR.put("accessibilityPerformDecrement", List.of(Accessible.Action.DECREMENT));
        BY_SELECTOR.put("accessibilityPerformShowMenu", List.of(Accessible.Action.SHOW_MENU));
        BY_SELECTOR.put("accessibilityPerformCancel", List.of(Accessible.Action.CANCEL));
    }

    /**
     * The AppKit global naming each selector's action, as a client lists and performs it. Resolved by
     * {@code dlsym} at run time; the values were read on the macOS 26.6.2 guest on 2026-09-13
     * (readings/macos-appkit-constants.txt, "action names").
     */
    private static final Map<String, String> ACTION_SYMBOL = new LinkedHashMap<>();

    static {
        ACTION_SYMBOL.put("accessibilityPerformPress", "NSAccessibilityPressAction");
        ACTION_SYMBOL.put("accessibilityPerformConfirm", "NSAccessibilityConfirmAction");
        ACTION_SYMBOL.put("accessibilityPerformIncrement", "NSAccessibilityIncrementAction");
        ACTION_SYMBOL.put("accessibilityPerformDecrement", "NSAccessibilityDecrementAction");
        ACTION_SYMBOL.put("accessibilityPerformShowMenu", "NSAccessibilityShowMenuAction");
        ACTION_SYMBOL.put("accessibilityPerformCancel", "NSAccessibilityCancelAction");
    }

    /**
     * {@code AXScrollToVisible}, new in macOS 26, and the one action with no selector of its own: no
     * class or protocol declares one (read on the guest 2026-09-13). What reaches an
     * {@code NSAccessibilityElement} is the legacy pair: with {@code accessibilityActionNames} listing it,
     * a client's perform entered {@code accessibilityPerformAction:} with its name, while an
     * {@code NSAccessibilityCustomAction} of that name was never run and a guessed
     * {@code accessibilityPerformScrollToVisible} never entered (read on the macOS 26.6.2 guest,
     * 2026-09-15, {@code scripts/a11y/macos/scroll-to-visible-probe.swift}).
     */
    static final String SCROLL_TO_VISIBLE_SYMBOL = "NSAccessibilityScrollToVisibleAction";

    /**
     * What {@code accessibilityActionNames} lists for a node. Once that selector is answered AppKit lists
     * exactly what it answers and no longer derives the list from the selectors an element responds to
     * (read 2026-09-15), so every action the node offers is listed here, in the selectors' order, and
     * scroll-to-visible after them where the node accepts {@code SCROLL_INTO_VIEW}.
     *
     * @param node the node
     * @return the symbols of the actions it offers, in order
     */
    static List<String> actionSymbolsFor(AccessibleNode node) {
        List<String> symbols = new java.util.ArrayList<>();
        for (Map.Entry<String, String> action : ACTION_SYMBOL.entrySet()) {
            if (verbFor(node, action.getKey()) != null) symbols.add(action.getValue());
        }
        if (node.accepts(Accessible.Action.SCROLL_INTO_VIEW)) symbols.add(SCROLL_TO_VISIBLE_SYMBOL);
        return symbols;
    }

    /**
     * The verb {@code accessibilityPerformAction:} posts for an action named by its symbol: the same
     * verb its own selector performs, and {@code SCROLL_INTO_VIEW} for scroll-to-visible, each only where
     * the node accepts it. A named action with its own selector normally arrives through that selector
     * (AXPress entered {@code accessibilityPerformPress} on the guest even with this pair installed); it
     * is answered here too, so that a name the list offers is never one nothing performs.
     *
     * @param node   the node
     * @param symbol one of {@link #actionSymbols()}
     * @return the verb to post, or {@code null}
     */
    static Accessible.Action verbForActionSymbol(AccessibleNode node, String symbol) {
        if (SCROLL_TO_VISIBLE_SYMBOL.equals(symbol)) {
            return node.accepts(Accessible.Action.SCROLL_INTO_VIEW) ? Accessible.Action.SCROLL_INTO_VIEW : null;
        }
        for (Map.Entry<String, String> action : ACTION_SYMBOL.entrySet()) {
            if (action.getValue().equals(symbol)) return verbFor(node, action.getKey());
        }
        return null;
    }

    /** @return every action-name symbol this bridge lists or performs, for the constants test */
    static List<String> actionSymbols() {
        List<String> symbols = new java.util.ArrayList<>(ACTION_SYMBOL.values());
        symbols.add(SCROLL_TO_VISIBLE_SYMBOL);
        return symbols;
    }

    /**
     * @param selector any selector name
     * @return whether it is one of the action selectors this bridge implements, which is what the
     *         allowed-gate needs in order to leave every attribute selector alone
     */
    static boolean isActionSelector(String selector) {
        return BY_SELECTOR.containsKey(selector);
    }

    /** @return every selector this bridge implements, for the class that installs them. */
    static Iterable<String> selectors() {
        return BY_SELECTOR.keySet();
    }

    /**
     * The verb a selector performs on a node, or {@code null} when the node offers none of them.
     *
     * <p>Null is also the answer {@code isAccessibilitySelectorAllowed:} needs: a selector that
     * would perform nothing is one the element must not advertise, or a reader offers a user an
     * action that silently does nothing.
     *
     * @param node     the node the message was sent to
     * @param selector one of {@link #selectors()}
     * @return the verb to perform, or {@code null}
     */
    static Accessible.Action verbFor(AccessibleNode node, String selector) {
        List<Accessible.Action> candidates = BY_SELECTOR.get(selector);
        if (candidates == null) return null;
        for (int i = 0; i < candidates.size(); i++) {   // indexed: an iterator is an allocation
            Accessible.Action candidate = candidates.get(i);
            // The toolkit's one reading of "does this node accept this verb now".
            if (node.accepts(candidate)) return candidate;
        }
        return null;
    }
}
