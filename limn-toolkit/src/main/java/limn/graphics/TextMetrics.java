package limn.graphics;

/**
 * Measured extents of a single line of text, in logical points, relative to
 * the baseline. Values are unquantized (independent of the monitor content
 * scale), so layout decisions match what {@code drawText} renders at any
 * HiDPI factor.
 *
 * @param width      advance width of the whole string
 * @param ascent     distance from baseline to the font's ascender (positive up)
 * @param descent    distance from baseline to the descender (positive down)
 * @param lineHeight recommended baseline-to-baseline distance
 *                   (ascent + descent + line gap)
 */
public record TextMetrics(float width, float ascent, float descent, float lineHeight) {

    /** @return total height of the line box (ascent + descent) */
    public float height() {
        return ascent + descent;
    }
}
