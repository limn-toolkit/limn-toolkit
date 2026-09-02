package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure-Java OpenType {@code name}-table reader that backs system-font
 * enumeration, exercised against the bundled Roboto face (a real .ttf) so it is
 * deterministic and platform-independent.
 */
class SystemFontsTest {

    @Test
    void parsesFamilyAndStyleFromRealFont() throws Exception {
        Path file = Files.createTempFile("roboto", ".ttf");
        try (InputStream in = SystemFontsTest.class.getResourceAsStream(
                "/limn/fonts/Roboto-Bold.ttf")) {
            assertNotNull(in, "bundled Roboto-Bold must be on the classpath");
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            List<SystemFonts.Face> faces = SystemFonts.facesIn(file);
            assertFalse(faces.isEmpty(), "the face's name table must be parsed");
            SystemFonts.Face face = faces.get(0);
            assertEquals("Roboto", face.family());
            assertTrue(face.bold(), "Roboto-Bold reports a bold style");
            assertFalse(face.italic());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void ignoresNonFontFilesGracefully() throws Exception {
        Path file = Files.createTempFile("junk", ".ttf");
        Files.writeString(file, "not a font at all");
        try {
            assertTrue(SystemFonts.facesIn(file).isEmpty(), "garbage yields no faces, never throws");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void scanNeverThrows() {
        // Platform-dependent count (CI may have few/no fonts); just prove it is safe.
        assertNotNull(SystemFonts.scan());
    }

    @Test
    void enumerationFiltersInternalDotFamilies() {
        // macOS exposes private faces named '.SF NS', '.AppleSystemUIFont', … that error
        // when used; they must never reach the catalog. (No-op where there are no such fonts.)
        List<String> dotted = SystemFonts.scan().stream().map(SystemFonts.Face::family)
                .filter(f -> f.startsWith(".")).distinct().sorted().toList();
        assertTrue(dotted.isEmpty(), "internal dot families must be filtered: " + dotted);
    }

    @Test
    void prefersDecodableAsciiName() {
        // A parsed name must never contain the Unicode replacement char (a decode failure)
        // and, on any platform, should decode cleanly: the fix for tofu family names.
        for (SystemFonts.Face face : SystemFonts.scan()) {
            assertFalse(face.family().indexOf('�') >= 0,
                    "family name failed to decode cleanly: " + face.family());
        }
    }
}
