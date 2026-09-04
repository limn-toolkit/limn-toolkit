package limn.accessibility;

/**
 * A node that opens and closes: a combo box, a menu title, a disclosure.
 *
 * @param expanded whether the node is open
 */
public record ExpandFacet(boolean expanded) {
}
