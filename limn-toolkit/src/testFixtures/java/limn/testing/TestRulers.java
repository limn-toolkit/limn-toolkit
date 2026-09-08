package limn.testing;

import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;

/**
 * The text rulers headless tests lay out against, so that a layout measured on one machine is
 * the layout measured on every other.
 */
public final class TestRulers {

    private TestRulers() {
    }

    /**
     * Ten points per code point whatever the font, ascent eight, descent two, line height twelve:
     * ellipsis math and every width assertion become exact integers. Seven test classes across
     * four modules declared exactly this lambda; a transcript taken under one of them has to be
     * the transcript taken under the others.
     */
    public static final TextRuler FIXED = (text, font) ->
            new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);

    /**
     * A ruler that scales with the font, for control-size assertions. It uses the embedded
     * Roboto's real vertical ratios (em 2048, ascent 1900, descent -500, line gap 0): ascent
     * 0.927734em, descent 0.244141em, line height 1.171875em, with a flat 0.6em advance per code
     * point. {@link #FIXED} is font-blind, so a MEDIUM control measured under it was never the
     * shipped MEDIUM; every "MEDIUM is the identity" baseline has to be captured under this one.
     */
    public static final TextRuler SCALED = (text, font) -> {
        float s = font.size();
        return new TextMetrics(0.6f * s * (int) text.codePoints().count(),
                0.927734375f * s, 0.244140625f * s, 1.171875f * s);
    };
}
