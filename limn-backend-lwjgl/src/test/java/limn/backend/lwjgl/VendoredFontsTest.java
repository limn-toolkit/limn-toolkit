package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two Noto faces that ship in this module are the ones {@code scripts/fetch-fonts.sh} pins,
 * byte for byte.
 *
 * <p>They are parsed by stb, which is C, so what they are is worth more than a comment saying what
 * they should be: a hand-placed or re-downloaded face that is not the pinned build would otherwise
 * reach a release without anything noticing. Moving a pin means changing the commit and the digest
 * in that script and the digest here together, which is the point, because those are the two
 * places that must agree.
 */
class VendoredFontsTest {

    @Test
    void theShippedNotoFacesAreTheOnesTheFetchScriptPins() throws Exception {
        assertPinned("NotoSansCJK-Regular.otf",
                "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5");
        assertPinned("NotoColorEmoji.ttf",
                "72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b");
    }

    private static void assertPinned(String file, String sha256) throws Exception {
        byte[] bytes;
        try (InputStream in = VendoredFontsTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/" + file)) {
            // The faces are optional the way the GL context and the FFmpeg native are: absent, the
            // toolkit falls back to Roboto, so a checkout without them still builds green.
            Assumptions.assumeTrue(in != null, file + " is optional");
            bytes = in.readAllBytes();
        }
        assertEquals(sha256, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)),
                file + " is not the build scripts/fetch-fonts.sh pins");
    }
}
