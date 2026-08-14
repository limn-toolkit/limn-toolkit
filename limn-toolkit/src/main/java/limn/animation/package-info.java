/**
 * Animated values for widget properties: {@link limn.animation.Transition} eases one
 * {@code float} toward a target, {@link limn.animation.ColorTransition} does the same for a
 * {@link limn.graphics.Color}, and {@link limn.animation.Easing} names the curve both
 * follow.
 *
 * <p>A transition drives itself off the scene's frame ticker only while moving and
 * unregisters the instant it settles, so an idle widget costs nothing. With no scene
 * (headless, or detached) it jumps straight to the target, which is the "final state, no
 * animation" a test wants. Moving the target is UI-thread-confined like any widget
 * mutation.
 */
package limn.animation;
