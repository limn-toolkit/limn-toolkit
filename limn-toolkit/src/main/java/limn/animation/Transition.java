package limn.animation;

import limn.concurrent.Ui;
import limn.scene.Widget;

import java.util.Objects;

/**
 * One animated {@code float} property: the toolkit's reusable "CSS transition".
 * A widget owns a {@code Transition} per property it wants to animate (opacity,
 * a fill level, a lerp factor, a position, …), configures it once, then just
 * moves the target with {@link #to(float)}; {@link #value()}, read each paint,
 * eases toward it.
 *
 * <p>Like a CSS {@code transition}, the developer controls whether it animates
 * ({@link #enabled}), the {@link #duration}, the {@link #easing} curve and
 * whether it {@link #repeat}s, for any property, since the value is just a
 * number the widget maps onto whatever it draws.
 *
 * <p><b>Optimized</b>: it drives itself off the scene's frame ticker only while
 * moving and unregisters the instant it settles, so an idle widget costs nothing.
 * <b>Safe</b>: {@link #to} / {@link #snap} are UI-thread-confined, and when the
 * owner has no scene (headless, or detached) it simply jumps to the target: the
 * same "final state, no animation" a test or an off-screen widget wants.
 *
 * <pre>{@code
 * private final Transition fade = new Transition(this).duration(0.15).easing(Easing.EASE_OUT);
 * // on state change:
 * fade.to(visible ? 1 : 0);
 * // in onPaint:
 * color.withAlpha(fade.value());
 * }</pre>
 */
public final class Transition {

    private final Widget owner;

    private double duration = 0.2;
    private Easing easing = Easing.EASE_OUT;
    private boolean enabled = true;
    private boolean repeat;
    private boolean sceneTime;

    private float current;
    private float from;
    private float to;
    private double elapsed;
    private boolean animating;
    private boolean ticking;

    /** A transition starting (and resting) at {@code 0}. */
    public Transition(Widget owner) {
        this(owner, 0f);
    }

    /** A transition starting at {@code initial}, repainting {@code owner} as it runs. */
    public Transition(Widget owner, float initial) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.current = this.from = this.to = initial;
    }

    // --------------------------------------------------------------- config

    /** Sets the animation length in seconds ({@code 0} = no animation; {@link #to} snaps). */
    public Transition duration(double seconds) {
        this.duration = Math.max(0, seconds);
        return this;
    }

    /** Sets the timing curve (default {@link Easing#EASE_OUT}). */
    public Transition easing(Easing curve) {
        this.easing = Objects.requireNonNull(curve, "easing");
        return this;
    }

    /** When {@code false}, {@link #to} jumps instead of animating (default {@code true}). */
    public Transition enabled(boolean value) {
        this.enabled = value;
        return this;
    }

    /**
     * When {@code true}, the animation ping-pongs between its endpoints forever
     * (a pulse/breathe) until {@link #snap} or {@code repeat(false)} stops it.
     */
    public Transition repeat(boolean value) {
        this.repeat = value;
        return this;
    }

    /**
     * Whether this transition runs on the scene's <b>scene time</b> (and so obeys
     * {@link limn.scene.Scene#setPaused} and {@link limn.scene.Scene#setTimeScale}) instead of
     * wall time. Default {@code false}.
     *
     * <p>The default is what every widget in the toolkit wants: a hover, focus or press fade is
     * shell feedback, and feedback that stops responding while an app pauses its own animation
     * reads as a hang, not as a pause. Turn it on for a transition that animates <b>content</b>
     * (a game object's position, a value the simulation owns), which is exactly what pausing is
     * supposed to stop.
     *
     * <p>Read when the transition registers itself with the scene, so flipping it mid-animation
     * takes effect on the next run rather than the current one.
     */
    public Transition sceneTime(boolean value) {
        this.sceneTime = value;
        return this;
    }

    /** Duration of one run, in seconds. */
    public double duration() {
        return duration;
    }

    /** Whether animating at all; when off, the value jumps to its target. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether the transition restarts on completion instead of settling. */
    public boolean repeats() {
        return repeat;
    }

    // -------------------------------------------------------------- control

    /**
     * Animates the value toward {@code target}. Jumps immediately (no animation)
     * when disabled, zero-duration, or the owner is detached from a scene. UI thread.
     */
    public void to(float target) {
        Ui.checkUiThread();
        if (!enabled || duration <= 0 || owner.scene() == null) {
            snap(target);
            return;
        }
        if (target == to) {
            return; // already there, or already easing toward it
        }
        from = current;
        to = target;
        elapsed = 0;
        animating = true;
        ensureTicking();
    }

    /** Jumps to {@code value} immediately, cancelling any animation. UI thread. */
    public void snap(float value) {
        Ui.checkUiThread();
        current = from = to = value;
        animating = false;
        owner.invalidate();
    }

    /** @return the current (eased) value; read this each paint */
    public float value() {
        // Paint happens only while the owner is showing, so this is the resume point
        // for an animation that paused itself while its widget was hidden.
        if (animating && !ticking && owner.isShowing()) {
            ensureTicking();
        }
        return current;
    }

    /** @return the value it is heading toward */
    public float target() {
        return to;
    }

    /** Whether the value is still moving: false once it has settled on its target. */
    public boolean isAnimating() {
        return animating;
    }

    // -------------------------------------------------------------- driving

    private void ensureTicking() {
        if (ticking || owner.scene() == null) {
            return;
        }
        ticking = true;
        // Wall time by default: a Transition is chrome (hover, focus, press, the tab indicator
        // sliding) and chrome that stops moving while the app pauses its own scene time reads
        // as a hang. Content opts in with sceneTime(true).
        if (sceneTime) {
            owner.scene().addTicker(this::tick);
        } else {
            owner.scene().addRealTimeTicker(this::tick);
        }
    }

    /** Advances one frame; returns whether it should keep ticking. Package-private for tests. */
    boolean tick(double dt) {
        if (!animating || owner.scene() == null) {
            ticking = false;
            return false;
        }
        if (!owner.isShowing()) {
            // Inside a hidden container: pause (unregister) rather than burn frames;
            // value(), called on the next paint, re-arms us. State is left frozen.
            ticking = false;
            return false;
        }
        elapsed += dt;
        float t = duration > 0 ? (float) Math.min(1.0, elapsed / duration) : 1f;
        current = from + (to - from) * easing.apply(t);
        owner.invalidate();
        if (t >= 1f) {
            current = to;
            if (repeat) {
                float swap = from;
                from = to;
                to = swap;
                elapsed = 0;
                return true;
            }
            animating = false;
            ticking = false;
            return false;
        }
        return true;
    }
}
