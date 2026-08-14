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

    @Override public void requestFrame() { frameRequests++; }
    @Override public void setFrameCallback(FrameCallback callback) { }
    @Override public void setInput(WindowInput input) { }
    @Override public String title() { return ""; }
    @Override public void setTitle(String title) { }
    @Override public float logicalWidth() { return 0; }
    @Override public float logicalHeight() { return 0; }
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
    @Override public boolean isModalBlocked() { return false; }
    @Override public void registerChildPopup(NativeWindow child, PopupKind kind) { }
    @Override public void unregisterChildPopup(NativeWindow child) { }
    @Override public Backend backend() { return null; }
    @Override public Clipboard clipboard() { return null; }
    @Override public int screenX() { return 0; }
    @Override public int screenY() { return 0; }
    @Override public void setScreenPosition(int x, int y) { }
    @Override public float logicalToScreenFactor() { return 1; }
    @Override public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) { }
    @Override public void setContentScaleListener(ContentScaleListener listener) { }
}
