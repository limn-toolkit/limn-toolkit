package limn.demo.site;

import limn.backend.AccessibilityBridge;
import limn.backend.FrameCallback;
import limn.backend.FrameInfo;
import limn.backend.GpuRenderer;
import limn.backend.NativeWindow;
import limn.components.Button;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way the capture ends as a failure says so in {@code driver.failure()}, and every one of
 * them ends the frame it happened on.
 *
 * <p>{@code fail()} is the router: it names the shot, prints one line to stderr, sets the flag,
 * drops the film and the scene and closes both windows. Three endings used to open-code half of
 * that and leave {@code failure()} null -- the watchdog, the flat-frame bail-out and a transcript
 * that could not be written -- and a caller with no stderr to read (the site's build, and these
 * tests) got "null" for the failure whose cause is hardest to guess from a log. The transcript
 * one also handed control back into the middle of the body, which carried on capturing.
 */
class GalleryFailureNamingTest {

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

    /** What the loop below is left holding, including the callback, to hand it more frames. */
    private record Run(HeadlessWindow window, Gallery.Driver driver, List<Throwable> escaped,
                       int frames, FrameCallback callback) {
    }

    /** A gallery entry whose scene is one button, recording each time it is built. */
    private static GalleryEntry entry(String id, AtomicBoolean built) {
        return new GalleryEntry(id, id, "gallery:" + id, () -> {
            built.set(true);
            return GalleryScenes.filmable(new Scene(new Button("Here")), null);
        }, null);
    }

    /**
     * Runs {@code files.length} unfilmed shots over one headless window with {@code renderer},
     * handing out frames until the driver closes the window or {@code limit} is reached, and
     * carrying on after a frame that threw exactly as a backend's loop does.
     *
     * <p>The window's accessibility bridge is a {@code Gallery.TreeKeeper}, as {@code main}
     * installs on the real one, because that is what makes the driver write a transcript at all.
     */
    private Run run(GpuRenderer renderer, int limit, List<AtomicBoolean> built, Path... files) {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow inner = backend.open("Limn UI: gallery", WIDTH, HEIGHT);
        AccessibilityBridge keeper = new Gallery.TreeKeeper();
        AtomicReference<FrameCallback> callback = new AtomicReference<>();
        NativeWindow window = (NativeWindow) Proxy.newProxyInstance(
                NativeWindow.class.getClassLoader(), new Class<?>[] {NativeWindow.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setFrameCallback")) {
                        callback.set((FrameCallback) args[0]);
                        return null;
                    }
                    if (method.getName().equals("accessibility")) {
                        return keeper;
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        List<Gallery.Shot> shots = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            shots.add(new Gallery.Shot(entry("shot" + i, built.get(i)),
                    new Gallery.Palette("dark", Theme.limn()), files[i], window, false));
        }
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
        });
        Gallery.Driver driver = new Gallery.Driver(shots, List.of(window), writer, runtime);
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
        return new Run(inner, driver, escaped, frames, callback.get());
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

    /** A still with two different pixels: a capture the driver accepts as a drawn scene. */
    private static Image drawn() {
        return new Image(2, 1, new byte[] {0, 0, 0, -1, -1, -1, -1, -1});
    }

    /**
     * The watchdog: a capture that makes no progress at all, here because the renderer's sink
     * never delivers the still it was asked for, so the driver waits for a frame that never
     * comes. The ceiling for one shot is 24 * 12 + 128 frames.
     */
    @Test
    void theWatchdogNamesItselfAndTheShotItGaveUpOn() {
        AtomicBoolean built = new AtomicBoolean();
        Run run = run(new TestRenderer() {
            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                // Asked for, never delivered: the shot can never complete.
            }
        }, 2_000, List.of(built), dir.resolve("stuck-dark@2x.png"));

        assertTrue(run.window().isClosed(), "the watchdog ended the capture; after "
                + run.frames() + " frames it had not");
        assertTrue(run.driver().failed(), "and the run is a failure");
        String failure = String.valueOf(run.driver().failure());
        assertTrue(failure.contains("stuck-dark@2x.png") && failure.contains("watchdog"),
                "which names itself and the shot it gave up on, for a caller with no stderr"
                        + " to read: " + failure);
    }

    /**
     * A transcript that cannot be written: the still's directory is a regular file, so
     * {@code Files.writeString} beside it fails. The run ends there -- it used to declare the
     * failure and carry on through the rest of the body, advancing to the next shot in the same
     * frame, which is what the second shot's builder witnesses here.
     */
    @Test
    void aTranscriptThatCannotBeWrittenNamesItselfAndEndsTheFrame() throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();

        Run run = run(new TestRenderer() {
            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                sink.accept(drawn());
            }
        }, 200, List.of(first, second),
                blocked.resolve("one-dark@2x.png"), dir.resolve("two-dark@2x.png"));

        assertTrue(run.window().isClosed(), "the failed transcript ended the capture; after "
                + run.frames() + " frames it had not");
        assertTrue(run.driver().failed(), "and the run is a failure");
        String failure = String.valueOf(run.driver().failure());
        assertTrue(failure.contains("one-dark@2x.png") && failure.contains("could not write")
                        && failure.contains("shot0.a11y.txt"),
                "which names the shot and the file it could not write: " + failure);
        assertTrue(first.get(), "the first shot was built");
        assertFalse(second.get(), "and the frame ended there: the run did not go on to build"
                + " the next shot after declaring itself failed");
    }

    /**
     * And a frame delivered AFTER the run failed is not taken either, which is the driver
     * stating that invariant rather than borrowing it.
     *
     * <p>Under the real backend nothing delivers such a frame: {@code requestClose} posts, the
     * post drains at the top of the next iteration, and the render phase skips a close-requested
     * window. The driver used to hold nothing itself -- the frame would have fallen through to
     * "scene == null && !advance()" and built, themed and bound the next shot. This loop hands
     * it ten of them anyway.
     */
    @Test
    void aFrameDeliveredAfterTheRunFailedIsNotTaken() throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        GpuRenderer renderer = new TestRenderer() {
            @Override
            public void captureFramebuffer(Consumer<Image> sink) {
                sink.accept(drawn());
            }
        };

        Run run = run(renderer, 200, List.of(first, second),
                blocked.resolve("one-dark@2x.png"), dir.resolve("two-dark@2x.png"));
        assertTrue(run.driver().failed(), "the run failed: " + run.driver().failure());
        assertFalse(second.get(), "and stopped before the next shot");

        for (int i = 0; i < 10; i++) {
            run.callback().onFrame(renderer, new FrameInfo(WIDTH, HEIGHT, 1f));
        }

        assertFalse(second.get(), "a failed run takes no further frame: ten more of them did"
                + " not resume the capture at the next shot");
        assertTrue(String.valueOf(run.driver().failure()).contains("one-dark@2x.png"),
                "and the failure still names the shot it happened on: "
                        + run.driver().failure());
    }
}
