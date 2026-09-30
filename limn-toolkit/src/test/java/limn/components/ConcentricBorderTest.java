package limn.components;

import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A bordered surface's border lies inside its box and is concentric with it, and nothing the
 * border surrounds reaches past its centre line.
 *
 * <p>The defect this is written against was seen on a screen before it was understood: a red
 * swatch with a grey border showed red pixels outside the grey at every corner. The fill covered
 * the whole box, {@code (0, 0, w, h)} with radius {@code r}, and the border was stroked on the box
 * inset by half a point with the <em>same</em> radius. Those two outlines are not concentric: the
 * stroke's outer edge is a radius {@code r + 0.5} arc about a centre half a point further in, which
 * on the diagonal falls {@code 0.5 × (√2 − 1)}, about 0.2 pt, short of the fill's corner, and the
 * fill's antialiased edge showed past it. So the assertions are about geometry, read off the calls a widget makes, because
 * a headless test has no pixels: the stroke's path is the box inset by half the stroke with the
 * radius reduced by as much, and every fill painted under it, or the rounded clip bounding that
 * fill, lies inside that path.
 *
 * <p>Each case names the box from the widget's own layout and the radius from its own tokens, so
 * the test does not learn the geometry from the calls it is checking.
 */
class ConcentricBorderTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    /** Points sampled along each quarter arc of a fill's outline. */
    private static final int ARC_STEPS = 32;

    /** The date widgets' today, fixed, so nothing painted depends on when the test runs. */
    private static final Clock SEPTEMBER_27 =
            Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    /**
     * A clock the test moves, so a focus fade can be run to its end: the thickened border is
     * where a stroke centred half a point in reached half a point outside its box.
     */
    @BeforeEach
    void installControllableClock() {
        ui.close();
        ui = new HeadlessUi(nanos::get);
        runtime = ui.runtime();
    }

    // ------------------------------------------------------------ the text cluster

    @Test
    void aSecondaryButtonsBorderIsConcentricWithItsFace() {
        Button button = new Button("Save").setSecondary(true);
        BorderCanvas canvas = paint(button, 120, 32);
        assertFilled(canvas, "secondary button", 0, 0, 120, 32, tokens(button).radiusMedium());
    }

    @Test
    void aTextFieldsBorderIsConcentricWithItsFace() {
        TextField field = new TextField().setText("value");
        BorderCanvas canvas = paint(field, 200, 32);
        assertFilled(canvas, "text field", 0, 0, 200, 32, tokens(field).radiusMedium());
    }

    @Test
    void aFocusedTextFieldsThickerBorderStillLiesInsideItsBox() {
        TextField field = new TextField().setText("value");
        Scene scene = scene(field, 200, 32);
        scene.requestFocus(field);
        settle(scene, field);
        BorderCanvas canvas = paint(scene, field);
        Op border = assertFilled(canvas, "focused text field", 0, 0, 200, 32,
                tokens(field).radiusMedium());
        assertEquals(Strokes.FOCUS_RING, border.stroke(), EPS, "the focus fade ran to its end");
    }

    @Test
    void aComboBoxsBorderIsConcentricWithItsFace() {
        ComboBox<String> box = new ComboBox<>(List.of("One", "Two"));
        BorderCanvas canvas = paint(box, 160, 32);
        assertFilled(canvas, "combo box", 0, 0, 160, 32, tokens(box).radiusMedium());
    }

    @Test
    void aSpinnersBorderIsConcentricWithItsFaceAndItsClip() {
        Spinner spinner = new Spinner(0, 10, 1);
        BorderCanvas canvas = paint(spinner, 140, 32);
        assertFilled(canvas, "spinner", 0, 0, 140, 32, tokens(spinner).radiusMedium());
    }

    @Test
    void aDateFieldsBorderIsConcentricWithItsFaceAndItsClip() {
        DateField field = new DateField().setClock(SEPTEMBER_27);
        BorderCanvas canvas = paint(field, 180, 32);
        assertFilled(canvas, "date field", 0, 0, 180, 32, tokens(field).radiusMedium());
    }

    @Test
    void aDatePickersBorderIsConcentricWithItsFace() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_27);
        BorderCanvas canvas = paint(picker, 220, 32);
        assertFilled(canvas, "date picker", 0, 0, 220, 32, tokens(picker).radiusMedium());
    }

    // ------------------------------------------------------------ strips and toggles

    @Test
    void aSegmentedControlsTrackBorderIsConcentricWithTheTrack() {
        SegmentedControl control = new SegmentedControl(List.of("Day", "Week", "Month"));
        Scene scene = scene(control, 400, 32);
        float natural = control.measure(Constraints.loose(400, 32)).width();
        settle(scene, control);
        BorderCanvas canvas = paint(scene, control);
        // The track is centred in whatever the parent handed over and is its natural width.
        assertFilled(canvas, "segmented control", (400 - natural) / 2, 0, natural, 32,
                tokens(control).radiusMedium());
    }

    @Test
    void aToolBarsBorderIsConcentricWithItsFace() {
        ToolBar bar = new ToolBar();
        bar.addItem(new Button("Cut"));
        BorderCanvas canvas = paint(bar, 200, 40);
        assertFilled(canvas, "tool bar", 0, 0, 200, 40, tokens(bar).radiusMedium());
    }

    @Test
    void aCheckedCheckboxsThickerBorderIsConcentricWithItsFill() {
        Checkbox box = new Checkbox("Enabled").setChecked(true);
        BorderCanvas canvas = paint(box, 160, 32);
        SizeTokens t = tokens(box);
        float side = t.indicator();
        Op border = assertFilled(canvas, "checkbox", 0, (32 - side) / 2, side, side,
                t.indicatorRadius());
        assertEquals(Strokes.INDICATOR_BORDER, border.stroke(), EPS,
                "the indicator's pen, which is not a point wide");
    }

    // --------------------------------------------------------------- the colour pair

    @Test
    void aColourButtonsFaceAndChipBordersAreConcentric() {
        ColorPickerButton button = new ColorPickerButton(Color.rgb(0xE11D48));
        BorderCanvas canvas = paint(button, 160, 32);
        SizeTokens t = tokens(button);
        assertFilled(canvas, "colour button face", 0, 0, 160, 32, t.radiusMedium());
        float chip = t.iconBox();
        assertFilled(canvas, "colour button chip", t.padH(), (32 - chip) / 2, chip, chip,
                t.radiusSmall());
    }

    @Test
    void theColourPickersFieldRampRailsAndSwatchBordersAreConcentric() {
        ColorPicker picker = new ColorPicker();
        picker.setInitialColor(Color.rgb(0xE11D48));
        BorderCanvas canvas = paint(picker, 320, 520);
        SizeTokens t = tokens(picker);
        assertFilled(canvas, "saturation/value field", picker.saturationValueField(),
                t.radiusSmall());
        assertFilled(canvas, "hue ramp", picker.hueRamp(), t.radiusSmall());
        assertFilled(canvas, "before/after swatch", picker.preview(), t.radiusSmall());
        for (Widget<?> rail : List.of(picker.rail(0), picker.alphaRail())) {
            float railH = t.colorRailH();
            float top = rail.localToSceneY() + (rail.height() - railH) / 2;
            assertFilled(canvas, "channel rail", rail.localToSceneX(), top, rail.width(), railH,
                    Math.min(railH / 2, t.radiusSmall()));
        }
    }

    // ------------------------------------------------------------------- the scene

    @Test
    void aTooltipsBorderIsConcentricWithItsPanel() {
        Widget<?> host = new Box(400, 200);
        host.setTooltip("abcd");
        Scene scene = new Scene(host, nanos::get);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 200);
        drive(scene).mouseMoved(200, 100);
        drive(scene).inputBatchEnded();
        nanos.addAndGet(5_000_000_000L);
        runtime.drain();
        settle(scene, host);
        BorderCanvas canvas = paint(scene, host);
        limn.scene.TooltipStyle style = Theme.tooltipStyle(limn.scene.ControlSize.MEDIUM);
        // The panel opens right of and below the pointer; its size is the text's, so it is read
        // off the widest thing painted there rather than re-derived.
        Op panel = null;
        for (Op op : canvas.ops) {
            if (op.kind() == Kind.STROKE && (panel == null || op.shape().width() > panel.shape().width())) {
                panel = op;
            }
        }
        assertTrue(panel != null, "the tooltip was painted");
        float half = panel.stroke() / 2;
        assertFilled(canvas, "tooltip", 212, 120, panel.shape().width() + 2 * half,
                panel.shape().height() + 2 * half, style.radius());
    }

    // --------------------------------------------------------------- the assertions

    /** {@link #assertFilled(BorderCanvas, String, float, float, float, float, float)} over a widget's own box. */
    private static Op assertFilled(BorderCanvas canvas, String what, Widget<?> widget, float radius) {
        return assertFilled(canvas, what, widget.localToSceneX(), widget.localToSceneY(),
                widget.width(), widget.height(), radius);
    }

    /**
     * Asserts that the box {@code (x, y, w, h)} with corner {@code radius} carries a border whose
     * outer edge is the box's outline, concentric with it, and that everything painted under that
     * border and belonging to the box stays inside its centre line, and that there was something
     * under it to check.
     *
     * @return the border's stroke
     */
    private static Op assertFilled(BorderCanvas canvas, String what,
                                   float x, float y, float w, float h, float radius) {
        int index = borderIndex(canvas, what, x, y, w, h);
        Op border = canvas.ops.get(index);
        RoundRect path = border.shape().normalized();
        int checked = 0;
        for (int i = 0; i < index; i++) {
            Op op = canvas.ops.get(i);
            if (op.kind() != Kind.FILL || !belongsTo(op, x, y, w, h)) {
                continue;
            }
            checked++;
            boolean inside = contains(path, op.shape());
            for (RoundRect clip : op.clips()) {
                inside |= contains(path, clip);
            }
            assertTrue(inside, what + ": a fill under the border reaches past its centre line, "
                    + "so its edge shows outside the border at the corners; fill " + op.shape()
                    + " clips " + op.clips() + " border path " + path);
        }
        assertTrue(checked > 0, what + ": nothing was painted under the border to check");
        float expected = Math.max(0, effective(RoundRect.of(x, y, w, h, radius)) - border.stroke() / 2);
        for (float corner : new float[] {path.topLeft(), path.topRight(), path.bottomRight(),
                path.bottomLeft()}) {
            assertEquals(expected, Math.min(corner, Math.min(path.width(), path.height()) / 2), EPS,
                    what + ": the border's path is the box inset by half the stroke, and its "
                            + "radius has to shrink by as much or the two corners are not "
                            + "concentric; path " + path);
        }
        return border;
    }

    /** The first stroke whose outer edge is the box: its path inset by half its width. */
    private static int borderIndex(BorderCanvas canvas, String what,
                                   float x, float y, float w, float h) {
        List<RoundRect> strokes = new ArrayList<>();
        for (int i = 0; i < canvas.ops.size(); i++) {
            Op op = canvas.ops.get(i);
            if (op.kind() != Kind.STROKE) {
                continue;
            }
            float half = op.stroke() / 2;
            RoundRect s = op.shape();
            if (Math.abs(s.x() - half - x) < EPS && Math.abs(s.y() - half - y) < EPS
                    && Math.abs(s.width() + op.stroke() - w) < EPS
                    && Math.abs(s.height() + op.stroke() - h) < EPS) {
                return i;
            }
            strokes.add(s);
        }
        fail(what + ": no stroke has the box's outline as its outer edge, so the border does not "
                + "lie inside the box (" + x + ", " + y + ", " + w + ", " + h + "); strokes "
                + strokes);
        return -1;
    }

    /** A fill is this box's if it lies in the box, or is clipped to a rounded rect that does. */
    private static boolean belongsTo(Op fill, float x, float y, float w, float h) {
        if (within(fill.shape(), x, y, w, h)) {
            return true;
        }
        for (RoundRect clip : fill.clips()) {
            if (within(clip, x, y, w, h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(RoundRect r, float x, float y, float w, float h) {
        return r.x() >= x - EPS && r.y() >= y - EPS
                && r.x() + r.width() <= x + w + EPS && r.y() + r.height() <= y + h + EPS;
    }

    /** The radius a renderer draws a uniform rounded rect with: capped at half its shorter side. */
    private static float effective(RoundRect r) {
        RoundRect n = r.normalized();
        return Math.min(n.topLeft(), Math.min(n.width(), n.height()) / 2);
    }

    /**
     * Whether {@code inner} lies inside {@code outer}. Both are convex, so it is enough that every
     * point of {@code inner}'s four corner arcs does: the straight sides join arc ends.
     */
    private static boolean contains(RoundRect outer, RoundRect inner) {
        RoundRect in = inner.normalized();
        float cap = Math.min(in.width(), in.height()) / 2;
        float[] radii = {Math.min(in.topLeft(), cap), Math.min(in.topRight(), cap),
                Math.min(in.bottomRight(), cap), Math.min(in.bottomLeft(), cap)};
        // Corner centres and the quarter each arc sweeps, in the order of radii above.
        float[][] centres = {
                {in.x() + radii[0], in.y() + radii[0]},
                {in.x() + in.width() - radii[1], in.y() + radii[1]},
                {in.x() + in.width() - radii[2], in.y() + in.height() - radii[2]},
                {in.x() + radii[3], in.y() + in.height() - radii[3]}};
        float[][] directions = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int c = 0; c < 4; c++) {
            for (int step = 0; step <= ARC_STEPS; step++) {
                double angle = Math.PI / 2 * step / ARC_STEPS;
                float px = centres[c][0] + directions[c][0] * radii[c] * (float) Math.cos(angle);
                float py = centres[c][1] + directions[c][1] * radii[c] * (float) Math.sin(angle);
                if (distance(outer, px, py) > EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Signed distance from a point to a rounded rect's outline, negative inside. */
    private static float distance(RoundRect r, float px, float py) {
        RoundRect n = r.normalized();
        float cx = n.x() + n.width() / 2;
        float cy = n.y() + n.height() / 2;
        float corner = px >= cx
                ? (py >= cy ? n.bottomRight() : n.topRight())
                : (py >= cy ? n.bottomLeft() : n.topLeft());
        float radius = Math.min(corner, Math.min(n.width(), n.height()) / 2);
        double qx = Math.abs(px - cx) - (n.width() / 2 - radius);
        double qy = Math.abs(py - cy) - (n.height() / 2 - radius);
        return (float) (Math.hypot(Math.max(qx, 0), Math.max(qy, 0))
                + Math.min(Math.max(qx, qy), 0) - radius);
    }

    // --------------------------------------------------------------------- driving

    private static SizeTokens tokens(Widget<?> widget) {
        return Theme.current().tokensFor(widget);
    }

    private Scene scene(Widget<?> root, float width, float height) {
        Scene scene = new Scene(root, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, height);
        return scene;
    }

    private BorderCanvas paint(Widget<?> root, float width, float height) {
        Scene scene = scene(root, width, height);
        settle(scene, root);
        return paint(scene, root);
    }

    /** One full frame: damaged whole, so a partial pass cannot cull what is being asserted on. */
    private static BorderCanvas paint(Scene scene, Widget<?> root) {
        root.invalidate();
        BorderCanvas canvas = new BorderCanvas(root.width(), root.height());
        scene.renderFrame(canvas);
        return canvas;
    }

    /** Runs every transition to its end: a second of frames at ten a second. */
    private void settle(Scene scene, Widget<?> root) {
        for (int i = 0; i < 10; i++) {
            nanos.addAndGet(100_000_000L);
            runtime.drain();
            scene.renderFrame(new FakeCanvas(root.width(), root.height()));
        }
    }

    /** A plain box with a fixed size, for hosting a tooltip. */
    private static final class Box extends Widget<Box> {
        private final float width;
        private final float height;

        Box(float width, float height) {
            this.width = width;
            this.height = height;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(width, height);
        }
    }

    // ----------------------------------------------------------- reading a frame

    private enum Kind { FILL, STROKE }

    /**
     * One recorded shape in scene coordinates: a fill (a rect is a rounded rect with no radius) or
     * a stroke's path and width, and the rounded clips in force when it was painted.
     */
    private record Op(Kind kind, RoundRect shape, float stroke, List<RoundRect> clips) {
    }

    /** Records fills, strokes and rounded clips, following the transforms a scene pushes. */
    private static final class BorderCanvas extends FakeCanvas {

        final List<Op> ops = new ArrayList<>();
        private final List<RoundRect> clips = new ArrayList<>();
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float tx;
        private float ty;

        BorderCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty, clips.size()});
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] outer = saved.pop();
                tx = outer[0];
                ty = outer[1];
                while (clips.size() > (int) outer[2]) {
                    clips.remove(clips.size() - 1);
                }
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRoundRect(RoundRect roundRect) {
            clips.add(moved(roundRect));
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            if (w > 0 && h > 0) {
                ops.add(new Op(Kind.FILL, RoundRect.of(tx + x, ty + y, w, h, 0), 0,
                        List.copyOf(clips)));
            }
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            ops.add(new Op(Kind.FILL, moved(roundRect), 0, List.copyOf(clips)));
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            ops.add(new Op(Kind.STROKE, moved(roundRect), strokeWidth, List.copyOf(clips)));
        }

        private RoundRect moved(RoundRect r) {
            return new RoundRect(tx + r.x(), ty + r.y(), r.width(), r.height(),
                    r.topLeft(), r.topRight(), r.bottomRight(), r.bottomLeft());
        }
    }
}
