package limn.backend;

import limn.backend.spi.BackendPeer;
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
 *
 * <p>This is the application's view of a backend, which the 1.0 contract freezes. What the toolkit
 * asks of it below that (the modal stack) is {@link BackendPeer}'s, in the incubating layer; every
 * backend is one, which is why this interface is sealed.
 */
public sealed interface Backend extends AutoCloseable permits BackendPeer {

    /** @return the concurrency runtime owned by this backend */
    UiRuntime uiRuntime();

    /**
     * Names this application where the desktop's accessibility tree lists applications by name.
     *
     * <p>On Linux, AT-SPI2 shows one application object per process with every window beneath it,
     * and a screen reader says that name when the user moves into the application; left unset, a
     * backend uses the title of the first window it created, which is right for a one-window
     * program and wrong for one whose first window is a splash or a login. Windows names an
     * application from the process itself and reads nothing here. Call it before the first window
     * for the name to be there from the start; a later call renames the application for the next
     * client that asks.
     *
     * <p>On macOS the name is also what the toolkit writes in the items of the application's menu
     * (About, Hide, Quit). It is <b>not</b> what the system writes in bold at the head of its menu
     * bar: the system reads that once, as the windowing library starts, from the application's
     * package, and for an application that is not packaged from the name the backend was made
     * with. A backend that takes a name at its construction says so there.
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
     * Whether the system's compositor casts a shadow around an undecorated window of this
     * backend's (a menu, the list of a combo box, a dialog with no frame). Where it does the toolkit draws none of its own, or there
     * would be two; where it does not, the toolkit makes such a window larger than its panel and
     * draws the shadow in the margin.
     *
     * @return {@code true} (the default) where the compositor casts it, and for a backend with
     *         no desktop under it, which then draws nothing extra
     */
    default boolean compositorShadowsPopups() {
        return true;
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

    /** Asks the loop to exit. Safe to call from any thread. */
    void stop();

    /** Destroys all windows and releases native resources. UI thread only. */
    @Override
    void close();
}
