package limn.demo.a11y;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.Display;
import limn.backend.FrameCallback;
import limn.backend.FrameInfo;
import limn.backend.GpuRenderer;
import limn.backend.NativeWindow;
import limn.backend.Resolution;
import limn.backend.ScreenRect;
import limn.backend.WindowInput;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.graphics.TextRuler;

import java.util.List;
import java.util.function.Consumer;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;

/**
 * A window with no platform behind it, whose accessibility bridge keeps the last tree it was
 * handed: what a demo scene is bound to so that its accessible tree can be read with no GL, no
 * fonts and no desktop.
 *
 * <p>A frame here is the scene's own frame callback, the one {@code Scene#bind} installs, driven
 * by hand: what a real backend does once per vertical blank this does when a test asks. That is
 * the whole publish path a screen reader would see, and not a shortcut around it.
 */
public final class HeadlessWindow implements NativeWindow {

    /**
     * Ten points per code point, ascent eight, descent two, line twelve: the fixed monospace
     * ruler every headless layout in this repository measures against, so that a transcript
     * taken on one machine is the transcript taken on every other.
     */
    public static final TextRuler RULER = TestRulers.FIXED;

    /**
     * A bridge that listens, keeps the newest tree and every event it was handed, and does
     * nothing else.
     */
    public static final class CapturingBridge implements AccessibilityBridge {
        private volatile AccessibleTree tree = AccessibleTree.EMPTY;

        /** Every event handed over, in order, across every publish. */
        public final List<AccessibleEvent> events = new java.util.ArrayList<>();

        /** How many trees were handed over. */
        public int publishes;

        @Override
        public boolean isListening() {
            return true;
        }

        @Override
        public void publish(AccessibleTree published, boolean reentrant) {
            tree = published;
            publishes++;
        }

        @Override
        public void emit(AccessibleEvent event) {
            events.add(event);
        }

        /** @return the newest tree the scene published, or the empty one */
        public AccessibleTree tree() {
            return tree;
        }

        /**
         * @param type the kind to look for
         * @return every event of that kind handed over so far, in order
         */
        public List<AccessibleEvent> eventsOf(AccessibleEvent.Type type) {
            List<AccessibleEvent> found = new java.util.ArrayList<>();
            for (AccessibleEvent event : events) {
                if (event.type() == type) {
                    found.add(event);
                }
            }
            return found;
        }
    }

    private final String title;
    private final int width;
    private final int height;
    private final HeadlessBackend backend;
    private final CapturingBridge bridge = new CapturingBridge();
    private final NoopCanvas canvas;
    private final GpuRenderer renderer;
    private FrameCallback callback;
    private WindowInput input;
    private boolean visible;
    private boolean closed;
    boolean modal;
    boolean modalBlocked;

    HeadlessWindow(String title, int width, int height, HeadlessBackend backend) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.backend = backend;
        this.canvas = new NoopCanvas(width, height);
        this.renderer = new GpuRenderer() {
            @Override
            public Canvas canvas() {
                return canvas;
            }

            @Override
            public void clear(float red, float green, float blue, float alpha) {
            }

            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
            }
        };
    }

    /** Renders one frame through the callback the bound scene installed. */
    public void frame() {
        if (callback == null) {
            throw new IllegalStateException("no scene is bound to \"" + title + "\"");
        }
        callback.onFrame(renderer, new FrameInfo(width, height, 1f));
    }

    /**
     * Tells the bound scene the desktop gave this window the keyboard, or took it away: what a
     * user's click on a window does, and what showing a dialog that takes focus does to both.
     *
     * @param focused whether this window is now the one the keyboard goes to
     */
    public void desktopFocus(boolean focused) {
        if (input == null) {
            throw new IllegalStateException("no scene is bound to \"" + title + "\"");
        }
        input.windowFocusChanged(focused);
        input.inputBatchEnded();
    }

    /**
     * Presses and releases one key in this window, as the desktop delivers a keystroke: the
     * press, the release, and the end of the input batch that dispatches both.
     *
     * @param key the key code, from {@link limn.input.Keys}
     */
    public void key(int key) {
        if (input == null) {
            throw new IllegalStateException("no scene is bound to \"" + title + "\"");
        }
        input.keyEvent(key, true, false, 0);
        input.keyEvent(key, false, false, 0);
        input.inputBatchEnded();
    }

    /** @return the bridge that kept the newest tree this window's scene published */
    public CapturingBridge bridge() {
        return bridge;
    }

    /**
     * A display exactly the size of this window, at scale one. A native popup menu refuses to
     * open on a window with no display at all, since its work area is what the cascade is kept
     * inside; a combo's popup and a dialog tolerate its absence. So the display is answered, and
     * it is this window's own rectangle, which is the only screen a headless window has.
     */
    private final Display display = new Display() {
        @Override public String id() { return "headless"; }
        @Override public String name() { return "Headless display"; }
        @Override public boolean isPrimary() { return true; }
        @Override public Resolution currentResolution() { return new Resolution(width, height); }
        @Override public List<Resolution> availableResolutions() {
            return List.of(currentResolution());
        }
        @Override public ScreenRect bounds() { return new ScreenRect(0, 0, width, height); }
        @Override public ScreenRect workArea() { return bounds(); }
        @Override public float contentScale() { return 1; }
    };

    @Override public AccessibilityBridge accessibility() { return bridge; }
    @Override public Display display() { return display; }
    @Override public String title() { return title; }
    @Override public void setTitle(String newTitle) { }
    @Override public float logicalWidth() { return width; }
    @Override public float logicalHeight() { return height; }
    @Override public int framebufferWidth() { return width; }
    @Override public int framebufferHeight() { return height; }
    @Override public float contentScale() { return 1; }
    @Override public void overrideContentScale(float scale) { }
    @Override public void setSize(int newWidth, int newHeight) { }
    @Override public void show() { visible = true; }
    @Override public void hide() { visible = false; }
    @Override public void focus() { }
    @Override public boolean isVisible() { return visible; }
    @Override public boolean isClosed() { return closed; }
    @Override public void enterFullscreen(int w, int h, int refreshRate) { }
    @Override public void exitFullscreen() { }
    @Override public boolean isFullscreen() { return false; }
    @Override public boolean isModalBlocked() { return modalBlocked; }
    @Override public boolean isModal() { return modal; }
    @Override public void registerChildPopup(NativeWindow child, PopupKind kind) { }
    @Override public void unregisterChildPopup(NativeWindow child) { }
    @Override public void setFrameCallback(FrameCallback newCallback) { callback = newCallback; }
    @Override public void setInput(WindowInput newInput) { input = newInput; }
    @Override public Backend backend() { return backend; }
    @Override public Clipboard clipboard() { return null; }
    @Override public int screenX() { return 0; }
    @Override public int screenY() { return 0; }
    @Override public void setScreenPosition(int x, int y) { }
    @Override public float logicalToScreenFactor() { return 1; }
    @Override public void captureNextFrame(Consumer<Image> sink) { }
    @Override public void setContentScaleListener(ContentScaleListener listener) { }
    @Override public void requestFrame() { }
    @Override public void requestClose() { close(); }

    /** Closes as a desktop backend does: the bound scene is told, once, and the window is gone. */
    @Override public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (input != null) {
            input.windowClosed();
        }
    }
}
