package limn.demo;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.FillRule;
import limn.graphics.Font;
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
 * marching along their paths, and a translucent line crossing itself; and transforms, a shear, a
 * turn about a point, and a hit test through the inverse of the matrix a shape was drawn with.
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
        return new Scene(new Padding(Insets.all(20), content()));
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
}
