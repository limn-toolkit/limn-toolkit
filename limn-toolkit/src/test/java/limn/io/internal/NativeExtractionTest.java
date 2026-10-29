package limn.io.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extraction finding a directory a cleaner of temporary files has been through. */
class NativeExtractionTest {

    private static final String CACHE = "limn.test.extraction.cache";
    private static final List<String> NAMES = List.of("liblimn.so", "libdependency.so");

    @TempDir
    Path payload;
    @TempDir
    Path cache;

    private URLClassLoader loader;
    private NativeExtraction extraction;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(payload.resolve("limn/test/native"));
        Files.writeString(root.resolve("liblimn.so"), "the library");
        Files.writeString(root.resolve("libdependency.so"), "what it loads");
        loader = new URLClassLoader(new URL[] {payload.toUri().toURL()}, null);
        System.setProperty(CACHE, cache.toString());
        extraction = new NativeExtraction("limn-test", CACHE);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(CACHE);
        loader.close();
    }

    private Path extract() throws Exception {
        return extraction.extract(loader, "limn/test/native/", NAMES, "");
    }

    /** Emptied and left as a directory: what was seen on a Windows guest, where SVG then never loaded. */
    @Test
    void anEmptiedDirectoryIsExtractedAgain() throws Exception {
        Path first = extract();
        for (String name : NAMES) {
            Files.delete(first.resolve(name));
        }

        Path second = extract();

        assertEquals(first, second);
        assertEquals("the library", Files.readString(second.resolve("liblimn.so")));
        assertEquals("what it loads", Files.readString(second.resolve("libdependency.so")));
    }

    /** The first library is still there and a dependency is not: reusing it would fail at load. */
    @Test
    void aDirectoryMissingADependencyIsExtractedAgain() throws Exception {
        Path first = extract();
        Files.delete(first.resolve("libdependency.so"));

        Path second = extract();

        assertTrue(Files.isRegularFile(second.resolve("libdependency.so")));
    }

    /** The directory set aside goes, and so does the staging one. */
    @Test
    void nothingIsLeftBesideTheExtraction() throws Exception {
        Path first = extract();
        Files.delete(first.resolve("liblimn.so"));

        Path second = extract();

        try (var entries = Files.list(second.getParent())) {
            assertEquals(List.of(second), entries.toList());
        }
    }
}
