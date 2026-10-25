package limn.demo.site;

import limn.backend.NativeWindow;
import limn.backend.spi.FrameCallback;
import limn.backend.spi.FrameInfo;
import limn.backend.spi.GpuRenderer;
import limn.components.DisplayMode;
import limn.components.PopupMenu;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The popup-menu card is a still of a right-click's result: the driver poses the scene after its
 * first frame ({@link Poses}), the menu opens inside the window the capture reads, and the
 * transcript written beside the still is what a screen reader hears with it up. Without the pose
 * the card is a list of four files published under the name of a menu, and nothing in the capture
 * could tell: the still is drawn, the manifest is satisfied.
 *
 * <p>The driver runs as it does in {@code Gallery.main}, over a headless window whose renderer
 * hands back a drawn capture, with the transcript keeper the capture installs as its bridge.
 */
class GalleryPoseTest {

    private static final int WIDTH = 480;
    private static final int HEIGHT = 470;

    @TempDir
    Path dir;

    private HeadlessUi ui;
    private UiRuntime runtime;
    private Theme theme;
    private Locale locale;

    @BeforeEach
    void installRuntime() {
        theme = Theme.current();
        locale = I18n.locale();
        ui = new HeadlessUi(() -> 0L);
        runtime = ui.runtime();
        TextRulers.install(HeadlessWindow.RULER);
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void uninstallRuntime() {
        I18n.setLocale(locale);
        TextRulers.uninstall(HeadlessWindow.RULER);
        Theme.setCurrent(theme);
        ui.close();
    }

    @Test
    void thePopupMenuCardIsCapturedWithTheRightClicksMenuOpenInTheCapturedWindow() throws IOException {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow inner = backend.open("Limn UI: gallery", WIDTH, HEIGHT);
        Gallery.TreeKeeper keeper = new Gallery.TreeKeeper();
        AtomicReference<FrameCallback> callback = new AtomicReference<>();
        NativeWindow window = (NativeWindow) Proxy.newProxyInstance(
                limn.backend.spi.WindowPeer.class.getClassLoader(), new Class<?>[] {limn.backend.spi.WindowPeer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setFrameCallback")) {
                        callback.set((FrameCallback) args[0]);
                        return null;
                    }
                    if (method.getName().equals("accessibility") && method.getParameterCount() == 0) {
                        return keeper; // what Gallery.main installs, so the transcript is written
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        GpuRenderer renderer = new GpuRenderer() {
            private final Canvas canvas = new NoopCanvas(WIDTH, HEIGHT);

            @Override
            public Canvas canvas() {
                return canvas;
            }

            @Override
            public void clear(float red, float green, float blue, float alpha) {
            }

            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                // Two different pixels: a capture of a scene that drew something.
                sink.accept(new Image(2, 1, new byte[] {0, 0, 0, -1, -1, -1, -1, -1}));
            }
        };

        GalleryEntry entry = GalleryScenes.entries().stream()
                .filter(each -> each.id().equals("popup-menu"))
                .findFirst().orElseThrow();
        Gallery.Shot shot = new Gallery.Shot(entry, new Gallery.Palette("dark", Theme.limn()),
                dir.resolve("popup-menu-dark@2x.png"), window, false);
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
        });
        Gallery.Driver driver = new Gallery.Driver(List.of(shot), List.of(window), writer, runtime);
        driver.start();
        int frames = 0;
        while (!inner.isClosed() && frames++ < 5_000) {
            callback.get().onFrame(renderer, new FrameInfo(WIDTH, HEIGHT, 1f));
        }

        assertTrue(inner.isClosed(), "the capture ended; after " + frames + " frames it had not");
        assertFalse(driver.failed(), "and it did not fail: " + driver.failure());
        // The captured window's own tree: a menu in a window of its own publishes to that window's
        // bridge, and would be missing here exactly as it would be missing from the picture.
        String transcript = Files.readString(dir.resolve("popup-menu.a11y.txt"));
        assertTrue(transcript.contains("\"Open Invoice 1042.pdf\"")
                        && transcript.contains("\"Rename…\"") && transcript.contains("\"Move to\"")
                        && transcript.contains("\"Delete\""),
                "the still is taken with the selected file's menu open in the captured window:\n"
                        + transcript);
        assertEquals(DisplayMode.NATIVE_WINDOW, PopupMenu.defaultDisplayMode(),
                "and the process-wide default the pose borrowed is put back");
    }
}
