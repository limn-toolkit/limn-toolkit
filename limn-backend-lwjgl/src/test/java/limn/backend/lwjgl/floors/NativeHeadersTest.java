package limn.backend.lwjgl.floors;

import limn.backend.lwjgl.floors.NativeHeaders.Binary;
import limn.backend.lwjgl.floors.NativeHeaders.Found;
import limn.backend.lwjgl.floors.OsFloors.Target;
import limn.backend.lwjgl.floors.OsFloors.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header reader against binaries built here byte by byte, one per shape it has to read: a thin
 * Mach-O with each of its two minimum-version commands, a universal one, a 64-bit ELF with a
 * version-needs section, a PE of each machine; and the class file that opens with a universal
 * binary's magic. Each over-floor fixture is also put through {@link FloorCheck}, which is the
 * proof that a library newer than its floor fails the check.
 */
class NativeHeadersTest {

    static final int ARM64 = 0x0100000C;
    static final int X86_64 = 0x01000007;

    @Test
    void aBuildVersionCommandIsReadAsTheMinimumMacOs() {
        Binary binary = only(NativeHeaders.read("a.dylib", machO(ARM64, true, 0x000B0000)));
        assertEquals(Target.MACOS_AARCH64, binary.target());
        assertEquals(Version.parse("11.0"), binary.requires());
    }

    @Test
    void anOlderVersionMinCommandIsReadToo() {
        Binary binary = only(NativeHeaders.read("a.dylib", machO(X86_64, false, 0x000A0B00)));
        assertEquals(Target.MACOS_X86_64, binary.target());
        assertEquals(Version.parse("10.11"), binary.requires());
    }

    @Test
    void eachSliceOfAUniversalBinaryIsReadOnItsOwn() {
        byte[] fat = fat(machO(X86_64, false, 0x000A0D00), machO(ARM64, true, 0x000C0000));
        List<Binary> slices = NativeHeaders.read("u.dylib", fat);
        assertEquals(2, slices.size());
        assertEquals(Target.MACOS_X86_64, slices.get(0).target());
        assertEquals(Version.parse("10.13"), slices.get(0).requires());
        assertEquals(Target.MACOS_AARCH64, slices.get(1).target());
        assertEquals(Version.parse("12.0"), slices.get(1).requires());
        List<String> failures = check("u-1.0-natives-macos.jar", slices);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("u.dylib[slice 1] asks for minimum macOS 12.0"), failures.get(0));
    }

    @Test
    void aMacOsLibraryNewerThanElevenFailsTheCheck() {
        List<String> failures = check("x-1.0-natives-macos-aarch64.jar",
                NativeHeaders.read("x.dylib", machO(ARM64, true, 0x001A0000)));
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("26.0") && failures.get(0).contains("macOS aarch64's floor of 11.0"),
                failures.get(0));
    }

    @Test
    void aClassFileIsNotTakenForAUniversalBinary() {
        byte[] head = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
        assertFalse(NativeHeaders.isNative(head));
    }

    @Test
    void theNewestGlibcVersionNeededIsTheRequirement() {
        Binary binary = only(NativeHeaders.read("a.so", elf(183, "GLIBC_2.17", "GLIBC_2.34", "GLIBC_2.2.5")));
        assertEquals(Target.LINUX_AARCH64, binary.target());
        assertEquals(Version.parse("2.34"), binary.requires());
    }

    @Test
    void aLinuxLibraryNeedingANewerGlibcFailsTheCheck() {
        List<String> failures = check("x-1.0-natives-linux.jar",
                NativeHeaders.read("x.so", elf(62, "GLIBC_2.14", "GLIBC_2.29")));
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("glibc 2.29") && failures.get(0).contains("Linux x86_64's floor of 2.28"),
                failures.get(0));
        assertTrue(check("x-1.0-natives-linux-arm64.jar",
                NativeHeaders.read("x.so", elf(183, "GLIBC_2.35"))).isEmpty());
        assertEquals(1, check("x-1.0-natives-linux-arm64.jar",
                NativeHeaders.read("x.so", elf(183, "GLIBC_2.36"))).size());
    }

    @Test
    void anElfThatNeedsNoGlibcVersionRecordsNoRequirement() {
        Binary binary = only(NativeHeaders.read("a.so", elf(62)));
        assertNull(binary.requires());
    }

    @Test
    void aPeIsReadForItsMachineAndTheNewerOfItsTwoVersions() {
        Binary x64 = only(NativeHeaders.read("a.dll", pe(0x8664, 6, 0, 6, 0)));
        assertEquals(Target.WINDOWS_X86_64, x64.target());
        assertEquals(Version.parse("6.0"), x64.requires());
        Binary arm = only(NativeHeaders.read("a.dll", pe(0xAA64, 6, 0, 6, 2)));
        assertEquals(Target.WINDOWS_AARCH64, arm.target());
        assertEquals(Version.parse("6.2"), arm.requires());
        List<String> failures = check("x-1.0-natives-windows.jar", NativeHeaders.read("x.dll", pe(0x8664, 10, 0, 10, 1)));
        assertEquals(1, failures.size(), failures.toString());
    }

    @Test
    void aNativeForAPlatformNoFloorNamesIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> NativeHeaders.read("a.so", elf(243, "GLIBC_2.17")));
        assertThrows(IllegalArgumentException.class, () -> NativeHeaders.read("a.dylib", machO(7, false, 0x000A0B00)));
    }

    /** The jar walk finds natives by their bytes, whatever their names, and passes everything else over. */
    @Test
    void aJarIsSearchedByContent(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("p-1.0-natives-macos-arm64.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.US_ASCII));
            put(zip, "p/Some.class", new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61});
            put(zip, "p/native/libp", fat(machO(ARM64, true, 0x000B0000)));
            put(zip, "p/native/p.so", elf(183, "GLIBC_2.17"));
        }
        List<Found> found = NativeHeaders.readJar(jar);
        assertEquals(2, found.size(), found.toString());
        assertEquals("p-1.0-natives-macos-arm64.jar", found.get(0).jar());
        assertEquals(Target.MACOS_AARCH64, found.get(0).binary().target());
        assertEquals(Target.LINUX_AARCH64, found.get(1).binary().target());
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Binary only(List<Binary> binaries) {
        assertEquals(1, binaries.size(), binaries.toString());
        return binaries.get(0);
    }

    private static List<String> check(String jar, List<Binary> binaries) {
        return FloorCheck.failures(binaries.stream().map(b -> new Found(jar, b)).toList(),
                FloorCheck.declaredFloors(), List.of());
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** A 64-bit little-endian Mach-O with one minimum-version command and nothing else. */
    static byte[] machO(int cpu, boolean buildVersion, int version) {
        int commandSize = buildVersion ? 24 : 16;
        ByteBuffer b = ByteBuffer.allocate(32 + commandSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0xFEEDFACF).putInt(cpu).putInt(0).putInt(6 /* MH_DYLIB */).putInt(1).putInt(commandSize)
                .putInt(0).putInt(0);
        if (buildVersion) {
            b.putInt(0x32).putInt(24).putInt(1 /* macOS */).putInt(version).putInt(version).putInt(0);
        } else {
            b.putInt(0x24).putInt(16).putInt(version).putInt(version);
        }
        return b.array();
    }

    /** A universal binary of the slices given, each at a 4096-byte boundary. */
    static byte[] fat(byte[]... slices) {
        int offset = 4096;
        ByteBuffer header = ByteBuffer.allocate(8 + 20 * slices.length).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0xCAFEBABE).putInt(slices.length);
        int[] offsets = new int[slices.length];
        for (int i = 0; i < slices.length; i++) {
            offsets[i] = offset;
            offset += (slices[i].length + 4095) / 4096 * 4096;
        }
        for (int i = 0; i < slices.length; i++) {
            int cpu = ByteBuffer.wrap(slices[i]).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
            header.putInt(cpu).putInt(0).putInt(offsets[i]).putInt(slices[i].length).putInt(12);
        }
        byte[] out = new byte[offset];
        System.arraycopy(header.array(), 0, out, 0, header.position());
        for (int i = 0; i < slices.length; i++) {
            System.arraycopy(slices[i], 0, out, offsets[i], slices[i].length);
        }
        return out;
    }

    /**
     * A 64-bit little-endian ELF with three sections: the null one, a string table, and a
     * version-needs section asking libc.so.6 for each version given.
     */
    static byte[] elf(int machine, String... versions) {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        strings.write(0);
        int libc = strings.size();
        strings.writeBytes("libc.so.6\0".getBytes(StandardCharsets.US_ASCII));
        int[] names = new int[versions.length];
        for (int i = 0; i < versions.length; i++) {
            names[i] = strings.size();
            strings.writeBytes((versions[i] + "\0").getBytes(StandardCharsets.US_ASCII));
        }
        byte[] table = strings.toByteArray();
        int needs = versions.length == 0 ? 0 : 1;
        ByteBuffer verneed = ByteBuffer.allocate(16 + 16 * versions.length).order(ByteOrder.LITTLE_ENDIAN);
        if (needs == 1) {
            verneed.putShort((short) 1).putShort((short) versions.length).putInt(libc).putInt(16).putInt(0);
            for (int i = 0; i < versions.length; i++) {
                verneed.putInt(0).putShort((short) 0).putShort((short) (i + 2)).putInt(names[i])
                        .putInt(i == versions.length - 1 ? 0 : 16);
            }
        }
        int stringsAt = 64;
        int verneedAt = stringsAt + table.length;
        int sectionsAt = (verneedAt + verneed.capacity() + 7) / 8 * 8;
        ByteBuffer b = ByteBuffer.allocate(sectionsAt + 3 * 64).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0});
        b.position(16);
        b.putShort((short) 3).putShort((short) machine).putInt(1).putLong(0).putLong(0).putLong(sectionsAt)
                .putInt(0).putShort((short) 64).putShort((short) 56).putShort((short) 0)
                .putShort((short) 64).putShort((short) 3).putShort((short) 1);
        b.position(stringsAt);
        b.put(table);
        b.put(verneed.array());
        b.position(sectionsAt + 64);
        section(b, 3 /* SHT_STRTAB */, stringsAt, table.length, 0, 0);
        section(b, 0x6FFFFFFE, verneedAt, verneed.capacity(), 1, needs);
        return b.array();
    }

    private static void section(ByteBuffer b, int type, long offset, long size, int link, int info) {
        b.putInt(0).putInt(type).putLong(0).putLong(0).putLong(offset).putLong(size).putInt(link).putInt(info)
                .putLong(1).putLong(0);
    }

    /** A PE32+ header and nothing after it: enough for the machine and the two versions. */
    static byte[] pe(int machine, int osMajor, int osMinor, int subsystemMajor, int subsystemMinor) {
        ByteBuffer b = ByteBuffer.allocate(0x40 + 24 + 240).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'M').put((byte) 'Z');
        b.putInt(0x3C, 0x40);
        b.position(0x40);
        b.putInt(0x00004550).putShort((short) machine).putShort((short) 0).putInt(0).putInt(0).putInt(0)
                .putShort((short) 240).putShort((short) 0x2022);
        int optional = 0x40 + 24;
        b.putShort(optional, (short) 0x20B);
        b.putShort(optional + 40, (short) osMajor).putShort(optional + 42, (short) osMinor);
        b.putShort(optional + 48, (short) subsystemMajor).putShort(optional + 50, (short) subsystemMinor);
        return b.array();
    }
}
