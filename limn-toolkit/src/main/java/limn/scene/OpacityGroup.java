package limn.scene;

import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import org.jspecify.annotations.Nullable;

/**
 * The opacity of one {@link limn.scene.layout.Opacity} container and the fade that moves it: the
 * value its paint opens a layer with, the fade in flight, and the one ticker that steps it.
 *
 * <p>Here, beside {@link Widget}, and not in the container, because the three things it touches are
 * {@code limn.scene}'s own: the value is read by {@link Widget#paintWidget} and
 * {@link Widget#paintedBounds()}, a change damages through the scene's picture-only path, and a
 * fade settles as the widget leaves its scene, which only the widget's own walk of the tree sees,
 * from one scene straight into another included. The container reaches it through
 * {@link limn.scene.internal.OpacityAccess}. A widget holds one only when it is such a container,
 * so a widget nobody fades pays one null reference for the whole feature.
 */
final class OpacityGroup {

    /** The container this is the opacity of, and the widget that announces its changes. */
    private final Widget<?> owner;
    /** The opacity, 0 to 1; an ancestor group's multiplies it as pictures do. */
    float value = 1;
    /** The fade moving {@link #value} now, or null. */
    private @Nullable Fade fade;
    /**
     * The one ticker stepping this group's fades, while it is registered, or null. One per group
     * and not one per fade, as {@code Transition} keeps one: a superseded fade's ticker went only at
     * its next tick, and a covered or minimized window renders no frames, so a fade asked for there
     * on every update of some data added a ticker each time that nothing took away.
     */
    private @Nullable FadeTicker ticker;
    /**
     * The {@code onArrive} of a fade that went straight to its target, waiting on the UI queue for
     * the seconds its call asked for, or for the next turn ({@link StraightArrival}), or null.
     * Whatever supersedes a fade drops it, as it drops a fade in flight.
     */
    private @Nullable Runnable waitingArrival;
    /** What withdraws {@link #waitingArrival} from the queue, while one waits. */
    private @Nullable Subscription waitingTimer;

    OpacityGroup(Widget<?> owner) {
        this.owner = owner;
    }

    /** One fade in flight: where it started, where it goes, how long it takes, what it runs at the end. */
    private static final class Fade {
        final float from;
        final float target;
        final double seconds;
        final @Nullable Runnable onArrive;
        double elapsed;
        /** Whether a frame has stepped it: its first frame is its start and moves nothing. */
        boolean started;

        Fade(float from, float target, double seconds, @Nullable Runnable onArrive) {
            this.from = from;
            this.target = target;
            this.seconds = seconds;
            this.onArrive = onArrive;
        }
    }

    /** The ticker on one scene's wall-time frames that steps whichever fade is current. */
    private final class FadeTicker implements Scene.Ticker {
        final Scene scene;

        FadeTicker(Scene scene) {
            this.scene = scene;
        }

        @Override
        public boolean tick(double dt) {
            return step(this, dt);
        }
    }

    /** The container's {@code setOpacity}: see its Javadoc for the rules this keeps. */
    void set(float opacity) {
        Ui.checkUiThread();
        Widget.checkNotPainting(Change.Aspect.OPACITY);
        float clamped = checked(opacity);
        supersede();
        if (clamped != value) {
            move(clamped);
            owner.notifyChange(Change.of(Change.Aspect.OPACITY, Change.Origin.CODE));
        }
    }

    /** The container's {@code fadeTo}: see its Javadoc for the rules this keeps. */
    void fadeTo(float target, double seconds, @Nullable Runnable onArrive) {
        Ui.checkUiThread();
        Widget.checkNotPainting(Change.Aspect.OPACITY);
        float clamped = checked(target);
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            // Finite, as a transform's motions are: a fade of infinite length would never arrive,
            // and its onArrive, often what hides or removes the container, would never run.
            throw new IllegalArgumentException("a fade takes a finite number of seconds, zero or more: "
                    + seconds);
        }
        supersede();
        Scene ticking = owner.scene();
        double length = SystemPreferences.current().reduceMotion() ? 0 : seconds;
        if (length <= 0 || ticking == null || ticking.window() == null || clamped == value) {
            if (clamped != value) {
                move(clamped);
                owner.notifyChange(Change.of(Change.Aspect.OPACITY, Change.Origin.CODE));
            }
            arriveLater(seconds, onArrive);
            return;
        }
        fade = new Fade(value, clamped, length, onArrive);
        owner.notifyChange(Change.of(Change.Aspect.OPACITY, Change.Origin.CODE));
        FadeTicker stepping = ticker;
        if (stepping == null || stepping.scene != ticking) {
            // The one this group has steps the new fade as it would have stepped the old one.
            stepping = new FadeTicker(ticking);
            ticker = stepping;
            ticking.addRealTimeTicker(stepping);
        }
    }

    /**
     * One frame of whichever fade is current: eased toward its target, quietly, and settled at the
     * end. The ticker stops when no fade is left for it, a superseded one having announced its end.
     */
    private boolean step(FadeTicker stepping, double dt) {
        if (ticker != stepping) {
            return false; // on a scene the container has left, whose leaving settled its fade
        }
        Fade current = fade;
        if (current == null) {
            ticker = null;
            return false;
        }
        if (current.started) {
            current.elapsed += dt;
        } else {
            current.started = true; // its first frame is its start, as a fresh ticker's is
        }
        float t = (float) Math.min(1.0, current.elapsed / current.seconds);
        if (t < 1f) {
            move(current.from + (current.target - current.from) * limn.math.Scalars.smoothstep(t));
            return true;
        }
        settle(current);
        if (ticker != stepping) {
            return false; // its onArrive took the container out of this scene
        }
        if (fade != null) {
            return true; // its onArrive started another fade, which this ticker steps
        }
        ticker = null;
        return false;
    }

    /**
     * The container leaves the scene whose frames step its fade: the fade settles at its target
     * now, announcing its end and running its {@code onArrive}, as it would have at its last frame,
     * and the group lets go of the ticker, and through it of that scene. Not at that scene's next
     * frame, which a closed, covered or minimized window never renders: the container moved to
     * another window kept a value between the two for good and the scene it left alive.
     *
     * <p>Settled and not dropped, because the design of fades says a container that leaves its
     * scene settles at its target and runs its {@code onArrive}, and that is the one end that keeps
     * its two announcements and what an {@code onArrive} is for: it hides or removes something, and
     * a container taken away mid-fade has asked for that end, not stopped short of it. Only a newer
     * fade or a set supersedes one, which is the caller choosing another end.
     */
    void settleOnLeaving() {
        ticker = null; // the ticker left behind finds itself replaced, if that scene ticks again
        Fade leaving = fade;
        if (leaving != null) {
            settle(leaving);
        }
    }

    /** A fade arrives: the target, its one announcement of the end, and what it runs. */
    private void settle(Fade arriving) {
        fade = null;
        move(arriving.target);
        owner.notifyChange(Change.of(Change.Aspect.OPACITY, Change.Origin.CODE));
        if (arriving.onArrive != null) {
            arriving.onArrive.run();
        }
    }

    /**
     * A fade that went straight there arrives later, as one stepped by frames arrives on a later
     * frame: its {@code onArrive} runs once, after the seconds the call asked for or on the next
     * turn when it asked for none, unless something supersedes the fade first.
     */
    private void arriveLater(double seconds, @Nullable Runnable onArrive) {
        if (onArrive == null) {
            return;
        }
        Runnable arrival = onArrive::run; // its own identity: the same Runnable may be asked for twice
        waitingArrival = arrival;
        waitingTimer = StraightArrival.post(seconds, () -> {
            if (waitingArrival != arrival) {
                return; // superseded before its time
            }
            waitingArrival = null;
            waitingTimer = null;
            arrival.run();
        });
    }

    /**
     * A fade in flight stops where it is, announces its end, and never runs its onArrive; one that
     * went straight there and waits to arrive is dropped, its value already set.
     */
    private void supersede() {
        waitingArrival = null;
        Subscription timer = waitingTimer;
        if (timer != null) {
            waitingTimer = null;
            timer.cancel(); // lets go of what it captures now, not when it falls due
        }
        if (fade != null) {
            fade = null;
            owner.notifyChange(Change.of(Change.Aspect.OPACITY, Change.Origin.CODE));
        }
    }

    /**
     * Sets the value and damages what it draws, with no announcement and no layout: what the
     * subtree paints ({@link Widget#paintedBounds()}), the container's box and outset and any
     * descendant's past them, as a picture-only damage that does not ask the accessible tree to be
     * walked, since nothing in it reads an opacity.
     */
    private void move(float to) {
        if (to == value) {
            return;
        }
        value = to;
        Scene scene = owner.scene();
        if (scene != null) {
            scene.damageWidgetPicture(owner);
        }
    }

    private static float checked(float opacity) {
        if (Float.isNaN(opacity)) {
            throw new IllegalArgumentException("an opacity is a number from 0 to 1: " + opacity);
        }
        return Math.max(0f, Math.min(1f, opacity));
    }
}
