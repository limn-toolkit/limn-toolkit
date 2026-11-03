package limn.demo;

import limn.components.Theme;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Font;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.ImagePaint;
import limn.graphics.Images;
import limn.graphics.Picture;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.Stroke;
import limn.i18n.I18nString;
import limn.math.Affine2D;
import limn.math.Vec2;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.event.MouseEvent;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;

/**
 * The Drawing page: what a widget that paints for itself has on the canvas. Fill rules, with a
 * star drawn as one outline and a ring of two; strokes, with every kind of end and corner, dashes
 * marching along their paths, and a translucent line crossing itself; transforms, a shear, a turn
 * about a point, and a hit test through the inverse of the matrix a shape was drawn with; clips by a
 * path and by a turning rectangle, an eraser and a fill inside a shape; every blend mode, each
 * drawn the same way; paints, a gradient of many colours, one from a focus, a sweep, and a picture
 * repeated as a pattern; and text as a shape, letters to see through, a recorded picture stamped
 * round a ring, and an image drawn off screen.
 */
final class DrawingScene {

    /** What the page says, ready for translation. */
    static final class Text {
        static final I18nString FILLS = new I18nString("showcase.drawing.fills", "Fill rules");
        static final I18nString STROKES = new I18nString("showcase.drawing.strokes", "Strokes");
        static final I18nString TRANSFORMS = new I18nString("showcase.drawing.transforms", "Transforms");
        static final I18nString NON_ZERO = new I18nString("showcase.drawing.nonZero", "Non-zero");
        static final I18nString EVEN_ODD = new I18nString("showcase.drawing.evenOdd", "Even-odd");
        static final I18nString ENDS = new I18nString("showcase.drawing.ends", "Ends");
        static final I18nString CORNERS = new I18nString("showcase.drawing.corners", "Corners");
        static final I18nString DASHES = new I18nString("showcase.drawing.dashes", "Dashes");
        static final I18nString TRANSLUCENT = new I18nString("showcase.drawing.translucent", "Translucent");
        static final I18nString SHEAR = new I18nString("showcase.drawing.shear", "Shear");
        static final I18nString TURN = new I18nString("showcase.drawing.turn", "Turn about a point");
        static final I18nString HIT_TEST = new I18nString("showcase.drawing.hitTest", "Hit test");
        static final I18nString CLIPS = new I18nString("showcase.drawing.clips", "Clips and erasing");
        static final I18nString PATH_CLIP = new I18nString("showcase.drawing.pathClip", "Clipped by a path");
        static final I18nString TURNED_CLIP = new I18nString("showcase.drawing.turnedClip", "Turned rectangle");
        static final I18nString ERASER = new I18nString("showcase.drawing.eraser", "Eraser");
        static final I18nString FILL_INSIDE = new I18nString("showcase.drawing.fillInside", "Inside a shape");
        static final I18nString BLENDS = new I18nString("showcase.drawing.blends", "Blend modes");
        static final I18nString PAINTS = new I18nString("showcase.drawing.paints", "Paints");
        static final I18nString MANY_COLOURS = new I18nString("showcase.drawing.manyColours", "Many colours");
        static final I18nString FOCUS = new I18nString("showcase.drawing.focus", "From a focus");
        static final I18nString SWEEP = new I18nString("showcase.drawing.sweep", "Sweep");
        static final I18nString PATTERN = new I18nString("showcase.drawing.pattern", "Image pattern");
        static final I18nString TEXT_AND_PICTURES = new I18nString("showcase.drawing.textAndPictures",
                "Text and pictures");
        static final I18nString TEXT_PATH = new I18nString("showcase.drawing.textPath", "Text as a path");
        static final I18nString THROUGH_LETTERS = new I18nString("showcase.drawing.throughLetters",
                "Seen through letters");
        static final I18nString STAMPED = new I18nString("showcase.drawing.stamped", "A picture, stamped");
        static final I18nString OFF_SCREEN = new I18nString("showcase.drawing.offScreen", "Drawn off screen");

        private Text() {
        }
    }

    /** Every panel on the page is this tall, its caption included. */
    private static final float TILE = 168;
    /** The room a caption takes under its cell. */
    private static final float CAPTION = 26;
    /** Between two cells of a panel. */
    private static final float GAP = 12;

    private DrawingScene() {
    }

    /** Standalone {@code --scene drawing}. */
    static Scene create() {
        return new Scene(new limn.components.ScrollView(new Padding(Insets.all(20), content())));
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        Column column = Page.column();
        column.add(Page.firstSection(Text.FILLS));
        column.add(new Fills());
        column.add(Page.section(Text.STROKES));
        column.add(new Strokes());
        column.add(Page.section(Text.TRANSFORMS));
        column.add(new Transforms());
        column.add(Page.section(Text.CLIPS));
        column.add(new Clips());
        column.add(Page.section(Text.BLENDS));
        column.add(new Blends());
        column.add(Page.section(Text.PAINTS));
        column.add(new Paints());
        column.add(Page.section(Text.TEXT_AND_PICTURES));
        column.add(new TextAndPictures());
        return column; // the page scrolls it
    }

    /** A five-pointed star as one outline crossing itself, of radius 1 about the origin. */
    static Path2D star(FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule);
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * 4 * Math.PI / 5;
            float x = (float) Math.cos(angle);
            float y = (float) Math.sin(angle);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path.close();
    }

    /** A ring of radius 1 about the origin: two circles turning the same way. */
    static Path2D ring(FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule).addCircle(0, 0, 1);
        return path.addCircle(0, 0, 0.55f).addCircle(0, 0, 0.25f);
    }

    /** A panel of cells side by side, each a drawing with a caption under it. */
    private abstract static class Cells extends Widget<Cells> {
        double time;
        private boolean started;
        private final I18nString[] captions;

        Cells(I18nString name, I18nString... captions) {
            this.captions = captions;
            setAccessibleName(name);
            setAccessibleRole(limn.accessibility.Accessible.Role.IMAGE);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 720, TILE);
        }

        /** Keeps the panel moving while it shows. */
        void animate() {
            if (!started && scene() != null) {
                started = true;
                scene().addTicker(dt -> {
                    if (!isShowing()) {
                        started = false; // armed again by the next paint that shows it
                        return false;
                    }
                    time += dt;
                    invalidate();
                    return true;
                });
            }
        }

        float cellWidth() {
            return (width() - GAP * (captions.length - 1)) / captions.length;
        }

        float cellHeight() {
            return height() - CAPTION;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float w = cellWidth();
            float radius = theme.tokensFor(this).radiusMedium();
            Font font = theme.tokensFor(this).label();
            for (int i = 0; i < captions.length; i++) {
                float x = i * (w + GAP);
                canvas.fillRoundRect(x, 0, w, cellHeight(), radius, theme.surface());
                canvas.save();
                canvas.clipRect(x, 0, w, cellHeight());
                canvas.translate(x, 0);
                paintCell(canvas, i, w, cellHeight(), theme);
                canvas.restore();
                String shown = captions[i].get();
                var m = textRuler().measure(shown, font);
                canvas.drawText(shown, x + (w - m.width()) / 2, cellHeight() + 6 + m.ascent(), font,
                        theme.textMuted());
            }
        }

        /** Paints cell {@code index}, its top-left corner at the origin. */
        abstract void paintCell(Canvas canvas, int index, float width, float height, Theme theme);
    }

    /** The star and the ring under both rules: the middle of each is as the rule says. */
    private static final class Fills extends Cells {
        Fills() {
            super(Text.FILLS, Text.NON_ZERO, Text.EVEN_ODD, Text.NON_ZERO, Text.EVEN_ODD);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            float size = Math.min(width, height) * 0.4f;
            FillRule rule = index % 2 == 0 ? FillRule.NON_ZERO : FillRule.EVEN_ODD;
            Path2D shape = index < 2 ? star(rule) : ring(rule);
            canvas.save();
            canvas.translate(width / 2, height / 2 + (index < 2 ? size * 0.1f : 0));
            canvas.scale(size, size);
            canvas.fillPath(shape, theme.primary());
            canvas.restore();
        }
    }

    /** Ends, corners, dashes that march, and a translucent line crossing itself. */
    private static final class Strokes extends Cells {
        private static final Stroke.Cap[] CAPS = {Stroke.Cap.BUTT, Stroke.Cap.ROUND, Stroke.Cap.SQUARE};
        private static final Stroke.Join[] JOINS = {Stroke.Join.MITER, Stroke.Join.ROUND, Stroke.Join.BEVEL};

        Strokes() {
            super(Text.STROKES, Text.ENDS, Text.CORNERS, Text.DASHES, Text.TRANSLUCENT);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            Color ink = theme.primary();
            Color guide = theme.textMuted();
            switch (index) {
                case 0 -> {
                    // Three thick lines, each with the line it is drawn along: the ends past it are
                    // the cap's.
                    float left = width * 0.25f;
                    float right = width * 0.75f;
                    for (int i = 0; i < 3; i++) {
                        float y = height * (0.27f + i * 0.23f);
                        canvas.drawLine(left, y, right, y, Stroke.of(16).withCap(CAPS[i]), ink);
                        canvas.drawLine(left, y, right, y, Stroke.of(1).withCap(Stroke.Cap.ROUND), guide);
                    }
                }
                case 1 -> {
                    for (int i = 0; i < 3; i++) {
                        float x = width * (0.2f + i * 0.3f);
                        Path2D corner = new Path2D().moveTo(x - 18, height * 0.72f).lineTo(x, height * 0.3f)
                                .lineTo(x + 18, height * 0.72f);
                        canvas.drawPath(corner, Stroke.of(12).withJoin(JOINS[i]), ink);
                        canvas.drawPath(corner, Stroke.of(1), guide);
                    }
                }
                case 2 -> {
                    animate();
                    float phase = (float) (-time * 24);
                    canvas.drawRect(16, 16, width - 32, height * 0.42f,
                            Stroke.of(3).withDash(10, 6).withDashPhase(phase), ink);
                    canvas.drawCircle(width * 0.3f, height * 0.75f, height * 0.15f,
                            Stroke.of(5).withCap(Stroke.Cap.ROUND).withDash(0, 10).withDashPhase(phase / 2), ink);
                    canvas.drawLine(width * 0.55f, height * 0.75f, width - 16, height * 0.75f,
                            Stroke.of(4).withCap(Stroke.Cap.ROUND).withDash(14, 7, 0, 7).withDashPhase(phase), ink);
                }
                default -> {
                    // Painted once where the line crosses itself and where its segments meet.
                    Path2D loop = new Path2D().moveTo(width * 0.15f, height * 0.75f)
                            .cubicTo(width * 0.4f, -height * 0.1f, width * 1.0f, height * 0.6f, width * 0.5f, height * 0.8f)
                            .cubicTo(width * 0.2f, height * 0.95f, width * 0.2f, height * 0.25f, width * 0.85f, height * 0.25f);
                    Color half = new Color(ink.r(), ink.g(), ink.b(), 0.5f);
                    canvas.drawPath(loop, Stroke.of(14).withCap(Stroke.Cap.ROUND).withJoin(Stroke.Join.ROUND), half);
                }
            }
        }
    }

    /** A shear that leans and comes back, a needle turning about its pivot, and a hit test. */
    private static final class Transforms extends Cells {
        /** Where the pointer is, in this widget's coordinates; NaN when it is not over it. */
        private float pointerX = Float.NaN;
        private float pointerY = Float.NaN;

        Transforms() {
            super(Text.TRANSFORMS, Text.SHEAR, Text.TURN, Text.HIT_TEST);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            animate();
            Color ink = theme.primary();
            Color guide = theme.textMuted();
            switch (index) {
                case 0 -> {
                    float lean = (float) Math.sin(time * 1.3) * 0.6f;
                    float size = Math.min(width, height) * 0.5f;
                    canvas.translate(width / 2, height / 2);
                    canvas.shear(lean, 0);
                    for (int i = 0; i <= 4; i++) {
                        float at = -size / 2 + i * size / 4;
                        canvas.drawLine(at, -size / 2, at, size / 2, Stroke.of(1.5f), guide);
                        canvas.drawLine(-size / 2, at, size / 2, at, Stroke.of(1.5f), guide);
                    }
                    canvas.drawRect(-size / 2, -size / 2, size, size, Stroke.of(3).withJoin(Stroke.Join.MITER), ink);
                }
                case 1 -> {
                    float cx = width / 2;
                    float cy = height * 0.62f;
                    float r = Math.min(width, height) * 0.42f;
                    Path2D dial = new Path2D().arc(cx, cy, r, r, (float) Math.PI, (float) Math.PI);
                    canvas.drawPath(dial, Stroke.of(6).withCap(Stroke.Cap.ROUND).withDash(0, 14), guide);
                    canvas.save();
                    canvas.rotate((float) (Math.sin(time * 0.8) * 1.3), cx, cy);
                    canvas.drawLine(cx, cy, cx, cy - r * 0.85f, Stroke.of(4).withCap(Stroke.Cap.ROUND), ink);
                    canvas.restore();
                    canvas.fillCircle(cx, cy, 6, ink);
                }
                default -> {
                    // The star is drawn turned and scaled; the pointer, given in this widget's
                    // coordinates, is brought into the star's by the inverse of the matrix drawn
                    // with, and the star asked whether it holds it.
                    float size = Math.min(width, height) * 0.38f;
                    Affine2D placed = Affine2D.translation(width / 2, height / 2 + size * 0.1f)
                            .rotate(time * 0.4).scale(size, size);
                    boolean over = false;
                    if (!Float.isNaN(pointerX)) {
                        float cellX = pointerX - index * (cellWidth() + GAP);
                        Vec2 inStar = placed.invert().transformPoint(new Vec2(cellX, pointerY));
                        over = star(FillRule.NON_ZERO).contains(inStar.x(), inStar.y());
                    }
                    canvas.save();
                    canvas.concat(placed);
                    Path2D shape = star(FillRule.NON_ZERO);
                    if (over) {
                        canvas.fillPath(shape, ink);
                    } else {
                        canvas.drawPath(shape, Stroke.of(3 / size).withJoin(Stroke.Join.ROUND), ink);
                    }
                    canvas.restore();
                }
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER, MOVE, DRAG -> {
                    Vec2 local = sceneToLocal(event.x(), event.y());
                    pointerX = local.x();
                    pointerY = local.y();
                }
                case EXIT -> {
                    pointerX = Float.NaN;
                    pointerY = Float.NaN;
                }
                default -> {
                    super.onMouseEvent(event);
                    return;
                }
            }
            invalidate();
        }
    }

    /**
     * A star clipping stripes that slide under it, a rectangle clipped as it turns, an eraser crossing
     * a card, and a gradient drawn only over a shape, the last two each inside a layer of its own.
     */
    private static final class Clips extends Cells {
        Clips() {
            super(Text.CLIPS, Text.PATH_CLIP, Text.TURNED_CLIP, Text.ERASER, Text.FILL_INSIDE);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            animate();
            Color ink = theme.primary();
            Color accent = theme.danger();
            float size = Math.min(width, height) * 0.4f;
            switch (index) {
                case 0 -> {
                    Path2D shape = star(FillRule.NON_ZERO).transformed(Affine2D.translation(width / 2,
                            height / 2 + size * 0.1f).scale(size, size));
                    canvas.clipPath(shape);
                    float slide = (float) (time * 20 % 16);
                    for (float x = -16 + slide; x < width; x += 16) {
                        canvas.fillRect(x, 0, 8, height, ink);
                        canvas.fillRect(x + 8, 0, 8, height, accent);
                    }
                }
                case 1 -> {
                    canvas.rotate((float) (time * 0.5), width / 2, height / 2);
                    canvas.clipRect(width / 2 - size, height / 2 - size * 0.7f, size * 2, size * 1.4f);
                    canvas.rotate((float) (-time * 0.5), width / 2, height / 2);
                    canvas.fillRect(0, 0, width, height, new LinearGradient(0, 0, width, height, ink, accent));
                    for (float y = 8; y < height; y += 14) {
                        for (float x = 8; x < width; x += 14) {
                            canvas.fillCircle(x, y, 2.5f, theme.surface());
                        }
                    }
                }
                case 2 -> {
                    float x0 = width / 2 - size * 1.2f;
                    float y0 = height / 2 - size * 0.8f;
                    canvas.saveLayer(x0, y0, size * 2.4f, size * 1.6f, 1);
                    canvas.fillRoundRect(x0, y0, size * 2.4f, size * 1.6f, 10, ink);
                    canvas.setBlendMode(BlendMode.DESTINATION_OUT);
                    float t = (float) (Math.sin(time * 0.9) * 0.5 + 0.5);
                    canvas.fillCircle(x0 + size * 2.4f * t, height / 2, size * 0.55f, Color.BLACK);
                    canvas.fillCircle(x0 + size * 2.4f * (1 - t), height / 2 + size * 0.45f, size * 0.3f,
                            new Color(0, 0, 0, 0.5f));
                    canvas.restore();
                }
                default -> {
                    Path2D ring = ring(FillRule.EVEN_ODD).transformed(Affine2D.translation(width / 2, height / 2)
                            .scale(size, size));
                    canvas.saveLayer(0, 0, width, height, 1);
                    canvas.fillPath(ring, Color.BLACK);
                    canvas.setBlendMode(BlendMode.SOURCE_IN);
                    float shift = (float) (Math.sin(time * 0.7) * width * 0.3);
                    canvas.fillRect(0, 0, width, height, new LinearGradient(shift, 0, width + shift, height, accent, ink));
                    canvas.restore();
                }
            }
        }
    }

    /** Every blend mode, each a disc and then a square drawn over it in that mode, inside a layer. */
    private static final class Blends extends Widget<Blends> {
        private static final int COLUMNS = 4;
        private static final float SWATCH = 76;

        Blends() {
            setAccessibleName(Text.BLENDS);
            setAccessibleRole(limn.accessibility.Accessible.Role.IMAGE);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            int rows = (BlendMode.values().length + COLUMNS - 1) / COLUMNS;
            return c.constrain(c.hasBoundedWidth() ? c.maxWidth() : 720, rows * (SWATCH + CAPTION + GAP) - GAP);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            Font font = theme.tokensFor(this).label();
            float radius = theme.tokensFor(this).radiusMedium();
            float w = (width() - GAP * (COLUMNS - 1)) / COLUMNS;
            BlendMode[] modes = BlendMode.values();
            Color checker = theme.textMuted().withAlpha(0.18f);
            for (int i = 0; i < modes.length; i++) {
                float x = (i % COLUMNS) * (w + GAP);
                float y = (i / COLUMNS) * (SWATCH + CAPTION + GAP);
                canvas.fillRoundRect(x, y, w, SWATCH, radius, theme.surface());
                canvas.save();
                canvas.clipRoundRect(limn.graphics.RoundRect.of(x, y, w, SWATCH, radius));
                // A checkerboard behind, so that what a mode leaves transparent shows as such.
                for (float cy = 0; cy < SWATCH; cy += 8) {
                    for (float cx = ((int) (cy / 8) % 2) * 8; cx < w; cx += 16) {
                        canvas.fillRect(x + cx, y + cy, 8, 8, checker);
                    }
                }
                float cx = x + w / 2;
                float cy = y + SWATCH / 2;
                canvas.saveLayer(x, y, w, SWATCH, 1);
                canvas.fillCircle(cx - 10, cy - 4, 24, theme.danger().withAlpha(0.9f));
                canvas.setBlendMode(modes[i]);
                canvas.fillRoundRect(cx - 6, cy - 12, 38, 34, 6, theme.primary().withAlpha(0.8f));
                canvas.restore();
                canvas.restore();
                String name = modes[i].name();
                var m = textRuler().measure(name, font);
                canvas.drawText(name, x + (w - m.width()) / 2, y + SWATCH + 6 + m.ascent(), font, theme.textMuted());
            }
        }
    }

    /**
     * A sky of four colours with a hard horizon, a sphere lit from a focus that circles, a sweep that
     * turns, and a hatch drawn from a picture of sixteen pixels repeated under a turning matrix.
     */
    private static final class Paints extends Cells {
        /** Sixteen by sixteen pixels: one diagonal line, which repeated is a hatch. */
        private static final Image HATCH = hatch();

        Paints() {
            super(Text.PAINTS, Text.MANY_COLOURS, Text.FOCUS, Text.SWEEP, Text.PATTERN);
        }

        private static Image hatch() {
            byte[] rgba = new byte[16 * 16 * 4];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int at = (y * 16 + x) * 4;
                    boolean line = (x + y) % 16 < 4;
                    rgba[at] = (byte) (line ? 0x20 : 0);
                    rgba[at + 1] = (byte) (line ? 0x50 : 0);
                    rgba[at + 2] = (byte) (line ? 0xC0 : 0);
                    rgba[at + 3] = (byte) (line ? 255 : 0);
                }
            }
            return new Image(16, 16, rgba);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            animate();
            float size = Math.min(width, height) * 0.42f;
            float cx = width / 2;
            float cy = height / 2;
            switch (index) {
                case 0 -> canvas.fillRoundRect(16, 12, width - 32, height - 24, 8, Gradient.linear(0, 12, 0,
                        height - 12, new float[] {0, 0.55f, 0.56f, 1}, Color.rgb(0x1B2A49), Color.rgb(0xE8835A),
                        Color.rgb(0x2A6F97), Color.rgb(0x0B2545)));
                case 1 -> {
                    float angle = (float) (time * 0.8);
                    canvas.fillCircle(cx, cy, size, Gradient.radial(cx, cy, size, Color.WHITE,
                            Color.rgb(0x7FB3D5), Color.rgb(0x1B4F72)).withFocus(
                            cx + (float) Math.cos(angle) * size * 0.45f, cy + (float) Math.sin(angle) * size * 0.45f));
                }
                case 2 -> canvas.fillCircle(cx, cy, size, Gradient.sweep(cx, cy, (float) (time * 0.6),
                        Color.rgb(0xE74C3C), Color.rgb(0xF1C40F), Color.rgb(0x2ECC71), Color.rgb(0x3498DB),
                        Color.rgb(0x9B59B6), Color.rgb(0xE74C3C)));
                default -> canvas.fillRoundRect(16, 12, width - 32, height - 24, 8, ImagePaint.of(HATCH)
                        .withTransform(limn.math.Affine2D.translation(cx, cy).rotate(time * 0.3).scale(0.75, 0.75)));
            }
        }
    }

    /**
     * A word as a path, stroked with a dash that marches; the same word as a clip a gradient slides
     * behind; a picture of a pin recorded once and stamped round a ring that turns; and an image the
     * canvas drew off screen on the first paint, shown again enlarged and pixelated.
     */
    private static final class TextAndPictures extends Cells {
        /** A map pin, recorded once and drawn at every stamp. */
        private static final Picture PIN = Picture.record(16, 22, c -> {
            c.fillPath(Path2D.parse("M8 0C3.6 0 0 3.4 0 7.8 0 13.6 8 22 8 22s8-8.4 8-14.2C16 3.4 12.4 0 8 0z"),
                    Color.rgb(0xE74C3C));
            c.fillCircle(8, 8, 3, Color.WHITE);
        });
        private Image offScreen;

        TextAndPictures() {
            super(Text.TEXT_AND_PICTURES, Text.TEXT_PATH, Text.THROUGH_LETTERS, Text.STAMPED, Text.OFF_SCREEN);
        }

        @Override
        void paintCell(Canvas canvas, int index, float width, float height, Theme theme) {
            animate();
            Color ink = theme.primary();
            switch (index) {
                case 0, 1 -> {
                    Font font = theme.tokensFor(this).title().withSize(Math.min(height * 0.48f, width * 0.28f))
                            .withWeight(800);
                    limn.graphics.ShapedText word = textRuler().shape("Limn", font);
                    float x = (width - word.metrics().width()) / 2;
                    float y = height / 2 + word.metrics().ascent() * 0.36f;
                    Path2D outline = word.outline(x, y);
                    if (index == 0) {
                        canvas.drawPath(outline, Stroke.of(2).withCap(Stroke.Cap.ROUND).withDash(6, 4)
                                .withDashPhase((float) (-time * 12)), ink);
                    } else {
                        canvas.clipPath(outline);
                        float shift = (float) (Math.sin(time * 0.9) * width * 0.5);
                        canvas.fillRect(0, 0, width, height, Gradient.linear(shift, 0, width + shift, 0,
                                Color.rgb(0xE74C3C), Color.rgb(0xF1C40F), Color.rgb(0x2ECC71), Color.rgb(0x3498DB))
                                .withSpread(Gradient.Spread.REFLECT));
                    }
                }
                case 2 -> {
                    float r = Math.min(width, height) * 0.3f;
                    for (int i = 0; i < 8; i++) {
                        double a = time * 0.5 + i * Math.PI / 4;
                        canvas.save();
                        canvas.translate(width / 2 + (float) Math.cos(a) * r - 8,
                                height / 2 + (float) Math.sin(a) * r - 11); // by the pin's middle
                        canvas.drawPicture(PIN);
                        canvas.restore();
                    }
                }
                default -> {
                    if (offScreen == null) {
                        // Drawn once, here, inside the frame: twelve by twelve pixels of a sweep.
                        offScreen = Images.render(12, 12, 1, c -> c.fillCircle(6, 6, 6,
                                Gradient.sweep(6, 6, 0, Color.rgb(0xE74C3C), Color.rgb(0x3498DB),
                                        Color.rgb(0xE74C3C))));
                    }
                    float side = Math.min(width, height) * 0.7f;
                    canvas.save();
                    canvas.setSampling(limn.graphics.Sampling.PIXELATED);
                    canvas.drawImage(offScreen, (width - side) / 2, (height - side) / 2, side, side);
                    canvas.restore();
                }
            }
        }
    }
}
