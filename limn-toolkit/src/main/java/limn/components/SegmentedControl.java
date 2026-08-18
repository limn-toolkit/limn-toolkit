package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
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
    private float[] bounds; // n+1 cumulative segment edges, from onLayout
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

    // Cumulative segment edges from the current label widths. Takes the row rather than
    // resolving it: measure and layout each call this once per pass, and two resolutions that
    // disagreed would leave bounds[] describing a different geometry than the one painted.
    private float[] edges(SizeTokens t) {
        Font font = t.body();
        float[] edge = new float[segments.size() + 1];
        float x = 0;
        for (int i = 0; i < segments.size(); i++) {
            edge[i] = x;
            // The one width-axis accessibility clamp on this control: a one-glyph segment at
            // the dense steps is under 24pt of label plus gutter, and a segment is a target.
            // A no-op at MEDIUM, where the narrowest segment is already ~38pt.
            float label = textRuler().measure(segments.get(i), font).width();
            x += Math.max(Strokes.MIN_HIT_TARGET, label + 2 * t.segPadH());
        }
        edge[segments.size()] = x;
        return edge;
    }

    /** @param localX in this widget's coordinates; translated into the scrolled strip's own */
    private int segmentAt(float localX) {
        if (bounds == null) {
            return -1;
        }
        float x = localX - viewLeft + scrollOffset;
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

    /** @return -1 over the previous chevron, +1 over the next one, 0 over the strip itself */
    private int chevronAt(float localX) {
        if (!overflowing || !onTrack(localX)) {
            return 0;
        }
        if (localX < viewLeft) {
            return -1;
        }
        return localX >= viewLeft + viewWidth ? 1 : 0;
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
        float[] edge = edges(t);
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
        bounds = edges(t);

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

        float origin = viewLeft - scrollOffset;
        float left = origin + bounds[selected] + t.segInset();
        float right = origin + bounds[selected + 1] - t.segInset();
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
        TextMetrics fm = textRuler().measure("Hg", font);
        float origin = viewLeft - scrollOffset;
        for (int i = 0; i < segments.size(); i++) {
            float cellLeft = origin + bounds[i];
            float cellWidth = bounds[i + 1] - bounds[i];
            if (cellLeft + cellWidth < viewLeft || cellLeft > viewLeft + viewWidth) {
                continue; // wholly outside the viewport: the clip would drop it anyway
            }
            String label = segments.get(i);
            float textWidth = textRuler().measure(label, font).width();
            Color ink = !isEnabled() ? theme.disabledText
                    : i == selected ? theme.onPrimary
                    : i == hoverIndex ? theme.text : theme.textMuted;
            canvas.drawText(label, cellLeft + (cellWidth - textWidth) / 2,
                    (height() - fm.height()) / 2 + fm.ascent(), font, ink);
        }
        canvas.restore();

        if (overflowing) {
            paintChevron(canvas, t, theme, -1, (trackLeft + viewLeft) / 2, scrollOffset > 0.5f);
            paintChevron(canvas, t, theme, 1,
                    (viewLeft + viewWidth + trackLeft + trackWidth) / 2,
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
     * @param direction -1 for the arrow pointing back, +1 for the one pointing on
     * @param live      whether there is anything left to scroll that way; a dead arrow is drawn
     *                  disabled rather than dropped, because dropping it would resize the
     *                  viewport and make the strip jump at each end
     */
    private void paintChevron(Canvas canvas, SizeTokens t, Theme theme,
                              int direction, float cx, boolean live) {
        Color ink = !isEnabled() || !live ? theme.disabledText
                : chevronHover == direction ? theme.text : theme.textMuted;
        float cy = height() / 2;
        float s = t.tabChevron();
        float tip = cx + direction * s / 2;
        float tail = cx - direction * s / 2;
        canvas.drawLine(tail, cy - s, tip, cy, Strokes.ARROW_PEN, ink);
        canvas.drawLine(tip, cy, tail, cy + s, Strokes.ARROW_PEN, ink);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        float lx = sceneToLocalX(event.x());
        switch (event.type()) {
            case MOVE, ENTER -> {
                int chevron = chevronAt(lx);
                int i = chevron == 0 && onTrack(lx) ? segmentAt(lx) : -1;
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
                    int chevron = chevronAt(lx);
                    if (chevron != 0) {
                        scrollBy(chevron * SCROLL_STEP_FRACTION * viewWidth);
                    } else if (onTrack(lx)) {
                        choose(segmentAt(lx));
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
                    // in a dense control and a roomy one: locked, not tabled.
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
        switch (event.key()) {
            case Keys.LEFT -> {
                choose(selected - 1);
                event.consume();
            }
            case Keys.RIGHT -> {
                choose(selected + 1);
                event.consume();
            }
            // The jump keys the tab strip this control is modelled on already answers. They matter
            // more here since overflow landed: reaching the last segment of a long strip is
            // otherwise one press per segment, and choose() already reveals whatever it selects.
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
