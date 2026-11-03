package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Images;

/**
 * A program, not a test, for Windows, where a lost device's contexts are made again with the system's
 * OpenGL unloaded from the process: a picture drawn with no window open makes the backend's own
 * context; a window is then opened and every context made again, as a loss makes them, by
 * {@code -Dlimn.backend.rebuildContextAfter}; the window closes, and a second picture is drawn with
 * the backend's context made anew. Prints {@code OK} when both pictures are right and the windows'
 * contexts were made again.
 */
public final class OffscreenAcrossRebuild {

    private OffscreenAcrossRebuild() {
    }

    private static boolean right(Image image) {
        byte[] p = image.pixels();
        int centre = ((image.height() / 2) * image.width() + image.width() / 2) * 4;
        return (p[centre] & 0xff) == 0x20 && (p[centre + 2] & 0xff) == 0xC0 && (p[centre + 3] & 0xff) == 255;
    }

    public static void main(String[] args) {
        boolean ok;
        try (LwjglBackend backend = new LwjglBackend("Offscreen across a rebuild")) {
            Image before = Images.render(20, 20, 1, c -> c.fillRect(0, 0, 20, 20, Color.rgb(0x2050C0)));
            System.out.println("before the rebuild: " + (right(before) ? "right" : "WRONG"));
            NativeWindow window = backend.createWindow(WindowConfig.of("rebuild", 200, 120).focusOnShow(false));
            window.setOpacity(0f);
            window.show();
            Ui.postDelayed(window::requestClose, 3000);
            backend.runEventLoop();
            Image after = Images.render(20, 20, 1, c -> c.fillRect(0, 0, 20, 20, Color.rgb(0x2050C0)));
            System.out.println("after the rebuild: " + (right(after) ? "right" : "WRONG"));
            ok = right(before) && right(after);
        }
        System.out.println(ok ? "OK" : "FAILED");
        System.exit(ok ? 0 : 1);
    }
}
