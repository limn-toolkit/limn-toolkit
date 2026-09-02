package limn.backend.lwjgl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Noto faces on this suite's classpath — the limn-fonts artifacts' resources, since ADR 036
 * moved them out of this module — are the builds this suite was written against, byte for byte.
 *
 * <p>They are parsed by stb, which is C, so what they are is worth more than a comment saying what
 * they should be. The faces now version apart from this repository, and that is exactly why this
 * test exists on this side of the split: bumping a font dependency in the version catalog is a
 * one-line diff that swaps megabytes of binary nobody reviews, and this is what makes that diff
 * fail until the digests here — and the layout facts {@code ComplexScriptFallbackTest} pins —
 * are re-verified against the new build. The pins themselves live in the limn-fonts repository's
 * {@code scripts/fetch-fonts.sh}; these digests are the two repositories' agreement.
 *
 * <p>The four script faces are pinned for a second reason on top of that one. What they are asked
 * for is not coverage but <em>layout</em> — the conjuncts, the reordering and the contextual forms
 * pinned in {@code ComplexScriptFallbackTest} are properties of a particular build's GSUB, not of
 * the script — so a different Noto Sans Arabic would be a face that renders and a suite that lies.
 */
class VendoredFontsTest {

    @Test
    void theShippedNotoFacesAreTheOnesTheFetchScriptPins() throws Exception {
        assertPinned("NotoSansCJK-Regular.otf",
                "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5");
        assertPinned("NotoColorEmoji.ttf",
                "72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b");
        assertPinned("NotoSansArabic-Regular.ttf",
                "bdff3e5659d67e67def05b33f749683b9376ae819d65d3dd62ac4640b3aaef48");
        assertPinned("NotoSansHebrew-Regular.ttf",
                "cdefaf8efd47045f6820928eba84db5bed7557539328952b5f828315485e02ee");
        assertPinned("NotoSansDevanagari-Regular.ttf",
                "306b53ecfb182a504dd8a7446093c316387d2fd8dc350d0792ed1753fe0996cd");
        assertPinned("NotoSansThai-Regular.ttf",
                "61cf814eec46b294d6ea4401ac295d0cecd5207bd2331dcc5a15e7301d30ee44");
        assertPinned("NotoSansArabic-Bold.ttf",
                "4e5462d2e8be880317b9f49b5b2da109ddb6a3563d91cc604b67f3535832a555");
        assertPinned("NotoSansHebrew-Bold.ttf",
                "da9226e886c245a7e11673c24dec82bded64d8574c1e1f03983bf89297d2aaa8");
        assertPinned("NotoSansDevanagari-Bold.ttf",
                "3ad8362a06271814869838dcc3d161b13c9fb97681b627af1f7f283ea9387d56");
        assertPinned("NotoSansThai-Bold.ttf",
                "2ac6c6e8a478e23b15f76e4894af1fa2210f8f350e4e6e54aad530bec03efbfb");
    }

    private static void assertPinned(String file, String sha256) throws Exception {
        byte[] bytes;
        try (InputStream in = VendoredFontsTest.class.getResourceAsStream(
                "/limn/fonts/" + file)) {
            // The faces are optional the way the GL context and the FFmpeg native are: absent, the
            // toolkit falls back to Roboto, so a build resolving offline still runs green.
            Assumptions.assumeTrue(in != null, file + " is optional");
            bytes = in.readAllBytes();
        }
        assertEquals(sha256, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)),
                file + " is not the build this suite's layout facts were verified against");
    }
}
