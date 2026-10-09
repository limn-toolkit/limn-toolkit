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

    /** The window is closing. UI thread. */
    void close();
}
