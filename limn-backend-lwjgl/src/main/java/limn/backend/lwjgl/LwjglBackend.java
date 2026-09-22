package limn.backend.lwjgl;

import limn.backend.Backend;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.Platform;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.lang.Checks;
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
    private final ShapingRuler textRuler;
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
        selectPlatform();
        if (!glfwInit()) {
            // GLFW's own description, read back the way the window-creation failure reads it
            // (LwjglWindow): "glfwInit() failed" alone names the call and not the reason, and
            // this is the one line anybody debugging a machine that will not start ever sees.
            throw new IllegalStateException("glfwInit() failed: " + GraphicsProbe.lastError()
                    + startupAdvice());
        }
        // AFTER init, and the only error it can miss is the one above, which is read back into
        // that message instead. Installed earlier it printed the init failure a second time, as
        // a native stack trace through glfwInit — which is what --gl-info looked like it had
        // crashed with on a machine that simply has no display server. Errors from here on have
        // nowhere else to surface, so they keep the callback.
        GLFWErrorCallback.createPrint(System.err).set();
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
        if (Platform.current().isMacOs() || Platform.current().isWindows()) {
            return;
        }
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String display = System.getenv("DISPLAY");
        int chosen = platformForSession(waylandDisplay, display,
                glfwPlatformSupported(GLFW_PLATFORM_WAYLAND), glfwPlatformSupported(GLFW_PLATFORM_X11));
        if (chosen == GLFW_PLATFORM_WAYLAND) {
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_WAYLAND);
            LOG.log(Level.INFO, "windowing platform: Wayland on WAYLAND_DISPLAY={0} (this session is "
                    + "Wayland; set {1}=x11 to go through XWayland instead)",
                    waylandDisplay, PLATFORM_PROPERTY);
        } else if (chosen == GLFW_PLATFORM_X11) {
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
            LOG.log(Level.INFO, "windowing platform: X11 on DISPLAY={0}", display);
        }
    }

    /**
     * Which windowing platform a session asks for, given what its environment says.
     *
     * <p><b>A Wayland session is used as a Wayland session, and this used to be the other way
     * round.</b> The rule was "X11 whenever {@code DISPLAY} is set", chosen so that popups could be
     * given windows of their own at absolute positions, which Wayland does not allow. But XWayland
     * sets {@code DISPLAY} on every Wayland desktop there is, so that rule routed all of them
     * through XWayland — and on Fedora 44 KDE that hangs the application before it ever draws:
     * GLFW's X11 backend waits for a {@code VisibilityNotify} on the window it just mapped, XWayland
     * in rootless mode never sends one, and other events keep arriving, so the wait never sleeps. It
     * spins at 100% of a core, inside {@code glfwCreateWindow} or inside {@code glfwShowWindow} if
     * the window was created hidden — the map is what it waits on, wherever that happens.
     *
     * <p><b>What the old preference bought is already handled elsewhere.</b> ADR 028 decided, and
     * implemented, that a popup on a platform with no absolute positioning is drawn inside the
     * window that owns it, which is what {@code NativeWindow#supportsAbsolutePositioning()} is for.
     * So the trade was a graceful degradation against an application that does not start, and it
     * was made before the second half of that was known.
     *
     * <p>Pure and package-private so that the decision can be exercised without a session of any
     * kind; {@link #selectPlatform()} is the part that reads the environment.
     *
     * @param waylandDisplay  {@code WAYLAND_DISPLAY}, or null
     * @param display         {@code DISPLAY}, or null
     * @param waylandSupported whether this GLFW build has the Wayland platform
     * @param x11Supported     whether this GLFW build has the X11 platform
     * @return the GLFW platform constant, or {@code 0} to let GLFW decide for itself
     */
    static int platformForSession(String waylandDisplay, String display,
                                  boolean waylandSupported, boolean x11Supported) {
        if (waylandDisplay != null && !waylandDisplay.isBlank() && waylandSupported) {
            return GLFW_PLATFORM_WAYLAND;
        }
        // No WAYLAND_DISPLAY and a DISPLAY is a genuine X11 session, where X11 is not a fallback
        // but the right answer -- and where popups do get windows of their own.
        if (display != null && !display.isBlank() && x11Supported) {
            return GLFW_PLATFORM_X11;
        }
        return 0;
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

    private static String startupAdvice() {
        return startupAdvice(Platform.current().os(), System.getenv("DISPLAY"),
                System.getenv("WAYLAND_DISPLAY"));
    }

    /**
     * What to try after {@code glfwInit} refused, appended to GLFW's own description of what
     * happened.
     *
     * <p>GLFW says what failed and never what to do about it, and neither of the two things that
     * stop it starting is a programming error: a macOS JVM on the wrong thread, and a shell with
     * no desktop session attached to it. The second is the one that reaches a CI log or an SSH
     * transcript, and there {@code GLFW_PLATFORM_UNAVAILABLE} — "Failed to detect any supported
     * platform" — reads like a broken build rather than like a machine that simply has no display
     * server. Naming the two variables it went on turns it back into a fact about the machine.
     *
     * <p>Package-private, and taking the environment rather than reading it, for the test: this is
     * a string nothing in the program consumes, so a wrong one is not a failure anywhere — it is
     * advice that reaches a person exactly once, on the machine where it is already too late.
     *
     * @return advice beginning with its own separator, or an empty string where none applies
     */
    static String startupAdvice(Platform.Os os, String display, String waylandDisplay) {
        if (os == Platform.Os.MACOS) {
            return "; on macOS the JVM must run with -XstartOnFirstThread";
        }
        // Windows selects the one platform it has without consulting either variable, so their
        // absence there is the normal case and says nothing about why GLFW refused.
        if (os == Platform.Os.WINDOWS) {
            return "";
        }
        if (isUnset(display) && isUnset(waylandDisplay)) {
            return "; neither DISPLAY nor WAYLAND_DISPLAY is set, so this machine has no display "
                    + "server and no window can be opened on it (run it under xvfb-run to give it "
                    + "one, as scripts/screenshot.sh does)";
        }
        return "";
    }

    private static boolean isUnset(String variable) {
        return variable == null || variable.isBlank();
    }

    /**
     * The graphics report for a machine where this backend could not be constructed at all, which
     * {@link #graphicsInfo()} cannot answer because there is no instance to ask it of.
     *
     * <p>It carries the one thing that is knowable before {@code glfwInit}: the version string of
     * the GLFW that is loaded, which lists the platforms that {@code libglfw} was BUILT with. On
     * the failure this exists for that is exactly the useful contrast — a library naming Wayland
     * and X11 next to a machine running neither is a headless machine, not a broken library. The
     * platform is reported as none, because failing to select one is what happened.
     *
     * @param failure why the backend could not be constructed; becomes
     *                {@link limn.backend.GraphicsInfo#failure()}
     */
    public static limn.backend.GraphicsInfo startupFailure(String failure) {
        // glfwGetVersionString is one of the handful of GLFW calls documented to work before
        // initialization, and after a failed one. glfwGetPlatform is NOT: asking it here would
        // raise GLFW_NOT_INITIALIZED, printing a second error over the first one.
        return limn.backend.GraphicsInfo.unavailable("none selected", glfwGetVersionString(),
                failure);
    }

    /**
     * Whether the loop is parked inside GLFW's own event wait right now.
     *
     * <p>Written by the loop around its three pump calls and read by {@link #wakeLoop()}. It
     * exists for one caller shape and there is exactly one in the process: code running on the UI
     * thread, from inside a platform callback the pump itself is dispatching, which is not a GLFW
     * event and therefore produces nothing for the pump to return on. A screen reader's request is
     * that shape on macOS, and without this flag every action it performs waits for the user to
     * move the mouse.
     */
    private volatile boolean parkedInPump;

    /**
     * Wakes the sleeping event loop. Safe from any thread.
     *
     * <p>Unconditionally from a foreign thread, because that is where the race is real: the loop
     * may decide to sleep between the post and this call. From the UI thread only while the loop
     * is parked, and that read cannot race — if it says parked, the caller is re-entrant inside
     * the pump and the wake is exactly what is needed; if it says otherwise, the loop has not yet
     * re-read {@link limn.concurrent.UiRuntime#nanosUntilNextDeadline()}, which answers zero while
     * immediate work is queued, and it will see the work.
     *
     * <p>Waking on every UI-thread post regardless would make each of the toolkit's
     * self-rescheduling timers — the caret blink, the auto-repeat of a spinner and a scroll bar,
     * the media position tick, the tooltip dwell — write a native event from inside a drain the
     * loop is demonstrably already awake for, and each of those exists precisely to let the loop
     * sleep between its own beats.
     */
    void wakeLoop() {
        if (terminated) {
            return;
        }
        if (parkedInPump || !uiRuntime.isUiThread()) {
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
        java.nio.ByteBuffer pixels = OffHeap.copyOf(image.pixels());
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
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

    /** The name {@link #setApplicationName} was given, or null for the default. */
    private volatile String applicationName;

    /** The title of the first window this backend created; the application's name by default. */
    private volatile String firstWindowTitle;

    /**
     * {@inheritDoc}
     *
     * <p>Handed to every window's accessibility bridge as it opens, and to the process's AT-SPI
     * application at once when windows already have one (decision 56).
     */
    @Override
    public void setApplicationName(String name) {
        this.applicationName = java.util.Objects.requireNonNull(name, "name");
        limn.backend.lwjgl.a11y.Bridges.nameApplication(name);
    }

    /** @return what the desktop should call this application now: the given name, or the default */
    String applicationName() {
        return applicationNameOf(applicationName, firstWindowTitle);
    }

    /**
     * The rule decision 56 settled, apart from any window: the name an application was given,
     * otherwise its first window's title, otherwise nothing.
     *
     * @param given            the name set, or null
     * @param firstWindowTitle the first window's title, or null before any window
     * @return the name to publish; never null
     */
    static String applicationNameOf(String given, String firstWindowTitle) {
        if (given != null) {
            return given;
        }
        return firstWindowTitle != null ? firstWindowTitle : "";
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        uiRuntime.checkUiThread();
        ensureOpen();
        if (firstWindowTitle == null) {
            // Before the window exists, so the name its own bridge is opened with is already this.
            firstWindowTitle = config.title();
        }
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

    /** How often the loop looks again at a covered window with a frame waiting. */
    private static final long COVERED_RECHECK_NANOS = 250_000_000L;

    /** Holds the loop to the refresh rate when the vsynced swap does not block (decision 113). */
    private final FramePacer pacer = FramePacer.real(0);

    @Override
    public void runEventLoop() {
        uiRuntime.checkUiThread();
        ensureOpen();
        if (running) {
            throw new IllegalStateException("event loop is already running");
        }
        running = true;
        pacer.setPeriodNanos(primaryRefreshPeriodNanos());
        int crashStreak = 0;
        try {
            while (running) {
                boolean crashed = false;
                Throwable lastCrash = null;
                destroyClosedWindows();
                if (windows.isEmpty()) {
                    break;
                }

                // A covered window's frame waits (decision 113): it is not drawn, and it does not
                // keep the loop polling; the loop wakes at least every COVERED_RECHECK to see
                // whether the window has been uncovered, and draws the frame then.
                boolean framePending = false;
                boolean coveredPending = false;
                for (LwjglWindow window : windows) {
                    if (window.frameRequested()) {
                        if (window.isCovered()) {
                            coveredPending = true;
                        } else {
                            framePending = true;
                        }
                    }
                }
                long sleepBudgetNanos = uiRuntime.nanosUntilNextDeadline();
                if (coveredPending && (sleepBudgetNanos < 0 || sleepBudgetNanos > COVERED_RECHECK_NANOS)) {
                    sleepBudgetNanos = COVERED_RECHECK_NANOS;
                }
                try {
                    parkedInPump = true;
                    try {
                        if (framePending || sleepBudgetNanos == 0) {
                            glfwPollEvents();
                        } else if (sleepBudgetNanos < 0) {
                            glfwWaitEvents();
                        } else {
                            glfwWaitEventsTimeout(
                                    Math.min(sleepBudgetNanos / 1_000_000_000.0, MAX_WAIT_SECONDS));
                        }
                    } finally {
                        parkedInPump = false;
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
                    if (window.closeRequested() || window.isCovered()) {
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

                if (vsyncTaken) {
                    pacer.afterVsyncedPresent();
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

    /** @return the primary monitor's refresh period, or 0 when GLFW cannot say */
    private static long primaryRefreshPeriodNanos() {
        long monitor = org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor();
        if (monitor == NULL) {
            return 0;
        }
        org.lwjgl.glfw.GLFWVidMode mode = org.lwjgl.glfw.GLFW.glfwGetVideoMode(monitor);
        return mode == null || mode.refreshRate() <= 0 ? 0 : 1_000_000_000L / mode.refreshRate();
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

    /** @return whether {@code window} is one of the active modals */
    boolean isModal(LwjglWindow window) {
        return modalStack.isModal(window);
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
        textRuler.close(); // its native shaping buffers, before the store it shaped from
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
        Checks.notClosed(terminated, "the backend");
    }
}
