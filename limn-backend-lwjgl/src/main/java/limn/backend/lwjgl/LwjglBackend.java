package limn.backend.lwjgl;

import limn.backend.Backend;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.sound.AudioClip;
import limn.sound.Sounds;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_ANY_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;
import static org.lwjgl.glfw.GLFW.GLFW_ARROW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CROSSHAIR_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_IBEAM_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_NOT_ALLOWED_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_POINTING_HAND_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_ALL_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_EW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NESW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NS_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZE_NWSE_CURSOR;
import static org.lwjgl.glfw.GLFW.glfwCreateStandardCursor;
import static org.lwjgl.glfw.GLFW.glfwDestroyCursor;
import static org.lwjgl.glfw.GLFW.glfwGetMonitors;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVersionString;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwInitHint;
import static org.lwjgl.glfw.GLFW.glfwPlatformSupported;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWaitEvents;
import static org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW + OpenGL 3.3 core implementation of {@link Backend}.
 *
 * <p>Must be constructed on the process main thread (on macOS the JVM needs
 * {@code -XstartOnFirstThread}); that thread becomes the Limn UI thread.
 * The event loop is event-driven: it sleeps in {@code glfwWaitEvents()} (or
 * {@code glfwWaitEventsTimeout()} when a delayed task is scheduled) and is
 * woken by native events or by {@code glfwPostEmptyEvent()} whenever another
 * thread posts through {@link Ui}.
 *
 * <p>Waking is not painting: the loop runs the tasks and then renders only the
 * windows something asked a frame of, so an application whose posted task
 * mutates a widget without invalidating it will not see the change.
 */
public final class LwjglBackend implements Backend {

    private static final System.Logger LOG = System.getLogger(LwjglBackend.class.getName());
    private static final double MAX_WAIT_SECONDS = 3600.0;

    private final UiRuntime uiRuntime;
    private final FontStore fontStore;
    private limn.graphics.FontCatalog fontCatalog;
    private limn.graphics.FontLoader fontLoader;
    private final limn.graphics.TextRuler textRuler;
    private final limn.graphics.ImageDecoder imageDecoder;
    private final limn.graphics.SvgRasterizer svgRasterizer = new NanoSvgRasterizer();
    // Stateless router → the current window's per-context Gl3DContext (owned by its GlCanvas).
    private final LwjglGraphics3D graphics3d = new LwjglGraphics3D();
    // Same shape for video surfaces → the current window's GlVideoContext.
    private final LwjglVideoSurfaces videoSurfaces = new LwjglVideoSurfaces();
    private final ModalStack modalStack = new ModalStack();
    private final TinyFdDialogs fileDialogs = new TinyFdDialogs(this);
    private final OpenAlAudio audio = new OpenAlAudio();
    private final AudioFileDecoder audioDecoder = new AudioFileDecoder();
    // The blocked-click alert is just a synthesized tone played through the
    // shared audio engine, the same package applications use for rich sound.
    private final AudioClip blockedClickTone = AudioClip.tone(660f, 0.09f, 0.5f);
    private final List<LwjglWindow> windows = new ArrayList<>();
    // Standard cursors are window-independent shared GLFW objects: create each
    // shape once, lazily, and reuse it across every window.
    private final Map<Cursor, Long> cursors = new EnumMap<>(Cursor.class);
    // Read once, from the first window that gets a context: the strings describe
    // the process's driver, not that window, and a window may close before
    // anybody asks. Null until then.
    private limn.backend.GraphicsInfo graphicsInfo;
    private volatile boolean running;
    private volatile boolean terminated;

    public LwjglBackend() {
        GLFWErrorCallback.createPrint(System.err).set();
        selectPlatform();
        if (!glfwInit()) {
            throw new IllegalStateException("glfwInit() failed"
                    + (isMacOs() ? ": on macOS the JVM must run with -XstartOnFirstThread" : ""));
        }
        uiRuntime = UiRuntime.create(this::wakeLoop);
        uiRuntime.bindToCurrentThread();
        Ui.install(uiRuntime);
        fontStore = new FontStore();
        // A class rather than the measuring lambda it used to be: the ruler now also shapes, and
        // carries the shape memo and the epoch a held ShapedText is invalidated against.
        textRuler = new ShapingRuler(fontStore);
        limn.graphics.TextRulers.install(textRuler);
        // Catalog is available immediately with the bundled families. Nothing
        // else loads at startup: the OS enumeration runs on the FIRST listing
        // request (or unknown-family resolve), and the heavy Noto fallbacks
        // (CJK + color emoji) on the FIRST glyph the primary face lacks; the
        // store kicks both in the background and re-installs the catalog via
        // this notifier, so Fonts listeners (relayout, pickers) observe it.
        fontCatalog = fontStore::families;
        limn.graphics.Fonts.installCatalog(fontCatalog);
        fontStore.setCatalogChangedNotifier(() -> limn.graphics.Fonts.installCatalog(fontCatalog));
        fontLoader = fontStore::loadFile;
        limn.graphics.Fonts.installLoader(fontLoader);
        imageDecoder = new StbImageDecoder();
        limn.graphics.Images.installDecoder(imageDecoder);
        limn.graphics.SvgIcon.installRasterizer(svgRasterizer);
        limn.render3d.Graphics3D.install(graphics3d);
        limn.video.VideoSurfaces.install(videoSurfaces);
        Sounds.installEngine(audio);
        Sounds.installDecoder(audioDecoder);
        // Same shape as the font store's background loads: pay the file-dialog
        // library's extract-and-link on a worker now, not on the click that
        // wants a chooser. It buys back milliseconds, not the panel's real
        // startup cost (see TinyFdDialogs.warmNative).
        TinyFdDialogs.warmNative();
        LOG.log(Level.INFO, "GLFW {0} initialized; UI thread = ''{1}''",
                glfwGetVersionString(), Thread.currentThread().getName());
    }

    /**
     * The windowing platform to ask GLFW for, which is only a question on Linux.
     *
     * <p>Values: {@code x11}, {@code wayland}, {@code any}. Unset means the default below.
     */
    public static final String PLATFORM_PROPERTY = "limn.backend.platform";

    /**
     * Asks for X11 on Linux while Wayland cannot place a window.
     *
     * <p>One {@code libglfw.so} carries both backends and chooses at run time, so a Wayland session
     * gets Wayland with nothing said, and Wayland has no absolute window positioning at all.
     * {@code glfwGetWindowPos} fails there with {@code GLFW_FEATURE_UNAVAILABLE}, on the first
     * window, before any popup exists; every dropdown, menu cascade and centred dialog is then
     * placed with a number the platform refused to give. Nothing throws. The error callback prints
     * and the application carries on drawing windows in the wrong place, which is the worst way for
     * this to be discovered.
     *
     * <p>X11 is reached through XWayland, which every mainstream compositor ships, and it restores
     * the positioning this toolkit is built on. What it costs is native Wayland integration
     * (fractional scaling, and per-surface colour management later), so this is a stopgap with a
     * real price, not a preference.
     *
     * <p><b>Only where X11 can actually be reached.</b> A misplaced popup beats a window that never
     * opens, and forcing X11 without a server is the second one: {@code glfwInit} fails outright
     * with "X11: The DISPLAY environment variable is missing" and the application does not start.
     * {@code glfwPlatformSupported} is not the question to ask; it answers whether the backend is
     * compiled into this {@code libglfw.so}, which it always is, and says nothing about whether a
     * display exists. {@code DISPLAY} is the signal, and it is the one GLFW itself goes on.
     *
     * <p>{@link #PLATFORM_PROPERTY} overrides all of it: {@code wayland} for an application that
     * wants native Wayland and can live without positioned popups, {@code any} to hand the choice
     * back to GLFW. Nothing here runs off Linux: macOS and Windows have one platform each.
     */
    /**
     * The GLFW platform a {@link #PLATFORM_PROPERTY} value names, or {@code 0} for anything else,
     * including empty, which is the ordinary "not set" and means the default applies.
     *
     * <p>Package-private for the test. A value nobody recognises must not become a platform: a typo
     * that silently selected Wayland would reintroduce exactly the defect this method exists to
     * avoid, and it would do it on the machine of whoever typed it.
     */
    static int platformFor(String requested) {
        return switch (requested) {
            case "x11" -> GLFW_PLATFORM_X11;
            case "wayland" -> GLFW_PLATFORM_WAYLAND;
            case "any" -> GLFW_ANY_PLATFORM;
            default -> 0;
        };
    }

    private static void selectPlatform() {
        String requested = System.getProperty(PLATFORM_PROPERTY, "").trim().toLowerCase(
                java.util.Locale.ROOT);
        int platform = platformFor(requested);
        if (platform != 0) {
            glfwInitHint(GLFW_PLATFORM, platform);
            LOG.log(Level.INFO, "windowing platform: {0} requested through {1}",
                    requested, PLATFORM_PROPERTY);
            return;
        }
        if (!requested.isEmpty()) {
            LOG.log(Level.WARNING, "{0}=''{1}'' is not x11, wayland or any; ignoring it",
                    PLATFORM_PROPERTY, requested);
        }
        if (isMacOs() || System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank() && glfwPlatformSupported(GLFW_PLATFORM_X11)) {
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
            LOG.log(Level.INFO, "windowing platform: X11 on DISPLAY={0} (Wayland cannot position "
                    + "windows; set {1}=wayland to override)", display, PLATFORM_PROPERTY);
        }
    }

    /**
     * Set this to {@code true} to make every window report that it cannot be positioned, which
     * puts menus and dropdowns into the owner scene on a platform that would otherwise place
     * them in windows.
     *
     * <p>It exists so the fallback can be <em>looked at</em>. Wayland is the only platform that
     * really needs it, and a presentation that can only be seen on one desktop is one that gets
     * broken on the other three and noticed a release later. With this the demo renders it
     * anywhere, including into a {@code --screenshot}. It changes no behaviour the application
     * chooses and none that a user can reach.
     */
    public static final String FORCE_IN_SCENE_POPUPS_PROPERTY = "limn.backend.inScenePopups";

    /**
     * Whether the platform GLFW actually selected can place and report window positions:
     * everything except Wayland, where the protocol has no such concept and GLFW answers
     * {@code GLFW_FEATURE_UNAVAILABLE} for the whole family of position calls.
     *
     * <p>Read after {@code glfwInit}, from GLFW rather than from what {@link #selectPlatform()}
     * asked for: the hint is a request. A machine with no X server, or one where the property
     * named {@code any}, ends up on Wayland regardless of what was preferred, and a window that
     * answered from the request would promise a position it cannot deliver.
     */
    static boolean canPositionWindows() {
        return !Boolean.getBoolean(FORCE_IN_SCENE_POPUPS_PROPERTY)
                && glfwGetPlatform() != GLFW_PLATFORM_WAYLAND;
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Wakes the sleeping event loop. Safe from any thread. */
    void wakeLoop() {
        if (!terminated) {
            glfwPostEmptyEvent();
        }
    }

    @Override
    public UiRuntime uiRuntime() {
        return uiRuntime;
    }

    @Override
    public limn.backend.FileDialogs fileDialogs() {
        return fileDialogs;
    }

    /** Shared CPU font registry (glyph textures stay per-window). */
    FontStore fontStore() {
        return fontStore;
    }

    /**
     * The shared GLFW handle for a standard {@code cursor} shape, created lazily
     * and cached (cursors are not window-specific). Returns {@code NULL} when the
     * platform has no such shape; the window then falls back to the arrow. UI
     * thread only (GLFW cursor creation is main-thread).
     */
    long cursorHandle(Cursor cursor) {
        return cursors.computeIfAbsent(cursor, c -> glfwCreateStandardCursor(glfwShape(c)));
    }

    // Custom image cursors, shared across windows like the standard shapes but
    // bounded: distinct ImageCursors an app can reasonably cycle through is
    // small; past the cap the least-recently-used native cursor is destroyed
    // (GLFW reverts any window still showing it to the default arrow; churning
    // more than MAX_IMAGE_CURSORS distinct cursors is a caller bug).
    private static final int MAX_IMAGE_CURSORS = 16;
    private final java.util.LinkedHashMap<limn.backend.ImageCursor, Long> imageCursors =
            new java.util.LinkedHashMap<>(MAX_IMAGE_CURSORS, 0.75f, true /* access order */);

    /**
     * The native cursor for {@code cursor}, created from its RGBA pixels on
     * first use and cached. UI thread only.
     */
    long imageCursorHandle(limn.backend.ImageCursor cursor) {
        Long cached = imageCursors.get(cursor);
        if (cached != null) {
            return cached;
        }
        limn.graphics.Image image = cursor.image();
        long handle;
        // Pixel data goes on the heap, not the MemoryStack: cursor images are
        // usually tiny, but nothing bounds them. GLFW copies before returning.
        java.nio.ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(image.pixels().length);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            pixels.put(image.pixels()).flip();
            org.lwjgl.glfw.GLFWImage glfwImage = org.lwjgl.glfw.GLFWImage.malloc(stack);
            glfwImage.set(image.width(), image.height(), pixels);
            handle = org.lwjgl.glfw.GLFW.glfwCreateCursor(
                    glfwImage, cursor.hotspotX(), cursor.hotspotY());
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
        }
        if (imageCursors.size() >= MAX_IMAGE_CURSORS) {
            // Evict the least-recently-used cursor NOT actively shown by any
            // window: destroying an in-use cursor reverts that window to the
            // arrow and strands its recorded state. With every entry in use
            // (pathological), skip eviction and let the cache grow.
            var it = imageCursors.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (!imageCursorInUse(entry.getKey())) {
                    glfwDestroyCursor(entry.getValue());
                    it.remove();
                    break;
                }
            }
        }
        imageCursors.put(cursor, handle);
        return handle;
    }

    private boolean imageCursorInUse(limn.backend.ImageCursor cursor) {
        for (LwjglWindow window : windows) {
            if (cursor.equals(window.activeImageCursor())) {
                return true;
            }
        }
        return false;
    }

    private static int glfwShape(Cursor cursor) {
        return switch (cursor) {
            case POINTER -> GLFW_POINTING_HAND_CURSOR;
            case TEXT -> GLFW_IBEAM_CURSOR;
            case CROSSHAIR -> GLFW_CROSSHAIR_CURSOR;
            case RESIZE_EW -> GLFW_RESIZE_EW_CURSOR;
            case RESIZE_NS -> GLFW_RESIZE_NS_CURSOR;
            case RESIZE_NESW -> GLFW_RESIZE_NESW_CURSOR;
            case RESIZE_NWSE -> GLFW_RESIZE_NWSE_CURSOR;
            case MOVE -> GLFW_RESIZE_ALL_CURSOR;
            case NOT_ALLOWED -> GLFW_NOT_ALLOWED_CURSOR;
            case DEFAULT -> GLFW_ARROW_CURSOR;
        };
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        uiRuntime.checkUiThread();
        ensureOpen();
        LwjglWindow window = new LwjglWindow(this, config);
        windows.add(window);
        if (graphicsInfo == null) {
            graphicsInfo = window.readGraphicsInfo();
        }
        return window;
    }

    @Override
    public limn.backend.GraphicsInfo graphicsInfo() {
        uiRuntime.checkUiThread();
        return graphicsInfo != null ? graphicsInfo
                : limn.backend.GraphicsInfo.unavailable(GraphicsProbe.platformName(),
                        glfwGetVersionString(),
                        "no window has been created yet, and the strings live on a context");
    }

    // Consecutive loop iterations that contained a crash before the loop gives
    // up and rethrows: a backstop against application code that fails
    // deterministically every iteration (Scene has its own, earlier recovery;
    // this net exists for raw frame callbacks and callback-resident code).
    private static final int CRASH_STREAK_LIMIT = 100;

    @Override
    public void runEventLoop() {
        uiRuntime.checkUiThread();
        ensureOpen();
        if (running) {
            throw new IllegalStateException("event loop is already running");
        }
        running = true;
        int crashStreak = 0;
        try {
            while (running) {
                boolean crashed = false;
                Throwable lastCrash = null;
                destroyClosedWindows();
                if (windows.isEmpty()) {
                    break;
                }

                boolean framePending = false;
                for (LwjglWindow window : windows) {
                    framePending |= window.frameRequested();
                }
                long sleepBudgetNanos = uiRuntime.nanosUntilNextDeadline();
                try {
                    if (framePending || sleepBudgetNanos == 0) {
                        glfwPollEvents();
                    } else if (sleepBudgetNanos < 0) {
                        glfwWaitEvents();
                    } else {
                        glfwWaitEventsTimeout(Math.min(sleepBudgetNanos / 1_000_000_000.0, MAX_WAIT_SECONDS));
                    }
                } catch (Crashes.ShutdownRequested shutdown) {
                    throw shutdown;
                } catch (Throwable error) {
                    // Application code resident in GLFW callbacks (live-resize
                    // repaint, close veto, content-scale listener) surfaces its
                    // exception when the native poll returns, the only place
                    // it can be caught.
                    crashed = true;
                    lastCrash = error;
                    if (!Crashes.dispatch(CrashPhase.EVENT_POLL, error)) {
                        throw Crashes.shutdownRequested(error);
                    }
                    requestFrameAll(); // callbacks may have half-applied state
                }

                // Deterministic frame order: user input first, then posted tasks
                // (animation/layout/paint happen inside the frame render).
                // Copy: an input handler may legally create/close windows.
                for (LwjglWindow window : List.copyOf(windows)) {
                    try {
                        window.notifyInputBatchEnd();
                    } catch (Crashes.ShutdownRequested shutdown) {
                        throw shutdown;
                    } catch (Throwable error) {
                        crashed = true;
                        lastCrash = error;
                        if (!Crashes.dispatch(CrashPhase.INPUT, error)) {
                            throw Crashes.shutdownRequested(error);
                        }
                    }
                }

                // Deliberately NOT followed by a blanket repaint: a drain buys
                // no frame. Every posted task invalidates what it mutates (the
                // contract on Ui.post), so a window nothing touched stays idle,
                // which is what makes a timer that polls a few times a second
                // free, and what stops a paint that arms a poll from sustaining
                // its own frame rate forever. A task that THREW is the
                // exception: it applied part of its mutation and invalidated
                // none of it, extent unknown, so settle every window.
                uiRuntime.drain(this::requestFrameAll);

                // ONE window per iteration blocks on vblank (the pacer); the rest
                // swap unthrottled into the same vblank; the compositor prevents
                // tearing. All-windows vsync would serialize N animated windows
                // down to refresh/N fps each (six bouncing gadgets = 10 fps).
                boolean vsyncTaken = false;
                for (LwjglWindow window : List.copyOf(windows)) {
                    if (window.closeRequested()) {
                        continue;
                    }
                    try {
                        if (window.frameRequested()) {
                            vsyncTaken |= window.renderNow(!vsyncTaken);
                        }
                        // Once a render burst settles, present the same frame again so
                        // both double buffers match; a sparse present otherwise lets the
                        // compositor surface a one-frame-old buffer (thumb ghost/flicker).
                        vsyncTaken |= window.syncBuffers(!vsyncTaken);
                    } catch (Crashes.ShutdownRequested shutdown) {
                        throw shutdown;
                    } catch (Throwable error) {
                        // Scene contains its own frame crashes; this net catches raw
                        // frame callbacks; keep the OTHER windows rendering.
                        crashed = true;
                        lastCrash = error;
                        if (!Crashes.dispatch(CrashPhase.FRAME, error)) {
                            throw Crashes.shutdownRequested(error);
                        }
                        window.requestFrame();
                    }
                }

                if (!crashed) {
                    crashStreak = 0;
                } else if (++crashStreak >= CRASH_STREAK_LIMIT) {
                    LOG.log(Level.ERROR, "event loop crashed {0} consecutive iterations; giving up", crashStreak);
                    throw new IllegalStateException(
                            "event loop crashed " + crashStreak + " consecutive iterations", lastCrash);
                }
            }
        } finally {
            running = false;
        }
    }

    /**
     * Draws and presents every window's pending frame now, then re-presents each
     * so both of its buffers hold it. UI thread only.
     *
     * <p>Called immediately before a native call that parks the UI thread for an
     * unbounded time: a system file chooser, which returns only when the user
     * picks or cancels. While that call is on the stack no GLFW callback fires,
     * so the frame the loop would have drawn on this iteration never happens and
     * the compositor keeps showing whatever was swapped last. Presenting here is
     * what decides <em>which</em> picture the user stares at: an event handler
     * dispatches the button's release and its click in the same batch, so
     * without this the frozen window still shows the button pressed. Both
     * buffers are filled because a long freeze is exactly when a compositor
     * surfacing the one-frame-old back buffer becomes visible.
     *
     * <p>No window takes vsync: the pacing the loop grants to one window per
     * iteration exists to throttle a render burst, and there is no next
     * iteration to throttle; blocking on a vblank here would only delay the
     * chooser.
     */
    void presentBeforeBlocking() {
        uiRuntime.checkUiThread();
        for (LwjglWindow window : List.copyOf(windows)) {
            if (window.closeRequested()) {
                continue;
            }
            try {
                if (window.frameRequested()) {
                    window.renderNow(false);
                }
                window.syncBuffers(false);
            } catch (Crashes.ShutdownRequested shutdown) {
                throw shutdown;
            } catch (Throwable error) {
                // A paint that throws must not swallow the dialog the caller
                // asked for: contain it exactly as the loop's frame phase does,
                // leave the frame pending, and let the chooser open anyway.
                if (!Crashes.dispatch(CrashPhase.FRAME, error)) {
                    throw Crashes.shutdownRequested(error);
                }
                window.requestFrame();
            }
        }
    }

    private void destroyClosedWindows() {
        // Two phases: destroy() runs arbitrary app callbacks (a dialog result
        // completing, a scene teardown) that may create or close windows;
        // mutating 'windows' inside a live iterator is a CME. Snapshot the
        // dying windows, drop them from the list FIRST (callbacks see a
        // consistent list), then destroy. Windows closed by those callbacks
        // are flagged and picked up by the next sweep.
        List<LwjglWindow> closing = null;
        for (int i = 0; i < windows.size(); i++) {
            LwjglWindow window = windows.get(i);
            if (window.closeRequested()) {
                if (closing == null) {
                    closing = new ArrayList<>();
                }
                closing.add(window);
            }
        }
        if (closing == null) {
            return;
        }
        windows.removeAll(closing);
        boolean poppedModal = false;
        for (LwjglWindow window : closing) {
            // A modal destroyed without an explicit popModal (e.g. its
            // parent closed) must still leave the modal stack; otherwise
            // it would keep blocking input forever.
            if (modalStack.isModal(window)) {
                modalStack.pop(window);
                poppedModal = true;
            }
            // In-scene modals this window hosted have no window of their own
            // to close; drop them so they stop blocking siblings forever.
            poppedModal |= modalStack.removeOwnedBy(window);
            window.destroy();
        }
        if (poppedModal) {
            focusTopModal();
        }
    }

    @Override
    public void pushModal(NativeWindow modal, NativeWindow parent) {
        uiRuntime.checkUiThread();
        modalStack.push(modal, parent);
        focusTopModal();
        requestFrameAll(); // repaint every window so blocked ones dim in
    }

    @Override
    public void popModal(NativeWindow modal) {
        uiRuntime.checkUiThread();
        modalStack.pop(modal);
        focusTopModal();
        requestFrameAll(); // repaint so released windows undim
    }

    @Override
    public Backend.SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
        uiRuntime.checkUiThread();
        LwjglWindow ownerWin = (LwjglWindow) owner;
        // The token IS the modal identity; the owner is exempted so it stays
        // interactive (its overlay blocks its own content), while its siblings,
        // owned popups (window scope) or every window (toolkit scope), lock.
        SceneModalToken token = new SceneModalToken(ownerWin);
        modalStack.push(token, toolkitScope ? null : ownerWin, ownerWin);
        focusTopModal();
        requestFrameAll();
        return () -> {
            uiRuntime.checkUiThread();
            modalStack.pop(token);
            focusTopModal();
            requestFrameAll();
        };
    }

    private void requestFrameAll() {
        for (LwjglWindow window : windows) {
            window.requestFrame();
        }
    }

    /** @return whether {@code window}'s input is currently blocked by a modal */
    boolean isInputBlocked(LwjglWindow window) {
        if (modalStack.isEmpty()) {
            return false;
        }
        return modalStack.isBlocked(window,
                (parent, w) -> parent == w
                        || (parent instanceof LwjglWindow p && p.ownsPopup((LwjglWindow) w)),
                (host, w) -> host == w
                        || (host instanceof LwjglWindow h && h.ownsTransientPopup((LwjglWindow) w)));
    }

    @Override
    public void signalModalBlocked() {
        uiRuntime.checkUiThread();
        onBlockedClick();
    }

    /**
     * The OS activated a modal-blocked window (focus-stealing click/raise).
     * Immediately hand activation back to the modal that blocks it: the window
     * that stole focus must never keep it, so the modal in front stays put
     * instead of flickering. No beep/attention here (that's for a real click).
     */
    void onBlockedWindowActivated(LwjglWindow window) {
        LwjglWindow top = focusTargetOf(modalStack.topModal());
        if (top != null && top != window) {
            top.focusNative();
        }
    }

    /** A click landed on a modal-locked window: raise the modal, attention, beep. */
    void onBlockedClick() {
        LwjglWindow top = focusTargetOf(modalStack.topModal());
        if (top != null) {
            top.focusNative();
            top.requestAttention();
        }
        audio.play(blockedClickTone, 0.7f, false);
    }

    /**
     * Hands focus to the top modal, and keeps the always-on-top band clear enough
     * for it to actually reach the front.
     *
     * <p>Focusing raises a window over ordinary ones but never over a floating one,
     * so when the new top modal's surface is an ordinary window (an in-scene
     * dialog's host), every floating modal has to step down first. When the new top
     * is itself floating, nothing steps down: floating windows order among
     * themselves, so focusing is enough, and the modals below stay visible above the
     * window they are blocking instead of disappearing behind it.
     */
    private void focusTopModal() {
        Object top = modalStack.topModal();
        boolean topFloats = top instanceof LwjglWindow window && window.floatsByConfig();
        for (LwjglWindow window : windows) {
            if (modalStack.isModal(window)) {
                window.setFloating(window == top || topFloats);
            }
        }
        LwjglWindow target = focusTargetOf(top);
        if (target != null) {
            target.focusNative();
        }
    }

    /** The window to focus for a modal: itself for a native window, the host for a scene modal. */
    private static LwjglWindow focusTargetOf(Object modal) {
        if (modal instanceof LwjglWindow window) {
            return window;
        }
        if (modal instanceof SceneModalToken token) {
            return token.owner();
        }
        return null;
    }

    /** Identity of an in-scene modal on the modal stack; carries its host window. */
    private record SceneModalToken(LwjglWindow owner) {
    }

    @Override
    public limn.backend.RenderStats renderStats() {
        uiRuntime.checkUiThread();
        limn.backend.RenderStats total = limn.backend.RenderStats.EMPTY;
        for (LwjglWindow window : windows) {
            total = total.plus(window.renderStats());
        }
        return total;
    }

    @Override
    public limn.render3d.Render3DStats render3DStats() {
        uiRuntime.checkUiThread();
        limn.render3d.Render3DStats total = limn.render3d.Render3DStats.EMPTY;
        for (LwjglWindow window : windows) {
            total = total.plus(window.render3DStats());
        }
        return total;
    }

    @Override
    public java.util.List<limn.backend.Display> displays() {
        uiRuntime.checkUiThread();
        java.util.List<limn.backend.Display> out = new ArrayList<>();
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                out.add(new LwjglDisplay(monitors.get(i), i, this));
            }
        }
        return out;
    }

    @Override
    public limn.backend.Display primaryDisplay() {
        uiRuntime.checkUiThread();
        long primary = glfwGetPrimaryMonitor();
        return primary == NULL ? null : displayFor(primary);
    }

    /** Wraps a GLFW monitor handle, resolving its index for a stable {@link limn.backend.Display#id()}. */
    LwjglDisplay displayFor(long monitor) {
        int index = 0;
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                if (monitors.get(i) == monitor) {
                    index = i;
                    break;
                }
            }
        }
        return new LwjglDisplay(monitor, index, this);
    }

    @Override
    public void stop() {
        running = false;
        wakeLoop();
    }

    @Override
    public void close() {
        if (terminated) {
            return;
        }
        uiRuntime.checkUiThread();
        running = false;
        for (LwjglWindow window : windows) {
            window.destroy();
        }
        windows.clear();
        terminated = true;
        Ui.uninstall(uiRuntime);
        limn.graphics.TextRulers.uninstall(textRuler);
        limn.graphics.Fonts.uninstallCatalog(fontCatalog);
        limn.graphics.Fonts.uninstallLoader(fontLoader);
        limn.graphics.Images.uninstallDecoder(imageDecoder);
        limn.graphics.SvgIcon.uninstallRasterizer(svgRasterizer);
        // Each window's Gl3DContext was disposed by its GlCanvas in destroy() above
        // (with its context current); the router is stateless, so just drop it.
        limn.render3d.Graphics3D.uninstall(graphics3d);
        // Each window's GlVideoContext was disposed by its GlCanvas above, with
        // that window's context current; this router holds nothing of its own.
        limn.video.VideoSurfaces.uninstall(videoSurfaces);
        Sounds.uninstallEngine(audio);
        Sounds.uninstallDecoder(audioDecoder);
        audio.close();
        uiRuntime.close();
        fontStore.close();
        for (Long handle : cursors.values()) {
            glfwDestroyCursor(handle); // glfwDestroyCursor(NULL) is a documented no-op
        }
        cursors.clear();
        for (Long handle : imageCursors.values()) {
            glfwDestroyCursor(handle);
        }
        imageCursors.clear();
        glfwTerminate();
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }

    private void ensureOpen() {
        if (terminated) {
            throw new IllegalStateException("backend is closed");
        }
    }
}
