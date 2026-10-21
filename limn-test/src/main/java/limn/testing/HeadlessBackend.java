package limn.testing;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.LoopEnd;
import limn.backend.LoopLifetime;
import limn.backend.NativeWindow;
import limn.backend.QuitHandler;
import limn.backend.WindowConfig;
import limn.backend.internal.Ending;
import limn.concurrent.UiRuntime;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import limn.backend.spi.WindowPeer;
import limn.backend.spi.BackendPeer;

/**
 * A backend that mints {@link HeadlessWindow}s and remembers them, so that a dialog, a menu or a
 * popup which opens as a window of its own opens somewhere a test can read. Install it with a
 * {@link HeadlessUi}'s runtime: {@code new HeadlessBackend(ui.runtime())}.
 *
 * <p>Modality is the two bits the toolkit asks a window about and nothing more: the modal's own
 * bit and its owner's blocked bit, set and cleared exactly as a platform would.
 */
public final class HeadlessBackend implements BackendPeer {

    private final UiRuntime runtime;
    /**
     * Copied on write, so that a test iterating {@link #windows()} to frame each window keeps
     * going when a frame, which drains the UI queue, opens or closes one: the loop sees the windows
     * there were when it began, and the next pass sees the new one.
     */
    private final List<HeadlessWindow> windows = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The open windows, the front one first: a new window opens in front, a focused one moves there. */
    private final List<HeadlessWindow> frontToBack = new ArrayList<>();
    private LoopLifetime lifetime = LoopLifetime.UNTIL_LAST_WINDOW;
    private @Nullable LoopEnd ended;

    /** How a close is decided and how the application ends, as every backend decides them. */
    final Ending ending = new Ending(new Ending.Host() {
        @Override
        public List<? extends NativeWindow> topLevelFrontToBack() {
            List<HeadlessWindow> open = new ArrayList<>();
            for (HeadlessWindow window : frontToBack) {
                if (!window.isClosed() && !owned(window)) {
                    open.add(window);
                }
            }
            return open;
        }

        @Override
        public void endApplication() {
            for (HeadlessWindow window : List.copyOf(frontToBack)) {
                window.close();
            }
            ended = LoopEnd.QUIT;
        }
    });

    /**
     * The clipboard every window of this backend hands out, as a desktop has one for all of an
     * application's windows: text copied in a dialog pastes in its owner. Empty at first, and this
     * backend's own, so one test's copy never reaches another's paste.
     */
    final Clipboard clipboard = Clipboard.inMemory();

    /**
     * @param runtime the runtime the test installed, which is what {@link #uiRuntime()} answers
     */
    public HeadlessBackend(UiRuntime runtime) {
        this.runtime = runtime;
    }

    private boolean compositorShadows = true;

    /**
     * Says whether this backend's system casts a shadow around an undecorated window, which it
     * does by default. Told it does not, the toolkit makes such a window larger than its panel and
     * draws the shadow in the margin, as on a desktop whose compositor casts none: what a test of
     * a frameless dialog or popup on such a desktop sets first.
     *
     * @param casts whether the compositor casts the shadow
     */
    public void setCompositorShadowsPopups(boolean casts) {
        compositorShadows = casts;
    }

    @Override
    public boolean compositorShadowsPopups() {
        return compositorShadows;
    }

    /**
     * Opens the application's own window, the one a demo scene binds to.
     *
     * @param title  what the window is called, which is the tree's root name
     * @param width  logical width
     * @param height logical height
     * @return the window
     */
    public HeadlessWindow open(String title, int width, int height) {
        HeadlessWindow window = new HeadlessWindow(title, width, height, this);
        windows.add(window);
        frontToBack.add(0, window);
        return window;
    }

    /**
     * @return every window this backend created that has not closed, in the order it created
     *         them; a live view, whose iteration walks the windows there were when it began. A
     *         window leaves it as it closes, so that a test is not what keeps a closed window, its
     *         scene and what that scene held alive (a native popup's page looked leaked); a test
     *         that needs a window after it closed keeps its own reference, which still answers
     *         {@link HeadlessWindow#isClosed()}
     */
    public List<HeadlessWindow> windows() {
        return Collections.unmodifiableList(windows);
    }

    @Override
    public UiRuntime uiRuntime() {
        return runtime;
    }

    @Override
    public WindowPeer createWindow(WindowConfig config) {
        HeadlessWindow window = open(config.title(), config.width(), config.height());
        window.focusOnShow = config.focusOnShow();
        NativeWindow owner = config.owner();
        if (owner != null) {
            WindowPeer.of(owner).registerChildPopup(window,
                    config.isPopup() ? WindowPeer.PopupKind.TRANSIENT : WindowPeer.PopupKind.OWNED_WINDOW);
        }
        limn.backend.SizeLimits limits = config.sizeLimits();
        window.setSizeLimits(limits.minWidth(), limits.minHeight(), limits.maxWidth(), limits.maxHeight());
        return window;
    }

    /**
     * @return why the application ended: {@link LoopEnd#QUIT} once a {@link #requestQuit()} was
     *         agreed to and every window closed, and {@code null} until then. What
     *         {@code runEventLoop()} would have returned, for a backend that has no loop.
     */
    public @Nullable LoopEnd ended() {
        return ended;
    }

    /** A window closed: it leaves {@link #windows()} and the front-to-back order. */
    void closed(HeadlessWindow window) {
        windows.remove(window);
        frontToBack.remove(window);
    }

    void toFront(HeadlessWindow window) {
        if (window.isClosed()) {
            return;
        }
        frontToBack.remove(window);
        frontToBack.add(0, window);
    }

    private boolean owned(HeadlessWindow window) {
        for (HeadlessWindow other : windows) {
            if (other != window && !other.isClosed() && other.owned.contains(window)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LoopEnd runEventLoop() {
        throw new UnsupportedOperationException("a headless backend has no loop; call frame()");
    }

    @Override
    public void setLoopLifetime(LoopLifetime newLifetime) {
        lifetime = java.util.Objects.requireNonNull(newLifetime, "lifetime");
    }

    @Override
    public LoopLifetime loopLifetime() {
        return lifetime;
    }

    /** Asks the quit handler and then each window, front to back, as a desktop backend does. */
    @Override
    public void requestQuit() {
        ending.requestQuit();
    }

    @Override
    public void setQuitHandler(@Nullable QuitHandler handler) {
        ending.setQuitHandler(handler);
    }

    private limn.backend.spi.@Nullable SystemMenuBar systemMenuBar;

    /**
     * Gives this backend a menu bar outside its windows, which it has none of, for a test of what
     * a {@code MenuBar} does where the system keeps the bar: it is asked for the bar as a real
     * backend is on such a system.
     *
     * @param bar what answers for the system's bar, or {@code null} for none again
     */
    public void setSystemMenuBar(limn.backend.spi.@Nullable SystemMenuBar bar) {
        this.systemMenuBar = bar;
    }

    @Override
    public limn.backend.spi.@Nullable SystemMenuBar systemMenuBar() {
        return systemMenuBar;
    }

    @Override
    public void pushModal(NativeWindow modal, @Nullable NativeWindow parent) {
        if (modal instanceof HeadlessWindow own) {
            own.modal = true;
        }
        if (parent instanceof HeadlessWindow owner) {
            owner.modalBlocked = true;
        }
    }

    @Override
    public void popModal(NativeWindow modal) {
        if (modal instanceof HeadlessWindow own) {
            own.modal = false;
        }
        for (HeadlessWindow window : windows) {
            window.modalBlocked = false;
        }
    }

    @Override
    public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
        return () -> { };
    }

    @Override
    public void signalModalBlocked() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void close() {
    }
}
