package limn.backend.lwjgl;

import limn.graphics.TextMetrics;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Face 0 of a TrueType collection does <em>not</em> live at byte 0 (that's the
 * {@code ttcf} header), so the loader must look its offset up even for index 0
 * (nearly every macOS system font and the Windows CJK fonts ship as .ttc).
 * Exercised deterministically by wrapping the bundled faces in a minimal
 * collection built here.
 *
 * <p>The second test is the other half of the same fact, and the half that
 * cost more: <b>a collection's face is addressed two different ways</b> — stb
 * takes a byte offset, the shaper takes the index — so a face at index
 * <em>n</em> is only correct while both ends name the same one.
 */
class StbFontTtcTest {

    /**
     * Packs whole .ttf files into one .ttc, rebasing each face's table
     * directory (sfnt table offsets are file-absolute, so a face moved by
     * concatenation has to have every one of them shifted by where it landed).
     */
    private static byte[] ttcWrap(byte[]... faces) {
        int header = 12 + 4 * faces.length;
        int total = header;
        for (byte[] face : faces) {
            total += face.length;
        }
        ByteBuffer out = ByteBuffer.allocate(total); // big-endian
        out.putInt(0x74746366); // 'ttcf'
        out.putInt(0x00010000); // version 1.0
        out.putInt(faces.length);
        int at = header;
        for (byte[] face : faces) {
            out.putInt(at);
            at += face.length;
        }
        for (byte[] face : faces) {
            out.put(face);
        }
        byte[] bytes = out.array();
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int base = header;
        for (byte[] face : faces) {
            int numTables = bb.getShort(base + 4) & 0xFFFF;
            for (int i = 0; i < numTables; i++) {
                int offsetField = base + 12 + i * 16 + 8;
                bb.putInt(offsetField, bb.getInt(offsetField) + base);
            }
            base += face.length;
        }
        return bytes;
    }

    private static byte[] bundled(String resource) throws Exception {
        try (InputStream in = StbFontTtcTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/" + resource)) {
            assertNotNull(in, "bundled font must be on the classpath: " + resource);
            return in.readAllBytes();
        }
    }

    @Test
    void loadsFaceZeroOfACollection() throws Exception {
        Path file = Files.createTempFile("roboto", ".ttc");
        try {
            Files.write(file, ttcWrap(bundled("Roboto-Regular.ttf")));
            StbFont face = StbFont.loadFile(file, 0, "Roboto TTC");
            TextMetrics metrics = face.measure("Hello", 16f);
            assertTrue(metrics.width() > 0, "face 0 of the collection must shape text");
            assertTrue(metrics.ascent() > 0);
            face.close();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void theShaperOpensTheFaceOfTheCollectionThatStbOpened() throws Exception {
        // Two faces with nothing in common, so a mix-up cannot go unnoticed: the menu-symbol face
        // draws arrows and has no Latin at all, Roboto is at index 1. A shaper hard-wired to face
        // 0 answers this collection's Latin out of the symbol face — silently, with ids that look
        // perfectly valid, because a glyph index carries no evidence of which face issued it.
        Path file = Files.createTempFile("two-faces", ".ttc");
        try {
            Files.write(file, ttcWrap(bundled("LimnMenuSymbols.ttf"), bundled("Roboto-Regular.ttf")));
            StbFont face = StbFont.loadFile(file, 1, "Roboto at index 1");
            try {
                HarfBuzzShaper.Handle handle = face.shaper();
                assertNotNull(handle, "the HarfBuzz native has to be present to prove this");

                String text = "Hambur";
                HarfBuzzShaper.Output shaped = new HarfBuzzShaper.Output();
                assertTrue(HarfBuzzShaper.shapeRun(handle, text, 0, text.length(),
                                HarfBuzzShaper.scriptTag(Character.UnicodeScript.LATIN), false,
                                face.scaleForSize(16f), shaped),
                        "a plain Latin run in a face that covers it must shape");

                // No ligature in "Hambur", so the shaper's answer IS the cmap's answer — for the
                // face it was opened over. Comparing the two is what catches a shaper reading a
                // different face of the same file: the ids simply stop matching, and every letter
                // on screen becomes a different letter while the advances stay plausible.
                assertEquals(text.length(), shaped.count);
                for (int i = 0; i < text.length(); i++) {
                    assertEquals(face.glyphIndex(text.charAt(i)), shaped.glyphIds[i],
                            "the shaper and the rasterizer must be looking at the same face");
                }
                // Not a vacuous comparison: the symbol face at index 0 has no Latin, so a shaper
                // opened on it answers .notdef for all six. If both sides were somehow 0 this
                // assertion is what says so.
                for (int i = 0; i < text.length(); i++) {
                    assertTrue(shaped.glyphIds[i] > 0,
                            "face 1 covers Latin; a zero here is the wrong face answering");
                }
            } finally {
                face.close();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
