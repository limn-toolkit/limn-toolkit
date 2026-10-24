package limn.components;

import limn.components.chart.ChartPalette;
import limn.graphics.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default palettes are pinned, hex by hex and in order, because their guarantee is not
 * "these look nice": the slot order is what keeps every adjacent pair apart under
 * simulated protanopia and deuteranopia, and it was chosen by measuring candidate orders
 * rather than by eye. A reorder or a re-step is a change to that guarantee; this test is
 * what makes it a decision rather than an accident.
 *
 * <p>Changing a value here means re-running the palette validation for both modes first.
 */
class ChartPaletteTest extends ComponentTestBase {

    private static final List<Integer> LIGHT_SLOTS = List.of(
            0x2A78D6, 0xEB6834, 0x1BAF7A, 0xEDA100, 0xE87BA4, 0x008300, 0x4A3AA7, 0xE34948);

    private static final List<Integer> DARK_SLOTS = List.of(
            0x3987E5, 0xD95926, 0x199E70, 0xC98500, 0xD55181, 0x008300, 0x9085E9, 0xE66767);

    @Test
    void theBuiltInSlotsAreExactlyTheValidatedOnes() {
        assertSlots(LIGHT_SLOTS, ChartPalette.forLightSurface());
        assertSlots(DARK_SLOTS, ChartPalette.forDarkSurface());
    }

    private static void assertSlots(List<Integer> expected, ChartPalette palette) {
        assertEquals(expected.size(), palette.size(), "slot count");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(Color.rgb(expected.get(i)), palette.color(i),
                    "slot " + i + " must stay " + String.format("#%06X", expected.get(i)));
        }
    }

    @Test
    void theDefaultStartsFromTheThemesModeAndMovesNothingThatAlreadyClears() {
        // High Contrast's black surfaces leave every dark slot above 3:1 as written.
        Theme highContrast = Theme.builtins().stream().filter(t -> t.name().equals("High Contrast")).findFirst().orElseThrow();
        assertEquals(ChartPalette.forDarkSurface().colors(), ChartPalette.defaultFor(highContrast).colors());
        assertSame(ChartPalette.defaultFor(Theme.light()), ChartPalette.defaultFor(Theme.light()), "solved once");
    }

    /**
     * ADR 081: every default slot clears 3:1 on its palette's canvas, card and popover, and the
     * separation the slots were chosen for survives the move (adjacent OKLab ΔE × 100 of 15
     * under normal vision and 8 under simulated protanopia and deuteranopia, Machado 2009).
     */
    @Test
    void theDefaultClearsThreeToOneEverywhereAndKeepsItsSeparation() {
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (Theme theme : Theme.builtins()) {
            ChartPalette palette = ChartPalette.defaultFor(theme);
            for (int i = 0; i < palette.size(); i++) {
                for (Color ground : List.of(theme.background(), theme.surface(), theme.surfaceRaised())) {
                    double ratio = Color.contrastRatio(palette.color(i), ground);
                    if (ratio < 3.0) {
                        wrong.add(theme.name() + " slot " + i + ": " + String.format("%.2f", ratio));
                    }
                }
                if (i + 1 < palette.size()) {
                    Color a = palette.color(i);
                    Color b = palette.color(i + 1);
                    if (deltaE(a, b, null) < 15 || deltaE(a, b, PROTAN) < 8 || deltaE(a, b, DEUTAN) < 8) {
                        wrong.add(theme.name() + " slots " + i + "/" + (i + 1) + String.format(": %.1f/%.1f/%.1f",
                                deltaE(a, b, null), deltaE(a, b, PROTAN), deltaE(a, b, DEUTAN)));
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    private static final double[][] PROTAN = {
            {0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216}, {-0.003882, -0.048116, 1.051998}};
    private static final double[][] DEUTAN = {
            {0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413}, {-0.011820, 0.042940, 0.968881}};

    /** OKLab ΔE × 100, written here again so that the palette's own arithmetic is not its judge. */
    private static double deltaE(Color a, Color b, double[][] vision) {
        double[] x = lab(a, vision);
        double[] y = lab(b, vision);
        return 100 * Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }

    private static double[] lab(Color c, double[][] vision) {
        double[] v = {lin(c.r()), lin(c.g()), lin(c.b())};
        if (vision != null) {
            double[] w = new double[3];
            for (int i = 0; i < 3; i++) {
                w[i] = Math.max(0, Math.min(1, vision[i][0] * v[0] + vision[i][1] * v[1] + vision[i][2] * v[2]));
            }
            v = w;
        }
        double l = Math.cbrt(0.4122214708 * v[0] + 0.5363325363 * v[1] + 0.0514459929 * v[2]);
        double m = Math.cbrt(0.2119034982 * v[0] + 0.6806995451 * v[1] + 0.1073969566 * v[2]);
        double s = Math.cbrt(0.0883024619 * v[0] + 0.2817188376 * v[1] + 0.6299787005 * v[2]);
        return new double[] {0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double lin(float c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    @Test
    void aSlotPastTheLastOneRepeatsItShaded() {
        ChartPalette palette = ChartPalette.forDarkSurface();
        Color first = palette.color(0);
        Color wrapped = palette.color(palette.size());
        assertNotEquals(first, wrapped, "a ninth series must not be indistinguishable from the first");
        assertTrue(wrapped.r() > first.r() && wrapped.g() > first.g(),
                "the first wrap lightens toward white");
        assertNotEquals(wrapped, palette.color(palette.size() * 2),
                "and the second wrap goes the other way");
    }

    @Test
    void aCustomPaletteNeedsAtLeastOneColor() {
        assertThrows(IllegalArgumentException.class, ChartPalette::of);
        assertThrows(IllegalArgumentException.class, () -> ChartPalette.of(List.of()));
    }

    @Test
    void aCustomPaletteIsUsedInTheOrderGiven() {
        ChartPalette palette = ChartPalette.of(Color.WHITE, Color.BLACK);
        assertEquals(Color.WHITE, palette.color(0));
        assertEquals(Color.BLACK, palette.color(1));
        assertEquals(2, palette.size());
    }
}
