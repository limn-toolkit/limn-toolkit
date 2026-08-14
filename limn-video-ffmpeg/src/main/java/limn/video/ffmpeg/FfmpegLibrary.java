package limn.video.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

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
    public static String failure() {
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
        return platformFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * The directory name for a given {@code os.name} and {@code os.arch}.
     *
     * <p>Note which architecture is being asked about: <b>the JVM's, never the machine's.</b> An
     * x86_64 JVM under Rosetta on an Apple Silicon Mac reports {@code os.arch=x86_64} and can load
     * only an x86_64 library; asking the machine would find arm64, hand the JVM a library it
     * cannot map, and produce an UnsatisfiedLinkError that names no cause. The same holds for a
     * 32-bit JVM on a 64-bit Windows install.
     */
    static String platformFor(String osName, String osArch) {
        String name = osName.toLowerCase(java.util.Locale.ROOT);
        String os = name.contains("mac") || name.contains("darwin") ? "macos"
                : name.contains("win") ? "windows"
                : "linux";
        String machine = osArch.toLowerCase(java.util.Locale.ROOT);
        String arch = switch (machine) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> machine;
        };
        return os + "-" + arch;
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
            return "this build carries no FFmpeg native for " + platform
                    + " (run scripts/build-ffmpeg.sh, or set " + LIBRARY_PROPERTY + ")";
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

    private static Path extract(String root, List<String> names, String manifestText)
            throws IOException {
        String digest = digestOf(root, names, manifestText);
        Path base = privateBase();
        Path target = base.resolve(digest);
        if (Files.isDirectory(target) && Files.isRegularFile(target.resolve(names.get(0)))) {
            return target;
        }

        Path staging = Files.createTempDirectory(base, "staging-");
        try {
            for (String name : names) {
                try (InputStream in = resource(root + name)) {
                    if (in == null) {
                        throw new IOException("the manifest names " + name + ", which is not in "
                                + "this build");
                    }
                    Files.copy(in, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
                // Only the shim is ever dlopen'd by name on a platform that checks the bit, but
                // the whole set is made executable: a dependency without it fails to map on some
                // hardened Linux configurations, and the failure names the shim rather than the
                // file that actually lacked the bit.
                staging.resolve(name).toFile().setExecutable(true, true);
            }
            moveIntoPlace(staging, target, names.get(0));
            return target;
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Renames the staged directory into place, treating "somebody got there first" as success.
     *
     * <p>Two of this user's applications extracting at once is ordinary, and the loser of the
     * rename uses the winner's copy: the digest is taken over the content, so both directories
     * hold the same bytes. What decides that a failed move was that race is <b>the directory being
     * there and complete afterwards</b>, not the class of the exception. Every plausible spelling
     * turned up in practice: a rename onto an existing non-empty directory is {@code ENOTEMPTY} on
     * Unix, which the JDK does not map to {@link DirectoryNotEmptyException}; it arrives as a bare
     * {@link java.nio.file.FileSystemException} whose only distinguishing mark is a message no code
     * should read. Anything else is rethrown, so a full disk still reports a full disk.
     *
     * <p>The nesting is the other half. The fallback for a file system that cannot rename
     * atomically is a second move, and it loses the same race; one catch around both is what stops
     * it from reporting a missing native while a complete extraction sits at {@code target}.
     *
     * <p>Package-private for the test, which cannot ask for a file system without atomic rename.
     *
     * @param marker a file the winner's directory must hold for it to count as a complete
     *               extraction, the same one the reuse path looks for
     */
    static void moveIntoPlace(Path staging, Path target, String marker) throws IOException {
        try {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(staging, target);
            }
        } catch (IOException collided) {
            if (!(Files.isDirectory(target) && Files.isRegularFile(target.resolve(marker)))) {
                throw collided;
            }
            // The winner's directory stays; this one's staging goes in the caller's finally.
        }
    }

    private static Path cacheRoot() {
        String configured = System.getProperty(CACHE_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }

    /**
     * The directory the digest-named ones are extracted into: one of this user's own, under the
     * cache root, created owner-only and verified before anything is loaded out of it.
     *
     * <p>Nothing is written to the cache root itself. It is shared ({@code /tmp} on most Unix
     * systems), and the digest is taken over bytes that ship in the application, so another
     * account can compute the extraction path offline, create it first and have its own libraries
     * loaded into this process. The private directory is what makes the rest of extraction, and
     * the race between two extractions, a question about this user's own processes only.
     *
     * <p>Package-private for the test, which asserts that the path extraction uses is not a child
     * of the shared root: a native this build may not carry cannot be loaded to prove it.
     */
    static Path privateBase() throws IOException {
        Path root = cacheRoot();
        Files.createDirectories(root);
        UserPrincipal user = self(root);
        Path base = root.resolve("limn-ffmpeg-" + ownerTag(user));
        try {
            Files.createDirectory(base, ownerOnly(root));
        } catch (FileAlreadyExistsException existing) {
            // This user's own directory from an earlier run, ordinarily. Or somebody else's,
            // which is what the check below is for.
        }
        requirePrivateDirectory(base, user);
        return base;
    }

    /**
     * Refuses a directory another account could have planted, or could still write into.
     *
     * <p>Package-private for the test: this check is the whole defence, so it is asserted on its
     * own rather than through a load.
     *
     * @throws IOException naming what is wrong with it, so {@link #failure()} can say
     */
    static void requirePrivateDirectory(Path directory, UserPrincipal user) throws IOException {
        // NOFOLLOW on every question: a symbolic link left at this path is the attack, and asking
        // about its target instead would answer about a directory that is indeed fine.
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(directory + " is not a directory");
        }
        if (!Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(user)) {
            throw new IOException(directory + " belongs to another account");
        }
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException(directory + " is writable by other accounts");
            }
        }
    }

    /**
     * The owner of a file this process has just made: this user, however the platform spells it.
     * Looking {@code user.name} up in the file system's principal service instead would have to
     * guess at Windows' domain-qualified form and at what an identity-mapped mount reports, and
     * a wrong guess refuses a directory that is perfectly this user's own.
     */
    private static UserPrincipal self(Path writable) throws IOException {
        Path probe = Files.createTempFile(writable, "limn-ffmpeg-owner-", "");
        try {
            return Files.getOwner(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /**
     * A directory name for the principal that is about to own it, ASCII and short whatever the
     * account is called.
     *
     * <p><b>Taken from the owner and not from {@code user.name}</b>, because the owner is what
     * {@link #requirePrivateDirectory} compares against and the two must not be able to disagree
     * about who this is. On Windows they can: a process holding an elevated token creates its
     * objects owned by the Administrators group rather than by the person, so one run of an
     * application and a differently-elevated run of the same application by the same user arrive
     * with two different owners. Naming the directory after the owner gives each of them one of
     * its own, which is what lets the check stay strict; the mismatch stops arising instead of
     * having to be tolerated. {@code user.name} could not do that: it is a system property, it is
     * identical across both runs, and it is settable on the command line.
     *
     * <p>The readable half is a convenience and the digest is the identity, so two principals
     * whose names sanitise alike still get separate directories rather than queueing behind one.
     * The ownership check, never the name, remains what makes either of them safe.
     *
     * <p>A principal name is localized on Windows ({@code BUILTIN\Administrators} is
     * {@code BUILTIN\Administradores} on a Portuguese install), and that costs nothing here,
     * because the directory only has to be stable on this machine. Changing the system language
     * orphans the previous one, which spends disk and not correctness.
     *
     * <p>Package-private for the test: that two principals never share a directory is the whole
     * reason this is not {@code user.name}, so it is asserted directly rather than through a load
     * that only one of the two owners can reach.
     */
    static String ownerTag(UserPrincipal owner) throws IOException {
        String name = owner.getName();
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i < name.length() && tag.length() < 24; i++) {
            char c = name.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            tag.append(plain ? c : '_');
        }
        if (tag.isEmpty()) {
            tag.append("user");
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("no SHA-256", impossible);
        }
        return tag + "-"
                + HexFormat.of().formatHex(sha.digest(name.getBytes(StandardCharsets.UTF_8)), 0, 6);
    }

    /** {@code rwx------} where the file system understands it, nothing where it does not. */
    private static FileAttribute<?>[] ownerOnly(Path where) {
        if (!where.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Windows: the temporary directory is already per-user there, and an ACL set here
            // would be a second mechanism to get wrong for no ground it does not already cover.
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        };
    }

    /**
     * A digest of every library's bytes, so the directory name changes whenever the content does.
     * Naming the directory after a version instead would leave an application that was rebuilt
     * without a version bump loading the previous library for as long as the temporary directory
     * survived, which is exactly the bug that is hardest to believe while looking at it.
     */
    private static String digestOf(String root, List<String> names, String manifestText)
            throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("no SHA-256", impossible);
        }
        sha.update(manifestText.getBytes(StandardCharsets.UTF_8));
        byte[] chunk = new byte[64 * 1024];
        for (String name : names) {
            try (InputStream in = resource(root + name)) {
                if (in == null) {
                    throw new IOException("the manifest names " + name + ", which is not in this "
                            + "build");
                }
                int read;
                while ((read = in.read(chunk)) > 0) {
                    sha.update(chunk, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(sha.digest(), 0, 10);
    }

    private static void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover staging directory in the temporary directory is litter, not a
                    // failure, and reporting it would replace a working load with an error.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    // ------------------------------------------------------------------ resources

    private static InputStream resource(String path) {
        return FfmpegLibrary.class.getClassLoader().getResourceAsStream(path);
    }

    private static String readResource(String path) {
        try (InputStream in = resource(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
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
