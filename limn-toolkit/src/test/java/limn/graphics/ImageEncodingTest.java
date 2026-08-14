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
    }

    @Test
    void pngIsEncodableWithoutABackend() {
        assertFalse(Images.isDecoderInstalled(), "this suite runs with no backend");
        assertTrue(Images.canEncode(ImageFormat.PNG));
        assertEquals(1, Images.installedEncoders().size());
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
