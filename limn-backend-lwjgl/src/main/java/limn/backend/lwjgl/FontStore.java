package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.Fonts;
import limn.graphics.Image;
import limn.graphics.TextMetrics;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Backend-wide font registry (CPU data, shared by all windows). Ships the four
 * embedded Roboto faces (Regular, Bold, Italic, Bold-Italic; Apache 2.0), which
 * back {@link Font#DEFAULT_FAMILY} and any unknown family, plus the optional Noto
 * fallbacks. Loading is lazy throughout: only Roboto Regular parses at
 * construction; the style variants parse on first resolve, and the heavyweight
 * Noto fallbacks arrive from a background parse (see
 * {@link #parseHeavyFallbacks}/{@link #installHeavyFallbacks}). A {@link Font}'s
 * {@code (family, bold, italic)} triple resolves to the matching face, degrading
 * gracefully (drop italic, then bold, then to the family's regular, then the
 * global fallback).
 *
 * <p><b>Script fallback.</b> Any code point the primary face lacks (CJK, emoji, …)
 * is resolved per-code-point against the registered fallback faces, so mixed-script
 * lines "just work". Until the background fallbacks land (and when the Noto
 * binaries are absent) this degrades to Roboto-only; the fold-in re-notifies
 * {@link Fonts} listeners, so scenes relayout and boxes heal into glyphs.
 *
 * <p><b>System fonts.</b> Families enumerated from the OS (see {@link SystemFonts})
 * are registered as lightweight descriptors and their faces are loaded <em>lazily</em>,
 * only when a family is actually selected. To bound memory, at most
 * {@link #MAX_SYSTEM_FACES} system faces stay resident at once (LRU-evicted);
 * the bundled faces are pinned. The family {@link Font#DEFAULT_FAMILY} resolves to
 * is {@link Fonts#defaultFamily()}, so switching it re-fonts the whole UI at runtime.
 *
 * <p><b>Selecting a system family costs a file read.</b> That read happens inside
 * {@link #resolve}, which runs on the UI thread inside a measure or a paint, and
 * an OS font file is unbounded in size. {@link #preloadFamily} moves it to a
 * worker: warm the family, then select it, and the frame that switches never
 * touches the disk.
 */
final class FontStore implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FontStore.class.getName());

    /** Max resident dynamically-loaded system faces; bundled faces don't count. */
    private static final int MAX_SYSTEM_FACES = 6;

    private final Map<String, StbFont> byFamily = new HashMap<>();
    private final Map<StbFont, Integer> faceIds = new IdentityHashMap<>();
    private int nextFaceId; // monotonic: never reused, so an evicted+reloaded face gets a fresh atlas key
    private final List<StbFont> pinned = new ArrayList<>();      // bundled faces (closed on shutdown)
    private final List<StbFont> fallbackFaces = new ArrayList<>(); // per-code-point fallback chain
    private final Map<Font, StbFont> resolved = new IdentityHashMap<>();
    private final StbFont fallback; // Roboto Regular, assigned in the constructor
    private ColorEmojiFont colorEmoji; // optional CBDT color emoji; arrives from the background load
    // Bundled style variants parse lazily on first resolve (an app that never
    // shows bold italic never pays for it): family key -> pending face.
    private record LazyFace(String name, String resource) {
    }

    private final Map<String, LazyFace> lazyBundled = new HashMap<>();
    private boolean closed; // a background fallback load may complete after close()

    // On-demand kicks (one-shot, UI thread): the heavy Noto fallbacks load on
    // the FIRST glyph the primary face lacks; the OS font enumeration runs on
    // the FIRST listing request (or unknown-family resolve). An app that shows
    // only Latin text in bundled families never pays for either.
    private boolean heavyFallbacksRequested;
    private Throwable heavyFallbacksFailure;
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
    private final TreeSet<String> familyNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private final Runnable onFontsChanged = () -> resolved.clear();

    /**
     * The menu key symbols, or {@code null} in a build without them. Held apart from the
     * fallback list because it is the one face that has to beat the colour-emoji path; see
     * {@link #drawsAsText}.
     */
    private final StbFont menuSymbols;

    FontStore() {
        // Only Roboto Regular parses eagerly: it is the last-resort fallback and
        // the first frame's measure needs SOME face. Everything else is lazy:
        // style variants on first resolve, the heavyweight broad-coverage
        // fallbacks (CJK + color emoji, tens of MB) on a background task the
        // backend kicks off (see parseHeavyFallbacks/installHeavyFallbacks).
        StbFont roboto = register("Roboto", "/limn/backend/lwjgl/fonts/Roboto-Regular.ttf",
                "roboto", Font.DEFAULT_FAMILY);
        lazyBundled.put("roboto bold",
                new LazyFace("Roboto Bold", "/limn/backend/lwjgl/fonts/Roboto-Bold.ttf"));
        lazyBundled.put("roboto italic",
                new LazyFace("Roboto Italic", "/limn/backend/lwjgl/fonts/Roboto-Italic.ttf"));
        lazyBundled.put("roboto bold italic",
                new LazyFace("Roboto Bold Italic", "/limn/backend/lwjgl/fonts/Roboto-BoldItalic.ttf"));
        familyNames.add("Roboto");
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
        Fonts.addChangeListener(onFontsChanged);
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
        limn.concurrent.Ui.async(FontStore::parseHeavyFallbacks).whenComplete((loaded, failed) -> {
            if (failed != null) {
                // Without this the exception ends inside a future nobody observes, and the only
                // symptom is CJK text rendering as boxes for the rest of the process.
                heavyFallbacksFailure = failed;
                LOG.log(Level.WARNING,
                        "fallback fonts failed to load; CJK and emoji stay .notdef", failed);
                return;
            }
            if (installHeavyFallbacks(loaded)) {
                catalogChanged.run();
                LOG.log(Level.INFO, "fallback fonts installed (background load, on first need)");
            }
        });
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

    /** @return why the boxes are not healing: what the background parse threw, or null (tests). */
    Throwable heavyFallbacksFailure() {
        return heavyFallbacksFailure;
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
    record HeavyFallbacks(StbFont cjk, ColorEmojiFont emoji) {
        void close() {
            if (cjk != null) {
                cjk.close();
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
                        "/limn/backend/lwjgl/fonts/NotoSansCJK-Regular.otf",
                        "/limn/backend/lwjgl/fonts/NotoSansCJK-Regular.ttf",
                        "/limn/backend/lwjgl/fonts/NotoSansJP-Regular.ttf",
                        "/limn/backend/lwjgl/fonts/NotoSansSC-Regular.otf"),
                // Emoji come from the color font (CBDT bitmaps), drawn as images with their
                // own cmap/advance; stb can't open it (bitmap-only), so it isn't an StbFont
                // face in the chain. Absent → no emoji (a .notdef box).
                () -> ColorEmojiFont.loadResourceIfPresent(
                        "/limn/backend/lwjgl/fonts/NotoColorEmoji.ttf"));
    }

    /**
     * The order matters and so does the failure. An absent resource is null and graceful, but one
     * that is present and unreadable throws, and by then the CJK face is a native buffer stb has
     * already allocated that nothing else holds; the fold-in that would have taken ownership is
     * exactly what is not going to happen. So a throw from the second loader closes the first.
     *
     * <p>Package-private with its loaders passed in for the test: no bundled resource can be made
     * to fail on demand, and the leak is only reachable on that path.
     */
    static HeavyFallbacks parseHeavyFallbacks(java.util.function.Supplier<StbFont> cjkLoader,
                                              java.util.function.Supplier<ColorEmojiFont> emojiLoader) {
        StbFont cjk = cjkLoader.get();
        try {
            return new HeavyFallbacks(cjk, emojiLoader.get());
        } catch (RuntimeException failed) {
            if (cjk != null) {
                cjk.close();
            }
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
        LOG.log(Level.INFO, "optional fallback font not bundled ({0}); see fonts/README.md", name);
        return null;
    }

    /**
     * Folds the background-parsed fallbacks in (UI thread): CJK joins the
     * per-code-point fallback chain ahead of the Roboto last resort and becomes
     * a selectable family; color emoji switches on. The caller re-installs the
     * font catalog so {@code Fonts} listeners (scene relayout, font pickers)
     * observe the upgrade; text that showed {@code .notdef} boxes heals.
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
        if (loaded.emoji() != null) {
            colorEmoji = loaded.emoji();
            LOG.log(Level.INFO, "color emoji enabled (Noto Color Emoji)");
            changed = true;
        } else if (loaded.cjk() != null || colorEmoji == null) {
            LOG.log(Level.INFO, "color emoji font not bundled. See fonts/README.md");
        }
        if (changed) {
            resolved.clear(); // cached resolutions may now upgrade to the CJK family
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

    private int assignId(StbFont face) {
        int id = nextFaceId++;
        faceIds.put(face, id);
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
        resolved.clear();
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
            throw new IllegalArgumentException("no usable font face in " + file.toAbsolutePath());
        }
        for (SystemFonts.Face face : faces) {
            String key = face.family().toLowerCase(Locale.ROOT);
            // Idempotent: loading the same file twice must not leave the family holding two
            // copies of every face, which would make pickFace's choice depend on insertion order.
            addFaceOnce(loadedFaces, key, face);
            addFaceOnce(systemFaces, key, face);
            familyNames.add(face.family());
        }
        resolved.clear();
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
     * bundled one, is already resident, or is unknown to the operating system.
     * It never runs synchronously, so a caller may flip its own state inside it
     * without re-entering this. It does not run at all if the store is closed
     * first; the faces the load produced are then freed on a worker thread.
     *
     * <p>The load is idempotent and cannot be withdrawn: two requests for one
     * family share a single file read, and what a read produces goes into the
     * shared face cache, where it is worth the same whether or not whoever
     * asked still wants it. A caller that can change its mind (a font picker
     * moving through a list) must therefore check, inside {@code whenResident},
     * that the family it asked for is still the one it wants.
     *
     * <p>Italic faces are deliberately not preloaded: at most
     * {@link #MAX_SYSTEM_FACES} system faces stay resident, and regular plus
     * bold is what a UI needs on the frame after a switch.
     *
     * @param family       the family name, matched case-insensitively;
     *                     {@code null} or blank does nothing at all
     * @param whenResident run on the UI thread when the faces are resident, or
     *                     {@code null} for no callback
     */
    void preloadFamily(String family, Runnable whenResident) {
        limn.concurrent.Ui.checkUiThread();
        Runnable done = whenResident == null ? () -> { } : whenResident;
        String key = family == null ? "" : family.toLowerCase(Locale.ROOT).trim();
        if (closed || key.isEmpty() || !limn.concurrent.Ui.isInstalled()) {
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

    /** Starts (or skips) the background read for {@code key}. UI thread. */
    private void startFamilyPreload(String key, List<Runnable> waiting) {
        List<SystemFonts.Face> candidates = systemFaces.get(key);
        List<SystemFonts.Face> picks = candidates == null ? List.of() : preloadPicks(candidates);
        List<SystemFonts.Face> missing = new ArrayList<>(picks.size());
        for (SystemFonts.Face pick : picks) {
            if (!loadedSystem.containsKey(key + suffix(pick.bold(), pick.italic()))) {
                missing.add(pick);
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
                    .onFailure(error -> finishPreload(key, error))
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
                loaded.add(new LoadedFace(key + suffix(pick.bold(), pick.italic()),
                        StbFont.loadFile(pick.path(), pick.index(), pick.family() + " " + pick.style())));
            } catch (RuntimeException error) {
                LOG.log(Level.WARNING, "failed to preload system font " + pick.path(), error);
            }
        }
        return new PreloadedFamily(loaded);
    }

    /** Folds a background-parsed family in and wakes its waiters. UI thread. */
    private void installPreloaded(String key, PreloadedFamily loaded) {
        boolean changed = false;
        for (LoadedFace entry : loaded.faces()) {
            if (loadedSystem.containsKey(entry.key())) {
                entry.face().close(); // a synchronous resolve won the race
                continue;
            }
            assignId(entry.face());
            loadedSystem.put(entry.key(), entry.face());
            loadedSystemKeys.put(entry.face(), entry.key());
            changed = true;
        }
        if (changed) {
            evictSystemBeyondCap();
            resolved.clear(); // text that fell back to Roboto now resolves to the real face
        }
        finishPreload(key, null);
        if (changed) {
            // The same fold-in notification the fallback fonts use: scenes
            // relayout, so text drawn in the stand-in face picks up this one.
            catalogChanged.run();
        }
    }

    /** Drops the in-flight record and runs its waiters. UI thread. */
    private void finishPreload(String key, Throwable failure) {
        if (failure != null) {
            LOG.log(Level.WARNING, "font preload failed for " + key, failure);
        }
        List<Runnable> waiting = preloading.remove(key);
        if (waiting == null) {
            return;
        }
        for (Runnable waiter : waiting) {
            waiter.run();
        }
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
        if (resolved.size() < 4096) { // bound: hostile churn of Font instances
            resolved.put(font, face);
        }
        return face;
    }

    /**
     * The resolve memo short-circuits {@link #resolveSystem}, whose {@code get}
     * is the access-order touch; without this, the LRU reflects first-resolution
     * order and eviction can close the face currently rendering the whole UI.
     */
    private void touchSystemLru(StbFont face) {
        String key = loadedSystemKeys.get(face);
        if (key != null) {
            loadedSystem.get(key); // access-order relink
        }
    }

    private StbFont resolveUncached(Font font) {
        String family = font.family().toLowerCase(Locale.ROOT);
        if (family.equals(Font.DEFAULT_FAMILY)) {
            family = Fonts.defaultFamily().toLowerCase(Locale.ROOT);
            if (family.equals(Font.DEFAULT_FAMILY)) {
                family = "roboto";
            }
        }
        // Bundled: exact variant, else drop italic, else the family's regular.
        StbFont face = bundled(family + suffix(font.isBold(), font.isItalic()));
        if (face == null && font.isItalic()) {
            face = bundled(family + suffix(font.isBold(), false));
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
        StbFont system = resolveSystem(family, font.isBold(), font.isItalic());
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
    private StbFont resolveSystem(String family, boolean bold, boolean italic) {
        List<SystemFonts.Face> candidates = systemFaces.get(family);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        SystemFonts.Face pick = pickFace(candidates, bold, italic);
        String key = family + suffix(pick.bold(), pick.italic());
        StbFont cached = loadedSystem.get(key); // access-order touch
        if (cached != null) {
            return cached;
        }
        if (preloading.containsKey(family)) {
            // A worker is already reading this family. Doing it again here would
            // block a frame on a read that is about to land anyway: stand in with
            // the global fallback and let the fold-in re-resolve.
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
        loadedSystem.put(key, face);
        loadedSystemKeys.put(face, key);
        evictSystemBeyondCap();
        return face;
    }

    private static SystemFonts.Face pickFace(List<SystemFonts.Face> faces, boolean bold, boolean italic) {
        SystemFonts.Face regular = null;
        for (SystemFonts.Face face : faces) {
            if (face.bold() == bold && face.italic() == italic) {
                return face; // exact style match
            }
            if (!face.bold() && !face.italic()) {
                regular = face;
            }
        }
        return regular != null ? regular : faces.get(0);
    }

    /** Closes the least-recently-used system faces until within the memory cap. */
    private void evictSystemBeyondCap() {
        var iterator = loadedSystem.entrySet().iterator();
        while (loadedSystem.size() > MAX_SYSTEM_FACES && iterator.hasNext()) {
            StbFont evicted = iterator.next().getValue(); // eldest first (access-order)
            iterator.remove();
            loadedSystemKeys.remove(evicted);
            faceIds.remove(evicted);
            resolved.clear(); // a cached resolution may point at the evicted face
            evicted.close();
        }
    }

    // ------------------------------------------------------------- glyph access

    /**
     * Face that should draw {@code codepoint} for text whose primary face is
     * {@code primary}: the primary if it has the glyph, else the first fallback
     * face that does, else the primary (so a {@code .notdef} box shows).
     */
    StbFont faceForCodepoint(StbFont primary, int codepoint) {
        if (primary.hasGlyph(codepoint)) {
            return primary;
        }
        for (int i = 0; i < fallbackFaces.size(); i++) {
            StbFont face = fallbackFaces.get(i);
            if (face != primary && face.hasGlyph(codepoint)) {
                return face;
            }
        }
        // Only now: a glyph no face already in memory can draw is the first REAL need for the
        // heavy fallbacks (CJK + color emoji, tens of megabytes), so their background load is
        // kicked here rather than above the loop. Asking before looking made every menu
        // shortcut hint (three kilobytes of symbols, already resident) pay for that parse.
        requestHeavyFallbacks();
        return primary;
    }

    /**
     * @return the color-emoji image for {@code cp}, or {@code null}. Used by the
     *         renderer for code points the primary lacks; the color font supplies
     *         both the bitmap and (via {@link #colorEmojiAdvance}) the advance.
     */
    Image colorEmojiImage(int cp) {
        ColorEmojiFont.Emoji emoji = colorEmojiGlyph(cp);
        return emoji == null ? null : emoji.image();
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
        return id != null ? id : 0;
    }

    private static String suffix(boolean bold, boolean italic) {
        if (bold && italic) {
            return " bold italic";
        }
        if (bold) {
            return " bold";
        }
        if (italic) {
            return " italic";
        }
        return "";
    }

    @Override
    public void close() {
        closed = true; // a background fallback load finishing later frees itself
        // In-flight preloads are refused delivery by their deliverIf and freed
        // on a worker; their waiters are never called, the store being gone.
        preloading.clear();
        awaitingScan.clear();
        Fonts.removeChangeListener(onFontsChanged);
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
    }
}
