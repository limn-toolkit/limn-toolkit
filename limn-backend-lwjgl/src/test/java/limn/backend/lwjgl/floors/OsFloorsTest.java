package limn.backend.lwjgl.floors;

import limn.backend.lwjgl.floors.NativeHeaders.Binary;
import limn.backend.lwjgl.floors.NativeHeaders.Found;
import limn.backend.lwjgl.floors.OsFloors.KnownException;
import limn.backend.lwjgl.floors.OsFloors.Target;
import limn.backend.lwjgl.floors.OsFloors.Version;
import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every native library this build resolves, for all six platforms, runs on the systems
 * {@link OsFloors} names, and the install guide says so.
 *
 * <p>The jars come from the build in {@code limn.osFloors.jars}: the backend's runtime classpath,
 * which carries LWJGL's and the SVG renderer's natives for every platform, and the FFmpeg payload's
 * main jar and six classifiers at the version the catalog pins, which this module otherwise never
 * resolves. A dependency bump that raises a floor (a LWJGL built on a newer toolchain, a payload
 * rebuilt on a newer glibc) fails here, before the release that would have dropped a system.
 */
class OsFloorsTest {

    /** The payloads that ship natives, each of which must be read for every platform. */
    private static final List<String> PAYLOADS = List.of("lwjgl", "limn-lunasvg-natives", "limn-ffmpeg-natives");

    @Test
    void everyResolvedNativeIsWithinItsFloor() throws IOException {
        List<Found> found = resolved();
        List<String> failures = new ArrayList<>(FloorCheck.gaps(found, PAYLOADS));
        failures.addAll(FloorCheck.failures(found, FloorCheck.declaredFloors(), OsFloors.KNOWN_EXCEPTIONS));
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * The same libraries against floors lowered just under what they ask for: each platform then
     * fails, on exactly the libraries that set its floor, which is the proof that the numbers were
     * read from the binaries and not defaulted.
     */
    @Test
    void floorsLoweredUnderTheNewestRequirementFailOnTheLibrariesThatSetThem() throws IOException {
        List<Found> found = resolved();
        for (Target target : Target.values()) {
            Version newest = null;
            for (Found f : found) {
                Version requires = f.binary().requires();
                if (f.binary().target() == target && !excused(f) && requires != null
                        && (newest == null || requires.isAfter(newest))) {
                    newest = requires;
                }
            }
            assertNotNull(newest, "nothing read for " + target);
            Map<Target, Version> floors = FloorCheck.declaredFloors();
            Version lowered = newest.patch() > 0 ? new Version(newest.major(), newest.minor(), newest.patch() - 1)
                    : newest.minor() > 0 ? new Version(newest.major(), newest.minor() - 1, 99)
                    : new Version(newest.major() - 1, 99, 99);
            floors.put(target, lowered);
            List<String> failures = FloorCheck.failures(found, floors, OsFloors.KNOWN_EXCEPTIONS);
            assertFalse(failures.isEmpty(), target + " under " + lowered + " did not fail");
            for (Found f : found) {
                boolean setsIt = f.binary().target() == target && !excused(f) && newest.equals(f.binary().requires());
                boolean named = failures.stream().anyMatch(line -> line.startsWith(f.binary().name() + " asks for"));
                assertEquals(setsIt, named, f.binary() + " under a " + target + " floor of " + lowered);
            }
        }
    }

    /**
     * An exception holds while the catalog pins its version and fails the build once it does
     * not: renamed to the next version, the same libraries fail their floor and the exception is
     * reported stale; renamed and rebuilt within the floor, only the stale exception is left.
     */
    @Test
    void anExceptionGoesStaleWhenThePinMovesAndTheCheckThenFails() {
        KnownException exception = new KnownException("pay", "1.0.0", EnumSet.of(Target.MACOS_AARCH64),
                Version.parse("26.0"), "1.0.1 fixes it");
        Binary late = new Binary("libpay.dylib", Target.MACOS_AARCH64, Version.parse("26.0"));
        Binary fixed = new Binary("libpay.dylib", Target.MACOS_AARCH64, Version.parse("11.0"));
        Map<Target, Version> floors = FloorCheck.declaredFloors();

        assertEquals(List.of(), FloorCheck.failures(
                List.of(new Found("pay-1.0.0-natives-macos-aarch64.jar", late)), floors, List.of(exception)));

        List<String> bumped = FloorCheck.failures(
                List.of(new Found("pay-1.0.1-natives-macos-aarch64.jar", late)), floors, List.of(exception));
        assertEquals(2, bumped.size(), bumped.toString());
        assertTrue(bumped.get(0).startsWith("libpay.dylib asks for minimum macOS 26.0"), bumped.get(0));
        assertTrue(bumped.get(1).contains("is stale: no jar of that exact version") && bumped.get(1).contains("1.0.1 fixes it"),
                bumped.get(1));

        List<String> rebuilt = FloorCheck.failures(
                List.of(new Found("pay-1.0.1-natives-macos-aarch64.jar", fixed)), floors, List.of(exception));
        assertEquals(1, rebuilt.size(), rebuilt.toString());
        assertTrue(rebuilt.get(0).contains("is stale"), rebuilt.get(0));

        List<String> republished = FloorCheck.failures(
                List.of(new Found("pay-1.0.0-natives-macos-aarch64.jar", fixed)), floors, List.of(exception));
        assertEquals(1, republished.size(), republished.toString());
        assertTrue(republished.get(0).contains("within the floors now"), republished.get(0));
    }

    /** An exception excuses its version, its platforms and its ceiling, and nothing past them. */
    @Test
    void anExceptionExcusesNothingBeyondWhatItNames() {
        KnownException exception = new KnownException("pay", "1.0.0", EnumSet.of(Target.MACOS_AARCH64),
                Version.parse("26.0"), "1.0.1 fixes it");
        Map<Target, Version> floors = FloorCheck.declaredFloors();
        List<Found> worse = List.of(new Found("pay-1.0.0-natives-macos-aarch64.jar",
                new Binary("libpay.dylib", Target.MACOS_AARCH64, Version.parse("27.0"))));
        assertTrue(FloorCheck.failures(worse, floors, List.of(exception)).get(0)
                .contains("and than the 26.0 its exception allows"));
        List<Found> otherPlatform = List.of(
                new Found("pay-1.0.0-natives-macos-aarch64.jar",
                        new Binary("libpay.dylib", Target.MACOS_AARCH64, Version.parse("26.0"))),
                new Found("pay-1.0.0-natives-macos-x86_64.jar",
                        new Binary("libpay.dylib[x]", Target.MACOS_X86_64, Version.parse("26.0"))));
        List<String> failures = FloorCheck.failures(otherPlatform, floors, List.of(exception));
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).startsWith("libpay.dylib[x] asks for"), failures.get(0));

        assertTrue(exception.covers("pay-1.0.0.jar"));
        assertTrue(exception.covers("pay-1.0.0-natives-macos-aarch64.jar"));
        assertFalse(exception.covers("pay-1.0.0.1.jar"));
        assertFalse(exception.covers("pay-1.0.01-natives-macos-aarch64.jar"));
        assertFalse(exception.covers("pay-1.0.0-SNAPSHOT.jar"));
    }

    /** The install guide's table states every floor, and points here for where they are kept. */
    @Test
    void theInstallGuideStatesTheseFloorsAndCitesThisFile() throws IOException {
        String guide = Files.readString(RepositoryRoot.find().resolve("site/src/guides/install.md"));
        int start = guide.indexOf("### Supported systems");
        assertTrue(start >= 0, "the install guide has no Supported systems table");
        int end = guide.indexOf("\n## ", start);
        String table = guide.substring(start, end < 0 ? guide.length() : end);
        for (Target target : Target.values()) {
            assertTrue(table.contains(target.documented()), "the table does not say " + target.documented());
        }
        assertTrue(table.contains("limn-backend-lwjgl/src/test/java/limn/backend/lwjgl/floors/OsFloors.java"),
                "the table does not cite OsFloors");
    }

    private static boolean excused(Found f) {
        return OsFloors.KNOWN_EXCEPTIONS.stream()
                .anyMatch(e -> e.covers(f.jar()) && e.targets().contains(f.binary().target()));
    }

    private static List<Found> resolved() throws IOException {
        String jars = System.getProperty("limn.osFloors.jars");
        assertNotNull(jars, "limn.osFloors.jars is not set; limn-backend-lwjgl's build passes it to the test task");
        List<Found> found = new ArrayList<>();
        for (String path : jars.split(File.pathSeparator)) {
            Path jar = Path.of(path);
            if (path.endsWith(".jar") && Files.isRegularFile(jar)) {
                found.addAll(NativeHeaders.readJar(jar));
            }
        }
        return found;
    }
}
