package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Two panes and a divider the user drags to share the space between them.
 *
 * <pre>{@code
 * SplitPane split = SplitPane.horizontal(sidebar, editor).setRatio(0.25f);
 * split.setMinimums(180, 320);          // neither pane below this
 * split.onRatioChange(app::rememberLayout);
 * }</pre>
 *
 * <p><b>The orientation names the axis the panes are arranged along</b>, not the
 * divider: {@link #horizontal} puts them side by side, so the divider is the
 * vertical line between them.
 *
 * <p><b>The split is a ratio, not a width</b>, so growing the window grows both panes.
 * An application that wants a sidebar to keep its width while the other pane absorbs
 * every new pixel re-sets the ratio from its own resize handling; there is no weight
 * knob, because which pane absorbs a change is a per-layout decision rather than a
 * per-splitter one.
 *
 * <p>{@link #setMinimums} keeps a pane from being dragged away entirely; the default
 * floor is a hit target's worth on each side. Pass {@code 0} for a pane that is allowed
 * to collapse to nothing, such as a preview the user is meant to be able to dismiss.
 *
 * <p><b>The divider is thin to look at and thick to hit</b>: it paints a hairline that
 * thickens into the accent on hover, and catches a band a few points wider than the
 * gutter, overlapping both panes. Focus, hover and drag are the same mark in three
 * tints of the accent, each lighter than the one before.
 *
 * <p><b>The divider is not a tab stop unless asked</b>; see
 * {@link #setDividerFocusable}, which is what turns on the keyboard: arrows by a
 * visible step, by one point with Shift, Home and End to the minimum on either side.
 *
 * <p>Three panes are two splitters: nest one in the other.
 */
public final class SplitPane extends Widget {

    /** Which axis the two panes are arranged along. */
    public enum Orientation {
        /** Side by side, divided by a vertical line. */
        HORIZONTAL,
        /** Stacked, divided by a horizontal line. */
        VERTICAL
    }

    /**
     * One arrow press, and one with Shift held. <b>Locked</b>, as a value-domain
     * increment: a splitter in a dense panel must not move the
     * boundary in coarser jumps than a roomy one, for the same reason a compact
     * {@link Slider} keeps its step.
     */
    private static final float KEY_STEP = 10;
    private static final float FINE_STEP = 1;

    /**
     * How far towards white the divider's accent is taken for hover, and again for a
     * drag. The base tone is {@link Theme#focusRing}, so focus, hover and drag are one
     * colour lightened twice rather than three unrelated ones, and the <em>order</em>
     * is what carries the state.
     *
     * <p>Both are wider apart than a palette's own hover step, deliberately: that step
     * separates two states and these have to separate three, on a 2&nbsp;pt line and
     * with nothing else to tell them apart. Moving one without the other, or crossing
     * them, silently makes two states read as one.
     */
    private static final float HOVER_LIGHTEN = 0.22f;
    private static final float DRAG_LIGHTEN = 0.45f;

    private final Orientation orientation;
    private final Pane firstPane;
    private final Pane secondPane;
    /**
     * Built in the constructor body, never as a field initializer: initializers run
     * <em>before</em> it, so a divider made here would ask an orientation that is
     * still null which way it lies and be told "not horizontal"; both kinds of
     * split then take the stacked cursor, and only one of them looks wrong.
     */
    private final Divider divider;

    private float ratio = 0.5f;
    private float firstMin = Strokes.MIN_HIT_TARGET;
    private float secondMin = Strokes.MIN_HIT_TARGET;
    private Consumer<Float> onRatioChange = r -> { };

    private SplitPane(Orientation orientation, Widget first, Widget second) {
        this.orientation = orientation;
        this.firstPane = new Pane(Objects.requireNonNull(first, "first"));
        this.secondPane = new Pane(Objects.requireNonNull(second, "second"));
        this.divider = new Divider(); // after `orientation`: it reads it for its cursor
        add(firstPane);
        add(secondPane);
        // Last, so it wins the hit test where its grab band overlaps the panes:
        // children are tested in reverse order, and the overlap is the whole point.
        add(divider);
    }

    /** Two panes side by side, divided by a vertical line. */
    public static SplitPane horizontal(Widget left, Widget right) {
        return new SplitPane(Orientation.HORIZONTAL, left, right);
    }

    /** Two panes stacked, divided by a horizontal line. */
    public static SplitPane vertical(Widget top, Widget bottom) {
        return new SplitPane(Orientation.VERTICAL, top, bottom);
    }

    // ------------------------------------------------------------------- API

    /**
     * Sets the share of the space given to the first pane, {@code 0..1}. Clamped
     * by {@link #setMinimums} at layout, not here: the minimums are in points and
     * the space is not known until there is a box to divide. Programmatic, so it
     * does not fire {@link #onRatioChange}.
     */
    public SplitPane setRatio(float newRatio) {
        Ui.checkUiThread();
        applyRatio(newRatio, false);
        return this;
    }

    /** @return the requested share of the first pane, before any minimum applies */
    public float ratio() {
        return ratio;
    }

    /**
     * Floors, in points, under which neither pane may be dragged. {@code 0} lets
     * that pane collapse entirely. When the box is too small for both floors the
     * first one wins and the second pane takes what is left: a split that cannot
     * honour both still has to lay out.
     */
    public SplitPane setMinimums(float first, float second) {
        Ui.checkUiThread();
        this.firstMin = Math.max(0, first);
        this.secondMin = Math.max(0, second);
        markNeedsLayout();
        return this;
    }

    /** Fires while the user drags or keys the divider, never on {@link #setRatio}. */
    public SplitPane onRatioChange(Consumer<Float> listener) {
        Ui.checkUiThread();
        this.onRatioChange = listener == null ? r -> { } : listener;
        return this;
    }

    /**
     * Makes the divider a tab stop, off by default. A split is reached with the
     * pointer, and a tab stop that only moves a boundary costs every keyboard user a
     * stop on the way to the panes' own controls, so the keyboard support is here to
     * be turned on by a layout that wants it, not to be paid for by one that does not.
     *
     * <p>On, the divider takes focus by Tab and by a click on its band, shows the
     * accent at its focus tint, and answers the arrows, Shift+arrow, Home and End.
     * Off, nothing else changes: dragging, the cursor, the hover tint and the grab
     * band are the pointer's, and none of them asks about focus.
     *
     * <p>The flag is the divider's rather than the split's, because the divider is
     * what focus would land on; the split itself is a container and never a tab stop.
     * UI thread only.
     */
    public SplitPane setDividerFocusable(boolean focusable) {
        Ui.checkUiThread();
        divider.setFocusable(focusable);
        return this;
    }

    /** @return whether the divider takes keyboard focus ({@code false} by default) */
    public boolean isDividerFocusable() {
        return divider.isFocusable();
    }

    /** Which axis the panes are arranged along. */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * The divider. Package-private for the tests: it is the only part of a split
     * a user aims at, so reaching it by coordinates derived from the layout would
     * turn every assertion about dragging into an assertion about arithmetic the
     * widget itself owns.
     */
    Widget divider() {
        return divider;
    }

    // -------------------------------------------------------------- geometry

    private boolean horizontal() {
        return orientation == Orientation.HORIZONTAL;
    }

    /**
     * Space kept clear between the panes, from the resolved step. It is
     * {@code separatorBox} because that is exactly what it is (the box a centred
     * hairline lives in), and that row is odd at every step so the 1 pt line lands
     * on a whole device pixel (the parity rule {@link Separator} explains).
     */
    private static float gutter(SizeTokens t) {
        return t.separatorBox();
    }

    /**
     * The band that catches the pointer, centred on the gutter and overlapping
     * both panes. <b>Locked</b> at the accessibility floor (a hit
     * target is a human motor constant): a divider is no easier to hit in a dense
     * layout than in a roomy one, and it is the one thing here a user chases.
     */
    private static float grab() {
        return Strokes.MIN_HIT_TARGET;
    }

    /** The axis being divided; the other one both panes simply fill. */
    private float mainSize() {
        return horizontal() ? width() : height();
    }

    /** What is left to share once the gutter has taken its cut. */
    private float shareable(SizeTokens t) {
        return Math.max(0, mainSize() - gutter(t));
    }

    /**
     * The first pane's extent for a given shareable space, with the floors
     * applied. One function for paint, layout and the pointer, so a divider can
     * never be drawn somewhere the drag would not put it.
     */
    private float firstExtent(float total) {
        if (total <= 0) {
            return 0;
        }
        float low = Math.min(firstMin, total);
        float high = Math.max(low, total - secondMin);
        return Math.max(low, Math.min(high, ratio * total));
    }

    /** Applies a raw ratio, reporting it when the change came from the user. */
    private void applyRatio(float raw, boolean fromUser) {
        float clamped = Math.max(0f, Math.min(1f, raw));
        if (clamped == ratio) {
            return;
        }
        ratio = clamped;
        markNeedsLayout();
        if (fromUser) {
            onRatioChange.accept(ratio);
        }
    }

    /** Puts the first pane at {@code extent} points, clamped, and reports it. */
    private void dragTo(SizeTokens t, float extent) {
        float total = shareable(t);
        if (total <= 0) {
            return;
        }
        float low = Math.min(firstMin, total);
        float high = Math.max(low, total - secondMin);
        applyRatio(Math.max(low, Math.min(high, extent)) / total, true);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        // Resolved once for the pass and threaded on, as everywhere in the
        // toolkit: two resolutions that disagreed inside one component would place
        // the divider where the pointer is not.
        SizeTokens t = Theme.current().tokensFor(this);
        Constraints loose = constraints.loosened();
        Size a = firstPane.measure(loose);
        Size b = secondPane.measure(loose);
        float main = gutter(t) + (horizontal()
                ? a.width() + b.width() : a.height() + b.height());
        float cross = horizontal()
                ? Math.max(a.height(), b.height()) : Math.max(a.width(), b.width());
        float naturalWidth = horizontal() ? main : cross;
        float naturalHeight = horizontal() ? cross : main;
        // Bounded, a split fills what it is given: it exists to divide a space,
        // so shrinking to its content would leave the space undivided.
        return constraints.constrain(
                constraints.hasBoundedWidth() ? constraints.maxWidth() : naturalWidth,
                constraints.hasBoundedHeight() ? constraints.maxHeight() : naturalHeight);
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        float gutter = gutter(t);
        float grab = grab();
        float total = shareable(t);
        float first = firstExtent(total);
        float second = Math.max(0, total - first);
        float dividerStart = first + gutter / 2 - grab / 2;
        if (horizontal()) {
            place(firstPane, 0, 0, first, height());
            place(secondPane, first + gutter, 0, second, height());
            place(divider, dividerStart, 0, grab, height());
        } else {
            place(firstPane, 0, 0, width(), first);
            place(secondPane, 0, first + gutter, width(), second);
            place(divider, 0, dividerStart, width(), grab);
        }
    }

    private static void place(Widget widget, float x, float y, float w, float h) {
        widget.measure(Constraints.tight(w, h));
        widget.layoutBox(x, y, w, h);
    }

    // ------------------------------------------------------------- the parts

    /**
     * One side of the split. Exists to clip: a pane is a region, and a child that
     * overflows its region has to stop at the gutter rather than paint into the
     * other pane, which is what "two panes" means to whoever is looking at it.
     */
    private static final class Pane extends Widget {

        Pane(Widget content) {
            add(content);
        }

        private Widget content() {
            return children().get(0);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = content().measure(constraints);
            return constraints.constrain(size.width(), size.height());
        }

        @Override
        protected void onLayout() {
            content().layoutBox(0, 0, width(), height());
        }

        @Override
        protected boolean clipsChildren() {
            return true; // partial rendering clamps a pane's damage to the pane
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            // In a finally: the pane below is an arbitrary subtree and a throw in it unwinds
            // through here, leaving the clip pushed and the frame unbalanced.
            canvas.save();
            try {
                canvas.clipRect(0, 0, width(), height());
                super.paintChildren(canvas);
            } finally {
                canvas.restore();
            }
        }
    }

    /**
     * The band between the panes: a hairline at rest, the theme's accent when it is
     * focused, pointed at or being dragged, and the whole band grabbable.
     */
    private final class Divider extends Widget {

        private final Transition hover =
                new Transition(this).duration(Theme.current().animHover)
                        .easing(Theme.current().animEasing);
        private final Transition dragFade =
                new Transition(this).duration(Theme.current().animHover)
                        .easing(Theme.current().animEasing);
        private final Transition focusFade =
                new Transition(this).duration(Theme.current().animFocus)
                        .easing(Theme.current().animEasing);

        private boolean dragging;
        private boolean pointerInside;
        /** Pointer minus divider centre at the press, so the divider does not jump. */
        private float grabOffset;

        Divider() {
            setCursor(horizontal() ? Cursor.RESIZE_EW : Cursor.RESIZE_NS);
        }

        /** The band the split lays out for it; it never asks for a size of its own. */
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(
                    constraints.hasBoundedWidth() ? constraints.maxWidth() : grab(),
                    constraints.hasBoundedHeight() ? constraints.maxHeight() : grab());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float focus = focusFade.value();
            float hovered = hover.value();
            float dragged = dragFade.value();
            // Three fades applied in state order, so whichever is furthest along is
            // the tint that shows and the ones below it are what it fades back
            // through. A max() over the three would jump between tints instead: a
            // pointer leaving a focused divider has to land back on the focus tone,
            // and that is only continuous if hover was mixed on top of it.
            Color color = theme.outline
                    .lerp(theme.focusRing, focus)
                    .lerp(theme.focusRing.lerp(Color.WHITE, HOVER_LIGHTEN), hovered)
                    .lerp(theme.focusRing.lerp(Color.WHITE, DRAG_LIGHTEN), dragged);
            float lit = Math.max(focus, Math.max(hovered, dragged));
            float w = width();
            float h = height();
            // ONE stroke that thickens continuously, HAIRLINE to FOCUS_RING, the way
            // every bordered control in the toolkit animates its focus: both ends are
            // locked weights, so the line reads identically at every step. Focus rides
            // this same stroke rather than a ring of its own: the divider is a line in
            // a band of empty space, so a ring drawn around the band frames nothing the
            // user can see, and sits beside a line already changing colour under it.
            float weight = Strokes.HAIRLINE + (Strokes.FOCUS_RING - Strokes.HAIRLINE) * lit;
            if (horizontal()) {
                float cx = (int) (w / 2) + Strokes.HALF_PIXEL_INSET;
                canvas.drawLine(cx, 0, cx, h, weight, color);
            } else {
                float cy = (int) (h / 2) + Strokes.HALF_PIXEL_INSET;
                canvas.drawLine(0, cy, w, cy, weight, color);
            }
        }

        /** The pointer's position on the divided axis, in the split's own coordinates. */
        private float pointerMain(MouseEvent event) {
            return horizontal()
                    ? SplitPane.this.sceneToLocalX(event.x())
                    : SplitPane.this.sceneToLocalY(event.y());
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    pointerInside = true;
                    hover.to(1);
                }
                case EXIT -> {
                    pointerInside = false;
                    if (!dragging) {
                        hover.to(0);
                    }
                }
                case PRESS -> {
                    if (event.button() == Keys.MOUSE_LEFT && isEnabled()) {
                        SizeTokens t = Theme.current().tokensFor(this);
                        dragging = true;
                        hover.to(1);
                        dragFade.to(1);
                        // The divider does not jump to the pointer: the grab band
                        // is wider than the line, so a press four points off it is
                        // still a press on the line the user was aiming at.
                        grabOffset = pointerMain(event)
                                - (firstExtent(shareable(t)) + gutter(t) / 2);
                        event.consume();
                    }
                }
                case DRAG -> {
                    if (dragging) {
                        SizeTokens t = Theme.current().tokensFor(this);
                        dragTo(t, pointerMain(event) - grabOffset - gutter(t) / 2);
                        event.consume();
                    }
                }
                case RELEASE -> {
                    if (dragging) {
                        dragging = false;
                        dragFade.to(0);
                        if (!pointerInside) {
                            hover.to(0);
                        }
                        event.consume();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed() || !isEnabled()) {
                return;
            }
            SizeTokens t = Theme.current().tokensFor(this);
            float step = (event.modifiers() & Keys.MOD_SHIFT) != 0 ? FINE_STEP : KEY_STEP;
            float total = shareable(t);
            float here = firstExtent(total);
            boolean handled = true;
            switch (event.key()) {
                case Keys.LEFT, Keys.UP -> {
                    if (matchesAxis(event.key())) {
                        dragTo(t, here - step);
                    } else {
                        handled = false;
                    }
                }
                case Keys.RIGHT, Keys.DOWN -> {
                    if (matchesAxis(event.key())) {
                        dragTo(t, here + step);
                    } else {
                        handled = false;
                    }
                }
                // Against one floor or the other: the two positions a keyboard
                // user would otherwise have to arrow all the way to.
                case Keys.HOME -> dragTo(t, 0);
                case Keys.END -> dragTo(t, total);
                default -> handled = false;
            }
            if (handled) {
                event.consume();
            }
        }

        /**
         * Whether a key moves the axis this split divides. The other axis is left
         * alone rather than treated as the same gesture: a vertical split inside a
         * scrolling panel would otherwise eat Left/Right from whatever wanted them.
         */
        private boolean matchesAxis(int key) {
            boolean horizontalKey = key == Keys.LEFT || key == Keys.RIGHT;
            return horizontalKey == horizontal();
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
        }
    }
}
