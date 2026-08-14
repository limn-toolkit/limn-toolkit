/**
 * The input events the scene dispatches: {@link limn.scene.event.MouseEvent},
 * {@link limn.scene.event.KeyEvent}, {@link limn.scene.event.CharEvent} for committed
 * text, {@link limn.scene.event.PreeditEvent} for IME composition still in flight, and
 * {@link limn.scene.event.FileDropEvent}. Event data is immutable; the one mutable bit is
 * consumption: a handler that fully processed an event consumes it, and bubbling stops
 * there.
 */
package limn.scene.event;
