/**
 * The input events the scene dispatches: {@link limn.scene.event.MouseEvent},
 * {@link limn.scene.event.KeyEvent}, {@link limn.scene.event.CharEvent} for committed
 * text and {@link limn.scene.event.PreeditEvent} for IME composition still in flight. Event
 * data is immutable; the one mutable bit is consumption: a handler that fully processed an event
 * consumes it, and bubbling stops there.
 *
 * <p>A drag has three of its own, which are answered rather than consumed:
 * {@link limn.scene.event.DragStartEvent} asks a widget whether a drag begins on it,
 * {@link limn.scene.event.DragEvent} asks one whether it would take what is over it, and
 * {@link limn.scene.event.DropEvent} hands it over.
 */
@NullMarked
package limn.scene.event;

import org.jspecify.annotations.NullMarked;
