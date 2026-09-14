package limn.accessibility;

/**
 * A node that holds a selection among its descendants: a list, a menu column, a tab strip, a
 * combo box's popup.
 *
 * <p>Which descendants are selected is not stored here. It is on the descendants themselves, in
 * their {@link SelectionItemFacet}, so one fact has one home. What is here is the container's own
 * shape and nothing else.
 *
 * <p>Where the keyboard cursor is inside a container is <b>not</b> a fact of the container either
 * (ADR 039 §1.10, amended 2026-09-14; decision 6). It is the tree's: {@link
 * AccessibleTree#activeDescendant()} is the first node published {@link Accessible.State#ACTIVE}
 * below the focused node, whichever containers lie between, and one event names the focused node
 * when it moves. Until that amendment this record carried an {@code activeDescendant} resolved
 * per container, so a combo's layer and its list, or a menu surface and each of its columns,
 * announced one arrow key two or three times over, and a container nobody was in announced a
 * cursor nobody had.
 *
 * @param multiSelectable whether more than one descendant may be selected at once
 * @param required        whether at least one descendant is always selected
 */
public record SelectionFacet(boolean multiSelectable, boolean required) {
}
