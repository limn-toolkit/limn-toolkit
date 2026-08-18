package limn.components;

import limn.video.VideoFrame;
import limn.video.VideoSurface;
import limn.video.VideoSurfaces;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link VideoSurfaces.Provider} with no device behind it: the seam that lets a widget create a
 * surface, upload to it and draw it with no GPU anywhere. The registry it installs into is
 * process-wide and outlives a test class, so every test that installs one must put it back.
 */
final class TestVideoSurfaces implements VideoSurfaces.Provider {

    /** Every surface this provider has handed out, in order. */
    final List<Surface> created = new ArrayList<>();

    @Override
    public VideoSurface createSurface() {
        Surface surface = new Surface();
        created.add(surface);
        return surface;
    }

    /** @return the surface handed out most recently, or null if none has been */
    Surface latest() {
        return created.isEmpty() ? null : created.get(created.size() - 1);
    }

    /** @return the presentation time of the last picture uploaded to any surface, or -1 */
    long lastPtsMicros() {
        long last = -1;
        for (Surface surface : created) {
            if (surface.uploads > 0) {
                last = surface.lastPtsMicros;
            }
        }
        return last;
    }

    /** @return uploads across every surface this provider has handed out */
    int totalUploads() {
        int total = 0;
        for (Surface surface : created) {
            total += surface.uploads;
        }
        return total;
    }

    /** Counts what a real surface would do to the device, and asserts the picture is still held. */
    static final class Surface implements VideoSurface {

        int uploads;
        long lastPtsMicros = -1;
        int disposals;
        boolean disposed;
        private int width;
        private int height;

        @Override
        public void upload(VideoFrame frame) {
            // Asking for a plane is what says the picture is still the caller's to read: a released
            // frame's planes belong to its producer again and this throws, which is the whole
            // release-after-upload ordering, checked rather than described.
            frame.plane(0);
            uploads++;
            lastPtsMicros = frame.ptsMicros();
            width = frame.width();
            height = frame.height();
        }

        @Override
        public boolean hasPicture() {
            return uploads > 0 && !disposed;
        }

        @Override
        public int widthPx() {
            return disposed ? 0 : width;
        }

        @Override
        public int heightPx() {
            return disposed ? 0 : height;
        }

        @Override
        public void resize(int widthPx, int heightPx) {
            throw new AssertionError("a video surface is the size of its picture; resize is a no-op "
                    + "the widget must never call");
        }

        @Override
        public void dispose() {
            disposals++;
            disposed = true;
        }
    }
}
