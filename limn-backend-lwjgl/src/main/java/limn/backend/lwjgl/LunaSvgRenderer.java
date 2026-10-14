package limn.backend.lwjgl;

import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import limn.graphics.Image;
import limn.graphics.SvgRenderer;
import limn.io.MalformedContentException;

/**
 * The backend's {@link SvgRenderer}: lunasvg and plutovg, through {@code liblimnsvg}.
 *
 * <p>When the library could not be loaded, every document refuses with the reason, which an
 * {@code SvgIcon} logs once and answers with nothing drawn.
 */
final class LunaSvgRenderer implements SvgRenderer {

    /** The largest side a drawing is given: 8192 pixels, a quarter of a gigabyte of picture. */
    static final int MAX_PIXEL_SIZE = 8192;

    /** Closes a document an application let go of without closing, so its native memory goes with it. */
    private static final Cleaner CLEANER = Cleaner.create(runnable -> {
        Thread thread = new Thread(runnable, "limn-svg-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Document open(byte[] svg) {
        Objects.requireNonNull(svg, "svg");
        if (!LunaSvgLibrary.isAvailable()) {
            throw new IllegalStateException("the SVG renderer is not loaded: " + LunaSvgLibrary.failure());
        }
        long handle = LunaSvgNative.open(svg);
        if (handle == 0) {
            throw new MalformedContentException("SVG", "invalid SVG");
        }
        float[] size = new float[2];
        if (!LunaSvgNative.size(handle, size) || !(size[0] > 0) || !(size[1] > 0)
                || Float.isInfinite(size[0]) || Float.isInfinite(size[1])) {
            LunaSvgNative.close(handle);
            throw new MalformedContentException("SVG", "SVG has no intrinsic size");
        }
        return new LunaDocument(handle, size[0], size[1]);
    }

    @Override
    public boolean addFont(String family, boolean bold, boolean italic, byte[] data) {
        return LunaSvgLibrary.isAvailable() && LunaSvgNative.addFont(
                family.getBytes(StandardCharsets.UTF_8), bold, italic, data.clone());
    }

    /** The handle, apart from the document, so the cleaner holds this and not what it cleans. */
    private static final class Handle implements Runnable {
        private long value;

        Handle(long value) {
            this.value = value;
        }

        @Override
        public synchronized void run() {
            if (value != 0) {
                LunaSvgNative.close(value);
                value = 0;
            }
        }

        synchronized long get() {
            if (value == 0) {
                throw new IllegalStateException("the SVG document is closed");
            }
            return value;
        }
    }

    private static final class LunaDocument implements Document {

        private final Handle handle;
        private final Cleaner.Cleanable cleanable;
        private final float width;
        private final float height;

        LunaDocument(long handle, float width, float height) {
            this.handle = new Handle(handle);
            this.cleanable = CLEANER.register(this, this.handle);
            this.width = width;
            this.height = height;
        }

        @Override
        public float width() {
            return width;
        }

        @Override
        public float height() {
            return height;
        }

        @Override
        public boolean setAttribute(String id, String name, String value) {
            return LunaSvgNative.setAttribute(handle.get(), id.getBytes(StandardCharsets.UTF_8),
                    name.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Image render(int width, int height) {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("an SVG is drawn at least 1 pixel a side: " + width + "x" + height);
            }
            if (width > MAX_PIXEL_SIZE || height > MAX_PIXEL_SIZE) {
                throw new IllegalArgumentException("an SVG of " + width + "x" + height + " pixels is above the "
                        + MAX_PIXEL_SIZE + " a side a drawing is given");
            }
            byte[] rgba = new byte[width * height * 4];
            if (!LunaSvgNative.render(handle.get(), width, height, rgba)) {
                throw new MalformedContentException("SVG", "the SVG could not be drawn at " + width + "x" + height);
            }
            return new Image(width, height, rgba);
        }

        @Override
        public void close() {
            cleanable.clean();
        }
    }
}
