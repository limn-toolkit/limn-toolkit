package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

/**
 * Generic scroll container: the child is measured unbounded on the scrolling
 * axes, painted offset by the scroll position and clipped to the viewport on
 * those axes; the wheel scrolls whenever there is overflow. The child is
 * physically positioned at {@code (-offsetX, -offsetY)}, so hit-testing and
 * event coordinates work unchanged.
 *
 * <p><b>It clips exactly on an axis it scrolls while the content overflows it there, and
 * nowhere else.</b> Where the content scrolls, what lies past the viewport's edge is out of sight
 * and is cut there. Across an axis that does not scroll, or one the content fits, nothing can
 * move out of sight, so the content may paint past the viewport by a band, the reach of the
 * theme's focus ring unless {@link #paintOverflow} says otherwise: a control flush with the side
 * of a view that scrolls only vertically keeps its whole focus ring, and so does one flush with
 * its bottom until the content grows past the viewport.
 *
 * <p>Scroll indicators are the shared {@link ScrollBar} (draggable, themed,
 * policy-driven), the same component every scroller uses.
 *
 * <p><b>The bars either float over the content or take a gutter of their own</b>
 * ({@link #setBarLayout}). Which one is right is a property of what is being
 * scrolled, not a taste: an image, a video or a page of prose wants every point of
 * width and loses nothing to a bar that fades over it, while a table, a form or a
 * tree wants its last column readable at all times, and a bar sitting on it is a
 * defect however briefly it shows.
 */
public final class ScrollView extends Widget<ScrollView> implements Scrollable {

    /** Logical points scrolled per wheel notch. */
    private static final float WHEEL_STEP = 48;
    /** What {@link #paintOverflow} holds while the band follows the theme: the default. */
    private static final float FROM_THEME = -1;
    /**
     * Overflow under this is not overflow for the clip, the dead band the bar shows by and the
     * gutter reserves by: content a rounding error taller than its viewport has nothing to scroll
     * out of sight, and clipping it would cut a ring at the edge for that error.
     */
    private static final float OVERFLOW_SLOP = 0.5f;

    private final Widget<?> child;
    private final boolean horizontal;
    private final boolean vertical;
    private final ScrollBar vBar;
    private final ScrollBar hBar;
    private float offsetX;
    private float offsetY;
    /** Whether a layout has measured the content, against which an offset can be clamped. */
    private boolean laidOut;
    private final ScrollGutters gutters = new ScrollGutters();
    /** The band {@link #paintOverflow(float)} declared, or {@link #FROM_THEME}. */
    private float paintOverflow = FROM_THEME;
    /**
     * Whether the content is clipped exactly at the viewport across, and down: it scrolls on that
     * axis and the last layout left it overflowing there. Set only by {@link #onLayout()}, which is
     * where the content's size and the viewport's change, so that every clip, damage and culling
     * question between two layouts gets the answer the paint uses.
     */
    private boolean clipsX;
    private boolean clipsY;

    /**
     * Wraps {@code child}, scrolling vertically as its content overflows: clipped at the top and
     * bottom while it does, and let past the sides, and past the top and bottom while it fits, by
     * {@link #paintOverflow}'s band.
     */
    public ScrollView(Widget<?> child) {
        this(child, false, true);
    }

    /** Wraps {@code child}, scrolling only on the axes enabled here. */
    public ScrollView(Widget<?> child, boolean horizontal, boolean vertical) {
        this.child = child;
        this.horizontal = horizontal;
        this.vertical = vertical;
        add(child);
        vBar = vertical ? new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return child.height();
            }

            @Override
            public float viewportLength() {
                return viewportHeight();
            }

            @Override
            public float offset() {
                return offsetY;
            }

            @Override
            public void setOffset(float value) {
                scrollTo(offsetX, value, Change.Origin.USER); // the bar is the user's
            }
        }).setPolicy(ScrollBar.Policy.AUTO) : null;
        if (vBar != null) {
            add(vBar);
        }
        hBar = horizontal ? new ScrollBar(ScrollBar.Orientation.HORIZONTAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return child.width();
            }

            @Override
            public float viewportLength() {
                return viewportWidth();
            }

            @Override
            public float offset() {
                return offsetX;
            }

            @Override
            public void setOffset(float value) {
                scrollTo(value, offsetY, Change.Origin.USER);
            }
        }).setPolicy(ScrollBar.Policy.AUTO) : null;
        if (hBar != null) {
            add(hBar);
        }
    }

    /** Sets the scrollbar visibility policy (default {@link ScrollBar.Policy#AUTO}). */
    public ScrollView setScrollbarPolicy(ScrollBar.Policy policy) {
        Ui.checkUiThread();
        if (vBar != null) {
            vBar.setPolicy(policy);
        }
        if (hBar != null) {
            hBar.setPolicy(policy);
        }
        return this;
    }

    /**
     * Sets whether the bars float over the content or reserve a strip of their own
     * (default {@link ScrollGutters.Layout#OVERLAY}).
     */
    public ScrollView setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        return this;
    }

    /** Whether the scrollbars overlay the content or reserve a gutter. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * Declares how far the content may paint past the viewport across an axis this view does not
     * scroll, or one its content fits, in logical points, in place of the reach the theme gives a
     * focus ring. UI thread only.
     *
     * <p><b>What it is for.</b> On an axis it scrolls and the content overflows, this view clips
     * the content exactly at the viewport, because there the content moves under the edge and
     * what lies past it is out of sight. On an axis it does not scroll, or one the content fits,
     * nothing can move out of sight, and a clip there cut only what a control draws outside its
     * own box at the viewport's edge: the focus ring of a checkbox flush with the side of a view
     * that scrolls vertically, or with its bottom while nothing overflows, a cursor centred on the
     * edge of a colour plane. So across such an axis the content may paint this far past the
     * viewport, on both sides, and is clipped there. A view whose content overflows it both ways
     * lets nothing past; one whose content fits it lets the band past on all four sides, whatever
     * it scrolls. The layout that makes the content overflow an axis or fit it again repaints the
     * wider of the two clips, so that what the content drew in a band that closed is wiped and what
     * it draws in one that opened is drawn at once.
     *
     * <p><b>By default the band is the reach of the focus ring</b> in the theme in effect for this
     * view, {@link Theme#focusRingReach()}, the number every layout declares for its children, so a
     * focused control flush with the view's side keeps its whole ring wherever the view has that
     * much room round it. Declare more when the content draws farther and there is room for it,
     * the padding of a card round the view for instance, and 0 for a view that must clip at its
     * viewport on every side. {@link #paintOverflowFromTheme()} goes back to the theme's number.
     *
     * <p>Three things the band never reaches. A strip reserved for a bar
     * ({@link ScrollGutters.Layout#RESERVED}) is not painted into: on the side the strip holds,
     * the content stops at the viewport whatever this says. The bars stay clipped to this view's
     * box. And the band is this view's allowance, not its ancestors': a clip above the view still
     * cuts whatever reaches past it.
     *
     * <p><b>It is also what this view declares to partial rendering</b> as painting outside its
     * box, through {@link #paintOutset()}, which is one number for both axes. So the view
     * declares it only while an axis is left unclipped, and a view whose content overflows it
     * both ways declares nothing; on an axis the view clips the number over-declares by the band,
     * which costs that many points of repaint above and below the view and never a stale pixel.
     * The clip every descendant's damage is clamped to is the clip the content is painted in,
     * band included, so a ring hanging into the band is repainted there when it changes.
     *
     * <p>A declaration already in force changes nothing and repaints nothing. A new one repaints
     * this view and the wider of the band it leaves and the band it takes, so that what the
     * content drew in a band that narrowed is wiped, and what it draws in one that widened is
     * drawn at once.
     *
     * @param points how far the content may paint past each side of the viewport across an axis
     *               this view does not scroll or its content fits, in logical points
     * @return this view
     * @throws IllegalArgumentException when negative, {@code NaN} or infinite
     */
    public ScrollView paintOverflow(float points) {
        Ui.checkUiThread();
        float band = Checks.notNegative(points, "paint overflow");
        if (paintOverflow != band) {
            redeclare(band);
        }
        return this;
    }

    /**
     * Goes back to letting the content paint past the viewport by the reach of the theme's focus
     * ring across an axis this view does not scroll or its content fits, which is the default; see
     * {@link #paintOverflow(float)}. Repaints nothing when that is already the declaration. UI
     * thread only.
     *
     * @return this view
     */
    public ScrollView paintOverflowFromTheme() {
        Ui.checkUiThread();
        if (paintOverflow != FROM_THEME) {
            redeclare(FROM_THEME);
        }
        return this;
    }

    /** Stores a new declaration and repaints the wider of the band it leaves and the one it takes. */
    private void redeclare(float declared) {
        float was = paintOutset();
        paintOverflow = declared;
        repaintBand(was);
    }

    /**
     * Repaints this view and the wider of {@code was}, what it declared before a change, and what
     * it declares now: the band past the viewport the content could paint in before and the one
     * it can now, on every side.
     */
    private void repaintBand(float was) {
        float reach = Math.max(was, paintOutset());
        invalidate(-reach, -reach, width() + 2 * reach, height() + 2 * reach);
    }

    /** The band in force: what {@link #paintOverflow(float)} declared, else the theme's reach. */
    private float band() {
        return paintOverflow >= 0 ? paintOverflow : Theme.of(this).focusRingReach();
    }

    /**
     * The width content is actually shown in: the box, less any reserved gutter.
     * Every offset, clamp and reveal is measured against this rather than against
     * {@link #width()}, or a reserved bar would hide the last column it exists to
     * keep clear.
     */
    public float viewportWidth() {
        return gutters.viewportWidth(width());
    }

    /** Visible height in logical points, excluding anything the content overflows by. */
    public float viewportHeight() {
        return gutters.viewportHeight(height());
    }

    /**
     * Horizontal scroll position in logical points, {@code 0} at the <b>leading</b> edge: the
     * left edge in a left-to-right subtree and the right edge in a right-to-left one. The range
     * is {@code [0, maxOffsetX()]} in both, so "scrolled to the start" is {@code 0} either way.
     */
    public float offsetX() {
        return offsetX;
    }

    /** Vertical scroll position in logical points, {@code 0} at the top. */
    public float offsetY() {
        return offsetY;
    }

    /** Largest valid {@link #offsetX()}; {@code 0} when the content fits. */
    public float maxOffsetX() {
        return Math.max(0, child.width() - viewportWidth());
    }

    /** Largest valid {@link #offsetY()}; {@code 0} when the content fits. */
    public float maxOffsetY() {
        return Math.max(0, child.height() - viewportHeight());
    }

    /** Scrolls by a delta in logical points (clamped). */
    public void scrollBy(float dx, float dy) {
        scrollTo(offsetX + dx, offsetY + dy);
    }

    /** Scrolls the minimum so the rect (in viewport coordinates) becomes visible. */
    @Override
    public void revealRect(float x, float y, float rectWidth, float rectHeight) {
        Ui.checkUiThread();
        float dx = 0;
        float dy = 0;
        if (horizontal) {
            // Against the VIEWPORT's edges and not the box's: under RESERVED they differ by the
            // gutter, and a rect flush with the box would otherwise count as visible while
            // sitting under the bar.
            float left = viewportLeft();
            float right = left + viewportWidth();
            if (x < left) {
                dx = x - left; // off the leading edge: scroll back
            } else if (x + rectWidth > right) {
                // Oversize rects align their near edge.
                dx = Math.min(x - left, x + rectWidth - right);
            }
        }
        if (vertical) {
            if (y < 0) {
                dy = y;
            } else if (y + rectHeight > viewportHeight()) {
                dy = Math.min(y, y + rectHeight - viewportHeight());
            }
        }
        // The rect arrives in this widget's own PHYSICAL coordinates, so nothing above computed
        // dx knows a direction. What the direction decides is which way offsetX moves the
        // content: left to right the content sits at -offsetX and right to left at
        // viewportWidth() - childWidth + offsetX, so the same physical displacement is the
        // opposite change of offset. One sign flip, and the arithmetic above is untouched.
        if (dx != 0 && isRightToLeft()) {
            dx = -dx;
        }
        if (dx != 0 || dy != 0) {
            scrollBy(dx, dy);
        }
    }

    /**
     * Scrolls to an absolute offset (clamped to the content). A caller's write, announced as
     * {@code VALUE}/{@code CODE}; the wheel, the keys and the bars announce {@code USER} through
     * the same seam, and a layout that clamped the offset an {@code ADJUSTMENT}. UI thread only.
     *
     * <p>Called before the first layout, as a view built already scrolled is, the offset is kept
     * and the first layout clamps it to the content it measures: the first frame shows the view
     * where it was asked to be, never at the top first.
     */
    public void scrollTo(float newOffsetX, float newOffsetY) {
        Ui.checkUiThread();
        scrollTo(newOffsetX, newOffsetY, Change.Origin.CODE);
    }

    private void scrollTo(float newOffsetX, float newOffsetY, Change.Origin origin) {
        if (!laidOut) {
            // Nothing measured to clamp against: kept as asked, and clamped by the first layout.
            float keptX = horizontal ? Math.max(0, newOffsetX) : 0;
            float keptY = vertical ? Math.max(0, newOffsetY) : 0;
            if (keptX != offsetX || keptY != offsetY) {
                offsetX = keptX;
                offsetY = keptY;
                markNeedsLayout();
                notifyChange(Change.of(Change.Aspect.VALUE, origin));
            }
            return;
        }
        float clampedX = horizontal ? Math.min(Math.max(0, newOffsetX), maxOffsetX()) : 0;
        float clampedY = vertical ? Math.min(Math.max(0, newOffsetY), maxOffsetY()) : 0;
        if (clampedX == offsetX && clampedY == offsetY) {
            return;
        }
        boolean movedX = clampedX != offsetX;
        boolean movedY = clampedY != offsetY;
        offsetX = clampedX;
        offsetY = clampedY;
        // Fast path: content size unchanged, only its position moves. The origin is recomputed
        // rather than decremented, because it is not the same function of offsetX in the two
        // directions and a delta that assumed one would run the other backwards.
        moveChild(child, contentOriginX(child.width()), -offsetY);
        if (movedY && vBar != null) {
            vBar.onScrolled();
        }
        if (movedX && hBar != null) {
            hBar.onScrolled();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size content = child.measure(childConstraints(constraints.loosened()));
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : content.width();
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight() : content.height();
        return constraints.constrain(w, h);
    }

    private Constraints childConstraints(Constraints loose) {
        return new Constraints(
                0, horizontal ? Constraints.UNBOUNDED_LIMIT : loose.maxWidth(),
                0, vertical ? Constraints.UNBOUNDED_LIMIT : loose.maxHeight());
    }

    @Override
    protected void onLayout() {
        Size content = gutters.resolve(width(), height(), vBar, hBar,
                (viewW, viewH) -> child.measure(childConstraints(Constraints.loose(viewW, viewH))));
        float viewW = viewportWidth();
        float viewH = viewportHeight();
        float childWidth = horizontal ? Math.max(content.width(), viewW) : viewW;
        float childHeight = vertical ? Math.max(content.height(), viewH) : viewH;
        float wasX = offsetX;
        float wasY = offsetY;
        offsetX = horizontal ? Math.min(offsetX, Math.max(0, childWidth - viewW)) : 0;
        offsetY = vertical ? Math.min(offsetY, Math.max(0, childHeight - viewH)) : 0;
        child.layoutBox(contentOriginX(childWidth), -offsetY, childWidth, childHeight);
        laidOut = true;
        if (offsetX != wasX || offsetY != wasY) {
            // Content that shrank under the offset pulled it back: the view moved by itself.
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.ADJUSTMENT));
        }
        // An axis the content has just grown past closes its band and one it fits again opens it.
        // A full pass repaints everything anyway, but a pass in place repaints only what moved,
        // through the clip as it is now: the part of a ring left in a band that closed would stay
        // on screen, and so repaint the wider of the two clips here.
        boolean overflowsX = horizontal && childWidth - viewW > OVERFLOW_SLOP;
        boolean overflowsY = vertical && childHeight - viewH > OVERFLOW_SLOP;
        if (overflowsX != clipsX || overflowsY != clipsY) {
            float was = paintOutset();
            clipsX = overflowsX;
            clipsY = overflowsY;
            repaintBand(was);
        }

        float t = ScrollBar.thickness();
        // With both bars, leave a clear square in the corner so their thumbs never
        // overlap and each can travel all the way to its own (shortened) end. Under
        // RESERVED the gutter already is that square, and only when the other axis
        // actually overflows; an unused axis costs nothing there.
        boolean both = vBar != null && hBar != null;
        boolean reserved = gutters.layout() == ScrollGutters.Layout.RESERVED;
        // Floored, because a pane can legitimately be laid out at a box thinner than a bar -- a
        // collapsed SplitPane pane, a SizedBox(0, 0) -- and both branches subtract from a side
        // that may already be smaller than what they take. Constraints refuses a negative extent,
        // and this is the layout pass: the throw would abandon the whole frame, so nothing in the
        // window paints and no accessible tree is published, over a pane nobody can see anyway.
        float vLen = Math.max(0, reserved ? viewH : (both ? height() - t : height()));
        float hLen = Math.max(0, reserved ? viewW : (both ? width() - t : width()));
        // The vertical bar sits on the side reading ends on, and the horizontal one starts
        // after whatever strip that leaves, so the clear corner square is on the bar's own side
        // in both directions rather than always on the right.
        boolean rtl = isRightToLeft();
        if (vBar != null) {
            vBar.measure(Constraints.tight(t, vLen));
            vBar.layoutBox(rtl ? 0 : width() - t, 0, t, vLen);
            // Told after the geometry it reads from is settled, not before: this is where a bar
            // learns its content has just become scrollable, which is the only moment an AUTO bar
            // has to say so before the pointer arrives.
            vBar.refresh();
        }
        if (hBar != null) {
            hBar.measure(Constraints.tight(hLen, t));
            hBar.layoutBox(rtl ? width() - hLen : 0, height() - t, hLen, t);
            hBar.refresh();
        }
    }

    /**
     * Physical left edge of the viewport.
     *
     * <p>Zero, except reading right to left under {@link ScrollGutters.Layout#RESERVED}: the
     * vertical bar takes its strip from the side reading ends on, which is then the left, and the
     * content starts after it. {@link ScrollGutters} has no direction of its own &mdash; it
     * answers how much a strip takes, not which side takes it &mdash; so the side is resolved
     * here, where the bar is placed.
     */
    private float viewportLeft() {
        return isRightToLeft() ? gutters.verticalStrip() : 0;
    }

    /**
     * Where the content's left edge goes for the current {@link #offsetX()}.
     *
     * <p>{@code offsetX == 0} is the <b>leading</b> edge in both directions, so left to right it
     * puts the content's left edge on the viewport's left edge and right to left it puts the
     * content's <em>right</em> edge on the viewport's right edge. Advancing the scroll then
     * reveals content on the trailing side either way, which is what lets every clamp keep its
     * form and {@link #maxOffsetX()} stay a positive magnitude.
     *
     * <p>The <b>viewport</b> width and not {@link #width()}: under
     * {@link ScrollGutters.Layout#RESERVED} the two differ by the gutter, and using the box width
     * would slide every right-to-left layout under the scrollbar.
     */
    private float contentOriginX(float childWidth) {
        return isRightToLeft()
                ? viewportLeft() + viewportWidth() - childWidth + offsetX
                : -offsetX;
    }

    /** The vertical bar, or null when this view does not scroll vertically (tests). */
    ScrollBar verticalBar() {
        return vBar;
    }

    /**
     * One scroll pane, whether or not anything overflows today, carrying where the viewport sits
     * in the content and how much of it shows on each axis.
     *
     * <p>The role is unconditional. The pane is the viewport whose clip decides what inside it is
     * on screen at all, and a node that appeared when the content grew and vanished when it
     * shrank would raise a structure change and a destroyed node on every resize; "nothing to
     * scroll" is carried by the facet's two booleans instead. Without the role the pane declares
     * nothing, is never focusable and paints nothing of its own, so the walk deletes it and hoists
     * its content into whatever sits above it, which takes the scroll pattern out of a scene that
     * is mostly scrollable.
     *
     * <p>The facet is six primitives read from this widget's own numbers, so describing it
     * allocates nothing and needs no witness. The percent is {@code offset / maxOffset} with the
     * offset as this widget publishes it: zero at the <b>leading</b> edge in both directions, the
     * range a positive magnitude either way, which is the facet's own "zero at the start" and is
     * published unflipped under a right-to-left layout by decision, not omission; the mirroring
     * lives in where the content is placed and never in the offset. The view size divides the
     * viewport by the <em>child</em> and not by the content's measured size, because the layout
     * gives the child the larger of the two on a scrolling axis and exactly the viewport on a
     * fixed one, so the fraction never exceeds one and is exactly one on an axis that does not
     * scroll, with no case for it. The zero guards are not defensive: the difference between two
     * trees compares these fields with {@code !=}, and a pane laid out at nothing, or described
     * before its first layout, would otherwise publish a not-a-number that differs from itself on
     * every damaged frame and copy the whole tree each time. Scrollable is {@code maxOffset > 0},
     * the predicate the wheel and the Page keys use, and not the bar's half-point slop: what a
     * reader is told can scroll is what a wheel notch would move.
     *
     * <p>No name, no state and no action are declared here. The pane holds no string; the axis
     * bits name the one axis of a single value and the pane has two; and the vocabulary has no
     * scroll verb with an axis in it, so nothing is advertised that the pane cannot perform. A
     * scroll republishes the tree through the same damage the repaint rides, so nothing here
     * marks the node dirty by hand.
     *
     * @param a the builder for this widget's node
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.SCROLL_PANE);
        float maxX = maxOffsetX();
        float maxY = maxOffsetY();
        float childWidth = child.width();
        float childHeight = child.height();
        a.scrollFrom(offsetX, maxX, viewportWidth(), childWidth,
                offsetY, maxY, viewportHeight(), childHeight);
    }

    @Override
    protected boolean clipsChildren() {
        return true; // partial rendering: scrolled-out damage clamps to the viewport
    }

    /**
     * The band across an axis this view does not scroll or its content fits, which its content
     * may paint into past the box; nothing while the content overflows it both ways, when the
     * viewport bounds everything. One number for both axes, so on an axis the view clips it
     * over-declares by the band, which repaints a little more and never leaves a stale pixel.
     * O(1), from two flags and the band in force.
     */
    @Override
    protected float paintOutset() {
        return clipsX && clipsY ? 0 : band();
    }

    // How far the content may paint past each side of the viewport: nothing on an axis the view
    // scrolls while the content overflows it, where the content moves under the edge; nothing on
    // the side a reserved strip holds, which the bar owns; the band everywhere else. The vertical
    // strip is on the side reading ends on, the left right to left, and the horizontal one at the
    // bottom.

    private float bandLeft() {
        return clipsX || (isRightToLeft() && gutters.verticalStrip() > 0) ? 0 : band();
    }

    private float bandRight() {
        return clipsX || (!isRightToLeft() && gutters.verticalStrip() > 0) ? 0 : band();
    }

    private float bandTop() {
        return clipsY ? 0 : band();
    }

    private float bandBottom() {
        return clipsY || gutters.horizontalStrip() > 0 ? 0 : band();
    }

    // The content is clipped to the viewport grown by the band on each side and the bars to the
    // box, exactly as paintChildren does it, so that the showing test and the damage clamp agree
    // with the paint: a ring hanging into the band damages the band. Under RESERVED the viewport
    // and the box differ by the gutter, and a descendant whose whole box lies in that strip is
    // never painted; answering the box for it published it on screen under its own bar.
    @Override
    protected float clipX(Widget<?> forChild) {
        return forChild == child ? viewportLeft() - bandLeft() : 0;
    }

    @Override
    protected float clipY(Widget<?> forChild) {
        return forChild == child ? -bandTop() : 0;
    }

    @Override
    protected float clipWidth(Widget<?> forChild) {
        return forChild == child ? bandLeft() + viewportWidth() + bandRight() : width();
    }

    @Override
    protected float clipHeight(Widget<?> forChild) {
        return forChild == child ? bandTop() + viewportHeight() + bandBottom() : height();
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        // The content is clipped to the viewport, grown by the band across an axis that does not
        // scroll or that it fits, and the bars to the box: under RESERVED those differ by the
        // gutter, and content painting into it would show through around the thumb, the one thing
        // the mode exists to prevent, which is why the band stops at the viewport on the gutter's
        // side.
        // Both in a finally, for the reason Widget.paintChildren gives: the content below is an
        // arbitrary subtree, a throw in it unwinds through here, and a clip left pushed would end
        // the frame unbalanced, reported against nobody, because whatever threw is long out of
        // the stack by then. paintOne already guards its own; these are the two it does not cover.
        canvas.save();
        try {
            canvas.clipRect(clipX(child), clipY(child), clipWidth(child), clipHeight(child));
            paintOne(canvas, child);
        } finally {
            canvas.restore();
        }
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            if (vBar != null) {
                paintOne(canvas, vBar);
            }
            if (hBar != null) {
                paintOne(canvas, hBar);
            }
        } finally {
            canvas.restore();
        }
    }

    private static void paintOne(Canvas canvas, Widget<?> target) {
        canvas.save();
        try {
            canvas.translate(target.x(), target.y());
            target.paintWidget(canvas);
        } finally {
            canvas.restore();
        }
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        if (event.type() == MouseEvent.Type.MOVE) {
            if (vBar != null) {
                vBar.onHostActivity();
            }
            if (hBar != null) {
                hBar.onHostActivity();
            }
            return;
        }
        if (event.type() != MouseEvent.Type.WHEEL) {
            return;
        }
        // Shift turns a vertical wheel into a horizontal one, which is how a plain mouse (no
        // tilt wheel, no trackpad) scrolls a wide table on Windows and GTK. Without it the only
        // horizontal gesture left to that user is dragging the scrollbar thumb. Taken before the
        // normal mapping so a tilt wheel and Shift cannot both drive the same axis in one event.
        boolean shiftToHorizontal = (event.modifiers() & Keys.MOD_SHIFT) != 0
                && horizontal && maxOffsetX() > 0 && event.scrollY() != 0;
        if (shiftToHorizontal) {
            scrollTo(offsetX - event.scrollY() * WHEEL_STEP, offsetY, Change.Origin.USER);
            event.consume();
            return;
        }
        boolean useY = vertical && maxOffsetY() > 0 && event.scrollY() != 0;
        boolean useX = horizontal && maxOffsetX() > 0 && event.scrollX() != 0;
        if (!useX && !useY) {
            return;
        }
        scrollTo(offsetX + (useX ? -event.scrollX() * WHEEL_STEP : 0),
                offsetY + (useY ? -event.scrollY() * WHEEL_STEP : 0), Change.Origin.USER);
        event.consume();
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        scrollByKey(event);
    }

    /**
     * Page/Home/End scrolling, for the keys the focused widget did not want.
     *
     * <p>Package-private as well as an override because a {@link Dialog}'s action row sits
     * <b>outside</b> the body's scroll view by design, so a key pressed with a footer button
     * focused bubbles past the body rather than through it. The dialog hands those keys here
     * itself; without that, content a capped card cut off cannot be reached without a mouse.
     *
     * <p>Arrow keys are deliberately not taken. They are the one set a focused widget is most
     * likely to want for something else (a list moves its selection, a slider its value), and a
     * view that swallowed the ones nobody claimed would be guessing. Page and Home/End are what
     * Windows and GTK give a scrolling pane, and nothing else competes for them here.
     *
     * <p>Consumed only when the view actually moved, so a nested view already at its end lets the
     * one outside it take the key.
     */
    void scrollByKey(KeyEvent event) {
        if (!event.isPressed() || event.isConsumed()) {
            return;
        }
        // One axis answers: the vertical one where there is one, because that is the axis Page
        // means. A horizontal-only view takes them rather than leaving them unanswered.
        boolean useY = vertical && maxOffsetY() > 0;
        boolean useX = !useY && horizontal && maxOffsetX() > 0;
        if (!useX && !useY) {
            return;
        }
        float page = useY ? viewportHeight() : viewportWidth();
        float max = useY ? maxOffsetY() : maxOffsetX();
        float before = useY ? offsetY() : offsetX();
        float target = switch (event.key()) {
            case Keys.PAGE_UP -> before - page;
            case Keys.PAGE_DOWN -> before + page;
            case Keys.HOME -> 0;
            case Keys.END -> max;
            default -> before;
        };
        if (target == before) {
            return;
        }
        scrollTo(useX ? target : offsetX(), useY ? target : offsetY(), Change.Origin.USER);
        if ((useY ? offsetY() : offsetX()) != before) {
            event.consume();
        }
    }
}
