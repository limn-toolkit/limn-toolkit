/**
 * What the widget set's own packages share and an application does not:
 * {@link limn.components.internal.FloatingSurfaces} holds the rule every anchored popup keeps
 * before it opens a window of its own, for the popups of {@code limn.components} and of its
 * subpackages alike.
 *
 * <p><b>Not API.</b> The module does not export this package; its classes are public only so
 * that the widget set's packages can reach them, and an application should not name them. They
 * change without notice.
 */
package limn.components.internal;
