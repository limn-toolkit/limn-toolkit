package limn.backend.lwjgl;

import limn.backend.Cursor;
import limn.backend.FrameCallback;
import limn.backend.FrameInfo;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.WindowInput;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWPreeditCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

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
import static org.lwjgl.glfw.GLFW.GLFW_IME;
import static org.lwjgl.glfw.GLFW.GLFW_RAW_MOUSE_MOTION;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_SCALE_TO_MONITOR;
import static org.lwjgl.glfw.GLFW.GLFW_TRANSPARENT_FRAMEBUFFER;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwFocusWindow;
import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMonitors;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwSetWindowMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwHideWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwRequestWindowAttention;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursor;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;
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
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowOpacity;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSizeLimits;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW-backed {@link NativeWindow} with an OpenGL 3.3 core (forward-compatible)
 * context. Renders from the loop when dirty, and also straight from the GLFW
 * refresh/framebuffer-size callbacks so the content stays live during native
 * resize drags (where {@code glfwPollEvents} blocks on Windows/macOS).
 */
final class LwjglWindow implements NativeWindow {

    private static final System.Logger LOG = System.getLogger(LwjglWindow.class.getName());

    private final LwjglBackend backend;
    private final long handle;
    private final GLCapabilities glCapabilities;
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
    private java.util.function.BooleanSupplier closeRequestHandler;
    private WindowInput input;
    private int framebufferWidth;
    private int framebufferHeight;
    private float contentScale;
    private float contentScaleOverride;
    private float pixelsPerScreenCoord = 1f;
    private boolean frameRequested = true;
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
    private boolean fullscreen;
    private int windowedX;
    private int windowedY;
    private int windowedW;
    private int windowedH;

    LwjglWindow(LwjglBackend backend, WindowConfig config) {
        this.backend = backend;
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

        handle = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
        if (handle == NULL) {
            // GLFW's own description instead of a guess: the reason is usually
            // specific ("WGL: Failed to create OpenGL context"), and naming the
            // windowing platform matters because it is picked at run time: the
            // same binary reaches X11 or Wayland depending on the session.
            throw new IllegalStateException("glfwCreateWindow failed on the "
                    + GraphicsProbe.platformName() + " platform: " + GraphicsProbe.lastError()
                    + "; Limn needs an OpenGL 3.3 core context");
        }
        glfwMakeContextCurrent(handle);
        glCapabilities = GL.createCapabilities();
        glfwSwapInterval(1); // vsync

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
            framebufferWidth = width;
            framebufferHeight = height;
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
                input.mouseButton(button, action == GLFW_PRESS, mods,
                        toLogical(eventCursorX()), toLogical(eventCursorY()));
            }
        });
        glfwSetScrollCallback(handle, (win, dx, dy) -> {
            if (input != null && !inputBlocked()) {
                input.scrolled((float) dx, (float) dy,
                        toLogical(eventCursorX()), toLogical(eventCursorY()));
            }
        });
        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            if (input != null && !inputBlocked()) {
                input.keyEvent(key, action != GLFW_RELEASE, action == GLFW_REPEAT, mods);
            }
        });
        glfwSetCharCallback(handle, (win, codepoint) -> {
            if (input != null && !inputBlocked()) {
                input.charTyped(codepoint);
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
                input.preeditChanged(text, blocks, focusedBlock, caret);
                frameRequested = true;
            }
        });
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
        glfwSetWindowPosCallback(handle, (win, x, y) -> windowMovedTo(x, y));
        glfwSetWindowContentScaleCallback(handle, (win, scaleX, scaleY) -> {
            contentScale = scaleX;
            LOG.log(Level.INFO, "Window ''{0}'' moved to a monitor with content scale {1}", title, scaleX);
            if (contentScaleListener != null) {
                contentScaleListener.onContentScaleChanged(scaleX);
            }
            frameRequested = true;
        });
        glfwSetWindowFocusCallback(handle, (win, focused) -> {
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
            if (input != null && !inputBlocked()) {
                java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    paths.add(java.nio.file.Path.of(GLFWDropCallback.getName(names, i)));
                }
                input.filesDropped(paths);
            }
        });
        glfwSetWindowCloseCallback(handle, win -> {
            // Fires only for USER close attempts (close button / Alt-F4 / Cmd-W);
            // requestClose() sets the flag directly and never runs this callback,
            // so a programmatic close always bypasses the veto.
            //
            // Deliberately NOT gated on inputBlocked(). A window with a modal open
            // is not clickable, but it must stay closable: "quit" is the one thing
            // an application still has to be able to ask about, and an app whose
            // answer is a second dialog gets one (see ModalStack, which is what
            // makes the pair of them answerable). Refusing here instead turns the
            // close button into a beep with no dialog and nothing to act on, which
            // is a worse answer than the deadlock it was avoiding.
            if (closeRequestHandler != null && !closeRequestHandler.getAsBoolean()) {
                glfwSetWindowShouldClose(handle, false);
            }
        });

        renderer = new GlRenderer(backend.fontStore());
        clipboard = new limn.backend.Clipboard() {
            @Override
            public String get() {
                // GLFW clipboard access is main-thread only; this is the one
                // GLFW-touching API the toolkit hands applications directly.
                backend.uiRuntime().checkUiThread();
                String value = glfwGetClipboardString(handle);
                return value == null ? "" : value;
            }

            @Override
            public void set(String text) {
                backend.uiRuntime().checkUiThread();
                glfwSetClipboardString(handle, text == null ? "" : text);
            }
        };
    }

    long handle() {
        return handle;
    }

    /** @return this window's resident texture stats (glyph atlas + image cache). */
    limn.backend.RenderStats renderStats() {
        return renderer.stats();
    }

    /**
     * @return what this window's context reports about itself. Makes the context
     *         current, so call it between frames and not inside one.
     */
    limn.backend.GraphicsInfo readGraphicsInfo() {
        glfwMakeContextCurrent(handle);
        GL.setCapabilities(glCapabilities);
        return GraphicsProbe.read(handle, glCapabilities);
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
            glfwFocusWindow(handle);
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
        org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(handle, GLFW_FLOATING, want ? GLFW_TRUE : GLFW_FALSE);
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
        int dx = x - lastScreenX;
        int dy = y - lastScreenY;
        lastScreenX = x;
        lastScreenY = y;
        childPopups.moveBy(dx, dy);
    }

    boolean frameRequested() {
        return frameRequested;
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
            glfwMakeContextCurrent(handle);
            // LWJGL caps are thread-local per context: re-bind when hopping windows.
            GL.setCapabilities(glCapabilities);
            // One window per loop iteration paces on vblank; the others swap
            // free into the same one (see renderNow). The interval is
            // per-context state, so re-assert it every frame.
            glfwSwapInterval(vsync ? 1 : 0);
            glViewport(0, 0, framebufferWidth, framebufferHeight);
            frameRequested = false; // callback may re-request for the next frame
            float scale = effectiveScale();
            renderer.beginFrame(framebufferWidth, framebufferHeight, scale, rePresent);
            try {
                frameCallback.onFrame(renderer,
                        new FrameInfo(framebufferWidth, framebufferHeight, scale, rePresent,
                                renderer.takeGpuFrameMs()));
            } finally {
                // User code may have switched GL contexts (posting is the
                // sanctioned path for window creation, but stay safe).
                glfwMakeContextCurrent(handle);
                GL.setCapabilities(glCapabilities);
                renderer.endFrame();
            }
            glfwSwapBuffers(handle);
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
        // GL resources (VBO/shader) must die on their own context.
        glfwMakeContextCurrent(handle);
        GL.setCapabilities(glCapabilities);
        renderer.dispose();
        // glfwFreeCallbacks predates the preedit patch and does not free this
        // callback; its native upcall stub pins this window (and its scene)
        // through a JNI global ref unless freed explicitly.
        GLFWPreeditCallback preeditCallback = glfwSetPreeditCallback(handle, null);
        if (preeditCallback != null) {
            preeditCallback.free();
        }
        Callbacks.glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public void setTitle(String newTitle) {
        backend.uiRuntime().checkUiThread();
        this.title = Objects.requireNonNull(newTitle, "title");
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
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive, got " + width + "x" + height);
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
        // Same logical → screen conversion as setSize (GLFW limits are screen coords).
        glfwSetWindowSizeLimits(handle,
                toScreenOrDontCare(minWidth), toScreenOrDontCare(minHeight),
                toScreenOrDontCare(maxWidth), toScreenOrDontCare(maxHeight));
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
        if (Platform.get() == Platform.MACOSX) {
            return; // macOS windows have no per-window icon (the app bundle's is used)
        }
        // Icon pixels go on the heap, not the MemoryStack: a 256px RGBA icon
        // (256 KB) would overflow it. GLFW copies the data before returning.
        java.util.List<ByteBuffer> pixelBuffers = new java.util.ArrayList<>(icons.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer images = GLFWImage.malloc(icons.length, stack);
            for (int i = 0; i < icons.length; i++) {
                limn.graphics.Image icon = icons[i];
                ByteBuffer pixels = MemoryUtil.memAlloc(icon.pixels().length);
                pixels.put(icon.pixels()).flip();
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
    public void setCloseRequestHandler(java.util.function.BooleanSupplier handler) {
        backend.uiRuntime().checkUiThread();
        this.closeRequestHandler = handler;
    }

    @Override
    public void show() {
        backend.uiRuntime().checkUiThread();
        glfwShowWindow(handle);
    }

    @Override
    public void hide() {
        backend.uiRuntime().checkUiThread();
        glfwHideWindow(handle);
    }

    @Override
    public void setOpacity(float value) {
        backend.uiRuntime().checkUiThread();
        float clamped = Math.max(0f, Math.min(1f, value));
        if (destroyed || clamped == opacity) {
            return;
        }
        opacity = clamped;
        // Compositor-level, whole-window alpha (frame + content). Silently
        // unavailable on some platforms (e.g. Wayland); GLFW just no-ops there.
        glfwSetWindowOpacity(handle, clamped);
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

    @Override
    public void setCursor(Cursor newCursor) {
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

    @Override
    public void setImageCursor(limn.backend.ImageCursor newCursor) {
        backend.uiRuntime().checkUiThread();
        if (destroyed || java.util.Objects.equals(newCursor, imageCursor)) {
            return;
        }
        imageCursor = newCursor;
        if (newCursor != null) {
            glfwSetCursor(handle, backend.imageCursorHandle(newCursor));
        } else {
            // Image cleared: fall back to the recorded standard shape.
            glfwSetCursor(handle, cursor == Cursor.DEFAULT ? NULL : backend.cursorHandle(cursor));
        }
    }

    @Override
    public void setPointerMode(limn.backend.PointerMode mode) {
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

    @Override
    public void focus() {
        backend.uiRuntime().checkUiThread();
        focusNative(); // brings to front + input focus (guards destroyed/hidden)
    }

    @Override
    public void setImeEnabled(boolean enabled) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
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

    @Override
    public void enterFullscreen(int width, int height, int refreshRate) {
        backend.uiRuntime().checkUiThread();
        if (destroyed) {
            return;
        }
        long monitor = monitorForWindow();
        GLFWVidMode mode = monitor == NULL ? null : glfwGetVideoMode(monitor);
        if (mode == null) {
            LOG.log(Level.WARNING, "no monitor/video mode available for fullscreen");
            return;
        }
        if (!fullscreen) {
            // Remember the windowed geometry (screen coords) to restore later.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                readWindowPos(x, y);
                windowedX = x.get(0);
                windowedY = y.get(0);
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetWindowSize(handle, w, h);
                windowedW = w.get(0);
                windowedH = h.get(0);
            }
        }
        // width/height <= 0 keeps the monitor's current resolution (no mode switch).
        int fw = width > 0 ? width : mode.width();
        int fh = height > 0 ? height : mode.height();
        int fr = refreshRate > 0 ? refreshRate : mode.refreshRate();
        glfwSetWindowMonitor(handle, monitor, 0, 0, fw, fh, fr);
        fullscreen = true;
        refreshFramebufferMetrics();
    }

    @Override
    public void exitFullscreen() {
        backend.uiRuntime().checkUiThread();
        if (destroyed || !fullscreen) {
            return;
        }
        glfwSetWindowMonitor(handle, NULL, windowedX, windowedY,
                Math.max(1, windowedW), Math.max(1, windowedH), GLFW_DONT_CARE);
        fullscreen = false;
        refreshFramebufferMetrics();
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
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
        if (!supportsAbsolutePositioning()) {
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
     */
    private void readWindowPos(IntBuffer px, IntBuffer py) {
        if (!supportsAbsolutePositioning()) {
            px.put(0, 0);
            py.put(0, 0);
            return;
        }
        glfwGetWindowPos(handle, px, py);
    }

    @Override
    public void setMousePassthrough(boolean passthrough) {
        backend.uiRuntime().checkUiThread();
        org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(handle,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_PASSTHROUGH, passthrough ? GLFW_TRUE : GLFW_FALSE);
    }

    @Override
    public float cursorX() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(handle, x, y); // polled: live even unfocused/passthrough
            return toLogical(x.get(0));
        }
    }

    @Override
    public float cursorY() {
        backend.uiRuntime().checkUiThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            glfwGetCursorPos(handle, x, y);
            return toLogical(y.get(0));
        }
    }

    private static final boolean MACOS =
            System.getProperty("os.name", "").toLowerCase().contains("mac");
    // NSFloatingWindowLevel (GLFW's always-on-top) sits at 3, below the Dock
    // (kCGDockWindowLevel 20) and the menu bar (kCGMainMenuWindowLevel 24). To
    // cover them we jump to NSPopUpMenuWindowLevel (101): clear above both, still
    // well under NSScreenSaverWindowLevel (1000) and system security prompts.
    private static final long CHROME_OVERLAY_LEVEL = 101;
    private static final long FLOATING_LEVEL = 3;

    @Override
    public void setAboveSystemChrome(boolean above) {
        backend.uiRuntime().checkUiThread();
        if (!MACOS) {
            // On Windows/X11 a topmost window sized to the full monitor already
            // sits over the taskbar/panel; nothing extra to do.
            return;
        }
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(handle);
        if (nsWindow == NULL) {
            return;
        }
        // [nsWindow setLevel:level], a one-shot ObjC message; libobjc's
        // objc_msgSend takes (id self, SEL op, NSInteger level) and returns void.
        // NSInteger is pointer-sized, so it rides the third pointer slot.
        long objcMsgSend = org.lwjgl.system.macosx.ObjCRuntime.getLibrary()
                .getFunctionAddress("objc_msgSend");
        long setLevel = org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("setLevel:");
        org.lwjgl.system.JNI.invokePPPV(nsWindow, setLevel,
                above ? CHROME_OVERLAY_LEVEL : FLOATING_LEVEL, objcMsgSend);
    }

    @Override
    public limn.backend.Display display() {
        backend.uiRuntime().checkUiThread();
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

    @Override
    public void requestFrame() {
        backend.uiRuntime().checkUiThread();
        frameRequested = true;
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
