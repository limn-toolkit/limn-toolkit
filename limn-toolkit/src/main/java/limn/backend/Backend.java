package limn.backend;

import limn.concurrent.UiRuntime;
import org.jspecify.annotations.Nullable;

/**
 * Service-provider interface for a native platform backend (windows, input,
 * GPU rendering, clipboard). The only shipped implementation is
 * {@code limn-backend-lwjgl} (GLFW + OpenGL 3.3 core); the abstraction
 * exists so ANGLE/Vulkan (or SDL) backends can replace it without touching
 * any module above.
 *
 * <p>Lifecycle: construct on the process main thread (which becomes the UI
 * thread), create windows, then {@link #runEventLoop()} until the last window
 * closes, an agreed {@link #requestQuit()} or {@link #stop()}, as the
 * {@link #setLoopLifetime lifetime} says. The loop is event-driven: it sleeps in
 * the native event wait and is woken by input, window damage or
 * {@link limn.concurrent.Ui#post}.
 */
public interface Backend extends AutoCloseable {

    /** @return the concurrency runtime owned by this backend */
    UiRuntime uiRuntime();

    /**
     * Names this application where the desktop's accessibility tree lists applications by name.
     *
     * <p>On Linux, AT-SPI2 shows one application object per process with every window beneath it,
     * and a screen reader says that name when the user moves into the application; left unset, a
     * backend uses the title of the first window it created, which is right for a one-window
     * program and wrong for one whose first window is a splash or a login. Windows and macOS name an
     * application from the process itself and read nothing here. Call it before the first window
     * for the name to be there from the start; a later call renames the application for the next
     * client that asks.
     *
     * @param name what the desktop should call this application; never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    default void setApplicationName(String name) {
        java.util.Objects.requireNonNull(name, "name");
    }

    /** Creates (but does not necessarily show) a native window. UI thread only. */
    NativeWindow createWindow(WindowConfig config);

    /**
     * Runs the event/render loop on the calling (UI) thread. Returns when the
     * last window closes (under {@link LoopLifetime#UNTIL_LAST_WINDOW}), when a
     * {@link #requestQuit()} was agreed to and every window has closed, or when
     * {@link #stop()} is invoked.
     *
     * @return why it returned
     */
    LoopEnd runEventLoop();

    /**
     * Sets how long {@link #runEventLoop()} runs: until the last window closes (the default) or
     * until the application ends it, which lets an application live with no window open. UI thread
     * only.
     *
     * @param lifetime the lifetime; never {@code null}
     * @throws NullPointerException if {@code lifetime} is {@code null}
     */
    void setLoopLifetime(LoopLifetime lifetime);

    /** @return how long {@link #runEventLoop()} runs; {@link LoopLifetime#UNTIL_LAST_WINDOW} unless set */
    LoopLifetime loopLifetime();

    /**
     * Asks to end the application. The {@link #setQuitHandler quit handler} is asked first, then
     * every open top-level window's {@link NativeWindow#setCloseRequestHandler handler}, front to
     * back (the window used last first), one at a time; a window with no handler agrees. A refusal
     * anywhere ends the request and every window stays open. When all have agreed, every window
     * closes, owned ones included, and {@link #runEventLoop()} returns {@link LoopEnd#QUIT}.
     *
     * <p>A request made while one is being answered joins it. On macOS the system's Quit command
     * for the application (Cmd-Q, the application menu, the Dock) makes this request. UI thread
     * only.
     */
    void requestQuit();

    /**
     * Sets the handler {@link #requestQuit()} asks before any window, for what belongs to no window.
     * {@code null} clears. UI thread only.
     *
     * @param handler the handler, or {@code null} for none
     */
    void setQuitHandler(@Nullable QuitHandler handler);

    /**
     * Registers {@code modal} as an active modal window and pushes it on the
     * modal stack. Only the topmost modal receives input; every window it
     * locks ignores input until it closes.
     *
     * <p>Modality scope: a non-null {@code parent} makes it <em>window-modal</em>,
     * so it locks only {@code parent} (and {@code parent}'s owned popups),
     * leaving unrelated windows interactive. A {@code null} parent makes it
     * <em>toolkit-modal</em>, so it locks every other window. Modals stack: with
     * several open, only the top one is usable and the non-modal windows are
     * released only after the whole modal stack closes. UI thread only.
     */
    void pushModal(NativeWindow modal, @Nullable NativeWindow parent);

    /** Removes {@code modal} from the modal stack, releasing what it locked. UI thread only. */
    void popModal(NativeWindow modal);

    /**
     * Registers an <em>in-scene</em> (overlay) modal hosted by {@code owner}.
     * Unlike {@link #pushModal}, there is no separate modal window: the overlay
     * blocks {@code owner}'s own content, so {@code owner} stays interactive,
     * while this call blocks the same <em>sibling</em> windows a native modal
     * would. {@code toolkitScope} {@code true} locks every other window;
     * {@code false} locks only {@code owner}'s owned popups. Release the
     * returned handle when the overlay closes. UI thread only.
     */
    SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope);

    /** Handle to an in-scene modal (see {@link #pushSceneModal}); {@link #release} it on close. */
    interface SceneModalHandle {
        /** Releases the in-scene modal, unblocking what it locked. UI thread only. */
        void release();
    }

    /**
     * Gives the modal-blocked feedback (the alert sound plus a re-raise of the
     * top modal) for an interaction the active modal ignores. The backend
     * already does this for clicks on blocked native windows; call it for an
     * in-scene modal's scrim so internal and native modals feel identical.
     * UI thread only.
     */
    void signalModalBlocked();

    /**
     * @return aggregate GPU resource usage across all windows (texture count +
     *         estimated bytes), for diagnostics. Default: {@link RenderStats#EMPTY}.
     *         UI thread only.
     */
    default RenderStats renderStats() {
        return RenderStats.EMPTY;
    }

    /**
     * @return aggregate 3D-subsystem GPU usage across all windows (meshes,
     *         textures, render targets, bytes, and last-frame draw calls/triangles),
     *         for diagnostics. Default: {@link limn.render3d.Render3DStats#EMPTY}.
     *         UI thread only.
     */
    default limn.render3d.Render3DStats render3DStats() {
        return limn.render3d.Render3DStats.EMPTY;
    }

    /**
     * @return which graphics context this backend obtained and which optional
     *         capabilities it has: the report a bug about a machine that will
     *         not start, or that renders slowly, has to carry. The strings live
     *         on the context, so this is only answerable once
     *         {@link #createWindow} has succeeded at least once; before that,
     *         and on a machine where it never does, the result carries the
     *         windowing platform and a {@link GraphicsInfo#failure()} instead.
     *         Default: {@link GraphicsInfo#NONE}. UI thread only.
     */
    default GraphicsInfo graphicsInfo() {
        return GraphicsInfo.NONE;
    }

    /**
     * @return every connected {@link Display}, in the platform's order (index 0
     *         is usually the primary). Empty when headless/embedded. UI thread only.
     */
    default java.util.List<Display> displays() {
        return java.util.List.of();
    }

    /**
     * @return the primary {@link Display}, or {@code null} when headless.
     *         Default: the display that reports {@link Display#isPrimary()}, else
     *         the first of {@link #displays()}. UI thread only.
     */
    default @Nullable Display primaryDisplay() {
        java.util.List<Display> all = displays();
        for (Display display : all) {
            if (display.isPrimary()) {
                return display;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * @return the platform's native file/folder dialogs. Headless/embedded
     *         backends return {@link FileDialogs#NONE} (every dialog resolves
     *         empty, as if cancelled). UI thread only.
     */
    default FileDialogs fileDialogs() {
        return FileDialogs.NONE;
    }

    /** Asks the loop to exit. Safe to call from any thread. */
    void stop();

    /** Destroys all windows and releases native resources. UI thread only. */
    @Override
    void close();
}
