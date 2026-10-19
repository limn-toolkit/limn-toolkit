package limn.backend.lwjgl.floors;

import limn.backend.lwjgl.floors.OsFloors.Target;
import limn.backend.lwjgl.floors.OsFloors.Version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads, from a native library's own headers, which platform it is for and the oldest system it
 * says it runs on. Plain Java over the bytes, so the check needs no tool of any platform's:
 *
 * <ul>
 *   <li><b>Mach-O</b>: the CPU type, and the minimum macOS of {@code LC_BUILD_VERSION} or, in an
 *       older build, {@code LC_VERSION_MIN_MACOSX}. A universal ("fat") file is read slice by
 *       slice, each a binary of its own.</li>
 *   <li><b>ELF</b>: the machine, and the newest {@code GLIBC_x.y} among the versions the library
 *       needs, read from its {@code .gnu.version_r} section. A library that needs no versioned
 *       glibc symbol records no requirement.</li>
 *   <li><b>PE</b>: the machine, and the newer of the optional header's operating system and
 *       subsystem versions; the subsystem version is the one the loader refuses on.</li>
 * </ul>
 */
public final class NativeHeaders {

    private static final int MH_MAGIC = 0xFEEDFACE;
    private static final int MH_MAGIC_64 = 0xFEEDFACF;
    private static final int FAT_MAGIC = 0xCAFEBABE;
    private static final int FAT_MAGIC_64 = 0xCAFEBABF;
    private static final int CPU_TYPE_X86_64 = 0x01000007;
    private static final int CPU_TYPE_ARM64 = 0x0100000C;
    private static final int LC_VERSION_MIN_MACOSX = 0x24;
    private static final int LC_BUILD_VERSION = 0x32;
    private static final int PLATFORM_MACOS = 1;
    /**
     * A Java class file starts with the same four bytes as a universal binary, followed by its
     * version numbers where the binary has its slice count; the first class-file major version is
     * 45, and no universal binary has that many slices.
     */
    private static final int MAX_FAT_SLICES = 44;

    private static final int EM_X86_64 = 62;
    private static final int EM_AARCH64 = 183;
    private static final int SHT_GNU_VERNEED = 0x6FFFFFFE;
    private static final Pattern GLIBC = Pattern.compile("GLIBC_(\\d+(?:\\.\\d+){1,2})");

    private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    private static final int IMAGE_FILE_MACHINE_ARM64 = 0xAA64;

    private NativeHeaders() {
    }

    /**
     * One native library, or one slice of a universal one.
     *
     * @param name     where it was found, for a message
     * @param target   the platform it is for
     * @param requires the oldest system it says it runs on, or null when it records none
     */
    public record Binary(String name, Target target, Version requires) {
    }

    /**
     * A binary found in a jar.
     *
     * @param jar    the jar's file name, which names the artifact and its version
     * @param binary what was read from it
     */
    public record Found(String jar, Binary binary) {
    }

    /**
     * Every native library in a jar, whatever its name; class files are passed over.
     *
     * @param jar the jar
     * @return what each native says, in entry order
     * @throws IOException if the jar cannot be read
     * @throws IllegalArgumentException if a native is for a platform no floor names, or is
     *                                  malformed
     */
    public static List<Found> readJar(Path jar) throws IOException {
        List<Found> found = new ArrayList<>();
        String jarName = jar.getFileName().toString();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] head;
                try (InputStream in = zip.getInputStream(entry)) {
                    head = in.readNBytes(8);
                }
                if (!isNative(head)) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                for (Binary binary : read(jarName + "!" + entry.getName(), bytes)) {
                    found.add(new Found(jarName, binary));
                }
            }
        }
        return found;
    }

    /**
     * @param head the first bytes of a file; eight are enough
     * @return whether they open a Mach-O, ELF or PE file
     */
    public static boolean isNative(byte[] head) {
        if (head.length < 4) {
            return false;
        }
        int big = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        int little = Integer.reverseBytes(big);
        if (little == MH_MAGIC || little == MH_MAGIC_64 || big == MH_MAGIC || big == MH_MAGIC_64) {
            return true;
        }
        if ((big == FAT_MAGIC || big == FAT_MAGIC_64) && head.length >= 8) {
            int slices = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            return slices > 0 && slices <= MAX_FAT_SLICES;
        }
        if (head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
            return true;
        }
        return head[0] == 'M' && head[1] == 'Z';
    }

    /**
     * @param name  where the bytes came from, for messages
     * @param bytes a whole native library
     * @return one binary, or one per slice of a universal file
     * @throws IllegalArgumentException if the bytes are not a library this check can read
     */
    public static List<Binary> read(String name, byte[] bytes) {
        if (!isNative(bytes)) {
            throw new IllegalArgumentException(name + ": not a Mach-O, ELF or PE file");
        }
        if (bytes[0] == 0x7F) {
            return List.of(elf(name, bytes));
        }
        if (bytes[0] == 'M') {
            return List.of(pe(name, bytes));
        }
        int big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(0);
        if (big == FAT_MAGIC || big == FAT_MAGIC_64) {
            return fat(name, bytes, big == FAT_MAGIC_64);
        }
        return List.of(machO(name, bytes, 0, bytes.length));
    }

    // ------------------------------------------------------------------------------------ Mach-O

    private static List<Binary> fat(String name, byte[] bytes, boolean wide) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int slices = b.getInt(4);
        List<Binary> binaries = new ArrayList<>();
        for (int i = 0; i < slices; i++) {
            long offset;
            long size;
            if (wide) {
                int at = 8 + i * 32;
                offset = b.getLong(at + 8);
                size = b.getLong(at + 16);
            } else {
                int at = 8 + i * 20;
                offset = Integer.toUnsignedLong(b.getInt(at + 8));
                size = Integer.toUnsignedLong(b.getInt(at + 12));
            }
            if (offset < 0 || size < 0 || offset + size > bytes.length) {
                throw new IllegalArgumentException(name + ": slice " + i + " lies outside the file");
            }
            binaries.add(machO(name + "[slice " + i + "]", bytes, (int) offset, (int) size));
        }
        return binaries;
    }

    private static Binary machO(String name, byte[] bytes, int start, int length) {
        ByteBuffer b = ByteBuffer.wrap(bytes, start, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt(0);
        if (magic != MH_MAGIC && magic != MH_MAGIC_64) {
            b.order(ByteOrder.BIG_ENDIAN);
            magic = b.getInt(0);
            if (magic != MH_MAGIC && magic != MH_MAGIC_64) {
                throw new IllegalArgumentException(name + ": not a Mach-O slice");
            }
        }
        int cpu = b.getInt(4);
        Target target = switch (cpu) {
            case CPU_TYPE_X86_64 -> Target.MACOS_X86_64;
            case CPU_TYPE_ARM64 -> Target.MACOS_AARCH64;
            default -> throw new IllegalArgumentException(name + ": a Mach-O for CPU type 0x"
                    + Integer.toHexString(cpu) + ", which no floor names");
        };
        int commands = b.getInt(16);
        int at = magic == MH_MAGIC_64 ? 32 : 28;
        Version minimum = null;
        for (int i = 0; i < commands; i++) {
            int cmd = b.getInt(at);
            int size = b.getInt(at + 4);
            if (size < 8 || at + size > length) {
                throw new IllegalArgumentException(name + ": load command " + i + " is malformed");
            }
            if (cmd == LC_BUILD_VERSION) {
                int platform = b.getInt(at + 8);
                if (platform != PLATFORM_MACOS) {
                    throw new IllegalArgumentException(name + ": built for platform " + platform
                            + ", not macOS");
                }
                minimum = nibbles(b.getInt(at + 12));
            } else if (cmd == LC_VERSION_MIN_MACOSX) {
                minimum = nibbles(b.getInt(at + 8));
            }
            at += size;
        }
        if (minimum == null) {
            throw new IllegalArgumentException(name + ": records no minimum macOS");
        }
        return new Binary(name, target, minimum);
    }

    /** Mach-O's xxxx.yy.zz encoding: the major in the high half, then a byte each. */
    private static Version nibbles(int encoded) {
        return new Version(encoded >>> 16, (encoded >>> 8) & 0xFF, encoded & 0xFF);
    }

    // --------------------------------------------------------------------------------------- ELF

    private static Binary elf(String name, byte[] bytes) {
        boolean wide = bytes[4] == 2;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(bytes[5] == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int machine = Short.toUnsignedInt(b.getShort(18));
        Target target = switch (machine) {
            case EM_X86_64 -> Target.LINUX_X86_64;
            case EM_AARCH64 -> Target.LINUX_AARCH64;
            default -> throw new IllegalArgumentException(name + ": an ELF for machine " + machine
                    + ", which no floor names");
        };
        long sectionTable = wide ? b.getLong(0x28) : Integer.toUnsignedLong(b.getInt(0x20));
        int entrySize = Short.toUnsignedInt(b.getShort(wide ? 0x3A : 0x2E));
        int sections = Short.toUnsignedInt(b.getShort(wide ? 0x3C : 0x30));
        if (sectionTable == 0 || sections == 0) {
            throw new IllegalArgumentException(name + ": has no section headers to read the glibc versions from");
        }
        Version newest = null;
        for (int i = 0; i < sections; i++) {
            int header = (int) (sectionTable + (long) i * entrySize);
            if (b.getInt(header + 4) != SHT_GNU_VERNEED) {
                continue;
            }
            long offset = wide ? b.getLong(header + 0x18) : Integer.toUnsignedLong(b.getInt(header + 0x10));
            int link = b.getInt(header + (wide ? 0x28 : 0x18));
            int count = b.getInt(header + (wide ? 0x2C : 0x1C));
            int strings = (int) (sectionTable + (long) link * entrySize);
            long stringsAt = wide ? b.getLong(strings + 0x18) : Integer.toUnsignedLong(b.getInt(strings + 0x10));
            int need = (int) offset;
            for (int n = 0; n < count; n++) {
                int auxCount = Short.toUnsignedInt(b.getShort(need + 2));
                int aux = need + b.getInt(need + 8);
                for (int a = 0; a < auxCount; a++) {
                    String version = string(bytes, (int) stringsAt + b.getInt(aux + 8));
                    Matcher glibc = GLIBC.matcher(version);
                    if (glibc.matches()) {
                        Version v = Version.parse(glibc.group(1));
                        if (newest == null || v.isAfter(newest)) {
                            newest = v;
                        }
                    }
                    aux += b.getInt(aux + 12);
                }
                need += b.getInt(need + 12);
            }
        }
        return new Binary(name, target, newest);
    }

    private static String string(byte[] bytes, int at) {
        int end = at;
        while (bytes[end] != 0) {
            end++;
        }
        return new String(bytes, at, end - at, StandardCharsets.US_ASCII);
    }

    // ---------------------------------------------------------------------------------------- PE

    private static Binary pe(String name, byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int pe = b.getInt(0x3C);
        if (pe < 0 || pe + 24 + 52 > bytes.length || b.getInt(pe) != 0x00004550) {
            throw new IllegalArgumentException(name + ": an MZ file with no PE header");
        }
        int machine = Short.toUnsignedInt(b.getShort(pe + 4));
        Target target = switch (machine) {
            case IMAGE_FILE_MACHINE_AMD64 -> Target.WINDOWS_X86_64;
            case IMAGE_FILE_MACHINE_ARM64 -> Target.WINDOWS_AARCH64;
            default -> throw new IllegalArgumentException(name + ": a PE for machine 0x"
                    + Integer.toHexString(machine) + ", which no floor names");
        };
        int optional = pe + 24;
        // The same offsets in PE32 and PE32+: the wider image base takes the place of BaseOfData.
        Version system = new Version(Short.toUnsignedInt(b.getShort(optional + 40)),
                Short.toUnsignedInt(b.getShort(optional + 42)), 0);
        Version subsystem = new Version(Short.toUnsignedInt(b.getShort(optional + 48)),
                Short.toUnsignedInt(b.getShort(optional + 50)), 0);
        return new Binary(name, target, subsystem.isAfter(system) ? subsystem : system);
    }
}
