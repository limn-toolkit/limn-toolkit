package limn.backend.lwjgl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import limn.backend.Platform;
import limn.io.internal.NativeExtraction;
import org.jspecify.annotations.Nullable;

/**
 * Finds and loads {@code liblimnsvg}, the SVG renderer of the {@code limn-lunasvg-natives}
 * payload, and answers whether it is there.
 *
 * <p>Two places are looked in, in order. A directory named by {@value #LIBRARY_PROPERTY}, for a
 * packaged application that lays its natives out itself, or a developer trying a build of the
 * payload. Otherwise the class path: the platform's jar of the payload carries
 * {@code limn/svg/native/<os>-<arch>/liblimnsvg.<ext>}, which is extracted as every native payload
 * of the toolkit is ({@link NativeExtraction}: a directory of this user's own, named after the
 * library's digest) and loaded from there. {@value #CACHE_PROPERTY} names another place to
 * extract to than the temporary directory.
 *
 * <p>Loading is attempted once, by the first question, and never throws: a backend without it
 * starts, and its SVG icons draw nothing, saying why once in the log.
 */
final class LunaSvgLibrary {

    /** A directory holding {@code liblimnsvg}, to be loaded from where it lies. */
    static final String LIBRARY_PROPERTY = "limn.svg.library";

    /** Where to extract to, when the default temporary directory cannot be written to. */
    static final String CACHE_PROPERTY = "limn.svg.cache";

    /** The shim's ABI this backend speaks; the shim reports its own and the two must agree. */
    static final int EXPECTED_ABI = 1;

    private static final String RESOURCE_ROOT = "limn/svg/native/";
    private static final String PAYLOAD = "io.github.limn-toolkit:limn-lunasvg-natives";
    private static final NativeExtraction EXTRACTION = new NativeExtraction("limn-svg", CACHE_PROPERTY);

    private static final Object LOCK = new Object();
    private static boolean attempted;
    private static boolean loaded;
    private static @Nullable String failure;

    private LunaSvgLibrary() {
    }

    /** @return whether the library is loaded and speaks this backend's ABI; never throws */
    static boolean isAvailable() {
        synchronized (LOCK) {
            if (!attempted) {
                attempted = true;
                try {
                    failure = attemptLoad();
                } catch (Throwable error) {
                    // Throwable on purpose: an UnsatisfiedLinkError is an Error, and a backend
                    // that only asked whether SVG can be drawn must start either way.
                    failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                }
                loaded = failure == null;
            }
            return loaded;
        }
    }

    /** @return why {@link #isAvailable()} is false, in one sentence, or null when it loaded */
    static @Nullable String failure() {
        isAvailable();
        synchronized (LOCK) {
            return failure;
        }
    }

    /** @return {@code macos-aarch64} and the like: the JVM's platform, as the payload names it */
    static String platform() {
        Platform platform = Platform.current();
        String os = switch (platform.os()) {
            case MACOS -> "macos";
            case WINDOWS -> "windows";
            default -> "linux";
        };
        return os + "-" + platform.arch();
    }

    /** @return the library's file name on this platform */
    static String fileName() {
        return switch (Platform.current().os()) {
            case MACOS -> "liblimnsvg.dylib";
            case WINDOWS -> "liblimnsvg.dll";
            default -> "liblimnsvg.so";
        };
    }

    private static @Nullable String attemptLoad() {
        String platform = platform();
        String name = fileName();
        Path library;
        String directory = System.getProperty(LIBRARY_PROPERTY);
        if (directory != null && !directory.isBlank()) {
            library = Path.of(directory).resolve(name);
            if (!Files.isRegularFile(library)) {
                return "no " + name + " under " + directory + " (" + LIBRARY_PROPERTY + ")";
            }
        } else {
            ClassLoader loader = LunaSvgLibrary.class.getClassLoader();
            String root = RESOURCE_ROOT + platform + "/";
            if (loader.getResource(root + name) == null) {
                return "this build carries no SVG renderer for " + platform + ": add the " + PAYLOAD
                        + " classifier natives-" + platform + ", or set " + LIBRARY_PROPERTY;
            }
            try {
                library = EXTRACTION.extract(loader, root, List.of(name), platform).resolve(name);
            } catch (IOException error) {
                return "cannot extract the SVG renderer for " + platform + ": " + error
                        + " (set " + CACHE_PROPERTY + " to a writable directory)";
            }
        }
        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError error) {
            String detail = String.valueOf(error.getMessage());
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("mach-o") || lower.contains("incompatible architecture")
                    || lower.contains("elfclass") || lower.contains("not a valid win32")) {
                return "the SVG renderer found is for another processor than the JVM's (" + platform
                        + "): " + detail;
            }
            return "cannot load " + library + " for " + platform + ": " + detail;
        }
        return probe();
    }

    /** The handshake: a library that loaded can still be another payload's version than this backend's. */
    private static @Nullable String probe() {
        int abi;
        try {
            abi = LunaSvgNative.abi();
        } catch (UnsatisfiedLinkError error) {
            return "the SVG renderer loaded but has no ABI entry point: it is not a " + PAYLOAD + " build";
        }
        if (abi != EXPECTED_ABI) {
            return "the SVG renderer speaks shim ABI " + abi + " and this backend expects " + EXPECTED_ABI
                    + ": align the " + PAYLOAD + " version with the one this backend's POM names";
        }
        return null;
    }
}
