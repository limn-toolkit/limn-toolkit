package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.a11y.RowsAccessibility;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A row of connected segments with a single selection, a compact alternative to
 * a {@link RadioButton} group or tabs. The selected segment is marked by an
 * accent indicator that slides between segments (animated like the
 * {@link TabbedPane} indicator). Click a segment, or move with Left/Right when
 * focused. Colours come from {@link Theme}; the box, the type and the gutters come
 * from the resolved {@link limn.scene.ControlSize} row ({@link SizeTokens}), while the
 * track border stays one weight at every step ({@link Strokes}).
 *
 * <p><b>Width:</b> the track is exactly as wide as its segments need and no wider, centred in
 * whatever box the parent assigns. That matters because the common parent (a column with
 * {@code STRETCH}) hands every child the full row, and a pill track drawn edge to edge with
 * four short segments huddled at its left end reads as broken. The margins either side belong
 * to what is behind this control: a click landing on one selects nothing.
 *
 * <p><b>Overflow:</b> when the segments do not fit, the strip becomes a clipped, scrollable
 * viewport with a chevron at each edge, the dead side drawn disabled rather than dropped,
 * since dropping it would resize the viewport and make the strip jump at the ends. The mouse
 * wheel scrolls it, a chevron click scrolls by most of a viewport, and selecting a segment by
 * any route (click, Left/Right, or {@link #setSelectedIndex}) scrolls it into view. Nothing
 * is ever painted outside the track. There is no all-segments popup, which is the one part of
 * the {@link TabbedPane} overflow story this control does not carry: its labels are handed in
 * by the caller and are few and short by construction, and a control that fits in a toolbar
 * row has nowhere to put a third piece of chrome.
 *
 * <p><b>Reading direction:</b> the strip is a row of items in reading order, so it mirrors. The
 * segments are placed from the edge reading starts at, the back chevron sits in that same gutter
 * and points back towards the first segment, and Left selects the segment that is visually on the
 * left: a keyboard that walked the other way from the pointer would be arguing with the screen.
 * {@code Home} and {@code End} do not mirror, because they name the first and last segment rather
 * than a side. Only the placement moves; the track, the viewport and each label inside its own
 * segment are symmetric and stay where they are, and there is no mirror transform anywhere here.
 *
 * <p><b>This or a {@link TabbedPane}? Ask who owns the content.</b> A segmented
 * control owns none: it takes labels and hands back an index, so the caller keeps
 * the content and switches it, which is what makes it the one that belongs in a
 * toolbar row, a form, or inside another widget that already owns what changes. A
 * tabbed pane is the opposite: you hand it the pages and it owns them, laying out
 * and painting only the selected one, and carrying the overflow story that owning
 * an unknown number of pages requires. Looking alike is not the test; a row of
 * labels is what both <em>are</em>, and what they <em>do</em> is where they split.
 */
public class SegmentedControl extends Widget {

    private final List<I18nString> segments;
    private int selected;
    private int hoverIndex = -1;
    private IntConsumer onSelect;
    // The selected indicator slides by animating its two x edges (snap on first layout).
    private final Transition indicatorLeft =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    private final Transition indicatorRight =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    /** Fades the focus ring in and out, so keyboard focus arrives rather than blinks on. */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private boolean indicatorPlaced;
    /** The pill's edges last handed to its transitions; see the same pair in TabbedPane. */
    private float indicatorTargetLeft = Float.NaN;
    private float indicatorTargetRight = Float.NaN;
    /**
     * The n+1 cumulative segment edges, in the strip's own <b>logical</b> space: index 0 is the
     * first segment's leading edge whichever way the control reads, and
     * {@link #cellLeft(boolean, float, float)} is the one place that becomes a physical x.
     * Rebuilt by every layout pass, which is what keeps it current across a change of direction:
     * the label widths behind these edges are direction-sensitive, and every way of changing the
     * resolved direction already forces a layout pass on the widgets whose resolved value moved.
     */
    private float[] bounds;
    /** Which segment the indicator is currently at, so a scroll snaps it instead of sliding it. */
    private int indicatorSegment = -1;

    /** Fraction of the viewport scrolled per chevron click. */
    private static final float SCROLL_STEP_FRACTION = 0.75f;

    /**
     * The accessible key of the arrow that scrolls back, and of the one that scrolls on. Negative
     * because the chevrons share one key space with the segments, whose keys are their indices;
     * the magnitudes are the two arrows' own <b>logical</b> identities, offset off zero because a
     * segment already owns it.
     */
    private static final long CHEVRON_BACK = -1;
    private static final long CHEVRON_FORWARD = -2;

    private boolean overflowing;
    private float scrollOffset;
    /** The track itself: never wider than the segments need, centred in whatever box arrives. */
    private float trackLeft;
    private float trackWidth;
    private float viewLeft;  // where the clipped viewport starts; the chevron zone is before it
    private float viewWidth;
    private int revealPending = -1; // segment to scroll into view on the next layout
    private int chevronHover; // -1 previous, +1 next, 0 neither

    /**
     * A control over the given fixed segment labels; the first is selected. Needs at least one.
     *
     * <p>Each label is wrapped as a literal, exactly as the {@code String} constructors of
     * {@link Button}, {@link Label} and {@link Checkbox} do. A literal cannot follow the
     * interface's language, so a control whose captions should is built from the
     * {@linkplain #SegmentedControl(I18nString...) localizable} constructor instead.
     *
     * @param segments the captions; at least one
     * @throws IllegalArgumentException if the list is empty
     */
    public SegmentedControl(List<String> segments) {
        this(segments.stream().map(I18nString::literal).toArray(I18nString[]::new));
    }

    /**
     * A control whose segment captions follow the UI language; the first is selected.
     *
     * <p>Every other name in the accessible tree is resolved under the subtree's own language, and
     * a caption held as a bare string is the one that cannot be: it is fixed at construction, so a
     * screen reader reads it in whatever language the caller happened to build it in.
     *
     * @param segments the captions; at least one
     * @throws IllegalArgumentException if none is given
     * @throws NullPointerException     if any of them is {@code null}
     */
    public SegmentedControl(I18nString... segments) {
        if (segments.length == 0) {
            throw new IllegalArgumentException("SegmentedControl needs at least one segment");
        }
        List<I18nString> copy = new java.util.ArrayList<>(segments.length);
        for (I18nString segment : segments) {
            copy.add(Objects.requireNonNull(segment, "segment"));
        }
        this.segments = List.copyOf(copy);
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    /**
     * @return how many segments this control has; always at least one
     */
    public int segmentCount() {
        return segments.size();
    }

    /**
     * @param index a segment in {@code [0, segmentCount)}
     * @return that segment's caption, resolved in this widget's own language
     * @throws IndexOutOfBoundsException if {@code index} is not a segment
     */
    public String segment(int index) {
        return segments.get(index).get();
    }

    /**
     * @param index a segment in {@code [0, segmentCount)}
     * @return the localizable string behind {@link #segment(int)}
     * @throws IndexOutOfBoundsException if {@code index} is not a segment
     */
    public I18nString segmentSource(int index) {
        return segments.get(index);
    }

    /**
     * Called with the chosen index whenever the selection changes: a click, an arrow key and a
     * {@link #setSelectedIndex} from code all arrive here.
     */
    public SegmentedControl onSelect(IntConsumer listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "SegmentedControl.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            if (onSelect != null) {
                onSelect.accept(selected);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /**
     * The selected segment's index; never negative, since the control refuses an empty segment
     * list and so always has exactly one selection. There is nothing to clear to.
     */
    public int selectedIndex() {
        return selected;
    }

    /**
     * Selects a segment, scrolls it into view and fires {@link #onSelect}; code and a click take
     * the same path, so a listener sees every change either way. Re-selecting the current segment
     * still reveals it (a caller asking for a segment is asking to be shown it) but changes
     * nothing and fires nothing; that early return is what keeps two controls bound to each other
     * from recursing, so do not remove it. UI thread only.
     *
     * @param index a segment in {@code [0, segmentCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not a segment. Arrowing past an end is
     *         not this: keyboard traversal stops at the end rather than raising.
     */
    public SegmentedControl setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, segments.size());
        select(index, Change.Origin.CODE);
        return this;
    }

    /**
     * The one seam every selection goes through: the public setter passes {@code CODE}, and a
     * click, an arrow key and an assistive technology's select pass {@code USER} through
     * {@link #choose}. Announces {@code SELECTION} after the layout mark, so a watcher reads the
     * new index against the bounds and the indicator of the previous pass; the geometry is a
     * pass behind by design.
     */
    private void select(int index, Change.Origin origin) {
        // Revealed even when the selection did not change: a caller re-selecting the current
        // segment is asking to be shown it, and it may well be scrolled out of sight.
        revealPending = index;
        // Contained, not a full layout: re-targeting the indicator and applying the reveal move
        // nothing outside this control's box, and a full pass repainted the whole WINDOW for
        // every arrow key -- the same defect as the focus flicker ADR 043 opened with, one frame
        // instead of eight. Safe by the scene's own check: it re-measures this control and falls
        // back to a full pass if the size moved, and a selection does not move it, since every
        // segment is sized from its label in one font whichever is selected.
        markNeedsContainedLayout();
        if (index == selected) {
            return;
        }
        selected = index;
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * What every click and key goes through. The arrow keys hand in {@code selected ± 1} and so
     * run off both ends, which is a key with nowhere to go rather than a caller's bad index;
     * hence clamped here and refused in the public setter.
     */
    /** A gesture choosing a segment: clamped, because arrowing past an end lands on the end. */
    private void choose(int index) {
        select(Math.max(0, Math.min(index, segments.size() - 1)), Change.Origin.USER);
    }

    // Cumulative segment edges from the current label widths. Takes the row AND the neutral base
    // rather than resolving either: measure and layout each call this once per pass, and two
    // resolutions that disagreed would leave bounds[] describing a different geometry than the one
    // painted.
    private float[] edges(SizeTokens t, ShapedText.Direction neutral) {
        Font font = t.body();
        TextRuler ruler = textRuler();
        float[] edge = new float[segments.size() + 1];
        float x = 0;
        for (int i = 0; i < segments.size(); i++) {
            edge[i] = x;
            // Shaped rather than measured, because a width is the only thing this control asks
            // about a label and the base direction is an input to it. The base is the shaper's
            // fallback and never an imposition: a Latin label in a right-to-left control still
            // reads left to right, and the fallback changes anything only for a label with no
            // strong character to decide with. The paint shapes the same string the same way, so
            // the width a segment is sized from is the width its label is centred by.
            String text = segments.get(i).get();
            float label = ruler.shape(text, font, ShapedText.Direction.of(text, neutral))
                    .metrics().width();
            // The one width-axis accessibility clamp on this control: a one-glyph segment at
            // the dense steps is under 24pt of label plus gutter, and a segment is a target.
            // A no-op at MEDIUM, where the narrowest segment is already ~38pt.
            x += Math.max(Strokes.MIN_HIT_TARGET, label + 2 * t.segPadH());
        }
        edge[segments.size()] = x;
        return edge;
    }


    /**
     * Physical left edge of the strip-logical span {@code [start, end)}: the one place the
     * strip's own coordinates become the screen's, and the exact inverse of
     * {@link #segmentAt(float, boolean)}.
     *
     * <p>Reading left to right a span's leading edge is its left one, so it sits at the viewport's
     * left edge pulled back by the scroll. Reading right to left the leading edge is the right
     * one, so the span is placed by its <em>end</em> against the viewport's right edge. Same
     * convention as every scroll here: zero is the leading edge and the offset is a distance
     * travelled, never a coordinate, which is what lets the clamp keep its form and the extent
     * stay a positive magnitude in both directions.
     *
     * @param rtl the direction resolved once for the pass that is calling
     */
    private float cellLeft(boolean rtl, float start, float end) {
        return rtl ? viewLeft + viewWidth - (end - scrollOffset) : viewLeft - scrollOffset + start;
    }

    /**
     * @param localX in this widget's coordinates; translated into the scrolled strip's own
     * @param rtl    the direction resolved once for the pass that is calling
     */
    private int segmentAt(float localX, boolean rtl) {
        if (bounds == null) {
            return -1;
        }
        // The inverse of cellLeft, and the only place a pointer coordinate becomes a strip one:
        // reading right to left the strip starts at the viewport's right edge, so the distance is
        // measured back from it. Only this coordinate mirrors; the walk below stays logical, and
        // so do the two ends it falls through to. Reflecting a coordinate turns a half-open cell
        // into a half-closed one, so a point exactly on a shared edge goes to the other of the two
        // segments it divides; every point still belongs to exactly one, which is what the walk
        // asks for.
        float x = rtl ? viewLeft + viewWidth - localX + scrollOffset
                : localX - viewLeft + scrollOffset;
        for (int i = 0; i < segments.size(); i++) {
            if (x >= bounds[i] && x < bounds[i + 1]) {
                return i;
            }
        }
        return x < bounds[0] ? 0 : segments.size() - 1;
    }

    /**
     * Whether {@code localX} is on the track at all. A stretched parent leaves margins either
     * side of it, and they belong to whatever is behind this control, not to its end segments;
     * without this a click far off to the right selects the last segment.
     */
    private boolean onTrack(float localX) {
        return localX >= trackLeft && localX < trackLeft + trackWidth;
    }

    /**
     * Which chevron zone {@code localX} is in. The answer is the arrow's <b>logical</b> identity,
     * so a click on it scrolls by that sign and nothing downstream has to know a direction; what
     * mirrors is which gutter holds which arrow. The back arrow is in the gutter reading starts
     * from, which is the left one reading left to right and the right one reading right to left.
     *
     * @param rtl the direction resolved once for the pass that is calling
     * @return -1 over the previous chevron, +1 over the next one, 0 over the strip itself
     */
    private int chevronAt(float localX, boolean rtl) {
        if (!overflowing || !onTrack(localX)) {
            return 0;
        }
        if (localX < viewLeft) {
            return rtl ? 1 : -1;
        }
        if (localX >= viewLeft + viewWidth) {
            return rtl ? -1 : 1;
        }
        return 0;
    }

    private float maxScrollOffset() {
        return bounds == null ? 0 : Math.max(0, bounds[segments.size()] - viewWidth);
    }

    private void scrollBy(float dx) {
        float target = Math.min(Math.max(0, scrollOffset + dx), maxScrollOffset());
        if (target == scrollOffset) {
            return;
        }
        scrollOffset = target;
        // A scroll is the newest statement of where the user wants to be looking, so it drops
        // any reveal still waiting for a layout. Without this a selection made before the
        // scroll (including re-selecting what was already selected, which still asks to be
        // revealed) is applied afterwards and yanks the strip straight back.
        revealPending = -1;
        // Layout rather than paint: the indicator's two edges are placed there, and the
        // chevrons' dead/live state is read from the new offset. Contained for the reason
        // select() gives: a scroll inside the strip moves nothing outside it.
        markNeedsContainedLayout();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        // The row and the direction are each resolved once here and handed down. The measure
        // cache is keyed on the resolved direction, so the size returned has to be a function of
        // the one this pass read, and never of a second reading of it.
        float[] edge = edges(t, neutralBase());
        float lineHeight = textRuler().measure("Hg", t.body()).lineHeight();
        return constraints.constrain(edge[segments.size()], t.resolvedHeight(lineHeight));
    }

    @Override
    protected float baselineOffset() {
        TextMetrics fm = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return (height() - fm.height()) / 2 + fm.ascent();
    }

    /**
     * Yes, and vacuously: this control paints its segments itself and has no child widgets, so
     * there is nothing a clip could fail to contain.
     *
     * <p>Answered because it is what {@link #markNeedsContainedLayout()} requires, and that is the
     * difference between a selection repainting this control and repainting the window. It clips
     * no paint of this control's own &mdash; the focus ring still reaches out through
     * {@link #paintOutset()} &mdash; since the hook concerns {@code paintChildren} alone.
     */
    @Override
    protected boolean clipsChildren() {
        return true;
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        // One resolution for the whole pass, as the size row is. The paint resolves its own, and
        // the two agree because neither is held across a change: a change of direction is a
        // relayout followed by a repaint.
        LayoutDirection direction = layoutDirection();
        boolean rtl = direction.isRightToLeft();
        bounds = edges(t, direction.neutralBase());

        // The track takes what the segments need and no more, centred in the box it was given.
        // A parent that stretches its children (a column with STRETCH, which is the common
        // case) otherwise hands this control the whole row, and a pill track drawn edge to
        // edge with four short segments huddled at its left reads as a bug, because it is one.
        float natural = bounds[segments.size()];
        trackWidth = Math.min(natural, width());
        trackLeft = (width() - trackWidth) / 2;

        // Measured against the FULL track, never the viewport: deciding overflow from a width
        // the chevrons have already been subtracted from is how a control ends up flipping
        // between overflowing and not on every pass, one chevron wide.
        overflowing = natural > trackWidth + 0.5f;
        // Square, and capped so the strip keeps at least half the track however narrow it
        // gets: the chevrons shrink with it rather than being dropped, which would move the
        // viewport's edges non-monotonically as the control is resized.
        float chevron = overflowing ? Math.min(height(), trackWidth / 4) : 0;
        viewLeft = trackLeft + chevron;
        viewWidth = Math.max(0, trackWidth - 2 * chevron);

        if (revealPending >= 0 && revealPending < segments.size() && overflowing) {
            float start = bounds[revealPending];
            float end = bounds[revealPending + 1];
            if (start < scrollOffset) {
                scrollOffset = start;
            } else if (end > scrollOffset + viewWidth) {
                scrollOffset = end - viewWidth;
            }
        }
        revealPending = -1;
        scrollOffset = overflowing ? Math.min(Math.max(0, scrollOffset), maxScrollOffset()) : 0;

        // The pill's two edges stay physical, so the animation and the paint that reads it are
        // untouched by the axis; what mirrors is where the selected segment's cell landed.
        float cellLeft = cellLeft(rtl, bounds[selected], bounds[selected + 1]);
        float cellWidth = bounds[selected + 1] - bounds[selected];
        float left = cellLeft + t.segInset();
        float right = cellLeft + cellWidth - t.segInset();
        if (!indicatorPlaced || indicatorSegment != selected) {
            // A different segment: slide, which is the animation this control is known for.
            if (indicatorPlaced) {
                indicatorLeft.to(left);
                indicatorRight.to(right);
            } else {
                indicatorLeft.snap(left);
                indicatorRight.snap(right);
                indicatorPlaced = true;
            }
        } else if (left != indicatorTargetLeft || right != indicatorTargetRight) {
            // The SAME segment at a new place: a scroll, or a resize. Sliding here would send
            // the pill chasing a selection that never moved, and a scroll of several segments
            // would have it drift across the whole strip to end up where it started.
            //
            // And only at a NEW place: the same segment laid out where it already was is some
            // other widget's layout pass arriving mid-slide, and snapping then cut the slide
            // short. ADR 043 §9.4.4.
            indicatorLeft.snap(left);
            indicatorRight.snap(right);
        }
        indicatorTargetLeft = left;
        indicatorTargetRight = right;
        indicatorSegment = selected;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        // One resolution for this whole paint, threaded into the two things that need it: where
        // the cells land, and which gutter each chevron is drawn in. The track, the border, the
        // clip and the focus ring are all symmetric about the track's centre and take no branch.
        LayoutDirection direction = layoutDirection();
        boolean rtl = direction.isRightToLeft();
        canvas.fillRoundRect(trackLeft, 0, trackWidth, height(), t.radiusMedium(), theme.surface);
        canvas.drawRoundRect(trackLeft + Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                trackWidth - 2 * Strokes.HALF_PIXEL_INSET, height() - 2 * Strokes.HALF_PIXEL_INSET,
                t.radiusMedium(), Strokes.BORDER, theme.outline);

        float indLeft = indicatorLeft.value();
        float indRight = indicatorRight.value();
        Color accent = isEnabled() ? theme.primary : theme.disabledFill;

        // Everything that scrolls is drawn inside the clip, and nothing outside it: a segment
        // past the last one that fits would otherwise paint over whatever the control is
        // sitting next to, which reads as the control having no bounds rather than as its
        // content being too wide. Clipping the paint clips the hit test with it (a widget's
        // own bounds already cut that), so the two cannot disagree.
        canvas.save();
        canvas.clipRect(viewLeft, 0, viewWidth, height());
        // Tighter than the track radius so the inset pill reads as concentric with the outer
        // rounding. segPillRadius() owns it: the concentric radiusMedium - segInset, at every
        // step: MEDIUM carries no pinned fudge of its own. Never re-derive it here.
        canvas.fillRoundRect(indLeft, t.segInset(), Math.max(0, indRight - indLeft),
                height() - 2 * t.segInset(), t.segPillRadius(), accent);

        Font font = t.body();
        TextRuler ruler = textRuler();
        TextMetrics fm = ruler.measure("Hg", font);
        ShapedText.Direction neutral = direction.neutralBase();
        for (int i = 0; i < segments.size(); i++) {
            float cellLeft = cellLeft(rtl, bounds[i], bounds[i + 1]);
            float cellWidth = bounds[i + 1] - bounds[i];
            if (cellLeft + cellWidth < viewLeft || cellLeft > viewLeft + viewWidth) {
                continue; // wholly outside the viewport: the clip would drop it anyway
            }
            String label = segments.get(i).get();
            // The same shaping the segment was sized from, so the label is centred by its own
            // width and not by a second opinion about it. A label is centred INSIDE its cell,
            // which is symmetric and so is the same offset in both directions; only the cell moves.
            ShapedText line = ruler.shape(label, font, ShapedText.Direction.of(label, neutral));
            float textWidth = line.metrics().width();
            Color ink = !isEnabled() ? theme.disabledText
                    : i == selected ? theme.onPrimary
                    : i == hoverIndex ? theme.text : theme.textMuted;
            canvas.drawText(line, cellLeft + (cellWidth - textWidth) / 2,
                    (height() - fm.height()) / 2 + fm.ascent(), ink);
        }
        canvas.restore();

        if (overflowing) {
            // The two gutters are physical and symmetric; what mirrors is which arrow goes in
            // which, and which way its ink points. The -1/+1 handed over stays the arrow's
            // LOGICAL identity, because that is what the hover state and the scroll step mean.
            float leftGutter = (trackLeft + viewLeft) / 2;
            float rightGutter = (viewLeft + viewWidth + trackLeft + trackWidth) / 2;
            paintChevron(canvas, t, theme, -1, rtl ? rightGutter : leftGutter, rtl ? 1 : -1,
                    scrollOffset > 0.5f);
            paintChevron(canvas, t, theme, 1, rtl ? leftGutter : rightGutter, rtl ? -1 : 1,
                    scrollOffset < maxScrollOffset() - 0.5f);
        }

        float focus = focusFade.value();
        if (focus > 0.001f) {
            // Around the TRACK, not the widget box: the box is whatever a stretching parent
            // handed over, and a ring around empty margin points at nothing. Outside it, with a
            // clear gap, and never over the fill, the same shape every other focusable control
            // here draws, because a control that invents its own focus affordance is one the
            // keyboard user has to learn twice. Gated on the fade value rather than isFocused,
            // so the ring keeps rendering while it fades out.
            float gapOut = Strokes.FOCUS_GAP_BUTTON;
            canvas.drawRoundRect(trackLeft - gapOut, -gapOut,
                    trackWidth + 2 * gapOut, height() + 2 * gapOut,
                    t.radiusMedium() + gapOut, Strokes.FOCUS_RING,
                    theme.focusRing.withAlpha(focus));
        }
    }

    /**
     * The focus ring reaches {@link Strokes#FOCUS_RING_OUTSET} beyond the box, and the scene
     * inflates a widget's damage by {@code 1 + paintOutset()}. Without this the ring's fade-out
     * sheds stale pixels under partial rendering.
     *
     * <p>Declares reach only; it moves no ink.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }

    /**
     * One scroll arrow, drawn with two strokes rather than set as a glyph: a font with no
     * coverage for an arrow character renders tofu, which has happened here before, and the
     * chevron is chrome that must look the same in every language.
     *
     * <p>The arrow's identity and its pointing are two parameters and not one, because reading
     * right to left they disagree: the arrow that scrolls <em>back</em> points physically right
     * there. The identity is what the hover state and the scroll step are keyed on and is logical
     * in both directions; the pointing is ink, and is decided by the caller that already resolved
     * the direction, so it is decided in one place rather than twice.
     *
     * @param direction -1 for the arrow that scrolls back, +1 for the one that scrolls on
     * @param pointing  which way that arrow's ink points: -1 physically left, +1 physically right
     * @param live      whether there is anything left to scroll that way; a dead arrow is drawn
     *                  disabled rather than dropped, because dropping it would resize the
     *                  viewport and make the strip jump at each end
     */
    private void paintChevron(Canvas canvas, SizeTokens t, Theme theme,
                              int direction, float cx, int pointing, boolean live) {
        Color ink = !isEnabled() || !live ? theme.disabledText
                : chevronHover == direction ? theme.text : theme.textMuted;
        float cy = height() / 2;
        float s = t.tabChevron();
        float tip = cx + pointing * s / 2;
        float tail = cx - pointing * s / 2;
        canvas.drawLine(tail, cy - s, tip, cy, Strokes.ARROW_PEN, ink);
        canvas.drawLine(tip, cy, tail, cy + s, Strokes.ARROW_PEN, ink);
    }

    // ------------------------------------------------------------------- accessibility

    /**
     * What this strip is to an assistive technology: one horizontal
     * {@link Accessible.Role#RADIO_GROUP} holding one {@link Accessible.Role#RADIO_BUTTON} per
     * segment, plus the two scroll arrows while it overflows.
     *
     * <p>A radio group and not a tab list, for the reason this class's own documentation gives:
     * it owns no content, it takes labels and hands back an index, so there is no page for a tab
     * to select and "tab, 1 of 4" would offer navigation that leads nowhere. The selection facet
     * is single and always satisfied, which is this control's documented invariant on both
     * halves — the constructor refuses an empty segment list, and the selected index is never
     * negative because there is nothing to clear to. The active descendant is not declared here
     * and could not be: the publish step resolves it from the first node in this subtree that
     * says it is active, which is the selected segment below.
     *
     * <p>The scroll facet is published on every frame rather than only while the strip overflows,
     * so that resizing past the fitting point moves two numbers instead of making a whole facet
     * appear and disappear. Every value is read from the fields the layout settled and from this
     * widget's own maximum, so the facet cannot drift from the clamp that produced it, and it is
     * derived from {@code overflowing} rather than from the arithmetic alone: that predicate
     * carries the half-point slop below which every path here declines to scroll at all. The
     * offset is a distance from the leading edge in both directions, so the percentage is
     * published unflipped; the vertical pair is the axis's nothing-to-scroll answer.
     *
     * <p><b>Where the segment boxes come from, which is the whole difficulty of this widget.</b>
     * A segment's box is neither the widget's box divided by the count nor a raw edge out of
     * {@link #bounds}: the track takes only what the segments need and centres itself in whatever
     * a stretching parent handed over, the viewport is inset by the two chevron gutters, the
     * scroll offset is subtracted, and reading right to left a span is placed by its end.
     * {@link #cellLeft(boolean, float, float)} folds all four together and is called here rather
     * than re-derived, so the box a reader is given is the box a click lands in. Tree order stays
     * the reading order in both directions; only the coordinates mirror.
     *
     * <p>The geometry is guarded and the children are not, because {@code bounds} is null until
     * the first layout and that state reaches a published tree: a strip inside an invisible
     * subtree is skipped by the layout and still walked, so an unguarded index would throw inside
     * the publish step. What a reader is told there — how many segments there are and which one
     * is selected — does not depend on a layout having run, and gaining the children on the first
     * one would be a structure change for nothing.
     *
     * <p>The hover index is deliberately not published. It is a pointer affordance, and a fact
     * derived from it would republish the whole tree on every mouse move; so are the indicator's
     * slide, the focus fade and the segment it is resting on. Nothing here formats a string:
     * every name is an {@link I18nString} this widget holds, handed over by reference, so a frame
     * damaged by any of those compares equal and allocates nothing.
     *
     * <p>The group names itself with nothing. A radio group's name is the question it asks, which
     * only the application has, and this is a public class an application reaches with
     * {@code setAccessibleName}, a bound caption or a tooltip. Every segment carries a real name,
     * so an unnamed group still announces its selection.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.RADIO_GROUP);
        a.state(Accessible.State.HORIZONTAL);
        // The container half of the ROWS shape (ADR 045 §3): one segment is always chosen, and
        // the group has no PRESS of its own.
        RowsAccessibility.describeContainer(a, RowsAccessibility.Selection.SINGLE, true, false);
        // One resolution for the whole hook, beside the paint's and the hit test's and for the
        // same reason they give: two resolutions that disagreed inside one pass would describe a
        // segment at its neighbour's rectangle.
        boolean rtl = isRightToLeft();
        float content = bounds == null ? 0 : bounds[segments.size()];
        float max = maxScrollOffset();
        boolean scrollable = overflowing && max > 0;
        a.scrollFrom(scrollOffset, scrollable ? max : 0, viewWidth, scrollable ? content : 0,
                0, 0, 1, 0);

        // Reading order: the arrow that scrolls back, the segments, the arrow that scrolls on.
        if (overflowing) {
            describeChevron(a, CHEVRON_BACK, rtl, scrollOffset > 0.5f);
        }
        for (int i = 0; i < segments.size(); i++) {
            a.child(i);
            if (bounds != null) {
                float left = cellLeft(rtl, bounds[i], bounds[i + 1]);
                float cellWidth = bounds[i + 1] - bounds[i];
                a.bounds(left, 0, cellWidth, height());
                if (left + cellWidth < viewLeft || left > viewLeft + viewWidth) {
                    // The paint loop's own skip test, negated: a segment the clip drops is
                    // visible and not on screen, and this is the only route to that pair.
                    a.offScreen();
                }
            }
            a.role(Accessible.Role.RADIO_BUTTON);
            // The string this control holds and never the string it reads as: the difference
            // compares a reference, a language and a translation epoch, and get() would allocate
            // one string per segment per damaged frame.
            a.name(segments.get(i), Accessible.NameFrom.CONTENT);
            // The ROWS shape, written once (ADR 045 §3), read against this widget: the
            // membership; the cursor mark on the chosen segment, because selection and cursor
            // are one thing here — the arrows call choose(), which moves the selection itself,
            // and there is no separate highlight; without the bit the tree resolves no cursor
            // below the group and walking the strip with Left and Right tells a reader nothing
            // about where the user is — and only while the group holds the keyboard (ADR 039
            // §1.10, amended 2026-09-14); and SELECT alone, no press, as RadioButton answers:
            // the two radio surfaces must answer alike or a bridge's table has to special-case
            // one of them. No FOCUS (decision 11) and no SCROLL_INTO_VIEW.
            RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                    RowsAccessibility.Selection.SINGLE, i == selected, i + 1, segments.size(),
                    false, false, i == selected && isFocused(), false, false, false);
            a.endChild();
        }
        if (overflowing) {
            describeChevron(a, CHEVRON_FORWARD, rtl, scrollOffset < maxScrollOffset() - 0.5f);
        }
    }

    /**
     * Publishes one scroll arrow as the gutter a click on it lands in, which is the zone
     * {@link #chevronAt(float, boolean)} maps and not the smaller glyph drawn inside it.
     *
     * <p>The arrow's identity is logical and which gutter holds it is not: the back arrow sits in
     * the gutter reading starts from, so the two swap ends right to left while the tree order
     * above does not move.
     *
     * <p><b>A dead side is published disabled, with no verb</b> — the shape of a refused
     * calendar day, which semantics 5 names for every disabled synthetic item: not
     * {@code ENABLED} through {@link Accessibility#disabled()} (decision 30, narrowing only, so
     * the arrow can be less enabled than the strip and never more), and no {@code PRESS}. Until
     * 2026-09-14 the dead side dropped the verb without the state, because a widget could not say
     * "disabled" of a synthetic child; from that day to 2026-09-15 it carried the state and kept
     * the verb, so that a pattern set Windows froze on first read (W2) would not lose Invoke when
     * the side came alive. Phase 3 rebuilds pattern sets on a change, and the verb goes. The
     * arrow declares {@code PRESS} either way and the walk withdraws it from every node that is
     * not {@code ENABLED} (ADR 039 §1.5, amended 2026-09-15), so the narrowing is the only thing
     * written here.
     * {@link #onSyntheticAction} still answers a press that arrives on a dead side anyway by
     * whether the scroll moved, which on that side is not at all.
     *
     * @param a    the node being described
     * @param key  {@link #CHEVRON_BACK} or {@link #CHEVRON_FORWARD}
     * @param rtl  the direction resolved once for the pass that is calling
     * @param live whether there is anything left to scroll that way
     */
    private void describeChevron(Accessibility a, long key, boolean rtl, boolean live) {
        boolean back = key == CHEVRON_BACK;
        float trailingX = viewLeft + viewWidth;
        // Computed rather than assumed equal to the leading one: they are equal by construction
        // today, and a rounding that made them differ belongs in the box and not in a comment.
        float trailingWidth = trackLeft + trackWidth - trailingX;
        boolean trailing = back == rtl;
        a.child(key);
        a.bounds(trailing ? trailingX : trackLeft, 0,
                trailing ? trailingWidth : viewLeft - trackLeft, height());
        a.role(Accessible.Role.BUTTON);
        a.name(back ? ComponentStrings.SEGMENT_PREVIOUS : ComponentStrings.SEGMENT_NEXT,
                Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        if (!live) {
            // Disabled, and so with no verb (decision 30; semantics 5, 2026-09-15): the walk takes
            // PRESS off a node that is not ENABLED, as it takes SELECT off a refused day, and a
            // bridge reads the absence. The verb was kept here on 2026-09-14 so that Windows'
            // frozen pattern set would not lose Invoke when the side came alive (W2); phase 3
            // rebuilds pattern sets on a change, so the verb goes.
            a.disabled();
        }
        a.endChild();
    }

    /**
     * Selects a segment or scrolls the strip, through the same private paths a click takes.
     *
     * <p>A select reaches {@link #choose}, which is literally what the click branch calls, so an
     * assistive technology's select clamps the same way, reveals the same way and fires
     * {@link #onSelect} through the same {@code USER} seam a click does — including the early
     * return that changes nothing and fires nothing when the segment was already selected.
     * Re-selecting the current segment is still accepted, because it still reveals, which is the
     * answer the pointer gets there.
     *
     * <p>A press on an arrow scrolls by the same expression the click branch uses, where the sign
     * is the arrow's logical identity and the offset is logical too, so it needs no mirroring.
     * The answer is whether the offset moved: a dead arrow is published disabled and without the
     * verb above, and its scroll clamps to nothing here, so a press that arrived on it anyway is
     * refused rather than reported done.
     *
     * <p>A segment answers {@code SELECT} alone and never {@code FOCUS}: the selection is the
     * cursor here, so a focus that did not select would be a lie and one that did would be
     * {@code SELECT} under another name (decision 11; ADR 039 §1.5's amendment of 2026-09-14).
     * The walk grants {@code FOCUS} only to a focusable widget node, and a segment is not one,
     * so the verb is neither published nor performed.
     *
     * <p>No enabled guard of its own, and none is owed. The scene's dispatcher already walks this
     * widget and every ancestor for the enabled flag, refuses an owner that is not showing,
     * refuses a modal-blocked window and refuses anything outside the layer that owns input, all
     * before this runs. It runs on the UI thread inside the posted task, which is what
     * {@link #setSelectedIndex}'s own thread check asks for.
     *
     * @param key    a segment's index, or one of the two chevron keys
     * @param action what is being asked
     * @param arg    unused; every verb here is parameterless
     * @return whether this control did it
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if (key >= 0) {
            if (key >= segments.size()) {
                return false;
            }
            return RowsAccessibility.performOnRow(rowsHost, (int) key, action);
        }
        if (key != CHEVRON_BACK && key != CHEVRON_FORWARD
                || action != Accessible.Action.PRESS) {
            return false;
        }
        int chevron = key == CHEVRON_BACK ? -1 : 1;
        float before = scrollOffset;
        scrollBy(chevron * SCROLL_STEP_FRACTION * viewWidth);
        return scrollOffset != before;
    }

    /**
     * The control's mechanisms as the rows shape drives them, over a segment's index:
     * {@code SELECT} is {@link #choose}, which is what a click and the arrows do. The cursor is
     * the selection, so {@code FOCUS} is refused before it could reach {@link #moveCursor};
     * segments have no activation of their own and nothing reveals, because neither verb is
     * published on one.
     */
    private final class RowsHost implements RowsAccessibility.Host<Integer> {
        @Override
        public RowsAccessibility.Selection selection() {
            return RowsAccessibility.Selection.SINGLE;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return true;
        }

        @Override
        public boolean rowsActivate() {
            return false;
        }

        @Override
        public boolean isSelected(Integer index) {
            return index == selected;
        }

        @Override
        public boolean select(Integer index, boolean moveCursor) {
            choose(index);
            return true;
        }

        @Override
        public void moveCursor(Integer index) {
            throw new UnsupportedOperationException("the cursor is the selection: FOCUS is refused");
        }

        @Override
        public void reveal(Integer index) {
            // Not published on a segment, so never asked for.
        }
    }

    private final RowsHost rowsHost = new RowsHost();

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // The pointer arrives PHYSICAL and stays that way. The reflection lives in the two
        // functions that turn an x into a strip coordinate or a gutter, and applying it here as
        // well would flip every branch below a second time.
        boolean rtl = isRightToLeft();
        float lx = sceneToLocalX(event.x());
        switch (event.type()) {
            case MOVE, ENTER -> {
                int chevron = chevronAt(lx, rtl);
                int i = chevron == 0 && onTrack(lx) ? segmentAt(lx, rtl) : -1;
                if (i != hoverIndex || chevron != chevronHover) {
                    hoverIndex = i;
                    chevronHover = chevron;
                    invalidate();
                }
            }
            case EXIT -> {
                if (hoverIndex != -1 || chevronHover != 0) {
                    hoverIndex = -1;
                    chevronHover = 0;
                    invalidate();
                }
            }
            case CLICK -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    event.consume();
                    int chevron = chevronAt(lx, rtl);
                    if (chevron != 0) {
                        // The identity is logical and so is scrollOffset, so this step is the
                        // same expression in both directions.
                        scrollBy(chevron * SCROLL_STEP_FRACTION * viewWidth);
                    } else if (onTrack(lx)) {
                        choose(segmentAt(lx, rtl));
                    }
                }
            }
            case WHEEL -> {
                if (!overflowing) {
                    return; // nothing to scroll: leave the wheel to whatever encloses this
                }
                float delta = event.scrollX() != 0 ? event.scrollX() : event.scrollY();
                if (delta != 0) {
                    event.consume();
                    // A detent is a device unit, so the same flick travels the same distance
                    // in a dense control and a roomy one: locked, not tabled. And it is a
                    // PHYSICAL gesture, so this sign is the same in both directions: the strip's
                    // origin already turns an advancing offset into the opposite physical
                    // movement, and flipping here too would undo it and scroll the wrong way.
                    scrollBy(-delta * Strokes.WHEEL_STEP);
                }
            }
            case PRESS -> event.consume();
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        // A strip of items in reading order, so the arrows name a SIDE and mirror with the strip:
        // Left selects the segment drawn on the left, which is the previous one reading left to
        // right and the next one reading right to left. A key that stayed logical would move the
        // selection away from the pointer, and the control would disagree with itself.
        boolean rtl = isRightToLeft();
        switch (event.key()) {
            case Keys.LEFT -> {
                choose(rtl ? selected + 1 : selected - 1);
                event.consume();
            }
            case Keys.RIGHT -> {
                choose(rtl ? selected - 1 : selected + 1);
                event.consume();
            }
            // The jump keys the tab strip this control is modelled on already answers. They matter
            // more here since overflow landed: reaching the last segment of a long strip is
            // otherwise one press per segment, and choose() already reveals whatever it selects.
            // They name the first and last SEGMENT rather than a side, so they do not mirror:
            // Home is the first segment in both directions, wherever it is drawn.
            case Keys.HOME -> {
                choose(0);
                event.consume();
            }
            case Keys.END -> {
                choose(segments.size() - 1);
                event.consume();
            }
            default -> {
            }
        }
    }
}
