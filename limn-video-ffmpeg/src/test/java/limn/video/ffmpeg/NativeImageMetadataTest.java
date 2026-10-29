package limn.video.ffmpeg;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What limn-video-ffmpeg's jar tells GraalVM's native image: the two classes the JNI shim looks up
 * by name, the exception it throws and the String arrays it builds for track names.
 */
class NativeImageMetadataTest {

    @Test
    void theShimsLookupsAreNamedAndExist() throws Exception {
        String metadata;
        try (InputStream in = FfmpegException.class.getResourceAsStream(
                "/META-INF/native-image/io.github.limn-toolkit/limn-video-ffmpeg/reachability-metadata.json")) {
            assertNotNull(in, "the metadata is not in the jar");
            metadata = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(metadata.contains("\"limn.video.ffmpeg.FfmpegException\""), metadata);
        assertTrue(metadata.contains("\"java.lang.String\""), metadata);
        // ThrowNew calls the one-argument constructor.
        assertDoesNotThrow(() -> FfmpegException.class.getConstructor(String.class));
    }
}
