package limn.backend;

/**
 * Renders one frame of a window. Invoked on the UI thread by the backend loop
 * whenever the window is dirty ({@link NativeWindow#requestFrame()}, damage,
 * resize); Limn redraws per frame instead of tracking dirty regions, and
 * the loop sleeps when nothing is dirty.
 */
@FunctionalInterface
public interface FrameCallback {

    /**
     * @param renderer the window's renderer, valid only during this call
     * @param frame    physical framebuffer size and content scale for this frame
     */
    void onFrame(GpuRenderer renderer, FrameInfo frame);
}
