package limn.graphics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Image export with no backend running: no window, no GL context, no installed decoder. That is the
 * point: a test or an asset tool must be able to produce a file.
 */
class ImageEncodingTest {

    @AfterEach
    void restoreDefaultEncoders() {
        Images.uninstallAllEncoders();
        Images.installEncoder(PngEncoder.INSTANCE);
        Images.installEncoder(GifEncoder.INSTANCE);
    }

    @Test
    void pngIsEncodableWithoutABackend() {
        assertFalse(Images.isDecoderInstalled(), "this suite runs with no backend");
        assertTrue(Images.canEncode(ImageFormat.PNG));
        assertTrue(Images.canEncode(ImageFormat.GIF));
        assertEquals(2, Images.installedEncoders().size(), "the two the toolkit writes itself");
    }

    @Test
    void roundTripReproducesEveryPixel() {
        Image source = gradient(37, 23);
        Image decoded = TestPngReader.decode(Images.encode(source, ImageFormat.PNG));

        assertEquals(source.width(), decoded.width());
        assertEquals(source.height(), decoded.height());
        assertArrayEquals(source.pixels(), decoded.pixels(),
                "PNG is lossless and Image's layout is PNG's; nothing may be lost");
    }

    @Test
    void roundTripKeepsRowZeroAtTheTop() {
        // The flip trap: a PNG's first row is the top one, and so is an Image's. Two rows that
        // cannot be confused catch a stray vertical flip on either side of the round trip.
        byte[] rows = new byte[2 * 4];
        rows[0] = (byte) 0xFF; // top pixel: opaque red
        rows[3] = (byte) 0xFF;
        rows[6] = (byte) 0xFF; // bottom pixel: opaque blue
        rows[7] = (byte) 0xFF;
        Image decoded = TestPngReader.decode(Images.encode(new Image(1, 2, rows), ImageFormat.PNG));

        assertEquals((byte) 0xFF, decoded.pixels()[0], "top row stays red");
        assertEquals((byte) 0xFF, decoded.pixels()[6], "bottom row stays blue");
    }

    @Test
    void roundTripKeepsStraightAlphaIncludingFullyTransparentColour() {
        // Straight alpha, by Image's contract: a transparent pixel keeps its colour channels, which
        // a premultiplying encoder would have zeroed on the way out.
        byte[] pixels = {(byte) 0x40, (byte) 0x80, (byte) 0xC0, 0x00,
                         (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x80};
        Image decoded = TestPngReader.decode(Images.encode(new Image(2, 1, pixels), ImageFormat.PNG));

        assertArrayEquals(pixels, decoded.pixels());
    }

    @Test
    void encodingIsDeterministic() {
        // What makes an exported file comparable against a reference at all.
        Image source = gradient(64, 64);
        assertArrayEquals(Images.encode(source, ImageFormat.PNG),
                Images.encode(source, ImageFormat.PNG));
    }

    @Test
    void qualityDoesNotChangeALosslessFormat() {
        Image source = gradient(16, 16);
        assertArrayEquals(Images.encode(source, new ImageEncodeOptions(ImageFormat.PNG, 1)),
                Images.encode(source, new ImageEncodeOptions(ImageFormat.PNG, 100)));
    }

    @Test
    void byDefaultEveryRowIsWrittenAsItIsAndAFilteringAskedForFiltersThem() {
        // Rows as they are by default: one pass, as the platform's own writer does, where trying
        // both took twice its time on every picture a server answered.
        Image photo = noiseOverRamp(64, 48);
        assertTrue(filterTypes(Images.encode(photo, ImageFormat.PNG)).stream().allMatch(f -> f == 0),
                "no row filtered by default");
        ImageEncodeOptions adaptive = new ImageEncodeOptions(ImageFormat.PNG)
                .withFiltering(ImageEncodeOptions.Filtering.ADAPTIVE);
        assertTrue(filterTypes(Images.encode(photo, adaptive)).stream().anyMatch(f -> f != 0),
                "rows filtered when asked");
    }

    @Test
    void everyFilteringAndLevelDecodesToTheSamePixelsAndTheSmallerIsNeverLarger() {
        for (Image source : new Image[] {gradient(64, 64), noiseOverRamp(64, 48)}) {
            int none = 0;
            int adaptive = 0;
            for (ImageEncodeOptions.Filtering filtering : ImageEncodeOptions.Filtering.values()) {
                for (int level : new int[] {0, 1, 4, 9}) {
                    byte[] file = Images.encode(source, new ImageEncodeOptions(ImageFormat.PNG)
                            .withFiltering(filtering).withCompression(level));
                    assertArrayEquals(source.pixels(), TestPngReader.decode(file).pixels(), filtering + " " + level);
                    if (level == 4) {
                        switch (filtering) {
                            case NONE -> none = file.length;
                            case ADAPTIVE -> adaptive = file.length;
                            case SMALLER -> assertEquals(Math.min(none, adaptive), file.length,
                                    "the smaller of the two");
                        }
                    }
                }
            }
        }
        Image source = gradient(64, 64);
        assertTrue(Images.encode(source, new ImageEncodeOptions(ImageFormat.PNG).withCompression(0)).length
                > Images.encode(source, new ImageEncodeOptions(ImageFormat.PNG).withCompression(9)).length,
                "level 0 stores, 9 compresses");
    }

    @Test
    void aCompressionOutsideZeroToNineIsRefused() {
        ImageEncodeOptions options = new ImageEncodeOptions(ImageFormat.PNG);
        assertThrows(IllegalArgumentException.class, () -> options.withCompression(-1));
        assertThrows(IllegalArgumentException.class, () -> options.withCompression(10));
        assertEquals(ImageEncodeOptions.DEFAULT_COMPRESSION, options.compression());
        assertEquals(ImageEncodeOptions.Filtering.NONE, options.filtering());
    }

    /** A photograph's kind of picture: a ramp with a little noise, which filters well. */
    private static Image noiseOverRamp(int width, int height) {
        Random random = new Random(7);
        byte[] p = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                p[i] = (byte) (x * 3 + random.nextInt(6));
                p[i + 1] = (byte) (y * 4 + random.nextInt(6));
                p[i + 2] = (byte) ((x + y) * 2 + random.nextInt(6));
                p[i + 3] = (byte) 255;
            }
        }
        return new Image(width, height, p);
    }

    /** Each row's filter type, read from the file's inflated image data. */
    private static java.util.List<Integer> filterTypes(byte[] file) {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        int width = 0;
        int height = 0;
        int at = 8;
        while (at < file.length) {
            int length = ((file[at] & 0xff) << 24) | ((file[at + 1] & 0xff) << 16) | ((file[at + 2] & 0xff) << 8)
                    | (file[at + 3] & 0xff);
            String type = new String(file, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals("IHDR")) {
                width = ((file[at + 8] & 0xff) << 24) | ((file[at + 9] & 0xff) << 16) | ((file[at + 10] & 0xff) << 8)
                        | (file[at + 11] & 0xff);
                height = ((file[at + 12] & 0xff) << 24) | ((file[at + 13] & 0xff) << 16)
                        | ((file[at + 14] & 0xff) << 8) | (file[at + 15] & 0xff);
            } else if (type.equals("IDAT")) {
                data.write(file, at + 8, length);
            }
            at += 12 + length;
        }
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(data.toByteArray());
        byte[] raw = new byte[height * (1 + width * 4)];
        try {
            inflater.inflate(raw);
        } catch (java.util.zip.DataFormatException e) {
            throw new AssertionError(e);
        } finally {
            inflater.end();
        }
        java.util.List<Integer> types = new java.util.ArrayList<>();
        for (int row = 0; row < height; row++) {
            types.add(raw[row * (1 + width * 4)] & 0xff);
        }
        return types;
    }

    @Test
    void incompressibleImageSpansSeveralIdatChunks() {
        // Deflated output is emitted in bounded chunks; noise defeats compression, so this crosses
        // the boundary. A reader that assumed one IDAT would fail here, and so would a chunk loop
        // that lost bytes at the seam.
        Random random = new Random(20260805L);
        byte[] noise = new byte[256 * 256 * 4];
        random.nextBytes(noise);
        Image source = new Image(256, 256, noise);

        byte[] file = Images.encode(source, ImageFormat.PNG);
        assertTrue(countChunks(file, "IDAT") > 1, "expected the payload to span several IDAT chunks");
        assertArrayEquals(noise, TestPngReader.decode(file).pixels());
    }

    @Test
    void savesToDiskCreatingMissingDirectories(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested/deeper/shot.png");
        Image source = gradient(8, 8);

        Images.save(source, ImageFormat.PNG, file);

        assertArrayEquals(source.pixels(), TestPngReader.decode(Files.readAllBytes(file)).pixels());
    }

    @Test
    void failureNamesEveryEncoderAskedInOrder() {
        Images.uninstallAllEncoders();
        Images.installEncoder(declining("first"));
        Images.installEncoder(declining("second"));

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> Images.encode(gradient(2, 2), new ImageFormat("image/webp")));

        assertTrue(failure.getMessage().contains("image/webp"), failure.getMessage());
        assertTrue(failure.getMessage().contains("first, second"),
                "the message must name every encoder asked, in probe order: " + failure.getMessage());
    }

    @Test
    void failureSaysSoWhenNothingIsInstalledAtAll() {
        Images.uninstallAllEncoders();

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> Images.encode(gradient(2, 2), ImageFormat.PNG));

        assertTrue(failure.getMessage().contains("none installed"), failure.getMessage());
    }

    @Test
    void firstInstalledEncoderWins() throws IOException {
        Images.uninstallAllEncoders();
        Images.installEncoder(claiming("early", (byte) 1));
        Images.installEncoder(claiming("late", (byte) 2));

        assertArrayEquals(new byte[]{1}, Images.encode(gradient(2, 2), ImageFormat.PNG));
    }

    @Test
    void mediaTypeIsTheIdentityAndIsNormalised() {
        assertEquals(ImageFormat.PNG, new ImageFormat("  IMAGE/PNG "));
        assertThrows(IllegalArgumentException.class, () -> new ImageFormat("png"));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageEncodeOptions(ImageFormat.PNG, 0));
    }

    /** Opaque-to-transparent ramp over a colour gradient: every channel varies. */
    private static Image gradient(int width, int height) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                pixels[at] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[at + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[at + 2] = (byte) ((x + y) & 0xFF);
                pixels[at + 3] = (byte) (255 - (x * 255 / Math.max(1, width - 1)));
            }
        }
        return new Image(width, height, pixels);
    }

    private static ImageEncoder declining(String name) {
        return new ImageEncoder() {
            @Override public String name() {
                return name;
            }

            @Override public boolean supports(ImageEncodeOptions options) {
                return false;
            }

            @Override public void encode(Image image, ImageEncodeOptions options, OutputStream out) {
                throw new AssertionError("must not be reached");
            }
        };
    }

    private static ImageEncoder claiming(String name, byte marker) {
        return new ImageEncoder() {
            @Override public String name() {
                return name;
            }

            @Override public boolean supports(ImageEncodeOptions options) {
                return true;
            }

            @Override public void encode(Image image, ImageEncodeOptions options, OutputStream out)
                    throws IOException {
                out.write(marker);
            }
        };
    }

    private static int countChunks(byte[] file, String type) {
        int found = 0;
        for (int at = 8; at + 8 <= file.length; ) {
            int length = ((file[at] & 0xFF) << 24) | ((file[at + 1] & 0xFF) << 16)
                    | ((file[at + 2] & 0xFF) << 8) | (file[at + 3] & 0xFF);
            if (type.equals(new String(file, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
                found++;
            }
            at += 12 + length;
        }
        return found;
    }
}
