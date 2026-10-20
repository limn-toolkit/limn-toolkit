package limn.scene.layout;

import limn.concurrent.Ui;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.TransformAccess;
import limn.scene.internal.WindowAnimation;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Draws one child moved, scaled, rotated, skewed, flipped or turned in depth, without changing
 * where layout put anything: a render transform. The child is drawn, hit by the pointer and published to a screen reader where the
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
 *   <li>a rotation, {@link #setRotation}, in degrees clockwise on the screen, the same way in
 *       either direction, as a clock turns; {@code 0};</li>
 *   <li>a scale, {@link #setScale(float, float)}: a negative factor mirrors, and a factor of 0
 *       collapses the child to a line or a point, which then draws nothing and is out of reach (below);
 *       {@code (1, 1)};</li>
 *   <li>a general matrix, {@link #setMatrix}, for a skew or any other map the other values do not
 *       name; the identity;</li>
 *   <li>an origin, {@link #setOrigin}: the point a rotation, a scale, the matrix, a turn in depth
 *       and a flip keep still, as fractions of the box measured from its start edge and its top, so
 *       that a scale that grows from the start grows toward the end in either direction;
 *       {@code (0.5, 0.5)}.</li>
 * </ul>
 * And, for turning the child's plane out of the screen:
 * <ul>
 *   <li>a turn in depth, {@link #setRotation3D(Vec3, float)}, about an axis through the origin;
 *       none;</li>
 *   <li>a perspective, {@link #setPerspective}: how far in front of the origin the eye is, so that
 *       the near edge of a turned child is drawn larger than the far one; none, infinitely far, by
 *       default, which draws a turn about the vertical axis as a narrowing;</li>
 *   <li>whether the child is drawn while its back is turned to the viewer,
 *       {@link #setBackfaceVisible}; it is.</li>
 * </ul>
 * In this order, for a point of the child: the matrix, the scale, the rotation and the turn in
 * depth, each about the origin, then the perspective, seen from in front of the origin, then the
 * translation of what is seen, so that a translation moves the turned child as it is and a
 * rotation turns the scaled one. Another order is two containers nested.
 *
 * <p><b>Transparent to everything but where its child is drawn.</b> It measures, lays out and
 * aligns on a baseline as its child alone does, the child given its whole box; it paints nothing of
 * its own, takes no focus, and has no node of its own in the accessible tree, which holds the child
 * in its place. It does not clip: what the child draws past the box is seen there, and a transform
 * that should be cut goes inside a container that clips. It is hit only where its child is drawn,
 * inside its box or past it, so the place a child slid away from takes the pointer from what is
 * beneath.
 *
 * <p><b>A child drawn as nothing is out of reach, as a hidden one is.</b> Collapsed by a scale of 0
 * on either axis, turned exactly edge-on, or showing its back while {@link #setBackfaceVisible back
 * faces are hidden}, the child and everything inside it are not {@link #isShowing() showing}: the
 * Tab key, an access letter and the pointer pass them by, the focus, the hover and a press held
 * there are taken out of them, with the events a widget hidden by {@link #setVisible} is sent,
 * inside the set that does it, or once the frame of a motion that does it has stepped every
 * motion, and a screen reader is told they are hidden. They come
 * back by themselves, in reach and published again, when the scale returns or the face turns
 * back; the focus they lost is not given back. Nothing of the child's own state changes, and
 * {@link #isVisible()} still answers what it was told.
 *
 * <p><b>What it costs</b>: nothing at rest, where the frame is the frame without it. Moved or
 * scaled, the child is drawn straight into the window, moved and scaled, every clip inside it exact;
 * a change repaints where the child was and where it is, and lays nothing out. A uniform scale
 * keeps text crisp; a scale animation draws the glyphs again at each size it passes through.
 * Rotated or skewed, the child is drawn upright into a picture of its own, at the size it is seen
 * at, and the picture is laid at the angle: every clip inside stays an exact rectangle, a view
 * scrolled inside still skips what scrolled away, the edges are smoothed over about a pixel, and
 * text reads slightly softer, as text at an angle does. A frosted glass inside reads the window
 * behind as it would upright. The picture costs what an {@link Opacity} container's fade costs a
 * frame of the same size; it is made again whenever something inside repaints while the child is
 * turned, and given back the frame after the child comes to rest upright. Turned in depth, the
 * picture is laid in perspective and drawn at the size of its nearest edge, so it stays sharp
 * there, and text far from the eye reads softer; on a computer with no graphics processor, where
 * every pixel of a picture costs processor time, it is drawn at the child's own size instead, and
 * the near edge reads a little softer too. Where the picture is seen at less than half its size,
 * a page turned far back, its detail is averaged rather than left to flicker while it turns. What
 * part of a child passes behind the eye is not drawn, and the rest is. A frosted glass inside a
 * child seen in perspective reads only what the child draws beneath it, not the window. Something
 * inside that draws in a {@link limn.graphics.BlendMode blend mode}, a tint in
 * {@link limn.graphics.BlendMode#MULTIPLY}, blends with the picture while the child is turned, and
 * where the child drew nothing beneath it is drawn as itself; at rest, or only moved and scaled,
 * there is no picture and it blends with what is beneath the container.
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
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
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
     * where it is, announces its end, and its {@code onArrive} never runs; nor does that of one gone
     * straight there that still waits.
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
     * scale keeps text crisp; two different factors draw it softer while they last. A factor of 0
     * on either axis draws the child as nothing and takes the keyboard focus out of it, as hiding
     * it would (see the class's note).
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
     * Turns the child by {@code degrees} clockwise on the screen about the
     * {@link #setOrigin origin}, at once. Physical: a right-to-left subtree turns the same way, as a
     * clock does. A {@link #rotateTo rotation} in flight is superseded. The child is drawn through a
     * picture while it is turned (see the class's note on what it costs); a whole turn of 360
     * degrees draws it upright, through the picture still.
     *
     * @param degrees clockwise on the screen
     * @return this
     * @throws IllegalArgumentException if it is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setRotation(float degrees) {
        Ui.checkUiThread();
        values.setRotation(degrees);
        return this;
    }

    /** @return the rotation, in degrees clockwise on the screen */
    public float rotation() {
        return values.rotation();
    }

    /**
     * Sets a general matrix the child is drawn through, about the {@link #setOrigin origin}, before
     * the scale and the turns: a skew, or any map the other values do not name. The child lies in
     * the plane {@code z = 0}, so a point {@code (x, y)} of it, taken from the origin, goes through
     * the matrix as {@code (x, y, 0, 1)} and is drawn at its x and y divided by its w; the z row
     * matters through a turn in depth and the {@link #setPerspective perspective}, which see the
     * depth it gives, and a bottom row that changes w draws the child in a perspective of the
     * matrix's own. Physical, as the rotation is. The identity is no matrix.
     * {@link limn.math.Mat4#affine2D} makes one from the six numbers of a 2D affine map, and
     * {@link limn.math.Mat4#perspectiveAt} a perspective.
     *
     * @param matrix the matrix
     * @return this
     * @throws NullPointerException     if it is null
     * @throws IllegalArgumentException if an entry is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setMatrix(Mat4 matrix) {
        Ui.checkUiThread();
        values.setMatrix(matrix);
        return this;
    }

    /** @return the general matrix, {@link Mat4#IDENTITY} when none is set */
    public Mat4 matrix() {
        return values.matrix();
    }

    // ------------------------------------------------------------- in depth

    /**
     * Turns the child's plane by {@code degrees} about {@code axis}, through the
     * {@link #setOrigin origin}, at once: out of the screen, so that one edge comes toward the
     * viewer and the opposite one goes away. The axes are the canvas's: x to the right, y down, z
     * out of the screen toward the viewer. A positive angle about z turns as
     * {@link #setRotation} does, clockwise; about y the right edge goes away from the viewer and the
     * left comes toward it; about x the bottom edge comes toward the viewer and the top goes away.
     * Physical, as the rotation is: a right-to-left subtree turns the same way.
     *
     * <p>With no {@link #setPerspective perspective} a turn about y narrows the child, and one past
     * ninety degrees shows its back, mirrored. A turn of exactly ninety degrees out of the screen
     * leaves the child edge-on: it draws nothing and is out of reach, as the class's note says of a
     * child drawn as nothing. A whole turn is no turn.
     *
     * @param axis    the axis, of any length but 0
     * @param degrees the angle, in degrees, the way the paragraph above says for each axis; about
     *                any other axis, as a quaternion of that axis and angle turns
     * @return this
     * @throws NullPointerException     if {@code axis} is null
     * @throws IllegalArgumentException if a component or the angle is {@code NaN} or infinite, or
     *                                  the axis has no length
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setRotation3D(Vec3 axis, float degrees) {
        Ui.checkUiThread();
        Objects.requireNonNull(axis, "axis");
        if (!Float.isFinite(axis.x()) || !Float.isFinite(axis.y()) || !Float.isFinite(axis.z())
                || !Float.isFinite(degrees)) {
            throw new IllegalArgumentException("a turn's axis and angle are finite numbers: " + axis
                    + ", " + degrees);
        }
        if (!(axis.lengthSquared() > 0)) {
            throw new IllegalArgumentException("a turn's axis has a length: " + axis);
        }
        return setRotation3D(Quat.fromAxisAngle(axis, (float) Math.toRadians(degrees)));
    }

    /**
     * Turns the child's plane by {@code turn}, through the {@link #setOrigin origin}, at once, in
     * the canvas's axes as {@link #setRotation3D(Vec3, float)} names them: for a turn composed of
     * several, or one kept from another rotation. Normalized, so a quaternion of any length but 0
     * names its turn; of the two quaternions that name one turn, {@link #rotation3D()} answers the
     * one whose {@code w} is not below 0. {@link Quat#IDENTITY} is no turn.
     *
     * @param turn the turn
     * @return this
     * @throws NullPointerException     if it is null
     * @throws IllegalArgumentException if a component is {@code NaN} or infinite, or all are 0
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setRotation3D(Quat turn) {
        Ui.checkUiThread();
        values.setRotation3D(turn);
        return this;
    }

    /** @return the turn in depth, a unit quaternion, {@link Quat#IDENTITY} when there is none */
    public Quat rotation3D() {
        return values.rotation3D();
    }

    /**
     * Looks at the child from {@code distance} points in front of the {@link #setOrigin origin}, at
     * once: a turned child's near edge is drawn larger than its far one, as a card held up and
     * turned is seen. Two to four times the larger side of the box is the usual look; nearer is
     * more dramatic, farther flatter. The vanishing point is the origin, and the
     * {@link #setTranslation translation} moves it with what it sees. The perspective changes
     * nothing of a child whose plane faces the viewer squarely: only a turn in depth, a flip or a
     * matrix that gives the plane depth is seen through it.
     *
     * <p>{@link Float#POSITIVE_INFINITY}, the default, is no perspective. A part of the child that a
     * turn brings past the eye is not drawn, and the rest is.
     *
     * @param distance how far the eye is, in points, above 0; infinite for none
     * @return this
     * @throws IllegalArgumentException if it is not above 0, or is {@code NaN}
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform setPerspective(float distance) {
        Ui.checkUiThread();
        values.setPerspective(distance);
        return this;
    }

    /** @return how far the eye is in front of the origin, in points; infinite for no perspective */
    public float perspective() {
        return values.perspective();
    }

    /**
     * Sets whether the child is drawn, and hit, while its plane shows the viewer its back: turned
     * past ninety degrees out of the screen. Drawn by default, mirrored as the back of a pane of
     * glass is; hidden, a turned-over child draws nothing and is out of reach as a hidden child is
     * (see the class's note): not showing, passed by the Tab key and the pointer, the focus taken
     * out of it, published hidden, until it turns back. A child mirrored by a negative
     * {@link #setScale scale} still faces the viewer and is drawn either way.
     *
     * <p>This is how two faces show one at a time without a swap: a {@link Stack} of two
     * containers with the same perspective and back faces hidden, the back turned half a turn
     * further than the front, both turned together. At every angle exactly one face is drawn, is
     * showing and takes the keyboard and the pointer, the one turned toward the viewer, but at
     * exactly ninety degrees from flat, where both planes lie along the line of sight and neither is
     * drawn: for that one angle neither face is showing or in reach. A focus held in the face that
     * turns away is lost as it turns.
     *
     * @param visible whether a back face is drawn and hit
     * @return this
     * @throws IllegalStateException off the UI thread, or from inside a paint
     */
    public Transform setBackfaceVisible(boolean visible) {
        Ui.checkUiThread();
        values.setBackfaceVisible(visible);
        return this;
    }

    /** @return whether the child is drawn and hit while its back is turned to the viewer */
    public boolean isBackfaceVisible() {
        return values.isBackfaceVisible();
    }

    /**
     * Puts every value back at rest, the origin included, and stops every motion in flight, as one
     * change: a watcher hears it once, besides the end of each motion it superseded. The callbacks
     * of a motion gone straight there that still wait never run either.
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
     * <p><b>Straight to the target</b>, as one {@link #setTranslation}, while the system asks for
     * less motion, for no time, for the translation the container already has, or with no frames
     * to step it: a container in no scene, or in a scene with no window. Its {@code onArrive} runs
     * then after the {@code seconds} asked for, as a task {@link Ui#postDelayed} runs, so that what
     * follows it keeps the pace the call asked for; for no time, on the next turn of the UI thread's
     * queue.
     *
     * <p><b>{@code onArrive} never runs inside this call</b>, whether the slide moves or goes
     * straight there, so the next step of a sequence, or of motion that repeats, can be asked for in
     * it. Such a chain keeps the pace its calls ask for, where the motions go straight there too,
     * and the UI thread sleeps between its steps. It goes on, one step a period whether the
     * container is shown or not, for as long as the code keeps asking, so repeating motion stops
     * itself: when what it stands for is over, or when its container is no longer
     * {@link #isShowing() showing}.
     *
     * <p><b>A newer slide or a {@link #setTranslation} supersedes one in flight</b>, or one gone
     * straight there whose {@code onArrive} still waits: it stops where it is, and its
     * {@code onArrive} never runs. A slide on a container that leaves its scene settles
     * at its target as it leaves and runs its {@code onArrive} then, as does one that went straight
     * there and still waits for its seconds (a flip's swap first, if it has not run). Both run once
     * the container is out of the scene it left, so a motion they ask for follows the container:
     * straight there when it is in no scene, or on the frames of the scene it went to, never on
     * those of the window it left, and after the change that took it out has done all its own
     * work. A callback that throws, on a frame or as its container leaves, is logged and reported
     * as a throwing ticker is, and changes nothing for the motions beside it, the container's
     * later ones, one it asked for before it threw, or the change.
     *
     * <p><b>A watcher hears it twice</b>, as {@link Change.Aspect#TRANSFORM}: when it starts, and when
     * it arrives or is superseded. The frames between repaint without announcing and without telling
     * a screen reader, which hears where the child is when it arrives.
     *
     * @param x        toward the end, in points
     * @param y        down, in points
     * @param seconds  how long it takes, when the system does not ask for less motion
     * @param onArrive run once when it arrives, or after {@code seconds} when it goes straight there
     *                 (on the next turn of the UI thread's queue for none); never inside this call,
     *                 never when it is superseded; may be null
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
     * one ticker, straight there under less motion with {@code onArrive} after its seconds and never
     * inside this call, superseded by a newer scale or a {@link #setScale}, settled as the container
     * leaves its scene, and heard twice. The glyphs of
     * the text inside are drawn again at each size the scale passes through.
     *
     * <p>A scale that arrives at 0 on either axis draws the child as nothing and puts it out of
     * reach as a hidden child is (see the class's note): it loses the keyboard focus, which is not
     * given back when the scale returns. A motion arrives exactly at its target, so a card turned
     * over by hand, scaled to 0 across, swapped and scaled back, always loses the focus it held;
     * {@link #flipTo flipTo} turns a card over and keeps it. A motion that only passes 0 on its way,
     * from 1 to −1, keeps it: its frames between do not land on 0.
     *
     * @param sx       the factor along x to arrive at
     * @param sy       the factor along y to arrive at
     * @param seconds  how long it takes, when the system does not ask for less motion
     * @param onArrive run once when it arrives, or after {@code seconds} when it goes straight there
     *                 (on the next turn of the UI thread's queue for none); never inside this call,
     *                 never when it is superseded; may be null
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
     * {@link #rotateTo(float, double, Runnable)} over the theme's window animation, with nothing
     * run at the end.
     *
     * @param degrees the rotation to arrive at, clockwise on the screen
     * @return this
     * @throws IllegalArgumentException if it is {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform rotateTo(float degrees) {
        return rotateTo(degrees, WindowAnimation.of(this), null);
    }

    /**
     * Moves the {@link #setRotation rotation} to {@code degrees} over {@code seconds}, by the rules
     * {@link #slideTo(float, float, double, Runnable) slideTo} states: eased, on the one ticker
     * beside a slide and a scale, straight there under less motion with {@code onArrive} after its
     * seconds and never inside this call, superseded by a newer rotation or a
     * {@link #setRotation}, settled as the container leaves its scene, and heard twice. It
     * turns the short way or the long way as the numbers say: from 350 to 10 degrees is 340 degrees
     * back, and to 370 is twenty forward.
     *
     * @param degrees  the rotation to arrive at, clockwise on the screen
     * @param seconds  how long it takes, when the system does not ask for less motion
     * @param onArrive run once when it arrives, or after {@code seconds} when it goes straight there
     *                 (on the next turn of the UI thread's queue for none); never inside this call,
     *                 never when it is superseded; may be null
     * @return this
     * @throws IllegalArgumentException if {@code degrees} is {@code NaN} or infinite, or
     *                                  {@code seconds} is negative, {@code NaN} or infinite
     * @throws IllegalStateException    off the UI thread, or from inside a paint
     */
    public Transform rotateTo(float degrees, double seconds, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        values.rotateTo(degrees, seconds, onArrive);
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
     * <p>Drawn flat while no {@link #setPerspective perspective} is set: the child narrows to a line
     * about the {@link #setOrigin origin} and widens back, which is cheap and needs nothing but a
     * scale; text is softer while it turns and crisp again at the end. With a perspective it turns
     * in depth, the edge coming over growing toward the viewer, through a picture while it turns.
     * Either way it turns the child as it appears, after its own turn in depth and rotation, and
     * leaves every value as it found it. A frame that lands exactly edge-on draws the child as
     * nothing, which puts it out of reach for that frame (see the class's note); the face
     * {@code atEdge} hides loses the focus as {@link #setVisible} takes it from any widget.
     *
     * <p>By the rules of {@link #slideTo(float, float, double, Runnable) slideTo}: eased over the
     * whole turn, fastest edge-on; on the one ticker beside a slide and a scale; superseded by a newer
     * flip, which turns on from wherever this one had got to and runs its own {@code atEdge}, or by
     * {@link #reset()}; settled as the container leaves its scene, {@code atEdge} first if the edge
     * was not reached; heard twice. <b>While the system asks for less motion</b>, for no time, or
     * with no frames to step it, there is no turn at all: {@code atEdge} runs on the next turn of
     * the UI thread's queue, so the content changes at once and with no transition, and
     * {@code onArrive} after {@code seconds} (with the swap, for none). Two flips asked for some way
     * apart therefore swap twice, as they do when the first is past its edge as the second comes. A
     * newer flip or a {@link #reset()} before the swap supersedes both; after it, the swap stays
     * made and only the waiting {@code onArrive} is dropped; and a flip that its own {@code atEdge}
     * supersedes does not arrive. <b>Neither runs inside this call</b>, in any case.
     *
     * @param direction which way the child turns over
     * @param seconds   how long the whole turn takes, when the system does not ask for less motion
     * @param atEdge    run once, with the child edge-on, or on the next turn with no turn at all;
     *                  never inside this call
     * @param onArrive  run once when the child is flat again, or after {@code seconds} with no turn
     *                  at all; never inside this call, never when it is superseded; may be null
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
