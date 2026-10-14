package limn.video.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import limn.backend.Platform;
import limn.io.Resources;
import limn.io.internal.NativeExtraction;
import org.jspecify.annotations.Nullable;

/**
 * Finds and loads the native library the FFmpeg decoder needs, and answers whether it is there.
 *
 * <p><b>It never throws.</b> {@link #isAvailable()} is the whole interface for anything on a
 * decision path, and it reports {@code false} for every way this can go wrong: a build for
 * another operating system, a build for another processor, no build at all, a temporary directory
 * that cannot be written to, a library that loads but is missing an entry point. Each of those
 * leaves a sentence in {@link #failure()} saying which one it was, because a decoder that is
 * silently absent is indistinguishable from one that is present and declining the file.
 *
 * <p>That matters more than it sounds. {@code VideoDecoder.supports} must never throw and must be
 * cheap, and it runs once per installed decoder every time anything at all is opened. If this
 * class threw on a machine with no native (which is every machine that has not built one), then
 * one absent library would make the whole probe unusable and the pure-Java decoders behind it
 * would stop being reachable.
 *
 * <h2>Where it looks, in order</h2>
 *
 * <ol>
 *   <li><b>{@code -Dlimn.video.ffmpeg.library=<directory>}</b>: a directory holding the
 *       libraries, loaded from where they lie. For a developer pointing at a build tree, and for
 *       an installer that has already laid the files out beside the application.</li>
 *   <li><b>{@code java.library.path}</b>: {@code System.loadLibrary}, letting the operating
 *       system resolve the FFmpeg libraries the way it resolves any other dependency. For a
 *       package that installed them where the loader already looks.</li>
 *   <li><b>The classpath</b>: extracted from the jar to a cache directory and loaded from there.
 *       This is the path a plain {@code java -jar} takes, and the reason the extraction rules
 *       below exist.</li>
 * </ol>
 *
 * <h2>Extraction</h2>
 *
 * <p>The cache directory is named after a digest of the libraries themselves, so two builds never
 * collide, an upgraded application never loads yesterday's library, and a directory left behind
 * by an older version is simply never consulted again. Files are written to a private temporary
 * directory and the whole directory is then moved into place in one step, so a second process
 * either sees nothing or sees everything, never a half-written library, which would load and
 * then crash rather than fail.
 *
 * <p>Two applications extracting at once is therefore ordinary: both write their own temporary
 * directory, one move wins, and the loser deletes its copy and uses the winner's. Neither waits
 * for the other and there is no lock file to be left behind by a process that was killed.
 *
 * <p><b>None of that happens directly in the temporary directory, because that directory is
 * shared.</b> On most Unix systems {@code java.io.tmpdir} is {@code /tmp}, which every local
 * account can write to, and the digest naming the extraction directory is taken over bytes that
 * ship inside the application, so any other account on the machine can work the path out offline
 * and create it first, and everything found there is handed to {@code System.load}. Extraction
 * therefore happens one level down, in a directory of this user's own that is created readable and
 * writable by nobody else; a directory already there that this user does not own, or that anyone
 * else can write into, is refused and reported through {@link #failure()} rather than loaded from.
 *
 * <p>Where nothing can be written (a read-only temporary directory, a container with no writable
 * filesystem, a hardened deployment), extraction fails, {@link #isAvailable()} is false and
 * {@link #failure()} says so. {@code -Dlimn.video.ffmpeg.cache=<directory>} names somewhere else
 * to try, which is the answer for a deployment that has a writable directory but not that one.
 *
 * <h2>Running on JDK 24 and later</h2>
 *
 * <p>Loading a native library became a restricted operation in JDK 24. It still works, and it
 * prints a four-line warning on every run naming this class; a future release is documented to
 * refuse it instead. An application that ships this decoder should therefore launch with
 * {@code --enable-native-access=ALL-UNNAMED}, or, once it has a module descriptor, with its own
 * module named instead of {@code ALL-UNNAMED}.
 *
 * <p>The flag cannot simply be added to a launcher that might also run on JDK 17: a JVM that does
 * not know an option does not warn about it, it refuses to start. Add it where the launcher knows
 * which JDK it is on: a jpackage image knows, and a start script can test.
 *
 * <h2>Threading, and why the first call is the expensive one</h2>
 *
 * <p>Any thread. The load is attempted once per class loader and the outcome, success or failure,
 * is remembered: a machine with no native pays one failed lookup for the life of the process
 * rather than one per call to {@code supports}.
 *
 * <p><b>That one attempt is not cheap, and every path into video reaches it.</b> On a build
 * carrying the libraries in its jar it reads the manifest resource, digests every library's bytes
 * to name the cache directory, copies each of them out of the jar (tens of megabytes), moves the
 * directory into place, links them in dependency order and runs the native identity probe. It
 * holds a global lock while doing so, so a second thread that asks meanwhile waits for all of it.
 * On the UI thread it is a freeze of that length, and the call that triggers it is as likely to be
 * a file chooser asking whether a clip is playable as an actual open. {@link
 * FfmpegVideoDecoder#warmUp()} is what pays it somewhere else; every call after the first is a
 * lock and a field read.
 */
public final class FfmpegLibrary {

    /** A directory holding the libraries, to be loaded from where they lie rather than extracted. */
    public static final String LIBRARY_PROPERTY = "limn.video.ffmpeg.library";

    /** Where to extract to, when the default temporary directory cannot be written to. */
    public static final String CACHE_PROPERTY = "limn.video.ffmpeg.cache";

    private static final String RESOURCE_ROOT = "limn/video/ffmpeg/native/";

    /**
     * The shim ABI this Java expects; see {@link FfmpegNative#abi()}. Moves only when a native
     * signature does, together with {@code LIMN_FFMPEG_ABI} in limn-ffmpeg-natives.
     */
    static final int EXPECTED_ABI = 1;

    /** The payload artifact, named in every message that asks the reader to add or update it. */
    private static final String PAYLOAD = "io.github.limn-toolkit:limn-ffmpeg-natives";

    /** Written by the build script: the libraries to load, in dependency order, one per line. */
    private static final String MANIFEST = "libraries.txt";

    private static final Object LOCK = new Object();

    private static boolean attempted;
    private static boolean loaded;
    private static String failure;

    private FfmpegLibrary() {
    }

    /**
     * <p>Any thread, and <b>the first call in a process is a slow one</b>: it is the call that
     * extracts and links the libraries, which on a build carrying them is tens of megabytes of
     * copying, and it does that under a global lock, so every other thread asking meanwhile blocks
     * behind it too. Later calls are a lock and a field read. Do not let the first one happen on
     * the UI thread; {@link FfmpegVideoDecoder#warmUp()} exists to make it happen on a worker
     * instead.
     *
     * @return whether the native library is loaded and usable. Never throws, on any machine, for
     *         any reason; the first call attempts the load and every call afterwards reports what
     *         that one found.
     */
    public static boolean isAvailable() {
        synchronized (LOCK) {
            if (!attempted) {
                attempted = true;
                try {
                    failure = attemptLoad();
                    loaded = failure == null;
                } catch (Throwable error) {
                    // Deliberately Throwable: UnsatisfiedLinkError is an Error, and so is the
                    // ExceptionInInitializerError a static initializer failure would arrive as.
                    // Neither may reach a caller that only asked whether video is available.
                    loaded = false;
                    failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                }
            }
            return loaded;
        }
    }

    /**
     * @return why {@link #isAvailable()} is false, in one sentence naming the platform looked for
     *         and what went wrong, or null when the library did load
     */
    public static @Nullable String failure() {
        isAvailable();
        synchronized (LOCK) {
            return failure;
        }
    }

    /**
     * @throws FfmpegException with {@link #failure()}'s text, for the paths where being unable to
     *         continue is the honest answer: opening a stream, rather than deciding whether to
     *         offer to
     */
    static void require() {
        if (!isAvailable()) {
            throw new FfmpegException("the FFmpeg decoder's native library is not loaded: "
                    + failure());
        }
    }

    /** @return {@code macos-aarch64} and the like: the directory this machine's build lives in */
    public static String platform() {
        return platformFor(Platform.current());
    }

    /**
     * The directory name for a given platform: the family's short name and the JVM's
     * architecture, joined by a hyphen.
     *
     * <p>{@link Platform} is what settles that the architecture is <b>the JVM's, never the
     * machine's</b> — an x86_64 JVM under Rosetta can load only an x86_64 library — and what folds
     * {@code arm64} and {@code aarch64} into one spelling. What is decided here is only the
     * vocabulary of the directory names, and that anything that is not macOS or Windows looks in
     * the Linux build: the BSDs are not a platform this payload is built for, and "no library for
     * linux-x86_64" is a truer report there than a directory name nobody ships.
     */
    static String platformFor(Platform platform) {
        String os = switch (platform.os()) {
            case MACOS -> "macos";
            case WINDOWS -> "windows";
            default -> "linux";
        };
        return os + "-" + platform.arch();
    }

    // ------------------------------------------------------------------ the three routes

    /** @return null when the library loaded, or a sentence saying why it did not */
    private static String attemptLoad() {
        String platform = platform();

        String directory = System.getProperty(LIBRARY_PROPERTY);
        if (directory != null && !directory.isBlank()) {
            return loadFromDirectory(Path.of(directory), platform);
        }

        try {
            System.loadLibrary("limnffmpeg");
            return probe();
        } catch (UnsatisfiedLinkError ignored) {
            // Not on java.library.path, which is the ordinary case rather than a problem: fall
            // through to the jar. The reason is deliberately not kept: reporting "not found on
            // java.library.path" when the real failure came later, in extraction, would name the
            // wrong step.
        }

        return loadFromClasspath(platform);
    }

    private static String loadFromDirectory(Path directory, String platform) {
        Path manifest = directory.resolve(MANIFEST);
        List<String> names;
        try {
            names = readManifest(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return "no " + MANIFEST + " under " + directory + " (" + LIBRARY_PROPERTY + ")";
        }
        for (String name : names) {
            Path library = directory.resolve(name);
            if (!Files.isRegularFile(library)) {
                return "missing " + name + " under " + directory;
            }
            try {
                System.load(library.toAbsolutePath().toString());
            } catch (UnsatisfiedLinkError error) {
                return describeLinkFailure(library.toString(), platform, error);
            }
        }
        return probe();
    }

    private static String loadFromClasspath(String platform) {
        String root = RESOURCE_ROOT + platform + "/";
        String manifestText = readResource(root + MANIFEST);
        if (manifestText == null) {
            return "this build carries no FFmpeg native for " + platform + ": add the " + PAYLOAD
                    + " classifier natives-" + platform + " (or limn-video-ffmpeg-natives-all"
                    + " for every platform), or set " + LIBRARY_PROPERTY;
        }
        List<String> names = readManifest(manifestText);
        if (names.isEmpty()) {
            return "the FFmpeg native manifest for " + platform + " is empty";
        }

        Path directory;
        try {
            directory = extract(root, names, manifestText);
        } catch (IOException error) {
            return "cannot extract the FFmpeg native for " + platform + ": " + error
                    + " (set " + CACHE_PROPERTY + " to a writable directory)";
        }

        for (String name : names) {
            Path library = directory.resolve(name);
            try {
                System.load(library.toAbsolutePath().toString());
            } catch (UnsatisfiedLinkError error) {
                return describeLinkFailure(name, platform, error);
            }
        }
        return probe();
    }

    /**
     * A library can load and still be the wrong one: an older build with a renamed entry point,
     * or a hand-placed file from a different version. One call that must succeed turns that into a
     * refusal here rather than an UnsatisfiedLinkError from somewhere in the middle of a decode.
     */
    private static String probe() {
        // The ABI first, because it is the check whose failure names the fix. The shim and this
        // class release apart now (ADR 037); an application that pinned an older or newer payload
        // than this module's POM names is the way they drift, and a wrong number here is a
        // sentence at load time instead of an UnsatisfiedLinkError on the first call that differs.
        int abi;
        try {
            abi = FfmpegNative.abi();
        } catch (UnsatisfiedLinkError error) {
            return "the FFmpeg native loaded but predates the ABI handshake (no abi entry point): "
                    + "it is an older " + PAYLOAD + " than this limn-video-ffmpeg expects";
        }
        if (abi != EXPECTED_ABI) {
            return "the FFmpeg native speaks shim ABI " + abi + " and this limn-video-ffmpeg expects "
                    + EXPECTED_ABI + ": align the " + PAYLOAD + " version with the one this module's"
                    + " POM names";
        }
        try {
            String identity = FfmpegNative.identity();
            if (identity == null || identity.isBlank()) {
                return "the FFmpeg native loaded but reports no identity";
            }
            return null;
        } catch (UnsatisfiedLinkError error) {
            return "the FFmpeg native loaded but is missing an entry point: " + error.getMessage();
        }
    }

    private static String describeLinkFailure(String what, String platform,
                                              UnsatisfiedLinkError error) {
        return classifyLinkFailure(what, platform, error.getMessage());
    }

    /**
     * Turns the loader's own words into an answer.
     *
     * <p>Every operating system words a wrong-architecture load differently and not one of them
     * says "wrong architecture" plainly: macOS talks about a Mach-O with an incompatible
     * architecture, Linux about a wrong ELF class, Windows about an invalid Win32 application. A
     * reader who has just built for the wrong processor is looking at whichever of those their
     * machine produced and has no reason to connect it to the JDK they are running, so the phrases
     * are recognised here and the answer is spelled out. Getting this wrong costs somebody an
     * afternoon, which is why it is a function with a test rather than a string concatenation.
     */
    static String classifyLinkFailure(String what, String platform, String message) {
        String detail = message == null ? "" : message;
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("mach-o") || lower.contains("wrong architecture")
                || lower.contains("incompatible architecture") || lower.contains("elfclass")
                || lower.contains("not a valid win32") || lower.contains("%1 is not a valid")) {
            return "the FFmpeg native beside this build is for another processor than the JVM's ("
                    + platform + "): " + detail;
        }
        return "cannot load " + what + " for " + platform + ": " + detail;
    }

    // ------------------------------------------------------------------ extraction

    /** How the libraries are copied out of the jar; the rules are the toolkit's, shared with every native payload. */
    private static final NativeExtraction EXTRACTION = new NativeExtraction("limn-ffmpeg", CACHE_PROPERTY);

    private static Path extract(String root, List<String> names, String manifestText)
            throws IOException {
        return EXTRACTION.extract(FfmpegLibrary.class.getClassLoader(), root, names, manifestText);
    }

    /** This user's own directory under the cache root; package-private for the test. */
    static Path privateBase() throws IOException {
        return EXTRACTION.privateBase();
    }

    /** See {@link NativeExtraction#moveIntoPlace}; package-private for the test. */
    static void moveIntoPlace(Path staging, Path target, String marker) throws IOException {
        NativeExtraction.moveIntoPlace(staging, target, marker);
    }

    /** See {@link NativeExtraction#requirePrivateDirectory}; package-private for the test. */
    static void requirePrivateDirectory(Path directory, UserPrincipal user) throws IOException {
        NativeExtraction.requirePrivateDirectory(directory, user);
    }

    /** See {@link NativeExtraction#ownerTag}; package-private for the test. */
    static String ownerTag(UserPrincipal owner) throws IOException {
        return NativeExtraction.ownerTag(owner);
    }

    // ------------------------------------------------------------------ resources

    private static InputStream resource(String path) {
        return FfmpegLibrary.class.getClassLoader().getResourceAsStream(path);
    }

    private static String readResource(String path) {
        return Resources.textIfPresent(FfmpegLibrary.class.getClassLoader(), path, "native manifest");
    }

    static List<String> readManifest(String text) {
        List<String> names = new ArrayList<>();
        for (String line : text.split("\n")) {
            String name = line.strip();
            // A manifest is generated, but it is also the one file a hand-laid-out directory has
            // to contain, so a path separator in it is refused rather than resolved: it would
            // reach outside the directory being extracted to.
            if (!name.isEmpty() && !name.contains("/") && !name.contains("\\")
                    && !name.equals("..")) {
                names.add(name);
            }
        }
        return names;
    }
}
