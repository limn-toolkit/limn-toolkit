package limn.backend.lwjgl;

import limn.testfixtures.MutationFuzz;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tables of a font that the toolkit reads itself, in Java, are read or given up on, and
 * never thrown from: the names a font file is listed by, and the character map and the colour
 * strikes of the emoji font, which are asked for in the middle of laying out a line of text.
 * What a native library reads of a font is not run here.
 */
class FontTablesFuzzTest {

    @TempDir
    Path directory;

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = FontTablesFuzzTest.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Test
    void aDamagedFontFileIsListedOrIsNot() throws IOException {
        byte[] seed = resource("/limn/backend/lwjgl/fonts/LimnMenuSymbols.ttf");
        Path file = directory.resolve("fuzz.ttf");
        int[] listed = {0};
        MutationFuzz.Result result = MutationFuzz.run(42, 5000, List.of(seed), data -> {
            Files.write(file, data);
            listed[0] += SystemFonts.facesIn(file).size();
        }, thrown -> false, "limn.backend.lwjgl.");
        assertEquals(Map.of(), result.escapes(), "a damaged font file escaped the reader of its names");
        assertTrue(listed[0] > 0, "no mutation left a face to list, so nothing was read");
    }

    /** The offset and length of each table the toolkit reads in Java, from the font's own directory. */
    private static List<int[]> javaReadTables(byte[] font) {
        ByteBuffer be = ByteBuffer.wrap(font);
        List<int[]> tables = new ArrayList<>();
        tables.add(new int[] {0, 12 + (be.getShort(4) & 0xFFFF) * 16});
        for (int i = 0; i < (be.getShort(4) & 0xFFFF); i++) {
            int record = 12 + i * 16;
            String tag = new String(font, record, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (List.of("cmap", "CBLC", "head", "hhea", "hmtx").contains(tag)) {
                tables.add(new int[] {be.getInt(record + 8), be.getInt(record + 12)});
            }
        }
        return tables;
    }

    @Test
    void aDamagedEmojiFontGivesNoGlyphAndNoException() throws IOException {
        byte[] seed = resource("/limn/fonts/NotoColorEmoji.ttf");
        Assumptions.assumeTrue(seed != null, "Noto Color Emoji is optional");
        List<int[]> tables = javaReadTables(seed);
        // A strike is a PNG, which the picture decoder reads: without one no glyph is ever drawn
        // and the path past the tables is not walked.
        StbImageDecoder pictures = new StbImageDecoder();
        limn.graphics.Images.installDecoder(pictures);
        try {
            fuzzEmoji(seed, tables);
        } finally {
            limn.graphics.Images.uninstallDecoder(pictures);
        }
    }

    private static void fuzzEmoji(byte[] seed, List<int[]> tables) {
        Random random = new Random(42);
        Map<String, String> escapes = new TreeMap<>();
        int opened = 0;
        int drawn = 0;
        for (int it = 0; it < 250; it++) {
            byte[] font = seed.clone();
            int[] table = tables.get(random.nextInt(tables.size()));
            int span = Math.max(1, Math.min(table[1], 4096)); // the head of a table is where its counts and offsets are
            int edits = 1 + random.nextInt(4);
            for (int e = 0; e < edits; e++) {
                int at = table[0] + random.nextInt(span);
                if (random.nextBoolean() && at + 4 <= font.length) {
                    ByteBuffer.wrap(font).putInt(at, new int[] {0, -1, 0x7FFFFFFF, 0x10000, 65535, 1 << 24}[random.nextInt(6)]);
                } else {
                    font[at] = (byte) random.nextInt(256);
                }
            }
            try {
                ColorEmojiFont emoji = ColorEmojiFont.fromBytes(font, "fuzz");
                if (emoji != null) {
                    opened++;
                    for (int cp : new int[] {0x1F600, 0x2764, 0x1F1E7, 0x41}) {
                        emoji.covers(cp);
                        emoji.advance(cp, 16);
                        if (emoji.emoji(cp) != null) {
                            drawn++;
                        }
                    }
                    emoji.close();
                }
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError thrown) {
                StackTraceElement[] frames = thrown.getStackTrace();
                escapes.putIfAbsent(thrown.getClass().getSimpleName() + " at "
                        + (frames.length == 0 ? "no frame" : frames[0]), String.valueOf(thrown.getMessage()));
            }
        }
        assertEquals(Map.of(), escapes, "a damaged emoji font escaped its reader");
        assertTrue(opened > 0 && drawn > 0, "the mutations test nothing: " + opened + " opened, " + drawn + " drawn");
    }
}
