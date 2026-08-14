package limn.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import limn.concurrent.Ui;
import limn.concurrent.Work;

/**
 * A themeable vector {@link Icon}: keeps the SVG source and lazily rasterizes it
 * (via the installed {@link SvgRasterizer}) to a cached {@link Image} per
 * requested pixel size (a small LRU of {@value #MAX_CACHED_SIZES} sizes). Because it rasterizes at the <em>exact</em> device size it
 * is drawn at (see {@link Icon#paint}), an SVG icon stays crisp on HiDPI and at
 * any zoom: no blur from up/down-sampling a fixed bitmap. Draw it tinted to
 * recolor with the theme (SVG icons are single-color masks, so {@link #tintable()}
 * is {@code true}); draw it untinted to keep the SVG's own colors.
 *
 * <p>Each rasterized {@link Image} is cached and its identity is the GPU texture
 * key, so reusing one {@code SvgIcon} reuses a single texture per size across the
 * whole UI. Rasterization needs the backend running (the rasterizer is installed
 * at startup); call {@link #image()} during setup to warm the cache if desired.
 *
 * <p><b>An instance is confined to the UI thread</b>: the size cache is a plain
 * LRU map with no lock, and it is written by every miss. {@link #image(int)} and
 * {@link #image(int, boolean)} therefore reject any other thread once a backend is
 * running. The one way to move the parse and rasterize off that thread is
 * {@link #imageAsync(int)}, which does the work on the worker pool and folds the
 * result back in here.
 */
public final class SvgIcon implements Icon {

    /** Base resolution used by the no-arg {@link #image()} convenience. */
    private static final int BASE_PIXELS = 96;

    /**
     * Rasterized sizes kept per icon (LRU). Enough for a few scales and zoom
     * levels; without a bound, an animated size (hover-grow, pinch zoom) would
     * pin one bitmap + GPU texture per pixel size touched, forever.
     */
    // 16, not 8: an icon that follows the control-size ramp is rasterized at five logical
    // sizes, and each is a different pixel size per content scale. One icon on a 2x monitor
    // beside a 1x one already wants ten entries, so an 8-entry LRU thrashes on every frame
    // that paints two steps at once, which is the coexistence case the feature exists for.
    private static final int MAX_CACHED_SIZES = 16;

    private static volatile SvgRasterizer rasterizer;

    private final byte[] svg;
    private final Map<Integer, Image> bySize = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Image> eldest) {
            return size() > MAX_CACHED_SIZES;
        }
    };
    // Steady state paints the same size every frame: skip the map (and the
    // Integer boxing of its key) entirely.
    private int lastSize = -1;
    private Image lastImage;

    private SvgIcon(byte[] svg) {
        this.svg = svg;
    }

    /** An icon from raw SVG source. */
    public static SvgIcon of(String svg) {
        return new SvgIcon(Objects.requireNonNull(svg, "svg").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An icon from a classpath resource (e.g. {@code "/limn/demo/icons/search.svg"}).
     *
     * <p>Reads the resource on the calling thread and needs no asynchronous form: an icon is a few
     * kilobytes of text, read once from a jar the class loader already has open, and nothing is
     * parsed or rasterized here; the expensive half happens later, in {@link #image(int)}, which
     * does have one.
     */
    public static SvgIcon fromResource(String path) {
        Objects.requireNonNull(path, "path");
        try (InputStream in = SvgIcon.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("SVG resource not found: " + path);
            }
            return new SvgIcon(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SVG resource: " + path, e);
        }
    }

    // --------------------------------------------------------- rasterizer SPI

    /** Installs the backend rasterizer (called by the backend at startup). */
    public static void installRasterizer(SvgRasterizer newRasterizer) {
        rasterizer = Objects.requireNonNull(newRasterizer, "rasterizer");
    }

    /** Removes the rasterizer if it is still {@code expected}, so a late teardown cannot clear a newer one. */
    public static void uninstallRasterizer(SvgRasterizer expected) {
        if (rasterizer == expected) {
            rasterizer = null;
        }
    }

    /** Whether SVG icons can be rasterized at all; without one they draw nothing. */
    public static boolean isRasterizerInstalled() {
        return rasterizer != null;
    }

    // ---------------------------------------------------------------- render

    /**
     * Rasterized bitmap fit into {@code pixelSize}px (cached; stable identity, because that
     * identity is the GPU texture key).
     *
     * <p><b>A miss parses and rasterizes the SVG on the calling thread</b>, and the calling thread
     * is normally the UI thread inside {@link Icon#paint}, so the first paint at a new content
     * scale, at a new size step, or at each step of a zoom gesture, pays a parse and a rasterize
     * inside that frame. {@link #imageAsync(int)} is the form that pays it on the worker pool;
     * calling that ahead of the paint (on a scale change, or during setup) is what keeps the frame
     * clean. There is no asynchronous form of {@link Icon#image} itself, and there should not be:
     * a paint asking for a bitmap has to be answered during that paint.
     *
     * @param pixelSize target extent in device pixels; values below 1 are treated as 1
     * @throws IllegalStateException if called off the UI thread while a backend is running, or if
     *                               no {@link SvgRasterizer} is installed
     */
    public Image image(int pixelSize) {
        checkConfined();
        int size = Math.max(1, pixelSize);
        Image cached = cached(size);
        if (cached != null) {
            return cached;
        }
        Image raster = requireRasterizer().rasterize(svg, size);
        fold(size, raster);
        return raster;
    }

    /**
     * Rasterizes for {@code pixelSize} on the {@code Ui} worker pool and folds the bitmap into this
     * icon's cache on the UI thread, so the next {@link #image(int)} at that size is a hit and no
     * frame pays for the parse. Returned <b>unstarted</b>:
     *
     * <pre>{@code
     * icon.imageAsync(px)
     *     .onSuccess(bitmap -> requestRepaint())
     *     .deliverIf(this::isAttached)
     *     .start();
     * }</pre>
     *
     * <p>The delivered {@link Image} is the one this icon now has cached for that size, so drawing
     * it and drawing {@link #image(int)} share a single texture. A size already cached is delivered
     * without rasterizing anything again, but a size still <em>in flight</em> is not: unlike
     * {@link Images#loadShared}, two calls for one size before the first lands rasterize twice, and
     * the second bitmap replaces the first in the cache. Warm a size once, or hold the
     * {@code Job} and cancel it before asking again.
     *
     * <p>Cancelling stops the delivery, not the work: if the rasterize had already finished, the
     * bitmap is still folded into the cache; it is paid for either way, and the next paint at that
     * size may as well have it. A failure (no installed rasterizer, malformed SVG) reaches
     * {@code onFailure} on the UI thread; nothing is thrown from here.
     *
     * @param pixelSize target extent in device pixels; values below 1 are treated as 1
     * @throws IllegalStateException if called off the UI thread, or if no backend is running
     */
    public Work<Image> imageAsync(int pixelSize) {
        Ui.checkUiThread();
        int size = Math.max(1, pixelSize);
        Image cached = cached(size);
        if (cached != null) {
            return Ui.work(progress -> cached);
        }
        return Ui.work(progress -> {
            // Worker thread: rasterize here, but never touch the cache from here; the fold is
            // posted, and lands before this value is delivered because the queue is in order.
            Image raster = requireRasterizer().rasterize(svg, size);
            Ui.post(() -> fold(size, raster));
            return raster;
        });
    }

    /**
     * {@link Icon} entry point: rasterizes at the requested device size (theme
     * brightness is irrelevant; SVG icons recolor by tint, not by variant).
     * UI thread, with the miss cost described on {@link #image(int)}.
     */
    @Override
    public Image image(int pixelSize, boolean dark) {
        return image(pixelSize);
    }

    /** Base-resolution bitmap, a convenience for warming the cache or ad-hoc draws. UI thread. */
    public Image image() {
        return image(BASE_PIXELS);
    }

    // --------------------------------------------------------- cache, UI thread

    /** @return the cached bitmap for {@code size}, or null; touches the LRU's access order */
    private Image cached(int size) {
        if (size == lastSize) {
            return lastImage;
        }
        Image hit = bySize.get(size);
        if (hit != null) {
            lastSize = size;
            lastImage = hit;
        }
        return hit;
    }

    /** Publishes a finished raster as the bitmap for {@code size}. */
    private void fold(int size, Image raster) {
        bySize.put(size, raster);
        lastSize = size;
        lastImage = raster;
    }

    /**
     * Rejects a caller on the wrong thread, but only once a backend has bound one: an asset tool or
     * a headless test rasterizes with no {@code Ui} runtime at all, and has no UI thread to be on.
     */
    private static void checkConfined() {
        if (Ui.isInstalled()) {
            Ui.checkUiThread();
        }
    }

    private static SvgRasterizer requireRasterizer() {
        SvgRasterizer active = rasterizer;
        if (active == null) {
            throw new IllegalStateException("no SvgRasterizer installed. Is the backend started?");
        }
        return active;
    }
}
