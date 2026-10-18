package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.ChartSeries;
import limn.components.date.DatePicker;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import limn.graphics.ShapedText;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every fade the toolkit draws in the scene draws its surface as one picture (ADR 071 §2.7): halfway
 * through, the surface's shadow, panel, highlight, text and icons are drawn at full strength into a
 * layer, and the layer is what fades. Drawn per draw instead, each part was translucent on its own
 * and showed through what covered it, the highlight through a row's icon, the shadow through the
 * panel, the card's border through its buttons. The pixels of the two ways are the backend's
 * ({@code GlLayerTest}); this holds each surface to the way it is drawn: a layer at the fade's
 * opacity, every draw inside it at an opacity of 1, and nothing anywhere in the frame drawn at a
 * per-draw opacity below 1.
 *
 * <p>The six of the record and the chart's tooltip: the combo box's list, the date picker's
 * calendar, the tab list, the popup menu's cascade, the dialog's card (its veil is one fill and
 * fades by its alpha), the scene's tooltip, and the chart's tooltip panel.
 */
class InSceneFadesAsOnePictureTest {

    private static final int W = 400;
    private static final int H = 300;

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private HeadlessUi ui;
    private Scene scene;

    @BeforeEach
    void start() {
        ui = new HeadlessUi(() -> nanos[0]);
        Theme.setCurrent(Theme.dark());
        limn.scene.ControlSize.setProcessDefault(limn.scene.ControlSize.MEDIUM);
    }

    @AfterEach
    void stop() {
        ui.close();
    }

    /** Keeps, for each draw, the per-draw opacity in force and the layer it went into. */
    static final class PictureCanvas extends NoopCanvas {
        record Draw(String what, float opacity, int layer) {
        }

        private final List<float[]> stack = new ArrayList<>(); // {opacity, layer index or -1}
        final List<Float> layers = new ArrayList<>();
        final List<Draw> draws = new ArrayList<>();

        PictureCanvas() {
            super(W, H);
            stack.add(new float[] {1, -1});
        }

        private float[] top() {
            return stack.get(stack.size() - 1);
        }

        @Override
        public void save() {
            super.save();
            stack.add(top().clone());
        }

        @Override
        public void saveLayer(float x, float y, float w, float h, float opacity) {
            super.save();
            layers.add(opacity);
            stack.add(new float[] {1, layers.size() - 1}); // inside, the composite carries it
        }

        @Override
        public void restore() {
            super.restore();
            if (stack.size() > 1) {
                stack.remove(stack.size() - 1);
            }
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > Math.max(0, count)) {
                restore();
            }
        }

        @Override
        public void setOpacity(float opacity) {
            top()[0] *= Math.max(0, Math.min(1, opacity));
        }

        @Override
        public float opacity() {
            return top()[0];
        }

        private void draw(String what) {
            draws.add(new Draw(what, top()[0], (int) top()[1]));
        }

        @Override public void fillRect(float x, float y, float w, float h, Paint paint) { draw("fillRect"); }
        @Override public void drawRect(float x, float y, float w, float h, float s, Paint paint) { draw("drawRect"); }
        @Override public void fillRoundRect(RoundRect r, Paint paint) { draw("fillRoundRect"); }
        @Override public void drawRoundRect(RoundRect r, float s, Paint paint) { draw("drawRoundRect"); }
        @Override public void drawShadow(RoundRect shape, Shadow shadow) { draw("drawShadow"); }
        @Override public void fillCircle(float cx, float cy, float r, Paint paint) { draw("fillCircle"); }
        @Override public void drawLine(float x1, float y1, float x2, float y2, float s, Paint paint) { draw("drawLine"); }
        @Override public void fillPath(Path2D path, Paint paint) { draw("fillPath"); }
        @Override public void drawPath(Path2D path, float s, Paint paint) { draw("drawPath"); }
        @Override public void drawText(String text, float x, float y, Font font, Paint paint) { draw("drawText"); }
        @Override public void drawText(ShapedText text, float x, float y, Paint paint) { draw("drawText"); }
        @Override public void drawImage(Image image, float x, float y, float w, float h) { draw("drawImage"); }
        @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) { draw("drawImage"); }
        @Override public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) { draw("drawImageMask"); }
    }

    // ------------------------------------------------------------------ plumbing

    private void bind(Widget<?> root, boolean canPosition) {
        bind(root, new StubWindow(canPosition));
    }

    private void bind(Widget<?> root, limn.backend.NativeWindow window) {
        scene = new Scene(root, () -> nanos[0]);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.bind(window);
        frame();
    }

    private void frame() {
        scene.renderFrame(new NoopCanvas(W, H));
    }

    private void advance(long millis) {
        nanos[0] += TimeUnit.MILLISECONDS.toNanos(millis);
        ui.runtime().drain();
        frame();
    }

    /** A whole frame halfway through the fade the step started, read for how it was drawn. */
    private void assertOnePictureHalfway(String surface, Runnable opens) {
        opens.run();
        frame(); // a fade's ticker starts on the frame after it is asked for
        advance(70);
        PictureCanvas canvas = new PictureCanvas();
        scene.requestRender();
        scene.renderFrame(canvas);
        float fading = -1;
        int inside = 0;
        for (int i = 0; i < canvas.layers.size(); i++) {
            float alpha = canvas.layers.get(i);
            if (alpha > 0 && alpha < 1) {
                fading = alpha;
                int index = i;
                inside = (int) canvas.draws.stream().filter(d -> d.layer() == index).count();
            }
        }
        if (fading < 0) {
            fail(surface + ": no layer at a fade's opacity halfway through its fade; layers "
                    + canvas.layers + ", per-draw opacities " + canvas.draws.stream()
                    .map(PictureCanvas.Draw::opacity).distinct().toList());
        }
        assertTrue(inside >= 3, surface + ": the fading picture holds the surface's parts, " + inside);
        for (PictureCanvas.Draw d : canvas.draws) {
            assertEquals(1f, d.opacity(), 1e-6f, surface + ": " + d.what()
                    + " drawn at a per-draw opacity, which would show what it covers through it");
        }
    }

    // ------------------------------------------------------------------ the surfaces

    @Test
    void theComboBoxListFadesAsOnePicture() {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"));
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        bind(new Padding(Insets.all(10), combo), true);
        assertOnePictureHalfway("the combo box's list", combo::open);
    }

    @Test
    void theDatePickerCalendarFadesAsOnePicture() {
        DatePicker picker = new DatePicker().setClock(java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-09T12:00:00Z"), java.time.ZoneOffset.UTC));
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        bind(new Padding(Insets.all(10), picker), true);
        assertOnePictureHalfway("the date picker's calendar", picker::open);
    }

    @Test
    void theTabListFadesAsOnePicture() {
        TabbedPane pane = new TabbedPane();
        for (String title : List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")) {
            pane.addTab(title, new SizedBox(60, 60));
        }
        Column root = new Column();
        root.add(new SizedBox(200, 120, pane)); // narrower than six tabs: the list has a button
        bind(root, false); // a window that cannot place another: the list goes in the scene
        assertOnePictureHalfway("the tab list", () -> {
            scene.focusTraverse(false); // the strip, the pane's one tab stop
            drive(scene).keyEvent(limn.input.Keys.DOWN, true, false, limn.input.Keys.MOD_ALT);
            drive(scene).keyEvent(limn.input.Keys.DOWN, false, false, limn.input.Keys.MOD_ALT);
            drive(scene).inputBatchEnded();
            assertTrue(pane.isTabListOpen(), "Alt+Down opened the list");
        });
    }

    @Test
    void thePopupMenusCascadeFadesAsOnePicture() {
        Label anchor = new Label("anchor");
        bind(new Padding(Insets.all(10), anchor), true);
        PopupMenu menu = new PopupMenu(new Menu().addItem("New", () -> { }).addItem("Open", () -> { })
                .addCheck("Wrap", true, on -> { })).setDisplayMode(DisplayMode.IN_SCENE);
        assertOnePictureHalfway("the popup menu's cascade", () -> menu.showAt(anchor, 20, 20));
    }

    @Test
    void theDialogsCardFadesAsOnePicture() {
        // A window with a backend: an in-scene dialog locks the rest of the application through it.
        bind(new Label("behind"), new limn.testing.HeadlessBackend(ui.runtime()).open("Owner", W, H));
        Dialog dialog = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .addButton("Cancel", "cancel")
                .setDisplayMode(DisplayMode.IN_SCENE);
        assertOnePictureHalfway("the dialog's card", () -> dialog.show(scene));
    }

    @Test
    void theScenesTooltipFadesAsOnePicture() {
        Button owner = new Button("Hover me");
        owner.setTooltip("A tooltip");
        bind(new Padding(Insets.all(20), owner), true);
        assertOnePictureHalfway("the scene's tooltip", () -> {
            drive(scene).mouseMoved(owner.localToScene(0, 0).x() + 5, owner.localToScene(0, 0).y() + 5);
            drive(scene).inputBatchEnded();
            advance(700); // the dwell before a tooltip shows
        });
    }

    @Test
    void theChartsTooltipFadesAsOnePicture() {
        BarChart chart = new BarChart();
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        bind(new SizedBox(360, 260, chart), true);
        advance(2000); // the chart's own entry animation settles first
        assertOnePictureHalfway("the chart's tooltip panel", () -> {
            drive(scene).mouseMoved(150, 200);
            drive(scene).inputBatchEnded();
        });
    }
}
