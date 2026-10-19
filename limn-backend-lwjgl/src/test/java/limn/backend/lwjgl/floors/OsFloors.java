package limn.backend.lwjgl.floors;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The oldest operating system Limn runs on, per platform: the one place these floors are written.
 * The install guide's "Supported systems" table cites this file, and {@link OsFloorsTest} reads
 * every native library the build resolves and fails when one of them asks for a newer system.
 *
 * <p>A floor here is what the shipped binaries record about themselves, and nothing else: the
 * minimum macOS a Mach-O library declares, the newest glibc symbol version an ELF library needs,
 * and the subsystem version a Windows DLL asks the loader for. On Windows the JDK sets the real
 * floor higher than the DLLs do (JDK 17 supports Windows 10 and later, and the DLLs would accept
 * Vista), so the Windows rows are a sanity bound rather than the number a reader is told. Read on
 * 2026-10-19 from LWJGL 3.4.3, limn-lunasvg-natives 3.5.0.0 and limn-ffmpeg-natives 9.0.2.0:
 *
 * <ul>
 *   <li>macOS 11.0 on both architectures: LWJGL's arm64 libraries and lunasvg's on both say 11.0;
 *       LWJGL's x86_64 libraries say 10.11, its OpenAL 10.15.</li>
 *   <li>glibc 2.28 on x86_64: LWJGL's core and OpenAL, lunasvg and FFmpeg's libavutil.</li>
 *   <li>glibc 2.35 on aarch64: LWJGL's HarfBuzz; its core, GLFW and OpenAL need 2.34.</li>
 *   <li>Windows: 6.0 on x64, 6.2 on ARM64 (LWJGL's arm64 DLLs), both under the 10.0 bound.</li>
 * </ul>
 */
public final class OsFloors {

    private OsFloors() {
    }

    /** A platform, the version its binaries are read for, and the newest one allowed. */
    public enum Target {
        /** Intel Macs. */
        MACOS_X86_64("macOS", "x86_64", "minimum macOS", Version.parse("11.0"), "macOS 11"),
        /** Apple silicon. */
        MACOS_AARCH64("macOS", "aarch64", "minimum macOS", Version.parse("11.0"), "macOS 11"),
        /** Linux on Intel and AMD. */
        LINUX_X86_64("Linux", "x86_64", "glibc", Version.parse("2.28"), "glibc 2.28"),
        /** Linux on ARM. */
        LINUX_AARCH64("Linux", "aarch64", "glibc", Version.parse("2.35"), "glibc 2.35"),
        /** Windows on Intel and AMD; the JDK, not the DLLs, puts the floor at Windows 10. */
        WINDOWS_X86_64("Windows", "x86_64", "Windows subsystem", Version.parse("10.0"), "Windows 10"),
        /** Windows on ARM; the DLLs say Windows 8 (6.2), and ARM64 JDKs exist only for 10 and 11. */
        WINDOWS_AARCH64("Windows", "aarch64", "Windows subsystem", Version.parse("10.0"), "Windows 10"),
        ;

        private final String system;
        private final String arch;
        private final String measure;
        private final Version floor;
        private final String documented;

        Target(String system, String arch, String measure, Version floor, String documented) {
            this.system = system;
            this.arch = arch;
            this.measure = measure;
            this.floor = floor;
            this.documented = documented;
        }

        /** @return the newest version a native for this platform may ask for */
        public Version floor() {
            return floor;
        }

        /** @return what the version is a version of, for a message */
        public String measure() {
            return measure;
        }

        /** @return how the install guide's table names this floor, which it must contain */
        public String documented() {
            return documented;
        }

        @Override
        public String toString() {
            return system + " " + arch;
        }
    }

    /**
     * A payload that ships over a floor, known and excused for one exact version only. Moving the
     * version pin away from it leaves the exception matching nothing, and the test then fails until
     * the exception is removed, so an excuse cannot outlive the build it was written for.
     *
     * @param artifact the artifact's name, as its jar file begins
     * @param version  the one version excused
     * @param targets  the platforms whose libraries it excuses
     * @param ceiling  the newest version excused, so a worse build is still caught
     * @param why      what is wrong and what fixes it, said when the exception goes stale
     */
    public record KnownException(String artifact, String version, Set<Target> targets, Version ceiling,
                                 String why) {

        /** @return whether the jar with this file name is this artifact at this exact version */
        public boolean covers(String jarName) {
            String prefix = artifact + "-" + version;
            return jarName.equals(prefix + ".jar")
                    || (jarName.startsWith(prefix + "-natives-") && jarName.endsWith(".jar"));
        }
    }

    /** The exceptions in force; each names the version that removes it. */
    public static final List<KnownException> KNOWN_EXCEPTIONS = List.of(
            new KnownException("limn-ffmpeg-natives", "9.0.2.0",
                    EnumSet.of(Target.MACOS_X86_64, Target.MACOS_AARCH64), Version.parse("26.0"),
                    "limn-ffmpeg-natives 9.0.2.0 was built for macOS 26.0 by mistake; 9.0.2.1 is built "
                            + "for macOS 11.0 and fixes it"));

    /** A dotted version, compared numerically: 2.2.5 is older than 2.28. */
    public record Version(int major, int minor, int patch) implements Comparable<Version> {

        /**
         * @param text one to three dot-separated numbers
         * @return that version
         */
        public static Version parse(String text) {
            String[] parts = text.split("\\.");
            if (parts.length < 1 || parts.length > 3) {
                throw new IllegalArgumentException("not a version: " + text);
            }
            return new Version(Integer.parseInt(parts[0]),
                    parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
        }

        @Override
        public int compareTo(Version other) {
            int c = Integer.compare(major, other.major);
            if (c == 0) {
                c = Integer.compare(minor, other.minor);
            }
            return c != 0 ? c : Integer.compare(patch, other.patch);
        }

        /** @return whether this version is newer than {@code other} */
        public boolean isAfter(Version other) {
            return compareTo(other) > 0;
        }

        @Override
        public String toString() {
            return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
        }
    }
}
