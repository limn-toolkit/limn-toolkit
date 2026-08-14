package limn.demo;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.RadialGradient;
import limn.graphics.RoundRect;

/**
 * "Test card": every Canvas capability on one 800x640 screen, arranged so
 * screenshots at scales 1.0/1.25/1.5/2.0 expose AA quality, pixel snapping of
 * hairlines, gradient smoothness, clipping and transform correctness.
 * Deliberately text-free.
 */
final class TestCard {

    private static final Color BACKGROUND = Color.rgb(0x14181F);
    private static final Color HAIRLINE = Color.rgb(0xE8ECF2);

    private TestCard() {
    }

    static void paint(Canvas canvas) {
        canvas.clear(BACKGROUND);

        row1RectsAndRoundRects(canvas);
        row2CirclesEllipsesLines(canvas);
        row3GradientsAndAlpha(canvas);
        row4TransformsClipPaths(canvas);

        // Full-width 1px hairlines hugging the frame: the snapping acid test.
        canvas.drawLine(10, 630, 790, 630, 1, HAIRLINE);
        canvas.drawRect(4.5f, 4.5f, 791, 631, 1, Color.rgba(0x8899AA, 0.9f));
    }

    private static void row1RectsAndRoundRects(Canvas c) {
        c.fillRect(20, 20, 100, 100, Color.rgb(0x4C8DFF));
        c.drawRect(140, 20, 100, 100, 1, HAIRLINE);                       // 1px: must stay crisp
        c.drawRect(260, 20, 100, 100, 2, Color.rgb(0xFFB454));
        c.fillRoundRect(380, 20, 100, 100, 16, Color.rgb(0x34D399));
        c.drawRoundRect(500, 20, 100, 100, 16, 2, Color.rgb(0xF472B6));
        c.fillRoundRect(new RoundRect(620, 20, 100, 100, 0, 12, 48, 24),  // per-corner radii
                Color.rgb(0xA78BFA));
        c.drawRoundRect(740, 20, 40, 100, 8, 1, Color.rgb(0x94A3B8));     // 1px rounded hairline
    }

    private static void row2CirclesEllipsesLines(Canvas c) {
        c.fillCircle(70, 210, 50, Color.rgb(0x38BDF8));
        c.drawCircle(190, 210, 50, 3, Color.rgb(0xFBBF24));
        c.fillEllipse(310, 210, 60, 35, Color.rgb(0xF87171));
        c.drawEllipse(430, 210, 60, 35, 2, Color.rgb(0x4ADE80));

        // Horizontal lines, widths 1..4 (odd widths sit on half-pixels).
        for (int i = 0; i < 4; i++) {
            c.drawLine(510, 170 + i * 18, 610, 170 + i * 18, i + 1, HAIRLINE);
        }
        // Vertical 1px and 2px.
        c.drawLine(630, 160, 630, 260, 1, HAIRLINE);
        c.drawLine(645, 160, 645, 260, 2, Color.rgb(0xFFB454));
        // Diagonals: AA quality check (no snapping applies).
        c.drawLine(665, 260, 730, 160, 1, HAIRLINE);
        c.drawLine(690, 260, 755, 160, 3, Color.rgb(0x22D3EE));
    }

    private static void row3GradientsAndAlpha(Canvas c) {
        c.fillRect(20, 300, 160, 100,
                new LinearGradient(20, 0, 180, 0, Color.rgb(0x4C8DFF), Color.rgb(0xF472B6)));
        c.fillRoundRect(200, 300, 160, 100, 12,
                new LinearGradient(200, 300, 360, 400, Color.rgb(0x34D399), Color.rgb(0x0EA5E9)));
        c.fillCircle(440, 350, 52,
                new RadialGradient(440, 350, 52, Color.WHITE, Color.rgb(0x7C3AED)));

        // Translucent venn: premultiplied blending correctness.
        c.fillCircle(560, 335, 34, Color.rgba(0xEF4444, 0.55f));
        c.fillCircle(590, 368, 34, Color.rgba(0x22C55E, 0.55f));
        c.fillCircle(620, 335, 34, Color.rgba(0x3B82F6, 0.55f));

        // Same color at state opacities 1.0 / 0.6 / 0.3.
        float[] opacities = {1.0f, 0.6f, 0.3f};
        for (int i = 0; i < opacities.length; i++) {
            c.save();
            c.setOpacity(opacities[i]);
            c.fillRoundRect(686, 300 + i * 36, 100, 28, 6, Color.rgb(0xFF6B6B));
            c.restore();
        }
    }

    private static void row4TransformsClipPaths(Canvas c) {
        // Rotated shapes (snapping correctly disabled under rotation).
        c.save();
        c.translate(70, 525);
        c.rotate((float) Math.toRadians(15));
        c.fillRoundRect(-40, -40, 80, 80, 10, Color.rgb(0xF59E0B));
        c.restore();

        c.save();
        c.translate(170, 525);
        c.rotate((float) Math.toRadians(40));
        c.drawRoundRect(-38, -38, 76, 76, 10, 2, Color.rgb(0x38BDF8));
        c.restore();

        // Clip: gradient panel with circles overflowing the rounded clip.
        c.save();
        c.clipRoundRect(RoundRect.of(240, 445, 160, 120, 22));
        c.fillRect(240, 445, 160, 120,
                new LinearGradient(240, 445, 400, 565, Color.rgb(0x1E293B), Color.rgb(0x475569)));
        c.fillCircle(400, 470, 34, Color.rgb(0xFACC15)); // must be cut by the clip
        c.fillCircle(250, 555, 30, Color.rgb(0xF472B6)); // cut at the rounded corner
        c.restore();

        // Star: concave polygon fill through the triangulator.
        c.fillPath(star(490, 505, 55, 22, 5), Color.rgb(0xFACC15));

        // Stroked paths: zigzag polyline + closed triangle (round caps/joins).
        Path2D zigzag = new Path2D().moveTo(570, 450);
        for (int i = 1; i <= 5; i++) {
            zigzag.lineTo(570 + i * 26, (i % 2 == 1) ? 490 : 450);
        }
        c.drawPath(zigzag, 3, Color.rgb(0x22D3EE));

        Path2D triangle = new Path2D().moveTo(575, 520).lineTo(655, 520).lineTo(615, 590).close();
        c.drawPath(triangle, 2, Color.rgb(0xF472B6));

        // Bézier wave: quad + cubic flattening quality.
        Path2D wave = new Path2D().moveTo(20, 590)
                .quadTo(65, 540, 110, 590)
                .cubicTo(140, 623, 170, 557, 200, 590);
        c.drawPath(wave, 2, Color.rgb(0xA3E635));

        // Scaled subtree: 1.5x, fractional-scale rendering via transform.
        c.save();
        c.translate(690, 445);
        c.scale(1.5f, 1.5f);
        c.fillRoundRect(0, 0, 60, 60, 8, Color.rgb(0x64748B));
        c.drawRect(0, 0, 60, 60, 1, HAIRLINE);
        c.restore();
    }

    private static Path2D star(float cx, float cy, float outer, float inner, int points) {
        Path2D path = new Path2D();
        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI * i / points - Math.PI / 2;
            float r = (i % 2 == 0) ? outer : inner;
            float x = cx + (float) (Math.cos(angle) * r);
            float y = cy + (float) (Math.sin(angle) * r);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path.close();
    }
}
