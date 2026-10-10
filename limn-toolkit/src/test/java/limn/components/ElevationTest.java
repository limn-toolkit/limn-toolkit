package limn.components;

import limn.graphics.Canvas;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a theme says of each level of elevation, and what a file keeps of it. */
class ElevationTest {

    @Test
    void aFlatSurfaceCastsNothingAndEachLevelAboveItReachesFurther() {
        for (Theme theme : Theme.builtins()) {
            assertTrue(theme.elevation(Elevation.FLAT).isNone(), theme.name());
            float raised = theme.elevation(Elevation.RAISED).reach();
            float overlay = theme.elevation(Elevation.OVERLAY).reach();
            float modal = theme.elevation(Elevation.MODAL).reach();
            assertTrue(0 < raised && raised < overlay && overlay < modal, theme.name());
        }
    }

    @Test
    void aDarkPaletteCastsADarkerShadowThanALightOne() {
        assertTrue(Theme.dark().elevation(Elevation.OVERLAY).keyOpacity()
                > Theme.light().elevation(Elevation.OVERLAY).keyOpacity());
    }

    @Test
    void aShadowIsTwoLayersStraightDownTheWideOneFirst() {
        List<Shadow> layers = new ElevationShadow(8, 16, 0.14f, 2, 0.12f).layers();
        assertEquals(2, layers.size());
        assertEquals(new Shadow(0, 8, 16, limn.graphics.Color.BLACK.withAlpha(0.14f)), layers.get(0));
        assertEquals(new Shadow(0, 0, 2, limn.graphics.Color.BLACK.withAlpha(0.12f)), layers.get(1));
        assertEquals(List.of(), ElevationShadow.NONE.layers());
        assertEquals(8 + 1.5f * 16, new ElevationShadow(8, 16, 0.14f, 2, 0.12f).reach());
    }

    @Test
    void numbersOutOfRangeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ElevationShadow(-1, 2, 0.1f, 2, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new ElevationShadow(1, 200, 0.1f, 2, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new ElevationShadow(1, 2, 1.5f, 2, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new ElevationShadow(1, 2, Float.NaN, 2, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new Shadow(0, 0, -1, limn.graphics.Color.BLACK));
        assertThrows(IllegalArgumentException.class, () -> Theme.light().toBuilder()
                .elevation(Elevation.FLAT, new ElevationShadow(1, 2, 0.1f, 2, 0.1f)));
    }

    @Test
    void aThemeFileKeepsEachShadowAndAFileWithoutThemTakesTheModes() {
        ElevationShadow deep = new ElevationShadow(20, 40, 0.5f, 4, 0.25f);
        Theme written = Theme.light().toBuilder().name("Deep").elevation(Elevation.OVERLAY, deep)
                .elevation(Elevation.RAISED, ElevationShadow.NONE).build();
        String text = ThemeFormat.write(written);
        assertTrue(text.contains("elevation.overlay = 20.0 40.0 0.5 4.0 0.25\n"), text);
        Theme read = ThemeFormat.parse(text);
        assertEquals(written, read);
        assertEquals(deep, read.elevation(Elevation.OVERLAY));
        assertTrue(read.elevation(Elevation.RAISED).isNone());
        assertFalse(written.equals(Theme.light().toBuilder().name("Deep").build()));

        Theme bare = ThemeFormat.parse("name = Bare\ndark = true\n");
        assertEquals(Theme.dark().elevation(Elevation.MODAL), bare.elevation(Elevation.MODAL));
    }

    @Test
    void aTooltipCastsTheShadowOfASurfaceThatCovers() {
        limn.scene.TooltipStyle style = Theme.tooltipStyle(limn.scene.ControlSize.MEDIUM);
        assertEquals(Theme.current().elevation(Elevation.OVERLAY).layers(), style.shadows());
        assertEquals(Theme.current().elevation(Elevation.OVERLAY).reach(), style.shadowReach());
    }

    @Test
    void aMalformedShadowNamesItsLine() {
        for (String bad : List.of("elevation.overlay = 1 2 3", "elevation.overlay = a b c d e",
                "elevation.overlay = 1 2 9 2 0.1", "elevation.flat = 0 0 0 0 0")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ThemeFormat.parse("name = X\ndark = false\n" + bad + "\n"), bad);
            assertTrue(refused.getMessage().contains("line 3"), refused.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> ThemeFormat.parse(
                "name = X\ndark = false\nelevation.modal = 1 2 0.1 2 0.1\nelevation.modal = 1 2 0.1 2 0.1\n"));
    }

    @Test
    void aSurfaceDrawsItsLevelsShadowUnderItsFillAndDamagesItsReach() {
        List<String> drawn = new ArrayList<>();
        Canvas canvas = (Canvas) java.lang.reflect.Proxy.newProxyInstance(Canvas.class.getClassLoader(),
                new Class<?>[] {Canvas.class}, (proxy, method, args) -> {
                    if (method.getName().equals("drawShadow") || method.getName().equals("fillRoundRect")) {
                        drawn.add(method.getName());
                    }
                    return null;
                });
        try (limn.testing.HeadlessUi ui = new limn.testing.HeadlessUi()) {
            Surface surface = new Surface(new Label("x")).setElevation(Elevation.OVERLAY);
            surface.onPaint(canvas);
            assertEquals(List.of("drawShadow", "drawShadow", "fillRoundRect"), drawn);
            assertEquals(Theme.current().elevation(Elevation.OVERLAY).reach(), surface.paintOutset());
            assertEquals(0, new Surface(new Label("x")).setElevation(Elevation.FLAT).paintOutset());
        }
    }
}
