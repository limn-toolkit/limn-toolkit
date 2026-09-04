package limn.accessibility;

/**
 * One member of a selection: a list row, a menu row, a tab, a radio button, a combo option.
 *
 * <p>The position and the size of the set are the numbers a screen reader speaks as
 * "3 of 12", and they are the <b>model's</b> numbers rather than the tree's: a list that publishes
 * only its realized rows still reports the true row count, so a user is told where they are in the
 * data and not where they are in the fraction of it that happens to be mounted.
 *
 * @param selected      whether this member is selected
 * @param positionInSet this member's one-based position, or {@code 0} when it has none
 * @param sizeOfSet     how many members the set holds, or {@code 0} when that is unknown
 */
public record SelectionItemFacet(boolean selected, int positionInSet, int sizeOfSet) {
}
