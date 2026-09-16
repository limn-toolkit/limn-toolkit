package limn.scene;

import limn.backend.Backend;
import limn.backend.Clipboard;
import limn.backend.Cursor;
import limn.backend.FrameCallback;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;

/**
 * {@link NativeWindow} test double that records the opacity and cursor set on
 * it; everything else is inert. Shared by the scene tests that assert what the
 * scene pushes to its window.
 */
final class RecordingWindow implements NativeWindow {

    float opacity = 1f;
    Cursor cursor = Cursor.DEFAULT;
    limn.backend.ImageCursor imageCursor;
    limn.backend.PointerMode pointerMode = limn.backend.PointerMode.NORMAL;
    boolean imeEnabled;
    int imeEnabledCalls;
    int preeditResets;
    limn.graphics.Rect lastCaretRect;
    private boolean closed;

    @Override
    public void setImeEnabled(boolean enabled) {
        imeEnabled = enabled;
        imeEnabledCalls++;
    }

    @Override
    public void setPreeditCaretRect(float x, float y, float width, float height) {
        lastCaretRect = new limn.graphics.Rect(x, y, width, height);
    }

    @Override
    public void resetPreedit() {
        preeditResets++;
    }

    @Override
    public void setOpacity(float value) {
        opacity = Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void setCursor(Cursor value) {
        cursor = value == null ? Cursor.DEFAULT : value;
    }

    @Override
    public void setImageCursor(limn.backend.ImageCursor value) {
        imageCursor = value;
    }

    @Override
    public void setPointerMode(limn.backend.PointerMode mode) {
        pointerMode = mode == null ? limn.backend.PointerMode.NORMAL : mode;
    }

    @Override
    public limn.backend.PointerMode pointerMode() {
        return pointerMode;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void requestClose() {
        closed = true;
    }

    @Override
    public void close() {
        closed = true;
    }

    int frameRequests;

    /**
     * Where this window claims to be, how many native units one logical point is, and whether it
     * can know its own position at all.
     *
     * <p>The first two answered a fixed zero and a fixed one until the accessible tree needed
     * them: a tree publishes scene-local boxes plus the window's own origin and scale, so a test
     * that wanted to assert a screen rectangle had two constants to assert against. The third
     * reproduces Wayland, where a window is placed by the desktop and never learns where it went.
     */
    int screenX;
    int screenY;
    float logicalToScreenFactor = 1;
    boolean canPosition = true;
    /** The two ends of modality, each settable: the dialog's own bit, and its owner's. */
    boolean modal;
    boolean modalBlocked;

    @Override public boolean supportsAbsolutePositioning() { return canPosition; }

    /** What {@link #accessibility()} hands the scene; none unless a test installs one. */
    limn.backend.AccessibilityBridge accessibility = limn.backend.AccessibilityBridge.NONE;

    @Override public limn.backend.AccessibilityBridge accessibility() { return accessibility; }

    @Override public void requestFrame() { frameRequests++; }
    @Override public void setFrameCallback(FrameCallback callback) { }
    @Override public void setInput(WindowInput input) { }
    /** What a window says about itself before the scene in it has ever laid out. */
    String title = "";
    float logicalWidth;
    float logicalHeight;

    @Override public String title() { return title; }
    @Override public void setTitle(String newTitle) { this.title = newTitle; }
    @Override public float logicalWidth() { return logicalWidth; }
    @Override public float logicalHeight() { return logicalHeight; }
    @Override public int framebufferWidth() { return 0; }
    @Override public int framebufferHeight() { return 0; }
    @Override public float contentScale() { return 1; }
    @Override public void overrideContentScale(float scale) { }
    @Override public void setSize(int width, int height) { }
    @Override public void show() { }
    @Override public void hide() { }
    @Override public void focus() { }
    @Override public boolean isVisible() { return true; }
    @Override public void enterFullscreen(int width, int height, int refreshRate) { }
    @Override public void exitFullscreen() { }
    @Override public boolean isFullscreen() { return false; }
    @Override public boolean isModalBlocked() { return modalBlocked; }
    @Override public boolean isModal() { return modal; }
    @Override public void registerChildPopup(NativeWindow child, PopupKind kind) { }
    @Override public void unregisterChildPopup(NativeWindow child) { }
    @Override public Backend backend() { return null; }
    @Override public Clipboard clipboard() { return null; }
    @Override public int screenX() { return screenX; }
    @Override public int screenY() { return screenY; }
    @Override public void setScreenPosition(int x, int y) { screenX = x; screenY = y; }
    @Override public float logicalToScreenFactor() { return logicalToScreenFactor; }
    @Override public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) { }
    @Override public void setContentScaleListener(ContentScaleListener listener) { }
}
