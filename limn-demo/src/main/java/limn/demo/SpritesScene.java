package limn.demo;

import limn.components.Checkbox;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.Sampling;
import limn.i18n.I18nString;
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
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * The Sprites page: the 2D primitives behind pictures and games, all procedural (no asset files).
 * A sprite sheet played by source-rectangle
 * {@link Canvas#drawImage(Image, float, float, float, float, float, float, float, float)}; the
 * same animation as images an image view plays; one picture in each of an image view's fits and
 * with a tint; pixel art scaled smooth and pixelated; three {@link BlendMode}s; and slow motion
 * snapped to whole pixels and not.
 */
final class SpritesScene {

    /** What the page says, ready for translation. */
    static final class Text {
        static final I18nString SHEET = new I18nString("showcase.sprites.sheet", "Sprite sheet");
        static final I18nString ANIMATED = new I18nString("showcase.sprites.animated", "Animated images");
        static final I18nString SHEET_CAPTION = new I18nString("showcase.sprites.sheetCaption", "Sheet");
        static final I18nString PLAYING = new I18nString("showcase.sprites.playing", "Playing");
        static final I18nString SPEED = new I18nString("showcase.sprites.speed", "Speed");
        static final I18nString REPEAT = new I18nString("showcase.sprites.repeat", "Repeat");
        static final I18nString AS_THE_FILE_SAYS =
                new I18nString("showcase.sprites.asTheFileSays", "As the file says");
        static final I18nString ONCE = new I18nString("showcase.sprites.once", "Once");
        static final I18nString BACK_AND_FORTH =
                new I18nString("showcase.sprites.backAndForth", "Back and forth");
        static final I18nString PICTURES = new I18nString("showcase.sprites.pictures", "Pictures");
        static final I18nString CONTAIN = new I18nString("showcase.sprites.contain", "Contain");
        static final I18nString COVER = new I18nString("showcase.sprites.cover", "Cover");
        static final I18nString FILL = new I18nString("showcase.sprites.fill", "Fill");
        static final I18nString ACTUAL_SIZE = new I18nString("showcase.sprites.actualSize", "Actual size");
        static final I18nString TINTED = new I18nString("showcase.sprites.tinted", "Tinted");
        static final I18nString PICTURE = new I18nString("showcase.sprites.picture", "Coastline at dusk");
        static final I18nString PIXEL_ART = new I18nString("showcase.sprites.pixelArt", "Pixel art");
        static final I18nString SMOOTH = new I18nString("showcase.sprites.smooth", "Smooth");
        static final I18nString PIXELATED = new I18nString("showcase.sprites.pixelated", "Pixelated");
        static final I18nString BLENDING = new I18nString("showcase.sprites.blending", "Blending");
        static final I18nString NORMAL = new I18nString("showcase.sprites.normal", "Normal");
        static final I18nString ADDITIVE = new I18nString("showcase.sprites.additive", "Additive");
        static final I18nString MULTIPLY = new I18nString("showcase.sprites.multiply", "Multiply");
        static final I18nString MOTION = new I18nString("showcase.sprites.motion", "Slow motion");
        static final I18nString SNAPPED = new I18nString("showcase.sprites.snapped", "Snapped to pixels");
        static final I18nString SUB_PIXEL = new I18nString("showcase.sprites.subPixel", "Sub-pixel");

        private Text() {
        }
    }

    /** Every panel on the page is this tall, so the page reads as one set of them. */
    private static final float TILE = 150;

    private SpritesScene() {
    }

    /** Standalone {@code --scene sprites}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        return scene;
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        Column column = Page.column();

        column.add(Page.firstSection(Text.SHEET));
        column.add(new SheetPlayer());

        column.add(Page.section(Text.ANIMATED));
        animatedImages(column);

        column.add(Page.section(Text.PICTURES));
        column.add(pictures());

        column.add(Page.section(Text.PIXEL_ART));
        column.add(new FilterCompare());

        column.add(Page.section(Text.BLENDING));
        column.add(new BlendPanels());

        column.add(Page.section(Text.MOTION));
        column.add(new ScrollCompare());

        return column; // the page scrolls it
    }

    /**
     * An animated image is an image: the sheet above cut into its frames, and a GIF, a PNG and a
     * WebP read from bytes, each given to an image view, which plays it. The toolbar above them
     * drives them all.
     */
    private static void animatedImages(Column column) {
        // A lab's switch: -Dlimn.demo.animationBudget=0 decodes the files as they play.
        Long budget = Long.getLong("limn.demo.animationBudget");
        if (budget != null) {
            Images.setAnimationBudget(budget);
        }
        // The sheet, cut along its one row: eight frames, a tenth of a second each.
        Image coin = Images.fromSheet(coinSheet(), FRAMES, 1, 100);
        // A GIF as a file would hold it: written to bytes and read back, palette, holes and all.
        Image orbit = Images.decode(Images.encode(orbit(), limn.graphics.ImageFormat.GIF));
        // The same as an animated PNG, without loss: alpha between, every frame its own colours.
        Image png = Images.decode(Images.encode(orbit(), limn.graphics.ImageFormat.PNG));
        // And the same animation as a WebP, from a file the application carries.
        Image webp = Images.fromResource("/limn/demo/images/orbit.webp");

        ImageView first = new ImageView(coin).setPreferredSize(96, 96);
        ImageView second = new ImageView(orbit).setPreferredSize(96, 96);
        ImageView third = new ImageView(png).setPreferredSize(96, 96);
        ImageView fourth = new ImageView(webp).setPreferredSize(96, 96);
        List<ImageView> views = List.of(first, second, third, fourth);

        Checkbox playing = new Checkbox(Checkbox.Variant.SWITCH, Text.PLAYING);
        playing.setChecked(true);
        playing.onChange(on -> views.forEach(view -> view.setPlaying(on)));

        SegmentedControl speed = new SegmentedControl(times(0.5), times(1), times(2));
        speed.setAccessibleName(Text.SPEED);
        speed.setSelectedIndex(1);
        float[] speeds = {0.5f, 1f, 2f};
        speed.onSelect(index -> views.forEach(view -> view.setSpeed(speeds[index])));

        SegmentedControl turns = new SegmentedControl(Text.AS_THE_FILE_SAYS, Text.ONCE, Text.BACK_AND_FORTH);
        turns.setAccessibleName(Text.REPEAT);
        limn.animation.ImageAnimation.Loop[] loops = {limn.animation.ImageAnimation.Loop.FILE,
                limn.animation.ImageAnimation.Loop.ONCE, limn.animation.ImageAnimation.Loop.BOUNCE};
        turns.onSelect(index -> views.forEach(view -> {
            view.setLoop(loops[index]);
            view.setPlaying(playing.isChecked()); // one that had finished starts again
        }));

        Row toolbar = Page.row(16, playing, speed, turns);
        toolbar.add(Expanded.spacer(1));
        column.add(toolbar);

        Row tiles = Page.row(12,
                tile(first, Text.SHEET_CAPTION),
                tile(second, I18nString.literal("GIF")),
                tile(third, I18nString.literal("PNG")),
                tile(fourth, I18nString.literal("WebP")));
        tiles.add(Expanded.spacer(1));
        column.add(tiles);
    }

    /** One picture in each of an image view's fits, and one with a tint, in boxes of one size. */
    private static Widget<?> pictures() {
        Image picture = GlassScene.dusk();
        Row row = Page.row(12,
                tile(fitted(picture, ImageView.Fit.CONTAIN), Text.CONTAIN),
                tile(fitted(picture, ImageView.Fit.COVER), Text.COVER),
                tile(fitted(picture, ImageView.Fit.FILL), Text.FILL),
                tile(fitted(picture, ImageView.Fit.NONE), Text.ACTUAL_SIZE),
                tile(fitted(picture, ImageView.Fit.COVER).setTint(Color.rgb(0x8CC4F0)), Text.TINTED));
        row.add(Expanded.spacer(1));
        return row;
    }

    /** The picture is 2:1 and the box 4:3, so the fits are four different pictures. */
    private static ImageView fitted(Image picture, ImageView.Fit fit) {
        return new ImageView(picture).setFit(fit).setPreferredSize(128, 96)
                .setAccessibleName(Text.PICTURE);
    }

    /** A picture on a panel of the page's tile height, its caption under it. */
    private static Widget<?> tile(Widget<?> picture, I18nString caption) {
        Column column = new Column();
        column.gap(8).crossAlignment(Flex.CrossAlignment.CENTER).mainAlignment(Flex.MainAlignment.CENTER);
        column.add(picture);
        column.add(new Label(caption).setMuted(true));
        return new SizedBox(160, TILE, Page.card(column));
    }

    /** A speed as the segment reads it: the multiplier in the language's digits, then "×". */
    private static I18nString times(double value) {
        return I18nString.literal(limn.i18n.NumberFormats.number(2).apply(value) + "×");
    }

    /** Twenty-four frames of a dot going round a ring, with nothing behind it: an animation drawn here. */
    private static Image orbit() {
        int size = 48;
        int frames = 24;
        java.util.List<Image> list = new java.util.ArrayList<>(frames);
        int[] millis = new int[frames];
        for (int f = 0; f < frames; f++) {
            byte[] rgba = new byte[size * size * 4];
            double angle = f * 2 * Math.PI / frames;
            double dotX = size / 2.0 + Math.cos(angle) * 16;
            double dotY = size / 2.0 + Math.sin(angle) * 16;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double fromCentre = Math.hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0);
                    double fromDot = Math.hypot(x + 0.5 - dotX, y + 0.5 - dotY);
                    int o = (y * size + x) * 4;
                    if (fromDot < 6) {
                        rgba[o] = (byte) 0xFF;
                        rgba[o + 1] = (byte) (0x90 + f * 4);
                        rgba[o + 2] = (byte) 0x20;
                        rgba[o + 3] = (byte) 0xFF;
                    } else if (Math.abs(fromCentre - 16) < 1.5) {
                        rgba[o] = (byte) 0x40;
                        rgba[o + 1] = (byte) 0x80;
                        rgba[o + 2] = (byte) 0xE0;
                        rgba[o + 3] = (byte) 0xFF;
                    }
                }
            }
            list.add(new Image(size, size, rgba));
            millis[f] = 50;
        }
        return Images.animation(list, millis, 0);
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
    private abstract static class Animated extends Widget<Animated> {
        double time;
        private boolean started;

        Animated(I18nString name) {
            setAccessibleName(name);
            setAccessibleRole(limn.accessibility.Accessible.Role.IMAGE);
        }

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

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 600, TILE);
        }

        void panel(Canvas canvas, Theme theme) {
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface());
        }

        /** A caption centred under {@code x .. x + width}, at {@code y}, in the muted label ink. */
        void caption(Canvas canvas, I18nString text, float x, float width, float y, Theme theme) {
            Font font = theme.tokensFor(this).label();
            String shown = text.get();
            var m = textRuler().measure(shown, font);
            canvas.drawText(shown, x + (width - m.width()) / 2, y + m.ascent(), font, theme.textMuted());
        }
    }

    // ------------------------------------------------------ section widgets

    /** The sheet at twice its size, the frame being played outlined. */
    private static final class SheetPlayer extends Animated {
        private final Image sheet = coinSheet();

        SheetPlayer() {
            super(Text.SHEET);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.of(this);
            panel(canvas, theme);
            int frame = (int) (time * 10) % FRAMES;
            // The whole sheet at 2×, pixelated so its cells read clearly, centred on the panel so
            // it sits the same in either reading direction.
            canvas.save();
            canvas.setSampling(Sampling.PIXELATED);
            float cell = COIN * 2;
            float sheetX = (width() - FRAMES * cell) / 2;
            float sheetY = (height() - cell) / 2;
            canvas.drawImage(sheet, sheetX, sheetY, FRAMES * cell, cell);
            canvas.drawRect(sheetX + frame * cell, sheetY, cell, cell, 2, theme.primary());
            canvas.restore();
        }
    }

    /** The invader at 6×, smooth on one side, pixelated on the other. */
    private static final class FilterCompare extends Animated {
        private final Image sprite = invader();

        FilterCompare() {
            super(Text.PIXEL_ART);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            panel(canvas, theme);
            float size = 96;
            float y = (height() - size - 24) / 2;
            float firstX = width() / 2 - size - 40;
            float secondX = width() / 2 + 40;
            canvas.drawImage(sprite, firstX, y, size, size); // SMOOTH is the default
            canvas.save();
            canvas.setSampling(Sampling.PIXELATED);
            canvas.drawImage(sprite, secondX, y, size, size);
            canvas.restore();
            caption(canvas, Text.SMOOTH, firstX, size, y + size + 4, theme);
            caption(canvas, Text.PIXELATED, secondX, size, y + size + 4, theme);
        }
    }

    /** Three panels, the same three orbiting glows: only the blend mode differs. */
    private static final class BlendPanels extends Animated {
        private static final Color[] GLOWS = {
                new Color(1f, 0.30f, 0.25f, 0.55f),
                new Color(0.25f, 0.95f, 0.45f, 0.55f),
                new Color(0.30f, 0.45f, 1f, 0.55f),
        };
        private static final I18nString[] NAMES = {Text.NORMAL, Text.ADDITIVE, Text.MULTIPLY};
        private static final BlendMode[] MODES = {BlendMode.NORMAL, BlendMode.ADDITIVE, BlendMode.MULTIPLY};

        BlendPanels() {
            super(Text.BLENDING);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.of(this);
            float panelW = (width() - 2 * 12) / 3;
            float panelH = height() - 26;
            for (int p = 0; p < 3; p++) {
                float x = p * (panelW + 12);
                // MULTIPLY needs a light backdrop to show darkening.
                Color backdrop = MODES[p] == BlendMode.MULTIPLY
                        ? new Color(0.92f, 0.92f, 0.95f, 1)
                        : new Color(0.07f, 0.07f, 0.10f, 1);
                canvas.fillRoundRect(x, 0, panelW, panelH, theme.tokensFor(this).radiusMedium(), backdrop);
                canvas.save();
                canvas.clipRect(x, 0, panelW, panelH);
                canvas.setBlendMode(MODES[p]);
                for (int i = 0; i < GLOWS.length; i++) {
                    double a = time * 0.9 + i * Math.PI * 2 / 3;
                    float gx = x + panelW / 2 + (float) Math.cos(a) * 18;
                    float gy = panelH / 2 + (float) Math.sin(a) * 14;
                    canvas.fillCircle(gx, gy, 34, GLOWS[i]);
                }
                canvas.restore();
                caption(canvas, NAMES[p], x, panelW, panelH + 6, theme);
            }
        }
    }

    /** The same slow scroll twice: whole-pixel steps above, a sub-pixel glide below. */
    private static final class ScrollCompare extends Animated {
        private static final float SPEED = 14f; // logical px/s, slow on purpose
        private final Image sheet = coinSheet();

        ScrollCompare() {
            super(Text.MOTION);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            armTicker();
            Theme theme = Theme.of(this);
            panel(canvas, theme);
            float offset = (float) ((time * SPEED) % (COIN + 24));
            paintStrip(canvas, 12, offset, Text.SNAPPED, theme);
            canvas.save();
            canvas.setPixelSnap(false);
            paintStrip(canvas, 80, offset, Text.SUB_PIXEL, theme);
            canvas.restore();
        }

        private void paintStrip(Canvas canvas, float y, float offset, I18nString label, Theme theme) {
            canvas.save();
            canvas.clipRect(12, y, width() - 24, 40);
            for (float x = 12 - offset; x < width() - 12; x += COIN + 24) {
                // Frame 0 of the sheet (a full coin), source-rect again.
                canvas.drawImage(sheet, 0, 0, COIN, COIN, x, y + 4, COIN, COIN);
            }
            canvas.restore();
            Font font = theme.tokensFor(this).label();
            String shown = label.get();
            var m = textRuler().measure(shown, font);
            float textX = layoutDirection().isRightToLeft() ? width() - 16 - m.width() : 16;
            canvas.drawText(shown, textX, y + 40 + m.ascent() + 2, font, theme.textMuted());
        }
    }
}
