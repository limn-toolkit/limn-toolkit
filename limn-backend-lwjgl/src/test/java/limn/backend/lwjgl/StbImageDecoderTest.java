package limn.backend.lwjgl;

import limn.graphics.Image;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stb_image decoder reads an image's header before it decodes it, and refuses one larger than
 * {@link StbImageDecoder#MAX_PIXELS}: a file of a kilobyte whose header claimed 23000&times;23000
 * decoded to 2 GB, and past about 23170&sup2; the RGBA array's size overflowed an {@code int}.
 */
class StbImageDecoderTest {

    /**
     * A JPEG, because stb reads one up to 65535 on a side where its PNG reader stops at 2<sup>30</sup>
     * bytes of pixels: the header claims 23000&times;23000 and nothing else is there.
     */
    @Test
    void anImageWhoseHeaderClaimsTooManyPixelsIsRefusedBeforeItIsDecoded() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new StbImageDecoder().decode(jpegHeader(23000, 23000)));
        assertTrue(refused.getMessage().contains("23000x23000"), refused.getMessage());
        refused = assertThrows(IllegalArgumentException.class,
                () -> new StbImageDecoder().decode(jpegHeader(23171, 23171)));
        assertTrue(refused.getMessage().contains("23171x23171"),
                "and one whose RGBA size overflows an int: " + refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new StbImageDecoder().decode(png(23000, 23000, new byte[0])),
                "a PNG as large is refused too");
    }

    /** A JPEG's start and its baseline frame header, three components: what stb reads for the size. */
    private static byte[] jpegHeader(int width, int height) {
        return ByteBuffer.allocate(21)
                .put((byte) 0xFF).put((byte) 0xD8) // SOI
                .put((byte) 0xFF).put((byte) 0xC0).putShort((short) 17) // SOF0, length
                .put((byte) 8).putShort((short) height).putShort((short) width).put((byte) 3)
                .put(new byte[] {1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0})
                .array();
    }

    @Test
    void anOrdinaryImageStillDecodes() {
        byte[] row = {0, (byte) 0xFF, 0x00, 0x00, (byte) 0xFF}; // filter 0, one red opaque pixel
        Image image = new StbImageDecoder().decode(png(1, 1, row));
        assertEquals(1, image.width());
        assertEquals(1, image.height());
    }

    /** A PNG of RGBA 8-bit rows, {@code raw} being the filtered scanlines before compression. */
    private static byte[] png(int width, int height, byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buffer = new byte[256];
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            idat.write(buffer, 0, deflater.deflate(buffer));
        }
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
