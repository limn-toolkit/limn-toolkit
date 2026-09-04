package limn.accessibility;

/**
 * A node that scrolls its content: a scroll view, a list, a text area, an overflowing strip.
 *
 * <p>The two percentages are where the viewport sits in the content, from {@code 0} at the start
 * to {@code 1} at the end; the two view sizes are the fraction of the content the viewport shows.
 * An axis that does not scroll reports a percentage of {@code 0} and a view size of {@code 1},
 * which is what every platform reads as "all of it, nowhere to go".
 *
 * @param horizontalPercent      where the viewport sits along the content's width, in {@code 0..1}
 * @param verticalPercent        where it sits along the content's height, in {@code 0..1}
 * @param horizontalViewSize     the fraction of the content's width the viewport shows
 * @param verticalViewSize       the fraction of its height the viewport shows
 * @param horizontallyScrollable whether the content is wider than the viewport
 * @param verticallyScrollable   whether it is taller
 */
public record ScrollFacet(double horizontalPercent, double verticalPercent,
                          double horizontalViewSize, double verticalViewSize,
                          boolean horizontallyScrollable, boolean verticallyScrollable) {
}
