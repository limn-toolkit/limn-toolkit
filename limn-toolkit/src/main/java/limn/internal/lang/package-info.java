/**
 * What every package assumes of the language itself and the JDK does not supply:
 * {@link limn.internal.lang.Checks} is the numeric sibling of {@code Objects.requireNonNull}, the one
 * vocabulary for "must be in range", "must be positive", "must be finite". Nothing here knows
 * a widget, a pixel or a frame; it is the layer under all of them.
 *
 * <p><b>Not API.</b> The module exports this package only to the Limn modules that share
 * it, and an application should not name it; it changes without notice.
 */
package limn.internal.lang;
