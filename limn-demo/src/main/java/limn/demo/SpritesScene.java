package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Sampling;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

/**
 * Sprite showcase of the 2D game-rendering primitives, all procedural (no asset
 * files): a sprite-sheet animation driven by source-rect
 * {@link Canvas#drawImage(Image, float, float, float, float, float, float, float, float)},
 * {@link Sampling#PIXELATED} vs {@link Sampling#SMOOTH} scaling for pixel art,
 * {@link BlendMode} additive/multiply compositing, and sub-pixel motion with
 * {@link Canvas#setPixelSnap} disabled.
 */
final class SpritesScene {

    private SpritesScene() {
    }

    /** Standalone {@code --scene sprites}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        column.add(heading("Sprite sheet (source-rect drawImage)"));
        column.add(new Label("Eight coin frames packed in ONE image; each sprite is "
                + "drawImage(sheet, srcX, srcY, srcW, srcH, dst…[, tint]): one texture, one batch "
                + "for any number of sprites. The highlight marks the frame being played.")
                .setMuted(true).setWrap(true));
        column.add(new SheetPlayer());

        column.add(heading("Pixel art: SMOOTH vs PIXELATED"));
        column.add(new Label("The same 16×16 image scaled 6×: setSampling(PIXELATED) keeps "
                + "the blocks crisp; SMOOTH (the default) bilinearly blurs them.")
                .setMuted(true).setWrap(true));
        column.add(new FilterCompare());

        column.add(heading("Blend modes"));
        column.add(new Label("Three overlapping glows per panel: NORMAL paints over, "
                + "ADDITIVE accumulates light (particles, lasers), MULTIPLY darkens "
                + "(shadows, vignettes). setBlendMode applies to shapes, text and images.")
                .setMuted(true).setWrap(true));
        column.add(new BlendPanels());

        column.add(heading("Sub-pixel motion (setPixelSnap)"));
        column.add(new Label("Both strips scroll at the same slow speed. The top one keeps "
                + "the default pixel snapping, so it advances in whole-pixel steps. The bottom "
                + "one disables it: sub-pixel positions filter smoothly, no stutter.")
                .setMuted(true).setWrap(true));
        column.add(new ScrollCompare());

        return new ScrollView(column);
    }

    private static Label heading(String text) {
        return new Label(text).setFont(Theme.current().title);
    }

    // ------------------------------------------------- procedural sprite art

    private static final int COIN = 32;   // coin cell edge
    private static final int FRAMES = 8;  // sheet columns

    /** 8-frame spinning-coin sheet in one row: width oscillates, back face darker. */
    static Image coinSheet() {
        byte[] px = new byte[FRAMES * COIN * COIN * 4];
        for (int f = 0; f < FRAMES; f++) {
            double phase = Math.cos(f * Math.PI * 2 / FRAMES);
            float halfW = Math.max(2.5f, (float) Math.abs(phase) * 13f);
            boolean front = phase >= 0;
            int cx = f * COIN + COIN / 2;
            int cy = COIN / 2;
            for (int y = 0; y < COIN; y++) {
                for (int x = f * COIN; x < (f + 1) * COIN; x++) {
                    float nx = (x - cx + 0.5f) / halfW;
                    float ny = (y - cy + 0.5f) / 13f;
                    float d = nx * nx + ny * ny;
                    if (d <= 1f) {
                        boolean rim = d > 0.68f;
                        int r = front ? (rim ? 0xB8 : 0xF5) : (rim ? 0x6E : 0x9A);
                        int g = front ? (rim ? 0x86 : 0xC9) : (rim ? 0x4E : 0x71);
                        int b = front ? (rim ? 0x1E : 0x37) : (rim ? 0x14 : 0x1F);
                        set(px, FRAMES * COIN, x, y, r, g, b, 255);
                    }
                }
            }
        }
        return new Image(FRAMES * COIN, COIN, px);
    }

    /** Classic 16×16 invader from a bitmask, deliberately chunky pixel art. */
    static Image invader() {
        int[] rows = {
                0b0000011001100000,
                0b0000111111110000,
                0b0001111111111000,
                0b0011001111001100,
                0b0011111111111100,
                0b0000110000110000,
                0b0001101111011000,
                0b0110000000000110,
        };
        byte[] px = new byte[16 * 16 * 4];
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < 16; c++) {
                if ((rows[r] & (1 << (15 - c))) != 0) {
                    set(px, 16, c, 4 + r, 0x53, 0xD7, 0x6E, 255); // green body
                }
            }
        }
        set(px, 16, 5, 7, 0x10, 0x2A, 0x16, 255); // eyes
        set(px, 16, 10, 7, 0x10, 0x2A, 0x16, 255);
        return new Image(16, 16, px);
    }

    private static void set(byte[] px, int stride, int x, int y, int r, int g, int b, int a) {
        int i = (y * stride + x) * 4;
        px[i] = (byte) r;
        px[i + 1] = (byte) g;
        px[i + 2] = (byte) b;
        px[i + 3] = (byte) a;
    }

    /** Arms a scene ticker on first paint; pauses automatically when hidden. */
    private abstract static class Animated extends Widget {
        double time;
        private boolean started;

        void armTicker() {
            if (!started && scene() != null) {
                started = true;
                scene().addTicker(dt -> {
                    if (!isShowing()) {
                        started = false; // re-armed by the next onPaint when shown
                        return false;
                    }
                    time += dt;
                    invalidate();
                    return true;
                });
            }
        }
    }

    // ------------------------------------------------------ section widgets

    /** The sheet with the playing frame highlighted, plus the sprite at 4×. */
    private static final class SheetPlayer extends Animated {
        private final Image sheet = coinSheet();

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 96);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            int frame = (int) (time * 10) % FRAMES;

            // The whole sheet at 2×, pixelated so cells read clearly.
            canvas.save();
            canvas.setSampling(Sampling.PIXELATED);
            float sheetY = (height() - COIN * 2) / 2;
            canvas.drawImage(sheet, 16, sheetY, FRAMES * COIN * 2, COIN * 2);
            canvas.drawRect(16 + frame * COIN * 2, sheetY, COIN * 2, COIN * 2,
                    2, theme.primary);
            // The played sprite at 3× (fits the 96px widget; drawing outside
            // the bounds would leave stale pixels under partial rendering):
            // ONE cell of the sheet via source rect.
            canvas.drawImage(sheet, frame * COIN, 0, COIN, COIN,
                    width() - COIN * 3 - 24, (height() - COIN * 3) / 2, COIN * 3, COIN * 3);
            // The same cell, tinted: the sprite primitive and the tint in one call, so a
            // sheet does not have to be split into one Image per cell to be modulated;
            // splitting it is what breaks the batching a sheet exists for.
            canvas.drawImage(sheet, frame * COIN, 0, COIN, COIN,
                    width() - COIN * 5 - 40, (height() - COIN * 2) / 2, COIN * 2, COIN * 2,
                    new Color(0.45f, 0.75f, 1f, 0.9f));
            canvas.restore();
        }
    }

    /** The invader at 6×, smooth on the left, pixelated on the right. */
    private static final class FilterCompare extends Widget {
        private final Image sprite = invader();

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 128);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            float size = 96;
            float y = (height() - size) / 2;
            float leftX = width() / 2 - size - 40;
            float rightX = width() / 2 + 40;
            canvas.drawImage(sprite, leftX, y, size, size); // SMOOTH default
            canvas.save();
            canvas.setSampling(Sampling.PIXELATED);
            canvas.drawImage(sprite, rightX, y, size, size);
            canvas.restore();
            var font = theme.body;
            var m1 = textRuler().measure("SMOOTH", font);
            canvas.drawText("SMOOTH", leftX + (size - m1.width()) / 2,
                    y + size + m1.ascent() - 4, font, theme.textMuted);
            var m2 = textRuler().measure("PIXELATED", font);
            canvas.drawText("PIXELATED", rightX + (size - m2.width()) / 2,
                    y + size + m2.ascent() - 4, font, theme.textMuted);
        }
    }

    /** Three panels, same three orbiting glows: only the blend mode differs. */
    private static final class BlendPanels extends Animated {
        private static final Color[] GLOWS = {
                new Color(1f, 0.30f, 0.25f, 0.55f),
                new Color(0.25f, 0.95f, 0.45f, 0.55f),
                new Color(0.30f, 0.45f, 1f, 0.55f),
        };

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 150);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.current();
            float panelW = (width() - 2 * 12) / 3;
            String[] names = {"NORMAL", "ADDITIVE", "MULTIPLY"};
            BlendMode[] modes = {BlendMode.NORMAL, BlendMode.ADDITIVE, BlendMode.MULTIPLY};
            for (int p = 0; p < 3; p++) {
                float x = p * (panelW + 12);
                // MULTIPLY needs a light backdrop to show darkening.
                Color backdrop = modes[p] == BlendMode.MULTIPLY
                        ? new Color(0.92f, 0.92f, 0.95f, 1)
                        : new Color(0.07f, 0.07f, 0.10f, 1);
                canvas.fillRoundRect(x, 0, panelW, 120, theme.tokensFor(this).radiusMedium(), backdrop);
                canvas.save();
                canvas.clipRect(x, 0, panelW, 120);
                canvas.setBlendMode(modes[p]);
                for (int i = 0; i < GLOWS.length; i++) {
                    double a = time * 0.9 + i * Math.PI * 2 / 3;
                    float gx = x + panelW / 2 + (float) Math.cos(a) * 18;
                    float gy = 60 + (float) Math.sin(a) * 14;
                    canvas.fillCircle(gx, gy, 34, GLOWS[i]);
                }
                canvas.restore();
                var font = theme.body;
                var m = textRuler().measure(names[p], font);
                canvas.drawText(names[p], x + (panelW - m.width()) / 2,
                        120 + m.ascent() + 6, font, theme.textMuted);
            }
        }
    }

    /** Same slow scroll twice: snapped steps on top, sub-pixel glide below. */
    private static final class ScrollCompare extends Animated {
        private static final float SPEED = 14f; // logical px/s, slow on purpose
        private final Image sheet = coinSheet();

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 132);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            float offset = (float) ((time * SPEED) % (COIN + 24));
            paintStrip(canvas, 14, offset, true, theme);
            canvas.save();
            canvas.setPixelSnap(false);
            paintStrip(canvas, 72, offset, false, theme);
            canvas.restore();
        }

        private void paintStrip(Canvas canvas, float y, float offset, boolean snapped, Theme theme) {
            canvas.save();
            canvas.clipRect(12, y, width() - 24, 44);
            for (float x = 12 - offset; x < width() - 12; x += COIN + 24) {
                // Frame 0 of the sheet (a full coin), source-rect again.
                canvas.drawImage(sheet, 0, 0, COIN, COIN, x, y + 4, COIN, COIN);
            }
            canvas.restore();
            var font = theme.label;
            String label = snapped ? "pixel snap ON (default)" : "pixel snap OFF (sub-pixel)";
            var m = textRuler().measure(label, font);
            canvas.drawText(label, 16, y + 44 + m.ascent() + 2, font, theme.textMuted);
        }
    }
}
