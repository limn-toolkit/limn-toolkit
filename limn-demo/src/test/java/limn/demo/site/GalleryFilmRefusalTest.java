package limn.demo.site;

import limn.backend.FrameCallback;
import limn.backend.FrameInfo;
import limn.backend.GpuRenderer;
import limn.backend.NativeWindow;
import limn.components.Button;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.demo.a11y.HeadlessBackend;
import limn.demo.a11y.HeadlessWindow;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.graphics.TextRulers;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A film step the live scene refuses ends the gallery capture as a failure that names the step
 * (task chip task_3aa41c6a). Before 2026-09-15 it spun: the step's exception escaped the frame
 * callback ahead of the watchdog's count, the film never reached its last frame, and every
 * later frame threw "the driver asked for one more" until the process was killed.
 *
 * <p>The driver runs here as it does in {@code Gallery.main}, over a headless window whose
 * renderer hands back a drawn (not flat) capture, so the still is accepted and the film starts;
 * the loop below keeps giving frames after a callback throws, as a desktop backend's loop does.
 */
class GalleryFilmRefusalTest {

    private static final int WIDTH = 480;
    private static final int HEIGHT = 470;

    @TempDir
    Path dir;

    private HeadlessUi ui;
    private UiRuntime runtime;
    private Theme theme;

    @BeforeEach
    void installRuntime() {
        theme = Theme.current();
        ui = new HeadlessUi(() -> 0L);
        runtime = ui.runtime();
        TextRulers.install(HeadlessWindow.RULER);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        Theme.setCurrent(theme);
        ui.close();
    }

    @Test
    void aFilmStepTheSceneRefusesFailsTheCaptureAndNamesTheStep() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow inner = backend.open("Limn UI: gallery", WIDTH, HEIGHT);
        AtomicReference<FrameCallback> callback = new AtomicReference<>();
        NativeWindow window = (NativeWindow) Proxy.newProxyInstance(
                NativeWindow.class.getClassLoader(), new Class<?>[] {NativeWindow.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setFrameCallback")) {
                        callback.set((FrameCallback) args[0]);
                        return null;
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

        GalleryEntry entry = new GalleryEntry("refused", "Refused", "gallery:refused",
                () -> GalleryScenes.filmable(new Scene(new Button("Here")), null),
                built -> Motion.script().from(0, 0)
                        .to("a widget the scene never built", () -> null, 0.5f, 0.5f, 4)
                        .press());
        Gallery.Shot shot = new Gallery.Shot(entry, new Gallery.Palette("dark", Theme.limn()),
                dir.resolve("refused-dark@2x.png"), window, false);
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
        });
        Gallery.Driver driver = new Gallery.Driver(List.of(shot), List.of(window), writer, runtime);
        driver.start();

        List<RuntimeException> escaped = new ArrayList<>();
        int frames = 0;
        while (!inner.isClosed() && frames++ < 5_000) {
            try {
                callback.get().onFrame(renderer, new FrameInfo(WIDTH, HEIGHT, 1f));
            } catch (RuntimeException e) {
                escaped.add(e); // a backend's loop carries on after a callback throws
            }
        }

        assertTrue(inner.isClosed(), "the refused step ended the capture; after " + frames
                + " frames it had not, and " + escaped.size() + " frame(s) threw: "
                + (escaped.isEmpty() ? "" : escaped.get(escaped.size() - 1)));
        assertTrue(driver.failed(), "and the run is a failure");
        String failure = String.valueOf(driver.failure());
        assertTrue(failure.contains("film step 1 of 2")
                        && failure.contains("glide to a widget the scene never built")
                        && failure.contains("refused-dark@2x.png"),
                "the failure names the shot and the step: " + failure);
        assertTrue(escaped.isEmpty(), "and nothing escaped the frame callback: " + escaped);
    }

    /** The playhead keeps refusing once it has, rather than playing on against nothing. */
    @Test
    void aFilmThatRefusedAStepRefusesEveryFrameAfter() {
        Motion.Film film = Motion.script().from(0, 0)
                .to("a widget the scene never built", () -> null, 0.5f, 0.5f, 4)
                .hold(2).film();
        film.next(); // the still the film opens on
        Motion.Refused first = assertThrows(Motion.Refused.class, film::next);
        assertTrue(first.getMessage().startsWith("film step 1 of 2 (glide to a widget the scene"
                + " never built), on frame 1 of 7, was refused: a film step aims at"), first.getMessage());
        for (int i = 0; i < 10; i++) {
            Motion.Refused again = assertThrows(Motion.Refused.class, film::next);
            assertTrue(again == first, "the same refusal, not a later step or \"one more\"");
        }
    }
}
