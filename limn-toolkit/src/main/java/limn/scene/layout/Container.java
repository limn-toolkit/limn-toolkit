package limn.scene.layout;

import limn.concurrent.Ui;

import limn.internal.lang.Checks;
import limn.scene.Widget;
import limn.scene.internal.LayoutOverflow;

/**
 * A widget whose children are whatever the application puts in it: the one kind of widget that
 * adds and removes children in public.
 *
 * <p>Every other widget arranges children of its own — a table its rows, a tab pane its headers, a
 * button its label — and adding to one of those from outside either did nothing it would show or
 * took a row out from under the widget's own bookkeeping. So {@link Widget#add} and
 * {@link Widget#remove} are protected, and this class is where they become public. {@link Column},
 * {@link Row}, {@link Wrap}, {@link Grid} and {@link Stack} are containers; a layout an
 * application writes extends this.
 *
 * <p><b>A container declares how far its children may paint past it</b>, which is what partial
 * rendering needs to know to repaint a focus ring at its edge; see {@link #paintOverflow(float)}.
 * A layout that extends this inherits the declaration and the setter with it.
 */
public abstract class Container<W extends Container<W>> extends Widget<W> {

    /** What {@link #paintOverflow} holds while the reach follows the theme: the default. */
    private static final float FROM_THEME = -1;

    /** The reach {@link #paintOverflow(float)} declared, or {@link #FROM_THEME}. */
    private float paintOverflow = FROM_THEME;

    /**
     * Appends a child (UI thread only).
     *
     * @param child the child to append; never {@code null}
     * @throws IllegalStateException    if {@code child} already has a parent
     * @throws IllegalArgumentException if {@code child} is an ancestor of this container, a
     *                                  scene's root, or an overlay on a scene's stack, which belongs
     *                                  to that scene until it is taken off the stack or its window
     *                                  closes
     */
    @Override
    public void add(Widget<?> child) {
        Ui.checkUiThread();
        super.add(child);
    }

    /**
     * Inserts a child at a position in {@link #children()} (UI thread only).
     *
     * @param index where the child goes, in {@code [0, children().size()]}
     * @param child the child to insert; never {@code null}
     * @throws IndexOutOfBoundsException if {@code index} is outside that range
     * @throws IllegalStateException     if {@code child} already has a parent
     * @throws IllegalArgumentException  if {@code child} is an ancestor of this container, a
     *                                   scene's root, or an overlay on a scene's stack, as
     *                                   {@link #add(Widget)} says
     */
    @Override
    public void add(int index, Widget<?> child) {
        Ui.checkUiThread();
        super.add(index, child);
    }

    /** Removes a child (UI thread only); a widget that is not a child is ignored. */
    @Override
    public void remove(Widget<?> child) {
        Ui.checkUiThread();
        super.remove(child);
    }

    /**
     * Declares how far this container's children may paint past its box, in logical points, in
     * place of the reach the theme gives every layout. UI thread only.
     *
     * <p><b>What it is for.</b> A layout that does not clip lets what its children draw outside
     * themselves show outside it too: a focus ring on a control at the edge of a row hangs past
     * the row. Partial rendering repaints only what changed, and a repaint pass skips any subtree
     * whose box, grown by what the subtree's root declares it paints outside itself, misses the
     * pass. So a pass landing only in the band just outside a layout that declared too little (a
     * neighbour's hover, a caret beside it) skipped the layout, never painted the control, and
     * wiped the ring's ink in that band.
     *
     * <p><b>By default a container declares the reach of the focus ring</b> in the theme in effect
     * for it, {@link limn.components.Theme#focusRingReach()}, which covers the ring of every
     * control the toolkit ships. Declare more when a child paints farther (a colour plane's cursor
     * centred on its edge, a glow, a badge placed across the corner), and less, down to 0, when
     * the children are known to stay inside, which spares the band on every repaint of this
     * container. {@link #paintOverflowFromTheme()} goes back to the theme's number.
     *
     * <p><b>It is what {@link #paintOutset()} answers</b>, the protected hook partial rendering
     * reads, and this is its public setter. So it grows this container's damage too: an
     * {@code invalidate()} repaints the box and the band. A container that
     * {@linkplain #clipsChildren() clips its children} declares nothing by default, because its
     * clip already bounds them, and a number set here still counts. A subclass that overrides
     * {@code paintOutset()} for painting of its own answers the larger of that and
     * {@code super.paintOutset()}, or this setter no longer reaches it.
     *
     * <p>The number is one per container and is not added up from the children: a child that
     * paints farther than its layouts declare still loses the part beyond. That is the price of
     * asking it in O(1), since a repaint pass asks it of every layout it considers.
     *
     * <p>A declaration already in force changes nothing and repaints nothing. A new one repaints
     * this container and the band it now declares, so that whatever a child draws there is drawn
     * at once rather than at the next pass that reaches it.
     *
     * @param points how far the children may paint past each side of this box, in logical points
     * @return this container
     * @throws IllegalArgumentException when negative, {@code NaN} or infinite
     */
    public W paintOverflow(float points) {
        Ui.checkUiThread();
        float reach = Checks.notNegative(points, "paint overflow");
        if (paintOverflow != reach) {
            paintOverflow = reach;
            invalidate();
        }
        return self();
    }

    /**
     * Goes back to declaring the reach of the theme's focus ring for the children, which is the
     * default; see {@link #paintOverflow(float)}. Repaints nothing when that is already the
     * declaration. UI thread only.
     *
     * @return this container
     */
    public W paintOverflowFromTheme() {
        Ui.checkUiThread();
        if (paintOverflow != FROM_THEME) {
            paintOverflow = FROM_THEME;
            invalidate();
        }
        return self();
    }

    /**
     * What {@link #paintOverflow(float)} declared; else nothing when this container clips its
     * children, whose clip bounds them; else the reach of the focus ring in the theme in effect
     * for it. O(1) and allocation-free: no child is visited.
     */
    @Override
    protected float paintOutset() {
        if (paintOverflow >= 0) {
            return paintOverflow;
        }
        return clipsChildren() ? 0 : LayoutOverflow.of(this);
    }
}
