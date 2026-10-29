package limn.backend.lwjgl.internal;

import limn.testfixtures.NativeImageMetadata;
import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the backend's jar tells GraalVM's native image it needs, and the feature that picks the natives. */
class NativeImageFeatureTest {

    private static final Path MODULE = RepositoryRoot.find().resolve("limn-backend-lwjgl");

    private static final List<Path> CLASS_PATH = Arrays.stream(
                    System.getProperty("java.class.path").split(File.pathSeparator))
            .map(Path::of).toList();

    /** Each platform's directories, in both spellings: the toolkit's payloads' and LWJGL's. */
    @Test
    void thePlatformNamesTheDirectories() {
        assertEquals(List.of("limn/video/ffmpeg/native/macos-aarch64/", "limn/svg/native/macos-aarch64/",
                        "limn/angle/native/macos-aarch64/", "macos/arm64/", "META-INF/macos/arm64/"),
                NativeImageFeature.prefixes("Mac OS X", "aarch64"));
        assertEquals(List.of("limn/video/ffmpeg/native/windows-x86_64/", "limn/svg/native/windows-x86_64/",
                        "limn/angle/native/windows-x86_64/", "windows/x64/", "META-INF/windows/x64/"),
                NativeImageFeature.prefixes("Windows 11", "amd64"));
        assertEquals("linux/arm64/", NativeImageFeature.prefixes("Linux", "aarch64").get(3));
        assertEquals("limn/svg/native/linux-x86_64/", NativeImageFeature.prefixes("Linux", "x86_64").get(1));
    }

    /**
     * Against the jars this module ships with, every platform finds its LWJGL and SVG libraries, and
     * no platform's selection reaches another's.
     */
    @Test
    void eachPlatformTakesItsOwnLibrariesAndNoOthers() {
        String[][] platforms = {{"Mac OS X", "aarch64"}, {"Mac OS X", "x86_64"}, {"Windows 11", "amd64"},
                {"Windows 11", "aarch64"}, {"Linux", "amd64"}, {"Linux", "aarch64"}};
        TreeSet<String> seen = new TreeSet<>();
        for (String[] platform : platforms) {
            List<String> prefixes = NativeImageFeature.prefixes(platform[0], platform[1]);
            List<String> found = NativeImageFeature.matching(CLASS_PATH, prefixes);
            String what = platform[0] + " " + platform[1];
            assertTrue(found.stream().anyMatch(name -> name.startsWith(prefixes.get(3))), what + ": LWJGL");
            assertTrue(found.stream().anyMatch(name -> name.startsWith(prefixes.get(1))), what + ": SVG");
            for (String name : found) {
                assertTrue(seen.add(name), name + " was taken by two platforms");
            }
        }
    }

    /** Every file the backend ships is in an image built from it, its licence and notes aside. */
    @Test
    void everyResourceIsNamed() {
        assertEquals(Set.of(), NativeImageMetadata.uncoveredResources(MODULE, "limn-backend-lwjgl", Set.of(
                        "limn/backend/lwjgl/fonts/README.md",
                        "limn/backend/lwjgl/fonts/LimnMenuSymbols-LICENSE.txt")),
                "resources a native image would leave out");
    }

    /** The faces the font artifacts carry, the optional ones included, are all in an image. */
    @Test
    void everyFaceOnTheClassPathIsNamed() {
        List<String> globs = NativeImageMetadata.globs(NativeImageMetadata.file(MODULE, "limn-backend-lwjgl"));
        List<String> faces = NativeImageFeature.matching(CLASS_PATH, List.of("limn/fonts/")).stream()
                .filter(name -> name.endsWith(".ttf") || name.endsWith(".otf")).toList();
        assertFalse(faces.isEmpty(), "no font artifact on the test class path");
        for (String face : faces) {
            assertTrue(globs.stream().anyMatch(glob -> NativeImageMetadata.matches(glob, face)), face);
        }
    }
}
