package limn.components;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.Cursor;
import limn.backend.Display;
import limn.backend.FrameCallback;
import limn.backend.NativeWindow;
import limn.backend.Resolution;
import limn.backend.ScreenRect;
import limn.backend.WindowInput;

import java.util.List;

/**
 * A {@link NativeWindow} that does nothing except answer questions: enough of a window for the
 * components that ask one whether they may open a window of their own, without a backend, a
 * display server or GL.
 *
 * <p>{@link #backend()} throws rather than returning {@code null}. A component that reached it
 * has decided to create a real window, which is the one thing no headless test can do, and an
 * {@code AssertionError} naming that says so where a {@code NullPointerException} three frames
 * down would not.
 */
class StubWindow implements NativeWindow {

    /** Frames the scene has asked for: what an idle scene must stop adding to. */
    int framesRequested;

    private final boolean canPosition;

    /** A window on a platform that places windows where it is told, which is most of them. */
    StubWindow() {
        this(true);
    }

    /**
     * @param canPosition what {@link #supportsAbsolutePositioning()} answers; {@code false}
     *                    reproduces Wayland, where nothing outside a window can be lined up
     *                    with anything inside it
     */
    StubWindow(boolean canPosition) {
        this.canPosition = canPosition;
    }

    @Override public boolean supportsAbsolutePositioning() { return canPosition; }

    /** A single 400×300 display at the origin, so the whole window is inside its work area. */
    @Override public Display display() {
        return new Display() {
            @Override public String id() { return "display-0"; }
            @Override public String name() { return "Stub"; }
            @Override public boolean isPrimary() { return true; }
            @Override public Resolution currentResolution() { return new Resolution(400, 300, 60); }
            @Override public List<Resolution> availableResolutions() {
                return List.of(currentResolution());
            }
            @Override public ScreenRect bounds() { return new ScreenRect(0, 0, 400, 300); }
            @Override public ScreenRect workArea() { return new ScreenRect(0, 0, 400, 300); }
            @Override public float contentScale() { return 1; }
        };
    }

    @Override public Backend backend() {
        throw new AssertionError("a headless test cannot create a native window");
    }

    @Override public void requestFrame() { framesRequested++; }
    @Override public void setFrameCallback(FrameCallback callback) { }
    @Override public void setInput(WindowInput input) { }
    @Override public String title() { return ""; }
    @Override public void setTitle(String title) { }
    @Override public float logicalWidth() { return 400; }
    @Override public float logicalHeight() { return 300; }
    @Override public int framebufferWidth() { return 400; }
    @Override public int framebufferHeight() { return 300; }
    @Override public float contentScale() { return 1; }
    @Override public void overrideContentScale(float scale) { }
    @Override public void setSize(int width, int height) { }
    @Override public void show() { }
    @Override public void hide() { }
    @Override public void focus() { }
    @Override public boolean isVisible() { return true; }
    @Override public void setOpacity(float value) { }
    @Override public void setCursor(Cursor value) { }
    @Override public boolean isClosed() { return false; }
    @Override public void requestClose() { }
    @Override public void close() { }
    @Override public void enterFullscreen(int width, int height, int refreshRate) { }
    @Override public void exitFullscreen() { }
    @Override public boolean isFullscreen() { return false; }
    @Override public boolean isModalBlocked() { return false; }
    @Override public void registerChildPopup(NativeWindow child, PopupKind kind) { }
    @Override public void unregisterChildPopup(NativeWindow child) { }
    @Override public Clipboard clipboard() { return null; }
    // What a Wayland window reports: GLFW leaves the caller's buffer untouched, so the answer is
    // whatever it held. Zero here, and a test that asserts on it is asserting on nothing.
    @Override public int screenX() { return 0; }
    @Override public int screenY() { return 0; }
    @Override public void setScreenPosition(int x, int y) { }
    @Override public float logicalToScreenFactor() { return 1; }
    @Override public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) { }
    @Override public void setContentScaleListener(ContentScaleListener listener) { }
}
