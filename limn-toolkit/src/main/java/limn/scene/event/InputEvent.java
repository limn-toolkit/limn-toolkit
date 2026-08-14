package limn.scene.event;

/**
 * Base of all dispatched input events. Event data is immutable; the single
 * mutable bit is consumption: a handler that fully processed the event calls
 * {@link #consume()} and bubbling stops.
 */
public abstract sealed class InputEvent permits MouseEvent, KeyEvent, CharEvent, PreeditEvent, FileDropEvent {

    private boolean consumed;

    /** Marks the event handled, stopping it from bubbling further up the tree. */
    public final void consume() {
        consumed = true;
    }

    /** Whether some handler has already claimed this event. */
    public final boolean isConsumed() {
        return consumed;
    }
}
