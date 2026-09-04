package limn.accessibility;

/**
 * A node that holds a selection among its descendants: a list, a menu column, a tab strip, a
 * combo box's popup.
 *
 * <p>Which descendants are selected is not stored here. It is on the descendants themselves, in
 * their {@link SelectionItemFacet}, so one fact has one home. What is here is the container's own
 * shape, plus the <b>active descendant</b>: the one node inside this container that a keyboard
 * walk is currently on, which is the fact a screen reader announces as the user arrows down a
 * menu or a list and which no per-item state can carry.
 *
 * <p>The active descendant is resolved by the publish step rather than declared here: it is the
 * first node in this container's subtree published with {@link Accessible.State#ACTIVE}. A widget
 * whose selection and cursor are one thing marks its selected child {@code ACTIVE} as well as
 * selected.
 *
 * @param multiSelectable   whether more than one descendant may be selected at once
 * @param required          whether at least one descendant is always selected
 * @param activeDescendant  the identifier of the active descendant, or {@code 0} when there is
 *                          none
 */
public record SelectionFacet(boolean multiSelectable, boolean required, long activeDescendant) {

    /**
     * The same facet with its active descendant filled in. Used by the publish step once the
     * subtree beneath the container has been walked and its identifiers are known.
     *
     * @param id the active descendant's identifier, or {@code 0} for none
     * @return this facet when the identifier is unchanged, and a new one otherwise
     */
    public SelectionFacet withActiveDescendant(long id) {
        return id == activeDescendant ? this
                : new SelectionFacet(multiSelectable, required, id);
    }
}
