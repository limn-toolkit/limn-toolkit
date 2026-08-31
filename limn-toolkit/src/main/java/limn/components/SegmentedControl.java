package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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

    private final List<String> segments;
    private int selected;
    private int hoverIndex = -1;
    private Consumer<Integer> onSelect = index -> {
    };
    // The selected indicator slides by animating its two x edges (snap on first layout).
    private final Transition indicatorLeft =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    private final Transition indicatorRight =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    /** Fades the focus ring in and out, so keyboard focus arrives rather than blinks on. */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private boolean indicatorPlaced;
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

    private boolean overflowing;
    private float scrollOffset;
    /** The track itself: never wider than the segments need, centred in whatever box arrives. */
    private float trackLeft;
    private float trackWidth;
    private float viewLeft;  // where the clipped viewport starts; the chevron zone is before it
    private float viewWidth;
    private int revealPending = -1; // segment to scroll into view on the next layout
    private int chevronHover; // -1 previous, +1 next, 0 neither

    /** A control over the given segment labels; the first is selected. Needs at least one. */
    public SegmentedControl(List<String> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("SegmentedControl needs at least one segment");
        }
        this.segments = List.copyOf(segments);
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    /**
     * Called with the chosen index whenever the selection changes: a click, an arrow key and a
     * {@link #setSelectedIndex} from code all arrive here.
     */
    public SegmentedControl onSelect(Consumer<Integer> listener) {
        Ui.checkUiThread();
        this.onSelect = Objects.requireNonNull(listener, "listener");
        return this;
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
        // Revealed even when the selection did not change: a caller re-selecting the current
        // segment is asking to be shown it, and it may well be scrolled out of sight.
        revealPending = index;
        markNeedsLayout(); // re-target the indicator, and apply the reveal
        if (index == selected) {
            return this;
        }
        selected = index;
        onSelect.accept(selected);
        return this;
    }

    /**
     * What every click and key goes through. The arrow keys hand in {@code selected ± 1} and so
     * run off both ends, which is a key with nowhere to go rather than a caller's bad index;
     * hence clamped here and refused in the public setter.
     */
    private void choose(int index) {
        setSelectedIndex(Math.max(0, Math.min(index, segments.size() - 1)));
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
            String text = segments.get(i);
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
     * The shaper's fallback for a label that has no strong character of its own: this control's
     * own reading direction, which is the direction of the interface around the label.
     *
     * <p>Resolved by the caller and handed in, never read here and never in a constructor, for
     * the reason every axis in this toolkit is: a value captured before the tree is complete is
     * permanently wrong, and two resolutions inside one pass measure one geometry and paint
     * another.
     */
    private static ShapedText.Direction neutralBase(LayoutDirection direction) {
        return direction == LayoutDirection.RTL
                ? ShapedText.Direction.RTL
                : ShapedText.Direction.LTR;
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
        // chevrons' dead/live state is read from the new offset.
        markNeedsLayout();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        // The row and the direction are each resolved once here and handed down. The measure
        // cache is keyed on the resolved direction, so the size returned has to be a function of
        // the one this pass read, and never of a second reading of it.
        float[] edge = edges(t, neutralBase(layoutDirection()));
        float lineHeight = textRuler().measure("Hg", t.body()).lineHeight();
        return constraints.constrain(edge[segments.size()], t.resolvedHeight(lineHeight));
    }

    @Override
    protected float baselineOffset() {
        TextMetrics fm = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return (height() - fm.height()) / 2 + fm.ascent();
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        // One resolution for the whole pass, as the size row is. The paint resolves its own, and
        // the two agree because neither is held across a change: a change of direction is a
        // relayout followed by a repaint.
        LayoutDirection direction = layoutDirection();
        boolean rtl = direction == LayoutDirection.RTL;
        bounds = edges(t, neutralBase(direction));

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
        } else {
            // The SAME segment at a new place: a scroll, or a resize. Sliding here would send
            // the pill chasing a selection that never moved, and a scroll of several segments
            // would have it drift across the whole strip to end up where it started.
            indicatorLeft.snap(left);
            indicatorRight.snap(right);
        }
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
        boolean rtl = direction == LayoutDirection.RTL;
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
        ShapedText.Direction neutral = neutralBase(direction);
        for (int i = 0; i < segments.size(); i++) {
            float cellLeft = cellLeft(rtl, bounds[i], bounds[i + 1]);
            float cellWidth = bounds[i + 1] - bounds[i];
            if (cellLeft + cellWidth < viewLeft || cellLeft > viewLeft + viewWidth) {
                continue; // wholly outside the viewport: the clip would drop it anyway
            }
            String label = segments.get(i);
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

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // The pointer arrives PHYSICAL and stays that way. The reflection lives in the two
        // functions that turn an x into a strip coordinate or a gutter, and applying it here as
        // well would flip every branch below a second time.
        boolean rtl = layoutDirection() == LayoutDirection.RTL;
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
        boolean rtl = layoutDirection() == LayoutDirection.RTL;
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
