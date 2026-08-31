package limn.scene.layout;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

/**
 * Simplified flexbox shared by {@link Column} and {@link Row}: children flow
 * along the main axis with an optional {@code gap}; {@link Expanded} children
 * split the leftover main-axis space by flex weight; alignment controls the
 * distribution of leftovers ({@link MainAlignment}) and cross-axis placement
 * ({@link CrossAlignment}, including STRETCH).
 */
public abstract class Flex extends Widget {

    /**
     * How leftover space along the layout axis is distributed, on two vocabularies that coexist
     * on purpose.
     *
     * <p>{@link #START}, {@link #END} and {@link #SPACE_BETWEEN} are <b>logical</b>: they name
     * where reading starts and ends, so on a {@link Row} they turn around with
     * {@link limn.scene.LayoutDirection}. This is what a caller almost always wants, and it is
     * why a dialog's button row needs no direction knowledge of its own.
     *
     * <p>{@link #LEFT} and {@link #RIGHT} are <b>physical</b>: they name a side of the box and
     * keep naming it whichever way the subtree reads. Reach for one when the placement is about
     * the box rather than about reading order &mdash; a resize grip, a debug strip, a control
     * that has to stay put beside something outside this row. On a {@link Column} the main axis
     * has no left, so {@code LEFT} behaves as {@code START} and {@code RIGHT} as {@code END}: a
     * name that means nothing there must still mean something predictable.
     *
     * <p>{@link #CENTER} is the same number in both vocabularies and in both directions.
     */
    public enum MainAlignment {
        /** Against the edge reading starts from. */
        START,
        /** Centred. */
        CENTER,
        /** Against the edge reading ends on. */
        END,
        /** Leftovers split evenly between children, from the edge reading starts from. */
        SPACE_BETWEEN,
        /** Against the box's left edge, whichever way the subtree reads. */
        LEFT,
        /** Against the box's right edge, whichever way the subtree reads. */
        RIGHT
    }

    /**
     * Cross-axis placement. {@code START}/{@code CENTER}/{@code END}/{@code STRETCH} align
     * <b>boxes</b>; {@link #BASELINE} aligns <b>text</b>.
     *
     * <p>Use {@code BASELINE} for a row that mixes {@link limn.scene.ControlSize} steps and
     * carries text. The cross-step baseline offset reduces to {@code body * 0.341796875} (a
     * function of the type ramp alone), so no choice of control heights can cancel it, and
     * under {@code CENTER} two controls at adjacent steps sit with their baselines up to
     * 0.69pt apart (2.73pt across the full ramp). {@code BASELINE} is ignored on a
     * {@link Column}, where the cross axis is horizontal and there is no shared baseline.
     */
    public enum CrossAlignment { START, CENTER, END, STRETCH, BASELINE }

    private final boolean vertical;
    private float gap;
    private MainAlignment mainAlignment = MainAlignment.START;
    private CrossAlignment crossAlignment = CrossAlignment.START;

    Flex(boolean vertical) {
        this.vertical = vertical;
    }

    /** Space between children, in logical points. Not applied before the first or after the last. */
    public Flex gap(float newGap) {
        setGap(Math.max(0, newGap));
        return this;
    }

    /**
     * Assigns the gap with an equality guard. Without the guard, a token-driven container
     * that re-applies its gap on every measure pass calls {@link #markNeedsLayout()} from
     * inside {@code onMeasure}, which dirties the path to the root and schedules another
     * pass: a layout loop that never settles.
     */
    private void setGap(float newGap) {
        if (this.gap == newGap) {
            return;
        }
        this.gap = newGap;
        markNeedsLayout();
    }

    /**
     * Sets the gap <b>without</b> requesting a layout pass: the measure-path form, for a
     * container that derives its gap from resolved size tokens inside its own
     * {@code onMeasure}. Safe there and only there: the caller is already inside the pass
     * that will consume the new value, so requesting another one is at best wasteful and at
     * worst non-terminating. Everything else uses {@link #gap(float)}.
     */
    protected final void gapSilently(float newGap) {
        this.gap = Math.max(0, newGap);
    }

    /** How leftover space along the layout axis is distributed. */
    public Flex mainAlignment(MainAlignment alignment) {
        // Validate BEFORE mutating: a stored null only explodes at the next
        // layout pass, killing the event loop far from the offending call.
        this.mainAlignment = java.util.Objects.requireNonNull(alignment, "alignment");
        markNeedsLayout();
        return this;
    }

    /** How children are placed across the layout axis; {@code BASELINE} is the one for a row that mixes size steps and carries text. */
    public Flex crossAlignment(CrossAlignment alignment) {
        this.crossAlignment = java.util.Objects.requireNonNull(alignment, "alignment");
        markNeedsLayout();
        return this;
    }

    private float mainOf(Size size) {
        return vertical ? size.height() : size.width();
    }

    private float crossOf(Size size) {
        return vertical ? size.width() : size.height();
    }

    private int visibleCount() {
        int count = 0;
        for (Widget child : children()) {
            if (child.isVisible()) {
                count++;
            }
        }
        return count;
    }

    private static int flexOf(Widget child) {
        return child instanceof Expanded expanded ? expanded.flex() : 0;
    }

    private static float minMainOf(Widget child) {
        return child instanceof Expanded expanded ? expanded.minMain() : 0;
    }

    /** Resolved main-axis extent per child index, and which of those are floors. */
    private float[] flexShares = new float[8];
    private boolean[] flexFrozen = new boolean[8];

    /**
     * Resolves every visible flex child's main-axis extent into {@link #flexShares},
     * indexed by child index. <b>The single copy on purpose</b>: measure and layout each
     * ran this distribution separately, and the two have to agree to the point; a child
     * measured at one width and laid out at another is a worse bug than either width
     * being wrong.
     *
     * <p>Freeze-and-redistribute, the way CSS resolves flexible lengths. Each round
     * splits what is left by weight; every child that lands under its
     * {@linkplain Expanded#atLeast declared floor} is frozen at that floor and leaves the
     * split, which gives the rest a smaller pool and can push another one under its own
     * floor, so the round repeats until nobody is under, or nobody is left. It
     * terminates because every round that repeats freezes at least one child.
     *
     * <p>Floors that cannot all fit drive the pool negative; the unfrozen children then
     * take {@code 0} rather than a negative width, and the container overflows. That is
     * {@code atLeast}'s documented outcome, not a degenerate case to guard against.
     *
     * <p>With no floors declared nothing is ever frozen and this is the plain weighted
     * split, arithmetic included: the running {@code pool}/{@code assigned} form hands
     * the last child the exact remainder instead of a rounded share.
     *
     * @param remaining main-axis space left for the flex children, already floored at 0
     */
    private void resolveFlexShares(float remaining) {
        int childCount = children().size();
        if (flexShares.length < childCount) {
            flexShares = new float[childCount * 2];
            flexFrozen = new boolean[childCount * 2];
        }
        int weightLeft = 0;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            flexShares[i] = 0;
            flexFrozen[i] = false;
            if (child.isVisible()) {
                weightLeft += flexOf(child);
            }
        }

        for (boolean froze = true; froze && weightLeft > 0; ) {
            froze = false;
            float frozenMain = 0;
            int frozenWeight = 0;
            for (int i = 0; i < childCount; i++) {
                Widget child = children().get(i);
                int flex = flexOf(child);
                if (!child.isVisible() || flex == 0 || flexFrozen[i]) {
                    continue;
                }
                // Weighed against the round's own pool, so every violation in a round is
                // found before any of them shrinks the pool for the others.
                float floor = minMainOf(child);
                if (floor > 0 && remaining * flex / weightLeft < floor) {
                    flexFrozen[i] = true;
                    flexShares[i] = floor;
                    frozenMain += floor;
                    frozenWeight += flex;
                    froze = true;
                }
            }
            remaining -= frozenMain;
            weightLeft -= frozenWeight;
        }

        float pool = Math.max(0, remaining);
        int assigned = weightLeft;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            int flex = flexOf(child);
            if (!child.isVisible() || flex == 0 || flexFrozen[i]) {
                continue;
            }
            float share = assigned == 0 ? 0 : pool * flex / assigned;
            flexShares[i] = share;
            pool -= share;
            assigned -= flex;
        }
    }

    private Constraints childConstraints(Constraints incoming, float mainMax) {
        float crossMax = vertical ? incoming.maxWidth() : incoming.maxHeight();
        boolean stretch = crossAlignment == CrossAlignment.STRETCH
                && crossMax != Constraints.UNBOUNDED_LIMIT;
        float crossMin = stretch ? crossMax : 0;
        return vertical
                ? new Constraints(crossMin, crossMax, 0, mainMax)
                : new Constraints(0, mainMax, crossMin, crossMax);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float mainMax = vertical ? constraints.maxHeight() : constraints.maxWidth();
        boolean mainBounded = mainMax != Constraints.UNBOUNDED_LIMIT;
        int visible = visibleCount();
        float gapsTotal = visible > 1 ? gap * (visible - 1) : 0;

        float fixedMain = 0;
        float maxCross = 0;
        int totalFlex = 0;
        for (Widget child : children()) {
            if (!child.isVisible()) {
                continue;
            }
            int flex = flexOf(child);
            if (flex > 0) {
                totalFlex += flex;
                continue;
            }
            Size size = child.measure(childConstraints(constraints, Constraints.UNBOUNDED_LIMIT));
            fixedMain += mainOf(size);
            maxCross = Math.max(maxCross, crossOf(size));
        }

        if (totalFlex > 0 && mainBounded) {
            resolveFlexShares(Math.max(0, mainMax - fixedMain - gapsTotal));
            int childCount = children().size();
            for (int i = 0; i < childCount; i++) {
                Widget child = children().get(i);
                if (!child.isVisible() || flexOf(child) == 0) {
                    continue;
                }
                float share = flexShares[i];
                Size size = child.measure(tightMain(childConstraints(constraints, share), share));
                maxCross = Math.max(maxCross, crossOf(size));
            }
        }

        float main = totalFlex > 0 && mainBounded ? mainMax : fixedMain + gapsTotal;
        return vertical
                ? constraints.constrain(maxCross, main)
                : constraints.constrain(main, maxCross);
    }

    private Constraints tightMain(Constraints base, float main) {
        return vertical
                ? new Constraints(base.minWidth(), base.maxWidth(), main, main)
                : new Constraints(main, main, base.minHeight(), base.maxHeight());
    }

    private float[] mainSizes = new float[8];
    private float[] crossSizes = new float[8];

    @Override
    protected void onLayout() {
        int childCount = children().size();
        int visible = visibleCount();
        if (visible == 0) {
            return;
        }
        if (mainSizes.length < childCount) {
            mainSizes = new float[childCount * 2];
            crossSizes = new float[childCount * 2];
        }
        float mainSize = vertical ? height() : width();
        float crossSize = vertical ? width() : height();
        float gapsTotal = gap * (visible - 1);
        Constraints bounds = Constraints.loose(width(), height());

        // Pass 1: measure fixed children (unbounded main), count flex weight.
        float fixedMain = 0;
        int totalFlex = 0;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            int flex = flexOf(child);
            if (flex > 0) {
                totalFlex += flex;
                continue;
            }
            Size size = child.measure(childConstraints(bounds, Constraints.UNBOUNDED_LIMIT));
            mainSizes[i] = mainOf(size);
            crossSizes[i] = crossOf(size);
            fixedMain += mainSizes[i];
        }

        // Pass 2: flex children split the leftover main space by weight, floors first.
        resolveFlexShares(Math.max(0, mainSize - fixedMain - gapsTotal));
        float contentMain = fixedMain + gapsTotal;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            if (!child.isVisible() || flexOf(child) == 0) {
                continue;
            }
            float share = flexShares[i];
            Size size = child.measure(tightMain(childConstraints(bounds, share), share));
            mainSizes[i] = mainOf(size);
            crossSizes[i] = crossOf(size);
            contentMain += mainSizes[i];
        }

        // Pass 3: place. Leftover goes to main alignment when no flex absorbed it.
        float free = totalFlex > 0 ? 0 : Math.max(0, mainSize - contentMain);
        // Resolved once for the whole pass: two resolutions that disagreed inside one layout
        // would place a child against one edge and its neighbour against the other.
        boolean rtl = !vertical && layoutDirection() == limn.scene.LayoutDirection.RTL;
        // The cursor is a LOGICAL distance along the main axis, which the placement below
        // reflects. So a physical constant is expressed by asking for the logical end that
        // reflects onto the side it names, and the placement needs no second branch.
        float cursor = switch (mainAlignment) {
            case START, SPACE_BETWEEN -> 0;
            case CENTER -> free / 2;
            case END -> free;
            case LEFT -> rtl ? free : 0;
            case RIGHT -> rtl ? 0 : free;
        };
        float between = mainAlignment == MainAlignment.SPACE_BETWEEN && visible > 1
                ? free / (visible - 1)
                : 0;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            float childMain = mainSizes[i];
            float childCross = crossAlignment == CrossAlignment.STRETCH ? crossSize : crossSizes[i];
            float crossPos = switch (crossAlignment) {
                case START, STRETCH, BASELINE -> 0; // BASELINE repositions in a second sweep
                case CENTER -> (crossSize - childCross) / 2;
                case END -> crossSize - childCross;
            };
            if (vertical) {
                child.layoutBox(crossPos, cursor, childCross, childMain);
            } else {
                // The cursor walk is untouched and only the final coordinate is reflected, which
                // is why END, SPACE_BETWEEN, CENTER and the gap arithmetic all fall out unchanged.
                // A Column is not a site: direction is the reading axis, and a column's main axis
                // is not it.
                child.layoutBox(rtl ? width() - cursor - childMain : cursor,
                        crossPos, childMain, childCross);
            }
            cursor += childMain + gap + between;
        }
        if (!vertical && crossAlignment == CrossAlignment.BASELINE) {
            alignBaselines(childCount, crossSize);
        }
    }

    /**
     * Second sweep for {@link CrossAlignment#BASELINE}: every child has a box (laid out at
     * cross 0 above), so {@code baselineOffset()} is valid. Shifts each child down by the
     * difference between the deepest baseline and its own, using {@link #moveChild}, which
     * moves without re-running layout, so no child measures twice.
     *
     * <p><b>Known limitation, deliberate.</b> A baseline-aligned row needs
     * {@code max(baseline) + max(height - baseline)} of cross space, which can exceed the
     * {@code max(height)} that {@link #onMeasure} reports, and it cannot be computed there,
     * because {@code baselineOffset()} reads {@code height()} and no child has a box until
     * layout. Rather than clip, each shift is clamped so every child stays inside the box:
     * with a tight parent the deepest-descender child sits correctly and the shallower ones
     * stop short of perfect alignment instead of disappearing. Give a BASELINE row slack (a
     * stretching parent, or explicit height) for exact alignment. Closing this properly needs
     * a measure-time baseline protocol ({@code baselineOf(Size)} rather than
     * {@code baselineOffset()}), which is deferred until the first real consumer asks for it.
     */
    private void alignBaselines(int childCount, float crossSize) {
        float deepest = 0;
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            if (child.isVisible()) {
                deepest = Math.max(deepest, baselineOffsetOf(child));
            }
        }
        for (int i = 0; i < childCount; i++) {
            Widget child = children().get(i);
            if (!child.isVisible()) {
                continue;
            }
            float wanted = deepest - baselineOffsetOf(child);
            float room = crossSize - child.height();
            moveChild(child, child.x(), Math.max(0, Math.min(wanted, room)));
        }
    }
}
