package limn.graphics;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import limn.backend.spi.Installed;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.io.Resources;

/**
 * A themeable vector {@link Icon}: keeps the SVG source and lazily draws it
 * (via the installed {@link SvgRenderer}) to a cached {@link Image} per
 * requested pixel size (a small LRU of {@value #MAX_CACHED_SIZES} sizes). Because it rasterizes at the <em>exact</em> device size it
 * is drawn at (see {@link Icon#paint}), an SVG icon stays crisp on HiDPI and at
 * any zoom: no blur from up/down-sampling a fixed bitmap. Draw it tinted to
 * recolor with the theme (SVG icons are single-color masks, so {@link #tintable()}
 * is {@code true}); draw it untinted to keep the SVG's own colors.
 *
 * <p>Each rasterized {@link Image} is cached and its identity is the GPU texture
 * key, so reusing one {@code SvgIcon} reuses a single texture per size across the
 * whole UI. Drawing needs the backend running (the renderer is installed at startup); call
 * {@link #image()} during setup to warm the cache if desired.
 *
 * <p><b>An SVG of any aspect</b> is drawn fitted into the box it is given and centred, its longer
 * side the box's side, as {@link Icon#paint} draws any icon.
 *
 * <p><b>A source the renderer refuses draws nothing.</b> A malformed SVG, or one with no
 * size of its own, fails at every size, so the first refusal is remembered, logged once as a
 * warning, and answered from then on with an empty bitmap rather than a parse per paint: an
 * exception thrown from a paint is caught by the frame and counted against the window, and
 * enough of them stop the window repainting at all.
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

    private static final Installed<SvgRenderer> RENDERER = new Installed<>(
            "no SvgRenderer installed. Is the backend started?");

    /**
     * What an icon whose source was refused answers every size with: one transparent pixel,
     * shared, so that every broken icon draws nothing through a single texture.
     */
    private static final Image NOTHING = new Image(1, 1, new byte[4]);

    private static final System.Logger LOG = System.getLogger(SvgIcon.class.getName());

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
    // Set once, on the UI thread, by the first refusal of the source; never cleared, because
    // the source it judged cannot change.
    private RuntimeException refused;

    /** The folder of the file it was read from, whose pictures it may show; null for any other source. */
    private final java.nio.file.@org.jspecify.annotations.Nullable Path folder;
    /** The source as read and cleaned, made by the first drawing on whichever thread. */
    private volatile limn.graphics.internal.@org.jspecify.annotations.Nullable SvgSource source;

    private SvgIcon(byte[] svg) {
        this(svg, null);
    }

    private SvgIcon(byte[] svg, java.nio.file.@org.jspecify.annotations.Nullable Path folder) {
        this(svg, folder, 0);
    }

    private SvgIcon(byte[] svg, java.nio.file.@org.jspecify.annotations.Nullable Path folder, int frameRate) {
        this.svg = svg;
        this.folder = folder;
        this.frameRate = frameRate;
    }

    /** The system property that sets the rate every animated SVG plays at, unless its icon says. */
    public static final String FRAME_RATE_PROPERTY = "limn.svg.frameRate";

    /** The rate this icon was given, or 0 for the property's or the default. */
    private final int frameRate;
    /** The rate it plays at, settled at the first question, so its frames never change count. */
    private int rate;

    /**
     * The same SVG, playing at {@code framesPerSecond} if it is animated: its own rate, which wins
     * over {@value #FRAME_RATE_PROPERTY}. An SVG says nothing of a rate, since neither SMIL nor CSS
     * has one: both measure time, and the toolkit draws that time as frames, 24 a second unless
     * set, the rate of film.
     *
     * @param framesPerSecond from 1 to 120
     * @return a new icon of the same source
     * @throws IllegalArgumentException when the rate is out of that range
     */
    public SvgIcon withFrameRate(int framesPerSecond) {
        if (framesPerSecond < 1 || framesPerSecond > limn.graphics.internal.SvgAnimation.MAX_FRAME_RATE) {
            throw new IllegalArgumentException("a frame rate is 1 to "
                    + limn.graphics.internal.SvgAnimation.MAX_FRAME_RATE + " a second: " + framesPerSecond);
        }
        SvgIcon other = new SvgIcon(svg, folder, framesPerSecond);
        other.source = source;
        return other;
    }

    /** @return the rate it plays at: its own, else the system property's, else 24 */
    public int frameRate() {
        int settled = rate;
        if (settled == 0) {
            settled = frameRate;
            if (settled == 0) {
                Integer property = Integer.getInteger(FRAME_RATE_PROPERTY);
                settled = property == null ? limn.graphics.internal.SvgAnimation.DEFAULT_FRAME_RATE
                        : Math.max(1, Math.min(limn.graphics.internal.SvgAnimation.MAX_FRAME_RATE, property));
            }
            rate = settled;
        }
        return settled;
    }

    /** The animation it plays, or null for a still SVG and one whose source was refused. */
    private limn.graphics.internal.@org.jspecify.annotations.Nullable SvgAnimation animation() {
        if (refused != null) {
            return null;
        }
        try {
            return source().animation();
        } catch (limn.io.MalformedContentException damaged) {
            return null;
        }
    }

    /**
     * {@inheritDoc} An SVG is animated when it has SMIL or CSS animation, which the toolkit reads
     * and plays itself, a moment a frame. Reading it parses the source on the first call.
     */
    @Override
    public boolean isAnimated() {
        return animation() != null;
    }

    @Override
    public int frameCount() {
        limn.graphics.internal.SvgAnimation animation = animation();
        return animation == null ? 1 : animation.frameCount(frameRate());
    }

    @Override
    public int frameMillis(int frame) {
        java.util.Objects.checkIndex(frame, frameCount());
        limn.graphics.internal.SvgAnimation animation = animation();
        return animation == null ? 0 : animation.frameMillis(frameRate(), frame);
    }

    /** {@inheritDoc} One whose animations repeat without end plays without end; one whose all end plays once and rests. */
    @Override
    public int loopCount() {
        limn.graphics.internal.SvgAnimation animation = animation();
        return animation == null || animation.loops() ? 0 : 1;
    }

    /** The frames of an animated icon drawn at a size, kept while the frames of that size fit in their budget. */
    private final Map<Integer, Image[]> framesBySize = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Image[]> eldest) {
            return size() > 4;
        }
    };

    /** The most bytes of frames kept for one size: past it each frame is drawn as it is asked for. */
    private static final long FRAMES_BUDGET = 16L << 20;

    /**
     * {@inheritDoc} A frame of an animated SVG is drawn at its moment, fitted into the box; drawn
     * once and kept, for a size whose frames together fit in sixteen megabytes. UI thread.
     */
    @Override
    public Image image(int pixelSize, boolean dark, int frame) {
        java.util.Objects.checkIndex(frame, frameCount());
        if (frame == 0) {
            return image(pixelSize);
        }
        checkConfined();
        int size = Math.max(1, pixelSize);
        Image[] frames = framesBySize.get(size);
        if (frames != null && frames[frame] != null) {
            return frames[frame];
        }
        Image raster;
        try {
            raster = draw(requireRenderer(), size, frame);
        } catch (RuntimeException e) {
            refuse(e);
            return NOTHING;
        }
        if ((long) raster.width() * raster.height() * 4 * frameCount() <= FRAMES_BUDGET) {
            if (frames == null) {
                frames = new Image[frameCount()];
                framesBySize.put(size, frames);
            }
            frames[frame] = raster;
        }
        return raster;
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
        return new SvgIcon(Resources.bytes(SvgIcon.class, path, "SVG"));
    }

    /**
     * An icon from a file. A picture the file shows by an address relative to it is read from
     * the file's own folder, or from one inside it, and from nowhere else; no address is ever
     * read from the network.
     *
     * <p>Reads the file on the calling thread, as {@link #fromResource} reads a resource: an SVG is
     * text, and the pictures it shows are read when it is first drawn.
     *
     * @param file the SVG file
     * @return the icon
     * @throws java.io.UncheckedIOException when the file cannot be read
     * @throws limn.io.MalformedContentException when it is larger than an SVG is read
     */
    public static SvgIcon fromFile(java.nio.file.Path file) {
        java.util.Objects.requireNonNull(file, "file");
        try {
            if (java.nio.file.Files.size(file) > limn.graphics.internal.SvgXml.MAX_BYTES) {
                throw new limn.io.MalformedContentException("SVG", file + " is larger than the "
                        + limn.graphics.internal.SvgXml.MAX_BYTES + " bytes an SVG is read");
            }
            java.nio.file.Path absolute = file.toAbsolutePath();
            return new SvgIcon(java.nio.file.Files.readAllBytes(absolute), absolute.getParent());
        } catch (java.io.IOException failed) {
            throw new java.io.UncheckedIOException("cannot read " + file, failed);
        }
    }

    // --------------------------------------------------------- renderer SPI

    /** Installs the backend's renderer (called by the backend at startup). */
    public static void installRenderer(SvgRenderer newRenderer) {
        RENDERER.install(newRenderer);
    }

    /** Removes the renderer if it is still {@code expected}, so a late teardown cannot clear a newer one. */
    public static void uninstallRenderer(SvgRenderer expected) {
        RENDERER.uninstall(expected);
    }

    /** Whether SVG icons can be drawn at all; without a renderer they draw nothing. */
    public static boolean isRendererInstalled() {
        return RENDERER.isInstalled();
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
     * <p>A source the renderer refuses is answered with an empty bitmap, at this size and at
     * every size not already cached, and is not handed to the renderer again.
     *
     * @param pixelSize target extent in device pixels; values below 1 are treated as 1
     * @throws IllegalStateException if called off the UI thread while a backend is running, or if
     *                               no {@link SvgRenderer} is installed
     */
    public Image image(int pixelSize) {
        checkConfined();
        int size = Math.max(1, pixelSize);
        Image cached = cached(size);
        if (cached != null) {
            return cached;
        }
        if (refused != null) {
            return NOTHING;
        }
        SvgRenderer renderer = requireRenderer();
        Image raster;
        try {
            raster = draw(renderer, size, 0);
        } catch (RuntimeException e) {
            refuse(e);
            return NOTHING;
        }
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
     *     .deliverIf(this::isShowing)
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
     * size may as well have it. A failure (no installed renderer, malformed SVG) reaches
     * {@code onFailure} on the UI thread; nothing is thrown from here. A refused source is
     * remembered as {@link #image(int)} remembers it, and asking again fails with the same cause
     * without rasterizing.
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
        RuntimeException known = refused;
        if (known != null) {
            return Ui.work(progress -> {
                throw known;
            });
        }
        return Ui.work(progress -> {
            // Worker thread: rasterize here, but never touch the cache from here; the fold is
            // posted, and lands before this value is delivered because the queue is in order.
            // A refusal is posted the same way, and still thrown for onFailure to hear.
            SvgRenderer renderer = requireRenderer();
            Image raster;
            try {
                raster = draw(renderer, size, 0);
            } catch (RuntimeException e) {
                Ui.post(() -> refuse(e));
                throw e;
            }
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

    /**
     * @return its own width, in its own units, which the toolkit takes as logical points: what an
     *         image view showing it measures at when nothing else sets its size. 0 for a source the
     *         renderer refuses. Read on the first call, which opens the document on this thread
     * @throws IllegalStateException if no {@link SvgRenderer} is installed
     */
    public float width() {
        return size()[0];
    }

    /** @return its own height; see {@link #width()} */
    public float height() {
        return size()[1];
    }

    /** Width and height, read once. */
    private volatile float @org.jspecify.annotations.Nullable [] size;

    private float[] size() {
        float[] known = size;
        if (known == null) {
            known = new float[2];
            if (refused == null) {
                try (SvgRenderer.Document document = requireRenderer().open(source().text())) {
                    known[0] = document.width();
                    known[1] = document.height();
                } catch (limn.io.MalformedContentException damaged) {
                    // Refused: it measures nothing and draws nothing, as image() says.
                }
            }
            size = known;
        }
        return known;
    }

    /**
     * An SVG file drawn at its own size, one pixel to one of its units, fitted within the
     * renderer's largest drawing when it is larger. What {@link Images#decode} answers for an SVG.
     * Any thread.
     */
    static Image decodeAtOwnSize(byte[] file) {
        SvgRenderer renderer = requireRenderer();
        limn.graphics.internal.SvgSource source = limn.graphics.internal.SvgSource.read(file, null);
        try (SvgRenderer.Document document = renderer.open(source.text())) {
            float scale = Math.min(1f, OWN_SIZE_CEILING / Math.max(document.width(), document.height()));
            int w = Math.max(1, (int) Math.ceil(document.width() * scale - 0.001f));
            int h = Math.max(1, (int) Math.ceil(document.height() * scale - 0.001f));
            limn.graphics.internal.SvgAnimation animation = source.animation();
            if (animation == null) {
                return document.render(w, h);
            }
            // An animated SVG is an animated image, every frame drawn now, while they fit in the
            // animation budget; one larger is its first frame, and an SvgIcon is what plays it.
            int rate = new SvgIcon(file).frameRate();
            int count = animation.frameCount(rate);
            limn.graphics.internal.SvgAnimation.Applied applied = new limn.graphics.internal.SvgAnimation.Applied();
            if ((long) w * h * 4 * count > Images.animationBudget()) {
                animation.apply(document, applied, animation.timeOf(rate, 0));
                return document.render(w, h);
            }
            java.util.List<Image> frames = new java.util.ArrayList<>(count);
            int[] millis = new int[count];
            for (int f = 0; f < count; f++) {
                animation.apply(document, applied, animation.timeOf(rate, f));
                frames.add(document.render(w, h));
                millis[f] = animation.frameMillis(rate, f);
            }
            return count == 1 ? frames.get(0) : Images.animation(frames, millis, animation.loops() ? 0 : 1);
        }
    }

    /** The longest side an SVG decoded at its own size is drawn at. */
    private static final float OWN_SIZE_CEILING = 8192;

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

    /** Remembers that the renderer refused this source, and says so once. */
    private void refuse(RuntimeException cause) {
        if (refused != null) {
            return;
        }
        refused = cause;
        LOG.log(System.Logger.Level.WARNING,
                "An SVG icon's source was refused by the renderer; the icon draws nothing", cause);
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

    private static SvgRenderer requireRenderer() {
        return RENDERER.require();
    }

    /**
     * The source as the renderer is given it: read in Java, within ceilings, its references to
     * anything outside it taken out and its pictures read by the toolkit. Made once; two threads
     * racing to make it make the same thing.
     */
    private limn.graphics.internal.SvgSource source() {
        limn.graphics.internal.SvgSource read = source;
        if (read == null) {
            read = limn.graphics.internal.SvgSource.read(svg, folder);
            source = read;
        }
        return read;
    }

    /**
     * Opens the source, draws one of its frames fitted into a square of {@code size}, and closes it.
     * Any thread: the document is this call's own.
     */
    private Image draw(SvgRenderer renderer, int size, int frame) {
        limn.graphics.internal.SvgSource read = source();
        try (SvgRenderer.Document document = renderer.open(read.text())) {
            limn.graphics.internal.SvgAnimation animation = read.animation();
            if (animation != null) {
                animation.apply(document, new limn.graphics.internal.SvgAnimation.Applied(),
                        animation.timeOf(frameRate(), frame));
            }
            float scale = size / Math.max(document.width(), document.height());
            int w = Math.max(1, Math.round(document.width() * scale));
            int h = Math.max(1, Math.round(document.height() * scale));
            return document.render(w, h);
        }
    }
}
