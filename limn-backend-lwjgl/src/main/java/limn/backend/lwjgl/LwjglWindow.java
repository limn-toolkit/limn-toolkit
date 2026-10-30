package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;

import limn.backend.Cursor;
import limn.backend.spi.FrameCallback;
import limn.backend.spi.FrameInfo;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.spi.WindowInput;
import limn.internal.lang.Checks;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWPreeditCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import limn.backend.Platform;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY;
import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_NATIVE_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API;
import static org.lwjgl.glfw.GLFW.GLFW_EGL_CONTEXT_API;
import static org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_CAPTURED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_DECORATED;
import static org.lwjgl.glfw.GLFW.GLFW_DONT_CARE;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FLOATING;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW;
import static org.lwjgl.glfw.GLFW.GLFW_FORMAT_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_API;
import static org.lwjgl.glfw.GLFW.GLFW_IME;
import static org.lwjgl.glfw.GLFW.GLFW_MAXIMIZED;
import static org.lwjgl.glfw.GLFW.GLFW_RAW_MOUSE_MOTION;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_SCALE_TO_MONITOR;
import static org.lwjgl.glfw.GLFW.GLFW_TRANSPARENT_FRAMEBUFFER;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwFocusWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMonitors;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwSetWindowMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwHideWindow;
import static org.lwjgl.glfw.GLFW.glfwRequestWindowAttention;
import static org.lwjgl.glfw.GLFW.glfwRestoreWindow;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursor;
import static org.lwjgl.glfw.GLFW.glfwSetCursorEnterCallback;
import static org.lwjgl.glfw.GLFW.glfwRawMouseMotionSupported;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetDropCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwResetPreeditText;
import static org.lwjgl.glfw.GLFW.glfwSetPreeditCallback;
import static org.lwjgl.glfw.GLFW.glfwSetPreeditCursorRectangle;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowCloseCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowContentScaleCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;
import static org.lwjgl.glfw.GLFW.glfwSetWindowRefreshCallback;
import static org.lwjgl.glfw.GLFW.GLFW_POSITION_X;
import static org.lwjgl.glfw.GLFW.GLFW_POSITION_Y;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowOpacity;
import static org.lwjgl.glfw.GLFW.glfwSetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSizeLimits;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static limn.backend.lwjgl.Gl.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;
import limn.backend.spi.WindowPeer;

/**
 * GLFW-backed {@link NativeWindow} with an OpenGL 3.3 core (forward-compatible)
 * context. Renders from the loop when dirty, and also straight from the GLFW
 * refresh/framebuffer-size callbacks so the content stays live during native
 * resize drags (where {@code glfwPollEvents} blocks on Windows/macOS).
 */
final class LwjglWindow implements WindowPeer {

    private static final System.Logger LOG = System.getLogger(LwjglWindow.class.getName());

    // Whether this machine has already answered the accelerated-context question
    // with "no" (see the constructor). UI thread only, like every window.
    private static boolean macSoftwareGl;

    private final LwjglBackend backend;
    private final long handle;

    /** The accessibility bridge an application handed this window, or none. */
    /**
     * Null until something asks, and then whatever this platform has — or the one an application
     * installed instead. Not initialised to NONE: that would be indistinguishable from "asked, and
     * this machine has none", and the difference is a platform gate that has or has not been read.
     */
    private limn.backend.AccessibilityBridge accessibility;
    /** The context this window draws with, and who made it; see {@link WindowContext}. */
    private WindowContext context;
    /**
     * The child window this window draws on after a lost context was made again, on
     * Windows; NULL until then, when the context is on the window itself.
     */
    private long drawingSurface = NULL;
    /** Between {@link #forgetContext} and a successful {@link #remakeContext}: nothing may draw or delete. */
    private boolean contextGone;
    /** Whether the context's reset status said the device lost what it held, once a frame. */
    private boolean resetReported;
    /** Set by a rebuild, so that the first frame drawn after it is said in the log. */
    private boolean firstFrameAfterRebuild;
    /**
     * The content view whose input method {@link MacInputContextGate} switches, on macOS; {@code NULL}
     * elsewhere, or where the gate could not be made.
     */
    private long imeGatedView = NULL;
    /** What {@link #setSizeLimits} last applied, for {@link #sizeLimits()}. */
    private limn.backend.SizeLimits sizeLimits = limn.backend.SizeLimits.NONE;
    /** This window's context's entry points, of the process's profile ({@link GlApi#createCapabilities}). */
    private Object glCapabilities;
    /** The kind of context {@link #glCapabilities} were kept for, given back when the window goes. */
    private GlCapabilitiesCache.Key glCapabilitiesKey;
    private final GlRenderer renderer;
    private final limn.backend.Clipboard clipboard;
    private final ChildPopups childPopups = new ChildPopups();
    // Last known screen origin, kept so the position callback can hand ChildPopups a DELTA.
    // GLFW reports the new absolute position and nothing else; the previous one is ours to keep.
    private int lastScreenX;
    private int lastScreenY;
    private String title;
    private FrameCallback frameCallback;
    private ContentScaleListener contentScaleListener;
    private limn.backend.@Nullable CloseRequestHandler closeRequestHandler;
    /** Whether typed characters go to the scene: every request but {@code TextInput.NONE}. */
    private boolean charactersWanted = true;
    private @Nullable WindowFileDialogs fileDialogs;
    private limn.backend.Launcher launcher;
    private limn.backend.Printer printer;
    private limn.backend.ClosePolicy closePolicy = limn.backend.ClosePolicy.DISPOSE;
    private WindowInput input;
    private int framebufferWidth;
    private int framebufferHeight;
    private float contentScale;
    private float contentScaleOverride;
    private float pixelsPerScreenCoord = 1f;
    private boolean frameRequested = true;
    /** Presses in a row, counted with the platform's double-click interval. */
    private final ClickCounter clicks = ClickCounter.forThisPlatform();

    /** Whether a frame has been presented since the window was made; see {@link #isCovered}. */
    private boolean presentedOnce;
    /** How many frames have been presented at the current framebuffer size; see {@link #bufferAge}. */
    private int presentsAtSize;
    private int presentedWidth = -1;
    private int presentedHeight = -1;

    /** Reads the back buffer's age from the driver, on X11 and Wayland; see {@link #bufferAge}. */
    private BufferAgeQuery bufferAgeQuery;

    /**
     * How old the back buffer's contents are, for partial rendering: what the driver says where it
     * can ({@link BufferAgeQuery}), and where it cannot, what double buffering guarantees and nothing
     * more — 2 once two frames of this size have been presented, and 0, the whole window, before
     * that, which covers the first frames and every resize, where the contents are undefined.
     *
     * <p>The driver's word wins because the assumption was measured wrong on Linux: a driver that
     * answered 1 for twenty frames then switched to three buffers mid-run, and a popup that went on
     * assuming 2 came up with only its highlighted row drawn. Where no driver answers (macOS,
     * Windows) the assumption stands, and was measured to hold there on 2026-09-25: every frame of
     * a popup came out whole in seven runs on a macOS guest and six on a Windows one.
     */
    private int bufferAge() {
        if (framebufferWidth != presentedWidth || framebufferHeight != presentedHeight) {
            presentedWidth = framebufferWidth;
            presentedHeight = framebufferHeight;
            presentsAtSize = 0;
        }
        int assumed = presentsAtSize >= 2 ? 2 : 0;
        presentsAtSize++;
        int told = bufferAgeQuery.age();
        return told == BufferAgeQuery.UNKNOWN ? assumed : told;
    }
    private boolean rendering;
    private boolean destroyed;
    private final boolean resizable;
    // Always-on-top as the window was CREATED, and as it stands right now. A modal
    // that stops being the top of the stack gives up floating (see setFloating), so
    // the two part ways while a second modal is open over it.
    private final boolean floatingByConfig;
    private boolean floating;
    // Set when a non-resizable window is resized programmatically: on macOS the GL
    // drawable is committed one runloop turn after the framebuffer size is reported,
    // so the very next render would draw the whole frame at the wrong scale. We skip
    // exactly one render so the following one lands on the committed drawable.
    private boolean deferNextRender;
    // Whether both double buffers hold the current frame. false after a normal
    // present (the swapped-out buffer is one frame behind); syncBuffers re-presents
    // to reconverge them when the render burst settles.
    private boolean buffersSynced = true;
    private float opacity = 1f;
    private Cursor cursor = Cursor.DEFAULT;
    /** Whether GLFW holds this window fullscreen on a monitor, the only kind outside macOS. */
    private boolean monitorFullscreen;
    /**
     * This window's {@code NSWindow} on macOS, read once, for native fullscreen; {@code NULL}
     * elsewhere.
     */
    private final long cocoaWindow;
    /** What this window knows of its macOS native fullscreen; never consulted elsewhere. */
    private final NativeFullscreen nativeFullscreen = new NativeFullscreen();
    /**
     * A fullscreen on the monitor asked for while native fullscreen was still to be left, as
     * {@code {width, height, refreshRate}}: GLFW rewrites the style mask of a window it takes
     * fullscreen, which must not happen while AppKit animates it, so the request waits for the
     * window to be out. {@code null} when nothing waits.
     */
    private int[] monitorFullscreenAfterNative;
    /**
     * The collection behaviour native fullscreen replaced on a window GLFW made unable to enter it,
     * given back once the window is out; {@code -1} when nothing was replaced.
     */
    private long lentCollectionBehavior = -1;
    /** Whether native fullscreen lent this window the resizable style it lacks, to take back on exit. */
    private boolean lentResizable;
    /**
     * What {@link #isFullscreen()} answered when the input was last told; see
     * {@link #reportFullscreen}.
     */
    private boolean reportedFullscreen;
    /**
     * The position, size and maximized state this window had before it went fullscreen on the
     * monitor, and the watching of the window manager's answers when it leaves.
     */
    private final WindowedGeometry windowed = new WindowedGeometry();

    /** Whether the window was made as a popup; see {@link #isPopupWindow()}. */
    private final boolean popupWindow;

    LwjglWindow(LwjglBackend backend, WindowConfig config) {
        this.popupWindow = config.isPopup();
        this.backend = backend;
        // On the clock the runtime's own delays are measured on, which is the process's.
        this.cursorPlayer = new CursorPlayer(backend.uiRuntime(), System::nanoTime, this::showCursorFrame);
        this.title = config.title();
        this.resizable = config.resizable();
        this.floatingByConfig = config.floating();
        this.floating = config.floating();

        glfwDefaultWindowHints();
        // OpenGL 3.3 core, forward-compatible: the exact profile macOS still
        // ships; see docs/adr/001-backend.md for the post-GL migration routes.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, config.visible() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, config.resizable() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, config.decorated() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FLOATING, config.floating() ? GLFW_TRUE : GLFW_FALSE);
        // Translucent windows: alpha < 1 in the framebuffer composites over
        // whatever is behind (rounded popup corners are REALLY rounded).
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, config.transparent() ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, config.focusOnShow() ? GLFW_TRUE : GLFW_FALSE);
        // Windows/X11 report window sizes in physical pixels; this hint sizes the
        // window by the monitor scale so WindowConfig stays in logical points.
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE);
        // Which monitor, decided here rather than by the desktop, when the caller asked.
        //
        // This is the ONLY moment the choice can be made, and the reason is the hint above:
        // the content scale a window is born with is the scale of the monitor it is born on,
        // and it decides the framebuffer. Moving the window afterwards does re-scale it --
        // glfwSetWindowPos is honoured even on a hidden window -- but by then the first frame
        // has a size nobody chose. GLFW's own createWindow monitor argument is no help: it means
        // fullscreen ON that monitor, not windowed on it. These two hints, added in GLFW 3.4, are
        // what a windowed answer looks like. Wayland has no window positions at all, which
        // canPositionWindows() already knows, and there the request is simply not made.
        if (config.screenX() != WindowConfig.ANY_POSITION
                && config.screenY() != WindowConfig.ANY_POSITION
                && LwjglBackend.canPositionWindows()) {
            glfwWindowHint(GLFW_POSITION_X, config.screenX());
            glfwWindowHint(GLFW_POSITION_Y, config.screenY());
        }

        // Ask for the accelerated context first, on every platform macOS included:
        // this is the path every real machine takes, and it is unchanged. Only
        // once this machine has been SEEN to refuse (or the property asks) do we
        // stop asking: a menu that reopens a hundred times must not spend a
        // hundred failed context creations, nor print a hundred GLFW errors.
        //
        // A process already on ANGLE (ADR 003 §4.3), or asked to be, goes there directly; one that
        // is not tries ANGLE next when the native refusal is one it answers, and on macOS the
        // software context after that.
        boolean angle = AngleFallback.taken() || AngleFallback.forced();
        boolean softwareGl = !angle && MACOS && (macSoftwareGl || MacSoftwareGl.forced());
        long created;
        GraphicsProbe.Failure failure = null;
        String angleFailure = null;
        WglContext ownWgl = null;
        long ownNsgl = NULL;
        EglContext ownEgl = null;
        if (angle) {
            created = AngleFallback.createWindow(config.width(), config.height(), config.title(), null);
            angleFailure = created == NULL ? AngleFallback.failure() : null;
        } else {
            if (WINDOWS) {
                // The window from GLFW, the context from the backend (ADR 082 §1.1): one GLFW made
                // could not be deleted and made again after the driver's library moved.
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                created = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
                failure = created == NULL ? GraphicsProbe.lastFailure() : null;
                if (created != NULL) {
                    ownWgl = WglContext.create(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(created));
                    if (ownWgl == null) {
                        failure = WglContext.lastFailure();
                        glfwDestroyWindow(created);
                        created = NULL;
                    }
                }
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API); // for an ANGLE attempt
            } else if (MACOS && !softwareGl) {
                // As on Windows, the window from GLFW and the context from the backend, with the
                // pixel format GLFW asked AppKit for (ADR 082 §1.1).
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                created = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
                failure = created == NULL ? GraphicsProbe.lastFailure() : null;
                if (created != NULL) {
                    ownNsgl = MacSoftwareGl.createContext(created, config.transparent(), false, true);
                    if (ownNsgl == NULL) {
                        // GLFW's words for the same refusal, which the fallbacks below answer.
                        failure = new GraphicsProbe.Failure(GLFW_FORMAT_UNAVAILABLE,
                                "NSGL: Failed to find a suitable pixel format");
                        glfwDestroyWindow(created);
                        created = NULL;
                    }
                }
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API); // for an ANGLE attempt
            } else if (LINUX) {
                // The context from the backend here too (ADR 082 §1.1). On Wayland the window comes
                // with none; on X11 GLFW makes an EGL one that only chooses the visual, since a
                // window without one has no alpha, and the backend's draws on GLFW's surface.
                boolean wayland = org.lwjgl.glfw.GLFW.glfwGetPlatform() == org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
                if (wayland) {
                    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                } else {
                    glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
                }
                created = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
                if (created != NULL) {
                    ownEgl = wayland ? EglContext.onWayland(created) : EglContext.onGlfwSurface(created);
                    if (ownEgl == null && wayland) {
                        glfwDestroyWindow(created); // no context at all on it: GLFW's own, below
                        created = NULL;
                    }
                }
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
                glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
                if (created == NULL) {
                    // No EGL, or no 3.3 core through it: GLFW's own context, as before.
                    created = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
                    failure = created == NULL ? GraphicsProbe.lastFailure() : null;
                }
            } else {
                created = softwareGl ? NULL
                        : glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
                failure = created == NULL && !softwareGl ? GraphicsProbe.lastFailure() : null;
            }
            if (created == NULL && !softwareGl && AngleFallback.answers(Platform.current().os(), failure.code())) {
                created = AngleFallback.createWindow(config.width(), config.height(), config.title(), failure);
                angle = created != NULL;
                angleFailure = created == NULL ? AngleFallback.failure() : null;
            }
        }
        if (created == NULL && !AngleFallback.taken()
                && (softwareGl || (MACOS && failure != null && failure.code() == GLFW_FORMAT_UNAVAILABLE))) {
            // A macOS box whose only renderer is the software one: NSGL matches no
            // pixel format because GLFW asks every one of them for acceleration.
            // Retry for the window alone and build the context ourselves; see
            // MacSoftwareGl for what is different about it and why.
            softwareGl = true;
            // Back to the desktop profile's hints: the ANGLE attempt may have set ES ones.
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            created = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
            if (created == NULL) {
                failure = GraphicsProbe.lastFailure();
            }
        }
        handle = created;
        if (handle == NULL) {
            // GLFW's own description instead of a guess: the reason is usually
            // specific ("WGL: Failed to create OpenGL context"), and naming the
            // windowing platform matters because it is picked at run time: the
            // same binary reaches X11 or Wayland depending on the session.
            int code = failure != null ? failure.code() : GLFW_API_UNAVAILABLE;
            String message = "glfwCreateWindow failed on the "
                    + GraphicsProbe.platformName() + " platform: "
                    + (failure != null ? failure : "no native context was asked for")
                    + "; Limn needs an OpenGL 3.3 core context"
                    + (angleFailure != null ? ", or OpenGL ES 3.0 from ANGLE, which failed too: " + angleFailure : "")
                    + GraphicsProbe.contextAdvice(Platform.current().os(), code);
            if (GraphicsProbe.lacksDriver(Platform.current().os(), code)) {
                // Launched from an icon, this process has no console and the message would
                // reach nobody; Windows' own dialog says it first. It returns in every case.
                WindowsDriverDialog.offer(config.title(), message);
            }
            throw new IllegalStateException(message);
        }
        cocoaWindow = MACOS ? org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle) : NULL;
        if (MACOS && angle) {
            // ANGLE draws at its layer's scale, which a window born on a 1x screen had wrong.
            MacAngleLayers.matchBackingScale(handle);
        }
        if (MACOS && angle && config.transparent()) {
            // ANGLE's Metal layer is opaque, and a transparent window would show black.
            MacAngleLayers.makeTransparent(handle);
        }
        if (config.isPopup() && !config.focusOnShow() && !config.visible()
                && org.lwjgl.glfw.GLFW.glfwGetPlatform() == org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11) {
            // Before the first map, as GTK's menus are: GNOME on X11 focused such a popup anyway,
            // which took the keyboard from its owner and had Orca read the owner's frame again at
            // every closing (X11Popups says what else was tried).
            X11Popups.unmanaged(org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display(),
                    org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle));
        }
        long softwareContext = NULL;
        if (softwareGl) {
            softwareContext = MacSoftwareGl.createContext(handle, config.transparent(),
                    MacSoftwareGl.forced(), false);
            if (softwareContext == NULL) {
                glfwDestroyWindow(handle);
                throw new IllegalStateException("this Mac offers no OpenGL 3.2 core pixel format "
                        + "even without NSOpenGLPFAAccelerated; Limn needs an OpenGL 3.3 core context");
            }
            if (!macSoftwareGl) {
                LOG.log(Level.INFO, "OpenGL context: macOS software fallback ({0}); built as an "
                        + "NSOpenGLContext without NSOpenGLPFAAccelerated{1}, and taken by every "
                        + "window from here on; force it anywhere with -D{2}=true",
                        MacSoftwareGl.forced() ? "requested" : "GLFW found no accelerated pixel format",
                        MacSoftwareGl.forced() ? " and on Apple Software Renderer by name" : "",
                        MacSoftwareGl.PROPERTY);
                macSoftwareGl = true;
            }
        }
        EglContext ownAngle = angle ? AngleFallback.takeOwnContext() : null;
        context = ownAngle != null ? ownAngle
                : softwareContext != NULL ? WindowContext.nsgl(softwareContext)
                : ownNsgl != NULL ? WindowContext.nsgl(ownNsgl)
                : ownWgl != null ? ownWgl
                : ownEgl != null ? ownEgl : WindowContext.glfw(handle);
        if (!angle) {
            AngleFallback.nativeContextMade();
        }
        makeContextCurrent();
        // Those of a living window whose context is of the same kind where the platform allows
        // it, because resolving them again is most of what opening a popup costs.
        glCapabilitiesKey = GraphicsProbe.currentContextKey(softwareGl);
        glCapabilities = backend.glCapabilities().acquire(glCapabilitiesKey, Gl.api()::createCapabilities);
        Gl.api().setCapabilities(glCapabilities);
        // With the context current, as the query needs; the software macOS context has none to ask.
        bufferAgeQuery = context instanceof EglContext egl
                ? BufferAgeQuery.forEgl(egl.display(), egl.surface()) : BufferAgeQuery.forWindow(handle);
        setSwapInterval(1); // vsync

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, width, height);
            framebufferWidth = width.get(0);
            framebufferHeight = height.get(0);
            FloatBuffer scaleX = stack.mallocFloat(1);
            FloatBuffer scaleY = stack.mallocFloat(1);
            glfwGetWindowContentScale(handle, scaleX, scaleY);
            contentScale = scaleX.get(0);
        }
        updatePixelsPerScreenCoord();

        glfwSetFramebufferSizeCallback(handle, (win, width, height) -> {
            stateMayHaveChanged(); // a size the system reports here and not as a window size
            framebufferWidth = width;
            framebufferHeight = height;
            windowed.answered(); // an answer to a window leaving fullscreen, while one is watched
            context.drawableChanged(); // GLFW resizes the drawables it owns, and only those
            if (drawingSurface != NULL) {
                WglSurface.resize(drawingSurface, width, height);
            }
            updatePixelsPerScreenCoord();
            if (input != null) {
                input.windowResized(logicalWidth(), logicalHeight());
            }
            frameRequested = true;
            if (resizable) {
                renderNow(true); // keep content live while the native resize drag blocks the loop
            } else {
                // Programmatic resize (e.g. a menu growing for a submenu): the drawable
                // has not caught up yet on macOS; defer one render so we present the
                // new size only once its backing store is consistent (no scale glitch).
                deferNextRender = true;
            }
        });
        glfwSetCursorPosCallback(handle, (win, cursorX, cursorY) -> {
            if (input == null || inputBlocked()) {
                return;
            }
            if (pointerMode == limn.backend.PointerMode.RELATIVE) {
                // Captured: positions are an unbounded virtual counter; forward
                // successive differences as deltas. The first callback after
                // entering the mode carries the jump from the real cursor to the
                // virtual origin; establish the baseline and swallow it.
                if (relativeJump) {
                    relativeJump = false;
                } else {
                    input.mouseDelta(toLogical(cursorX - relativeLastX),
                            toLogical(cursorY - relativeLastY));
                }
                relativeLastX = cursorX;
                relativeLastY = cursorY;
            } else {
                realCursorX = cursorX;
                realCursorY = cursorY;
                input.mouseMoved(toLogical(cursorX), toLogical(cursorY));
            }
        });
        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
            if (inputBlocked()) {
                // Locked by a modal: swallow the click, raise the modal + beep.
                if (action == GLFW_PRESS) {
                    backend.onBlockedClick();
                }
                return;
            }
            if (input != null) {
                float x = toLogical(eventCursorX());
                float y = toLogical(eventCursorY());
                boolean press = action == GLFW_PRESS;
                input.mouseButton(button, press, mods, x, y,
                        press ? clicks.press(button, x, y) : clicks.release());
            }
        });
        glfwSetScrollCallback(handle, (win, dx, dy) -> {
            if (input != null && !inputBlocked()) {
                input.scrolled((float) dx, (float) dy,
                        toLogical(eventCursorX()), toLogical(eventCursorY()), scrollGestureBegins());
            }
        });
        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            if (input != null && !inputBlocked()) {
                input.keyEvent(key, layoutCodePoint(key, scancode), action != GLFW_RELEASE,
                        action == GLFW_REPEAT, mods);
            }
        });
        glfwSetCharCallback(handle, (win, codepoint) -> {
            if (input != null && !inputBlocked()) {
                if (charactersWanted) {
                    input.charTyped(codepoint);
                }
            }
        });
        // IME composition (LWJGL bundles GLFW's preedit patch): the still-composing
        // text arrives here as UTF-32 code points split into styled blocks; the
        // final commit still comes through the char callback above. An empty
        // preedit (preeditCount == 0) clears the composition.
        glfwSetPreeditCallback(handle,
                (win, preeditCount, preeditString, blockCount, blockSizes, focusedBlock, caret) -> {
            if (input != null && !inputBlocked()) {
                String text = preeditCount <= 0 ? "" : decodeCodePoints(preeditString, preeditCount);
                int[] blocks = blockCount <= 0 ? EMPTY_BLOCKS : decodeInts(blockSizes, blockCount);
                // GLFW's styled blocks and the focused one, told as what they mean: the stretch
                // being converted, as a start and a length in code points.
                int targetStart = 0;
                int targetLength = 0;
                if (focusedBlock >= 0 && focusedBlock < blocks.length) {
                    for (int i = 0; i < focusedBlock; i++) {
                        targetStart += blocks[i];
                    }
                    targetLength = blocks[focusedBlock];
                }
                input.preeditChanged(text, caret, targetStart, targetLength);
                frameRequested = true;
            }
        });
        if (Platform.current().isWindows() && backend.systemClipboard() != null) {
            // The system's drag and drop, from and onto this window.
            windowsDrag = limn.backend.lwjgl.transfer.WindowsDrag.install(
                    org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle), backend.uiRuntime(),
                    () -> inputBlocked() ? null : input, this::pointerForDrag,
                    this::paintDuringDrag, this::animateDuringDrag);
        }
        if (backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.WaylandClipboard wayland) {
            // The system's drag and drop on Wayland, through the clipboard's own data device.
            linuxDrag = wayland.register(org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandWindow(handle),
                    () -> inputBlocked() ? null : input, this::toLogical, backend.uiRuntime());
        }
        if (backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.X11Clipboard x11) {
            // The system's drag and drop on X11, spoken on the clipboard's connection.
            x11Drag = true;
            linuxDrag = x11.drags(backend.uiRuntime()).register(
                    org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle),
                    () -> inputBlocked() ? null : input, this::toLogical, this::reapplyCursor);
        }
        if (backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.X11Clipboard x11) {
            // A pinch on a trackpad, through XInput 2.4 on the same connection. Wayland's comes
            // through the clipboard's pointer, on every window its drags know.
            x11Pinch = x11.pinches(org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle),
                    () -> inputBlocked() ? null : input, this::toLogical, backend.uiRuntime());
        }
        if (MACOS) {
            long view = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaView(handle);
            if (MacInputContextGate.install(view)) {
                imeGatedView = view;
                // A pinch on a trackpad, at the pointer, as a zoom the scene hands out.
                MacPinch.install(view, (scale, began) -> {
                    if (input != null && !inputBlocked()) {
                        input.zoomed(scale, toLogical(eventCursorX()), toLogical(eventCursorY()), began);
                    }
                });
            }
            // The system's drag and drop, from and onto this view; it shares the pasteboard's
            // provider class with the clipboard, which is therefore opened first.
            if (backend.systemClipboard() != null) {
                macDrag = limn.backend.lwjgl.transfer.MacDrag.install(view, backend.uiRuntime(),
                        () -> inputBlocked() ? null : input, this::pointerForDrag, logicalToScreenFactor(),
                        this::paintDuringDrag, this::animateDuringDrag,
                        () -> {
                            // The pointer the scene last asked for, set again: GLFW believes it
                            // is still showing, and a drag's destination has shown others since.
                            if (!destroyed && imageCursor == null) {
                                limn.backend.lwjgl.internal.ObjC.msgVoid(limn.backend.lwjgl.internal.ObjC.msg(
                                        limn.backend.lwjgl.internal.ObjC.cls("NSCursor"), "arrowCursor"), "set");
                                glfwSetCursor(handle, cursor == Cursor.DEFAULT ? NULL : backend.cursorHandle(cursor));
                            }
                        });
            }
            // Every native fullscreen transition, the person's as well as ours, so that
            // isFullscreen() tells the truth about the green button and Control-Command-F too.
            MacFullscreen.observe(cocoaWindow, this::nativeFullscreenChanged);
        }
        // After the scale is read: the limits are logical, and GLFW's are screen coordinates.
        limn.backend.SizeLimits limits = config.sizeLimits();
        if (!limits.equals(limn.backend.SizeLimits.NONE)) {
            setSizeLimits(limits.minWidth(), limits.minHeight(), limits.maxWidth(), limits.maxHeight());
        }
        glfwSetCursorEnterCallback(handle, (win, entered) -> {
            if (input != null && !inputBlocked()) {
                input.pointerEntered(entered);
            }
        });
        glfwSetWindowRefreshCallback(handle, win -> {
            frameRequested = true;
            renderNow(true);
        });
        // Seed the origin BEFORE the callback exists: GLFW may deliver a position the moment the
        // window is placed, and a delta measured against 0,0 would fling every popup off-screen.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            readWindowPos(px, py);
            lastScreenX = px.get(0);
            lastScreenY = py.get(0);
        }
        // Open popups are separate top-level windows at absolute screen positions; nothing in the
        // OS ties them to this one. Without this they stay put while their owner is dragged away.
        glfwSetWindowPosCallback(handle, (win, x, y) -> {
            windowMovedTo(x, y);
            stateMayHaveChanged();
        });
        org.lwjgl.glfw.GLFW.glfwSetWindowIconifyCallback(handle, (win, iconified) -> stateMayHaveChanged());
        org.lwjgl.glfw.GLFW.glfwSetWindowMaximizeCallback(handle, (win, maximized) -> stateMayHaveChanged());
        org.lwjgl.glfw.GLFW.glfwSetWindowSizeCallback(handle, (win, w, h) -> stateMayHaveChanged());
        glfwSetWindowContentScaleCallback(handle, (win, scaleX, scaleY) -> {
            contentScale = scaleX;
            refreshRateMayHaveChanged(); // a new scale is usually a new monitor
            LOG.log(Level.INFO, "Window ''{0}'' moved to a monitor with content scale {1}", title, scaleX);
            if (contentScaleListener != null) {
                contentScaleListener.onContentScaleChanged(scaleX);
            }
            frameRequested = true;
        });
        glfwSetWindowFocusCallback(handle, (win, focused) -> {
            if (focused) {
                backend.toFront(this); // the first window an ending application asks
                // No platform reports a monitor's rate changing (a display set from 120 Hz to
                // 60 Hz in the system settings), and coming back from those settings focuses us.
                refreshRateMayHaveChanged();
            }
            // A modal-blocked window must never keep OS activation: the OS
            // raises/focuses it on click before our button handler runs. Hand
            // activation straight to the modal that blocks it, in the same event
            // cycle (before a frame is shown), so the dialog in front never flickers.
            if (focused && inputBlocked()) {
                backend.onBlockedWindowActivated(this);
            }
            // Even a blocked window's scene must cancel stuck press/hover state.
            if (input != null) {
                input.windowFocusChanged(focused);
            }
        });
        glfwSetDropCallback(handle, (win, count, names) -> {
            if (Boolean.getBoolean("limn.drag.trace")) {
                System.err.println("limn.drag(glfw): its own drop of " + count + " file(s)");
            }
            if (backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.WaylandClipboard wayland) {
                // On Wayland GLFW reads a drop's files from an offer of its own, and the offer
                // made to the clipboard's device then reads empty: it is told what GLFW read.
                java.util.List<java.nio.file.Path> read = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    read.add(java.nio.file.Path.of(GLFWDropCallback.getName(names, i)));
                }
                wayland.filesOfADrop(read);
            }
            // Where this backend answers the system's drags itself, files arrive as any other
            // drop does, and GLFW's own word of the same drop would be a second one. Except on
            // X11, where GLFW hears of a drop only when the source went round the proxy this
            // window names: one that honours it never tells GLFW, and one that does not (GTK 4
            // talks to the window itself from its second message on) tells nobody else, so its
            // files are taken from GLFW or not at all.
            if (input != null && !inputBlocked() && windowsDrag == null && macDrag == null
                    && (linuxDrag == null || x11Drag)) {
                java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    paths.add(java.nio.file.Path.of(GLFWDropCallback.getName(names, i)));
                }
                // GLFW says nothing of where: the pointer, which the platform moved onto the
                // window to drop, is there.
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    java.nio.DoubleBuffer cx = stack.mallocDouble(1);
                    java.nio.DoubleBuffer cy = stack.mallocDouble(1);
                    org.lwjgl.glfw.GLFW.glfwGetCursorPos(handle, cx, cy);
                    input.filesDropped(paths, toLogical(cx.get(0)), toLogical(cy.get(0)));
                }
            }
        });
        glfwSetWindowCloseCallback(handle, win -> {
            // Fires only for USER close attempts (close button / Alt-F4 / Cmd-W);
            // requestClose() sets the flag directly and never runs this callback,
            // so a programmatic close always bypasses the handler and the policy.
            //
            // GLFW has already set the flag; it is taken back, and the close is decided
            // after the poll returns (ADR 048 §1.1), where the handler may open a window
            // and answer later, and where the policy says whether allowing it disposes,
            // hides or ends the application.
            //
            // Deliberately NOT gated on inputBlocked(). A window with a modal open
            // is not clickable, but it must stay closable: "quit" is the one thing
            // an application still has to be able to ask about, and an app whose
            // answer is a second dialog gets one (see ModalStack, which is what
            // makes the pair of them answerable). Refusing here instead turns the
            // close button into a beep with no dialog and nothing to act on, which
            // is a worse answer than the deadlock it was avoiding.
            glfwSetWindowShouldClose(handle, false);
            backend.uiRuntime().post(() -> backend.userClosed(this));
        });

        renderer = new GlRenderer(backend.fontStore());
        limn.backend.Clipboard system = backend.systemClipboard();
        clipboard = system != null ? system
                : new GlfwClipboard(this::clipboardWindow, backend.uiRuntime()::checkUiThread);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Kept and handed to a scene when one binds here, in place of the one this backend would
     * have chosen. An application needs this only to install a bridge of its own or to refuse the
     * platform's with {@link limn.backend.AccessibilityBridge#NONE}; the ordinary case is answered
     * by {@link #accessibility()} without anyone asking.
     */
    @Override
    public void setAccessibility(limn.backend.AccessibilityBridge bridge) {
        this.accessibility = java.util.Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * {@inheritDoc}
     *
     * <p>No bridge, and on macOS the {@code NSWindow} is no accessibility element: AppKit tells a
     * client about every window that is one as it appears, and nothing of ours stands for this one.
     */
    @Override
    public void publishAccessibilityElsewhere() {
        backend.uiRuntime().checkUiThread();
        accessibility = limn.backend.AccessibilityBridge.NONE;
        if (!MACOS || destroyed) {
            return;
        }
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
        if (nsWindow != NULL) {
            ObjC.msgVoid(nsWindow, "setAccessibilityElement:", 0L); // BOOL NO, in the pointer slot
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Opened here, on the first ask, rather than by the application.</b> The platform bridges
     * live in this backend now, so there is nothing for an application to add and nothing for it to
     * forget: a scene binding to this window gets whatever this machine has. Phase 6 found the
     * failure this closes — a window could be asked what its accessibility was and had no way to be
     * told, so an application could ship the right artifact and never connect it, with no symptom
     * but silence.
     *
     * <p>On the first ask and not in the constructor, because the gate each platform reads is the
     * cheapest thing it does and still not free, and a window nothing ever binds to should pay for
     * nothing. After that it is a field.
     */
    @Override
    public limn.backend.AccessibilityBridge accessibility() {
        if (accessibility == null) {
            // The application's name and not this window's title: on Linux every window is a frame
            // of one application object, which a popup titled "popup" must not rename (decision 56).
            accessibility = limn.backend.lwjgl.a11y.Bridges.openFor(nativeHandle(),
                    backend.applicationName());
        }
        return accessibility;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Asked of GLFW, which is the only thing here that knows what a window is on this platform.
     * An unsupported platform answers zero rather than throwing, because "there is no handle" is a
     * legitimate answer and a bridge that gets one will decline to open.
     *
     * <p><b>Asked of the WINDOWING platform and not of the operating system</b>, which is not the
     * same question on Linux and used not to be distinguished here. {@code Platform.current()} answers
     * LINUX for a Wayland session as readily as for an X11 one, and
     * {@code glfwGetX11Window} on a Wayland session is not zero but an error —
     * {@code GLFW_PLATFORM_UNAVAILABLE, "X11: Platform not initialized"}. That went unnoticed while
     * nothing asked: the two bridges that need a handle are for the two operating systems with one
     * windowing platform each, and the Linux bridge addresses nodes by object path and never asks.
     * It surfaced the moment the backend began opening bridges itself, on the first Wayland guest.
     *
     * <p>Wayland answers zero, which is honest: this backend has not been taught to hand a Wayland
     * surface to anything, and nothing on that platform wants one. So does a closed window, whose
     * platform window is gone with it.
     */
    @Override
    public long nativeHandle() {
        if (destroyed) {
            return 0;
        }
        return switch (org.lwjgl.glfw.GLFW.glfwGetPlatform()) {
            case org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32 ->
                    org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
            case org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA ->
                    org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
            case org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11 ->
                    org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle);
            default -> 0;
        };
    }

    long handle() {
        return handle;
    }

    /**
     * The window to hand GLFW's clipboard calls. The clipboard is the application's and outlives
     * any one window, and GLFW reads no window for it and documents NULL as a valid argument; a
     * closed window's handle is freed memory and is not.
     */
    private long clipboardWindow() {
        return destroyed ? NULL : handle;
    }

    private void makeContextCurrent() {
        context.makeCurrent();
    }

    /**
     * Whether this window's context still draws: a colour cleared into one pixel of the back buffer
     * reads back as that colour. The question is asked of the context the window has
     * had all along, whose answer a lost device was measured to make black; a whole frame is asked
     * for after it, since the pixel is left in the buffer. Stalls the GPU once: only on a signal.
     */
    boolean contextDraws() {
        if (contextGone) {
            return false;
        }
        if (destroyed || framebufferWidth == 0 || framebufferHeight == 0) {
            return true; // nothing to ask of a minimized window; it is asked when it comes back
        }
        makeContextCurrent();
        Gl.api().setCapabilities(glCapabilities);
        int rgb;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Gl.glBindFramebuffer(org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER, 0);
            Gl.glEnable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
            Gl.glScissor(0, 0, 1, 1);
            Gl.glClearColor(1f, 0f, 1f, 1f);
            Gl.glClear(org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT);
            java.nio.ByteBuffer pixel = stack.malloc(4);
            Gl.glReadPixels(0, 0, 1, 1, org.lwjgl.opengl.GL33C.GL_RGBA, org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE, pixel);
            Gl.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST);
            rgb = ((pixel.get(0) & 0xff) << 16) | ((pixel.get(1) & 0xff) << 8) | (pixel.get(2) & 0xff);
        }
        requestWholeFrame();
        return rgb == 0xFF00FF;
    }

    /** The next frame draws everything, as the first frame at a size does: nothing in the buffers is kept. */
    private void requestWholeFrame() {
        presentsAtSize = 0;
        frameRequested = true;
    }

    /**
     * Lets go of a lost context: every GL object of the window forgotten without a GL
     * call, the context deleted, its capabilities given back. The window draws nothing until
     * {@link #remakeContext}.
     */
    void forgetContext() {
        if (contextGone) {
            return;
        }
        contextGone = true;
        renderer.contextLost();
        context.dispose();
        if (drawingSurface != NULL) {
            WglSurface.destroy(drawingSurface);
            drawingSurface = NULL;
        }
        backend.glCapabilities().release(glCapabilitiesKey);
        glCapabilities = null;
        if (WINDOWS) {
            // The OpenGL runtime subclassed this window when its pixel format was set, and takes
            // itself out of the chain as it is unloaded only if it is the outermost procedure.
            limn.backend.lwjgl.a11y.windows.UiaBridge.stepAside(
                    org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle));
        }
    }

    /**
     * On Linux nothing is unloaded and the window keeps its surface: a new EGL context on the same
     * window, through the same path the first was made by.
     */
    private boolean remakeEglContext() {
        boolean wayland = org.lwjgl.glfw.GLFW.glfwGetPlatform() == org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
        EglContext remade = wayland ? EglContext.onWayland(handle) : EglContext.onGlfwSurface(handle);
        if (remade == null) {
            return false;
        }
        context = remade;
        contextGone = false;
        resetReported = false;
        firstFrameAfterRebuild = true;
        makeContextCurrent();
        glCapabilitiesKey = GraphicsProbe.currentContextKey(false);
        glCapabilities = backend.glCapabilities().acquire(glCapabilitiesKey, Gl.api()::createCapabilities);
        Gl.api().setCapabilities(glCapabilities);
        bufferAgeQuery = BufferAgeQuery.forEgl(remade.display(), remade.surface());
        setSwapInterval(1);
        requestWholeFrame();
        return true;
    }

    /**
     * With the process on ANGLE: a new ANGLE context on the window itself, whose pixel format, set by
     * the system's OpenGL, is nothing to Direct3D.
     */
    private boolean remakeAngleContext() {
        long top = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
        limn.backend.lwjgl.a11y.windows.UiaBridge.stepBack(top);
        EglContext remade = EglContext.onAngle(handle, top);
        if (remade == null) {
            return false;
        }
        context = remade;
        contextGone = false;
        resetReported = false;
        firstFrameAfterRebuild = true;
        makeContextCurrent();
        glCapabilitiesKey = GraphicsProbe.currentContextKey(false);
        glCapabilities = backend.glCapabilities().acquire(glCapabilitiesKey, Gl.api()::createCapabilities);
        Gl.api().setCapabilities(glCapabilities);
        bufferAgeQuery = BufferAgeQuery.forEgl(remade.display(), remade.surface());
        setSwapInterval(1);
        requestWholeFrame();
        return true;
    }

    /** @return whether the context said by itself that it was lost, as ANGLE's does at a swap */
    boolean contextReportedLost() {
        return !contextGone && (resetReported || context instanceof EglContext egl && egl.reportedLost());
    }

    /** @return whether this window draws with a context the backend can make again after a loss */
    boolean contextRemakeable() {
        return context instanceof WglContext || context instanceof EglContext;
    }

    /**
     * Makes this window's context again after {@link #forgetContext} and the driver's unload, on a
     * child window covering the client area, since the window itself keeps the pixel format of the
     * driver that was unloaded. Windows only.
     *
     * @return whether a context was made; the caller verifies that it draws
     */
    boolean remakeContext() {
        if (LINUX) {
            return remakeEglContext();
        }
        if (AngleFallback.taken()) {
            return remakeAngleContext();
        }
        long top = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
        if (WglContext.restoreClassProcedure(top)) {
            LOG.log(System.Logger.Level.WARNING, "the unloaded OpenGL runtime''s procedure was still on the "
                    + "window ''{0}''; the window''s own was put back", title);
        }
        limn.backend.lwjgl.a11y.windows.UiaBridge.stepBack(top); // above what the window has now
        long surface = WglSurface.create(top, framebufferWidth, framebufferHeight, this::requestWholeFrame);
        if (surface == NULL) {
            return false;
        }
        WglContext remade = WglContext.create(surface);
        if (remade == null) {
            WglSurface.destroy(surface);
            return false;
        }
        drawingSurface = surface;
        context = remade;
        contextGone = false;
        resetReported = false;
        firstFrameAfterRebuild = true;
        makeContextCurrent();
        glCapabilitiesKey = GraphicsProbe.currentContextKey(false);
        glCapabilities = backend.glCapabilities().acquire(glCapabilitiesKey, Gl.api()::createCapabilities);
        Gl.api().setCapabilities(glCapabilities);
        setSwapInterval(1);
        requestWholeFrame();
        return true;
    }

    private void setSwapInterval(int interval) {
        context.setSwapInterval(interval);
    }

    private void swapBuffers() {
        context.swapBuffers();
    }

    /** @return this window's resident texture stats (glyph atlas + image cache). */
    limn.backend.RenderStats renderStats() {
        return renderer.stats();
    }

    /** @return the capabilities this window's context draws with, which it may share; for tests */
    Object glCapabilities() {
        return glCapabilities;
    }

    /**
     * @return what this window's context reports about itself. Makes the context
     *         current, so call it between frames and not inside one.
     */
    limn.backend.GraphicsInfo readGraphicsInfo() {
        makeContextCurrent();
        Gl.api().setCapabilities(glCapabilities);
        return GraphicsProbe.read(handle);
    }

    /** @return this window's 3D-subsystem stats (meshes/textures/targets/bytes + last-frame workload). */
    limn.render3d.Render3DStats render3DStats() {
        return renderer.render3DStats();
    }

    private boolean inputBlocked() {
        return backend.isInputBlocked(this);
    }

    /** Brings this window to the front and gives it input focus (modal raise). */
    void focusNative() {
        // Only visible windows can take focus (and we must not force-show a
        // hidden window, e.g. offscreen screenshot mode).
        if (!destroyed && glfwGetWindowAttrib(handle, GLFW_VISIBLE) == GLFW_TRUE) {
            if (!focusWithTheUsersConsent()) {
                glfwFocusWindow(handle);
            }
        }
    }

    /**
     * On a Linux desktop, brings the window in front with the proof that the user asked, when
     * the user just acted on something of ours outside our windows. Without that proof a desktop
     * that guards the focus leaves the window behind and marks it as wanting attention, which is
     * what the windowing library's own request gets there.
     *
     * @return whether the request went with the proof, and the library's own is not to be made
     */
    private boolean focusWithTheUsersConsent() {
        if (!Platform.current().isLinux() || !backend.userJustActed()
                || "false".equalsIgnoreCase(System.getProperty("limn.linux.activationProof"))) {
            return false;   // the property is for measuring a desktop with and without the proof
        }
        String token = backend.consentToken();
        try {
            if (org.lwjgl.glfw.GLFW.glfwGetPlatform() == org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND) {
                if (token != null && backend.systemClipboard() != null
                        && backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.WaylandClipboard wayland
                        && wayland.canActivate()) {
                    long surface = org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandWindow(handle);
                    boolean sent = wayland.activate(token, surface);
                    // A window shown a moment ago has no picture yet, and a compositor may pass
                    // over a surface with none: asked once more when it has drawn.
                    backend.uiRuntime().postDelayed(() -> {
                        if (!destroyed) {
                            wayland.activate(token, surface);
                        }
                    }, 150);
                    return sent;
                }
                return false;
            }
            return X11Activation.activate(org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Display(),
                    org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle), token);
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(Level.DEBUG, "the window was not brought in front with the user's consent", failed);
            return false;
        }
    }

    /**
     * On a Linux desktop, the user's consent for a program this window starts to come in front of
     * it, asked for now and read later: the launcher is called on the UI thread in answer to the
     * user's press, which is when the compositor will vouch for it, and hands the address over on
     * a thread of its own, which is where the answer is waited for.
     *
     * @return what answers the token on the launcher's thread, {@code ""} where there is none
     */
    java.util.function.Supplier<String> launchToken() {
        if (!Platform.current().isLinux() || destroyed
                || "false".equalsIgnoreCase(System.getProperty("limn.linux.activationProof"))) {
            return () -> "";
        }
        try {
            if (org.lwjgl.glfw.GLFW.glfwGetPlatform() == org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND) {
                if (backend.systemClipboard() != null
                        && backend.nativeClipboard() instanceof limn.backend.lwjgl.transfer.WaylandClipboard wayland
                        && wayland.canActivate()) {
                    java.util.concurrent.CompletableFuture<String> asked = wayland.requestToken(
                            org.lwjgl.glfw.GLFWNativeWayland.glfwGetWaylandWindow(handle));
                    return () -> {
                        try {
                            return asked.get(700, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (Exception late) {
                            return "";   // a compositor that does not answer gives no consent
                        }
                    };
                }
                return () -> "";
            }
            return X11Activation::startupToken;   // the server's time, read on the launcher's thread
        } catch (RuntimeException | LinkageError failed) {
            return () -> "";
        }
    }

    /** @return whether this window was created always-on-top, whatever it is right now */
    boolean floatsByConfig() {
        return floatingByConfig;
    }

    /**
     * Raises or drops always-on-top, for a modal that gained or lost the top of the
     * modal stack. A window created non-floating never starts floating here; this
     * only ever gives back what {@link WindowConfig#floating()} asked for.
     *
     * <p>Always-on-top is a <em>band</em>, not a position: floating windows order
     * among themselves normally, but no ordinary window can be raised over one. So a
     * modal only has to step down when the surface that must come to the front is
     * itself ordinary (an in-scene dialog's host window, say). Stepping down when it
     * is not needed is what makes an open dialog appear to vanish: it falls behind
     * the window it is blocking, which is bigger, and there is nothing left to see.
     */
    void setFloating(boolean value) {
        boolean want = value && floatingByConfig;
        if (want == floating || destroyed) {
            return;
        }
        floating = want;
        // Wayland has no always-on-top band for a client: GLFW ignores the creation hint there
        // and refuses this call with an error the callback prints (honoursWindowRequests).
        if (LwjglBackend.honoursWindowRequests()) {
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(handle, GLFW_FLOATING, want ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    /** Requests user attention (dock bounce / taskbar flash) for a modal. */
    void requestAttention() {
        if (!destroyed) {
            glfwRequestWindowAttention(handle);
        }
    }

    /** @return whether {@code candidate} is one of this window's registered popups */
    /** Whether {@code candidate} is one of this window's TRANSIENT popups: its own content. */
    boolean ownsTransientPopup(LwjglWindow candidate) {
        return childPopups.ownsTransient(candidate);
    }

    boolean ownsPopup(LwjglWindow candidate) {
        return childPopups.contains(candidate);
    }

    /** Package-private seam for the position callback (and for reasoning about it in one place). */
    void windowMovedTo(int x, int y) {
        windowed.answered(); // an answer to a window leaving fullscreen, while one is watched
        int dx = x - lastScreenX;
        int dy = y - lastScreenY;
        lastScreenX = x;
        lastScreenY = y;
        childPopups.moveBy(dx, dy);
        refreshRateMayHaveChanged();
    }

    /** This window's refresh period, read again at the next ask; negative until read. */
    private long refreshPeriodNanos = -1;

    /**
     * Forgets the refresh period, to be read again the next time the loop paces to this window:
     * the window moved, possibly onto another monitor, or a monitor came or went. Only marked
     * here, because a drag moves the window on every event and the read lists every monitor.
     */
    void refreshRateMayHaveChanged() {
        refreshPeriodNanos = -1;
    }

    /**
     * The refresh period of the monitor this window is on, in nanoseconds, or 0 when no monitor
     * states a rate; see {@link RefreshPeriod} for which monitor that is.
     */
    long refreshPeriodNanos() {
        if (refreshPeriodNanos < 0) {
            refreshPeriodNanos = readRefreshPeriod();
        }
        return refreshPeriodNanos;
    }

    private long readRefreshPeriod() {
        java.util.List<RefreshPeriod.Monitor> connected = new java.util.ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer monitors = glfwGetMonitors();
            IntBuffer mx = stack.mallocInt(1);
            IntBuffer my = stack.mallocInt(1);
            for (int i = 0; monitors != null && i < monitors.limit(); i++) {
                long monitor = monitors.get(i);
                GLFWVidMode mode = glfwGetVideoMode(monitor);
                if (mode == null) {
                    continue;
                }
                glfwGetMonitorPos(monitor, mx, my);
                connected.add(new RefreshPeriod.Monitor(mx.get(0), my.get(0), mode.width(),
                        mode.height(), mode.refreshRate()));
            }
            boolean positionKnown = supportsAbsolutePositioning() && !destroyed;
            int cx = 0;
            int cy = 0;
            if (positionKnown) {
                IntBuffer px = stack.mallocInt(1);
                IntBuffer py = stack.mallocInt(1);
                IntBuffer sw = stack.mallocInt(1);
                IntBuffer sh = stack.mallocInt(1);
                readWindowPos(px, py);
                glfwGetWindowSize(handle, sw, sh);
                cx = px.get(0) + sw.get(0) / 2;
                cy = py.get(0) + sh.get(0) / 2;
            }
            return RefreshPeriod.forWindow(positionKnown, cx, cy, connected);
        }
    }

    boolean frameRequested() {
        return frameRequested;
    }

    /**
     * Whether this window is on screen and entirely covered, so a frame drawn now would be seen by
     * nobody. macOS only, where it is read off {@code occlusionState}; every
     * other platform answers false and paces through {@link FramePacer} alone.
     *
     * <p>Only for a window that GLFW calls visible and that has presented at least once: a hidden
     * window renders on purpose (the gallery captures from hidden windows), and a window just
     * shown may not have its visible bit yet, and must draw its first frame regardless.
     *
     * <p>Nor for a window owed a capture: someone asked for its pixels, and a covered window that
     * skipped its frames would never hand them over. The first cut of the pacer did exactly that,
     * and a program that captured a window behind another one waited forever with no error.
     */
    boolean isCovered() {
        if (!MACOS || destroyed || !presentedOnce || renderer.capturePending()
                || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_VISIBLE) == 0) {
            return false;
        }
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
        if (nsWindow == NULL) {
            return false;
        }
        // NSWindowOcclusionStateVisible = 1 << 1; an NSUInteger, which rides the pointer slot.
        return (ObjC.msg(nsWindow, "occlusionState") & 2L) == 0;
    }

    /** Whether the desktop says this window is the one keys go to, asked of it now. */
    boolean hasTheKeyboard() {
        return !destroyed && org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_FOCUSED)
                == org.lwjgl.glfw.GLFW.GLFW_TRUE;
    }

    boolean closeRequested() {
        return destroyed || glfwWindowShouldClose(handle);
    }

    /**
     * Renders one frame immediately (UI thread only). No-op while minimized.
     *
     * @param vsync whether this window's swap should block on the display's
     *              vblank. The loop grants it to ONE window per iteration: a
     *              single blocking swap paces every window to the refresh
     *              rate, while the others swap unthrottled into the same
     *              vblank (the compositor prevents tearing). All-windows
     *              vsync would serialize N animated windows to refresh/N fps.
     * @return whether a frame was actually drawn and swapped
     */
    boolean renderNow(boolean vsync) {
        if (renderFrameInternal(false, vsync)) {
            // The just-swapped-out buffer now holds the previous frame: diverged.
            buffersSynced = false;
            return true;
        }
        return false;
    }

    /**
     * Re-presents the current, already-settled frame so BOTH double buffers hold
     * it. With on-demand rendering the loop presents sparsely; macOS's
     * layer-backed compositor can then surface the one-frame-old back buffer
     * between presents, a faint ghost of the last thing that moved (e.g. a
     * scrollbar thumb settling). Called by the loop after a render burst goes
     * idle. No-op when a frame is still pending or the buffers already match.
     *
     * @return whether a re-present actually swapped (see {@link #renderNow} on vsync)
     */
    boolean syncBuffers(boolean vsync) {
        if (buffersSynced || frameRequested || destroyed || rendering) {
            return false;
        }
        // Identical pixels; FrameInfo.rePresent → not counted in metrics.
        boolean swapped = renderFrameInternal(true, vsync);
        buffersSynced = true;
        return swapped;
    }

    /** @return whether a frame was actually drawn and swapped */
    private boolean renderFrameInternal(boolean rePresent, boolean vsync) {
        if (destroyed || rendering) {
            return false;
        }
        if (contextGone) {
            frameRequested = true; // drawn once the context is made again
            return false;
        }
        if (deferNextRender) {
            // A programmatic resize just happened; the drawable is not yet consistent
            // with the new size. Skip this one present and render on the next loop
            // iteration, by which the backing store has committed.
            deferNextRender = false;
            frameRequested = true;
            return false;
        }
        if (frameCallback == null || framebufferWidth == 0 || framebufferHeight == 0) {
            // Nothing to draw (no callback registered, or minimized): clear the
            // dirty flag so the loop goes back to sleeping instead of spinning.
            frameRequested = false;
            return false;
        }
        rendering = true;
        try {
            makeContextCurrent();
            // LWJGL caps are thread-local per context: re-bind when hopping windows.
            Gl.api().setCapabilities(glCapabilities);
            if (Gl.api().graphicsResetStatus() != 0) {
                // The device lost what this context held: nothing drawn here would show. The watch
                // makes it again before the next frame (ADR 082 §1.3).
                resetReported = true;
                frameRequested = true;
                return false;
            }
            // One window per loop iteration paces on vblank; the others swap
            // free into the same one (see renderNow). The interval is
            // per-context state, so re-assert it every frame.
            setSwapInterval(vsync ? 1 : 0);
            glViewport(0, 0, framebufferWidth, framebufferHeight);
            frameRequested = false; // callback may re-request for the next frame
            float scale = effectiveScale();
            presentedOnce = true;
            int age = bufferAge(); // asked before anything is drawn into the frame
            renderer.beginFrame(framebufferWidth, framebufferHeight, scale, rePresent);
            try {
                frameCallback.onFrame(renderer,
                        new FrameInfo(framebufferWidth, framebufferHeight, scale, rePresent,
                                renderer.takeGpuFrameMs(), age));
            } finally {
                // User code may have switched GL contexts (posting is the
                // sanctioned path for window creation, but stay safe).
                makeContextCurrent();
                Gl.api().setCapabilities(glCapabilities);
                renderer.endFrame();
            }
            swapBuffers();
            if (firstFrameAfterRebuild) {
                firstFrameAfterRebuild = false;
                LOG.log(System.Logger.Level.INFO, "window ''{0}'' drew its first frame on the context made again",
                        title);
            }
        } finally {
            rendering = false;
        }
        return true;
    }

    private float effectiveScale() {
        return contentScaleOverride > 0 ? contentScaleOverride : contentScale;
    }

    /** Screen coords → logical points (macOS: 1:2:2, Windows/X11: 1:1:scale). */
    private float toLogical(double screenCoord) {
        return (float) (screenCoord * pixelsPerScreenCoord / effectiveScale());
    }

    private void updatePixelsPerScreenCoord() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            if (w.get(0) > 0 && framebufferWidth > 0) {
                pixelsPerScreenCoord = framebufferWidth / (float) w.get(0);
            }
        }
    }

    private static final int[] EMPTY_BLOCKS = new int[0];

    /** Reads {@code count} UTF-32 code points at {@code ptr} into a Java string. */
    private static String decodeCodePoints(long ptr, int count) {
        IntBuffer codePoints = MemoryUtil.memIntBuffer(ptr, count);
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.appendCodePoint(codePoints.get(i));
        }
        return text.toString();
    }

    /** Copies {@code count} native ints at {@code ptr} into a Java array. */
    private static int[] decodeInts(long ptr, int count) {
        IntBuffer buffer = MemoryUtil.memIntBuffer(ptr, count);
        int[] values = new int[count];
        buffer.get(values);
        return values;
    }

    /** Loop phase: after the native poll, before the Ui.post drain. */
    void notifyInputBatchEnd() {
        if (input != null) {
            input.inputBatchEnded();
        }
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        cursorPlayer.stop();
        // Let the bound scene flush anything waiting on a frame that will never
        // come (e.g. a window-fade callback that completes a dialog's result).
        // Contained: a throwing close callback must not skip the native teardown
        // below; destroy() never re-runs (destroyed is already set), so skipping
        // it would leak the GLFW window and its GL resources permanently.
        if (input != null) {
            try {
                input.windowClosed();
            } catch (limn.backend.Crashes.ShutdownRequested shutdown) {
                backend.stop(); // already dispatched; finish this teardown, then end the loop
            } catch (Throwable error) {
                if (!limn.backend.Crashes.dispatch(limn.backend.CrashPhase.WINDOW_CLOSE, error)) {
                    backend.stop(); // orderly: finish this teardown, then end the loop
                }
            }
        }
        // A parent must never outlive-close its popups: dismiss them first so
        // a floating (always-on-top) popup can't be orphaned on screen.
        childPopups.closeAll();
        // GL resources (VBO/shader) must die on their own context; one that went with the device
        // took them along, and there is nothing left to delete or to release.
        if (!contextGone) {
            makeContextCurrent();
            Gl.api().setCapabilities(glCapabilities);
        }
        try {
            if (!contextGone) {
                renderer.dispose();
            }
        } catch (RuntimeException | LinkageError error) {
            // Contained for the reason the close callback is: this method runs once, and what
            // follows gives back the callbacks and the window itself.
            LOG.log(System.Logger.Level.ERROR, "the renderer threw as its window closed; the window is still"
                    + " destroyed", error);
        }
        if (!contextGone) {
            context.dispose();
        }
        if (drawingSurface != NULL) {
            WglSurface.destroy(drawingSurface);
            drawingSurface = NULL;
        }
        // glfwFreeCallbacks predates the preedit patch and does not free this
        // callback; its native upcall stub pins this window (and its scene)
        // through a JNI global ref unless freed explicitly.
        GLFWPreeditCallback preeditCallback = glfwSetPreeditCallback(handle, null);
        if (preeditCallback != null) {
            preeditCallback.free();
        }
        Callbacks.glfwFreeCallbacks(handle);
        if (imeGatedView != NULL) {
            MacPinch.forget(imeGatedView);
            MacInputContextGate.forget(imeGatedView);
        }
        if (cocoaWindow != NULL) {
            MacFullscreen.forget(cocoaWindow);
        }
        if (macDrag != null) {
            macDrag.close();
            macDrag = null;
        }
        if (windowsDrag != null) {
            windowsDrag.close();
            windowsDrag = null;
        }
        if (x11Pinch != null) {
            x11Pinch.run();
            x11Pinch = null;
        }
        if (linuxDrag != null) {
            linuxDrag.close();
            linuxDrag = null;
        }
        glfwDestroyWindow(handle);
        if (!contextGone) {
            backend.glCapabilities().release(glCapabilitiesKey);
        }
    }

    @Override
    public String title() {
        return title;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A closed window keeps the title, for {@link #title()}, and asks GLFW nothing. That holds
     * for every setter here: the handle was freed with the window, and GLFW does not check what it
     * is given, so a call that reaches it corrupts whatever the allocator put there since, or
     * aborts the process. It is an ordinary call to meet, because nothing tells an application's
     * pending work that the user closed the window: a title set when a background load completes
     * is the common case.
     */
    @Override
    public void setTitle(String newTitle) {
        backend.uiRuntime().checkUiThread();
        this.title = Objects.requireNonNull(newTitle, "title");
        if (destroyed) {
            return;
        }
        glfwSetWindowTitle(handle, newTitle);
    }

    @Override
    public float logicalWidth() {
        return framebufferWidth / effectiveScale();
    }

    @Override
    public float logicalHeight() {
        return framebufferHeight / effectiveScale();
    }

    @Override
    public int framebufferWidth() {
        return framebufferWidth;
    }

    @Override
    public int framebufferHeight() {
        return framebufferHeight;
    }

    @Override
    public float contentScale() {
        return effectiveScale();
    }

    @Override
    public void overrideContentScale(float scale) {
        backend.uiRuntime().checkUiThread();
        this.contentScaleOverride = Math.max(0, scale);
        this.frameRequested = true;
    }

    @Override
    public void setSize(int width, int height) {
        backend.uiRuntime().checkUiThread();
        Checks.positiveSize(width, height, "window size");
        if (destroyed) {
            return;
        }
        // GLFW takes SCREEN coordinates: logical points on macOS, physical
        // pixels on Windows/X11. Convert via the framebuffer-per-screen-coord
        // ratio so "logical points" holds on every platform:
        //   screen = logical * monitorScale / (fb / screen_now)
        float pixelsPerScreenCoord = 1f;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            if (w.get(0) > 0 && framebufferWidth > 0) {
                pixelsPerScreenCoord = framebufferWidth / (float) w.get(0);
            }
        }
        int screenW = Math.max(1, Math.round(width * contentScale / pixelsPerScreenCoord));
        int screenH = Math.max(1, Math.round(height * contentScale / pixelsPerScreenCoord));
        glfwSetWindowSize(handle, screenW, screenH);
        // Refresh metrics right away: the framebuffer callback may only fire
        // on the next event poll.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            framebufferWidth = w.get(0);
            framebufferHeight = h.get(0);
        }
        updatePixelsPerScreenCoord();
        this.frameRequested = true;
        if (resizable) {
            // Present the correctly-sized frame right now so the compositor never
            // stretches the previous frame to the new bounds during a live drag.
            renderNow(true);
        } else {
            // Programmatic resize of a popup: on macOS the GL drawable lags the
            // reported framebuffer size by a runloop turn, so rendering now would draw
            // the whole frame at the wrong scale for one frame, a visible "shrink"
            // glitch when a native menu grows for a submenu. Defer one render so the
            // new size is presented only once the drawable is consistent.
            deferNextRender = true;
        }
    }

    @Override
    public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        sizeLimits = new limn.backend.SizeLimits(minWidth, minHeight, maxWidth, maxHeight);
        // Same logical → screen conversion as setSize (GLFW limits are screen coords).
        glfwSetWindowSizeLimits(handle,
                toScreenOrDontCare(minWidth), toScreenOrDontCare(minHeight),
                toScreenOrDontCare(maxWidth), toScreenOrDontCare(maxHeight));
    }

    @Override
    public limn.backend.SizeLimits sizeLimits() {
        return sizeLimits;
    }

    private int toScreenOrDontCare(int logical) {
        if (logical <= 0) {
            return GLFW_DONT_CARE;
        }
        return Math.max(1, Math.round(logical * contentScale / pixelsPerScreenCoord));
    }

    @Override
    public void setIcon(limn.graphics.Image... icons) {
        backend.uiRuntime().checkUiThread();
        if (destroyed || icons == null || icons.length == 0) {
            return;
        }
        if (MACOS) {
            return; // macOS windows have no per-window icon (the app bundle's is used)
        }
        if (!LwjglBackend.honoursWindowRequests()) {
            // Nor has a Wayland client: the desktop shows its application entry's icon, and GLFW
            // refuses the call with an error the callback prints (honoursWindowRequests).
            return;
        }
        // Icon pixels go on the heap, not the MemoryStack: a 256px RGBA icon
        // (256 KB) would overflow it. GLFW copies the data before returning.
        java.util.List<ByteBuffer> pixelBuffers = new java.util.ArrayList<>(icons.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer images = GLFWImage.malloc(icons.length, stack);
            for (int i = 0; i < icons.length; i++) {
                limn.graphics.Image icon = icons[i];
                ByteBuffer pixels = OffHeap.copyOf(icon.pixels());
                pixelBuffers.add(pixels);
                images.get(i).set(icon.width(), icon.height(), pixels);
            }
            glfwSetWindowIcon(handle, images);
        } finally {
            for (ByteBuffer pixels : pixelBuffers) {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    @Override
    public void setCloseRequestHandler(limn.backend.@Nullable CloseRequestHandler handler) {
        backend.uiRuntime().checkUiThread();
        this.closeRequestHandler = handler;
    }

    @Override
    public limn.backend.@Nullable CloseRequestHandler closeRequestHandler() {
        return closeRequestHandler;
    }

    @Override
    public void setClosePolicy(limn.backend.ClosePolicy policy) {
        java.util.Objects.requireNonNull(policy, "policy");
        backend.uiRuntime().checkUiThread();
        this.closePolicy = policy;
    }

    @Override
    public limn.backend.ClosePolicy closePolicy() {
        return closePolicy;
    }

    @Override
    public void show() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return; // closed is not hidden: there is nothing left to show
        }
        glfwShowWindow(handle);
        stateMayHaveChanged();
        context.drawableChanged(); // a window built hidden (every popup) gets its drawable here
    }

    @Override
    public void hide() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        glfwHideWindow(handle);
        stateMayHaveChanged();
    }

    private final java.util.List<java.util.function.Consumer<limn.backend.WindowState>> stateListeners =
            new java.util.ArrayList<>();
    private limn.backend.@org.jspecify.annotations.Nullable WindowState toldState;
    private boolean stateCheckPosted;

    @Override
    public limn.concurrent.Subscription observeState(java.util.function.Consumer<limn.backend.WindowState> listener) {
        backend.uiRuntime().checkUiThread();
        java.util.Objects.requireNonNull(listener, "listener");
        if (stateListeners.isEmpty()) {
            toldState = state();   // what a first listener is told is what changes from here
        }
        stateListeners.add(listener);
        return () -> stateListeners.remove(listener);
    }

    /**
     * Has the state compared with what the listeners were last told, after the event that may
     * have changed it and never inside it: the system's callbacks arrive in the middle of a
     * poll, and a listener that shows or resizes a window there would do it inside the system's
     * own handling of this one.
     */
    private limn.backend.@org.jspecify.annotations.Nullable ScreenRect boundsBeforeMinimizing;

    /**
     * The state with what two systems report of a minimized window put right: Windows parks one
     * far off the screen at no size, and macOS says it is not shown. A minimized window is a
     * shown one, at the place and size it comes back to.
     */
    @Override
    public limn.backend.WindowState state() {
        backend.uiRuntime().checkUiThread();
        boolean minimized = isMinimized();
        limn.backend.ScreenRect bounds;
        if (minimized && boundsBeforeMinimizing != null) {
            bounds = boundsBeforeMinimizing;
        } else {
            bounds = bounds();
            if (!minimized) {
                boundsBeforeMinimizing = bounds;
            }
        }
        return new limn.backend.WindowState(isVisible() || minimized, minimized, isMaximized(), isFullscreen(),
                bounds);
    }

    private void stateMayHaveChanged() {
        if (stateListeners.isEmpty() || stateCheckPosted || destroyed) {
            return;
        }
        if (!isMinimized()) {
            boundsBeforeMinimizing = bounds();   // kept current, so that minimizing finds it
        }
        stateCheckPosted = true;
        backend.uiRuntime().post(() -> {
            stateCheckPosted = false;
            if (destroyed || stateListeners.isEmpty()) {
                return;
            }
            limn.backend.WindowState now = state();
            if (!now.equals(toldState)) {
                toldState = now;
                for (java.util.function.Consumer<limn.backend.WindowState> listener : java.util.List.copyOf(stateListeners)) {
                    listener.accept(now);
                }
            }
        });
    }

    @Override
    public void minimize() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        org.lwjgl.glfw.GLFW.glfwIconifyWindow(handle);
    }

    @Override
    public void maximize() {
        backend.uiRuntime().checkUiThread();
        if (destroyed || isFullscreen()) {
            return;
        }
        org.lwjgl.glfw.GLFW.glfwMaximizeWindow(handle);
    }

    @Override
    public void restore() {
        backend.uiRuntime().checkUiThread();
        if (destroyed || isFullscreen()) {
            return;
        }
        glfwRestoreWindow(handle);
    }

    @Override
    public boolean isMinimized() {
        backend.uiRuntime().checkUiThread();
        return !destroyed && glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) == GLFW_TRUE;
    }

    @Override
    public boolean isMaximized() {
        backend.uiRuntime().checkUiThread();
        return !destroyed && glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE;
    }

    @Override
    public void setOpacity(float value) {
        backend.uiRuntime().checkUiThread();
        float clamped = Math.max(0f, Math.min(1f, value));
        if (destroyed || clamped == opacity) {
            return;
        }
        opacity = clamped;
        // Compositor-level, whole-window alpha (frame + content). Wayland gives a client none:
        // GLFW refuses the call there with an error the callback prints, at every step of a
        // fade, so it is not made, and a fade's window stays opaque (honoursWindowRequests).
        // The value kept above still dedupes the steps; the scene's fade keeps its own time.
        if (LwjglBackend.honoursWindowRequests()) {
            glfwSetWindowOpacity(handle, clamped);
        }
    }

    // Pointer mode + relative-capture delta state (see the cursor-pos callback).
    private limn.backend.PointerMode pointerMode = limn.backend.PointerMode.NORMAL;
    private double relativeLastX;
    private double relativeLastY;
    private boolean relativeJump;
    // Last REAL cursor position (screen coords). While RELATIVE, GLFW's queryable
    // position is an unbounded virtual counter, meaningless as an event
    // coordinate, so button/scroll events report this frozen position instead
    // (consistent with the scene's frozen hover/mouse state during capture).
    private double realCursorX;
    private double realCursorY;

    /** Cursor x for a button/scroll event: real position, frozen while RELATIVE. */
    private double eventCursorX() {
        if (pointerMode != limn.backend.PointerMode.RELATIVE) {
            refreshRealCursor();
        }
        return realCursorX;
    }

    /** Cursor y for a button/scroll event: real position, frozen while RELATIVE. */
    private double eventCursorY() {
        if (pointerMode != limn.backend.PointerMode.RELATIVE) {
            refreshRealCursor();
        }
        return realCursorY;
    }

    private void refreshRealCursor() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(handle, x, y);
            realCursorX = x.get(0);
            realCursorY = y.get(0);
        }
    }
    // Active custom cursor; while set it overrides the standard shape, which
    // stays recorded in 'cursor' and is restored when the image clears.
    private limn.backend.ImageCursor imageCursor;
    /** Swaps the frames of an animated image cursor while this window shows it. */
    private final CursorPlayer cursorPlayer;

    /** The pointer shape this window was last asked for, set again after something else changed it. */
    private void reapplyCursor() {
        if (!destroyed) {
            glfwSetCursor(handle, cursor == Cursor.DEFAULT ? NULL : backend.cursorHandle(cursor));
        }
    }

    @Override
    public void setCursor(@Nullable Cursor newCursor) {
        backend.uiRuntime().checkUiThread();
        Cursor target = newCursor == null ? Cursor.DEFAULT : newCursor;
        if (destroyed || target == cursor) {
            return;
        }
        cursor = target;
        if (imageCursor != null) {
            return; // shape recorded; the active image cursor stays on screen
        }
        // DEFAULT → NULL restores the window's plain arrow (no cursor object needed);
        // any other shape uses the backend's shared, lazily-created standard cursor.
        glfwSetCursor(handle, target == Cursor.DEFAULT ? NULL : backend.cursorHandle(target));
    }

    /** @return the custom cursor currently shown, or null (backend eviction guard) */
    limn.backend.ImageCursor activeImageCursor() {
        return imageCursor;
    }

    /** Shows one frame of the animated image cursor this window shows. */
    private void showCursorFrame(int frame) {
        limn.backend.ImageCursor shown = imageCursor;
        if (!destroyed && shown != null) {
            glfwSetCursor(handle, backend.imageCursorHandle(shown, frame));
        }
    }

    @Override
    public void setImageCursor(limn.backend.@Nullable ImageCursor newCursor) {
        backend.uiRuntime().checkUiThread();
        if (destroyed || java.util.Objects.equals(newCursor, imageCursor)) {
            return;
        }
        imageCursor = newCursor;
        cursorPlayer.play(newCursor);
        if (newCursor != null) {
            glfwSetCursor(handle, backend.imageCursorHandle(newCursor, 0));
        } else {
            // Image cleared: fall back to the recorded standard shape.
            glfwSetCursor(handle, cursor == Cursor.DEFAULT ? NULL : backend.cursorHandle(cursor));
        }
    }

    @Override
    public void setPointerMode(limn.backend.@Nullable PointerMode mode) {
        backend.uiRuntime().checkUiThread();
        limn.backend.PointerMode target =
                mode == null ? limn.backend.PointerMode.NORMAL : mode;
        if (destroyed || target == pointerMode) {
            return;
        }
        boolean wasRelative = pointerMode == limn.backend.PointerMode.RELATIVE;
        pointerMode = target;
        int glfwMode = switch (target) {
            case NORMAL -> GLFW_CURSOR_NORMAL;
            case HIDDEN -> GLFW_CURSOR_HIDDEN;
            case CONFINED -> GLFW_CURSOR_CAPTURED;
            case RELATIVE -> GLFW_CURSOR_DISABLED;
        };
        if (target == limn.backend.PointerMode.RELATIVE) {
            relativeJump = true; // baseline on the first virtual position
            // Freeze the REAL position while GLFW still reports one: button and
            // scroll events during the capture carry this point (the virtual
            // counter is unbounded and meaningless as a coordinate).
            refreshRealCursor();
            // Unaccelerated deltas where the OS can deliver them (GLFW errors
            // on setting the mode where unsupported, so it must be guarded).
            if (glfwRawMouseMotionSupported()) {
                glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
            }
        } else if (wasRelative && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE);
        }
        glfwSetInputMode(handle, GLFW_CURSOR, glfwMode);
    }

    @Override
    public limn.backend.PointerMode pointerMode() {
        backend.uiRuntime().checkUiThread();
        return pointerMode;
    }

    @Override
    public void setCursorPosition(float x, float y) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        if (!LwjglBackend.honoursWindowRequests()) {
            // Wayland lets no client move the pointer: GLFW refuses the call with an error the
            // callback prints (honoursWindowRequests), and the pointer stays where it is.
            return;
        }
        // Inverse of toLogical: logical points → native screen coordinates.
        float toScreen = effectiveScale() / pixelsPerScreenCoord;
        double sx = x * toScreen;
        double sy = y * toScreen;
        glfwSetCursorPos(handle, sx, sy);
        // No callback fires for a programmatic warp: keep the mirrors honest.
        // While RELATIVE the warp moves the virtual counter; re-baseline so
        // the next callback doesn't read the jump as one giant delta.
        realCursorX = sx;
        realCursorY = sy;
        relativeJump = pointerMode == limn.backend.PointerMode.RELATIVE;
    }

    /**
     * What the active layout types at a physical key with no modifier held: GLFW's name for a
     * printable key, which it reads from the layout (on AZERTY the key GLFW calls W is named "z"),
     * as one code point; {@code 0} for a key GLFW names nothing, which is every key that types
     * nothing.
     */
    private static int layoutCodePoint(int key, int scancode) {
        String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(key, scancode);
        return name == null || name.isEmpty() ? 0 : name.codePointAt(0);
    }

    /** @return whether this window was made as a popup: a dropdown, a menu, a tooltip */
    boolean isPopupWindow() {
        return popupWindow;
    }

    /** @return whether this window is fullscreen in the way that covers the system's menu bar */
    boolean coversSystemBar() {
        return monitorFullscreen || monitorFullscreenAfterNative != null;
    }

    /** @return what this window's scene hears through, or {@code null} before a scene is bound */
    @Nullable WindowInput input() {
        return input;
    }

    /** @return this window's {@code NSWindow}, or {@code 0} off macOS or once destroyed */
    long cocoaWindow() {
        if (!MACOS || destroyed) {
            return NULL;
        }
        return org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
    }

    /**
     * @return what the XDG desktop portal calls this window: {@code x11:} and its id in hex under
     *         X11, and {@code ""} elsewhere, where GLFW gives no name the portal can take
     */
    String portalParent() {
        if (destroyed || org.lwjgl.glfw.GLFW.glfwGetPlatform() != org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11) {
            return "";
        }
        return "x11:" + Long.toHexString(org.lwjgl.glfw.GLFWNativeX11.glfwGetX11Window(handle));
    }

    @Override
    public limn.backend.Launcher launcher() {
        backend.uiRuntime().checkUiThread();
        if (launcher == null) {
            launcher = new limn.backend.Launcher(new WindowLauncher(this));
        }
        return launcher;
    }

    @Override
    public limn.backend.Printer printer() {
        backend.uiRuntime().checkUiThread();
        if (printer == null) {
            printer = new limn.backend.Printer(new WindowPrinter(this));
        }
        return printer;
    }

    @Override
    public limn.backend.FileDialogs fileDialogs() {
        backend.uiRuntime().checkUiThread();
        if (fileDialogs == null) {
            fileDialogs = new WindowFileDialogs(this, backend);
        }
        return fileDialogs;
    }

    @Override
    public void focus() {
        backend.uiRuntime().checkUiThread();
        focusNative(); // brings to front + input focus (guards destroyed/hidden)
    }

    @Override
    public void setTextInput(limn.backend.spi.TextInput input) {
        java.util.Objects.requireNonNull(input, "input");
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        // GLFW delivers characters whatever the input method does; NONE drops them here.
        charactersWanted = input != limn.backend.spi.TextInput.NONE;
        boolean enabled = input == limn.backend.spi.TextInput.COMPOSITION;
        if (MACOS) {
            // Never GLFW's switch here, which selects another input source for the whole system;
            // see MacInputContextGate. Without the gate the input method simply stays on.
            if (imeGatedView != NULL) {
                MacInputContextGate.setEnabled(imeGatedView, enabled);
            }
            return;
        }
        glfwSetInputMode(handle, GLFW_IME, enabled ? GLFW_TRUE : GLFW_FALSE);
    }

    @Override
    public void resetPreedit() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        glfwResetPreeditText(handle);
    }

    @Override
    public void setPreeditCaretRect(float x, float y, float width, float height) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        // GLFW wants the rectangle in window content-area coordinates (the same
        // space glfwGetCursorPos reports), so undo toLogical: screen = logical *
        // (effectiveScale / pixelsPerScreenCoord) == logicalToScreenFactor().
        float f = logicalToScreenFactor();
        glfwSetPreeditCursorRectangle(handle,
                Math.round(x * f), Math.round(y * f),
                Math.max(1, Math.round(width * f)), Math.max(1, Math.round(height * f)));
    }

    @Override
    public boolean isVisible() {
        backend.uiRuntime().checkUiThread();
        return !destroyed && glfwGetWindowAttrib(handle, GLFW_VISIBLE) == GLFW_TRUE;
    }

    @Override
    public boolean isClosed() {
        return destroyed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two routes, chosen by {@link NativeFullscreen#isNativeRequest}. On macOS the request that
     * names no mode is native fullscreen, sent to AppKit, which animates after this returns; see
     * {@link NativeFullscreen} for what is answered meanwhile. Every other request is GLFW's
     * fullscreen on the monitor, made before this returns. A window in one kind asked for the other
     * leaves the first: out of a fullscreen on the monitor at once, before native fullscreen is
     * sent; out of native fullscreen by its animation, with the fullscreen on the monitor made when
     * AppKit announces the window is out, because GLFW's rewrites the style mask of a window AppKit
     * may still be animating.
     *
     * <p>What losing the focus does to a fullscreen on the monitor is set on each entry, by whether
     * the request names a mode: see {@link #minimizeOnFocusLoss} and
     * {@link #stayBehindTheFocusedWindow}.
     */
    @Override
    public void enterFullscreen(int width, int height, int refreshRate) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        requestFullscreen(width, height, refreshRate);
        reportFullscreen();
    }

    /** {@link #enterFullscreen(int, int, int)} without the report, which its every route shares. */
    private void requestFullscreen(int width, int height, int refreshRate) {
        if (cocoaWindow != NULL && NativeFullscreen.isNativeRequest(MACOS, width, height, refreshRate)) {
            monitorFullscreenAfterNative = null; // the later request replaces the earlier one
            if (monitorFullscreen) {
                leaveMonitorFullscreen();
                windowed.entered(); // AppKit resizes it next, which is no answer to correct
            }
            nativeFullscreen.want(true);
            settleNativeFullscreen();
            return;
        }
        if (cocoaWindow != NULL) {
            settleNativeFullscreen(); // the person may have entered it since the loop last looked
            if (!nativeFullscreen.windowed()) {
                monitorFullscreenAfterNative = new int[] {width, height, refreshRate};
                nativeFullscreen.want(false);
                settleNativeFullscreen();
                return;
            }
        }
        enterMonitorFullscreen(width, height, refreshRate);
    }

    /** GLFW's fullscreen on the monitor the window is on, switching its mode when it must. */
    private void enterMonitorFullscreen(int width, int height, int refreshRate) {
        long monitor = monitorForWindow();
        GLFWVidMode mode = monitor == NULL ? null : glfwGetVideoMode(monitor);
        if (mode == null) {
            LOG.log(Level.WARNING, "no monitor/video mode available for fullscreen");
            return;
        }
        if (!monitorFullscreen) {
            saveWindowedGeometry(); // to restore later
        }
        windowed.entered();
        // A width or height <= 0 asks for the current one, and GLFW then keeps the monitor's mode
        // wherever its mode list holds the current mode: Windows and X11 do. macOS never gets
        // here with no mode named, because its list lacks a Retina display's current mode and
        // GLFW would switch to the nearest listed one; see NativeFullscreen.isNativeRequest.
        int fw = width > 0 ? width : mode.width();
        int fh = height > 0 ? height : mode.height();
        int fr = refreshRate > 0 ? refreshRate : mode.refreshRate();
        boolean namedMode = namesMode(width, height, refreshRate);
        minimizeOnFocusLoss(namedMode);
        glfwSetWindowMonitor(handle, monitor, 0, 0, fw, fh, fr);
        if (!namedMode) {
            stayBehindTheFocusedWindow();
        }
        monitorFullscreen = true;
        refreshFramebufferMetrics();
    }

    /**
     * Whether a request for fullscreen names a display mode, a size or a refresh rate or both: the
     * exclusive fullscreen a game asks for, rather than the application's fullscreen at the mode
     * the display is in.
     *
     * @param width       the width asked for, or &le; 0 for none
     * @param height      the height asked for, or &le; 0 for none
     * @param refreshRate the refresh rate asked for, or &le; 0 for none
     * @return whether the request names a mode
     */
    static boolean namesMode(int width, int height, int refreshRate) {
        return width > 0 || height > 0 || refreshRate > 0;
    }

    /**
     * Says whether this window, once GLFW holds it fullscreen on a monitor, is minimized when it
     * loses the focus, which is GLFW's default, read by GLFW only while the window is fullscreen.
     * Set on each entry, so a window that entered one way and then the other is never left with
     * the first one's answer; a window out of fullscreen is not affected by it.
     *
     * <p>Yes at a named mode, the fullscreen a game asks for: a switched mode is switched back
     * while the window is minimized, so the person reaches the desktop at the mode it was in. No at
     * the mode the display is in, the fullscreen an application asks for: the window then stays
     * fullscreen behind whatever took the focus, as a browser's does, and the person comes back to
     * it as they left it. Minimized there, an owner that had gone fullscreen under an open menu or
     * dialog was minimized on GNOME over X11 as the surface closed, the desktop having given the
     * focus to a window of its own.
     *
     * <p>Package-private so that a test can read the attribute back on a hidden window, which is
     * never made fullscreen.
     *
     * @param namedMode whether the fullscreen about to be entered is at a named mode
     */
    void minimizeOnFocusLoss(boolean namedMode) {
        glfwSetWindowAttrib(handle, GLFW_AUTO_ICONIFY, namedMode ? GLFW_TRUE : GLFW_FALSE);
    }

    /**
     * On Windows, takes back the always-on-top that GLFW gives every window it makes fullscreen,
     * for the fullscreen at the mode the display is in. That window is not minimized when it loses
     * the focus, and in the always-on-top band it would stay over the window the person switched
     * to, which would get the keyboard while hidden behind it. Out of the band, the window the
     * person switches to comes in front, as it does over a browser's fullscreen. A named mode keeps
     * the band: that window is minimized when it loses the focus. Nothing elsewhere: on X11 the
     * window manager stacks a fullscreen window, and GLFW puts it in no band of its own.
     */
    private void stayBehindTheFocusedWindow() {
        if (glfwGetPlatform() != GLFW_PLATFORM_WIN32) {
            return;
        }
        long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
        if (hwnd != NULL) {
            org.lwjgl.system.windows.User32.SetWindowPos(null, hwnd,
                    org.lwjgl.system.windows.User32.HWND_NOTOPMOST, 0, 0, 0, 0,
                    org.lwjgl.system.windows.User32.SWP_NOMOVE
                            | org.lwjgl.system.windows.User32.SWP_NOSIZE
                            | org.lwjgl.system.windows.User32.SWP_NOACTIVATE);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Native fullscreen is left by its animation, after this returns, whether this window or
     * the person entered it; a fullscreen on the monitor is left before this returns, and the
     * window manager's answer, which may come after, is watched for half a second: see
     * {@link #settleWindowedGeometry}.
     */
    @Override
    public void exitFullscreen() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        monitorFullscreenAfterNative = null;
        if (monitorFullscreen) {
            leaveMonitorFullscreen();
        }
        if (cocoaWindow != NULL) {
            nativeFullscreen.want(false);
            settleNativeFullscreen();
        }
        reportFullscreen();
    }

    private void leaveMonitorFullscreen() {
        glfwSetWindowMonitor(handle, NULL, windowed.x(), windowed.y(),
                Math.max(1, windowed.width()), Math.max(1, windowed.height()), GLFW_DONT_CARE);
        monitorFullscreen = false;
        watchTheAnswersToLeaving();
        refreshFramebufferMetrics();
    }

    /** Remembers the window's position, size and maximized state now, in screen coordinates. */
    private void saveWindowedGeometry() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            readWindowPos(x, y);
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            windowed.save(x.get(0), y.get(0), w.get(0), h.get(0),
                    glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE);
        }
    }

    /**
     * After the request to leave a fullscreen on the monitor: unmaximizes a window that was not
     * maximized before and reports it now, and has the loop watch the window manager's answers,
     * so that a window given back anywhere but where it was is asked once more for its position
     * and size; see {@link WindowedGeometry} for what GNOME over X11 gave back, and
     * {@link #settleWindowedGeometry} for the asking.
     */
    private void watchTheAnswersToLeaving() {
        boolean maximizedNow = glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE;
        if (windowed.left(maximizedNow, System.nanoTime())) {
            glfwRestoreWindow(handle);
        }
    }

    /**
     * Acts on the window manager's answer to a window that left a fullscreen on the monitor: asks
     * once to unmaximize it, or once for its position and size again, as {@link WindowedGeometry}
     * says. Run by the loop after every poll, where the size and position callbacks that carry the
     * answer arrive; reads the window only while answers are watched, which is for half a second
     * after leaving. Asked of GLFW directly rather than through {@link #setSize}, which draws a
     * frame at once, from a loop turn that is about to draw one anyway.
     */
    void settleWindowedGeometry() {
        if (destroyed || !windowed.watching() || isFullscreen()) {
            return;
        }
        int x;
        int y;
        int w;
        int h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            readWindowPos(px, py);
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            glfwGetWindowSize(handle, pw, ph);
            x = px.get(0);
            y = py.get(0);
            w = pw.get(0);
            h = ph.get(0);
        }
        boolean positions = supportsAbsolutePositioning();
        switch (windowed.settle(System.nanoTime(), x, y, w, h,
                glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE, positions)) {
            case RESTORE -> glfwRestoreWindow(handle);
            case APPLY -> {
                if (positions) {
                    glfwSetWindowPos(handle, windowed.x(), windowed.y());
                }
                glfwSetWindowSize(handle, Math.max(1, windowed.width()),
                        Math.max(1, windowed.height()));
                frameRequested = true;
            }
            case NONE -> {
            }
        }
    }

    /**
     * Plays a leaving on a window that never went fullscreen, which a test must never make one: the
     * geometry it has now is saved as if it had entered, and the answers are watched as after
     * {@link #leaveMonitorFullscreen}, whose request to GLFW is the one step left out.
     */
    void leftMonitorFullscreenForTest() {
        saveWindowedGeometry();
        watchTheAnswersToLeaving();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Native fullscreen answers where the window is going, not where its animation has got to:
     * see {@link NativeFullscreen#fullscreen}. A fullscreen on the monitor waiting for native
     * fullscreen to be left counts as fullscreen already, which the window is throughout.
     */
    @Override
    public boolean isFullscreen() {
        if (monitorFullscreen || monitorFullscreenAfterNative != null) {
            return true;
        }
        return cocoaWindow != NULL && !destroyed && nativeFullscreen.fullscreen(System.nanoTime(),
                MacFullscreen.isFullscreen(cocoaWindow));
    }

    /**
     * Brings the native fullscreen record up to date and acts on it: sends the toggle a request
     * made during a transition was waiting for, lends and gives back the collection behaviour,
     * and makes a fullscreen on the monitor that was waiting for the window to leave native
     * fullscreen. Run by the loop after every poll, which is where AppKit's notifications arrive,
     * and by every request. A no-op outside macOS.
     */
    void settleNativeFullscreen() {
        if (cocoaWindow == NULL || destroyed) {
            return;
        }
        NativeFullscreen.Toggle toggle = nativeFullscreen.settle(System.nanoTime(),
                MacFullscreen.isFullscreen(cocoaWindow));
        if (toggle == NativeFullscreen.Toggle.ENTER && lentCollectionBehavior < 0) {
            lentCollectionBehavior = MacFullscreen.allowFullscreen(cocoaWindow);
        }
        if (toggle == NativeFullscreen.Toggle.ENTER && !lentResizable) {
            lentResizable = MacFullscreen.allowResize(cocoaWindow);
        }
        if (toggle != NativeFullscreen.Toggle.NONE) {
            MacFullscreen.toggleSoon(cocoaWindow);
        }
        if (nativeFullscreen.windowed()) {
            if (lentCollectionBehavior >= 0) {
                MacFullscreen.restoreCollectionBehavior(cocoaWindow, lentCollectionBehavior);
                lentCollectionBehavior = -1;
            }
            if (lentResizable) {
                MacFullscreen.restoreResizable(cocoaWindow);
                lentResizable = false;
            }
            int[] waiting = monitorFullscreenAfterNative;
            if (waiting != null) {
                monitorFullscreenAfterNative = null;
                enterMonitorFullscreen(waiting[0], waiting[1], waiting[2]);
            }
        } else if (monitorFullscreenAfterNative != null && nativeFullscreen.settledInFullscreen()) {
            // Leaving did not complete, or the person went back in: the window stays where it is,
            // and the request that needed it out goes with it.
            LOG.log(Level.WARNING, "native fullscreen was not left, so the fullscreen on the "
                    + "monitor asked for meanwhile is dropped");
            monitorFullscreenAfterNative = null;
        }
        // The loop's turn is where an answer changes that no call and no notification changed: a
        // transition given up at its deadline, a style mask believed over the record.
        reportFullscreen();
    }

    /**
     * Tells the input that {@link #isFullscreen()} has changed its answer, once per change and
     * whichever route changed it: {@link #enterFullscreen(int, int, int)} and
     * {@link #exitFullscreen()} on every platform, AppKit's notifications of the person's green
     * button, Control-Command-F or menu item, and the loop's settling of a transition that never
     * finished. Comparing against the last answer told, rather than telling at each route, is what
     * keeps a request that changes nothing, or a transition announced twice (asked for, then
     * begun), from being told as a change.
     *
     * <p>Told whether or not a modal blocks this window: the dialog that blocks it is one of the
     * surfaces that has to move. A frame is asked for, so that a loop about to park dispatches the
     * batch the change is in rather than holding it until the next input.
     */
    private void reportFullscreen() {
        boolean now = isFullscreen();
        if (now == reportedFullscreen) {
            return;
        }
        reportedFullscreen = now;
        frameRequested = true;
        if (input != null) {
            input.windowFullscreenChanged(now);
        }
    }

    /**
     * Whether this window's close must wait: AppKit is animating it into or out of native
     * fullscreen, and a window closed during that animation is left as a zombie that neither shows
     * nor goes, as Chromium found. The wait ends when AppKit announces the end, or at the record's
     * deadline.
     */
    boolean closeWaitsForFullscreen() {
        return cocoaWindow != NULL && !destroyed && nativeFullscreen.animating(System.nanoTime());
    }

    /**
     * AppKit's word on a native fullscreen transition, the person's or this window's. Called from
     * inside the event pump, so it only records: what to do about it is the loop's, after the poll.
     * Telling the input records too, as a key callback's does, since the scene queues the change
     * for the end of the batch; it is told here, in its place among the poll's other input, rather
     * than left for the loop's settling after the poll. Package-private so that a test can deliver
     * AppKit's four events to a window that never goes fullscreen.
     */
    void nativeFullscreenChanged(MacFullscreen.Event event) {
        long now = System.nanoTime();
        switch (event) {
            case WILL_ENTER -> nativeFullscreen.willChange(true, now);
            case WILL_EXIT -> nativeFullscreen.willChange(false, now);
            case DID_ENTER -> nativeFullscreen.didChange(true);
            case DID_EXIT -> nativeFullscreen.didChange(false);
        }
        reportFullscreen();
        frameRequested = true;
        // The notification may arrive while the loop is parked in the pump, and it is no event the
        // pump returns on: without a wake, a request waiting for this transition would wait for
        // the next input.
        backend.wakeLoop();
    }

    /** Picks the monitor whose bounds contain the window centre, else the primary. */
    private long monitorForWindow() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer px = stack.mallocInt(1);
            IntBuffer py = stack.mallocInt(1);
            readWindowPos(px, py);
            IntBuffer sw = stack.mallocInt(1);
            IntBuffer sh = stack.mallocInt(1);
            glfwGetWindowSize(handle, sw, sh);
            int cx = px.get(0) + sw.get(0) / 2;
            int cy = py.get(0) + sh.get(0) / 2;
            PointerBuffer monitors = glfwGetMonitors();
            if (monitors != null) {
                IntBuffer mx = stack.mallocInt(1);
                IntBuffer my = stack.mallocInt(1);
                for (int i = 0; i < monitors.limit(); i++) {
                    long monitor = monitors.get(i);
                    GLFWVidMode mode = glfwGetVideoMode(monitor);
                    if (mode == null) {
                        continue;
                    }
                    glfwGetMonitorPos(monitor, mx, my);
                    int left = mx.get(0);
                    int top = my.get(0);
                    if (cx >= left && cx < left + mode.width()
                            && cy >= top && cy < top + mode.height()) {
                        return monitor;
                    }
                }
            }
            return glfwGetPrimaryMonitor();
        }
    }

    /** Re-reads framebuffer size/content-scale after a monitor or size change. */
    private void refreshFramebufferMetrics() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            framebufferWidth = w.get(0);
            framebufferHeight = h.get(0);
            FloatBuffer scaleX = stack.mallocFloat(1);
            FloatBuffer scaleY = stack.mallocFloat(1);
            glfwGetWindowContentScale(handle, scaleX, scaleY);
            contentScale = scaleX.get(0);
        }
        updatePixelsPerScreenCoord();
        if (input != null) {
            input.windowResized(logicalWidth(), logicalHeight());
        }
        frameRequested = true;
    }

    @Override
    public boolean isModalBlocked() {
        return !destroyed && backend.isInputBlocked(this);
    }

    @Override
    public boolean isModal() {
        return !destroyed && backend.isModal(this);
    }

    @Override
    public void registerChildPopup(NativeWindow child, PopupKind kind) {
        backend.uiRuntime().checkUiThread();
        childPopups.add(child, kind);
    }

    @Override
    public void unregisterChildPopup(NativeWindow child) {
        backend.uiRuntime().checkUiThread();
        childPopups.remove(child);
    }

    @Override
    public void setFrameCallback(FrameCallback callback) {
        backend.uiRuntime().checkUiThread();
        this.frameCallback = callback;
        this.frameRequested = true;
    }

    @Override
    public void setInput(WindowInput newInput) {
        backend.uiRuntime().checkUiThread();
        this.input = newInput;
    }

    @Override
    public limn.backend.Backend backend() {
        return backend;
    }

    @Override
    public limn.backend.Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public int screenX() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            readWindowPos(x, y);
            return x.get(0);
        }
    }

    @Override
    public int screenY() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            readWindowPos(x, y);
            return y.get(0);
        }
    }

    @Override
    public void setScreenPosition(int x, int y) {
        backend.uiRuntime().checkUiThread();
        if (!supportsAbsolutePositioning() || destroyed) {
            return; // the desktop places this window; asking again only logs an error
        }
        glfwSetWindowPos(handle, x, y);
    }

    /**
     * Not cached in a field: the answer is a property of the process's windowing platform, which
     * is fixed once {@code glfwInit} has run, so every window of this backend gives the same one
     * and a per-window copy would only be a second place for it to be wrong.
     */
    @Override
    public boolean supportsAbsolutePositioning() {
        return LwjglBackend.canPositionWindows();
    }

    /**
     * Reads this window's screen position, answering {@code 0, 0} where the platform has no such
     * thing to report.
     *
     * <p>Every read goes through here rather than calling GLFW directly, for one reason: on
     * Wayland {@code glfwGetWindowPos} emits {@code GLFW_FEATURE_UNAVAILABLE}, the error callback
     * prints it, and it prints on window creation, on every monitor lookup and on every fullscreen
     * transition, a stream of platform errors on a session where nothing is wrong. It also leaves
     * the caller's buffer untouched, and {@code MemoryStack} hands out whatever was in that memory,
     * so a caller that ignored the error would read a number rather than a zero.
     *
     * <p>A closed window answers where it last was, which the position callback kept.
     */
    private void readWindowPos(IntBuffer px, IntBuffer py) {
        if (!supportsAbsolutePositioning()) {
            px.put(0, 0);
            py.put(0, 0);
            return;
        }
        if (destroyed) {
            px.put(0, lastScreenX);
            py.put(0, lastScreenY);
            return;
        }
        glfwGetWindowPos(handle, px, py);
    }

    @Override
    public void setMousePassthrough(boolean passthrough) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(handle,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_PASSTHROUGH, passthrough ? GLFW_TRUE : GLFW_FALSE);
    }

    // The cursor position the last poll read, in screen coordinates: what a closed window, which
    // has nothing left to poll, answers with.
    private double polledCursorX;
    private double polledCursorY;

    @Override
    public float cursorX() {
        backend.uiRuntime().checkUiThread();
        pollCursor(); // polled: live even unfocused/passthrough
        return toLogical(polledCursorX);
    }

    @Override
    public float cursorY() {
        backend.uiRuntime().checkUiThread();
        pollCursor();
        return toLogical(polledCursorY);
    }

    private void pollCursor() {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(handle, x, y);
            polledCursorX = x.get(0);
            polledCursorY = y.get(0);
        }
    }

    private static final boolean MACOS = Platform.current().isMacOs();
    private static final boolean WINDOWS = Platform.current().os() == Platform.Os.WINDOWS;
    private static final boolean LINUX = Platform.current().os() == Platform.Os.LINUX;

    /** {@code NSEventTypeScrollWheel}. */
    private static final long NS_SCROLL_WHEEL = 22;
    /** {@code NSEventPhaseBegan} and {@code NSEventPhaseMayBegin}: fingers down on the surface. */
    private static final long NS_PHASE_BEGAN = 1;
    private static final long NS_PHASE_MAY_BEGIN = 32;

    /**
     * Whether the scroll being delivered begins a gesture, read from the event AppKit is
     * dispatching: GLFW calls back from inside it and passes on no phase. A trackpad or a Magic
     * Mouse marks the first event after the fingers come down, while the inertia of the swipe
     * before may still be arriving with no pause a clock could see; a wheel that clicks marks
     * nothing and is told apart by its pauses. Elsewhere there is nothing to read.
     */
    private static boolean scrollGestureBegins() {
        if (!MACOS) {
            return false;
        }
        long event = ObjC.msg(ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication"), "currentEvent");
        if (event == 0 || ObjC.msg(event, "type") != NS_SCROLL_WHEEL) {
            return false;
        }
        long phase = ObjC.msg(event, "phase");
        return (phase & (NS_PHASE_BEGAN | NS_PHASE_MAY_BEGIN)) != 0;
    }
    // NSFloatingWindowLevel (GLFW's always-on-top) sits at 3, below the Dock
    // (kCGDockWindowLevel 20) and the menu bar (kCGMainMenuWindowLevel 24). To
    // cover them we jump to NSPopUpMenuWindowLevel (101): clear above both, still
    // well under NSScreenSaverWindowLevel (1000) and system security prompts.
    private static final long CHROME_OVERLAY_LEVEL = 101;
    private static final long FLOATING_LEVEL = 3;

    @Override
    public void setAboveSystemChrome(boolean above) {
        backend.uiRuntime().checkUiThread();
        if (!MACOS || destroyed) {
            // On Windows/X11 a topmost window sized to the full monitor already
            // sits over the taskbar/panel; nothing extra to do.
            return;
        }
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
        if (nsWindow == NULL) {
            return;
        }
        // [nsWindow setLevel:level], a one-shot ObjC message. NSInteger is pointer-sized, so
        // it rides the pointer slot.
        ObjC.msgVoid(nsWindow, "setLevel:", above ? CHROME_OVERLAY_LEVEL : FLOATING_LEVEL);
    }

    @Override
    public limn.backend.Display display() {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return null; // a closed window sits on no display
        }
        long monitor = monitorForWindow();
        return monitor == NULL ? null : backend.displayFor(monitor);
    }

    @Override
    public float logicalToScreenFactor() {
        // Must use the EFFECTIVE scale (honoring an override), which is the same
        // scale logical coordinates are defined against; otherwise popups
        // mis-position in forced-scale (screenshot) mode.
        return effectiveScale() / (pixelsPerScreenCoord <= 0 ? 1 : pixelsPerScreenCoord);
    }

    private limn.backend.lwjgl.transfer.@org.jspecify.annotations.Nullable MacDrag macDrag;
    private limn.backend.lwjgl.transfer.@org.jspecify.annotations.Nullable WindowsDrag windowsDrag;
    private limn.backend.lwjgl.transfer.@org.jspecify.annotations.Nullable WindowDrag linuxDrag;
    /** What forgets this window's pinches under X11, or {@code null}. */
    private @org.jspecify.annotations.Nullable Runnable x11Pinch;
    /** Whether {@link #linuxDrag} is X11's, whose proxy some sources go round. */
    private boolean x11Drag;

    /** The pointer in logical points, x then y, and the content's logical height: what a drag is placed by. */
    private float[] pointerForDrag() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.DoubleBuffer cx = stack.mallocDouble(1);
            java.nio.DoubleBuffer cy = stack.mallocDouble(1);
            org.lwjgl.glfw.GLFW.glfwGetCursorPos(handle, cx, cy);
            return new float[] {toLogical(cx.get(0)), toLogical(cy.get(0)), logicalHeight()};
        }
    }

    @Override
    public boolean supportsDrag() {
        return (macDrag != null || windowsDrag != null || linuxDrag != null) && !destroyed;
    }

    @Override
    public boolean startDrag(limn.backend.spi.DragRequest request) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return false;
        }
        if (windowsDrag != null) {
            return windowsDrag.start(request);
        }
        if (linuxDrag != null) {
            return linuxDrag.start(request);
        }
        return macDrag != null && macDrag.start(request);
    }

    @Override
    public void dragAnswerChanged(limn.transfer.@org.jspecify.annotations.Nullable DropAction action) {
        backend.uiRuntime().checkUiThread();
        // X11 and Wayland ask the window only as the pointer moves, so the answer goes to them
        // now. OLE calls DragOver and AppKit draggingUpdated again while the pointer rests, and
        // hear it at their next call.
        if (linuxDrag != null && !destroyed) {
            linuxDrag.answerChanged(action);
        }
    }

    /** Paints now, from inside a loop the system runs while it drives a drag. */
    private void paintDuringDrag() {
        frameRequested = true;
        renderNow(true);
    }

    /**
     * Between two events of a drag the system drives: the frame the scene asked for, if any (a
     * row sliding), and whether it asks for another; or, with none asked, the same frame again
     * so that both buffers hold it, which the loop does when idle and does not turn here. A mark
     * where a drop would land stayed behind without it.
     */
    private boolean animateDuringDrag() {
        if (destroyed) {
            return false;
        }
        if (!frameRequested) {
            syncBuffers(true);
            return false;
        }
        return renderNow(true) && frameRequested;
    }

    @Override
    public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) {
        backend.uiRuntime().checkUiThread();
        renderer.captureFramebuffer(sink);
        requestFrame();
    }

    @Override
    public void setContentScaleListener(ContentScaleListener listener) {
        backend.uiRuntime().checkUiThread();
        this.contentScaleListener = listener;
    }

    /**
     * {@inheritDoc}
     *
     * <p>It wakes the loop when the loop is parked, for the reason a UI-thread post does: this
     * sets a flag the loop reads on its way round, and a caller standing inside a platform
     * callback that the parked pump is dispatching produces no event for that pump to return on.
     * A frame asked for from inside a describe pass would otherwise wait for unrelated input.
     */
    @Override
    public void requestFrame() {
        backend.uiRuntime().checkUiThread();
        frameRequested = true;
        backend.wakeLoop();
    }

    @Override
    public void requestClose() {
        // Hop to the UI thread: glfwSetWindowShouldClose is documented any-thread,
        // but this window may be destroyed concurrently by the loop; the post
        // serializes the check against destruction (and wakes the loop for free).
        backend.uiRuntime().post(() -> {
            if (!destroyed) {
                glfwSetWindowShouldClose(handle, true);
            }
        });
    }

    @Override
    public void close() {
        requestClose();
    }
}
