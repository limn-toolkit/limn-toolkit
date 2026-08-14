package limn.demo;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.LinearGradient;
import limn.graphics.TextMetrics;

/**
 * M3 text specimens: size ladder, metrics/baseline validation box, kerning
 * pairs, Latin-extended/Cyrillic/Greek coverage, gradient/translucent text,
 * rotated and transform-scaled text. Rendered at --scale 1.0/1.25/1.5/2.0 the
 * glyphs must stay sharp at every size: bitmaps are rasterized per physical
 * size, never scaled.
 */
final class TextScene {

    private static final Color BACKGROUND = Color.rgb(0x14181F);
    private static final Color INK = Color.rgb(0xE8ECF2);
    private static final Color DIM = Color.rgb(0x8A94A6);
    private static final Color ACCENT = Color.rgb(0x4C8DFF);
    private static final Color BASELINE = Color.rgba(0xEF4444, 0.9f);

    private TextScene() {
    }

    static void paint(Canvas c) {
        c.clear(BACKGROUND);

        // --- Size ladder (left column) -----------------------------------
        float x = 24;
        float y = 40;
        float[] sizes = {10, 11, 12, 14, 16, 20, 24, 32, 44};
        for (float size : sizes) {
            Font font = Font.of(size);
            // Larger sizes get a shorter specimen so the ladder stays in its column.
            String specimen = size >= 28
                    ? (int) size + "px: Quick brown fox Wgy"
                    : (int) size + "px: Quick brown fox jumps Wgjpqy 0123";
            c.drawText(specimen, x, y, font, INK);
            y += c.measureText("X", font).lineHeight() + 4;
        }

        // --- Metrics box: stroke must hug the measured text ---------------
        Font metricsFont = Font.of(26);
        String measured = "Measured: Wgjpqy box";
        TextMetrics m = c.measureText(measured, metricsFont);
        float mx = 24;
        float my = y + 30;
        c.drawRect(mx, my - m.ascent(), m.width(), m.height(), 1, ACCENT);
        c.drawLine(mx - 12, my, mx + m.width() + 12, my, 1, BASELINE); // baseline
        c.drawText(measured, mx, my, metricsFont, INK);

        // --- Kerning pairs -------------------------------------------------
        c.drawText("Kerning: AVATAR WAVE To Yo P.A. L'To", 24, my + 50, Font.of(22), INK);

        // --- Unicode coverage (Latin ext, Cyrillic, Greek, punctuation) ---
        c.drawText("Olá João! Größe Żółć Привет, мир Ελληνικά «•·»", 24, my + 84, Font.of(17), INK);

        // --- Right column --------------------------------------------------
        float rx = 470;
        // Gradient-painted text
        Font gradFont = Font.of(40);
        String gradText = "Gradient";
        float gw = c.measureText(gradText, gradFont).width();
        c.drawText(gradText, rx, 70, gradFont,
                new LinearGradient(rx, 0, rx + gw, 0, Color.rgb(0x4C8DFF), Color.rgb(0xF472B6)));

        // Translucency ladder
        float oy = 110;
        for (float alpha : new float[] {1.0f, 0.6f, 0.3f}) {
            c.drawText("Opacity " + alpha, rx, oy, Font.of(20), INK.withAlpha(alpha));
            oy += 28;
        }

        // Transform-scaled text: 16px under scale(1.5) must be as sharp as
        // a native 24px run (glyphs re-rasterized at the transformed size).
        c.drawText("24px native, sharp", rx, 240, Font.of(24), INK);
        c.save();
        c.translate(rx, 274);
        c.scale(1.5f, 1.5f);
        c.drawText("16px × scale 1.5", 0, 0, Font.of(16), INK);
        c.restore();

        // Rotated text (no snapping under rotation, still legible)
        c.save();
        c.translate(rx + 40, 400);
        c.rotate((float) Math.toRadians(-12));
        c.drawText("Rotated 18px", 0, 0, Font.of(18), ACCENT);
        c.restore();

        // --- Paragraph with manual line breaking via lineHeight -----------
        Font body = Font.of(14);
        float lineHeight = c.measureText("X", body).lineHeight();
        String[] lines = {
                "Sample paragraph at 14px: each line is positioned by the",
                "font's metric lineHeight, and the glyph atlas is indexed",
                "by the physical pixel size, never by scaled bitmaps.",
        };
        float py = 430;
        for (String line : lines) {
            c.drawText(line, 24, py, body, DIM);
            py += lineHeight;
        }

        // --- Small-size legibility + baseline hairline ---------------------
        c.drawText("9px: the smallest legible size in the specimen", 24, py + 24, Font.of(9), DIM);
        c.drawText("8px, lower bound (expected: legible, no blur)", 24, py + 42, Font.of(8), DIM);

        c.drawLine(10, 620, 790, 620, 1, DIM.withAlpha(0.5f));
        c.drawText("Limn UI · stb_truetype + glyph atlas (Roboto, Apache 2.0)", 24, 608,
                Font.of(12), DIM);
    }
}
