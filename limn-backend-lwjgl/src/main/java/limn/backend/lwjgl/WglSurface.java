package limn.backend.lwjgl;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WinBase;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A child window covering a top-level window's client area, to draw on after a lost context
 * (ADR 082 §1.4, step 1), on Windows.
 *
 * <p>A window's pixel format belongs to the driver that set it, for the window's life, so once that
 * driver has been unloaded no context can be made on the top-level window again; a child that never
 * had one can take a new context from the driver loaded again, measured on 2026-10-29. The top-level
 * window stays: it is what the screen reader's provider, the system's drag and drop, the input
 * method, the tray and the menus are attached to.
 *
 * <p>The child is of a class of the backend's own: it paints no background, which the system's
 * static class did and which showed white while the window was dragged; it is transparent to hit
 * testing, so that every input goes to the top-level window as before; and it asks its window for a
 * frame when the system asks it to paint. UI thread only, Windows only.
 */
final class WglSurface {

    private static final int CS_OWNDC = 0x20;
    private static final int WM_PAINT = 0x000F;
    private static final int WM_ERASEBKGND = 0x0014;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int HTTRANSPARENT = -1;
    private static final int WS_CHILD = 0x40000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_DISABLED = 0x08000000;
    private static final int WS_CLIPSIBLINGS = 0x04000000;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;

    /** What each child asks for when the system asks it to paint, by its handle. */
    private static final Map<Long, Runnable> PAINTERS = new HashMap<>();
    private static WindowProc procedure;
    private static ByteBuffer className;

    private WglSurface() {
    }

    private static void register() {
        if (procedure != null) {
            return;
        }
        procedure = WindowProc.create((window, message, wParam, lParam) -> {
            switch (message) {
                case WM_ERASEBKGND:
                    return 1; // the frame covers it; a background would flash
                case WM_NCHITTEST:
                    return HTTRANSPARENT;
                case WM_PAINT:
                    Runnable painter = PAINTERS.get(window);
                    if (painter != null) {
                        painter.run();
                    }
                    return User32.DefWindowProc(window, message, wParam, lParam); // validates it
                default:
                    return User32.DefWindowProc(window, message, wParam, lParam);
            }
        });
        className = MemoryUtil.memUTF16("LimnDrawingSurface");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                    .cbSize(WNDCLASSEX.SIZEOF)
                    .style(CS_OWNDC)
                    .lpfnWndProc(procedure)
                    .hInstance(WinBase.GetModuleHandle(null, (CharSequence) null))
                    .lpszClassName(className);
            User32.RegisterClassEx(null, windowClass);
        }
    }

    /**
     * @param parent  the top-level window
     * @param width   its client area's width, in pixels
     * @param height  its client area's height, in pixels
     * @param painter what to do when the system asks the child to paint: ask for a frame
     * @return the child's handle, or NULL if it could not be made
     */
    static long create(long parent, int width, int height, Runnable painter) {
        register();
        long child = User32.CreateWindowEx(null, 0, "LimnDrawingSurface", "",
                WS_CHILD | WS_VISIBLE | WS_DISABLED | WS_CLIPSIBLINGS,
                0, 0, Math.max(1, width), Math.max(1, height), parent, NULL,
                WinBase.GetModuleHandle(null, (CharSequence) null), NULL);
        if (child != NULL) {
            PAINTERS.put(child, painter);
        }
        return child;
    }

    /** Covers the parent's client area again after it was resized. */
    static void resize(long child, int width, int height) {
        User32.SetWindowPos(null, child, NULL, 0, 0, Math.max(1, width), Math.max(1, height),
                SWP_NOZORDER | SWP_NOACTIVATE);
    }

    static void destroy(long child) {
        PAINTERS.remove(child);
        User32.DestroyWindow(null, child);
    }
}
