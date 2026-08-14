package limn.backend.lwjgl;

import limn.graphics.TextMetrics;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Face 0 of a TrueType collection does <em>not</em> live at byte 0 (that's the
 * {@code ttcf} header), so the loader must look its offset up even for index 0
 * (nearly every macOS system font and the Windows CJK fonts ship as .ttc).
 * Exercised deterministically by wrapping the bundled Roboto in a minimal
 * single-face collection built here.
 */
class StbFontTtcTest {

    /** Wraps a standalone .ttf as a 1-face .ttc (table offsets are file-absolute). */
    private static byte[] ttcWrap(byte[] ttf) {
        ByteBuffer out = ByteBuffer.allocate(16 + ttf.length); // big-endian
        out.putInt(0x74746366); // 'ttcf'
        out.putInt(0x00010000); // version 1.0
        out.putInt(1);          // one face
        out.putInt(16);         // face 0's offset table starts after this header
        out.put(ttf);
        byte[] bytes = out.array();
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int numTables = bb.getShort(16 + 4) & 0xFFFF;
        for (int i = 0; i < numTables; i++) {
            int offsetField = 16 + 12 + i * 16 + 8;
            bb.putInt(offsetField, bb.getInt(offsetField) + 16);
        }
        return bytes;
    }

    @Test
    void loadsFaceZeroOfACollection() throws Exception {
        byte[] ttf;
        try (InputStream in = StbFontTtcTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/Roboto-Regular.ttf")) {
            assertNotNull(in, "bundled Roboto must be on the classpath");
            ttf = in.readAllBytes();
        }
        Path file = Files.createTempFile("roboto", ".ttc");
        try {
            Files.write(file, ttcWrap(ttf));
            StbFont face = StbFont.loadFile(file, 0, "Roboto TTC");
            TextMetrics metrics = face.measure("Hello", 16f);
            assertTrue(metrics.width() > 0, "face 0 of the collection must shape text");
            assertTrue(metrics.ascent() > 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
