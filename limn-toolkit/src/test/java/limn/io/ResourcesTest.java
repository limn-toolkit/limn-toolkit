package limn.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one way to read a resource or a file whole, and what it says when it cannot. Two resources
 * are under test: this class's own {@code .class} file, which is on every classpath these run
 * on, and {@code greeting.txt} beside it in the test resources, for the text forms.
 */
class ResourcesTest {

    private static final String RELATIVE = "ResourcesTest.class";
    private static final String ABSOLUTE = "/limn/io/ResourcesTest.class";
    private static final String FROM_ROOT = "limn/io/ResourcesTest.class";
    private static final String GREETING = "olá, mundo\n";

    @Test
    void aResourceIsReadWholeRelativeToItsOwnerOrAbsolutely() {
        byte[] relative = Resources.bytes(ResourcesTest.class, RELATIVE, "class");
        byte[] absolute = Resources.bytes(ResourcesTest.class, ABSOLUTE, "class");
        assertTrue(relative.length > 4, "a class file has a header at least");
        assertArrayEquals(relative, absolute, "two spellings of the same resource");
        // A class file begins with the magic number 0xCAFEBABE.
        assertEquals((byte) 0xCA, relative[0]);
        assertEquals((byte) 0xFE, relative[1]);
    }

    @Test
    void aClassLoaderResolvesFromTheRootWithNoLeadingSlash() {
        byte[] fromLoader = Resources.bytes(ResourcesTest.class.getClassLoader(), FROM_ROOT, "class");
        assertArrayEquals(Resources.bytes(ResourcesTest.class, ABSOLUTE, "class"), fromLoader);
    }

    @Test
    void aMissingResourceNamesWhatWasLookedForAndWhereItWasNot() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Resources.bytes(ResourcesTest.class, "/no/such/thing.png", "image"));
        assertEquals("image resource missing: /no/such/thing.png", missing.getMessage());
        IllegalStateException fromLoader = assertThrows(IllegalStateException.class,
                () -> Resources.bytes(ResourcesTest.class.getClassLoader(), "no/such/thing.wav", "audio"));
        assertEquals("audio resource missing: no/such/thing.wav", fromLoader.getMessage());
        IllegalStateException text = assertThrows(IllegalStateException.class,
                () -> Resources.text(ResourcesTest.class, "/no/such/x.glsl", "shader"));
        assertEquals("shader resource missing: /no/such/x.glsl", text.getMessage());
    }

    @Test
    void theOptionalFormsAnswerNullForAMissingResource() {
        assertNull(Resources.bytesIfPresent(ResourcesTest.class, "/no/such/font.ttf", "font"));
        assertNull(Resources.bytesIfPresent(ResourcesTest.class.getClassLoader(), "no/such/font.ttf", "font"));
        assertNull(Resources.textIfPresent(ResourcesTest.class, "/no/such/strings.properties", "strings"));
        assertNull(Resources.textIfPresent(ResourcesTest.class.getClassLoader(), "no/such/strings.properties", "strings"));
        assertNotNull(Resources.bytesIfPresent(ResourcesTest.class, RELATIVE, "class"));
    }

    @Test
    void textIsTheResourceDecodedAsUtf8() {
        assertEquals(GREETING, Resources.text(ResourcesTest.class, "greeting.txt", "greeting"));
        assertEquals(GREETING, Resources.textIfPresent(ResourcesTest.class, "/limn/io/greeting.txt", "greeting"));
        assertEquals(GREETING, Resources.textIfPresent(ResourcesTest.class.getClassLoader(),
                "limn/io/greeting.txt", "greeting"));
    }

    @Test
    void aFileIsReadWholeAndAMissingOneSaysWhatItWas(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("clip.bin");
        byte[] payload = {1, 2, 3, 4, 5};
        Files.write(file, payload);
        assertArrayEquals(payload, Resources.bytes(file, "clip"));

        Path absent = dir.resolve("absent.bin");
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> Resources.bytes(absent, "Ogg stream"));
        assertEquals("reading Ogg stream " + absent, error.getMessage());
    }
}
