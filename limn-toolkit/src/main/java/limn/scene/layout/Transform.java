package limn.scene.layout;

import limn.concurrent.Ui;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.TransformAccess;
import limn.scene.internal.WindowAnimation;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Draws one child moved, scaled or flipped, without changing where layout put anything: a render
 * transform. The child is drawn, hit by the pointer and published to a screen reader where the
 * transform puts it, and its box, the container's box and every sibling's place stay what they are
 * at rest. A child scaled to twice its size overlaps its neighbours; it does not push them.
 *
 * <p>A container, as {@link Padding} is for space round a child and {@link Opacity} for a fade:
 * the cost is only where the container is, and the order of two effects on one child is the order
 * their containers are nested in. To fade a moving child, {@code new Opacity(new Transform(child))}
 * reads as "fade this moving thing" and draws the same picture as the other order.
 *
 * <p><b>The values</b>, each with its default at rest:
 * <ul>
 *   <li>a translation, {@link #setTranslation}, in points: {@code x} toward the end of the layout
 *       direction (right in a left-to-right subtree, left in a right-to-left one), {@code y} down;
 *       {@code (0, 0)};</li>
 *   <li>a scale, {@link #setScale(float, float)}: a negative factor mirrors, and a factor of 0
 *       collapses the child to a line or a point, which then draws nothing and takes no pointer;
 *       {@code (1, 1)};</li>
 *   <li>an origin, {@link #setOrigin}: the point a scale and a flip keep still, as fractions of
 *       the box measured from its start edge and its top, so that a scale that grows from the
 *       start grows toward the end in either direction; {@code (0.5, 0.5)}.</li>
 * </ul>
 * In that order: for a point of the child, the scale about the origin, then the translation of the
 * result, so a translation moves the scaled child as it is. Another order is two containers nested.
 *
 * <p><b>Transparent to everything but where its child is drawn.</b> It measures, lays out and
 * aligns on a baseline as its child alone does, the child given its whole box; it paints nothing of
 * its own, takes no focus, and has no node of its own in the accessible tree, which holds the child
 * in its place. It does not clip: what the child draws past the box is seen there, and a transform
 * that should be cut goes inside a container that clips. It is hit only where its child is drawn,
 * inside its box or past it, so the place a child slid away from takes the pointer from what is
 * beneath.
 *
 * <p><b>What it costs</b>: nothing at rest, where the frame is the frame without it. Moved or
 * scaled, the child is drawn straight into the window, moved and scaled, every clip inside it exact;
 * a change repaints where the child was and where it is, and lays nothing out. A uniform scale
 * keeps text crisp; a scale animation draws the glyphs again at each size it passes through.
 *
 * <p><b>A watcher hears it</b> as {@link Change.Aspect#TRANSFORM} with origin {@code CODE} on this
 * container: every time a set changes a value, once for {@link #reset()}, and twice for a motion,
 * as it starts and as it arrives or is superseded. A screen reader is told where the child is when a
 * value is set and when a motion arrives, and not on the frames between: its highlight jumps to
 * where the child came to rest.
 *
 * <p>Every setter and motion is fluent and UI thread only, refuses {@code NaN} and the infinities
 * with {@link IllegalArgumentException}, and is refused from inside a paint, which is re-run per
 * pass and must not move what it paints.
 */
public final class Transform extends Widget<Transform> {

    /**
     * Which way {@link #flipTo flipTo} turns the child over: two about the vertical axis that
     * follow the reading direction, and two about the horizontal axis that do not.
     */
    public enum Flip {
        /**
         * As a page turns forward: the end edge comes up toward the reader and over to the start,
         * so in a right-to-left subtree the left edge comes over to the right.
         */
        FORWARD,
        /** As a page turns back: the start edge comes up toward the reader and over to the end. */
        BACKWARD,
        /** The bottom edge comes up toward the reader and over the top, whatever the direction. */
        UP,
        /** The top edge comes down toward the reader and under the bottom, whatever the direction. */
        DOWN
    }

    private final Widget<?> child;
    private final TransformAccess.Values values;

    /**
     * Wraps {@code child} at rest: drawn where layout puts it.
     *
     * @param child the widget this container draws moved, scaled or flipped
     */
    public Transform(Widget<?> child) {
        this.child = Objects.requireNonNull(child, "child");
        add(child);
        values = TransformAccess.makeContainer(this);
    }

    // ------------------------------------------------------------------ values

    /**
     * Moves the child by {@code (x, y)} points, at once. {@code x} runs toward the end of the
     * layout direction, so a drawer that slides in from the end edge is the same line in either
     * direction, and {@code y} runs down. A {@link #slideTo slide} in flight is superseded: it stops
     * where it is, announces its end, and its {@code onArrive} never runs.
     *
     * @param x toward the end, in points
     * @param y down, in points
     * @return this
     * @throws IllegalArgumentException if either is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setTranslation(float x, float y) {
        Ui.checkUiThread();
        values.setTranslation(x, y);
        return this;
    }

    /** @return the translation toward the end, in points */
    public float translationX() {
        return values.translationX();
    }

    /** @return the translation down, in points */
    public float translationY() {
        return values.translationY();
    }

    /**
     * Scales the child by {@code s} on both axes about the {@link #setOrigin origin}, at once.
     *
     * @param s the factor; negative mirrors, 0 collapses the child to a point
     * @return this
     * @throws IllegalArgumentException if it is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     * @see #setScale(float, float)
     */
    public Transform setScale(float s) {
        return setScale(s, s);
    }

    /**
     * Scales the child by {@code sx} along x and {@code sy} along y about the
     * {@link #setOrigin origin}, at once. A {@link #scaleTo scale} in flight is superseded. A uniform
     * scale keeps text crisp; two different factors draw it softer while they last.
     *
     * @param sx the factor along x; negative mirrors, 0 collapses the child to a line
     * @param sy the factor along y
     * @return this
     * @throws IllegalArgumentException if either is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setScale(float sx, float sy) {
        Ui.checkUiThread();
        values.setScale(sx, sy);
        return this;
    }

    /** @return the scale along x */
    public float scaleX() {
        return values.scaleX();
    }

    /** @return the scale along y */
    public float scaleY() {
        return values.scaleY();
    }

    /**
     * Sets the point a scale and a flip keep still, as fractions of the box: {@code fractionX}
     * from the start edge (the left in a left-to-right subtree, the right in a right-to-left one),
     * {@code fractionY} from the top. {@code (0.5, 0.5)}, the middle, by default; {@code (0, 0)}
     * grows the child from its start corner. A fraction outside 0 to 1 puts the point outside the
     * box, which is allowed.
     *
     * @param fractionX from the start edge
     * @param fractionY from the top
     * @return this
     * @throws IllegalArgumentException if either is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setOrigin(float fractionX, float fractionY) {
        Ui.checkUiThread();
        values.setOrigin(fractionX, fractionY);
        return this;
    }

    /** @return the origin's fraction of the box from its start edge */
    public float originX() {
        return values.originX();
    }

    /** @return the origin's fraction of the box from its top */
    public float originY() {
        return values.originY();
    }

    /**
     * Puts every value back at rest, the origin included, and stops every motion in flight, as one
     * change: a watcher hears it once, besides the end of each motion it superseded.
     *
     * @return this
     * @throws IllegalStateException off the UI thread, or from inside a paint
     */
    public Transform reset() {
        Ui.checkUiThread();
        values.reset();
        return this;
    }

    // ----------------------------------------------------------------- motions

    /**
     * {@link #slideTo(float, float, double, Runnable)} over the theme's window animation, the time
     * the toolkit's own popups and dialogs move in, with nothing run at the end.
     *
     * @param x toward the end, in points
     * @param y down, in points
     * @return this
     * @throws IllegalArgumentException if either is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform slideTo(float x, float y) {
        return slideTo(x, y, WindowAnimation.of(this), null);
    }

    /**
     * Moves the {@link #setTranslation translation} to {@code (x, y)} over {@code seconds}, eased as
     * the toolkit eases a fade, one step a frame on the scene's wall-time ticker, which ticks only
     * while something moves and which pausing the scene does not freeze. One ticker per container,
     * whatever the number of motions asked for; a slide and a {@link #scaleTo scale} run together.
     *
     * <p><b>Straight to the target</b>, as one {@link #setTranslation} with {@code onArrive} run at
     * once, while the system asks for less motion, for no time, for the translation the container
     * already has, or with no frames to step it: a container in no scene, or in a scene with no
     * window.
     *
     * <p><b>A newer slide or a {@link #setTranslation} supersedes one in flight</b>: it stops where
     * it is, and its {@code onArrive} never runs. A slide on a container that leaves its scene settles
     * at its target as it leaves and runs its {@code onArrive} then.
     *
     * <p><b>A watcher hears it twice</b>, as {@link Change.Aspect#TRANSFORM}: when it starts, and when
     * it arrives or is superseded. The frames between repaint without announcing and without telling
     * a screen reader, which hears where the child is when it arrives.
     *
     * @param x        toward the end, in points
     * @param y        down, in points
     * @param seconds  how long it takes, when the system does not ask for less motion
     * @param onArrive run once when it arrives, or at once when it goes straight there; never when
     *                 it is superseded; may be null
     * @return this
     * @throws IllegalArgumentException if {@code x} or {@code y} is {@code NaN} or infinite, or
     *                                  {@code seconds} is negative, {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform slideTo(float x, float y, double seconds, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        values.slideTo(x, y, seconds, onArrive);
        return this;
    }

    /**
     * {@link #scaleTo(float, float, double, Runnable)} to {@code s} on both axes over the theme's
     * window animation, with nothing run at the end.
     *
     * @param s the factor to arrive at
     * @return this
     * @throws IllegalArgumentException if it is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform scaleTo(float s) {
        return scaleTo(s, s, WindowAnimation.of(this), null);
    }

    /**
     * Moves the {@link #setScale(float, float) scale} to {@code (sx, sy)} over {@code seconds},
     * by the rules {@link #slideTo(float, float, double, Runnable) slideTo} states: eased, on the
     * one ticker, straight there under less motion, superseded by a newer scale or a
     * {@link #setScale}, settled as the container leaves its scene, and heard twice. The glyphs of
     * the text inside are drawn again at each size the scale passes through.
     *
     * @param sx       the factor along x to arrive at
     * @param sy       the factor along y to arrive at
     * @param seconds  how long it takes, when the system does not ask for less motion
     * @param onArrive run once when it arrives, or at once when it goes straight there; never when
     *                 it is superseded; may be null
     * @return this
     * @throws IllegalArgumentException if a factor is {@code NaN} or infinite, or {@code seconds} is
     *                                  negative, {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform scaleTo(float sx, float sy, double seconds, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        values.scaleTo(sx, sy, seconds, onArrive);
        return this;
    }

    /**
     * {@link #flipTo(Flip, double, Runnable, Runnable)} over the theme's window animation, with
     * nothing run at the end.
     *
     * @param direction which way the child turns over
     * @param atEdge    run with the child edge-on, where it swaps what it shows
     * @return this
     * @throws NullPointerException  if either is null
     * @throws IllegalStateException off the UI thread, or from inside a paint
     */
    public Transform flipTo(Flip direction, Runnable atEdge) {
        return flipTo(direction, WindowAnimation.of(this), atEdge, null);
    }

    /**
     * Turns the child over and back over {@code seconds}: from flat to edge-on about the axis
     * {@code direction} names, where it runs {@code atEdge}, and from edge-on on the other side back
     * to flat, so that what {@code atEdge} put in the child arrives the right way round. The place
     * to hide one face and show the other: a card's front and back are two children of a
     * {@link Stack} inside this container, and {@code atEdge} swaps which is visible.
     *
     * <p>Drawn flat: the child narrows to a line about the {@link #setOrigin origin} and widens
     * back, with no perspective, which is cheap and needs nothing but a scale; text is softer while
     * it turns and crisp again at the end. It leaves every value as it found it.
     *
     * <p>By the rules of {@link #slideTo(float, float, double, Runnable) slideTo}: eased over the
     * whole turn, fastest edge-on; on the one ticker beside a slide and a scale; superseded by a newer
     * flip, which turns on from wherever this one had got to and runs its own {@code atEdge}, or by
     * {@link #reset()}; settled as the container leaves its scene, {@code atEdge} first if the edge
     * was not reached; heard twice. <b>While the system asks for less motion</b>, for no time, or
     * with no frames to step it, there is no turn at all: {@code atEdge} and then {@code onArrive}
     * run at once, and the content changes with no transition.
     *
     * @param direction which way the child turns over
     * @param seconds   how long the whole turn takes, when the system does not ask for less motion
     * @param atEdge    run once, with the child edge-on
     * @param onArrive  run once when the child is flat again, or at once; never when it is
     *                  superseded; may be null
     * @return this
     * @throws NullPointerException     if {@code direction} or {@code atEdge} is null
     * @throws IllegalArgumentException if {@code seconds} is negative, {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform flipTo(Flip direction, double seconds, Runnable atEdge, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        Objects.requireNonNull(direction, "direction");
        boolean aboutVertical = direction == Flip.FORWARD || direction == Flip.BACKWARD;
        boolean reverse = direction == Flip.BACKWARD || direction == Flip.DOWN;
        values.flipTo(aboutVertical, reverse, seconds, atEdge, onArrive);
        return this;
    }

    // ------------------------------------------------------------------ layout

    /**
     * How far the child, with its own reach, is drawn past this box: its own declared reach at rest,
     * as an {@link Opacity} container declares, and where the transform puts it otherwise, a drawer
     * slid past the box or a card scaled up, so that a repaint of that band reaches it. Computed from
     * the child's corners on asking, which is constant-time.
     */
    @Override
    protected float paintOutset() {
        return TransformAccess.paintOutsetOf(this);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = child.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        child.layoutBox(0, 0, width(), height());
        values.laidOut();
    }

    /** The child's: the container aligns on a baseline as the child alone does. */
    @Override
    protected float baselineOffset() {
        return baselineOffsetOf(child);
    }

    /** The child where it is drawn, or nothing: never this container, which is only a place. */
    @Override
    protected @Nullable Widget<?> onHitTest(float localX, float localY) {
        return hitTestChild(child, localX, localY);
    }
}
