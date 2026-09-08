package limn.backend;

import java.util.Locale;
import java.util.Objects;

/**
 * The operating system and processor architecture <em>this JVM</em> runs on, answered once and
 * from one place.
 *
 * <p>Every module has something it does differently per platform: which modifier a menu
 * accelerator means, whether a window may carry an icon, where the system fonts live, which
 * native library directory to load from, whether the JVM has to be started on the first thread.
 * Each of those used to read {@code os.name} for itself, and spelled the test its own way —
 * {@code startsWith("Mac")} here, {@code contains("mac")} there, and a {@code contains("win")}
 * that only failed to match {@code Darwin} because the macOS test happened to come first on the
 * same line. This is the one reading, so that a platform added or a spelling corrected is a
 * change in one file.
 *
 * <p><b>It answers about the JVM, never about the machine.</b> An x86_64 JVM under Rosetta on an
 * Apple Silicon Mac reports {@code os.arch=x86_64} and can load only an x86_64 library; asking
 * the hardware would find arm64, hand the JVM a library it cannot map, and produce an
 * {@code UnsatisfiedLinkError} that names no cause. The same holds for a 32-bit JVM on a 64-bit
 * Windows install. So the architecture here is what the JVM says it is.
 *
 * <p>{@link #of(String, String)} is the reading as a pure function of the two property values,
 * so that every branch a caller takes can be tested on a machine that is not that platform.
 *
 * @param os   the operating system family
 * @param arch the processor architecture the JVM was built for, normalized: {@code x86_64},
 *             {@code aarch64}, or whatever {@code os.arch} said, lower-cased, when it is
 *             neither
 */
public record Platform(Os os, String arch) {

    /** The operating system families the toolkit tells apart. */
    public enum Os {
        /** macOS, which some JVMs still report as {@code Darwin}. */
        MACOS,
        /** Windows. */
        WINDOWS,
        /** Linux, whatever the windowing system on it: X11 and Wayland are the same answer here. */
        LINUX,
        /** Anything else: the BSDs, and whatever the JVM names that none of the above match. */
        OTHER
    }

    private static final Platform CURRENT =
            of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));

    public Platform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
    }

    /** @return the platform this JVM is running on, read once from the system properties */
    public static Platform current() {
        return CURRENT;
    }

    /**
     * The platform a given {@code os.name} and {@code os.arch} describe.
     *
     * <p>The family is matched by substring of the lower-cased name, macOS first: {@code mac} or
     * {@code darwin}, then {@code win}, then {@code linux} or {@code nux}. The order matters
     * once — {@code Darwin} contains {@code win} — and this is the only place it has to be
     * right. The architecture folds the two spellings each of the common ones has
     * ({@code amd64}/{@code x86_64}, {@code arm64}/{@code aarch64}) into one, and passes any
     * other through lower-cased rather than pretending to know it.
     *
     * @param osName what {@code System.getProperty("os.name")} answered; never null
     * @param osArch what {@code System.getProperty("os.arch")} answered; never null
     * @return the platform they describe
     */
    public static Platform of(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        Os os = name.contains("mac") || name.contains("darwin") ? Os.MACOS
                : name.contains("win") ? Os.WINDOWS
                : name.contains("linux") || name.contains("nux") ? Os.LINUX
                : Os.OTHER;
        String machine = osArch.toLowerCase(Locale.ROOT);
        String arch = switch (machine) {
            case "x86_64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> machine;
        };
        return new Platform(os, arch);
    }

    /** @return whether this is macOS */
    public boolean isMacOs() {
        return os == Os.MACOS;
    }

    /** @return whether this is Windows */
    public boolean isWindows() {
        return os == Os.WINDOWS;
    }

    /** @return whether this is Linux */
    public boolean isLinux() {
        return os == Os.LINUX;
    }
}
