package limn.video;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.SurfaceRecordingCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The video-surface facade: what it does with no backend, off the UI thread,
 * and with two providers competing, plus the seam a surface reaches a canvas
 * through, which is the only way a picture gets on screen.
 */
class VideoSurfacesTest {

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void withoutABackendThereIsNoSurfaceAndItSaysSo() {
        assertFalse(VideoSurfaces.isAvailable(), "no provider is installed in a headless test");
        IllegalStateException error = assertThrows(IllegalStateException.class, VideoSurfaces::create);
        assertTrue(error.getMessage().contains("backend"),
                "the message must point at the missing backend: " + error.getMessage());
    }

    @Test
    void anInstalledProviderCreatesTheSurface() {
        FakeSurface surface = new FakeSurface();
        VideoSurfaces.Provider provider = () -> surface;
        VideoSurfaces.install(provider);
        try {
            assertTrue(VideoSurfaces.isAvailable());
            assertSame(surface, VideoSurfaces.create());
        } finally {
            VideoSurfaces.uninstall(provider);
        }
        assertFalse(VideoSurfaces.isAvailable(), "uninstall leaves the facade as it found it");
    }

    @Test
    void uninstallingSomeoneElsesProviderLeavesTheInstalledOne() {
        // A backend shutting down late must not clear the provider a newer one
        // has already installed.
        FakeSurface surface = new FakeSurface();
        VideoSurfaces.Provider older = () -> new FakeSurface();
        VideoSurfaces.Provider newer = () -> surface;
        VideoSurfaces.install(newer);
        try {
            VideoSurfaces.uninstall(older);
            assertTrue(VideoSurfaces.isAvailable());
            assertSame(surface, VideoSurfaces.create());
        } finally {
            VideoSurfaces.uninstall(newer);
        }
    }

    @Test
    void creatingOffTheUiThreadThrows() throws Exception {
        VideoSurfaces.Provider provider = FakeSurface::new;
        VideoSurfaces.install(provider);
        try {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    VideoSurfaces.create();
                } catch (Throwable error) {
                    thrown.set(error);
                }
            }, "not-the-ui-thread");
            other.start();
            other.join();
            assertTrue(thrown.get() instanceof IllegalStateException,
                    "a surface belongs to the window being rendered: " + thrown.get());
        } finally {
            VideoSurfaces.uninstall(provider);
        }
    }

    @Test
    void installingNullIsRefused() {
        assertThrows(NullPointerException.class, () -> VideoSurfaces.install(null));
        assertFalse(VideoSurfaces.isAvailable());
    }

    @Test
    void aSurfaceReachesTheCanvasAsARectangleInPoints() {
        FakeSurface surface = new FakeSurface();
        VideoFrame frame = uniformFrame(64, 36);
        surface.upload(frame);
        frame.release();
        assertTrue(surface.hasPicture());
        assertEquals(64, surface.widthPx());
        assertEquals(36, surface.heightPx());

        SurfaceRecordingCanvas canvas = new SurfaceRecordingCanvas(200, 100);
        canvas.drawSurface(surface, 10, 20, 128, 72);

        assertEquals(1, canvas.draws().size());
        SurfaceRecordingCanvas.Draw draw = canvas.draws().get(0);
        assertSame(surface, draw.surface());
        assertEquals(10f, draw.x());
        assertEquals(20f, draw.y());
        assertEquals(128f, draw.width(), "the destination is the caller's rectangle, not the picture's size");
        assertEquals(72f, draw.height());
    }

    private static VideoFrame uniformFrame(int width, int height) {
        VideoFrame.Writer writer = VideoFrame.Writer.allocate(0, VideoFrame.Recycler.NONE);
        writer.configure(width, height, PixelFormat.I420, VideoColor.BT709_LIMITED);
        for (int plane = 0; plane < PixelFormat.I420.planeCount(); plane++) {
            int bytes = PixelFormat.I420.planeByteWidth(plane, width)
                    * PixelFormat.I420.planeHeight(plane, height);
            writer.setPlane(plane, ByteBuffer.allocate(bytes),
                    PixelFormat.I420.planeByteWidth(plane, width));
        }
        return writer.publish();
    }

    /** A surface with no device behind it: enough to exercise the facade and the canvas seam. */
    private static final class FakeSurface implements VideoSurface {

        private int width;
        private int height;
        private boolean disposed;

        @Override
        public void upload(VideoFrame frame) {
            assertNotNull(frame.plane(0), "a real surface reads the planes here");
            width = frame.width();
            height = frame.height();
        }

        @Override
        public boolean hasPicture() {
            return width > 0 && !disposed;
        }

        @Override
        public int widthPx() {
            return width;
        }

        @Override
        public int heightPx() {
            return height;
        }

        @Override
        public void resize(int widthPx, int heightPx) {
        }

        @Override
        public void dispose() {
            disposed = true;
            width = 0;
            height = 0;
        }
    }
}
