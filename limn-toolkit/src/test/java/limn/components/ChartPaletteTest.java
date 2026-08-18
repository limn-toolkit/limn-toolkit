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
    void theDefaultFollowsTheThemesMode() {
        assertSame(ChartPalette.forDarkSurface(), ChartPalette.defaultFor(Theme.dark()));
        assertSame(ChartPalette.forLightSurface(), ChartPalette.defaultFor(Theme.light()));
        assertSame(ChartPalette.forDarkSurface(), ChartPalette.defaultFor(Theme.limn()));
        assertSame(ChartPalette.forLightSurface(), ChartPalette.defaultFor(Theme.limnLight()));
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
