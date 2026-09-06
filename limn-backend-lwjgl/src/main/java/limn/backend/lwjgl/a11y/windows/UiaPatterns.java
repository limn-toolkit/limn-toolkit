package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

/**
 * Which UI Automation patterns a node vends, decided from the facets it carries.
 *
 * <p><b>On this platform the pattern list is the whole of a control's behaviour.</b> A client asks
 * {@code GetPatternProvider} for each pattern it cares about and takes silence as "this control
 * cannot do that" — so a facet that fails to become a pattern here is not a degraded announcement,
 * it is a control that cannot be operated at all. That is the reason §2.1 spends a paragraph on one
 * of these rows.
 *
 * <p><b>The row it spends it on: {@code Value} comes from a {@code TextFacet} as well as from a
 * {@code ValueFacet}.</b> {@code TextPattern} is deferred (§11), and a text widget has never had a
 * numeric value — so mapping {@code Value} from {@code ValueFacet} alone leaves every text field,
 * text area, password field and search field in the toolkit vending no pattern whatsoever. A screen
 * reader would find a named element whose contents it cannot read, cannot set, and cannot report as
 * read-only.
 *
 * <p><b>{@code ScrollItem} is the one answer that is not about this node.</b> It says "I can be
 * scrolled into view", which is true of a node whose ancestor scrolls and false of one nobody can
 * move — so it is the only row here that walks the tree, and it walks it upward through the links
 * §1.4 stores rather than scanning anything.
 *
 * <p><b>An in-scene dialog vends no {@code Window} pattern</b>, which is why {@code WindowFacet} and
 * not the role is what decides it: vending {@code IWindowProvider} from a node with no HWND
 * advertises {@code Close()}, {@code SetVisualState()} and {@code CanMaximize} over an overlay that
 * has none of them. What such a dialog says instead is {@code IsDialog}, and that is a property.
 */
final class UiaPatterns {

    private UiaPatterns() {
    }

    /**
     * @param tree      the published tree the node came from, for the one row that needs an
     *                  ancestor
     * @param node      the node being asked
     * @param patternId one of {@link UiaIds}' pattern ids
     * @return whether this node vends that pattern
     */
    static boolean supports(AccessibleTree tree, AccessibleNode node, int patternId) {
        switch (patternId) {
            case UiaIds.INVOKE_PATTERN:
                // The one verb Invoke means: a press. Not every action -- a node that only offers
                // EXPAND has an ExpandCollapse pattern and no Invoke, and advertising one would
                // have a client's Invoke() reach a widget that refuses it.
                return node.actions() != null && node.actions().has(Accessible.Action.PRESS);

            case UiaIds.TOGGLE_PATTERN:
                return node.toggle() != null;

            case UiaIds.RANGE_VALUE_PATTERN:
                // A number with bounds: the slider, the progress bar, the spinner, the splitter.
                return node.value() != null;

            case UiaIds.VALUE_PATTERN:
                // Both, for the reason in this class's javadoc. The text facet's contribution is
                // what keeps four widgets from vending nothing at all.
                return node.value() != null || node.text() != null;

            case UiaIds.SELECTION_PATTERN:
                return node.selection() != null;

            case UiaIds.SELECTION_ITEM_PATTERN:
                return node.selectionItem() != null;

            case UiaIds.EXPAND_COLLAPSE_PATTERN:
                return node.expand() != null;

            case UiaIds.SCROLL_PATTERN:
                return node.scroll() != null;

            case UiaIds.SCROLL_ITEM_PATTERN:
                return hasAScrollableAncestor(tree, node);

            case UiaIds.WINDOW_PATTERN:
            case UiaIds.TRANSFORM_PATTERN:
                // Both from the same facet, and only a real window carries one: the two describe
                // moving, sizing and closing, which is what an HWND can do and an overlay cannot.
                return node.window() != null;

            default:
                // Text among them, deferred by §11, and every pattern this toolkit has no facet
                // for. Silence is the right answer: a client takes it as "this control cannot do
                // that", which is true.
                return false;
        }
    }

    /**
     * @return whether any ancestor of {@code node} scrolls, walking the links §1.4 stores rather
     *         than scanning a child list
     */
    private static boolean hasAScrollableAncestor(AccessibleTree tree, AccessibleNode node) {
        for (int at = node.parent(); at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (tree.node(at).scroll() != null) {
                return true;
            }
        }
        return false;
    }
}
