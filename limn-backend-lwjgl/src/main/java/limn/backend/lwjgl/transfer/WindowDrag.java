package limn.backend.lwjgl.transfer;

import limn.backend.spi.DragRequest;

/** One window's part in the system's drags: it begins them, and stops taking them when it closes. */
public interface WindowDrag {

    /**
     * Begins a drag the system drives. UI thread.
     *
     * @param request what is dragged
     * @return whether it was taken; the request hears its end
     */
    boolean start(DragRequest request);

    /**
     * What the window answers the drag over it now, with no move of the pointer to ask it: the
     * content scrolled under the pointer. Told to the drag's source as the answer to a move would
     * be, while that drag is over this window and not yet dropped. UI thread.
     *
     * @param action what a widget there would carry out, or null when none takes it
     */
    default void answerChanged(limn.transfer.@org.jspecify.annotations.Nullable DropAction action) {
    }

    /** The window is closing. UI thread. */
    void close();
}
