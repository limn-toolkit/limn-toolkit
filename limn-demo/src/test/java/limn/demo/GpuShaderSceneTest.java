package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.render3d.Material;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D page's shader scene: a surface computed on the GPU every frame, moving with time. What
 * moves must reach the shader as values, or each frame compiles a program; and the motion must
 * stop when the user asked the system for less of it.
 */
class GpuShaderSceneTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void aFrameChangesTheSurfacesValuesAndNeverItsProgram() {
        Material.Surface first = GpuShaderScene.surface(0.0, 0);
        Material.Surface later = GpuShaderScene.surface(12.5, 2);

        assertEquals(GpuShaderScene.KEY, first.key());
        assertEquals(first.key(), later.key(), "the key is what the program is cached under");
        assertSame(first.outputs(), later.outputs(),
                "the expressions are built once, so equal keys cannot mean different shaders");
        assertEquals(first.values().stream().map(Material.Surface.Value::name).toList(),
                later.values().stream().map(Material.Surface.Value::name).toList(),
                "the same uniforms, in the same order, as the program looked them up");
        assertNotEquals(first.values(), later.values(), "time and palette did move");
        assertEquals(12.5f, later.values().get(0).value().x(), 0f);
    }

    @Test
    void theClockFollowsTheViewsTimeAtTheChosenSpeed() {
        GpuShaderScene.Clock clock = new GpuShaderScene.Clock();
        assertEquals(0.0, clock.advance(3.0), "the first frame only sets where the view's time is");
        assertEquals(1.0, clock.advance(4.0), 1e-9);
        clock.speed(0.5f);
        assertEquals(1.5, clock.advance(5.0), 1e-9);
        // A hidden view is not rendered and its time does not move: shown again, the clock goes on
        // from where it stood rather than catching up.
        assertEquals(1.5, clock.advance(5.0), 1e-9);
    }

    @Test
    void lessMotionStopsTheClockAndSaysSoBesideTheControls() {
        try (TestPreferences system = new TestPreferences()) {
            system.reduceMotion(true);
            GpuShaderScene.Clock clock = new GpuShaderScene.Clock();
            clock.advance(1.0);
            assertEquals(0.0, clock.advance(2.0), "the surface holds still");

            HeadlessBackend backend = new HeadlessBackend(ui.runtime());
            Scene scene = new Scene(GpuShaderScene.content(view -> { }));
            HeadlessWindow window = backend.open("Shader", 1000, 700);
            scene.bind(window);
            window.frame();
            Label paused = pausedLine(scene);
            assertTrue(paused.isVisible(), "a still surface says why it is still");

            system.reduceMotion(false);
            window.frame();
            assertFalse(paused.isVisible(), "and stops saying so when motion is back");
            assertEquals(1.0, clock.advance(3.0), 1e-9, "the clock moves again");
        }
    }

    @Test
    void theFractalIsOneProgramThatLoopsAndEndsEarly() {
        Material.Surface first = GpuShaderScene.fractal(0.0, 0);
        Material.Surface later = GpuShaderScene.fractal(17.0, 1);
        assertEquals(GpuShaderScene.FRACTAL_KEY, later.key());
        assertSame(first.outputs(), later.outputs(), "the expressions are built once");
        limn.render3d.shader.Expr.Loop loop = loopIn(first.outputs().emissive());
        assertEquals(GpuShaderScene.FRACTAL_TURNS, loop.maxIterations());
        assertTrue(loop.until() != null, "it stops where the point escapes");
        assertNotEquals(first.values(), later.values(), "the zoom, the colour and the palette moved");
    }

    @Test
    void theCloudIsOneProgramThatMarchesAColourAndADistanceAndEndsEarly() {
        Material.Surface first = GpuShaderScene.cloud(0.0, 0);
        Material.Surface later = GpuShaderScene.cloud(9.0, 2);
        assertEquals(GpuShaderScene.CLOUD_KEY, later.key());
        assertSame(first.outputs(), later.outputs(), "the expressions are built once");
        limn.render3d.shader.Expr.Loop march = loopIn(first.outputs().emissive());
        assertEquals(GpuShaderScene.CLOUD_TURNS, march.maxIterations());
        assertEquals(java.util.List.of(limn.render3d.shader.ShaderType.VEC4, limn.render3d.shader.ShaderType.FLOAT),
                march.initial().stream().map(limn.render3d.shader.Expr::type).toList(),
                "the colour gathered and the distance travelled");
        assertTrue(march.until() != null, "it stops once the cloud is opaque");
        assertNotEquals(first.values(), later.values(), "the drift and the palette moved");
    }

    @Test
    void theZoomGoesFromTheWholeSetToAThousandthOfItAndBack() {
        assertEquals(GpuShaderScene.WIDEST, GpuShaderScene.scaleAt(0), 1e-9);
        assertEquals(GpuShaderScene.DEEPEST, GpuShaderScene.scaleAt(30), 1e-9);
        assertEquals(GpuShaderScene.WIDEST, GpuShaderScene.scaleAt(60), 1e-9);
        for (double t = 0; t < 60; t += 0.5) {
            double scale = GpuShaderScene.scaleAt(t);
            assertTrue(scale <= GpuShaderScene.WIDEST + 1e-9 && scale >= GpuShaderScene.DEEPEST - 1e-12, "at " + t);
        }
    }

    private static limn.render3d.shader.Expr.Loop loopIn(limn.render3d.shader.Expr node) {
        if (node instanceof limn.render3d.shader.Expr.Loop loop) {
            return loop;
        }
        java.util.List<limn.render3d.shader.Expr> children = java.util.List.of();
        if (node instanceof limn.render3d.shader.Expr.Binary b) {
            children = java.util.List.of(b.left(), b.right());
        } else if (node instanceof limn.render3d.shader.Expr.Call c) {
            children = c.args();
        } else if (node instanceof limn.render3d.shader.Expr.Construct c) {
            children = c.args();
        } else if (node instanceof limn.render3d.shader.Expr.Swizzle w) {
            children = java.util.List.of(w.source());
        }
        for (limn.render3d.shader.Expr child : children) {
            limn.render3d.shader.Expr.Loop found = loopIn(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Label pausedLine(Scene scene) {
        for (Label label : Viewport3DPageTest.all(scene.root(), Label.class)) {
            if (label.text().equals(GpuShaderScene.Text.PAUSED.get())) {
                return label;
            }
        }
        throw new AssertionError("no line says the surface is paused");
    }
}
