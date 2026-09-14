package limn.accessibility;

/**
 * One member of a selection: a list row, a menu row, a tab, a radio button, a combo option.
 *
 * <p>The position and the size of the set are <b>the numbers a screen reader speaks</b> as
 * "3 of 12", and nothing else: they are the <b>model's</b> numbers rather than the tree's, so a list
 * that publishes only its realized rows still reports the true row count and a user is told where
 * they are in the data and not where they are in the fraction of it that happens to be mounted.
 * Which set they count is each widget's to say, and three say it differently (ADR 039 §1.2,
 * amended 2026-09-14): a list row, a tab, a menu row or a radio counts the container's members; a
 * tree row counts its <em>siblings</em>, the rows at its own level under its own parent (decision
 * 4), its place in the whole outline being {@link HierarchyFacet}'s; a calendar day counts the
 * days of its own month, "15 of 30" (decision 37). A bridge that needs a row's index in a grid
 * reads {@link CellFacet}, never these numbers.
 *
 * <p>A zero says there is no number: a bridge publishes nothing for it on every platform rather
 * than "0 of 0" (semantics 6), which is what a radio outside any group carries.
 *
 * @param selected      whether this member is selected
 * @param positionInSet this member's one-based position, or {@code 0} when it has none
 * @param sizeOfSet     how many members the set holds, or {@code 0} when that is unknown
 */
public record SelectionItemFacet(boolean selected, int positionInSet, int sizeOfSet) {
}
