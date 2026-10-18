package limn.scene.layout;

import limn.concurrent.Ui;
import limn.internal.lang.Checks;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.Objects;

/**
 * Places children one after another along a line in reading order, and starts a new line below
 * when the next child does not fit in what is left of the width: the layout for a set of tags,
 * chips, filters or buttons whose number or length is not known when the window is designed. Each
 * line is a <b>run</b>.
 *
 * <p>A run is laid out the way a {@link Row} lays out its children: {@link #gap} between
 * neighbours, {@link #mainAlignment} for the room left at the end of the run, and
 * {@link #crossAlignment} for where a child sits within the height of its run, which is the height
 * of the run's tallest child. So a wrap whose children all fit on one line places them where a row
 * with the same gap and alignments would, and it is the width, and only the width, that decides
 * where a new run starts. Runs stack top to bottom with {@link #runGap} between them. In a
 * right-to-left subtree ({@link limn.scene.LayoutDirection}) a run starts at the right edge and
 * fills leftwards, and the runs still go down.
 *
 * <p><b>What it measures to.</b> Every child is measured once, against the width the wrap is given
 * and never against what is left of a run, so a child's size does not depend on where it lands.
 * The wrap is as wide as its widest run and as tall as its runs and the gaps between them. With no
 * bound on the width, which is what {@link limn.scene.Scene#pack()} and a horizontally scrolling
 * parent give, everything is one run, as wide as a row of the same children. A child wider
 * than the width on its own takes a run of its own and is given at most that width, so whether it
 * wraps its own text, ellipsises or overflows is the child's measure to decide and not the wrap's.
 *
 * <p><b>No child is flexible here.</b> A run has no leftover to share out: what one run leaves over
 * is the room the next child did not fit into, and it belongs to {@link #mainAlignment}. So an
 * {@link Expanded} is refused when it is added, rather than accepted and silently laid out as the
 * widget it wraps; a fixed gutter between two children is a {@link SizedBox}.
 *
 * <p>A wrap draws nothing, clips nothing and describes nothing to an assistive technology, so it
 * publishes no node of its own and its children take its place. Their reading order, and the order
 * Tab visits them in, is the order they were added in, run after run, in both directions: a
 * right-to-left subtree moves the boxes and not the order.
 */
public class Wrap extends Container<Wrap> {

    private float gap;
    private float runGap;
    private Flex.MainAlignment mainAlignment = Flex.MainAlignment.START;
    private Flex.CrossAlignment crossAlignment = Flex.CrossAlignment.START;

    /** An empty wrap, with no gap on either axis, its runs against the edge reading starts from. */
    public Wrap() {
    }

    /**
     * Space between neighbours on a run, in logical points: the gutter a {@link Row}'s
     * {@link Flex#gap} is, never before the first child of a run or after its last. Negative values
     * clamp to {@code 0}. UI thread only.
     *
     * @param newGap the gutter along a run
     * @return this wrap
     * @throws IllegalArgumentException when {@code NaN} or infinite
     */
    public Wrap gap(float newGap) {
        Ui.checkUiThread();
        float value = Math.max(0, Checks.finite(newGap, "gap"));
        if (gap != value) {
            gap = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Space between one run and the next, in logical points, never above the first run or below
     * the last; a wrap that fits on one run shows none of it. Negative values clamp to {@code 0}. UI
     * thread only.
     *
     * @param newGap the gutter between runs
     * @return this wrap
     * @throws IllegalArgumentException when {@code NaN} or infinite
     */
    public Wrap runGap(float newGap) {
        Ui.checkUiThread();
        float value = Math.max(0, Checks.finite(newGap, "run gap"));
        if (runGap != value) {
            runGap = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Sets both gutters <b>without</b> requesting a layout pass: the measure-path form, for a
     * wrap whose gutters follow resolved size tokens and are pushed from its own
     * {@code onMeasure}, as {@link Flex}'s silent gap is. Safe there and only there: the caller is
     * already inside the pass that consumes them. Everything else uses {@link #gap(float)} and
     * {@link #runGap(float)}, and this refuses and clamps what they do.
     *
     * @param newGap    the gutter along a run
     * @param newRunGap the gutter between runs
     * @throws IllegalArgumentException when either is {@code NaN} or infinite
     */
    protected final void gapsSilently(float newGap, float newRunGap) {
        this.gap = Math.max(0, Checks.finite(newGap, "gap"));
        this.runGap = Math.max(0, Checks.finite(newRunGap, "run gap"));
    }

    /**
     * Where the children of each run sit along it when the run is narrower than the wrap, run by
     * run, on the two vocabularies {@link Flex.MainAlignment} describes: {@code START},
     * {@code END} and {@code SPACE_BETWEEN} follow the reading direction, and {@code LEFT} and
     * {@code RIGHT} keep naming a side of the box whichever way the subtree reads.
     * {@code SPACE_BETWEEN} spreads every run, the last one included, and puts a run of one child
     * where reading starts. The default is {@code START}. UI thread only.
     *
     * @param alignment the placement of each run's children along it
     * @return this wrap
     */
    public Wrap mainAlignment(Flex.MainAlignment alignment) {
        Ui.checkUiThread();
        // Validated before anything is stored: a stored null only explodes at the next layout
        // pass, far from the call that put it there.
        Flex.MainAlignment value = Objects.requireNonNull(alignment, "alignment");
        if (mainAlignment != value) {
            mainAlignment = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Where a child sits within the height of its run, which is the height of the run's tallest
     * child. {@code START}, {@code CENTER} and {@code END} place the box, and do not turn around in
     * a right-to-left subtree because the axis is vertical. {@code BASELINE} lines up the text of a
     * run's children, shifting each one down inside its run and never out of it, the clamp a
     * {@link Row} applies inside its own height. {@code STRETCH} makes every child of a run as tall
     * as the run, and costs a second measure in every pass of each child shorter than its run,
     * because the height it is stretched to is known only once the run is formed. The default is
     * {@code START}. UI thread only.
     *
     * @param alignment the placement of a child within its run
     * @return this wrap
     */
    public Wrap crossAlignment(Flex.CrossAlignment alignment) {
        Ui.checkUiThread();
        Flex.CrossAlignment value = Objects.requireNonNull(alignment, "alignment");
        if (crossAlignment != value) {
            crossAlignment = value;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Appends a child (UI thread only).
     *
     * @param child the child to append
     * @throws IllegalArgumentException when {@code child} is an {@link Expanded}, which has no
     *                                  leftover to take in a wrap
     */
    @Override
    public void add(Widget<?> child) {
        Ui.checkUiThread();
        super.add(refuseFlexible(child));
    }

    /**
     * Inserts a child at a position in {@link #children()} (UI thread only).
     *
     * @param index where the child goes, in {@code [0, children().size()]}
     * @param child the child to insert; never {@code null}
     * @throws IllegalArgumentException when {@code child} is an {@link Expanded}, which has no
     *                                  leftover to take in a wrap
     */
    @Override
    public void add(int index, Widget<?> child) {
        Ui.checkUiThread();
        super.add(index, refuseFlexible(child));
    }

    private static Widget<?> refuseFlexible(Widget<?> child) {
        if (child instanceof Expanded) {
            throw new IllegalArgumentException("a Wrap has no leftover space to share: what a run "
                    + "leaves over goes to mainAlignment, so an Expanded would be laid out as the "
                    + "widget it wraps. Add the child itself, or a SizedBox for a fixed gutter.");
        }
        return child;
    }

    // ---------------------------------------------------------------- measure and layout

    /** Each child's measured size by child index, filled before every break. */
    private float[] widths = new float[8];
    private float[] heights = new float[8];

    /**
     * The runs of the last break: the index of each run's first child, the index just past its
     * last, how many visible children it holds, its extent along the run with the gaps, and the
     * height of its tallest child.
     */
    private int[] runFirst = new int[8];
    private int[] runEnd = new int[8];
    private int[] runCount = new int[8];
    private float[] runExtent = new float[8];
    private float[] runHeight = new float[8];

    /**
     * The constraints the last {@link #onMeasure} ran under, and the widest child it measured, so
     * that {@link #onLayout} can ask each child the question it already answered: the one-answer
     * cache in {@code Widget.measure} then hands back the same size and no child is measured twice
     * in a pass. {@link Flex} keeps it for the same reason. A wrap in a loose parent is the case
     * that needs it: it is measured against the parent's bound and laid out at its widest run,
     * which is narrower, and asking against that box would miss every child's cache. The answers
     * are reused only while they still fit: the box no larger than the bound they were given
     * against, and no narrower than the widest of them; otherwise layout asks against the box.
     */
    private Constraints measuredWith;
    private float measuredWidest;

    private void ensureCapacity(int childCount) {
        if (widths.length < childCount) {
            int size = childCount * 2;
            widths = new float[size];
            heights = new float[size];
            runFirst = new int[size];
            runEnd = new int[size];
            runCount = new int[size];
            runExtent = new float[size];
            runHeight = new float[size];
        }
    }

    /**
     * What every child is asked: at most the width the wrap may take and at most its height, with
     * no minimum. The width bound is the whole of what the wrap is given and never the room left on
     * a run, which is what keeps one measure per child: the answer does not depend on where the
     * child lands, so breaking the runs needs no second question.
     */
    private static Constraints childConstraints(Constraints incoming) {
        return new Constraints(0, incoming.maxWidth(), 0, incoming.maxHeight());
    }

    /**
     * Measures every visible child against {@code ask} into {@link #widths} and {@link #heights}.
     *
     * @return the widest of them
     */
    private float measureChildren(Constraints ask) {
        float widest = 0;
        int childCount = children().size();
        for (int i = 0; i < childCount; i++) {
            Widget<?> child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            Size size = child.measure(ask);
            widths[i] = size.width();
            heights[i] = size.height();
            widest = Math.max(widest, size.width());
        }
        return widest;
    }

    /**
     * Breaks the visible children into runs no wider than {@code limit}, greedily and in child
     * order: a child goes on the current run when the run with the gap and the child still fits,
     * and starts the next run when it does not, unless the current run is empty, which is how a
     * child wider than the limit gets a run of its own instead of an empty run before it.
     *
     * <p><b>The single copy on purpose</b>, for {@link Flex}'s reason: measure and layout both
     * break, and a wrap that measured one set of runs and laid out another would report a height
     * its children do not have. The extent is accumulated with the same expression it is compared
     * with, so a run measured with no bound, whose extent then comes back as the limit (the second
     * measure of {@link limn.scene.Scene#pack()}, a parent that lays the wrap out at its natural
     * width), breaks nowhere: {@code extent + gap + width} rounded once for the test and once more
     * for the sum could disagree in the last bit, and the last child of the line would drop onto a
     * run of its own.
     *
     * @return how many runs there are, {@code 0} when no child is visible
     */
    private int breakRuns(float limit) {
        int runs = 0;
        int count = 0;
        float extent = 0;
        float tallest = 0;
        int first = 0;
        int childCount = children().size();
        for (int i = 0; i < childCount; i++) {
            if (!children().get(i).isVisible()) {
                continue;
            }
            float next = count == 0 ? widths[i] : extent + gap + widths[i];
            if (count > 0 && next > limit) {
                closeRun(runs++, first, i, count, extent, tallest);
                count = 0;
                tallest = 0;
                next = widths[i];
            }
            if (count == 0) {
                first = i;
            }
            extent = next;
            tallest = Math.max(tallest, heights[i]);
            count++;
        }
        if (count > 0) {
            closeRun(runs++, first, childCount, count, extent, tallest);
        }
        return runs;
    }

    private void closeRun(int run, int first, int end, int count, float extent, float tallest) {
        runFirst[run] = first;
        runEnd[run] = end;
        runCount[run] = count;
        runExtent[run] = extent;
        runHeight[run] = tallest;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        measuredWith = constraints;
        ensureCapacity(children().size());
        measuredWidest = measureChildren(childConstraints(constraints));
        int runs = breakRuns(constraints.maxWidth());
        float width = 0;
        float height = runs > 1 ? runGap * (runs - 1) : 0;
        for (int run = 0; run < runs; run++) {
            width = Math.max(width, runExtent[run]);
            height += runHeight[run];
        }
        return constraints.constrain(width, height);
    }

    @Override
    protected void onLayout() {
        int childCount = children().size();
        ensureCapacity(childCount);
        float boxWidth = width();
        // The measure's own question wherever its answers still fit the box, so each child's
        // cached answer comes back (see measuredWith); otherwise the box, which is what a parent
        // that laid the wrap out narrower than it measured, or without measuring it, has given.
        boolean reuse = measuredWith != null
                && boxWidth <= measuredWith.maxWidth()
                && boxWidth >= measuredWidest
                && height() <= measuredWith.maxHeight();
        measureChildren(childConstraints(reuse ? measuredWith : Constraints.loose(boxWidth, height())));
        int runs = breakRuns(boxWidth);

        // Resolved once for the whole pass: two resolutions that disagreed inside one layout would
        // start one run from the left edge and the next from the right.
        boolean rtl = isRightToLeft();
        float top = 0;
        for (int run = 0; run < runs; run++) {
            float free = Math.max(0, boxWidth - runExtent[run]);
            // The cursor is a LOGICAL distance along the run, which the placement below reflects,
            // exactly as in Flex: a physical constant asks for the logical end that reflects onto
            // the side it names.
            float cursor = switch (mainAlignment) {
                case START, SPACE_BETWEEN -> 0;
                case CENTER -> free / 2;
                case END -> free;
                case LEFT -> rtl ? free : 0;
                case RIGHT -> rtl ? 0 : free;
            };
            float between = mainAlignment == Flex.MainAlignment.SPACE_BETWEEN && runCount[run] > 1
                    ? free / (runCount[run] - 1)
                    : 0;
            float lineHeight = runHeight[run];
            for (int i = runFirst[run]; i < runEnd[run]; i++) {
                Widget<?> child = children().get(i);
                if (!child.isVisible()) {
                    continue;
                }
                float childWidth = widths[i];
                float childHeight = heights[i];
                if (crossAlignment == Flex.CrossAlignment.STRETCH && childHeight < lineHeight) {
                    // The one second question a wrap asks, and only of a child shorter than its
                    // run: the run's height is known only now. The width is pinned to the answer
                    // already given, so the run the child was broken into still holds it.
                    Size stretched = child.measure(Constraints.tight(childWidth, lineHeight));
                    childWidth = stretched.width();
                    childHeight = stretched.height();
                }
                float y = top + switch (crossAlignment) {
                    case START, STRETCH, BASELINE -> 0; // BASELINE moves them in a second sweep
                    case CENTER -> (lineHeight - childHeight) / 2;
                    case END -> lineHeight - childHeight;
                };
                // Only the final coordinate is reflected, so the gap, END, CENTER and
                // SPACE_BETWEEN arithmetic is the same in both directions.
                child.layoutBox(rtl ? boxWidth - cursor - childWidth : cursor, y, childWidth,
                        childHeight);
                cursor += childWidth + gap + between;
            }
            if (crossAlignment == Flex.CrossAlignment.BASELINE) {
                alignBaselines(run, top);
            }
            top += lineHeight + runGap;
        }
    }

    /**
     * The {@link Flex.CrossAlignment#BASELINE} sweep over one run, once every child of it has a
     * box: each child moves down by the difference between the run's deepest baseline and its own,
     * clamped so that it stays inside the run. The clamp is the one {@link Row} applies, for the
     * same reason: a run is as tall as its tallest child and no taller, which can be less than a
     * baseline-aligned line needs, and a child shifted out of its run would overlap the next one.
     * The shift uses {@link #moveChild}, so no child is laid out twice.
     */
    private void alignBaselines(int run, float top) {
        float deepest = 0;
        for (int i = runFirst[run]; i < runEnd[run]; i++) {
            Widget<?> child = children().get(i);
            if (child.isVisible()) {
                deepest = Math.max(deepest, baselineOffsetOf(child));
            }
        }
        for (int i = runFirst[run]; i < runEnd[run]; i++) {
            Widget<?> child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            float wanted = deepest - baselineOffsetOf(child);
            float room = runHeight[run] - child.height();
            moveChild(child, child.x(), top + Math.max(0, Math.min(wanted, room)));
        }
    }
}
