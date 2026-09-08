/**
 * What every package assumes of the language itself and the JDK does not supply:
 * {@link limn.lang.Checks} is the numeric sibling of {@code Objects.requireNonNull}, the one
 * vocabulary for "must be in range", "must be positive", "must be finite". Nothing here knows
 * a widget, a pixel or a frame; it is the layer under all of them.
 */
package limn.lang;
