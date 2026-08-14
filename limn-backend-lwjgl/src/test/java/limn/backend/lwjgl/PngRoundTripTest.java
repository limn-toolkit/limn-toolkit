package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The toolkit's PNG encoder against a decoder that shares nothing with it: stb_image, a native C
 * implementation this project only consumes. The toolkit's own round-trip tests read the file back
 * with a reader written for the tests, so they can only prove self-consistency; this one proves the
 * file is a PNG by anyone else's reckoning.
 *
 * <p>No GL context and no window: stb_image is pure CPU, as is the encoder.
 */
class PngRoundTripTest {

    @Test
    void stbImageReadsBackWhatTheEncoderWrote() {
        Image source = checkerboard(19, 11);

        byte[] file = Images.encode(source, ImageFormat.PNG);
        Image decoded = new StbImageDecoder().decode(file);

        assertEquals(source.width(), decoded.width());
        assertEquals(source.height(), decoded.height());
        assertArrayEquals(source.pixels(), decoded.pixels());
    }

    @Test
    void stbImageAgreesOnRowOrder() {
        // Both sides claim row 0 is the top one. Odd dimensions and a single marked corner make a
        // flip on either side impossible to miss.
        Image source = checkerboard(7, 5);
        byte[] marked = source.pixels().clone();
        marked[0] = (byte) 0xFF;
        marked[1] = 0;
        marked[2] = 0;
        marked[3] = (byte) 0xFF;

        Image decoded = new StbImageDecoder()
                .decode(Images.encode(new Image(7, 5, marked), ImageFormat.PNG));

        assertEquals((byte) 0xFF, decoded.pixels()[0], "the marked pixel stays in the top-left");
        assertEquals(0, decoded.pixels()[1]);
    }

    private static Image checkerboard(int width, int height) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                boolean dark = ((x / 3) + (y / 3)) % 2 == 0;
                pixels[at] = (byte) (dark ? 0x20 : 0xE0);
                pixels[at + 1] = (byte) (x * 13);
                pixels[at + 2] = (byte) (y * 21);
                pixels[at + 3] = (byte) (dark ? 0xFF : 0x90);
            }
        }
        return new Image(width, height, pixels);
    }
}
