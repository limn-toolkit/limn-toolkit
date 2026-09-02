package limn.video.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader's failure paths, every one of which has to end in {@code false} rather than in an
 * exception, because {@code VideoDecoder.supports} runs once per installed decoder every time
 * anything is opened, and one decoder throwing there makes the whole probe unusable for the
 * decoders behind it.
 *
 * <p>The end-to-end cases run the loader in a child class loader with its own copy of the class,
 * because the outcome is remembered per class loader: once this JVM's copy has loaded the library,
 * nothing can make it try again. Note what that means for the loader's design: the outcome being
 * remembered is what stops a machine with no native paying a failed lookup on every call.
 */
class LibraryLoadingTest {

    @TempDir
    Path directory;

    // ------------------------------------------------------------------ which build to look for

    @Test
    void theDirectoryNameFollowsTheJvmAndNotTheMachine() {
        assertEquals("macos-aarch64", FfmpegLibrary.platformFor("Mac OS X", "aarch64"));
        assertEquals("linux-x86_64", FfmpegLibrary.platformFor("Linux", "amd64"));
        assertEquals("windows-x86_64", FfmpegLibrary.platformFor("Windows 11", "amd64"));
        assertEquals("windows-aarch64", FfmpegLibrary.platformFor("Windows 11", "aarch64"));

        // The case this project actually hit, and the reason the JVM is asked rather than the
        // machine: an x86_64 JDK under Rosetta on an Apple Silicon Mac. `uname -m` says arm64 and
        // os.arch says x86_64, and the library that can be loaded is the one os.arch names.
        assertEquals("macos-x86_64", FfmpegLibrary.platformFor("Mac OS X", "x86_64"));

        // aarch64 and arm64 are one machine under two names, and Java's spelling wins because the
        // loader is what has to find the directory.
        assertEquals(FfmpegLibrary.platformFor("Linux", "aarch64"),
                FfmpegLibrary.platformFor("Linux", "arm64"));
    }

    @Test
    void platformIsAnswerableOnThisMachineWithoutTheLibrary() {
        assertDoesNotThrow(FfmpegLibrary::platform);
        assertTrue(FfmpegLibrary.platform().contains("-"));
    }

    // ------------------------------------------------------------------ wrong architecture

    @Test
    void aLibraryForAnotherProcessorIsReportedAsExactlyThat() {
        // The real words each operating system uses. Not one of them says "wrong architecture"
        // plainly, and a reader who has just built for the wrong processor has no reason to
        // connect any of them to the JDK they are running.
        String macos = FfmpegLibrary.classifyLinkFailure("liblimnffmpeg.dylib", "macos-x86_64",
                "dlopen(/tmp/x/liblimnffmpeg.dylib, 0x0001): tried: '/tmp/x/liblimnffmpeg.dylib' "
                        + "(mach-o file, but is an incompatible architecture "
                        + "(have 'arm64', need 'x86_64'))");
        assertTrue(macos.contains("another processor"), macos);
        assertTrue(macos.contains("macos-x86_64"), macos);

        String linux = FfmpegLibrary.classifyLinkFailure("liblimnffmpeg.so", "linux-x86_64",
                "/tmp/x/liblimnffmpeg.so: wrong ELF class: ELFCLASS32");
        assertTrue(linux.contains("another processor"), linux);

        String windows = FfmpegLibrary.classifyLinkFailure("limnffmpeg.dll", "windows-x86_64",
                "C:\\x\\limnffmpeg.dll: %1 is not a valid Win32 application");
        assertTrue(windows.contains("another processor"), windows);

        // Anything else is reported as itself rather than guessed at.
        String other = FfmpegLibrary.classifyLinkFailure("liblimnffmpeg.so", "linux-x86_64",
                "libavcodec.so.61: cannot open shared object file: No such file or directory");
        assertFalse(other.contains("another processor"), other);
        assertTrue(other.contains("cannot load"), other);

        // A loader that supplied no message at all must still produce a sentence.
        assertNotNull(FfmpegLibrary.classifyLinkFailure("x", "linux-x86_64", null));
    }

    // ------------------------------------------------------------------ the manifest

    @Test
    void aManifestCannotNameSomethingOutsideItsOwnDirectory() {
        // The manifest is generated, but it is also the one file a hand-laid-out directory has to
        // contain, so it is treated as input: a name with a path separator in it would be resolved
        // against the extraction directory and reach outside it.
        List<String> names = FfmpegLibrary.readManifest(String.join("\n",
                "libavutil.59.dylib",
                "../../../etc/passwd",
                "sub/dir/thing.dylib",
                "..\\windows\\system32\\evil.dll",
                "..",
                "",
                "   ",
                "liblimnffmpeg.dylib"));
        assertEquals(List.of("libavutil.59.dylib", "liblimnffmpeg.dylib"), names);
    }

    @Test
    void aManifestKeepsItsOrder() {
        // Dependencies first, the shim last. Loading the shim before libavcodec works only where
        // the platform resolves siblings on its own, which is not every platform.
        assertEquals(List.of("a", "b", "c"), FfmpegLibrary.readManifest("a\nb\nc\n"));
    }

    // ------------------------------------------------------------------ the whole loader

    @Test
    void aBuildWithNoNativeForThisPlatformReportsItAndDoesNotThrow() throws Exception {
        // A class loader over the compiled classes but NOT the payload jars: exactly what an
        // application that added no natives-<os>-<arch> classifier sees.
        try (URLClassLoader isolated = new URLClassLoader(new URL[] {classesLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> library = isolated.loadClass(FfmpegLibrary.class.getName());
            Object available = library.getMethod("isAvailable").invoke(null);
            String failure = (String) library.getMethod("failure").invoke(null);
            assertEquals(Boolean.FALSE, available);
            assertNotNull(failure, "an unavailable library must say why");
            assertTrue(failure.contains(FfmpegLibrary.platform()),
                    "the reason names the platform that was looked for: " + failure);
        }
    }

    @Test
    void aDirectoryWithNoManifestIsReportedAndDoesNotThrow() throws Exception {
        Path empty = Files.createDirectory(directory.resolve("empty"));
        String failure = loadIsolatedWith(FfmpegLibrary.LIBRARY_PROPERTY, empty.toString());
        assertNotNull(failure);
        assertTrue(failure.contains("libraries.txt"), failure);
    }

    @Test
    void aManifestNamingSomethingAbsentIsReportedAndDoesNotThrow() throws Exception {
        Path bogus = Files.createDirectory(directory.resolve("bogus"));
        Files.writeString(bogus.resolve("libraries.txt"), "libnothing.dylib\n");
        String failure = loadIsolatedWith(FfmpegLibrary.LIBRARY_PROPERTY, bogus.toString());
        assertNotNull(failure);
        assertTrue(failure.contains("libnothing.dylib"), failure);
    }

    @Test
    void afileThatIsNotALibraryIsReportedAndDoesNotThrow() throws Exception {
        // The same code path a wrong-architecture build takes: the file is there, and the
        // operating system refuses to map it.
        Path junk = Files.createDirectory(directory.resolve("junk"));
        Files.write(junk.resolve("liblimnffmpeg.dylib"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Files.writeString(junk.resolve("libraries.txt"), "liblimnffmpeg.dylib\n");
        String failure = loadIsolatedWith(FfmpegLibrary.LIBRARY_PROPERTY, junk.toString());
        assertNotNull(failure);
    }

    @Test
    void anUnwritableExtractionDirectoryIsReportedAndDoesNotThrow() throws Exception {
        Path locked = Files.createDirectory(directory.resolve("locked"));
        // An assumption and not an assertion: NTFS keeps a read-only bit on directories and
        // ignores it, so setWritable answers false there and the machine simply cannot stage the
        // condition. That is a test to skip, the way the ones needing a GPU skip, and not a
        // failure: the two lines below already say the same thing for the case where the bit is
        // set but disregarded.
        org.junit.jupiter.api.Assumptions.assumeTrue(locked.toFile().setWritable(false, false),
                "this filesystem cannot make a directory read-only");
        try {
            // If the JVM can write there anyway (running as root, or a filesystem that ignores
            // the bit), there is nothing to observe, so say so rather than pass vacuously.
            org.junit.jupiter.api.Assumptions.assumeFalse(canWriteInto(locked),
                    "this JVM can write to a directory marked read-only");
            String failure = loadIsolatedWith(FfmpegLibrary.CACHE_PROPERTY, locked.toString(),
                    classesLocation(), resourcesLocation());
            // Only meaningful where this build actually carries a native to extract; where it does
            // not, the earlier "no native for this platform" answer is the one that comes back,
            // and it is equally non-throwing.
            assertNotNull(failure);
        } finally {
            locked.toFile().setWritable(true, false);
        }
    }

    // ------------------------------------------------------------------ the extraction directory

    @Test
    void extractionHappensBelowTheSharedTemporaryDirectoryAndNotInIt() throws Exception {
        // The digest is taken over bytes that ship in the application, so the name of the
        // extraction directory is a path any other local account can work out offline. Creating it
        // straight in a world-writable /tmp is handing that account a directory whose contents this
        // process passes to System.load: the extraction has to sit one level down, in a directory
        // of this user's own.
        Path base = withCacheRoot(directory, FfmpegLibrary::privateBase);
        assertEquals(directory, base.getParent(), "the base sits under the cache root");
        assertNotEquals(directory, base, "and is never the shared cache root itself");
        if (posix()) {
            assertEquals("rwx------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(base)));
        }

        // Asking again finds the directory already there and is content with it.
        assertEquals(base, withCacheRoot(directory, FfmpegLibrary::privateBase));
    }

    @Test
    void anExtractionDirectoryAnotherAccountCanWriteIntoIsRefused() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(posix(), "no POSIX permissions here");
        Path base = withCacheRoot(directory, FfmpegLibrary::privateBase);

        // The same path, opened up: whatever it holds at load time is whatever anybody put there
        // last, which is exactly what must not be loaded from.
        // setPosixFilePermissions rather than a creation attribute: the process umask trims the
        // creation mode, and 022 is common enough that the directory would come out private and
        // the test would pass without the check ever running.
        Files.delete(base);
        Files.createDirectory(base);
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(IOException.class, () -> withCacheRoot(directory, FfmpegLibrary::privateBase));
    }

    @Test
    void twoOwnersOfOneAccountDoNotShareADirectory() throws Exception {
        // The case this exists for. On Windows a process holding an elevated token owns its
        // objects as the Administrators group and a normal one owns them as the person, so the
        // same human arrives as two principals, and the second run would find the first run's
        // directory and be refused by a check that is working perfectly.
        UserPrincipal elevated = () -> "BUILTIN\\Administrators";
        UserPrincipal person = () -> "DESKTOP-1\\someone";

        assertNotEquals(FfmpegLibrary.ownerTag(elevated), FfmpegLibrary.ownerTag(person));
        assertEquals(FfmpegLibrary.ownerTag(person), FfmpegLibrary.ownerTag(person),
                "the same principal has to find its own directory again on the next run");

        // The name becomes a path segment, so nothing in a principal may survive into it that a
        // file system would read as structure.
        for (UserPrincipal who : List.of(elevated, person)) {
            String tag = FfmpegLibrary.ownerTag(who);
            assertFalse(tag.contains("\\") || tag.contains("/"), tag);
        }

        // Two principals that sanitise to one readable name still get one directory each: the
        // digest is the identity and the readable half is a convenience.
        UserPrincipal backslash = () -> "HOST\\user";
        UserPrincipal slash = () -> "HOST/user";
        assertNotEquals(FfmpegLibrary.ownerTag(backslash), FfmpegLibrary.ownerTag(slash));
    }

    @Test
    void aSymbolicLinkAtTheComputedPathIsRefused() throws Exception {
        // Every question has to be asked about the link and not its target, because the target is
        // somebody else's directory and answers perfectly well for itself.
        //
        // Its own test because it is the one part of the defence a machine may not be able to
        // stage: creating a symbolic link is a privilege on Windows, and the JDK does not enable
        // it in the token even where the account holds it, so Files.createSymbolicLink can fail
        // where mklink succeeds. A machine that cannot make the link cannot be attacked with one
        // either. POSIX, where /tmp is shared and this attack is the real one, always runs it.
        Path mine = Files.createDirectory(directory.resolve("mine-for-link"));
        UserPrincipal me = Files.getOwner(Files.createFile(directory.resolve("probe-for-link")));
        Path link = directory.resolve("link");
        try {
            Files.createSymbolicLink(link, mine);
        } catch (IOException | UnsupportedOperationException cannot) {
            org.junit.jupiter.api.Assumptions.abort(
                    "this machine cannot create a symbolic link: " + cannot);
        }
        assertThrows(IOException.class, () -> FfmpegLibrary.requirePrivateDirectory(link, me));
    }

    @Test
    void aPlantedDirectoryIsRefusedWhateverShapeItArrivesIn() throws Exception {
        Path mine = Files.createDirectory(directory.resolve("mine"));
        UserPrincipal me = Files.getOwner(Files.createFile(directory.resolve("probe")));
        assertDoesNotThrow(() -> FfmpegLibrary.requirePrivateDirectory(mine, me));

        // A real directory, owned by somebody else. Root owns the file system root everywhere this
        // runs; when the tests themselves run as root there is no second account to stand in.
        UserPrincipal other = Files.getOwner(Path.of("/"));
        org.junit.jupiter.api.Assumptions.assumeFalse(other.equals(me), "running as the owner of /");
        assertThrows(IOException.class, () -> FfmpegLibrary.requirePrivateDirectory(mine, other));
    }

    @Test
    void losingTheExtractionRaceUsesTheWinnersCopyRatherThanFailingTheLoad() throws Exception {
        // Two of this user's applications extracting at once is ordinary, and the class promises
        // the loser uses the winner's copy. The winner is already in place, holding what this
        // process was about to write, because the directory is named after a digest of it.
        Path staging = Files.createDirectory(directory.resolve("staging"));
        Files.writeString(staging.resolve("liblimnffmpeg.dylib"), "the same bytes");
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.writeString(target.resolve("liblimnffmpeg.dylib"), "the same bytes");

        // Note which exception this does not name. A rename onto a non-empty directory is
        // ENOTEMPTY, which the JDK leaves as a bare FileSystemException on this platform, so the
        // race is recognised by the directory being there and complete, not by a class.
        assertDoesNotThrow(() -> FfmpegLibrary.moveIntoPlace(staging, target, "liblimnffmpeg.dylib"));
        assertEquals("the same bytes",
                Files.readString(target.resolve("liblimnffmpeg.dylib")),
                "the winner's copy is left alone");

        // The fallback move, for a file system that cannot rename atomically, sits inside the same
        // catch, which is the only way to cover it here, since no file system this test can make
        // refuses an atomic rename.
    }

    @Test
    void aMoveThatFailedForAnyOtherReasonIsStillReported() throws Exception {
        // Treating every failed move as a lost race would turn a full disk into a silent claim
        // that the native is ready to load.
        //
        // The failure is provoked with a target whose parent does not exist, because that is the
        // one spelling every platform agrees on. Leaving a plain file at the target (the obvious
        // choice) is not portable: a rename onto it is ENOTDIR on Unix but succeeds on Windows,
        // where ATOMIC_MOVE reaches MoveFileEx and replaces the file. Windows ends up with the
        // right directory by a different route, so there is nothing to report there and nothing
        // for this test to catch.
        Path staging = Files.createDirectory(directory.resolve("staging2"));
        Path target = directory.resolve("missing2").resolve("target2");
        assertThrows(IOException.class,
                () -> FfmpegLibrary.moveIntoPlace(staging, target, "liblimnffmpeg.dylib"));
    }

    @Test
    void theOutcomeIsRememberedRatherThanRecomputed() {
        // Not a micro-benchmark: the point is that supports() may be called on every open, so the
        // answer must be a field read after the first attempt rather than a fresh look at the
        // filesystem. Calling it many times must therefore be free of side effects and stable.
        boolean first = FfmpegLibrary.isAvailable();
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, FfmpegLibrary.isAvailable());
        }
        assertEquals(first, FfmpegLibrary.failure() == null);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean posix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix");
    }

    /** Runs {@code body} with the cache property pointing at {@code root}, and puts it back. */
    private static <T> T withCacheRoot(Path root, Callable<T> body) throws Exception {
        String previous = System.getProperty(FfmpegLibrary.CACHE_PROPERTY);
        System.setProperty(FfmpegLibrary.CACHE_PROPERTY, root.toString());
        try {
            return body.call();
        } finally {
            if (previous == null) {
                System.clearProperty(FfmpegLibrary.CACHE_PROPERTY);
            } else {
                System.setProperty(FfmpegLibrary.CACHE_PROPERTY, previous);
            }
        }
    }

    private static boolean canWriteInto(Path directory) {
        try {
            Files.delete(Files.createTempFile(directory, "probe", ""));
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    /** @return the isolated loader's {@code failure()}, having asserted it did not load or throw */
    private String loadIsolatedWith(String property, String value, URL... classpath)
            throws Exception {
        URL[] urls = classpath.length > 0 ? classpath : new URL[] {classesLocation()};
        String previous = System.getProperty(property);
        System.setProperty(property, value);
        try (URLClassLoader isolated = new URLClassLoader(urls,
                ClassLoader.getPlatformClassLoader())) {
            Class<?> library = isolated.loadClass(FfmpegLibrary.class.getName());
            Object available = library.getMethod("isAvailable").invoke(null);
            assertEquals(Boolean.FALSE, available);
            return (String) library.getMethod("failure").invoke(null);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static URL classesLocation() {
        return FfmpegLibrary.class.getProtectionDomain().getCodeSource().getLocation();
    }

    /** Where the natives would be, if this build has any. */
    private static URL resourcesLocation() throws Exception {
        URL manifest = FfmpegLibrary.class.getClassLoader()
                .getResource("limn/video/ffmpeg/native/" + FfmpegLibrary.platform()
                        + "/libraries.txt");
        if (manifest == null) {
            return classesLocation();
        }
        String text = manifest.toString();
        return java.net.URI.create(
                text.substring(0, text.length() - ("limn/video/ffmpeg/native/"
                        + FfmpegLibrary.platform() + "/libraries.txt").length())).toURL();
    }
}
