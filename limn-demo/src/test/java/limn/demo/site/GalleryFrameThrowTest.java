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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anything else the gallery's frame callback throws ends the capture too, and every frame is
 * counted whether or not it finished.
 *
 * <p>The refused film step ({@code GalleryFilmRefusalTest}) was one way into the spin and not
 * the only one: the driver's body also reads a capture sink, builds the next scene, writes a
 * transcript and walks a footer, and a throw from any of those used to leave the callback ahead
 * of {@code ++totalFrames}. The backend's answer to a frame that threw is to ask the same window
 * for another one, so the ceiling the watchdog exists to impose was never reached.
 *
 * <p>A scene that throws while PAINTING is deliberately not among these: {@code Scene.renderFrame}
 * contains its own frame crashes and nothing reaches this callback from there.
 */
class GalleryFrameThrowTest {

    private static final int WIDTH = 480;
    private static final int HEIGHT = 470;

    /**
     * Frames the run may take to end itself once something has thrown. Comfortably above the
     * warm-up (24) and comfortably below the driver's own watchdog budget for one shot (416),
     * so a test that passes because the WATCHDOG eventually fired fails here instead.
     */
    private static final int NAMED_FAILURE_FRAMES = 100;

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

    /** What the loop below is left holding: the window, the driver and what escaped. */
    private record Run(HeadlessWindow window, Gallery.Driver driver, List<Throwable> escaped,
                       int frames) {
    }

    /**
     * Runs one unfilmed shot over a headless window with {@code renderer}, handing out frames
     * until the driver closes the window or {@code limit} is reached, and carrying on after a
     * frame that threw exactly as a backend's loop does (it logs the crash and asks the same
     * window for another frame).
     */
    private Run run(GpuRenderer renderer, int limit) {
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

        GalleryEntry entry = new GalleryEntry("broken", "Broken", "gallery:broken",
                () -> GalleryScenes.filmable(new Scene(new Button("Here")), null), null);
        Gallery.Shot shot = new Gallery.Shot(entry, new Gallery.Palette("dark", Theme.limn()),
                dir.resolve("broken-dark@2x.png"), window, false);
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
        });
        Gallery.Driver driver = new Gallery.Driver(List.of(shot), List.of(window), writer, runtime);
        driver.start();

        List<Throwable> escaped = new ArrayList<>();
        int frames = 0;
        while (!inner.isClosed() && frames++ < limit) {
            try {
                callback.get().onFrame(renderer, new FrameInfo(WIDTH, HEIGHT, 1f));
            } catch (RuntimeException | Error e) {
                escaped.add(e);
            }
        }
        return new Run(inner, driver, escaped, frames);
    }

    /** A renderer over a canvas that draws nothing; its capture sink is the seam. */
    private abstract static class TestRenderer implements GpuRenderer {
        private final Canvas canvas = new NoopCanvas(WIDTH, HEIGHT);

        @Override
        public Canvas canvas() {
            return canvas;
        }

        @Override
        public void clear(float red, float green, float blue, float alpha) {
        }
    }

    @Test
    void aCaptureSinkThatThrowsFailsTheRunAndNamesTheShot() {
        Run run = run(new TestRenderer() {
            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                throw new IllegalStateException("the framebuffer readback failed");
            }
        }, NAMED_FAILURE_FRAMES);

        assertTrue(run.window().isClosed(), "the throw ended the capture; after " + run.frames()
                + " frames it had not, and " + run.escaped().size() + " frame(s) threw: "
                + (run.escaped().isEmpty() ? "" : run.escaped().get(run.escaped().size() - 1)));
        assertTrue(run.driver().failed(), "and the run is a failure");
        String failure = String.valueOf(run.driver().failure());
        assertTrue(failure.contains("broken-dark@2x.png")
                        && failure.contains("the framebuffer readback failed"),
                "the failure names the shot and what threw: " + failure);
        assertTrue(run.escaped().isEmpty(),
                "and nothing escaped the frame callback: " + run.escaped());
    }

    /**
     * The count is what holds the ceiling, so it holds for a throw the driver does NOT catch:
     * an Error from the canvas the render is handed, on every frame. Nothing names this one --
     * an Error is not the driver's to contain -- but the run still ends, which is the whole
     * point of counting the frame before the body rather than after it.
     *
     * <p>The ceiling this reaches is THIS driver's, and the loop below is this test's own. Under
     * {@code LwjglBackend} an Error on every frame does not get that far: the loop catches it
     * (catch Throwable), logs it through {@code Crashes}, hands the same window another frame,
     * and gives up at {@code CRASH_STREAK_LIMIT} = 100 consecutive crashed iterations by
     * throwing out of {@code runEventLoop} -- about a hundred iterations against a budget of
     * some twenty-eight thousand frames. What happens on THAT path is
     * {@code GalleryDrainTest}'s: the writer is drained and its pool shut down, so the task
     * exits instead of hanging. What the count bounds under the real backend is an Error
     * intermittent enough to keep resetting that streak, which no net catches.
     */
    @Test
    void aFrameThatThrowsAnErrorEveryTimeIsBoundedByTheCount() {
        Run run = run(new TestRenderer() {
            @Override
            public Canvas canvas() {
                throw new AssertionError("the GL context is gone");
            }

            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                sink.accept(new Image(2, 1, new byte[] {0, 0, 0, -1, -1, -1, -1, -1}));
            }
        }, 5_000);

        assertTrue(run.window().isClosed(), "the watchdog ended the capture; after "
                + run.frames() + " frames it had not, and " + run.escaped().size()
                + " frame(s) threw");
        assertTrue(run.driver().failed(), "and the run is a failure");
        assertTrue(run.frames() > NAMED_FAILURE_FRAMES,
                "by the watchdog's ceiling rather than by anything earlier: " + run.frames());
    }
}
