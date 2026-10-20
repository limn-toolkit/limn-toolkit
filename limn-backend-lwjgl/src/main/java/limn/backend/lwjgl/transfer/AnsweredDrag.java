package limn.backend.lwjgl.transfer;

import org.jspecify.annotations.Nullable;

/**
 * The drag over a window of ours that the window last answered as the system asked, so that an
 * answer nobody asked for (the content scrolled under a pointer that stands still) reaches that
 * drag and no other. That answer is computed on the UI thread from the drag the scene last heard
 * of; one drag leaving and the next entering between the computation and the send would otherwise
 * hand the next drag the first one's accept and action. The UI thread hears of drags in the order
 * the backend does, so such a stale answer always arrives before the next drag's first ordinary
 * one, and is refused here. Identified by an object made once per drag; confined to the thread, or
 * the lock, that sends the answers.
 */
final class AnsweredDrag {

    private @Nullable Object drag;

    /** @param drag the drag an ordinary answer, to a move of the pointer, was just sent to */
    void answered(@Nullable Object drag) {
        this.drag = drag;
    }

    /**
     * @param current the drag over the window now, or null for none
     * @return whether it is the drag the last ordinary answer went to, so an unasked one may follow
     */
    boolean isAnswered(@Nullable Object current) {
        return current != null && current == drag;
    }

    /** The drag left or was dropped: nothing more goes to it unasked. */
    void forget() {
        drag = null;
    }
}
