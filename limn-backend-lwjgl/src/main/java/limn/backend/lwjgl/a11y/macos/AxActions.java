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
