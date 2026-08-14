package limn.animation;

import limn.graphics.Color;
import limn.scene.Widget;

import java.util.Objects;

/**
 * An animated {@link Color}: the color counterpart to {@link Transition}. It
 * fades from the current color to a new target over a duration with an easing,
 * so a widget can transition a fill/stroke/tint from a→b like a CSS color
 * transition. Internally it drives a {@link Transition} for the 0→1 progress and
 * blends with {@link Color#lerp}, so it inherits the same guarantees: it drives
 * itself off the scene ticker only while moving, is UI-thread-confined, and
 * snaps straight to the target when the owner is detached/headless or the
 * animation is disabled.
 *
 * <pre>{@code
 * private final ColorTransition fill =
 *         new ColorTransition(this, Theme.current().primary)
 *                 .duration(Theme.current().animHover).easing(Theme.current().animEasing);
 * // on state change:
 * fill.to(hovered ? Theme.current().primaryHover : Theme.current().primary);
 * // in onPaint:
 * canvas.fillRoundRect(x, y, w, h, r, fill.value());
 * }</pre>
 */
public final class ColorTransition {

    private final Transition progress;
    private Color from;
    private Color to;

    /** Rests at {@code initial}. */
    public ColorTransition(Widget owner, Color initial) {
        this.progress = new Transition(owner, 1f);
        this.from = this.to = Objects.requireNonNull(initial, "initial");
    }

    // --------------------------------------------------------------- config

    /** Duration of one run, in seconds. */
    public ColorTransition duration(double seconds) {
        progress.duration(seconds);
        return this;
    }

    /** The curve the interpolation follows. */
    public ColorTransition easing(Easing curve) {
        progress.easing(curve);
        return this;
    }

    /** Turns animation off, so a new target takes effect on the next frame. */
    public ColorTransition enabled(boolean value) {
        progress.enabled(value);
        return this;
    }

    /** See {@link Transition#sceneTime(boolean)}. Off by default, like the transition it drives. */
    public ColorTransition sceneTime(boolean value) {
        progress.sceneTime(value);
        return this;
    }

    // -------------------------------------------------------------- control

    /** Animates from the currently-shown color to {@code target} (snaps when detached/disabled). */
    public void to(Color target) {
        Objects.requireNonNull(target, "target");
        if (target.equals(to)) {
            return; // already there, or already easing toward it
        }
        from = value();     // resume from whatever blend is on screen
        to = target;
        progress.snap(0);
        progress.to(1);
    }

    /** Jumps to {@code color} immediately, cancelling any animation. */
    public void snap(Color color) {
        from = to = Objects.requireNonNull(color, "color");
        progress.snap(1);
    }

    /** @return the current interpolated color; read this each paint */
    public Color value() {
        return from.lerp(to, progress.value());
    }

    /** @return the color it is heading toward */
    public Color target() {
        return to;
    }

    /** Whether the colour is still moving towards its target. */
    public boolean isAnimating() {
        return progress.isAnimating();
    }

    /** Advances one frame; delegates to the inner transition. Package-private for tests. */
    boolean tick(double dt) {
        return progress.tick(dt);
    }
}
