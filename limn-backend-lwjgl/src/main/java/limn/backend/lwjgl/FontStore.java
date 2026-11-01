package limn.backend.lwjgl;

import limn.concurrent.Subscription;
import limn.graphics.Font;
import limn.graphics.Fonts;
import limn.graphics.TextMetrics;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Backend-wide font registry (CPU data, shared by all windows). The Roboto faces (Apache 2.0)
 * back {@link Font#DEFAULT_FAMILY} and any unknown family; they arrive from the required
 * {@code limn-fonts-roboto} artifact. Roboto Mono's two variable faces (SIL OFL 1.1) back
 * {@link Font#MONOSPACE_FAMILY}, from {@code limn-fonts-roboto-mono}, which the backend also
 * takes; the optional Noto fallbacks come from the {@code limn-fonts-*} artifacts an application
 * opts in to.
 * Loading is lazy throughout: only Roboto Regular parses at construction; the style variants
 * parse on first resolve, and the heavyweight Noto fallbacks arrive from a background parse
 * (see {@link #parseHeavyFallbacks}/{@link #installHeavyFallbacks}). A {@link Font}'s
 * {@code (family, bold, italic)} triple resolves to the matching face, degrading gracefully
 * (drop italic, then bold, then to the family's regular, then the global fallback).
 *
 * <p><b>Script fallback.</b> Any code point the primary face lacks (CJK, emoji,
 * Arabic, Hebrew, Devanagari, Thai, …) is resolved against the registered fallback
 * faces — once per run by the shaper, which needs the face before any glyph
 * exists, and per code point by the two walks that remain under it: the plain
 * measure, and the painter's fallback for a cluster that has no glyph. Mixed-script lines "just
 * work". Until the background fallbacks land (and when the Noto binaries are
 * absent) this degrades to Roboto-only; the fold-in re-notifies {@link Fonts}
 * listeners, so scenes relayout and boxes heal into glyphs.
 *
 * <p><b>System fonts.</b> Families enumerated from the OS (see {@link SystemFonts})
 * are registered as lightweight descriptors and their faces are loaded <em>lazily</em>,
 * only when a family is actually selected. To bound memory, at most
 * {@link #MAX_SYSTEM_FACES} system faces stay resident at once (see
 * {@link #evictSystemBeyondCap} for which go: never the default family's, and
 * faces nothing has asked for before any other); the bundled faces are pinned.
 * The family {@link Font#DEFAULT_FAMILY} resolves to is
 * {@link Fonts#defaultFamily()}, so switching it re-fonts the whole UI at runtime.
 *
 * <p><b>Selecting a system family costs a file read.</b> That read happens inside
 * {@link #resolve}, which runs on the UI thread inside a measure or a paint, and
 * an OS font file is unbounded in size. {@link #preloadFamily} moves it to a
 * worker: warm the family, then select it, and the frame that switches never
 * touches the disk. Applications reach it through {@link Fonts#preload} and
 * {@link Fonts#setDefaultFamilyWhenResident}, by way of the {@link #loader()} the
 * backend installs.
 */
final class FontStore implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FontStore.class.getName());

    /** Max resident dynamically-loaded system faces; bundled faces don't count. */
    private static final int MAX_SYSTEM_FACES = 6;

    /**
     * Where a bundled face lives on the classpath: {@code limn/fonts/}, the path the
     * limn-fonts artifacts publish their resources under. Only the menu symbols — authored by
     * this project, versioned with it — still live in this module's own resources, under the
     * old {@code limn/backend/lwjgl/fonts/} path.
     *
     * <p>The old path is kept as a fallback for every face all the same: it costs one
     * {@code getResource} probe, and it is the documented location an application could have
     * been placing a region-variant CJK file at since before the artifacts existed.
     */
    /**
     * Whether a bundled face is there. This module's own first, then its class loader, because the
     * faces come from the limn-fonts jars and, on the module path, a class's own lookup does not
     * leave its module.
     */
    static boolean exists(String absolutePath) {
        if (FontStore.class.getResource(absolutePath) != null) {
            return true;
        }
        ClassLoader loader = FontStore.class.getClassLoader();
        return loader != null && loader.getResource(absolutePath.substring(1)) != null;
    }

    /**
     * Opens a bundled face the way {@link #exists} finds one: this module's own resources, which on
     * the module path only this module's code can open, and then the limn-fonts jars through the
     * class loader.
     *
     * @param absolutePath the face's path, with the leading slash
     * @return the open stream, or {@code null} when neither has it
     */
    static java.io.InputStream openBundled(String absolutePath) {
        java.io.InputStream own = FontStore.class.getResourceAsStream(absolutePath);
        if (own != null) {
            return own;
        }
        ClassLoader loader = FontStore.class.getClassLoader();
        return loader == null || !absolutePath.startsWith("/")
                ? null : loader.getResourceAsStream(absolutePath.substring(1));
    }

    static String bundledPath(String file) {
        String modern = "/limn/fonts/" + file;
        return exists(modern)
                ? modern
                : "/limn/backend/lwjgl/fonts/" + file;
    }

    private final Map<String, StbFont> byFamily = new HashMap<>();
    private final Map<StbFont, Integer> faceIds = new IdentityHashMap<>();
    /** The step a variable face's instances are made at: a weight is drawn at the nearest. */
    static final int INSTANCE_STEP = 10;

    /** The weight from which a request is bold, as {@code Font.isBold()} reads one. */
    private static final int BOLD_FROM = 600;

    /** A fallback face's Bold, or the face itself when it has none; see {@link #boldTwin}. */
    private final Map<StbFont, StbFont> boldTwins = new IdentityHashMap<>();
    // The reverse, for a painter holding a ShapedText: a run names its face by id, and the id has
    // to become a face again to reach the atlas. Kept in step with faceIds at both ends, because
    // an id that outlives its face is exactly the case this map exists to answer "no" to.
    private final Map<Integer, StbFont> facesById = new HashMap<>();
    private int nextFaceId; // monotonic: never reused, so an evicted+reloaded face gets a fresh atlas key
    private final List<StbFont> pinned = new ArrayList<>();      // bundled faces (closed on shutdown)
    private final List<StbFont> fallbackFaces = new ArrayList<>(); // per-code-point fallback chain
    private final Map<Font, StbFont> resolved = new IdentityHashMap<>();
    private final StbFont fallback; // Roboto Regular, assigned in the constructor
    private ColorEmojiFont colorEmoji; // optional CBDT color emoji; arrives from the background load
    /** A bundled face that is a name and a classpath resource until something needs it. */
    private record LazyFace(String name, String resource) {
    }

    // Bundled style variants parse lazily on first resolve (an app that never
    // shows bold italic never pays for it): family key -> pending face.
    private final Map<String, LazyFace> lazyBundled = new HashMap<>();

    /**
     * Each bundled family's faces, described as {@link #pickFace} reads them, so a weight between
     * two of them is searched for as it is in a system family; and the key each face loads under.
     */
    private final Map<String, List<SystemFonts.Face>> bundledStyles = new HashMap<>();
    private final Map<SystemFonts.Face, String> bundledStyleKeys = new HashMap<>();

    /** One of Roboto's faces besides its Regular: its style name, file, weight and slant. */
    private record RobotoStyle(String name, String file, int weight, boolean italic, boolean always) {
    }

    /** Roboto 2.138's faces besides the Regular, which is parsed eagerly. */
    private static final List<RobotoStyle> ROBOTO_STYLES = List.of(
            new RobotoStyle("Thin", "Roboto-Thin.ttf", 100, false, false),
            new RobotoStyle("Thin Italic", "Roboto-ThinItalic.ttf", 100, true, false),
            new RobotoStyle("Light", "Roboto-Light.ttf", 300, false, false),
            new RobotoStyle("Light Italic", "Roboto-LightItalic.ttf", 300, true, false),
            new RobotoStyle("Italic", "Roboto-Italic.ttf", Font.REGULAR, true, true),
            new RobotoStyle("Medium", "Roboto-Medium.ttf", 500, false, false),
            new RobotoStyle("Medium Italic", "Roboto-MediumItalic.ttf", 500, true, false),
            new RobotoStyle("Bold", "Roboto-Bold.ttf", Font.BOLD, false, true),
            new RobotoStyle("Bold Italic", "Roboto-BoldItalic.ttf", Font.BOLD, true, true),
            new RobotoStyle("Black", "Roboto-Black.ttf", 900, false, false),
            new RobotoStyle("Black Italic", "Roboto-BlackItalic.ttf", 900, true, false));

    /** The key {@link Font#MONOSPACE_FAMILY} resolves to where Roboto Mono is bundled. */
    private static final String MONO_FAMILY = "roboto mono";

    /**
     * The range of weights Roboto Mono's {@code wght} axis covers, and its default instance's:
     * read from v3.001's {@code fvar} (100, 400, 700) and checked against the bundled files by
     * {@code MonospaceFamilyTest}.
     */
    static final int MONO_MIN_WEIGHT = 100;
    static final int MONO_DEFAULT_WEIGHT = Font.REGULAR;
    static final int MONO_MAX_WEIGHT = 700;

    /**
     * Registers one bundled variable face of {@code family}, parsed on first use, described to the
     * weight search with its range so a weight inside it draws that instance.
     */
    private void bundleVariable(String family, String key, String name, String style, String resource,
                                boolean italic) {
        lazyBundled.put(key, new LazyFace(name, resource));
        SystemFonts.Face face = new SystemFonts.Face(family, style, null, 0, false, italic,
                MONO_DEFAULT_WEIGHT, SystemFonts.NORMAL_WIDTH, MONO_MIN_WEIGHT, MONO_MAX_WEIGHT);
        bundledStyles.computeIfAbsent(family, k -> new ArrayList<>()).add(face);
        bundledStyleKeys.put(face, key);
    }

    /** Describes one bundled face of {@code family} to the weight search; see {@link #bundledStyles}. */
    private void bundleStyle(String family, String key, String style, int weight, boolean italic) {
        SystemFonts.Face face = new SystemFonts.Face(family, style, null, 0, weight >= 600, italic,
                weight, SystemFonts.NORMAL_WIDTH);
        bundledStyles.computeIfAbsent(family, k -> new ArrayList<>()).add(face);
        bundledStyleKeys.put(face, key);
    }
    private boolean closed; // a background fallback load may complete after close()

    /**
     * The faces that make the complex scripts drawable, in the order they join the fallback chain.
     *
     * <p>Each covers exactly one script <em>nothing else bundled here covers at all</em>: the cmaps
     * of Roboto and of Noto Sans CJK were both read directly, and both answer 0 of 256 Arabic, 0 of
     * 112 Hebrew, 0 of 128 Devanagari and 0 of 128 Thai. Without these the shaper runs, resolves
     * every complex-script run to the primary face, and shapes it into a row of {@code .notdef}
     * boxes — a pipeline that works and has nothing to work with.
     *
     * <p>They live in the same background batch as the CJK and colour-emoji faces, and the reason
     * is the trigger rather than the size. 531 KB for all four is nearer Roboto Regular's 349 KB
     * than the pan-CJK face's 16 MB, so "too big to parse at startup" is not the argument; what
     * makes them lazy is that nothing at construction time knows whether this application will ever
     * draw a character of any of them, and the moment that becomes known is the first code point no
     * resident face covers — which is precisely when {@link #requestHeavyFallbacks} already fires.
     * A second background path would duplicate the parts that are easy to get wrong (ownership on a
     * throw, the drop-and-free after {@link #close}, the epoch bump, the catalog re-notification)
     * for four files whose arrival condition is the same as the two already there.
     *
     * <p>The menu symbols went the other way — eager, at three kilobytes — and the difference is
     * worth stating because it is not size either. Shortcut hints appear in a UI that never asked
     * for them, in any locale, within the first frames, so a fallback arriving late is visible
     * there. These four are reached only by text already written in their scripts, and that text is
     * exactly what the epoch bump and the catalog notification re-shape.
     */
    private static final List<LazyFace> SCRIPT_FALLBACKS = List.of(
            new LazyFace("Noto Sans Arabic", bundledPath("NotoSansArabic-Regular.ttf")),
            new LazyFace("Noto Sans Hebrew", bundledPath("NotoSansHebrew-Regular.ttf")),
            new LazyFace("Noto Sans Devanagari", bundledPath("NotoSansDevanagari-Regular.ttf")),
            new LazyFace("Noto Sans Thai", bundledPath("NotoSansThai-Regular.ttf")));

    // On-demand kicks (one-shot, UI thread): the heavy Noto fallbacks load on
    // the FIRST glyph the primary face lacks; the OS font enumeration runs on
    // the FIRST listing request (or unknown-family resolve). An app that shows
    // only Latin text in bundled families never pays for either.
    private boolean heavyFallbacksRequested;
    /**
     * Completed once the heavy fallbacks have landed or failed; null until they are asked for.
     * What a printed page waits on before it records its text for good.
     */
    private java.util.concurrent.CompletableFuture<Void> heavyFallbacksDone;
    private boolean systemScanRequested;
    // Re-installs the backend's catalog after a background fold-in, so Fonts
    // listeners (scene relayout, font pickers) observe the change.
    private Runnable catalogChanged = () -> {
    };

    // System fonts: family (lowercase) -> discovered faces; loaded on first use into
    // loadedSystem (access-ordered = LRU for eviction). familyNames drives the catalog.
    private final Map<String, List<SystemFonts.Face>> systemFaces = new HashMap<>();
    private final LinkedHashMap<String, StbFont> loadedSystem = new LinkedHashMap<>(16, 0.75f, true);
    /** Reverse of {@link #loadedSystem}, so a memoized resolve can touch its LRU entry. */
    private final Map<StbFont, String> loadedSystemKeys = new IdentityHashMap<>();
    /** The family key each face of {@link #loadedSystem} belongs to, which eviction spares by. */
    private final Map<StbFont, String> loadedSystemFamilies = new IdentityHashMap<>();
    /**
     * Faces of {@link #loadedSystem} that a read nobody wanted any more brought in (see
     * {@link #installPreloaded}) and that no resolve has asked for since. Nothing draws with one,
     * no memoized resolution and no shaped text names one, so letting go of one costs a read on a
     * worker if the family is asked for again, never a frame, and nothing has to lay out again.
     */
    private final Set<StbFont> readAhead = Collections.newSetFromMap(new IdentityHashMap<>());
    private final TreeSet<String> familyNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    /** Handed back by {@link Fonts#observeChanges} in the constructor, cancelled in {@link #close}. */
    private Subscription fontsSubscription;
    /**
     * How many times {@link Fonts} has told its listeners of a change, this store among them: a
     * landing read compares it across the callbacks it runs to learn whether one of them (a font
     * switch) already had every scene lay out again.
     */
    private long fontsChanges;

    // ------------------------------------------------------------------ shaping epoch
    //
    // Drawn from ONE process-wide counter rather than numbered per store, and the reason is not
    // tidiness. A held ShapedText carries the number it was shaped under and is later asked
    // whether it is still current by whatever ruler it is handed; two stores counting
    // independently would eventually both answer the same number, and a value shaped by one would
    // report itself fresh under the other. Installing a backend replaces the ruler outright, so
    // uniqueness is the only thing standing between a held value and a ruler that never made it.
    //
    // It starts at 1, never 0: 0 means "depends on no ruler state, current under every ruler",
    // which is right for a fake and wrong for anything that resolves a face.
    private static final java.util.concurrent.atomic.AtomicLong EPOCHS =
            new java.util.concurrent.atomic.AtomicLong();
    private long epoch = EPOCHS.incrementAndGet();

    /** The current shaping epoch: moves whenever this store would shape the same string differently. */
    long epoch() {
        return epoch;
    }

    /**
     * Records that family-to-face resolution, or the set of resident faces, just changed.
     *
     * <p>The memo clear and the epoch bump are one call because they answer the same event for two
     * different audiences — this store's own cache, and every {@code ShapedText} already handed
     * out — and splitting them is how one of the two gets forgotten at the next call site. In
     * particular an eviction <em>closes</em> a face, and nothing else in the process fires when it
     * does: a held value's glyph ids then name a face that is gone.
     *
     * <p>Deliberately NOT called for a content-scale change, a monitor switch or a repaint.
     * Shaped positions are unquantized logical points in font units, so a display change
     * re-rasterizes glyph bitmaps (the atlas's job, keyed by quantized device size) and re-shapes
     * nothing. Bumping here would re-shape every string in the process whenever a window crossed
     * a monitor boundary.
     */
    private void resolutionChanged() {
        resolved.clear();
        epoch = EPOCHS.incrementAndGet();
    }

    /**
     * The menu key symbols, or {@code null} in a build without them. Held apart from the
     * fallback list because it is the one face that has to beat the colour-emoji path; see
     * {@link #drawsAsText}.
     */
    private final StbFont menuSymbols;

    FontStore() {
        // Only Roboto Regular parses eagerly: it is the last-resort fallback and
        // the first frame's measure needs SOME face. Everything else is lazy:
        // style variants on first resolve, and the optional broad-coverage
        // fallbacks (CJK + color emoji, tens of MB, plus the four complex-script
        // faces) on a background task the backend kicks off (see
        // parseHeavyFallbacks/installHeavyFallbacks).
        // Roboto arrives from the limn-fonts-roboto artifact, and unlike every other face it is
        // NOT optional: it is the last resort everything else degrades to, so its absence fails
        // here, loudly and immediately, rather than as a first frame with no text. The artifact
        // is a declared runtime dependency of this module and arrives transitively; the one way
        // it goes missing is a build that excluded it, and this message is for whoever did.
        String robotoRegular = bundledPath("Roboto-Regular.ttf");
        if (!exists(robotoRegular)) {
            throw new IllegalStateException(
                    "the default UI font is not on the classpath: limn-backend-lwjgl requires "
                            + "io.github.limn-toolkit:limn-fonts-roboto (a runtime dependency "
                            + "that normally arrives transitively). If the build excludes it, "
                            + "remove the exclusion; there is no Roboto-less mode.");
        }
        StbFont roboto = register("Roboto", robotoRegular, "roboto", Font.DEFAULT_FAMILY);
        bundleStyle("roboto", "roboto", "Regular", Font.REGULAR, false);
        for (RobotoStyle style : ROBOTO_STYLES) {
            String resource = bundledPath(style.file());
            // The four faces every limn-fonts-roboto carries are registered whatever the jar
            // holds, so a stripped one warns on first use as it always did; the eight lighter and
            // heavier ones only where this jar has them, since an older one does not, and a
            // weight they would have drawn then draws the nearest face that is here.
            if (!style.always() && !exists(resource)) {
                continue;
            }
            String key = "roboto " + style.name().toLowerCase(Locale.ROOT);
            lazyBundled.put(key, new LazyFace("Roboto " + style.name(), resource));
            bundleStyle("roboto", key, style.name(), style.weight(), style.italic());
        }
        familyNames.add("Roboto");
        // Roboto Mono backs Font.MONOSPACE_FAMILY: two variable faces, upright and italic, each
        // drawn at any weight from 100 to 700. Registered only where the jar is on the class
        // path; without it the alias resolves to the default family, never to nothing.
        String monoUpright = bundledPath("RobotoMono-Variable.ttf");
        if (exists(monoUpright)) {
            bundleVariable(MONO_FAMILY, MONO_FAMILY, "Roboto Mono", "Regular", monoUpright, false);
            String monoItalic = bundledPath("RobotoMono-VariableItalic.ttf");
            if (exists(monoItalic)) {
                bundleVariable(MONO_FAMILY, MONO_FAMILY + " italic", "Roboto Mono Italic", "Italic",
                        monoItalic, true);
            }
            familyNames.add("Roboto Mono");
        }
        // Roboto is also the last-resort fallback: when the primary is switched to a
        // system font that lacks a glyph Roboto has (e.g. accented Greek), fall back
        // to Roboto rather than a .notdef box. Skipped when Roboto IS the primary.
        fallbackFaces.add(roboto);

        // The menu key symbols (⌘ ⌥ ⌃ ⇧ and the rest), ahead of Roboto because Roboto has
        // none of them. Eager, and it is the one fallback that is: it is three kilobytes,
        // and the heavy faces below arrive on a background parse; a menu opened in the
        // first moments of a run would otherwise draw a row of boxes where its shortcut
        // hints belong, which is the one place a fallback arriving late is visible.
        //
        // Absent from a stripped build rather than fatal, like every other fallback here:
        // the hints then render as .notdef and nothing else changes.
        StbFont menuSymbols = StbFont.loadResourceIfPresent(
                "/limn/backend/lwjgl/fonts/LimnMenuSymbols.ttf", "Limn Menu Symbols");
        if (menuSymbols != null) {
            assignId(menuSymbols);
            pinned.add(menuSymbols);
            fallbackFaces.add(0, menuSymbols);
        }
        this.menuSymbols = menuSymbols;

        this.fallback = roboto;
        this.fontsSubscription = Fonts.observeChanges(this::fontsChanged);
    }

    /** A catalog or default-family change: the families may resolve to other faces now. */
    private void fontsChanged() {
        fontsChanges++;
        resolutionChanged();
    }

    // -------------------------------------------------- background fallbacks

    /** Sets how a background fold-in re-installs the catalog (backend startup). */
    void setCatalogChangedNotifier(Runnable notifier) {
        this.catalogChanged = notifier;
    }

    /**
     * First real need for the broad-coverage fallbacks (a glyph the primary
     * lacks): parse them once, in the background. Until they land the caller
     * renders {@code .notdef}; the fold-in notifies {@code Fonts} listeners and
     * the boxes heal.
     *
     * <p><b>Once per store, a failure included.</b> What this reads are bundled
     * resources, so a throw is a broken binary rather than a passing condition,
     * and retrying on the next glyph miss would re-read tens of megabytes to
     * fail again, on every miss. Roboto stays, the boxes stay, and the reason
     * is logged, which is the part that was missing.
     */
    private void requestHeavyFallbacks() {
        if (heavyFallbacksRequested || closed || !limn.concurrent.Ui.isInstalled()) {
            return;
        }
        heavyFallbacksRequested = true;
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        heavyFallbacksDone = done;
        limn.concurrent.Ui.async(FontStore::parseHeavyFallbacks).whenComplete((loaded, failed) -> {
            try {
                landHeavyFallbacks(loaded, failed);
            } finally {
                done.complete(null);
            }
        });
    }

    /**
     * @return a stage completed on the UI thread once no face this store was asked for is still
     *         loading: at once when none is
     */
    java.util.concurrent.CompletionStage<Void> whenSettled() {
        java.util.concurrent.CompletableFuture<Void> done = heavyFallbacksDone;
        return done == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : done;
    }

    private void landHeavyFallbacks(HeavyFallbacks loaded, Throwable failed) {
        if (failed != null) {
            // Without this the exception ends inside a future nobody observes, and the only
            // symptom is CJK text rendering as boxes for the rest of the process.
            LOG.log(Level.WARNING,
                    "fallback fonts failed to load; CJK and emoji stay .notdef", failed);
            return;
        }
        if (installHeavyFallbacks(loaded)) {
            catalogChanged.run();
            LOG.log(Level.INFO, "fallback fonts installed (background load, on first need)");
        }
    }

    /**
     * First request for the font listing (or an unknown-family resolve):
     * enumerate the OS faces once, in the background. Unknown families resolve
     * to the global fallback until the scan lands; the fold-in notification
     * re-resolves them to the real face.
     */
    private void requestSystemScan() {
        if (systemScanRequested || closed || !limn.concurrent.Ui.isInstalled()) {
            return;
        }
        systemScanRequested = true;
        limn.concurrent.Ui.async(SystemFonts::scan).thenAccept(faces -> {
            if (closed) {
                return;
            }
            setSystemFaces(faces);
            catalogChanged.run();
            LOG.log(Level.INFO, "system fonts enumerated: {0} faces (background scan, on first request)",
                    faces.size());
        });
    }

    /** @return whether the heavy-fallback load was kicked (tests). */
    boolean heavyFallbacksRequested() {
        return heavyFallbacksRequested;
    }

    /** @return whether the OS enumeration was kicked (tests). */
    boolean systemScanRequested() {
        return systemScanRequested;
    }

    /**
     * The heavyweight optional fallbacks, parsed off the UI thread. Pure CPU
     * (classpath read + stb table parse), so it is safe on a worker; the
     * result is folded in on the UI thread by {@link #installHeavyFallbacks}.
     */
    record HeavyFallbacks(StbFont cjk, List<StbFont> scripts, ColorEmojiFont emoji) {
        HeavyFallbacks {
            // Copied and never null, because this value crosses a thread and is then closed by
            // whichever side ends up owning it; a caller that could still mutate the list is a
            // caller that could hand a face to close() twice or not at all.
            scripts = scripts == null ? List.of() : List.copyOf(scripts);
        }

        void close() {
            if (cjk != null) {
                cjk.close();
            }
            for (StbFont script : scripts) {
                script.close();
            }
            if (emoji != null) {
                emoji.close();
            }
        }
    }

    /** Runs on a worker thread. Absent binaries are skipped (Roboto-only, graceful). */
    static HeavyFallbacks parseHeavyFallbacks() {
        return parseHeavyFallbacks(
                () -> firstPresent("Noto Sans CJK",
                        bundledPath("NotoSansCJK-Regular.otf"),
                        bundledPath("NotoSansCJK-Regular.ttf"),
                        bundledPath("NotoSansJP-Regular.ttf"),
                        bundledPath("NotoSansSC-Regular.otf")),
                FontStore::parseScriptFallbacks,
                // Emoji come from the color font (CBDT bitmaps), drawn as images with their
                // own cmap/advance; stb can't open it (bitmap-only), so it isn't an StbFont
                // face in the chain. Absent → no emoji (a .notdef box).
                () -> ColorEmojiFont.loadResourceIfPresent(
                        bundledPath("NotoColorEmoji.ttf")));
    }

    /**
     * The four complex-script faces, on a worker thread. Each is optional exactly as the CJK face
     * is: a checkout without {@code scripts/fetch-fonts.sh} having run is missing all four, and
     * what that costs is boxes where Arabic, Hebrew, Devanagari or Thai would be, not a build or a
     * startup that fails.
     *
     * <p>The batch closes what it has already parsed if a later one throws, for the same reason
     * {@link #parseHeavyFallbacks} does: a present-but-unreadable resource throws with two or three
     * native buffers already allocated and nothing else holding them.
     */
    private static List<StbFont> parseScriptFallbacks() {
        List<StbFont> faces = new ArrayList<>(SCRIPT_FALLBACKS.size());
        try {
            for (LazyFace pending : SCRIPT_FALLBACKS) {
                StbFont face = firstPresent(pending.name(), pending.resource());
                if (face != null) {
                    faces.add(face);
                }
            }
        } catch (RuntimeException failed) {
            for (StbFont face : faces) {
                face.close();
            }
            throw failed;
        }
        return faces;
    }

    /**
     * The order matters and so does the failure. An absent resource is null and graceful, but one
     * that is present and unreadable throws, and by then the faces before it are native buffers stb
     * has already allocated that nothing else holds; the fold-in that would have taken ownership is
     * exactly what is not going to happen. So a throw from any loader closes what the ones before
     * it produced.
     *
     * <p>Package-private with its loaders passed in for the test: no bundled resource can be made
     * to fail on demand, and the leak is only reachable on that path.
     */
    static HeavyFallbacks parseHeavyFallbacks(java.util.function.Supplier<StbFont> cjkLoader,
                                              java.util.function.Supplier<List<StbFont>> scriptLoader,
                                              java.util.function.Supplier<ColorEmojiFont> emojiLoader) {
        StbFont cjk = cjkLoader.get();
        List<StbFont> scripts = List.of();
        try {
            scripts = scriptLoader.get();
            return new HeavyFallbacks(cjk, scripts, emojiLoader.get());
        } catch (RuntimeException failed) {
            // Through the record's own close, so that adding a face to the batch cannot leave one
            // freed on the happy path and leaked on this one.
            new HeavyFallbacks(cjk, scripts, null).close();
            throw failed;
        }
    }

    private static StbFont firstPresent(String name, String... resources) {
        for (String resource : resources) {
            StbFont face = StbFont.loadResourceIfPresent(resource, name);
            if (face != null) {
                LOG.log(Level.INFO, "fallback font loaded: {0} ({1})", name, resource);
                return face;
            }
        }
        LOG.log(Level.INFO, "optional fallback font not on the classpath ({0}); the "
                + "io.github.limn-toolkit:limn-fonts-* artifacts carry the faces, one "
                + "dependency per face (or limn-fonts-all for every fallback at once)", name);
        return null;
    }

    /**
     * Folds the background-parsed fallbacks in (UI thread): CJK and the four complex-script faces
     * join the fallback chain ahead of the Roboto last resort and become selectable families;
     * color emoji switches on. The caller re-installs the font catalog so {@code Fonts} listeners
     * (scene relayout, font pickers) observe the upgrade; text that showed {@code .notdef} boxes
     * heals.
     *
     * @return whether anything new was installed (skipped entirely after close)
     */
    boolean installHeavyFallbacks(HeavyFallbacks loaded) {
        if (closed) {
            loaded.close(); // arrived after shutdown: free the native buffers
            return false;
        }
        heavyFallbacksRequested = true; // a manual fold-in must not re-kick later
        boolean changed = false;
        if (loaded.cjk() != null) {
            assignId(loaded.cjk());
            pinned.add(loaded.cjk());
            // Ahead of Roboto: broad coverage first, last resort stays last.
            fallbackFaces.add(Math.max(0, fallbackFaces.size() - 1), loaded.cjk());
            byFamily.put("noto sans cjk", loaded.cjk());
            familyNames.add("Noto Sans CJK");
            changed = true;
        }
        for (StbFont script : loaded.scripts()) {
            assignId(script);
            pinned.add(script);
            // Behind the CJK face and still ahead of Roboto. Position in this list is who WINS a
            // code point two faces both cover, and these four carry Latin digits and punctuation as
            // well as their own script: put ahead of the CJK face they would start drawing the
            // Latin of any line whose primary face lacks it, which is a visible change to text that
            // has nothing to do with these scripts. Behind it, they are consulted only for what
            // nothing before them has — which is exactly the four scripts they were added for.
            fallbackFaces.add(Math.max(0, fallbackFaces.size() - 1), script);
            // Selectable too, like the CJK face: an application whose UI is Arabic wants this as
            // its primary rather than as the thing that rescues Roboto, and a resident face nobody
            // can name is a face nobody can choose.
            byFamily.put(script.name().toLowerCase(Locale.ROOT), script);
            familyNames.add(script.name());
            // Its Bold, the same lazy variant registration Roboto's styles get in the
            // constructor, made here instead so it exists only when the Regular does: a stripped
            // build without the script binaries must not carry lazy entries whose first resolve
            // warn-fails. Bold only, because upstream ships no Italic for any of these scripts;
            // the resolver already drops italic before weight, so an italic request lands here or
            // on the Regular. The fallback CHAIN stays Regular, and faceForCodepoint swaps in
            // this Bold where the primary is bold, so bold Arabic behind Roboto Bold is bold.
            for (LazyFace pending : SCRIPT_FALLBACKS) {
                if (pending.name().equals(script.name())) {
                    String scriptKey = script.name().toLowerCase(Locale.ROOT);
                    lazyBundled.put(scriptKey + " bold",
                            new LazyFace(script.name() + " Bold",
                                    pending.resource().replace("-Regular.ttf", "-Bold.ttf")));
                    bundleStyle(scriptKey, scriptKey, "Regular", Font.REGULAR, false);
                    bundleStyle(scriptKey, scriptKey + " bold", "Bold", Font.BOLD, false);
                }
            }
            changed = true;
        }
        if (loaded.emoji() != null) {
            colorEmoji = loaded.emoji();
            LOG.log(Level.INFO, "color emoji enabled (Noto Color Emoji)");
            changed = true;
        } else if (loaded.cjk() != null || colorEmoji == null) {
            LOG.log(Level.INFO, "color emoji font not on the classpath; "
                    + "io.github.limn-toolkit:limn-fonts-noto-emoji carries it");
        }
        if (changed) {
            resolutionChanged(); // cached resolutions may now upgrade to a face that just arrived
        }
        return changed;
    }

    private StbFont register(String name, String resource, String... keys) {
        StbFont face = StbFont.loadResource(resource, name);
        assignId(face);
        pinned.add(face);
        for (String key : keys) {
            byFamily.put(key, face);
        }
        return face;
    }

    /**
     * The face an id names, or {@code null} if this store no longer knows it.
     *
     * <p>{@code null} is the answer for an id issued before an eviction, and it is the answer a
     * caller needs rather than a wrong face: ids are never reused, so the alternative is not a
     * stale glyph but a row number read against a table it does not belong to, which draws
     * different CHARACTERS. A painter that gets {@code null} draws the text it was given by the
     * slower route.
     */
    StbFont faceById(int id) {
        return facesById.get(id);
    }

    private int assignId(StbFont face) {
        int id = registerId(face);
        // A face arriving is a residency change, and a held ShapedText cannot see it: the string
        // that fell back to Roboto a moment ago resolves to this face now, and would keep drawing
        // yesterday's glyphs through the very relayout that exists to fix it. The three calls from
        // the constructor bump a counter nobody has read yet, which costs nothing.
        epoch = EPOCHS.incrementAndGet();
        return id;
    }

    /**
     * Gives {@code face} its id without moving the epoch, for the one arrival that changes no
     * resolution: a preloaded system face that nothing stood in for while it was read (see
     * {@link #installPreloaded}). Moving the epoch there would have every held ShapedText shaped
     * again on its next paint, once per read that lands.
     */
    private int registerId(StbFont face) {
        int id = nextFaceId++;
        faceIds.put(face, id);
        facesById.put(id, face);
        return id;
    }

    // ------------------------------------------------------------- system fonts

    /** Installs the OS-enumerated faces (from a background scan). UI thread. */
    void setSystemFaces(List<SystemFonts.Face> discovered) {
        systemFaces.clear();
        for (SystemFonts.Face face : discovered) {
            systemFaces.computeIfAbsent(face.family().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(face);
            familyNames.add(face.family());
        }
        // Faces the application loaded from a file are in no enumeration; merge them back or
        // the scan landing un-registers them. See loadedFaces.
        for (Map.Entry<String, List<SystemFonts.Face>> entry : loadedFaces.entrySet()) {
            for (SystemFonts.Face face : entry.getValue()) {
                addFaceOnce(systemFaces, entry.getKey(), face);
                familyNames.add(face.family());
            }
        }
        // Installing an enumeration IS the scan landing, whoever produced it;
        // without this a later unknown-family resolve would walk the OS font
        // directories again to learn what it already knows.
        systemScanRequested = true;
        systemFacesInstalled = true;
        resolutionChanged();
        // Preloads asked for before the enumeration landed now know their files.
        if (!awaitingScan.isEmpty()) {
            Map<String, List<Runnable>> pending = new HashMap<>(awaitingScan);
            awaitingScan.clear();
            for (Map.Entry<String, List<Runnable>> entry : pending.entrySet()) {
                startFamilyPreload(entry.getKey(), entry.getValue());
            }
        }
    }

    // ---------------------------------------------------- application-shipped fonts

    /**
     * Faces registered from an explicit file, kept apart from the enumerated ones.
     *
     * <p>{@link #setSystemFaces} rebuilds {@link #systemFaces} from the scan it is handed, and a
     * face that entered through {@link #loadFile} is in neither that scan nor the bundled set.
     * Without this second copy to merge back, such a face disappears the moment the background
     * enumeration lands, which happens mid-run, and reads as the font spontaneously reverting.
     */
    private final Map<String, List<SystemFonts.Face>> loadedFaces = new LinkedHashMap<>();

    /**
     * Registers every face in one font file and returns the family the first one declares.
     * Synchronous, on the UI thread; see {@link limn.graphics.FontLoader#load}.
     *
     * <p>Only the name table is read here. The faces join the same map the operating system's
     * do, so the outlines are parsed on first resolve and evicted under the same LRU cap: an
     * application-shipped font costs what a system font costs, not what a bundled one costs.
     *
     * <p>The family is taken from the file rather than from its name, because the two disagree
     * often enough to matter, and a caller that guessed wrong would silently render in the
     * default face, which is the failure {@code FontLoader} exists to prevent.
     */
    String loadFile(Path file) {
        if (!Files.isReadable(file)) {
            throw new java.io.UncheckedIOException(
                    new java.io.IOException("cannot read the font file " + file.toAbsolutePath()));
        }
        List<SystemFonts.Face> faces = SystemFonts.facesIn(file);
        if (faces.isEmpty()) {
            throw new limn.io.MalformedContentException("font", "no usable font face in " + file.toAbsolutePath());
        }
        for (SystemFonts.Face face : faces) {
            String key = face.family().toLowerCase(Locale.ROOT);
            // Idempotent: loading the same file twice must not leave the family holding two
            // copies of every face, which would make pickFace's choice depend on insertion order.
            addFaceOnce(loadedFaces, key, face);
            addFaceOnce(systemFaces, key, face);
            familyNames.add(face.family());
        }
        resolutionChanged();
        catalogChanged.run();
        return faces.get(0).family();
    }

    private static void addFaceOnce(Map<String, List<SystemFonts.Face>> into, String key,
            SystemFonts.Face face) {
        List<SystemFonts.Face> faces = into.computeIfAbsent(key, k -> new ArrayList<>());
        if (!faces.contains(face)) {
            faces.add(face);
        }
    }

    // -------------------------------------------------------- family preload

    /** Whether {@link #setSystemFaces} has run, i.e. the OS enumeration landed. */
    private boolean systemFacesInstalled;
    /** Family key -> callbacks waiting on an in-flight background face load. */
    private final Map<String, List<Runnable>> preloading = new HashMap<>();
    /** Family key -> callbacks waiting for the OS enumeration before they can load. */
    private final Map<String, List<Runnable>> awaitingScan = new HashMap<>();
    /**
     * Families a resolve stood in for with the global fallback while their read was out (see
     * {@link #resolveSystem}): text is laid out in the wrong face, so the read landing has to say
     * so. Each is dropped as its read lands or fails.
     */
    private final Set<String> stoodIn = new HashSet<>();

    /** One face parsed off the UI thread, with the cache key it installs under. */
    private record LoadedFace(String key, StbFont face) {
    }

    /** A preload's product: parsed faces, still to be installed on the UI thread. */
    private record PreloadedFamily(List<LoadedFace> faces) {
        void close() {
            for (LoadedFace loaded : faces) {
                loaded.face().close();
            }
        }
    }

    /**
     * Reads {@code family}'s regular and bold faces on the worker pool and
     * installs them here on the UI thread, so that a later {@link #resolve} of
     * that family finds them resident instead of reading a font file inside a
     * measure or a paint. Call it on the UI thread, before the family is
     * selected; that is the whole point, since a selection takes visual effect
     * on the next frame and the read does not fit in one.
     *
     * <p>{@code whenResident} runs on the UI thread, on a later frame, once the
     * faces are resident; or, having loaded nothing, when the family is a
     * bundled one, is already resident, is unknown to the operating system, or
     * is a blank name. It runs once and never synchronously, so a caller may
     * flip its own state inside it without re-entering this. It does not run at
     * all if the store is closed first; the faces the load produced are then
     * freed on a worker thread.
     *
     * <p>The load is idempotent, and once started it is not called back: two
     * requests for one family share a single file read. What a caller can take
     * back is its request, through {@link #withdrawPreload}, as
     * {@link Fonts#setDefaultFamilyWhenResident} does for a switch a later
     * choice overtook; a caller that does not must check, inside
     * {@code whenResident}, that the family it asked for is still the one it
     * wants. A read that lands with no request left on it, for a family nothing
     * names, disturbs nothing: see {@link #installPreloaded}.
     *
     * <p>Italic faces are deliberately not preloaded: at most
     * {@link #MAX_SYSTEM_FACES} system faces stay resident, and regular plus
     * bold is what a UI needs on the frame after a switch.
     *
     * @param family       the family name, matched case-insensitively;
     *                     {@code null} or blank reads nothing and is answered
     *                     on a later frame, as every other answer is
     * @param whenResident run on the UI thread when the faces are resident, or
     *                     {@code null} for no callback
     */
    void preloadFamily(String family, Runnable whenResident) {
        limn.concurrent.Ui.checkUiThread();
        Runnable done = whenResident == null ? () -> { } : whenResident;
        String key = family == null ? "" : family.toLowerCase(Locale.ROOT).trim();
        if (closed || !limn.concurrent.Ui.isInstalled()) {
            return;
        }
        if (key.isEmpty() || byFamily.containsKey(key)) {
            // A blank name names nothing to read, and FontLoader.preload answers "nothing to read"
            // like any other answer: once, on a later turn.
            //
            // Bundled: resolve takes the bundled face before any system face of the same name, so
            // reading the system's copy (Roboto is installed on many Linux desktops) would fill
            // the resident set with faces nothing draws, and waiting for the enumeration to learn
            // that would kick it for nothing.
            limn.concurrent.Ui.post(done);
            return;
        }
        List<Runnable> waiting = preloading.get(key);
        if (waiting != null) {
            waiting.add(done); // one file read serves both callers
            return;
        }
        if (!systemFacesInstalled) {
            // No file is known for any family yet. Kick the enumeration (or join
            // the one already running) and pick this up in setSystemFaces.
            awaitingScan.computeIfAbsent(key, k -> new ArrayList<>()).add(done);
            requestSystemScan();
            return;
        }
        startFamilyPreload(key, new ArrayList<>(List.of(done)));
    }

    /**
     * Takes back a request {@link #preloadFamily} was given: {@code whenResident} will not run.
     * A read already under way goes on, since it may serve another request and cannot be called
     * back, but it lands as one nobody wants unless something else does (see
     * {@link #installPreloaded}); a read still waiting for the enumeration with no request left
     * is never started. Does nothing for a request already answered or never made. UI thread.
     *
     * @param family       the family as it was asked for, matched case-insensitively
     * @param whenResident the callback passed with it, the same instance
     */
    void withdrawPreload(String family, Runnable whenResident) {
        String key = family == null ? "" : family.toLowerCase(Locale.ROOT).trim();
        if (closed || key.isEmpty()) {
            return;
        }
        List<Runnable> reading = preloading.get(key);
        if (reading != null) {
            reading.remove(whenResident); // the key stays: the read is still out
        }
        List<Runnable> waiting = awaitingScan.get(key);
        if (waiting != null && waiting.remove(whenResident) && waiting.isEmpty()) {
            awaitingScan.remove(key);
        }
    }

    /** @return whether a read of {@code family} is out or waiting for the enumeration (tests) */
    boolean readInFlight(String family) {
        String key = family.toLowerCase(Locale.ROOT).trim();
        return preloading.containsKey(key) || awaitingScan.containsKey(key);
    }

    /**
     * The loader the backend installs through {@link Fonts#installLoader}: {@link #loadFile} for
     * a file the application ships, and {@link #preloadFamily} and {@link #withdrawPreload} for
     * {@link limn.graphics.FontLoader#preload} and {@link limn.graphics.FontLoader#withdraw},
     * which is how {@link Fonts#preload} and {@link Fonts#setDefaultFamilyWhenResident} reach
     * this store. The same instance each call, because the backend uninstalls by identity.
     */
    limn.graphics.FontLoader loader() {
        return loader;
    }

    private final limn.graphics.FontLoader loader = new limn.graphics.FontLoader() {
        @Override
        public String load(Path file) {
            return loadFile(file);
        }

        @Override
        public void preload(String family, Runnable whenResident) {
            preloadFamily(family, whenResident);
        }

        @Override
        public void withdraw(String family, Runnable whenResident) {
            withdrawPreload(family, whenResident);
        }
    };

    /** Starts (or skips) the background read for {@code key}. UI thread. */
    private void startFamilyPreload(String key, List<Runnable> waiting) {
        List<SystemFonts.Face> candidates = systemFaces.get(key);
        List<SystemFonts.Face> picks = candidates == null ? List.of() : preloadPicks(candidates);
        List<SystemFonts.Face> missing = new ArrayList<>(picks.size());
        for (SystemFonts.Face pick : picks) {
            StbFont resident = loadedSystem.get(systemKey(key, pick)); // a touch
            if (resident == null) {
                missing.add(pick);
            } else {
                // Asked for again, a picker stepped back to a family it passed: wanted now, so a
                // read nobody wants must not close it before the switch it is for is made.
                readAhead.remove(resident);
            }
        }
        if (missing.isEmpty()) {
            // Bundled, unknown to the OS, or already resident: nothing to read,
            // but the caller is still told on a frame rather than inline.
            for (Runnable waiter : waiting) {
                limn.concurrent.Ui.post(waiter);
            }
            return;
        }
        try {
            limn.concurrent.Ui.work(progress -> parseFaces(key, missing, progress))
                    // A store closed mid-read has nowhere to put the faces; refusing
                    // delivery routes them to onDiscarded, which frees them.
                    .deliverIf(() -> !closed)
                    .onDiscarded(PreloadedFamily::close)
                    .onSuccess(loaded -> installPreloaded(key, loaded))
                    .onFailure(error -> failPreload(key, error))
                    .start();
        } catch (RuntimeException error) {
            // The pool refused the work (shutting down). Record nothing: a family
            // left marked as loading would stand in with the fallback forever.
            LOG.log(Level.WARNING, "font preload for " + key + " could not start", error);
            for (Runnable waiter : waiting) {
                limn.concurrent.Ui.post(waiter);
            }
            return;
        }
        // Only once the work is really running: this is what makes a resolve of
        // the family stand in rather than read the file itself.
        preloading.put(key, waiting);
    }

    /** The faces a preload warms: the family's regular and its bold, de-duplicated. */
    private static List<SystemFonts.Face> preloadPicks(List<SystemFonts.Face> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        SystemFonts.Face regular = pickFace(candidates, false, false);
        SystemFonts.Face bold = pickFace(candidates, true, false);
        return regular == bold ? List.of(regular) : List.of(regular, bold);
    }

    /**
     * Runs on a worker thread: reads each font file and parses it. A face that
     * fails is skipped rather than failing the batch, so a family with one
     * broken style still gains the other.
     */
    private static PreloadedFamily parseFaces(String key, List<SystemFonts.Face> picks,
            limn.concurrent.Progress progress) {
        List<LoadedFace> loaded = new ArrayList<>(picks.size());
        for (SystemFonts.Face pick : picks) {
            if (progress.isCancelled()) {
                break;
            }
            try {
                loaded.add(new LoadedFace(systemKey(key, pick),
                        StbFont.loadFile(pick.path(), pick.index(), pick.family() + " " + pick.style())));
            } catch (RuntimeException error) {
                LOG.log(Level.WARNING, "failed to preload system font " + pick.path(), error);
            }
        }
        return new PreloadedFamily(loaded);
    }

    /**
     * Folds a background-parsed family in and wakes its waiters. UI thread.
     *
     * <p>What the faces may cost the rest of the store depends on whether anything still wants
     * them. They are wanted while a request is still on the read (a switch no later choice
     * overtook, a {@link Fonts#preload}), when the family is the default family, and when a
     * resolve stood in for the family while it was read. Then they go in as a face a resolve read
     * goes in: {@link #evictSystemBeyondCap} makes room, never out of these faces nor the default
     * family's; and when something stood in, the memo is dropped before a waiter can resolve
     * again, and every scene is told to lay out again unless a waiter's own switch already told
     * it.
     *
     * <p>A read nobody wants any more, the overtaken entries of a font picker stepped through a
     * list, disturbs nothing. Its faces are kept only in room that closing other faces nobody has
     * asked for makes, and are closed themselves when there is none: no face a resolve asked for
     * is closed, the epoch does not move, and nothing lays out again. Stepping a picker through
     * ten families lays the UI out once, for the switch the last one makes, and leaves the family
     * the UI was drawn in resident.
     */
    private void installPreloaded(String key, PreloadedFamily loaded) {
        List<Runnable> waiting = preloading.remove(key);
        boolean stood = stoodIn.remove(key);
        boolean wanted = stood || (waiting != null && !waiting.isEmpty())
                || key.equals(defaultFamilyKey());
        Set<StbFont> arrived = Collections.newSetFromMap(new IdentityHashMap<>());
        // In reverse, so the regular is the younger: a read nobody wants, with room for one face,
        // keeps the face its family is drawn in first.
        for (int i = loaded.faces().size() - 1; i >= 0; i--) {
            LoadedFace entry = loaded.faces().get(i);
            if (loadedSystem.containsKey(entry.key())) {
                entry.face().close(); // a synchronous resolve won the race
                continue;
            }
            // No epoch: nothing resolved this family to another face while it was read, unless
            // something stood in, and the resolutionChanged below moves the epoch for that.
            registerId(entry.face());
            putSystemFace(entry.key(), key, entry.face());
            arrived.add(entry.face());
            if (!wanted) {
                readAhead.add(entry.face());
            }
        }
        if (!wanted) {
            evictSystemBeyondCap(true, Set.of());
            return;
        }
        evictSystemBeyondCap(false, arrived);
        if (stood) {
            resolutionChanged(); // the stand-in is memoized: drop it before a waiter resolves
        }
        long changesBefore = fontsChanges;
        runWaiters(waiting);
        if (stood && fontsChanges == changesBefore) {
            // The same fold-in notification the fallback fonts use: scenes relayout, so text
            // drawn in the stand-in face picks up this one.
            catalogChanged.run();
        }
    }

    /** A read that failed: logged, and its waiters told, the family resolving as it would have. */
    private void failPreload(String key, Throwable failure) {
        LOG.log(Level.WARNING, "font preload failed for " + key, failure);
        stoodIn.remove(key);
        runWaiters(preloading.remove(key));
    }

    private static void runWaiters(List<Runnable> waiting) {
        if (waiting == null) {
            return;
        }
        for (Runnable waiter : waiting) {
            waiter.run();
        }
    }

    /** The key of the family {@link Font#DEFAULT_FAMILY} resolves to, as a resolve reads it. */
    private static String defaultFamilyKey() {
        return Fonts.defaultFamily().toLowerCase(Locale.ROOT);
    }

    /** Makes {@code face} resident under {@code key}, as one of {@code family}'s faces. */
    private void putSystemFace(String key, String family, StbFont face) {
        loadedSystem.put(key, face);
        loadedSystemKeys.put(face, key);
        loadedSystemFamilies.put(face, family);
    }

    /** @return every selectable family (bundled + system), sorted case-insensitively. */
    List<String> families() {
        requestSystemScan(); // someone wants the listing: enumerate the OS once
        return List.copyOf(familyNames);
    }

    // ------------------------------------------------------------- resolution

    /**
     * The face that draws {@code font}, loading it if this is the first use.
     * UI thread only, and reached from every measure and every {@code drawText}.
     *
     * <p><b>The first resolve of a system family reads that family's font file
     * on this thread</b>: an OS font, so tens of megabytes for a macOS
     * collection, inside whatever measure or paint asked. Bundled faces and any
     * face already resident cost a map lookup. {@link #preloadFamily} is how a
     * caller that knows which family is coming keeps that read off a frame.
     */
    StbFont resolve(Font font) {
        // Confined to the UI thread, like every stateful toolkit entry point:
        // the memo/LRU maps and the per-face stb scratch are unsynchronized,
        // and this is reachable from any thread via the TextRulers facade.
        limn.concurrent.Ui.checkUiThread();
        StbFont memo = resolved.get(font);
        if (memo != null) {
            touchSystemLru(memo);
            return memo;
        }
        StbFont face = resolveUncached(font);
        if (resolved.size() >= 4096) {
            // Emptied at the bound and filled again: a program that makes fonts as it goes filled
            // the memo with fonts nobody had any more, each kept by it, and from then on every new
            // one was resolved the long way at each measure and each draw.
            resolved.clear();
        }
        resolved.put(font, face);
        return face;
    }

    /**
     * The resolve memo short-circuits {@link #resolveSystem}, whose {@code get}
     * is the access-order touch; without this, the LRU reflects first-resolution
     * order and eviction can close the face currently rendering the whole UI.
     */
    private void touchSystemLru(StbFont face) {
        // An instance of a variable face is held in the memo, its owner in the LRU.
        String key = loadedSystemKeys.get(face.isInstance() ? face.owner() : face);
        if (key != null) {
            loadedSystem.get(key); // access-order relink
        }
    }

    private StbFont resolveUncached(Font font) {
        String family = font.family().toLowerCase(Locale.ROOT);
        if (family.equals(Font.MONOSPACE_FAMILY)) {
            family = bundledStyles.containsKey(MONO_FAMILY) ? MONO_FAMILY : Font.DEFAULT_FAMILY;
        }
        if (family.equals(Font.DEFAULT_FAMILY)) {
            family = Fonts.defaultFamily().toLowerCase(Locale.ROOT);
            if (family.equals(Font.DEFAULT_FAMILY)) {
                family = "roboto";
            }
        }
        // Bundled: the family's face nearest the weight (italic dropped before weight), else its
        // regular, which is also all a family with one bundled face has.
        StbFont face = null;
        List<SystemFonts.Face> styles = bundledStyles.get(family);
        // A face that fails to load leaves the search, which then lands on the next nearest: a
        // jar without its Bold Italic draws Bold, never the family's Regular.
        while (face == null && styles != null && !styles.isEmpty()) {
            SystemFonts.Face picked = pickFace(styles, font.weight(), font.isItalic());
            face = bundled(bundledStyleKeys.get(picked));
            if (face == null) {
                styles.remove(picked);
            } else if (picked.variable()) {
                face = atWeight(face, picked, font.weight()); // a bundled variable face at its weight
            }
        }
        if (face == null) {
            face = bundled(family);
        }
        if (face != null) {
            return face;
        }
        // System font (loaded on demand), else the global fallback. A family we
        // don't know is the first real need for the OS enumeration; until the
        // scan lands this resolves to the fallback, and the fold-in
        // notification re-resolves it to the real face.
        requestSystemScan();
        StbFont system = resolveSystem(family, font.weight(), font.isItalic());
        return system != null ? system : fallback;
    }

    /**
     * A bundled face by family key, parsing it on first use: style variants
     * are registered lazily so an app that never shows them never pays the
     * classpath read + stb parse (~1 ms, once, on the UI thread).
     */
    private StbFont bundled(String key) {
        StbFont face = byFamily.get(key);
        if (face != null) {
            return face;
        }
        LazyFace pending = lazyBundled.remove(key); // never retried on failure
        if (pending == null) {
            return null;
        }
        try {
            StbFont parsed = StbFont.loadResource(pending.resource(), pending.name());
            assignId(parsed);
            pinned.add(parsed);
            byFamily.put(key, parsed);
            return parsed;
        } catch (RuntimeException error) {
            // Degrade to the family's regular / global fallback instead of
            // failing the frame that first used the style.
            LOG.log(Level.WARNING, "failed to load bundled font " + pending.resource(), error);
            return null;
        }
    }

    /** @return whether the bundled face for {@code key} has been parsed (tests). */
    boolean bundledLoaded(String key) {
        return byFamily.containsKey(key);
    }

    /**
     * Loads (and caches, LRU-bounded) the best system face for a family + style.
     * The load is a whole font file read on this thread, the UI thread; see
     * {@link #preloadFamily} for the way to have it already done.
     */
    private StbFont resolveSystem(String family, int weight, boolean italic) {
        List<SystemFonts.Face> candidates = systemFaces.get(family);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        SystemFonts.Face pick = pickFace(candidates, weight, italic);
        String key = systemKey(family, pick);
        StbFont cached = loadedSystem.get(key); // access-order touch
        if (cached != null) {
            readAhead.remove(cached); // asked for: in use from now on, like a face a resolve read
            return atWeight(cached, pick, weight);
        }
        if (preloading.containsKey(family)) {
            // A worker is already reading this family. Doing it again here would
            // block a frame on a read that is about to land anyway: stand in with
            // the global fallback and let the fold-in re-resolve, which it knows
            // to do from this record.
            stoodIn.add(family);
            return null;
        }
        StbFont face;
        try {
            face = StbFont.loadFile(pick.path(), pick.index(), pick.family() + " " + pick.style());
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "failed to load system font " + pick.path(), error);
            // Drop only the broken face (never retried); sibling styles of the
            // family may load fine, and the catalog still advertises them.
            List<SystemFonts.Face> remaining = new ArrayList<>(candidates);
            remaining.remove(pick);
            if (remaining.isEmpty()) {
                systemFaces.remove(family);
            } else {
                systemFaces.put(family, remaining);
            }
            return null;
        }
        assignId(face);
        putSystemFace(key, family, face);
        evictSystemBeyondCap(false, Set.of(face));
        return atWeight(face, pick, weight);
    }

    /**
     * The key a system face is resident under: the face itself, its file and its index in a
     * collection. Never the family and a bold flag, which a family with a Regular and a Medium
     * gives to both, so the second asked for would come back as the first.
     */
    private static String systemKey(String family, SystemFonts.Face face) {
        return family + "#" + face.path() + "#" + face.index();
    }

    /**
     * {@code face} drawn at the weight a request for {@code weight} lands on within its range:
     * the face itself where it is static or the weight is its default instance's, else that
     * instance, a view with an id of its own so its glyphs are cached apart.
     */
    private StbFont atWeight(StbFont face, SystemFonts.Face described, int weight) {
        // To the nearest ten: each instance is a shaper and a set of cached glyphs of its own,
        // held for the face's life, and a weight animated through the range made one per step.
        int instance = described.instanceWeight(Math.round(weight / (float) INSTANCE_STEP) * INSTANCE_STEP);
        if (!described.variable() || instance == described.weight()) {
            return face;
        }
        StbFont view = face.instance(instance);
        if (view == face) {
            // HarfBuzz cannot draw it: the default instance, shaped and drawn alike, and bold
            // drawn bold the way a family with no Bold is, where the default is lighter than it.
            if (weight >= BOLD_FROM && described.weight() < BOLD_FROM) {
                StbFont bold = face.syntheticBold();
                if (!faceIds.containsKey(bold)) {
                    assignId(bold);
                }
                return bold;
            }
            return face;
        }
        if (!faceIds.containsKey(view)) {
            assignId(view);
        }
        return view;
    }

    /** {@link #pickFace(List, int, boolean)} for a request that is regular or bold. */
    static SystemFonts.Face pickFace(List<SystemFonts.Face> faces, boolean bold, boolean italic) {
        return pickFace(faces, bold ? Font.BOLD : Font.REGULAR, italic);
    }

    /**
     * The face of a family that draws a request for {@code weight}, upright or italic.
     *
     * <p>Chosen by the face's declared weight, never by the order a collection lists its faces in:
     * a family of a dozen faces has several that are neither bold nor italic, and taking the first
     * of those drew a twelve-face family's Medium where its Regular was asked for. The search keeps
     * the request's side of the weight line (bold from 600, or not) before its slant, as the
     * bundled resolver always did, so a bold italic a family lacks lands on its bold before its
     * italic. Within that, the nearest weight wins in the order a style sheet's font matching uses
     * (for 400: 400, then 500, then lighter, then heavier; above 500: heavier first, then lighter;
     * below 400: lighter first), a variable face standing at the nearest point of its range, among
     * the faces of the width nearest normal, as font matching takes the width before the weight: a
     * family's "Condensed Black" never stands in for its "Bold"; then the plain style name.
     */
    static SystemFonts.Face pickFace(List<SystemFonts.Face> faces, int weight, boolean italic) {
        boolean bold = weight >= 600;
        List<SystemFonts.Face> tier = firstNonEmpty(faces,
                f -> sideOf(f, bold) && f.italic() == italic,
                f -> sideOf(f, bold),
                f -> f.italic() == italic);
        SystemFonts.Face best = null;
        for (SystemFonts.Face face : tier) {
            if (best == null || closer(face, best, weight)) {
                best = face;
            }
        }
        return best;
    }

    /** Whether some weight {@code face} draws is on the bold side of the line, or the other. */
    private static boolean sideOf(SystemFonts.Face face, boolean bold) {
        return bold ? face.maxWeight() >= 600 : face.minWeight() < 600;
    }

    @SafeVarargs
    private static List<SystemFonts.Face> firstNonEmpty(List<SystemFonts.Face> faces,
            Predicate<SystemFonts.Face>... tiers) {
        for (Predicate<SystemFonts.Face> tier : tiers) {
            List<SystemFonts.Face> kept = faces.stream().filter(tier).toList();
            if (!kept.isEmpty()) {
                return kept;
            }
        }
        return faces;
    }

    /**
     * Whether {@code a} matches {@code target} better than {@code b}; a full tie keeps {@code b}.
     * Two faces can declare the same weight (a system family's "Book" and "Roman" are both 400),
     * and then the one whose style name is the plain one for the request wins.
     */
    private static boolean closer(SystemFonts.Face a, SystemFonts.Face b, int target) {
        int byWidth = Integer.compare(Math.abs(a.width() - SystemFonts.NORMAL_WIDTH),
                Math.abs(b.width() - SystemFonts.NORMAL_WIDTH));
        if (byWidth != 0) {
            return byWidth < 0;
        }
        int byWeight = Long.compare(weightRank(a.instanceWeight(target), target),
                weightRank(b.instanceWeight(target), target));
        if (byWeight != 0) {
            return byWeight < 0;
        }
        boolean bold = target >= 600;
        return plainName(a, bold) && !plainName(b, bold);
    }

    /** Whether a face's style, without its slant, is the plain name for regular or for bold. */
    private static boolean plainName(SystemFonts.Face face, boolean bold) {
        String style = face.style().toLowerCase(Locale.ROOT)
                .replace("italic", "").replace("oblique", "").trim();
        return bold ? style.equals("bold")
                : style.isEmpty() || style.equals("regular") || style.equals("roman") || style.equals("normal");
    }

    /**
     * A weight's place in the search for {@code target}, lower first: the font-matching order of
     * CSS Fonts level 4, section 5.2, as a band (which direction is searched first) and a distance.
     */
    static long weightRank(int weight, int target) {
        int band;
        if (target >= 400 && target <= 500) {
            band = weight >= target && weight <= 500 ? 0 : weight < target ? 1 : 2;
        } else if (target < 400) {
            band = weight <= target ? 0 : 1;
        } else {
            band = weight >= target ? 0 : 1;
        }
        return (long) band << 32 | Math.abs(weight - target);
    }

    /**
     * Closes resident system faces, eldest first, until at most {@link #MAX_SYSTEM_FACES} remain;
     * never one of {@code keep}, nor one of the default family's, which draws the whole UI and
     * which the next frame would otherwise read again on the UI thread.
     *
     * <p>Faces in {@link #readAhead} go first: nothing draws with them and nothing names them, so
     * closing one clears no memo and moves no epoch. With {@code readAheadOnly} nothing else goes,
     * which is how a read nobody wants is kept from closing a face in use. Otherwise the least
     * recently used of the rest follow, each of which a memoized resolution or a held
     * {@code ShapedText} may name, so closing one clears the one and moves the epoch for the other.
     */
    private void evictSystemBeyondCap(boolean readAheadOnly, Set<StbFont> keep) {
        String defaultFamily = defaultFamilyKey();
        evictPass(true, keep, defaultFamily);
        if (!readAheadOnly) {
            evictPass(false, keep, defaultFamily);
        }
    }

    /** One walk of {@link #evictSystemBeyondCap}, over the read-ahead faces alone or over all. */
    private void evictPass(boolean readAheadOnly, Set<StbFont> keep, String defaultFamily) {
        var iterator = loadedSystem.values().iterator();
        while (loadedSystem.size() > MAX_SYSTEM_FACES && iterator.hasNext()) {
            StbFont evicted = iterator.next(); // eldest first (access-order)
            boolean unasked = readAhead.contains(evicted);
            if ((readAheadOnly && !unasked) || keep.contains(evicted)
                    || defaultFamily.equals(loadedSystemFamilies.get(evicted))) {
                continue;
            }
            iterator.remove();
            loadedSystemKeys.remove(evicted);
            loadedSystemFamilies.remove(evicted);
            readAhead.remove(evicted);
            Integer evictedId = faceIds.remove(evicted);
            if (evictedId != null) {
                // Both directions, or a held ShapedText's run would still resolve its id to a face
                // whose native memory is about to be freed, and paint from a closed atlas source.
                facesById.remove(evictedId);
            }
            for (StbFont view : evicted.instances()) {
                // Its instances read the same bytes and are closed with it, so their ids go too.
                Integer viewId = faceIds.remove(view);
                if (viewId != null) {
                    facesById.remove(viewId);
                }
            }
            if (!unasked) {
                resolutionChanged(); // a cached resolution may point at the evicted face
            }
            evicted.close();
        }
    }

    // ------------------------------------------------------------- glyph access

    /**
     * Face that should draw {@code codepoint} for text whose primary face is
     * {@code primary}: the primary if it has the glyph, else the first fallback
     * face that does, else the primary (so a {@code .notdef} box shows).
     *
     * <p>Takes a code point because coverage is a question about a character;
     * the caller must then take the <em>glyph index</em> from the face this
     * returns, since an index means nothing in any other face. On the
     * last-resort branch that answer is legitimately index 0 — that is what
     * draws the box, and it is not an error to be filtered out.
     */
    StbFont faceForCodepoint(StbFont primary, int codepoint) {
        if (primary.hasGlyph(codepoint)) {
            return primary;
        }
        for (int i = 0; i < fallbackFaces.size(); i++) {
            StbFont face = fallbackFaces.get(i);
            if (face != primary && face.hasGlyph(codepoint)) {
                // Bold text stays bold in the script that rescues it: a bold primary takes the
                // fallback's own Bold where the family has one. Chosen here, per code point, so
                // the run the shaper cuts and the glyphs the canvas draws agree on the face.
                if (isBold(primary)) {
                    StbFont twin = boldTwin(face);
                    if (twin.hasGlyph(codepoint)) {
                        return twin;
                    }
                }
                return face;
            }
        }
        // Only now: a glyph no face already in memory can draw is the first REAL need for the
        // optional fallbacks (CJK + colour emoji at tens of megabytes, and the four
        // complex-script faces at 531 KB together), so their background load is kicked here
        // rather than above the loop. Asking before looking made every menu shortcut hint (three
        // kilobytes of symbols, already resident) pay for that parse.
        //
        // This is also the ONLY thing that ever kicks it, and the shaper reaches these scripts
        // through this very method — so an Arabic run resolves to the primary and shapes into
        // boxes exactly once, until the fold-in bumps the epoch and every held ShapedText
        // re-shapes against the face that has now arrived.
        requestHeavyFallbacks();
        return primary;
    }

    /** Whether {@code face} draws bold: by its weight, so "Roboto Black" and an instance at 700 are. */
    private static boolean isBold(StbFont face) {
        return face.weight() >= 600;
    }

    /**
     * {@code face}'s Bold, parsed on first need: the bundled script faces register theirs as lazy
     * variants, and a face whose family has none (the CJK face, a system face) is drawn as a
     * {@linkplain StbFont#syntheticBold synthetic} one.
     */
    private StbFont boldTwin(StbFont face) {
        StbFont twin = boldTwins.get(face);
        if (twin == null) {
            StbFont bold = isBold(face) ? null : bundled(face.name().toLowerCase(Locale.ROOT) + " bold");
            if (bold == null && !isBold(face)) {
                // No Bold in the family (the CJK face, at sixteen megabytes, ships Regular only):
                // the face drawn thicker, with an id of its own so its glyphs are cached apart.
                bold = face.syntheticBold();
                assignId(bold);
            }
            twin = bold != null ? bold : face;
            boldTwins.put(face, twin);
        }
        return twin;
    }

    /**
     * The colour glyph for {@code cp} together with the box it is drawn in, or {@code null}.
     * The box is in ems, so a caller multiplies by the font size.
     */
    ColorEmojiFont.Emoji colorEmojiGlyph(int cp) {
        return colorEmoji == null || drawsAsText(cp) ? null : colorEmoji.emoji(cp);
    }

    /**
     * Whether {@code cp} must be drawn as a glyph even though the colour-emoji font has a
     * picture for it; true exactly for the key symbols.
     *
     * <p>Two of them, the Home and End arrows, are in the emoji font as well, and the emoji
     * path is consulted before any fallback face: without this a menu hint would show two
     * full-colour pictograms in the middle of a row of monochrome symbols. Unicode agrees
     * (both default to text presentation and turn into emoji only when a variation selector
     * asks), but this deliberately does not implement that rule in general. Doing so would
     * hand every emoji the CJK face happens to carry a monochrome glyph for back to text, and
     * that is a much larger change than a menu row is entitled to make.
     */
    private boolean drawsAsText(int cp) {
        return menuSymbols != null && menuSymbols.hasGlyph(cp);
    }

    /** Advance width of {@code cp}'s color emoji at {@code sizePx} (0 when no color font). */
    double colorEmojiAdvance(int cp, float sizePx) {
        return colorEmoji == null || drawsAsText(cp) ? 0 : colorEmoji.advance(cp, sizePx);
    }

    /** Measures {@code text} in {@code font}, honoring script fallback and color emoji. */
    TextMetrics measure(Font font, String text) {
        StbFont primary = resolve(font);
        String value = text == null ? "" : text;
        if (value.isEmpty() || (fallbackFaces.isEmpty() && colorEmoji == null)) {
            return primary.measure(value, font.size());
        }
        float size = font.size();
        return primary.measureWithFallback(value, size,
                cp -> faceForCodepoint(primary, cp),
                cp -> colorAdvanceOrNaN(primary, cp, size));
    }

    /** Color-emoji advance for {@code cp} when the primary lacks it, else {@code NaN}. */
    private double colorAdvanceOrNaN(StbFont primary, int cp, float sizePx) {
        // NaN means "not an emoji, measure it as a glyph", which is what the key symbols
        // are, even the two the emoji font also carries. Measure and paint have to agree on
        // that or the row is laid out for one and drawn with the other.
        return colorEmoji != null && !primary.hasGlyph(cp) && colorEmoji.covers(cp)
                && !drawsAsText(cp)
                ? colorEmoji.advance(cp, sizePx)
                : Double.NaN;
    }

    /** Stable small id per face, used in glyph cache keys. */
    int faceId(StbFont font) {
        Integer id = faceIds.get(font);
        // 0 for a face this store does not know, which an LRU-evicted one becomes.
        // It then shares atlas keys with whichever face really is 0 — and now that
        // the atlas keys on glyph indices, the two faces' index spaces are
        // unrelated, so the collision draws different CHARACTERS rather than the
        // right character in the wrong face. Harder to recognise, same cause.
        return id != null ? id : 0;
    }

    @Override
    public void close() {
        closed = true; // a background fallback load finishing later frees itself
        // In-flight preloads are refused delivery by their deliverIf and freed
        // on a worker; their waiters are never called, the store being gone.
        preloading.clear();
        awaitingScan.clear();
        stoodIn.clear();
        fontsSubscription.cancel();
        if (colorEmoji != null) {
            colorEmoji.close();
        }
        for (StbFont face : pinned) {
            face.close();
        }
        for (StbFont face : loadedSystem.values()) {
            face.close();
        }
        loadedSystem.clear();
        loadedSystemFamilies.clear();
        readAhead.clear();
    }
}
