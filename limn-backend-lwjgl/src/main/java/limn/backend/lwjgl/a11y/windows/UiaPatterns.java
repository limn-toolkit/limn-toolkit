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
 * it is a control that cannot be operated at all. That is why one of these rows needs a paragraph
 * of its own.
 *
 * <p><b>The row that needs it: {@code Value} comes from a {@code TextFacet} as well as from a
 * {@code ValueFacet}.</b> {@code TextPattern} is deferred, and a text widget has never had a
 * numeric value — so mapping {@code Value} from {@code ValueFacet} alone leaves every text field,
 * text area, password field and search field in the toolkit vending no pattern whatsoever. A screen
 * reader would find a named element whose contents it cannot read, cannot set, and cannot report as
 * read-only.
 *
 * <p><b>{@code ScrollItem} is the node's own verb.</b> It says "I can be scrolled into view", and
 * since 2026-09-15 it is vended only where the node publishes {@code SCROLL_INTO_VIEW}: until then
 * it was vended on any node with a scrollable ancestor, which offered a client a move a list or
 * tree row's widget refused while the client was told it was done.
 *
 * <p><b>No node vends {@code Window} or {@code Transform}.</b> The window's root answers
 * {@code get_HostRawElementProvider} with the provider UI Automation made for its HWND, and that
 * host provider serves both patterns for the real window (the probe's {@code [Window,Transform]}
 * came from it); this bridge serves neither interface, so claiming them here answered a client's
 * {@code GetPatternProvider} with a null. An in-scene dialog, which has no HWND, says what it is
 * with {@code IsDialog}, a property.
 */
final class UiaPatterns {

    private UiaPatterns() {
    }

    /**
     * @param tree      the published tree the node came from; no row reads it since ScrollItem
     *                  became the node's own verb (2026-09-15), kept so a row that needs the tree
     *                  again does not change every caller
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
                // The text facet, for the reason in this class's javadoc: its contribution is what
                // keeps four widgets from vending nothing at all. And the value facet ONLY when it
                // carries a spoken form -- a spinner's "07:30", a combo's item -- never for the
                // bare number a slider or a progress bar publishes. NVDA prefers Value's string to
                // RangeValue's number when a control vends both, and measured on the guest
                // (2026-09-07, ADR 039 §13.19) a slider vending both answered Value with "" and
                // was announced as "slider" with no value at all, while RangeValue read 41, 42,
                // 44, 45 correctly the whole time. A pattern with nothing to say is worse than
                // its absence here.
                return node.text() != null
                        || (node.value() != null && node.value().text() != null
                            && !node.value().text().isEmpty());

            case UiaIds.SELECTION_PATTERN:
                return node.selection() != null;

            case UiaIds.SELECTION_ITEM_PATTERN:
                return node.selectionItem() != null;

            case UiaIds.EXPAND_COLLAPSE_PATTERN:
                return node.expand() != null;

            case UiaIds.SCROLL_PATTERN:
                return node.scroll() != null;

            case UiaIds.SCROLL_ITEM_PATTERN:
                // The verb, not a scrollable ancestor: a row whose widget does not reveal it would
                // otherwise be offered to a client as a move that does nothing.
                return node.actions() != null
                        && node.actions().has(Accessible.Action.SCROLL_INTO_VIEW);

            case UiaIds.GRID_PATTERN:
            case UiaIds.TABLE_PATTERN:
                // Both from the table facet: a grid is asked by row and column, a table for its
                // headers, and a node that has one has the other (ADR 041 §7).
                return node.table() != null;

            case UiaIds.GRID_ITEM_PATTERN:
            case UiaIds.TABLE_ITEM_PATTERN:
                // A data cell only: a header or footer cell carries a negative row and is not
                // in the grid a client counts.
                return node.cell() != null && node.cell().row() >= 0;

            default:
                // Text among them, deferred by §11, and every pattern this toolkit has no facet
                // for. Silence is the right answer: a client takes it as "this control cannot do
                // that", which is true. Window and Transform too: the HWND's host provider serves
                // them for the real window, and this bridge serves neither interface.
                return false;
        }
    }
}
