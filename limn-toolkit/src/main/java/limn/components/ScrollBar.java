package limn.components;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;

import java.util.Objects;

/**
 * The one scrollbar shared by every scrollable component ({@link ListView},
 * {@link TextArea}, {@link ScrollView}, …), so they look and behave identically.
 * It is an <b>overlay</b> widget the host lays out along an edge; it reads/writes
 * scroll through a {@link Model} and handles its own thumb drag and track paging.
 *
 * <ul>
 *   <li><b>Themed</b>: the thumb is drawn in {@link Theme#textMuted}, so it is
 *       visible on both light and dark surfaces.</li>
 *   <li><b>Draggable</b>: drag the thumb, or click the track to page.</li>
 *   <li><b>Policy</b> ({@link #setPolicy}): {@link Policy#HIDDEN},
 *       {@link Policy#ALWAYS}, {@link Policy#ON_SCROLL} (fades in on scroll) or
 *       {@link Policy#AUTO} (also while the pointer is active over the host).</li>
 *   <li><b>Thin idle / wide on approach</b>: a slim bar that widens when the
 *       pointer is over it or dragging, ready to grab.</li>
 *   <li><b>Mirrored</b>: a <em>horizontal</em> bar reads with its subtree, so right to left the
 *       thumb rests at the right end of the track and walks left as the offset grows. A vertical
 *       bar's travel is a y and never mirrors; only the side its thumb hugs does, following the
 *       side the host puts the strip on.</li>
 * </ul>
 * The host calls {@link #onScrolled()} when it scrolls and {@link #onHostActivity()}
 * on pointer movement (for {@code AUTO}).
 */
public class ScrollBar extends Widget {

    /** Which axis the bar controls. */
    public enum Orientation { VERTICAL, HORIZONTAL }

    /** When the bar is shown. */
    public enum Policy {
        /** Never shown. */
        HIDDEN,
        /** Always shown while the content overflows. */
        ALWAYS,
        /** Fades in on scroll (and while hovered/dragged), then fades out. */
        ON_SCROLL,
        /** {@code ON_SCROLL} plus visible while the pointer is active over the host. */
        AUTO
    }

    /** The scroll state the bar reflects and drives. */
    public interface Model {
        /** @return total content extent along the bar's axis */
        float contentLength();

        /** @return visible extent along the bar's axis */
        float viewportLength();

        /** @return current scroll offset along the bar's axis */
        float offset();

        /** Scrolls to {@code offset} (the host clamps). */
        void setOffset(float offset);
    }

    private static final float THIN = 5;
    private static final float WIDE = 11;
    /** Breathing room around the track, the toolkit-wide constant so painting and dragging agree. */
    private static final float MARGIN = Strokes.SCROLLBAR_MARGIN;
    private static final float MIN_THUMB = 28;
    private static final double WIDTH_SECONDS = 0.10;
    private static final double FADE_IN_SECONDS = 0.09;
    private static final double FADE_OUT_SECONDS = 0.28;
    private static final double HOLD_SECONDS = 1.1;

    private final Orientation orientation;
    private final Model model;
    private Policy policy = Policy.AUTO;

    private boolean hoverBar;
    private boolean dragging;
    private float dragGrab;
    private double sinceScroll = HOLD_SECONDS;
    private double sinceHostActivity = HOLD_SECONDS;
    /** Last answer of {@link #hasOverflow}, so the first time it turns true can announce itself. */
    private boolean overflowed;
    private boolean ticking;
    // Reused toolkit animators: the bar fades and the thumb widens through these
    // instead of hand-rolled interpolation. Opacity's duration is set per direction
    // (quick in, slow out) right before each target change.
    private final Transition widthLevel = new Transition(this).duration(WIDTH_SECONDS).easing(Easing.LINEAR);
    private final Transition opacity = new Transition(this).easing(Easing.LINEAR);

    /** A bar reading its extent and offset from {@code model}. */
    public ScrollBar(Orientation orientation, Model model) {
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.model = Objects.requireNonNull(model, "model");
        // A plain arrow over the bar, overriding any text/pointer cursor the
        // scrolling host (e.g. a TextArea's I-beam) sets on its subtree.
        setCursor(Cursor.DEFAULT);
    }

    /** When the bar is shown: always, never, or only while the content overflows. */
    public ScrollBar setPolicy(Policy newPolicy) {
        this.policy = Objects.requireNonNull(newPolicy, "policy");
        refresh();
        return this;
    }

    /** The current visibility policy. */
    public Policy policy() {
        return policy;
    }

    private boolean vertical() {
        return orientation == Orientation.VERTICAL;
    }

    /**
     * The resolved direction of this bar's subtree. Read <b>once</b> at the top of a pass —
     * {@code onPaint}, or one arm of {@link #onMouseEvent} — and handed down as a parameter,
     * never re-read inside the geometry it feeds: a press resolves the thumb rectangle and the
     * paging sign from the same answer, and two answers inside one event would page away from
     * the thumb the pointer just missed.
     */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    /**
     * Whether this bar's own axis mirrors. Only a horizontal bar has a reading direction; a
     * vertical bar's track coordinates are y values, which a right-to-left subtree leaves
     * exactly where they are.
     *
     * @param rtl the direction resolved once by the caller's pass
     */
    private boolean mirrored(boolean rtl) {
        return rtl && !vertical();
    }

    /** The strip thickness the host reserves/lays the bar out with. */
    public static float thickness() {
        return WIDE + 2 * MARGIN;
    }

    /** The host scrolled: reveal the bar (ON_SCROLL / AUTO). */
    public void onScrolled() {
        sinceScroll = 0;
        ensureTicking();
    }

    /** The pointer is active over the host: reveal the bar (AUTO). */
    public void onHostActivity() {
        if (policy == Policy.AUTO) {
            sinceHostActivity = 0;
            ensureTicking();
        }
    }

    /**
     * Re-evaluate visibility after the content/overflow changed.
     *
     * <p>Content that has <b>just</b> become scrollable reveals the bar for one hold-and-fade, the
     * way an overlay scroller flashes when a pane first has something to scroll. Under
     * {@link Policy#AUTO} (the default, and what a {@link Dialog} body uses) the bar is
     * otherwise invisible until the pointer moves over the host or the wheel turns, so a card that
     * was capped opens with nothing at all saying that anything was cut off. The gutter inset says
     * the card is capped; only the bar says it can be scrolled.
     */
    public void refresh() {
        boolean overflow = hasOverflow();
        if (overflow && !overflowed) {
            sinceScroll = 0;
        }
        overflowed = overflow;
        ensureTicking();
        invalidate();
    }

    /**
     * @return whether the bar is inside a reveal window right now (tests). The opacity itself
     *         cannot answer this: it ramps on a ticker, so at the instant a reveal begins it is
     *         still the zero it was.
     */
    boolean revealing() {
        return shouldShow();
    }

    private float effectiveOpacity() {
        if (policy == Policy.HIDDEN || !hasOverflow()) {
            return 0;
        }
        return policy == Policy.ALWAYS ? 1 : opacity.value();
    }

    private boolean hasOverflow() {
        return model.contentLength() - model.viewportLength() > 0.5f;
    }

    private boolean shouldShow() {
        if (!hasOverflow() || policy == Policy.HIDDEN) {
            return false;
        }
        if (policy == Policy.ALWAYS || hoverBar || dragging) {
            return true;
        }
        if (sinceScroll < HOLD_SECONDS) {
            return true;
        }
        return policy == Policy.AUTO && sinceHostActivity < HOLD_SECONDS;
    }

    private void ensureTicking() {
        if (ticking || scene() == null) {
            return;
        }
        ticking = true;
        scene().addRealTimeTicker(this::tick); // chrome: the bar fades on wall time, like Transition
    }

    private boolean tick(double dt) {
        sinceScroll += dt;
        sinceHostActivity += dt;
        boolean reveal = shouldShow();
        opacity.duration(reveal ? FADE_IN_SECONDS : FADE_OUT_SECONDS).to(reveal ? 1 : 0);
        widthLevel.to(hoverBar || dragging ? 1 : 0);
        // This ticker only tracks the hold timers; once the hold window elapses the
        // target is stable and the two transitions finish on their own tickers.
        boolean holding = hoverBar || dragging || sinceScroll < HOLD_SECONDS
                || (policy == Policy.AUTO && sinceHostActivity < HOLD_SECONDS);
        ticking = holding;
        return holding;
    }

    private float maxOffset() {
        return Math.max(0, model.contentLength() - model.viewportLength());
    }

    private float trackLength() {
        return (vertical() ? height() : width()) - 2 * MARGIN;
    }

    private float thumbThickness() {
        return THIN + (WIDE - THIN) * widthLevel.value();
    }

    private float thumbLength() {
        float track = trackLength();
        float content = model.contentLength();
        float ratio = content > 0 ? model.viewportLength() / content : 1;
        return Math.max(MIN_THUMB, Math.min(track, track * ratio));
    }

    /**
     * Where the thumb starts along the bar's own axis.
     *
     * <p>An offset of zero is the <b>leading</b> edge, so a mirrored bar rests its thumb at the
     * far end of the track and walks it back toward {@code MARGIN} as the offset grows. The
     * offset itself, {@link #trackLength()} and {@link #maxOffset()} stay positive magnitudes in
     * both directions; this one mapping from offset to coordinate is the only thing here that
     * knows a direction.
     *
     * @param rtl the direction resolved once by the caller's pass
     */
    private float thumbStart(boolean rtl) {
        float travel = trackLength() - thumbLength();
        float max = maxOffset();
        float along = max > 0 ? travel * (model.offset() / max) : 0;
        return MARGIN + (mirrored(rtl) ? travel - along : along);
    }

    /**
     * The thumb as {@code {x, y, width, height}} in this bar's own box.
     *
     * @param rtl the direction resolved once by the caller's pass
     */
    private float[] thumbRect(boolean rtl) {
        float start = thumbStart(rtl);
        float len = thumbLength();
        float thick = thumbThickness();
        if (vertical()) {
            // A vertical thumb hugs the outer edge of its strip, and the host hangs that strip on
            // the trailing side of the content: the right edge reading left to right, the left
            // edge reading right to left. Hugging one fixed side instead would leave the thumb
            // floating a strip's width in from the window edge in a mirrored layout.
            float side = rtl ? MARGIN : width() - MARGIN - thick;
            return new float[] {side, start, thick, len};
        }
        // The horizontal thumb's y is a cross-axis coordinate and does not move; its x is
        // `start`, which is where the mirroring already happened.
        return new float[] {start, height() - MARGIN - thick, len, thick};
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        float op = effectiveOpacity();
        if (op < 0.02f) {
            return;
        }
        boolean rtl = isRtl(); // once for this paint, then handed down
        float[] t = thumbRect(rtl);
        float radius = Math.min(t[2], t[3]) / 2;
        float alpha = (dragging ? 0.9f : hoverBar ? 0.75f : 0.5f) * op;
        canvas.fillRoundRect(t[0], t[1], t[2], t[3], radius, Theme.current().textMuted.withAlpha(alpha));
    }

    /** Transparent to events while effectively invisible, so it never blocks the content. */
    @Override
    public Widget hitTest(float localX, float localY) {
        if (effectiveOpacity() < 0.05f) {
            return null;
        }
        return super.hitTest(localX, localY);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> {
                hoverBar = true;
                ensureTicking();
            }
            case EXIT -> {
                hoverBar = false;
                ensureTicking();
            }
            case PRESS -> {
                if (event.button() != Keys.MOUSE_LEFT) {
                    return;
                }
                boolean rtl = isRtl(); // once for this press, then handed down
                float pos = vertical() ? sceneToLocalY(event.y()) : sceneToLocalX(event.x());
                float[] t = thumbRect(rtl);
                float thumbStart = vertical() ? t[1] : t[0];
                float thumbLen = vertical() ? t[3] : t[2];
                if (pos >= thumbStart && pos < thumbStart + thumbLen) {
                    dragging = true;
                    dragGrab = pos - thumbStart;
                } else {
                    // Paging steps toward the pointer, which is a step in the offset only after
                    // the same mapping the thumb uses: on a mirrored bar the low-coordinate side
                    // of the thumb is the side the offset grows toward.
                    float dir = pos < thumbStart ? -1 : 1;
                    if (mirrored(rtl)) {
                        dir = -dir;
                    }
                    model.setOffset(model.offset() + dir * model.viewportLength());
                    onScrolled();
                }
                ensureTicking();
                event.consume();
            }
            case DRAG -> {
                if (dragging) {
                    boolean rtl = isRtl(); // once for this drag step, then handed down
                    float pos = vertical() ? sceneToLocalY(event.y()) : sceneToLocalX(event.x());
                    float travel = Math.max(1, trackLength() - thumbLength());
                    // The algebraic inverse of thumbStart, so it has to invert with it: mirror
                    // one and not the other and the thumb slides away from the hand holding it.
                    float along = (pos - dragGrab - MARGIN) / travel;
                    float ratio = mirrored(rtl) ? 1 - along : along;
                    model.setOffset(Math.max(0, Math.min(1, ratio)) * maxOffset());
                    onScrolled();
                    event.consume();
                }
            }
            case RELEASE -> {
                if (dragging) {
                    dragging = false;
                    ensureTicking();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }
}
