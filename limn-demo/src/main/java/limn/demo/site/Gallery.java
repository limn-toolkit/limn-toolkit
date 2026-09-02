package limn.demo.site;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.demo.SiteShowcase;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.scene.Scene;
import limn.scene.Widget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders every gallery entry twice (once in {@code Limn}, once in {@code Limn Light})
 * and writes {@code gallery.json} beside the PNGs.
 *
 * <p>The site renders its component pages <em>from that manifest</em>, so adding a scene to
 * {@link GalleryScenes#entries()} adds it to the site and nobody maintains a list twice.
 *
 * <p><b>One process and one window for the whole set.</b> The obvious shape (one JVM per
 * capture, the way {@code --screenshot} is used by hand) costs a backend start, a GL
 * context and a font load every time; measured on a headless Linux runner that is around
 * fifteen seconds each, so forty captures would be ten minutes of process startup. Here the
 * event loop advances a cursor: render the current entry, capture it, build the next, ask
 * for another frame, and close when the list runs out.
 *
 * <p>Both palettes are one manifest entry with two image paths, not two entries: they are
 * the same component, and the page swaps the image with the theme toggle.
 *
 * <p>Run: {@code ./gradlew :limn-demo:captureGallery} (adds {@code -XstartOnFirstThread} on
 * macOS and never anywhere else). Needs a GL context; on a headless Linux machine run it
 * under {@code xvfb-run -a} with {@code LIBGL_ALWAYS_SOFTWARE=1}.
 */
public final class Gallery {

    /**
     * Frames rendered before the capture. The first lays out; the rest let every
     * transition settle: with {@code GalleryScenes}' stepped clock each frame is a fixed
     * 20 ms of scene time, so this is 480 ms and the longest animation in the theme is
     * 220 ms. Wall-clock warmup cannot do this: the loop renders faster than the fades run,
     * so the Dialog was captured half-transparent no matter how many frames were drawn.
     */
    private static final int WARMUP_FRAMES = 24;

    /**
     * Wall-clock milliseconds a shot keeps rendering past its warmup frames before the
     * shutter, for the few that ask. Zero for the component entries, which are deterministic
     * scene-time stills, and zero for most showcase screens too: this is paid per palette, in
     * seconds, and it is charged only to {@code SiteShowcase.Entry.settles}.
     *
     * <p>A showcase capture is a whole application screen, and the kitchen sink's performance
     * footer latches its readout on a once-per-second wall-clock heartbeat: every gauge shows
     * a dash until the first beat, and the CPU gauge until the second, because the process CPU
     * probe reports nothing before a prior read has given it a baseline. Frames cannot buy
     * this (the loop renders far faster than the heartbeat), so raising
     * {@link #WARMUP_FRAMES} instead, the obvious edit, captures the dashes no matter how high
     * it goes. Two beats plus scheduling slack is the floor; a value below that puts the empty
     * footer back on the site's front page.
     */
    private static final long SHOWCASE_SETTLE_MS = 2_600;

    /**
     * The cadence a settling shot renders at: one frame per {@link Ui#postDelayed} interval
     * instead of back-to-back. Requesting the next frame directly (the edit that makes the
     * settle "finish faster") lets an invisible window render thousands of frames a second,
     * and the footer then latches exactly that: a four-digit FPS and a whole core of CPU, a
     * capture advertising the spin loop rather than the toolkit. Sixteen milliseconds is a
     * 60 Hz display's frame, the number a reader can compare with the machine in front of
     * them.
     */
    private static final long SETTLE_PACE_MS = 16;

    /**
     * Wall-clock milliseconds a shot may keep retrying after its still comes back as one
     * flat colour, before the run fails. A uniform frame is a scene that has not actually
     * drawn: the 3D showcase intermittently renders nothing but its cleared sky, and the
     * published site carried that rectangle because nothing here looked at the pixels.
     * Accepting the frame anyway is that regression; retrying without a bound is a build
     * that hangs until the watchdog guesses, minutes later, with the cause unnamed.
     */
    private static final long FLAT_RETRY_MS = 10_000;

    /** Captured at 2×, so the site can emit a `@2x` derivative without upscaling. */
    private static final float SCALE = 2f;

    /**
     * The one canvas every entry is drawn on, in logical points. Fixed rather than
     * per-entry: see {@link GalleryScenes.Built}. Sized for the LARGEST scene (the colour
     * picker), so nothing is ever clipped; the empty margin every smaller entry gets is
     * trimmed off afterwards by the site's image step, which is where cropping is cheap and
     * has no framebuffer to race.
     */
    /**
     * A whole application window, for the showcase captures, in logical points.
     *
     * <p>Sized for the page rather than for the scene: the site lays these out up to about
     * a thousand CSS pixels wide, and a capture much narrower than that is upscaled and
     * soft. Much wider only costs bytes.
     *
     * <p><b>This is the size the published asset is, and the capture is not.</b> The
     * framebuffer these windows get depends on the monitor the capture ran on (the same
     * command produces a 2× file on one machine and a 4× file on another), so the site's
     * image step resizes every showcase capture down to exactly twice this, and the manifest
     * carries the number for it to resize to.
     */
    private static final int SHOWCASE_WIDTH = 1024;
    private static final int SHOWCASE_HEIGHT = 700;

    private static final int CANVAS_WIDTH = 480;
    private static final int CANVAS_HEIGHT = 470;

    private Gallery() {
    }

    /** One palette an entry is captured in, and the file name suffix that identifies it. */
    private record Palette(String key, Theme theme) {
    }

    private static final List<Palette> PALETTES =
            List.of(new Palette("dark", Theme.limn()), new Palette("light", Theme.limnLight()));

    /**
     * How many frames each filmed entry actually produced, filled by the driver as it
     * captures and read by the manifest afterwards.
     *
     * <p>Counted rather than declared: a script's length depends on the widgets it aims at,
     * which do not exist until the scene is laid out. A number written by hand here would be
     * a promise about files, and the site fails the build over a file the manifest promised
     * and the capture did not write.
     */
    private static final java.util.Map<String, Integer> FILM_FRAMES =
            new java.util.LinkedHashMap<>();

    /**
     * The filmed component's box in logical points, measured after layout.
     *
     * <p>The site crops a film to this instead of trimming the pixels. Trimming finds the
     * INK, which for a component that is mostly background (a split pane is two labels and
     * a hairline) is far narrower than the component, and anything that then moves moves
     * straight out of the crop. The capture knows the geometry exactly; guessing it from the
     * pixels is what produced a film of a divider leaving the frame.
     */
    private static final java.util.Map<String, String> FILM_CONTENT =
            new java.util.LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: Gallery <output-directory>");
            System.exit(2);
        }
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);

        List<GalleryEntry> entries = GalleryScenes.entries();
        // Every (entry, palette) pair, flattened, so the event loop walks one cursor. The
        // locale is pinned per entry rather than left to the JVM default: several
        // components carry translated text of their own (the search field's placeholder is
        // the one that shows), and a capture run on a machine set to another language
        // published that language onto an English page.
        //
        // The layout direction is pinned beside it, and for the same reason in a second
        // dimension: it is a process default that any earlier code in this JVM may have moved —
        // the showcase's one right-to-left entry moves it on purpose — and a component still
        // published inside out is the same bug wearing a different hat. The component gallery
        // is English left-to-right by decision; the mirrored window belongs to the showcase,
        // where a whole screen can show it honestly.
        List<Shot> shots = new ArrayList<>();
        for (GalleryEntry entry : entries) {
            GalleryEntry english = new GalleryEntry(entry.id(), entry.title(), entry.region(),
                    () -> {
                        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
                        limn.scene.LayoutDirection.setProcessDefault(
                                limn.scene.LayoutDirection.LTR);
                        return entry.builder().get();
                    },
                    // Attached here rather than in the entry list, so that the scene
                    // functions (whose text the site publishes as the code sample) carry
                    // none of the filming.
                    Films.forEntry(entry.id()));
            for (Palette palette : PALETTES) {
                shots.add(new Shot(english, palette, outDir.resolve(
                        entry.id() + "-" + palette.key() + "@2x.png"), null, 0));
            }
        }

        try (Backend backend = new LwjglBackend()) {
            // Invisible window: the capture must not depend on a desktop, and on CI there
            // is none. Sized ONCE: every entry shares this canvas.
            float monitorScale = 1f;
            NativeWindow probe = backend.createWindow(
                    new WindowConfig("Limn UI: gallery", CANVAS_WIDTH, CANVAS_HEIGHT, false, true));
            monitorScale = probe.contentScale();
            // Size the window so the framebuffer is exactly canvas × SCALE device pixels,
            // whatever this monitor's own scale is; otherwise the same command produces a
            // different PNG on a Retina machine and on a CI runner.
            probe.setSize(Math.round(CANVAS_WIDTH * SCALE / monitorScale),
                    Math.round(CANVAS_HEIGHT * SCALE / monitorScale));
            probe.overrideContentScale(SCALE);
            NativeWindow window = probe;
            // The showcase gets a window of its own rather than sharing this one: its
            // canvas is a whole application window, and resizing between the two sets is
            // the race this design exists to avoid. Both windows are created up front and
            // the loop runs until both close.
            NativeWindow big = backend.createWindow(new WindowConfig(
                    "Limn UI: showcase", SHOWCASE_WIDTH, SHOWCASE_HEIGHT, false, true));
            big.setSize(Math.round(SHOWCASE_WIDTH * SCALE / monitorScale),
                    Math.round(SHOWCASE_HEIGHT * SCALE / monitorScale));
            big.overrideContentScale(SCALE);

            // ONE list, one driver, one shot at a time; see Shot's note.
            List<Shot> all = new ArrayList<>();
            for (Shot shot : shots) {
                all.add(new Shot(shot.entry(), shot.palette(), shot.file(), window,
                        shot.settleMillis()));
            }
            List<Shot> showcase = showcaseShots(outDir, big);
            // The showcase window's FIRST capture is a warm-up, taken and thrown away.
            //
            // Twice now (30 Aug and 2 Sep 2026) the site published the kitchen sink's dark
            // capture with a menu bar, a toolbar and nothing else — no title, no tabs, no form,
            // no footer — while its light capture and every other dark capture were complete.
            // The kitchen dark shot is the first one on this window, and the only shot that is;
            // the same command on a workstation draws it whole, so what fails is the first frame
            // the showcase window ever presents under the runner's software rasteriser, not the
            // scene. The 3D entry already pays a second pass for a first frame it cannot trust
            // (SiteShowcase.Entry.warmUpPass). Rather than mark the kitchen too and leave the
            // next entry that lands first to rediscover this, the window itself is warmed: its
            // first shot is a copy of the first real one, written beside the real captures and
            // deleted below, so no real capture is ever the window's first.
            Path warmUp = outDir.resolve(".showcase-warmup@2x.png");
            if (!showcase.isEmpty()) {
                Shot first = showcase.get(0);
                all.add(new Shot(first.entry(), first.palette(), warmUp, big, first.settleMillis()));
            }
            all.addAll(showcase);

            Driver driver = new Driver(all, List.of(window, big));
            driver.start();
            backend.runEventLoop();
            Files.deleteIfExists(warmUp);
            if (driver.failed()) {
                System.exit(1);
            }
            Files.writeString(outDir.resolve("showcase.json"), showcaseManifest(),
                    StandardCharsets.UTF_8);
        }

        Files.writeString(outDir.resolve("gallery.json"), manifest(entries), StandardCharsets.UTF_8);
        System.out.printf("gallery: %d entr%s × %d palette(s) → %s%n",
                entries.size(), entries.size() == 1 ? "y" : "ies", PALETTES.size(),
                outDir.toAbsolutePath());
    }

    /**
     * One capture, and the window it is taken in.
     *
     * <p>The window is carried per shot because {@code Theme.setCurrent} and
     * {@code I18n.setLocale} are process-wide. Two drivers advancing two windows in one
     * event loop race over both: the showcase captures came out with a dark canvas and
     * light-palette surfaces, because the other driver had switched the palette between
     * this one's build and its paint. One driver, one shot at a time, removes the race.
     *
     * <p>{@code settleMillis} is wall-clock the shutter waits past the warmup frames:
     * {@link #SHOWCASE_SETTLE_MS} for a showcase screen, zero for a component still.
     */
    private record Shot(GalleryEntry entry, Palette palette, Path file, NativeWindow window,
                        long settleMillis) {
    }

    /**
     * The showcase set, expressed as ordinary gallery entries so one driver renders both.
     * Its scene builder closes over the locale and sets it before constructing.
     */
    private static List<Shot> showcaseShots(Path outDir, NativeWindow window) {
        List<Shot> shots = new ArrayList<>();
        for (SiteShowcase.Entry entry : SiteShowcase.entries()) {
            for (Palette palette : PALETTES) {
                // A palette-invariant entry renders the same pixels in both palettes AND both
                // passes write the same file, so the second pass buys nothing but a second
                // settle wait, measured at 2.6 s each, which is 18 s of this run spent
                // overwriting bytes with identical bytes. One pass, unless the entry asked for
                // the warm-up (see Entry.warmUpPass, which is what the 3D screen needs).
                if (entry.paletteInvariant() && !entry.warmUpPass()
                        && !palette.key().equals(INVARIANT_KEY)) {
                    continue;
                }
                // A showcase entry is a whole application window, and what it is evidence of is
                // the screen rather than a gesture, so it gets no pointer and no film unless it
                // asked for one. The theme editor asks: what it claims is a window re-skinning
                // while you drag, and a still of that is a form with colour wells in it.
                GalleryEntry adapted = new GalleryEntry(
                        "showcase-" + entry.id(), entry.title(), null,
                        () -> {
                            limn.i18n.I18n.setLocale(entry.locale());
                            // Per entry, exactly like the locale beside it: the process default
                            // is sticky, so the entry after the right-to-left one would
                            // otherwise photograph mirrored.
                            limn.scene.LayoutDirection.setProcessDefault(entry.direction());
                            Scene scene = entry.builder().apply(palette.theme());
                            return entry.filmed()
                                    ? GalleryScenes.filmable(scene, scene.root())
                                    : new GalleryScenes.Built(scene, null, null, null);
                        },
                        entry.filmed() ? Films.forShowcase(entry.id()) : null);
                // Per entry, not per pass. The settle used to be blanket, and it was the
                // single largest thing in this run's wall clock: a screen with nothing on a
                // real clock in it renders its final pixels in the warm-up frames and then
                // waited 2.6 s anyway. Two entries ask for it (SiteShowcase.Entry.settling);
                // the rest photograph as soon as they have drawn, exactly as the component
                // stills already did.
                shots.add(new Shot(adapted, palette, outDir.resolve(
                        showcaseFile(entry, palette)), window,
                        entry.settles() ? SHOWCASE_SETTLE_MS : 0));
            }
        }
        return shots;
    }

    /**
     * The palette key a palette-invariant entry's single file is named with. Both manifest keys
     * point at it, so the site can ask for either theme and get the one file.
     */
    private static final String INVARIANT_KEY = "light";

    /**
     * Where one showcase shot lands. A palette-invariant entry uses one file for both palettes,
     * which is what lets {@code showcaseShots} skip the second pass, and, for an entry that
     * keeps the warm-up pass, what makes the second pass overwrite the first.
     */
    private static String showcaseFile(SiteShowcase.Entry entry, Palette palette) {
        String key = entry.paletteInvariant() ? INVARIANT_KEY : palette.key();
        return "showcase-" + entry.id() + "-" + key + "@2x.png";
    }

    /** The showcase manifest. No region: these are screens, and they carry no snippet. */
    private static String showcaseManifest() {
        List<SiteShowcase.Entry> entries = SiteShowcase.entries();
        StringBuilder json = new StringBuilder("{\n  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            SiteShowcase.Entry entry = entries.get(i);
            json.append("    {\n")
                    .append("      \"id\": ").append(quote(entry.id())).append(",\n")
                    .append("      \"title\": ").append(quote(entry.title())).append(",\n")
                    .append("      \"locale\": ").append(quote(entry.locale().toLanguageTag()))
                    .append(",\n")
                    .append("      \"images\": {\n")
                    .append("        \"dark\": ")
                    .append(quote(showcaseFile(entry, PALETTES.get(0)))).append(",\n")
                    .append("        \"light\": ")
                    .append(quote(showcaseFile(entry, PALETTES.get(1))))
                    .append("\n      },\n")
                    // The size the PUBLISHED asset is, in points. The capture's own pixel
                    // size depends on the monitor it ran on; the site resizes to this.
                    .append("      \"points\": ").append(SHOWCASE_WIDTH).append(",\n")
                    .append("      \"scale\": 2");
            // Counted by the driver, never declared: a number written here is a promise about
            // files, and the site fails the build over a frame the manifest promised and the
            // capture did not write.
            Integer frames = FILM_FRAMES.get("showcase-" + entry.id());
            if (frames != null) {
                json.append(",\n      \"frames\": ").append(frames)
                        .append(",\n      \"frameMs\": ").append(GalleryScenes.STEP_MS);
            }
            json.append("\n    }").append(i == entries.size() - 1 ? "\n" : ",\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    /**
     * Walks the shot list inside the frame callback. Kept as a small object rather than a
     * pile of arrays because the cursor and the warmup counter have to move together, and
     * a lambda capturing two mutable boxes is where an off-by-one hides.
     */
    private static final class Driver {

        private final List<Shot> shots;
        private final List<NativeWindow> windows;
        private NativeWindow window;
        private int index = -1;
        private int frames;
        private long totalFrames;
        private boolean failed;
        private Scene scene;
        private GalleryScenes.Built built;
        /**
         * The film in progress, or null while capturing a still. It is advanced one frame per
         * rendered frame and holds its own playhead: a film resolves each step's widget as
         * that step begins, so there is no list of positions to index into.
         */
        private Motion.Film film;
        /** Frames of it already captured, which is also the number in the file name. */
        private int frameIndex;
        private boolean buttonDown;
        /** When the current shot was bound, the zero its settle wait is measured from. */
        private long shotStartNanos;
        /** The shot a flat-frame warning was already printed for, so a retry logs once. */
        private int flatWarnedIndex = -1;
        /** Last frame's still, delivered by the capture sink; judged at the next frame. */
        private Image still;
        /** Whether a capture is scheduled whose sink has not delivered yet. */
        private boolean stillPending;
        /** The watchdog's frame ceiling for the whole run; see the constructor. */
        private final long frameBudget;

        Driver(List<Shot> shots, List<NativeWindow> windows) {
            this.shots = shots;
            this.windows = windows;
            // The watchdog's ceiling. Settle waits and flat-frame retries are wall-clock,
            // and a settling window renders at whatever rate it achieves: headless that is
            // hundreds of frames a second, because a scene with any live animation defeats
            // the SETTLE_PACE_MS throttle. Budgeting those shots by warmup frames alone is
            // the edit that starved this run one shot from the end after a legitimate
            // retry; two frames per waited millisecond covers the fastest observed rate
            // with room, while keeping a genuine hang a bounded failure.
            long settle = 0;
            for (Shot shot : shots) {
                if (shot.settleMillis() > 0) {
                    settle += (shot.settleMillis() + FLAT_RETRY_MS) * 2;
                }
            }
            this.frameBudget = WARMUP_FRAMES * shots.size() * 12L + 128 + settle;
        }

        /** Both windows stay open until the last shot, then close together. */
        private void closeAll() {
            for (NativeWindow each : windows) {
                each.requestClose();
            }
        }

        boolean failed() {
            return failed;
        }

        void start() {
            advance();
            window.requestFrame();
        }

        /**
         * Re-installed after every {@link Scene#bind}, and that is not belt-and-braces:
         * {@code bind} sets a frame callback of its own, so a callback installed once at
         * start-up is silently replaced by the first bind. The window then renders the
         * scene forever, captures nothing and never closes: a hang with no output, which
         * is exactly how this was found.
         */
        private void installCallback() {
            NativeWindow owner = window;
            window.setFrameCallback((renderer, frame) -> {
                // Only the current shot's window may run this body. The other window
                // keeps the callback from ITS last shot, and leaked timers from scenes
                // bound there keep delivering late frames for minutes; a body that ran
                // anyway rendered the CURRENT scene into that window's GL context, where
                // the scene's per-context 3D resources (its render target, its mesh
                // VAOs) mean something else entirely. That is what intermittently
                // published the 3D showcase as a rectangle of cleared sky: dropping
                // this guard re-opens exactly that corruption.
                if (owner != window) {
                    return;
                }
                if (scene != null) {
                    // One frame of scene time, once, before anything reads the clock. The
                    // scene reads it several times a frame; a clock that advanced on every
                    // read ran nine times too fast; see GalleryScenes.FrameClock.
                    if (built != null && built.clock() != null) {
                        built.clock().advance();
                    }
                    // The pointer moves BEFORE the render, never after: an event delivered
                    // to a scene that has already drawn shows up one frame late, and a
                    // press would land in the frame after the one the arrow is down in.
                    if (film != null) {
                        applyFilmStep();
                    }
                    scene.renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs());
                }
                // A capture that never happens must end the run rather than spin. Without
                // this, any future mistake in the cursor is an unbounded wait instead of a
                // failed build, which is exactly how the first version of this driver
                // behaved when Scene.bind replaced its frame callback.
                if (++totalFrames > frameBudget) {
                    System.err.println("gallery: watchdog (no progress), giving up at shot "
                            + index + " of " + shots.size());
                    failed = true;
                    closeAll();
                    return;
                }
                if (scene != null && film != null) {
                    // A filmed frame: this one is already on screen with its pointer in it.
                    Shot shot = shots.get(index);
                    renderer.captureFramebuffer(frameFile(shot, frameIndex));
                    if (++frameIndex >= film.frames()) {
                        System.out.println("  " + shot.file().getFileName()
                                + " + " + film.frames() + " frame(s)");
                        FILM_FRAMES.put(shot.entry().id(), film.frames());
                        film = null;
                        scene = null;
                    }
                } else if (scene != null && ++frames >= WARMUP_FRAMES && settled()) {
                    Shot shot = shots.get(index);
                    // The still is inspected before it is accepted: a frame whose every
                    // pixel is one colour is a scene that has not drawn, not a picture of
                    // one. Writing it to disk anyway is how the site published a flat
                    // rectangle for the 3D showcase: the file exists, the manifest is
                    // satisfied, and nothing downstream can tell a sky from a screenshot.
                    //
                    // Two frames per still, by the capture's own contract: the sink runs
                    // late in the frame that scheduled it, so the pixels are judged at the
                    // top of the frame after; deciding at the call site reads a capture
                    // that has not happened yet.
                    if (still == null && !stillPending) {
                        stillPending = true;
                        renderer.captureFramebuffer(image -> still = image);
                    } else if (still != null && uniform(still)) {
                        still = null;
                        stillPending = false;
                        if (flatWarnedIndex != index) {
                            flatWarnedIndex = index;
                            System.err.println("gallery: " + shot.file().getFileName()
                                    + " came back as one flat colour: the scene has not"
                                    + " actually drawn; retrying");
                        }
                        if (System.nanoTime() - shotStartNanos
                                > (shot.settleMillis() + FLAT_RETRY_MS) * 1_000_000L) {
                            System.err.println("gallery: " + shot.file().getFileName()
                                    + " never rendered anything; failing rather than"
                                    + " publishing a blank capture");
                            failed = true;
                            closeAll();
                            return;
                        }
                        // Fall through to the frame request below and try again.
                    } else if (still != null) {
                        Images.save(still, ImageFormat.PNG, shot.file());
                        still = null;
                        stillPending = false;
                        // The still is the poster, and it is captured before any pointer
                        // exists, so an entry that is filmed and one that is not still open
                        // the page the same way. The film starts on the next frame: the
                        // layout has settled by now, which is what makes the script's
                        // widget targets resolvable.
                        Motion motion = shot.entry().film() == null ? null
                                : shot.entry().film().apply(built);
                        if (motion == null) {
                            System.out.println("  " + shot.file().getFileName());
                            scene = null;
                        } else {
                            film = motion.film();
                            frameIndex = 0;
                            buttonDown = false;
                            Widget content = built.content();
                            if (content != null) {
                                FILM_CONTENT.put(shot.entry().id(), String.format(
                                        java.util.Locale.ROOT,
                                        "{ \"x\": %.1f, \"y\": %.1f, \"width\": %.1f, \"height\": %.1f }",
                                        content.localToSceneX(), content.localToSceneY(),
                                        content.width(), content.height()));
                            }
                        }
                    }
                }
                if (scene == null && !advance()) {
                    closeAll();
                    return;
                }
                // A shot with a settle wait is paced; everything else renders flat out. The
                // request goes through the UI queue with a delay rather than straight back to
                // the window, and that indirection IS the throttle; see SETTLE_PACE_MS for
                // what removing it puts in the published footer.
                if (shots.get(index).settleMillis() > 0) {
                    NativeWindow paced = window;
                    Ui.postDelayed(paced::requestFrame, SETTLE_PACE_MS);
                } else {
                    window.requestFrame();
                }
            });
        }

        /**
         * Whether the current shot's settle wait has passed. Wall clock, not frames, on
         * purpose: {@link #WARMUP_FRAMES} is scene time for transitions, while the readouts
         * the settle exists for (the performance footer's gauges) latch on real-time
         * heartbeats that no number of frames can hurry.
         */
        private boolean settled() {
            return System.nanoTime() - shotStartNanos
                    >= shots.get(index).settleMillis() * 1_000_000L;
        }

        /** Whether every pixel of the capture is one colour, a scene that has not drawn. */
        private static boolean uniform(Image image) {
            byte[] rgba = image.pixels();
            for (int i = 4; i < rgba.length; i += 4) {
                if (rgba[i] != rgba[0] || rgba[i + 1] != rgba[1]
                        || rgba[i + 2] != rgba[2] || rgba[i + 3] != rgba[3]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Hands the scene the frame the script is on: the pointer's position, the button if it
         * changed, and any wheel or text the step carries. The arrow layer is moved with it, so
         * what the capture shows and what the widgets were told are the same event.
         *
         * <p>Asking the film for the frame HERE, one frame before it is rendered, is what makes
         * a step's target resolvable: every earlier frame's events have been dispatched and
         * drawn by now, so a dialog opened four frames ago is up and a row that scrolled is
         * where the wheel left it.
         */
        private void applyFilmStep() {
            Motion.Frame step = film.next();
            scene.mouseMoved(step.x(), step.y());
            if (step.down() != buttonDown) {
                buttonDown = step.down();
                scene.mouseButton(limn.input.Keys.MOUSE_LEFT, buttonDown, 0, step.x(), step.y());
            }
            if (step.wheel() != 0) {
                // After the move, so the notch is delivered to whatever the pointer is over on
                // this frame rather than to what it was over on the last one.
                scene.scrolled(0, step.wheel(), step.x(), step.y());
            }
            if (step.typed() != 0) {
                scene.charTyped(step.typed());
            }
            if (built.pointer() != null) {
                built.pointer().setPointer(step.x(), step.y(), step.pointerVisible(), buttonDown);
            }
        }

        /** {@code button-dark@2x.png} → {@code button-dark-f007@2x.png}. */
        private static Path frameFile(Shot shot, int frame) {
            String name = shot.file().getFileName().toString();
            String stem = name.substring(0, name.length() - "@2x.png".length());
            return shot.file().resolveSibling(String.format("%s-f%03d@2x.png", stem, frame));
        }

        /** Builds and binds the next shot, or reports that the list is exhausted. */
        private boolean advance() {
            if (++index >= shots.size()) {
                return false;
            }
            Shot shot = shots.get(index);
            // The window this shot belongs to; the other one simply gets no frames.
            window = shot.window();
            // Before the scene is constructed, not after: scenes read the palette while
            // they build, so a theme set afterwards leaves whatever each widget resolved.
            Theme.setCurrent(shot.palette().theme());
            // The UI font is process-wide, and an entry is allowed to pin its own, so reset it
            // here, per shot, and let a scene that pinned one leave it pinned. It CANNOT be
            // restored by whoever set it: a family is resolved when text is measured, which is
            // frames after the builder returned, so a builder that put the previous family back
            // captured the previous font. That is not hypothetical: it is what published the
            // typeface tile in Roboto.
            limn.graphics.Fonts.setDefaultFamily(null);
            built = shot.entry().builder().get();
            scene = built.scene();
            scene.bind(window);
            // AFTER bind, never before: bind installs a frame callback of its own, and a
            // callback set only at start-up is silently replaced by the first bind.
            installCallback();
            frames = 0;
            film = null;
            still = null;
            stillPending = false;
            shotStartNanos = System.nanoTime();
            return true;
        }
    }

    /**
     * The manifest, written by hand rather than through a JSON library: this module has no
     * third-party dependency beyond the toolkit and the decoders, and one object shape is
     * not worth acquiring one.
     */
    private static String manifest(List<GalleryEntry> entries) {
        StringBuilder json = new StringBuilder("{\n  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            GalleryEntry entry = entries.get(i);
            json.append("    {\n")
                    .append("      \"id\": ").append(quote(entry.id())).append(",\n")
                    .append("      \"title\": ").append(quote(entry.title())).append(",\n")
                    .append("      \"region\": ").append(quote(entry.region())).append(",\n")
                    .append("      \"images\": {\n")
                    .append("        \"dark\": ").append(quote(entry.id() + "-dark@2x.png"))
                    .append(",\n")
                    .append("        \"light\": ").append(quote(entry.id() + "-light@2x.png"))
                    .append("\n      },\n")
                    .append("      \"scale\": 2");
            // Only a filmed entry carries this. The site joins the frames into one animation
            // per palette and keeps the still above as the poster, so an entry that grows a
            // film needs no change on the other side of the manifest.
            Integer frames = FILM_FRAMES.get(entry.id());
            if (frames != null) {
                json.append(",\n      \"frames\": ").append(frames)
                        .append(",\n      \"frameMs\": ").append(GalleryScenes.STEP_MS);
                String content = FILM_CONTENT.get(entry.id());
                if (content != null) {
                    json.append(",\n      \"content\": ").append(content);
                }
            }
            json.append("\n    }").append(i == entries.size() - 1 ? "\n" : ",\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
