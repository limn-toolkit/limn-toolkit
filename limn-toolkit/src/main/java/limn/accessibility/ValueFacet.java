package limn.accessibility;

/**
 * A node whose state is a number in a range: a slider, a spinner, a progress bar, a scroll bar,
 * a split pane's divider.
 *
 * <p>It carries the number <em>and</em> the text, because a value can have two forms and both are
 * wanted at once: a spinner in time mode holds 450 and displays {@code 07:30}, and one platform
 * asks the same element for both through two different interfaces. The text is already resolved
 * under the node's own locale; nothing downstream formats anything.
 *
 * <p>A value that advances on its own &mdash; a playing video's position, a determinate progress
 * bar &mdash; is published <b>rounded to the resolution a user can act on</b>, by the widget. The
 * tree compares what it is given, so an unrounded position would make the comparison find a change
 * on every frame of playback and copy the whole tree for an hour with nobody touching anything.
 *
 * @param value the current value
 * @param min   the smallest value the node accepts
 * @param max   the largest value the node accepts
 * @param step  one increment, or {@code 0} when the node has no step
 * @param text  the value as the node displays it, or {@code null} when the number is the whole
 *              of it
 */
public record ValueFacet(double value, double min, double max, double step, String text) {
}
