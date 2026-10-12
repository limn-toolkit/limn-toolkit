package limn.video.decode;

import limn.io.MalformedContentException;
import limn.testfixtures.MutationFuzz;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Y4M file that is broken is refused, as it is opened or as a picture is read, with a
 * {@link MalformedContentException}, or with an {@link UnsupportedOperationException} where its
 * header names a layout the reader does not decode, and with nothing else.
 */
class Y4mFuzzTest {

    @TempDir
    Path directory;

    private static byte[] seed(String header, int pictureBytes, int pictures) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        for (int p = 0; p < pictures; p++) {
            out.writeBytes("FRAME\n".getBytes(StandardCharsets.US_ASCII));
            byte[] picture = new byte[pictureBytes];
            for (int i = 0; i < picture.length; i++) {
                picture[i] = (byte) (i * 7 + p);
            }
            out.writeBytes(picture);
        }
        return out.toByteArray();
    }

    @Test
    void onlyADocumentedRefusalEverEscapes() {
        List<byte[]> seeds = List.of(
                seed("YUV4MPEG2 W4 H4 F30:1 Ip A1:1 C420\n", 24, 3),
                seed("YUV4MPEG2 W8 H2 F25:1 C444 XCOLORRANGE=FULL\n", 48, 2),
                seed("YUV4MPEG2 W2 H2 F30000:1001 C422\n", 8, 4));
        Path file = directory.resolve("fuzz.y4m");
        MutationFuzz.Result result = MutationFuzz.run(42, 4000, seeds, data -> {
            Files.write(file, data);
            try (VideoStreamSource source = new Y4mDecoder().openStream(file)) {
                for (int i = 0; i < 6 && source.readFrame() != VideoStreamSource.Read.END; i++) {
                    source.width();
                }
            }
        }, thrown -> thrown.getClass() == MalformedContentException.class
                || thrown.getClass() == UnsupportedOperationException.class, "limn.video.");
        assertEquals(Map.of(), result.escapes(), "a broken Y4M escaped the reader as something it does not document");
        assertTrue(result.read() > 0 && result.refused() > 0, "the mutations test nothing: "
                + result.read() + " read, " + result.refused() + " refused");
    }
}
