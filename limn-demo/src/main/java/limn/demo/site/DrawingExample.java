package limn.demo.site;

import java.util.List;
import java.util.function.BiConsumer;
import limn.accessibility.Accessible;
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
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.Picture;
import limn.graphics.RoundRect;
import limn.graphics.Sampling;
import limn.graphics.ShapedText;
import limn.graphics.Stroke;
import limn.graphics.TextRuler;
import limn.math.Affine2D;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

/**
 * The worked examples the drawing guide shows: shapes, paths and their fill rules, lines, paints,
 * transforms, clips, layers and blend modes, text as a path, and drawings kept as pictures. Each
 * region is the body of a widget's {@code onPaint}, drawing in a box of {@link #WIDTH} by
 * {@link #HEIGHT}; the guide's pictures are these same methods, drawn by {@link #scene}. Compiled
 * by {@code ./gradlew check}, and run by {@code DrawingExampleTest}.
 */
public final class DrawingExample {

    /** The box every drawing here is made for, in logical points. */
    static final float WIDTH = 160;
    static final float HEIGHT = 120;

    static final Color BLUE = Color.rgb(0x2F6FDE);
    static final Color ORANGE = Color.rgb(0xE8835A);
    static final Color TEAL = Color.rgb(0x1F9E8F);

    private DrawingExample() {
    }

    // #region guide:drawing-shapes
    static void shapes(Canvas canvas) {
        canvas.fillRect(12, 12, 60, 40, BLUE);
        canvas.fillRoundRect(88, 12, 60, 40, 12, ORANGE);
        canvas.fillCircle(42, 86, 24, TEAL);
        canvas.drawEllipse(118, 86, 30, 20, 3, BLUE);     // a width of 3 strokes it
        canvas.drawLine(12, 112, 148, 112, 2, ORANGE);
    }
    // #endregion

    // #region guide:drawing-path
    /** A five-pointed star drawn as one outline that crosses itself. */
    static Path2D star(float cx, float cy, float radius, FillRule rule) {
        Path2D path = new Path2D().setFillRule(rule);
        for (int i = 0; i < 5; i++) {
            double angle = -Math.PI / 2 + i * 4 * Math.PI / 5;
            float x = cx + radius * (float) Math.cos(angle);
            float y = cy + radius * (float) Math.sin(angle);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path.close();
    }

    static void fillRules(Canvas canvas) {
        canvas.fillPath(star(42, 64, 34, FillRule.NON_ZERO), BLUE);   // the middle is filled
        canvas.fillPath(star(118, 64, 34, FillRule.EVEN_ODD), BLUE);  // the middle is a hole
    }
    // #endregion

    // #region guide:drawing-parse
    /** A cross written as SVG path data, and a tab with one rounded corner drawn with arcTo. */
    static void svgAndArcs(Canvas canvas) {
        Path2D cross = Path2D.parse("M9 2h6v7h7v6h-7v7H9v-7H2V9h7z");
        canvas.save();
        canvas.translate(14, 30);
        canvas.scale(2.6f, 2.6f);                       // the data is 24 units square
        canvas.fillPath(cross, ORANGE);
        canvas.restore();

        Path2D tab = new Path2D()
                .moveTo(90, 100)
                .lineTo(90, 30)
                .arcTo(150, 30, 150, 100, 24)           // the corner between the two lines
                .lineTo(150, 100)
                .close();
        canvas.fillPath(tab, TEAL);
    }
    // #endregion

    // #region guide:drawing-strokes
    static void strokes(Canvas canvas) {
        // Ends: the same line with a butt, a round and a square cap.
        Stroke.Cap[] caps = {Stroke.Cap.BUTT, Stroke.Cap.ROUND, Stroke.Cap.SQUARE};
        for (int i = 0; i < caps.length; i++) {
            canvas.drawLine(22, 20 + i * 18, 70, 20 + i * 18, Stroke.of(10).withCap(caps[i]), BLUE);
        }
        // Corners: a mitred one, and a round one.
        Path2D corner = new Path2D().moveTo(90, 52).lineTo(102, 18).lineTo(114, 52);
        canvas.drawPath(corner, Stroke.of(8).withJoin(Stroke.Join.MITER), ORANGE);
        canvas.drawPath(corner.transformed(Affine2D.translation(32, 0)),
                Stroke.of(8).withJoin(Stroke.Join.ROUND), TEAL);
        // Dashes: dots are dashes of no length with round caps.
        canvas.drawRect(14, 76, 132, 30, Stroke.of(2).withDash(8, 5), BLUE);
        canvas.drawLine(28, 91, 132, 91, Stroke.of(6).withCap(Stroke.Cap.ROUND).withDash(0, 12), ORANGE);
    }
    // #endregion

    // #region guide:drawing-hit
    /** Whether a point is on the star, and whether it is on a line four units wide along it. */
    static boolean onShape(Path2D shape, float x, float y) {
        return shape.contains(x, y);
    }

    static boolean onLine(Path2D shape, float x, float y) {
        return Stroke.of(4).outline(shape).contains(x, y);
    }
    // #endregion

    // #region guide:drawing-gradients
    static void gradients(Canvas canvas) {
        // Two colours along a line.
        canvas.fillRoundRect(10, 10, 66, 46, 8, new LinearGradient(10, 10, 76, 56, BLUE, TEAL));
        // Any number of colours, two at one position making a hard edge: a sky and a sea.
        canvas.fillRoundRect(84, 10, 66, 46, 8, Gradient.linear(0, 10, 0, 56,
                new float[] {0, 0.55f, 0.55f, 1},
                Color.rgb(0x1B2A49), Color.rgb(0xE8835A), Color.rgb(0x2A6F97), Color.rgb(0x0B2545)));
        // Radial, lit from a focus off its centre.
        canvas.fillCircle(43, 88, 26, Gradient.radial(43, 88, 26, Color.WHITE, Color.rgb(0x7FB3D5),
                Color.rgb(0x1B4F72)).withFocus(35, 79));
        // A sweep once round a centre.
        canvas.fillCircle(117, 88, 26, Gradient.sweep(117, 88, 0, Color.rgb(0xE74C3C), Color.rgb(0xF1C40F),
                Color.rgb(0x2ECC71), Color.rgb(0x3498DB), Color.rgb(0xE74C3C)));
    }
    // #endregion

    // #region guide:drawing-pattern
    /** Sixteen by sixteen pixels holding one diagonal line: repeated, a hatch. */
    static final Image HATCH = hatch();

    static Image hatch() {
        byte[] rgba = new byte[16 * 16 * 4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((x + y) % 16 < 4) {
                    int at = (y * 16 + x) * 4;
                    rgba[at] = 0x2F;
                    rgba[at + 1] = 0x6F;
                    rgba[at + 2] = (byte) 0xDE;
                    rgba[at + 3] = (byte) 255;
                }
            }
        }
        return new Image(16, 16, rgba);
    }

    static void pattern(Canvas canvas) {
        // Repeated on both axes, turned a little under a matrix of its own.
        ImagePaint hatch = ImagePaint.of(HATCH).withTransform(Affine2D.rotation(0.4));
        canvas.fillRoundRect(12, 12, 136, 96, 14, hatch);
        canvas.drawRoundRect(RoundRect.of(12, 12, 136, 96, 14), 2, BLUE);
    }
    // #endregion

    // #region guide:drawing-transforms
    static void transforms(Canvas canvas) {
        // Five petals: each is the same ellipse, turned about the flower's middle.
        for (int i = 0; i < 5; i++) {
            canvas.save();
            canvas.rotate((float) (i * 2 * Math.PI / 5), 48, 60);
            canvas.fillEllipse(48, 36, 9, 20, ORANGE.withAlpha(0.85f));
            canvas.restore();
        }
        canvas.fillCircle(48, 60, 8, TEAL);
        // A square leaned by a shear, through a matrix of the application's.
        canvas.save();
        canvas.concat(Affine2D.translation(124, 38).shear(-0.4, 0));
        canvas.fillRect(-18, 0, 36, 44, BLUE);
        canvas.restore();
    }
    // #endregion

    // #region guide:drawing-clips
    static void clips(Canvas canvas) {
        // Stripes, seen only through a star.
        canvas.save();
        canvas.clipPath(star(42, 62, 40, FillRule.NON_ZERO));
        for (float x = 0; x < 84; x += 12) {
            canvas.fillRect(x, 0, 6, HEIGHT, BLUE);
            canvas.fillRect(x + 6, 0, 6, HEIGHT, ORANGE);
        }
        canvas.restore();
        // A gradient cut to a rounded rectangle.
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(92, 22, 56, 80, 18));
        canvas.fillRect(84, 0, 76, HEIGHT, new LinearGradient(84, 0, 160, HEIGHT, TEAL, BLUE));
        canvas.restore();
    }
    // #endregion

    // #region guide:drawing-layers
    static void layers(Canvas canvas) {
        // An eraser: inside a layer, a draw in DESTINATION_OUT takes away what the layer drew.
        canvas.saveLayer(8, 14, 70, 92, 1);
        canvas.fillRoundRect(8, 14, 70, 92, 12, BLUE);
        canvas.setBlendMode(BlendMode.DESTINATION_OUT);
        canvas.fillCircle(43, 60, 22, Color.BLACK);             // a hole right through
        canvas.fillCircle(66, 30, 14, Color.BLACK.withAlpha(0.5f)); // half of it taken away
        canvas.restore();
        // Paint inside a shape: SOURCE_IN paints only where the layer already drew.
        canvas.saveLayer(84, 14, 70, 92, 1);
        canvas.fillPath(star(119, 62, 36, FillRule.NON_ZERO), Color.BLACK);
        canvas.setBlendMode(BlendMode.SOURCE_IN);
        canvas.fillRect(84, 14, 70, 92, new LinearGradient(84, 14, 154, 106, ORANGE, TEAL));
        canvas.restore();
    }
    // #endregion

    // #region guide:drawing-blend
    static void blend(Canvas canvas) {
        // In a layer, the second disc multiplies the first and nothing else: the layer starts
        // transparent, so whatever is behind it is left out of the mix.
        canvas.saveLayer(16, 20, 128, 80, 1);
        canvas.fillCircle(58, 60, 36, ORANGE);
        canvas.setBlendMode(BlendMode.MULTIPLY);   // darkens where the two overlap
        canvas.fillCircle(102, 60, 36, TEAL);
        canvas.restore();
    }
    // #endregion

    // #region guide:drawing-text-path
    /** A word as a path: stroked with a dashed line, and filled with a gradient through its letters. */
    static void textAsPath(Canvas canvas, TextRuler ruler) {
        ShapedText word = ruler.shape("Limn", Font.of(46).withWeight(800));
        float x = (WIDTH - word.metrics().width()) / 2;
        Path2D outline = word.outline(x, 52);              // the glyphs where drawText would draw them
        canvas.drawPath(outline, Stroke.of(1.5f).withDash(4, 3), BLUE);

        canvas.save();
        canvas.clipPath(word.outline(x, 108));
        canvas.fillRect(0, 60, WIDTH, 60, Gradient.linear(x, 0, WIDTH - x, 0,
                Color.rgb(0xE74C3C), Color.rgb(0xF1C40F), Color.rgb(0x2ECC71), Color.rgb(0x3498DB)));
        canvas.restore();
    }
    // #endregion

    // #region guide:drawing-picture
    /** A map pin, recorded once: every drawPicture replays it. */
    static final Picture PIN = Picture.record(16, 22, c -> {
        c.fillPath(Path2D.parse("M8 0C3.6 0 0 3.4 0 7.8 0 13.6 8 22 8 22s8-8.4 8-14.2C16 3.4 12.4 0 8 0z"), ORANGE);
        c.fillCircle(8, 8, 3, Color.WHITE);
    });

    static void stamped(Canvas canvas) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            canvas.save();
            canvas.translate(80 + (float) Math.cos(angle) * 40 - 8, 60 + (float) Math.sin(angle) * 40 - 11);
            canvas.drawPicture(PIN);
            canvas.restore();
        }
    }
    // #endregion

    // #region guide:drawing-render
    /** A drawing made into an image once: twelve by twelve pixels, shown enlarged and pixelated. */
    static Image tiny() {
        return Images.render(12, 12, 1, c -> c.fillCircle(6, 6, 6,
                Gradient.sweep(6, 6, 0, Color.rgb(0xE74C3C), Color.rgb(0x3498DB), Color.rgb(0xE74C3C))));
    }

    static void enlarged(Canvas canvas, Image image) {
        canvas.save();
        canvas.setSampling(Sampling.PIXELATED);
        canvas.drawImage(image, 36, 16, 88, 88);
        canvas.restore();
    }
    // #endregion

    // ---------------------------------------------------------------- the guide's pictures

    /**
     * How much larger than its box a picture of the guide's shows each drawing: its window is a
     * whole screen of 1024 by 700 points, which two rows of two fill at this size.
     */
    static final float SCALE = 2.4f;

    /** One drawing of the guide's, enlarged, on the theme's surface, with its caption under it. */
    static final class Panel extends Widget<Panel> {
        private static final float CAPTION = 26;
        private final String caption;
        private final BiConsumer<Canvas, Panel> drawing;
        private Image image;

        Panel(String caption, BiConsumer<Canvas, Panel> drawing) {
            this.caption = caption;
            this.drawing = drawing;
            setAccessibleName(caption);
            setAccessibleRole(Accessible.Role.IMAGE);
        }

        /** The ruler the panel's scene measures and shapes its text with. */
        TextRuler ruler() {
            return textRuler();
        }

        /** The panel's image of {@link #tiny}, drawn on its first paint and kept. */
        Image tinyImage() {
            if (image == null) {
                image = tiny();
            }
            return image;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(WIDTH * SCALE, HEIGHT * SCALE + CAPTION);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float w = WIDTH * SCALE;
            float h = HEIGHT * SCALE;
            float radius = theme.tokensFor(this).radiusLarge();
            canvas.fillRoundRect(0, 0, w, h, radius, theme.surface());
            canvas.save();
            canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
            canvas.scale(SCALE, SCALE);
            drawing.accept(canvas, this);
            canvas.restore();
            Font font = theme.tokensFor(this).body();
            var m = textRuler().measure(caption, font);
            canvas.drawText(caption, (w - m.width()) / 2, h + 8 + m.ascent(), font, theme.textMuted());
        }
    }

    /** The panels of one of the guide's pictures, two to a row, in the middle of its window. */
    static Scene scene(List<Panel> panels) {
        Column rows = new Column();
        rows.gap(16).mainAlignment(Flex.MainAlignment.CENTER).crossAlignment(Flex.CrossAlignment.CENTER);
        for (int i = 0; i < panels.size(); i += 2) {
            Row row = new Row();
            row.gap(16).crossAlignment(Flex.CrossAlignment.START);
            panels.subList(i, Math.min(i + 2, panels.size())).forEach(row::add);
            rows.add(row);
        }
        return new Scene(new Padding(Insets.all(20), rows));
    }

    /** Shapes, the two fill rules, SVG data and arcs, and lines. */
    public static Scene shapesScene() {
        return scene(List.of(
                new Panel("Shapes", (c, p) -> shapes(c)),
                new Panel("Fill rules", (c, p) -> fillRules(c)),
                new Panel("SVG data and arcs", (c, p) -> svgAndArcs(c)),
                new Panel("Lines", (c, p) -> strokes(c))));
    }

    /** Gradients, an image pattern, transforms. */
    public static Scene paintsScene() {
        return scene(List.of(
                new Panel("Gradients", (c, p) -> gradients(c)),
                new Panel("An image pattern", (c, p) -> pattern(c)),
                new Panel("Transforms", (c, p) -> transforms(c))));
    }

    /** Clips, layers and a blend mode. */
    public static Scene clipsScene() {
        return scene(List.of(
                new Panel("Clips", (c, p) -> clips(c)),
                new Panel("Layers", (c, p) -> layers(c)),
                new Panel("Multiply", (c, p) -> blend(c))));
    }

    /** Text as a path, a picture stamped, an image drawn off screen. */
    public static Scene textScene() {
        return scene(List.of(
                new Panel("Text as a path", (c, p) -> textAsPath(c, p.ruler())),
                new Panel("A picture, stamped", (c, p) -> stamped(c)),
                new Panel("Drawn off screen", (c, p) -> enlarged(c, p.tinyImage()))));
    }
}
