/**
 * The accessible tree: what a screen reader is told about a window, as an immutable
 * whole-window snapshot the user-interface thread publishes and any thread may read.
 *
 * <p>Nothing here knows about a widget, a scene or a platform. {@link
 * limn.accessibility.Accessibility} is the builder a widget fills in;
 * {@link limn.accessibility.AccessibleTree} is what a publish produces and a bridge reads;
 * {@link limn.accessibility.AccessibleEvent} is the difference between two of them. The three
 * platform vocabularies disagree about where a behaviour lives &mdash; a pattern on Windows, a
 * state bit and an action row on Linux, an attribute on macOS &mdash; so behaviour is carried by
 * typed <em>facets</em> and every platform's view is derived from the same one.
 *
 * <p>See ADR 039.
 */
package limn.accessibility;
