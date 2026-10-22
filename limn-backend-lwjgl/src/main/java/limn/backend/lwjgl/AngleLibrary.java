package limn.backend.lwjgl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import limn.backend.Platform;
import limn.io.internal.NativeExtraction;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Configuration;

/**
 * Finds and loads ANGLE, the OpenGL ES 3.0 implementation of the {@code limn-angle-natives}
 * payload, which a window falls back to when the machine's own OpenGL gives it no context
 * ({@link AngleFallback}): Direct3D 11 underneath on Windows, Metal on macOS. Never on Linux,
 * where Mesa is the answer.
 *
 * <p>Two places are looked in, in order, as for the SVG renderer ({@link LunaSvgLibrary}): a
 * directory named by {@value #LIBRARY_PROPERTY}, for a packaged application that lays its natives
 * out itself or a developer trying another build, and otherwise the class path, where the
 * platform's jar of the payload carries {@code limn/angle/native/<os>-<arch>/}, extracted as every
 * native payload of the toolkit is ({@link NativeExtraction}).
 *
 * <p>Loading happens only on the way to a fallback, never at startup: a process whose machine has
 * a driver never maps a byte of ANGLE. Both libraries are loaded by full path, {@code libGLESv2}
 * first, and what that buys differs by platform:
 * <ul>
 *   <li>The windowing library opens {@code libEGL} by its bare name when the first EGL window is
 *       made. Windows answers a bare name with a module already loaded under it, which the Phase 0b
 *       probe relied on; macOS does so for an image whose install name is that bare name, which is
 *       why the payload's macOS libraries are built with {@code libEGL.dylib} and
 *       {@code libGLESv2.dylib} as theirs (measured on 2026-10-22: with the install name
 *       {@code ./libEGL.dylib} the lookup failed, with {@code libEGL.dylib} it found the loaded
 *       image). LWJGL 3.4.3's own way to hand the windowing library a path,
 *       {@code GLFWNativeEGL.setEGLPath}, crashes it on macOS before any EGL call: it reads past the
 *       end of the one-entry name list there to decide a prefix.</li>
 *   <li>Windows' {@code libEGL.dll} imports {@code libGLESv2.dll} by name, which the earlier load
 *       satisfies whatever the search path.</li>
 *   <li>LWJGL's own EGL and OpenGL ES bindings are pointed at the same files by full path, so that
 *       they bind the libraries the context came from and not another copy.</li>
 * </ul>
 *
 * <p>Attempted once; never throws. A failure is a sentence the window's error then carries.
 */
final class AngleLibrary {

    /** A directory holding the two libraries, to be loaded from where they lie. */
    static final String LIBRARY_PROPERTY = "limn.angle.dir";

    /** Where to extract to, when the default temporary directory cannot be written to. */
    static final String CACHE_PROPERTY = "limn.angle.cache";

    private static final String RESOURCE_ROOT = "limn/angle/native/";
    private static final String PAYLOAD = "io.github.limn-toolkit:limn-angle-natives";
    private static final NativeExtraction EXTRACTION = new NativeExtraction("limn-angle", CACHE_PROPERTY);

    private static final Object LOCK = new Object();
    private static boolean attempted;
    private static boolean loaded;
    private static @Nullable String failure;

    private AngleLibrary() {
    }

    /** Whether this platform has an ANGLE fallback at all: Windows and macOS. */
    static boolean platformHasFallback() {
        Platform.Os os = Platform.current().os();
        return os == Platform.Os.WINDOWS || os == Platform.Os.MACOS;
    }

    /** @return whether both libraries are loaded; loads them on the first call; never throws */
    static boolean load() {
        synchronized (LOCK) {
            if (!attempted) {
                attempted = true;
                try {
                    failure = platformHasFallback() ? attemptLoad()
                            : "ANGLE is not a fallback on " + Platform.current().os();
                } catch (Throwable error) {
                    // Throwable on purpose: an UnsatisfiedLinkError is an Error, and the window that
                    // asked has an error of its own to report with this one in it.
                    failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                }
                loaded = failure == null;
            }
            return loaded;
        }
    }

    /** @return why {@link #load()} is false, in one sentence, or null when it loaded */
    static @Nullable String failure() {
        synchronized (LOCK) {
            return attempted ? failure : "not attempted";
        }
    }

    /** @return the two file names on this platform, {@code libGLESv2} first: the order they load in */
    static List<String> fileNames() {
        return Platform.current().os() == Platform.Os.WINDOWS
                ? List.of("libGLESv2.dll", "libEGL.dll")
                : List.of("libGLESv2.dylib", "libEGL.dylib");
    }

    private static @Nullable String attemptLoad() {
        String platform = LunaSvgLibrary.platform();
        List<String> names = fileNames();
        Path directory;
        String property = System.getProperty(LIBRARY_PROPERTY);
        if (property != null && !property.isBlank()) {
            directory = Path.of(property);
            for (String name : names) {
                if (!Files.isRegularFile(directory.resolve(name))) {
                    return "no " + name + " under " + property + " (" + LIBRARY_PROPERTY + ")";
                }
            }
        } else {
            ClassLoader loader = AngleLibrary.class.getClassLoader();
            String root = RESOURCE_ROOT + platform + "/";
            if (loader.getResource(root + names.get(0)) == null) {
                return "this build carries no ANGLE for " + platform + ": add the " + PAYLOAD
                        + " classifier natives-" + platform + ", or set " + LIBRARY_PROPERTY;
            }
            try {
                directory = EXTRACTION.extract(loader, root, names, platform);
            } catch (IOException error) {
                return "cannot extract ANGLE for " + platform + ": " + error
                        + " (set " + CACHE_PROPERTY + " to a writable directory)";
            }
        }
        Path gles = directory.resolve(names.get(0)).toAbsolutePath();
        Path egl = directory.resolve(names.get(1)).toAbsolutePath();
        for (Path library : List.of(gles, egl)) {
            try {
                System.load(library.toString());
            } catch (UnsatisfiedLinkError error) {
                String detail = String.valueOf(error.getMessage());
                String lower = detail.toLowerCase(Locale.ROOT);
                if (lower.contains("mach-o") || lower.contains("incompatible architecture")
                        || lower.contains("not a valid win32")) {
                    return "the ANGLE found is for another processor than the JVM's (" + platform
                            + "): " + detail;
                }
                return "cannot load " + library + " for " + platform + ": " + detail;
            }
        }
        Configuration.EGL_LIBRARY_NAME.set(egl.toString());
        Configuration.OPENGLES_LIBRARY_NAME.set(gles.toString());
        return null;
    }
}
