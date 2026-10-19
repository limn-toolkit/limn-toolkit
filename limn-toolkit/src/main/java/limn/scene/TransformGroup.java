package limn.scene;

import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.math.Mat4;
import limn.math.MutMat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.scene.internal.TransformAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The values one {@link limn.scene.layout.Transform} container draws its child with, the motions
 * that move them, and the one ticker that steps every motion: the translation, the perspective,
 * the turn in depth, the rotation, the scale, the general matrix, the origin they are taken about,
 * the turn of a flip, and whether a back face is drawn, composed into the matrix the container
 * draws its child through.
 *
 * <p>Here, beside {@link Widget}, and not in the container, for the reasons {@link OpacityGroup}
 * is: the matrix is read by the widget's paint, hit test and conversions, a change damages through
 * the scene's own paths, and a motion settles as the container leaves its scene, which only the
 * widget's own walk of the tree sees. A widget holds one only when it is such a container.
 *
 * <p>The matrix is composed in a fixed order about the origin, {@code O · translation ·
 * perspective · flip · turn in depth · rotation · scale · matrix · O⁻¹}, with the translation's x
 * and the origin's fraction read from the start edge, so that a right-to-left subtree moves and
 * grows toward its own end; the turns and the matrix are physical, as a clock turns the same way in
 * either direction. The perspective looks at the turned child from in front of the origin, and the
 * translation moves what it sees, its vanishing point with it.
 */
final class TransformGroup implements TransformAccess.Values {

    private static final Vec3 ONE = new Vec3(1, 1, 1);

    /** The container these are the values of, and the widget that announces their changes. */
    private final Widget<?> owner;
    /** The matrix the values compose into, loaded into the one the container draws through. */
    private final MutMat4 matrix = new MutMat4();
    /** The move back from the origin, the last factor of the product. */
    private final MutMat4 toOrigin = new MutMat4();
    /** The turns and the scale, composed apart when a perspective comes between them and the move. */
    private final MutMat4 turned = new MutMat4();

    private float translationX;
    private float translationY;
    private float scaleX = 1;
    private float scaleY = 1;
    private float originX = 0.5f;
    private float originY = 0.5f;
    /** Clockwise on the screen, in degrees. */
    private float rotation;
    /** The general matrix, applied first, or null for the identity. */
    private @Nullable Mat4 general;
    /**
     * The turn in depth, a unit quaternion with its {@code w} not below 0 (of the two that name a
     * turn, the one compared), or null for none.
     */
    private @Nullable Quat depthTurn;
    /** How far the eye is in front of the origin, in points; infinite for no perspective. */
    private float perspective = Float.POSITIVE_INFINITY;
    /** The perspective as a matrix, made when it is set; null for none. */
    private @Nullable Mat4 lens;
    /** Whether the child is drawn and hit while its back is turned to the viewer. */
    private boolean backfaceVisible = true;
    /**
     * The turn of a flip in flight, in degrees, physical (the direction already resolved): 0 at
     * rest, toward 90 on one side of the edge and from −90 on the other. Only a flip moves it.
     */
    private float flipDegrees;
    /** Whether the flip turns about the vertical axis; about the horizontal one if not. */
    private boolean flipAboutVertical = true;

    private @Nullable Motion slide;
    private @Nullable Motion scale;
    /** A rotation in flight: its first pair of numbers is the angle, the second unused. */
    private @Nullable Motion rotating;
    private @Nullable Flip flip;
    /**
     * The one ticker stepping every motion of this container while it is registered, or null: one
     * per container and not one per motion, so a slide and a scale run together on it, and a
     * motion asked for on every update of some data while the window renders no frames adds
     * nothing that nothing takes away.
     */
    private @Nullable MotionTicker ticker;

    /** The four motions, each superseded only by its own kind: indices into {@link #arrivals}. */
    private static final int SLIDE = 0;
    private static final int SCALE = 1;
    private static final int ROTATION = 2;
    private static final int FLIP = 3;
    /**
     * Per motion, what a motion that went straight to its target runs later, waiting on the UI
     * queue for the seconds its call asked for or for the next turn ({@link StraightArrival}), or
     * null. Whatever supersedes a motion of that kind drops it.
     */
    private final @Nullable Arrival[] arrivals = new Arrival[4];

    /**
     * A straight-there motion's callbacks, a flip's swap and its arrival or another motion's
     * arrival, and what withdraws them from the queue.
     */
    private static final class Arrival {
        final @Nullable Runnable atEdge;
        final @Nullable Runnable onArrive;
        @Nullable Subscription timer;

        Arrival(@Nullable Runnable atEdge, @Nullable Runnable onArrive) {
            this.atEdge = atEdge;
            this.onArrive = onArrive;
        }
    }

    TransformGroup(Widget<?> owner) {
        this.owner = owner;
    }

    /** A slide, a scale or a rotation in flight: two numbers from where they were to where they go. */
    private static final class Motion {
        final float fromA;
        final float fromB;
        final float toA;
        final float toB;
        final double seconds;
        final @Nullable Runnable onArrive;
        double elapsed;
        /** Whether a frame has stepped it: its first frame is its start and moves nothing. */
        boolean started;

        Motion(float fromA, float fromB, float toA, float toB, double seconds, @Nullable Runnable onArrive) {
            this.fromA = fromA;
            this.fromB = fromB;
            this.toA = toA;
            this.toB = toB;
            this.seconds = seconds;
            this.onArrive = onArrive;
        }
    }

    /**
     * A flip in flight: from the turn it found to the edge on its own side, where it runs
     * {@code atEdge}, then from the edge on the other side back to flat, one eased motion over the
     * whole way, so that it is fastest edge-on, where the swap is not seen.
     */
    private static final class Flip {
        final boolean aboutVertical;
        /** The physical side the first half turns toward: +1 or −1. */
        final float side;
        final float from;
        final double seconds;
        final Runnable atEdge;
        final @Nullable Runnable onArrive;
        double elapsed;
        boolean started;
        boolean swapped;

        Flip(boolean aboutVertical, float side, float from, double seconds, Runnable atEdge,
             @Nullable Runnable onArrive) {
            this.aboutVertical = aboutVertical;
            this.side = side;
            this.from = from;
            this.seconds = seconds;
            this.atEdge = atEdge;
            this.onArrive = onArrive;
        }

        /** How far the first half turns: from where it started to the edge on its side. */
        float firstLeg() {
            return Math.abs(side * 90 - from);
        }

        /** The turn at a share {@code eased} of the whole way. */
        float degreesAt(float eased) {
            float first = firstLeg();
            float travelled = (first + 90) * eased;
            if (travelled < first) {
                return from + Math.signum(side * 90 - from) * travelled;
            }
            return -side * 90 + side * (travelled - first);
        }
    }

    /** The ticker on one scene's wall-time frames that steps whichever motions are current. */
    private final class MotionTicker implements Scene.Ticker {
        final Scene scene;

        MotionTicker(Scene scene) {
            this.scene = scene;
        }

        @Override
        public boolean tick(double dt) {
            return step(this, dt);
        }
    }

    // ------------------------------------------------------------------ values

    @Override
    public float translationX() {
        return translationX;
    }

    @Override
    public float translationY() {
        return translationY;
    }

    @Override
    public float scaleX() {
        return scaleX;
    }

    @Override
    public float scaleY() {
        return scaleY;
    }

    @Override
    public float originX() {
        return originX;
    }

    @Override
    public float originY() {
        return originY;
    }

    @Override
    public float rotation() {
        return rotation;
    }

    @Override
    public Mat4 matrix() {
        return general != null ? general : Mat4.IDENTITY;
    }

    @Override
    public Quat rotation3D() {
        return depthTurn != null ? depthTurn : Quat.IDENTITY;
    }

    @Override
    public float perspective() {
        return perspective;
    }

    @Override
    public boolean isBackfaceVisible() {
        return backfaceVisible;
    }

    @Override
    public void setRotation3D(Quat turn) {
        checkSettable();
        Objects.requireNonNull(turn, "turn");
        finite(turn.x(), "turn's component");
        finite(turn.y(), "turn's component");
        finite(turn.z(), "turn's component");
        finite(turn.w(), "turn's component");
        double length = Math.sqrt((double) turn.x() * turn.x() + (double) turn.y() * turn.y()
                + (double) turn.z() * turn.z() + (double) turn.w() * turn.w());
        if (!(length > 0)) {
            throw new IllegalArgumentException("a turn is a quaternion of some length: " + turn);
        }
        // Of q and -q, which name one turn, the one with w not below 0, and -0 read as 0, so that
        // the same turn set twice is the same value and is not announced.
        float sign = turn.w() < 0 || (turn.w() == 0 && (turn.x() < 0 || (turn.x() == 0
                && (turn.y() < 0 || (turn.y() == 0 && turn.z() < 0))))) ? -1 : 1;
        Quat unit = new Quat((float) (sign * turn.x() / length) + 0f, (float) (sign * turn.y() / length) + 0f,
                (float) (sign * turn.z() / length) + 0f, (float) (sign * turn.w() / length) + 0f);
        Quat kept = unit.x() == 0 && unit.y() == 0 && unit.z() == 0 ? null : unit;
        if (!sameTurn(kept, depthTurn)) {
            depthTurn = kept;
            moved(true);
            announce();
        }
    }

    /** Whether two turns, null as none, have the same components. */
    private static boolean sameTurn(@Nullable Quat a, @Nullable Quat b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.w() == b.w();
    }

    @Override
    public void setPerspective(float distance) {
        checkSettable();
        if (!(distance > 0)) {
            throw new IllegalArgumentException("an eye in front of the child is at a distance above 0, "
                    + "or infinitely far for no perspective: " + distance);
        }
        if (distance != perspective) {
            perspective = distance;
            lens = distance == Float.POSITIVE_INFINITY ? null : Mat4.perspectiveAt(distance);
            moved(true);
            announce();
        }
    }

    @Override
    public void setBackfaceVisible(boolean visible) {
        checkSettable();
        if (visible != backfaceVisible) {
            backfaceVisible = visible;
            moved(true);
            announce();
        }
    }

    @Override
    public void setTranslation(float x, float y) {
        checkSettable();
        finite(x, "translation");
        finite(y, "translation");
        slide = supersede(slide, SLIDE);
        if (x != translationX || y != translationY) {
            translationX = x;
            translationY = y;
            moved(true);
            announce();
        }
    }

    @Override
    public void setScale(float sx, float sy) {
        checkSettable();
        finite(sx, "scale");
        finite(sy, "scale");
        scale = supersede(scale, SCALE);
        if (sx != scaleX || sy != scaleY) {
            scaleX = sx;
            scaleY = sy;
            moved(true);
            announce();
        }
    }

    @Override
    public void setRotation(float degrees) {
        checkSettable();
        finite(degrees, "rotation");
        rotating = supersede(rotating, ROTATION);
        if (degrees != rotation) {
            rotation = degrees;
            moved(true);
            announce();
        }
    }

    @Override
    public void setMatrix(Mat4 next) {
        checkSettable();
        Objects.requireNonNull(next, "matrix");
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                finite(next.get(row, col), "matrix's entry");
            }
        }
        Mat4 kept = ChildTransform.isIdentity(next) ? null : next;
        if (!sameMatrix(kept, general)) {
            general = kept;
            moved(true);
            announce();
        }
    }

    /** Whether two matrices, null as the identity, have the same entries. */
    private static boolean sameMatrix(@Nullable Mat4 a, @Nullable Mat4 b) {
        if (a == b) {
            return true;
        }
        Mat4 left = a != null ? a : Mat4.IDENTITY;
        Mat4 right = b != null ? b : Mat4.IDENTITY;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                if (left.get(row, col) != right.get(row, col)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void setOrigin(float fractionX, float fractionY) {
        checkSettable();
        finite(fractionX, "origin");
        finite(fractionY, "origin");
        if (fractionX != originX || fractionY != originY) {
            originX = fractionX;
            originY = fractionY;
            moved(true);
            announce();
        }
    }

    @Override
    public void reset() {
        checkSettable();
        slide = supersede(slide, SLIDE);
        scale = supersede(scale, SCALE);
        rotating = supersede(rotating, ROTATION);
        flip = supersede(flip, FLIP);
        boolean atRest = translationX == 0 && translationY == 0 && scaleX == 1 && scaleY == 1
                && originX == 0.5f && originY == 0.5f && flipDegrees == 0 && rotation == 0
                && general == null && depthTurn == null && lens == null && backfaceVisible;
        if (!atRest) {
            translationX = 0;
            translationY = 0;
            scaleX = 1;
            scaleY = 1;
            originX = 0.5f;
            originY = 0.5f;
            flipDegrees = 0;
            rotation = 0;
            general = null;
            depthTurn = null;
            perspective = Float.POSITIVE_INFINITY;
            lens = null;
            backfaceVisible = true;
            moved(true);
            announce();
        }
    }

    // ----------------------------------------------------------------- motions

    @Override
    public void slideTo(float x, float y, double seconds, @Nullable Runnable onArrive) {
        checkSettable();
        finite(x, "translation");
        finite(y, "translation");
        checkSeconds(seconds);
        slide = supersede(slide, SLIDE);
        Scene ticking = tickingScene(seconds);
        if (ticking == null || (x == translationX && y == translationY)) {
            if (x != translationX || y != translationY) {
                translationX = x;
                translationY = y;
                moved(true);
                announce();
            }
            arriveLater(SLIDE, seconds, null, onArrive);
            return;
        }
        slide = new Motion(translationX, translationY, x, y, motionSeconds(seconds), onArrive);
        start(ticking);
    }

    @Override
    public void scaleTo(float sx, float sy, double seconds, @Nullable Runnable onArrive) {
        checkSettable();
        finite(sx, "scale");
        finite(sy, "scale");
        checkSeconds(seconds);
        scale = supersede(scale, SCALE);
        Scene ticking = tickingScene(seconds);
        if (ticking == null || (sx == scaleX && sy == scaleY)) {
            if (sx != scaleX || sy != scaleY) {
                scaleX = sx;
                scaleY = sy;
                moved(true);
                announce();
            }
            arriveLater(SCALE, seconds, null, onArrive);
            return;
        }
        scale = new Motion(scaleX, scaleY, sx, sy, motionSeconds(seconds), onArrive);
        start(ticking);
    }

    @Override
    public void rotateTo(float degrees, double seconds, @Nullable Runnable onArrive) {
        checkSettable();
        finite(degrees, "rotation");
        checkSeconds(seconds);
        rotating = supersede(rotating, ROTATION);
        Scene ticking = tickingScene(seconds);
        if (ticking == null || degrees == rotation) {
            if (degrees != rotation) {
                rotation = degrees;
                moved(true);
                announce();
            }
            arriveLater(ROTATION, seconds, null, onArrive);
            return;
        }
        rotating = new Motion(rotation, 0, degrees, 0, motionSeconds(seconds), onArrive);
        start(ticking);
    }

    @Override
    public void flipTo(boolean aboutVertical, boolean reverse, double seconds, Runnable atEdge,
                       @Nullable Runnable onArrive) {
        checkSettable();
        Objects.requireNonNull(atEdge, "atEdge");
        checkSeconds(seconds);
        flip = supersede(flip, FLIP);
        Scene ticking = tickingScene(seconds);
        if (ticking == null) {
            // Less motion, no time, or no frames: the content swaps with no turn at all, and a
            // flip left half-way by one superseded comes back flat. The swap waits with the
            // arrival, so that no callback of the application runs inside the call.
            if (flipDegrees != 0) {
                flipDegrees = 0;
                moved(true);
                announce();
            }
            arriveLater(FLIP, seconds, atEdge, onArrive);
            return;
        }
        // Which way the first half turns, physically: forward, the end edge comes up toward the
        // reader and over to the start, so a right-to-left subtree turns the other way; up, the
        // bottom edge comes up and over the top, whatever the direction. The sign is the one a
        // rotation about the axis needs to bring that edge to positive z, toward the viewer.
        float side;
        if (aboutVertical) {
            boolean endIsRight = !owner.isRightToLeft();
            side = endIsRight != reverse ? -1 : 1;
        } else {
            side = reverse ? -1 : 1;
        }
        if (flipAboutVertical != aboutVertical && flipDegrees != 0) {
            // A flip about the other axis superseded half-way: this one starts from flat, as its
            // own axis is.
            flipDegrees = 0;
        }
        flipAboutVertical = aboutVertical;
        flip = new Flip(aboutVertical, side, flipDegrees, motionSeconds(seconds), atEdge, onArrive);
        start(ticking);
    }

    /**
     * A motion begins: its start announced, and the one ticker made if this container has none on
     * this scene. Its first frame moves nothing.
     */
    private void start(Scene ticking) {
        announce();
        MotionTicker stepping = ticker;
        if (stepping == null || stepping.scene != ticking) {
            stepping = new MotionTicker(ticking);
            ticker = stepping;
            ticking.addRealTimeTicker(stepping);
        }
    }

    /**
     * One frame of every motion in flight: each eased toward its target, the matrix set once with
     * a picture-only damage, and then each that arrived settled, announced and its
     * {@code onArrive} run. The ticker stops when no motion is left for it.
     */
    private boolean step(MotionTicker stepping, double dt) {
        if (ticker != stepping) {
            return false; // on a scene the container has left, whose leaving settled its motions
        }
        if (slide == null && scale == null && rotating == null && flip == null) {
            ticker = null;
            return false;
        }
        Motion sliding = slide;
        Motion scaling = scale;
        Motion turning = rotating;
        Flip flipping = flip;
        boolean slideArrives = sliding != null && advance(sliding, dt);
        boolean scaleArrives = scaling != null && advance(scaling, dt);
        boolean turnArrives = turning != null && advance(turning, dt);
        boolean flipArrives = false;
        boolean flipReachesEdge = false;
        if (sliding != null) {
            float t = eased(sliding);
            translationX = sliding.fromA + (sliding.toA - sliding.fromA) * t;
            translationY = sliding.fromB + (sliding.toB - sliding.fromB) * t;
        }
        if (scaling != null) {
            float t = eased(scaling);
            scaleX = scaling.fromA + (scaling.toA - scaling.fromA) * t;
            scaleY = scaling.fromB + (scaling.toB - scaling.fromB) * t;
        }
        if (turning != null) {
            rotation = turning.fromA + (turning.toA - turning.fromA) * eased(turning);
        }
        if (flipping != null) {
            if (flipping.started) {
                flipping.elapsed += dt;
            } else {
                flipping.started = true;
            }
            float t = (float) Math.min(1.0, flipping.elapsed / flipping.seconds);
            flipArrives = t >= 1f;
            float travelled = (flipping.firstLeg() + 90) * limn.math.Scalars.smoothstep(t);
            flipReachesEdge = !flipping.swapped && travelled >= flipping.firstLeg();
            flipDegrees = flipArrives ? 0 : flipping.degreesAt(limn.math.Scalars.smoothstep(t));
        }
        if (slideArrives) {
            translationX = sliding.toA;
            translationY = sliding.toB;
        }
        if (scaleArrives) {
            scaleX = scaling.toA;
            scaleY = scaling.toB;
        }
        if (turnArrives) {
            rotation = turning.toA;
        }
        // One damage for the frame, of where the child was and where it is: the accessible tree
        // is walked only when something arrives, so a reader's highlight jumps to where the child
        // came to rest and does not follow the frames between.
        moved(slideArrives || scaleArrives || turnArrives || flipArrives);
        if (flipReachesEdge && flip == flipping) {
            flipping.swapped = true;
            flipping.atEdge.run(); // the application swaps what the child shows, edge-on
        }
        if (slideArrives && slide == sliding) {
            slide = null;
            arrive(sliding.onArrive);
        }
        if (scaleArrives && scale == scaling) {
            scale = null;
            arrive(scaling.onArrive);
        }
        if (turnArrives && rotating == turning) {
            rotating = null;
            arrive(turning.onArrive);
        }
        if (flipArrives && flip == flipping) {
            flip = null;
            arrive(flipping.onArrive);
        }
        if (ticker != stepping) {
            return false; // an onArrive took the container out of this scene
        }
        if (slide != null || scale != null || rotating != null || flip != null) {
            return true; // still moving, or an onArrive started another motion on this ticker
        }
        ticker = null;
        return false;
    }

    /** Steps a slide's, a scale's or a rotation's clock; whether it has arrived. */
    private static boolean advance(Motion motion, double dt) {
        if (motion.started) {
            motion.elapsed += dt;
        } else {
            motion.started = true; // its first frame is its start, as a fresh ticker's is
        }
        return motion.elapsed >= motion.seconds;
    }

    private static float eased(Motion motion) {
        float t = (float) Math.min(1.0, motion.elapsed / motion.seconds);
        return limn.math.Scalars.smoothstep(t);
    }

    /** A motion arrives: its one announcement of the end, and what it runs. */
    private void arrive(@Nullable Runnable onArrive) {
        announce();
        runIfAny(onArrive);
    }

    /**
     * The container leaves the scene whose frames step its motions: each settles at its target
     * now, announcing its end and running what it runs, a flip that has not reached its edge
     * running its {@code atEdge} first, and the container lets go of the ticker and of that scene.
     * As the opacity's fade settles, and for its reasons: an {@code onArrive} often hides or removes
     * something, and a container taken away mid-motion has asked for that end.
     */
    void settleOnLeaving() {
        ticker = null; // the ticker left behind finds itself replaced, if that scene ticks again
        Motion sliding = slide;
        Motion scaling = scale;
        Motion turning = rotating;
        Flip flipping = flip;
        if (sliding == null && scaling == null && turning == null && flipping == null) {
            return;
        }
        slide = null;
        scale = null;
        rotating = null;
        flip = null;
        if (sliding != null) {
            translationX = sliding.toA;
            translationY = sliding.toB;
        }
        if (scaling != null) {
            scaleX = scaling.toA;
            scaleY = scaling.toB;
        }
        if (turning != null) {
            rotation = turning.toA;
        }
        if (flipping != null) {
            flipDegrees = 0;
        }
        moved(true);
        if (flipping != null && !flipping.swapped) {
            flipping.atEdge.run();
        }
        if (sliding != null) {
            arrive(sliding.onArrive);
        }
        if (scaling != null) {
            arrive(scaling.onArrive);
        }
        if (turning != null) {
            arrive(turning.onArrive);
        }
        if (flipping != null) {
            arrive(flipping.onArrive);
        }
    }

    /**
     * A motion in flight stops where it is and announces its end; its onArrive never runs. One of
     * the same kind that went straight there and waits to arrive is dropped, its value already set.
     */
    private <T> @Nullable T supersede(@Nullable T motion, int kind) {
        Arrival waiting = arrivals[kind];
        if (waiting != null) {
            arrivals[kind] = null;
            Subscription timer = waiting.timer;
            if (timer != null) {
                timer.cancel(); // lets go of what it captures now, not when it falls due
            }
        }
        if (motion != null) {
            announce();
        }
        return null;
    }

    /**
     * A motion that went straight there arrives later, as one stepped by frames arrives on a later
     * frame: a flip's {@code atEdge} and then its {@code onArrive} run once, after the seconds the
     * call asked for or on the next turn when it asked for none, unless something supersedes the
     * motion first, the swap included: a flip its own {@code atEdge} supersedes does not arrive, as
     * one that turns would not.
     */
    private void arriveLater(int kind, double seconds, @Nullable Runnable atEdge,
                             @Nullable Runnable onArrive) {
        if (atEdge == null && onArrive == null) {
            return;
        }
        Arrival arrival = new Arrival(atEdge, onArrive);
        arrivals[kind] = arrival;
        arrival.timer = StraightArrival.post(seconds, () -> {
            if (arrivals[kind] != arrival) {
                return; // superseded before its time
            }
            Runnable swap = arrival.atEdge;
            if (swap != null) {
                swap.run();
                if (arrivals[kind] != arrival) {
                    return; // the swap itself superseded the flip
                }
            }
            arrivals[kind] = null;
            runIfAny(arrival.onArrive);
        });
    }

    @Override
    public void laidOut() {
        // The origin is a fraction of the box and the translation's x follows the direction, so a
        // new size or a new direction moves the matrix; the layout that moved them repaints it.
        if (owner.childTransform != null || !atRest()) {
            owner.setChildMatrix(composed());
        }
    }

    /** The matrix the container draws its child through now, as a value: for the internal hook. */
    Mat4 snapshot() {
        return matrix.toMat4();
    }

    // ------------------------------------------------------------------ matrix

    /**
     * Whether every value is at rest, so the container draws its child where layout put it. A
     * perspective and the back face's flag alone move nothing: the child's plane lies at the
     * perspective's own depth until something turns it, and shows its front.
     */
    private boolean atRest() {
        return translationX == 0 && translationY == 0 && scaleX == 1 && scaleY == 1 && flipDegrees == 0
                && rotation == 0 && general == null && depthTurn == null;
    }

    /**
     * The matrix the values make, composed into the one this container holds, or null at rest:
     * {@code O · translation · perspective · flip · turn in depth · rotation · scale · matrix · O⁻¹},
     * with the origin's x and the translation's x read from the start edge. In place, into one
     * matrix held for the life of the container: a frame of a motion allocates only the vectors and
     * the turn handed to {@link MutMat4#setTrs}, where a new matrix a frame cost several times that.
     */
    private @Nullable MutMat4 composed() {
        if (atRest()) {
            return null;
        }
        boolean rtl = owner.isRightToLeft();
        float ox = (rtl ? 1 - originX : originX) * owner.width();
        float oy = originY * owner.height();
        float tx = rtl ? -translationX : translationX;
        Quat turn = Quat.IDENTITY;
        if (flipDegrees != 0) {
            // A unit turn about one axis, written out: sin and cos of half the angle.
            double half = Math.toRadians(flipDegrees) / 2;
            float sin = (float) Math.sin(half);
            float cos = (float) Math.cos(half);
            turn = flipAboutVertical ? new Quat(0, sin, 0, cos) : new Quat(sin, 0, 0, cos);
        }
        if (depthTurn != null) {
            // The turn in depth after the rotation and before the flip: the flip turns the child as
            // it appears, turned or not.
            turn = turn == Quat.IDENTITY ? depthTurn : turn.multiply(depthTurn);
        }
        if (rotation != 0) {
            // About z, clockwise on the screen since y runs down, first of the turns.
            double half = Math.toRadians(rotation) / 2;
            Quat clockwise = new Quat(0, 0, (float) Math.sin(half), (float) Math.cos(half));
            turn = turn == Quat.IDENTITY ? clockwise : turn.multiply(clockwise);
        }
        Mat4 eye = lens;
        if (eye == null) {
            matrix.setTrs(new Vec3(ox + tx, oy + translationY, 0), turn, new Vec3(scaleX, scaleY, 1));
        } else {
            // The eye looks at the turned child from in front of the origin, and the translation
            // moves what it sees: the perspective between the move and the turns.
            matrix.setTrs(new Vec3(ox + tx, oy + translationY, 0), Quat.IDENTITY, ONE).mul(eye)
                    .mul(turned.setTrs(Vec3.ZERO, turn, new Vec3(scaleX, scaleY, 1)));
        }
        if (general != null) {
            matrix.mul(general);
        }
        return matrix.mul(toOrigin.setTrs(new Vec3(-ox, -oy, 0), Quat.IDENTITY, ONE));
    }

    /**
     * The values moved: the place the child was drawn is damaged, the new matrix set, and the
     * place it is drawn now damaged, with no layout. A set and an arrival also have the accessible
     * tree walked, since the bounds it publishes moved, and have a popup anchored inside placed
     * again; the frames between do neither.
     */
    private void moved(boolean settled) {
        Scene scene = owner.scene();
        if (scene != null) {
            scene.damageMovedPicture(owner, settled);
        }
        owner.setChildBackfaceVisible(backfaceVisible);
        owner.setChildMatrix(composed());
        if (scene != null) {
            scene.damageMovedPicture(owner, settled);
            if (settled) {
                scene.anchorsMoved(owner);
            }
        }
    }

    // ------------------------------------------------------------------ rules

    private void announce() {
        owner.notifyChange(Change.of(Change.Aspect.TRANSFORM, Change.Origin.CODE));
    }

    private static void checkSettable() {
        Ui.checkUiThread();
        Widget.checkNotPainting(Change.Aspect.TRANSFORM);
    }

    private static void finite(float value, String what) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("a " + what + " is a finite number: " + value);
        }
    }

    private static void checkSeconds(double seconds) {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException("a motion takes zero seconds or more: " + seconds);
        }
    }

    /** How long a motion takes: none while the system asks for less motion. */
    private static double motionSeconds(double seconds) {
        return SystemPreferences.current().reduceMotion() ? 0 : seconds;
    }

    /**
     * The scene whose frames will step a motion of {@code seconds}, or null when it goes straight
     * to its target: less motion asked for, no time, no scene, or a scene with no window.
     */
    private @Nullable Scene tickingScene(double seconds) {
        Scene scene = owner.scene();
        if (motionSeconds(seconds) <= 0 || scene == null || scene.window() == null) {
            return null;
        }
        return scene;
    }

    private static void runIfAny(@Nullable Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
