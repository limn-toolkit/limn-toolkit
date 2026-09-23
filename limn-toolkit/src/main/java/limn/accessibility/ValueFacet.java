package limn.accessibility;

/**
 * A node whose state is a number in a range: a slider, a spinner, a progress bar, a scroll bar,
 * a split pane's divider, a date field's segment.
 *
 * <p>It carries the number <em>and</em> the text, because a value can have two forms and both are
 * wanted at once: a spinner in time mode holds 450 and displays {@code 07:30}, and one platform
 * asks the same element for both through two different interfaces. The text is already resolved
 * under the node's own locale; nothing downstream formats anything.
 *
 * <p><b>A value can say it has no number</b>: a date segment nobody has typed into has a range and
 * a text and nothing in between. Such a facet is {@link #empty()}, keeps its {@code min},
 * {@code max}, {@code step} and {@code text}, and carries the <em>minimum</em> as its
 * {@code value}, because two platforms have no way to answer "no number" where a number is
 * mandatory (UIA's {@code RangeValue.Value}, AT-SPI's {@code CurrentValue}) and the minimum is what
 * they answer there; the text and the events are what say empty. A bridge that can say it, says it
 * from this flag and not from the number.
 *
 * <p>A value that advances on its own &mdash; a playing video's position, a determinate progress
 * bar &mdash; is published <b>rounded to the resolution a user can act on</b>, by the widget. The
 * tree compares what it is given, so an unrounded position would make the comparison find a change
 * on every frame of playback and copy the whole tree for an hour with nobody touching anything.
 *
 * <p>A value a user may read and not set &mdash; a progress bar &mdash; says so here, because the
 * facet's presence is what advertises a set on every platform and nothing else could take it back:
 * {@link Accessible.State#READ_ONLY} is derived from this field, never declared, so a widget that
 * publishes a range it refuses to accept is a range a bridge reports writable.
 *
 * <p>The tree raises {@link AccessibleEvent.Type#VALUE_CHANGED} when the number, the text
 * <em>or</em> the emptiness moved: a segment filled with a digit that happens to be its minimum
 * changes only its text and its emptiness, and a reader has to hear it.
 *
 * @param value    the current value, or the minimum when {@code empty}
 * @param min      the smallest value the node accepts
 * @param max      the largest value the node accepts
 * @param step     one increment, or {@code 0} when the node has no step
 * @param text     the value as the node displays it, or {@code null} when the number is the whole
 *                 of it
 * @param readOnly whether the value may be read and not set
 * @param empty    whether the node holds no number at all, its range and text standing
 */
public record ValueFacet(double value, double min, double max, double step, String text,
                         boolean readOnly, boolean empty) {

    /**
     * A value that has a number: the shape every widget but a blank segment publishes.
     *
     * @param value    the current value
     * @param min      the smallest value the node accepts
     * @param max      the largest value the node accepts
     * @param step     one increment, or {@code 0} when the node has no step
     * @param text     the value as displayed, or {@code null}
     * @param readOnly whether the value may be read and not set
     */
    public ValueFacet(double value, double min, double max, double step, String text,
                      boolean readOnly) {
        this(value, min, max, step, text, readOnly, false);
    }
}
