package limn.components.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The value axis's scale stays finite and countable at the ends of what a double holds. */
class ChartAxisScaleTest {

    /**
     * A spread wider than the largest double made the span infinite, the step 1 and the tick
     * count {@code Integer.MIN_VALUE}; a rounded-out end past the largest double was infinity.
     */
    @Test
    void aScaleAcrossTheWholeRangeOfADoubleIsFiniteAndHasAFewTicks() {
        double[][] cases = {
                {-Double.MAX_VALUE, Double.MAX_VALUE},
                {0, Double.MAX_VALUE},
                {Double.MAX_VALUE, Double.MAX_VALUE},
        };
        for (double[] range : cases) {
            ChartAxis.Scale scale = new ChartAxis(true, true).resolve(range[0], range[1]);
            String which = range[0] + " to " + range[1] + ": " + scale;
            assertTrue(Double.isFinite(scale.min()) && Double.isFinite(scale.max())
                    && Double.isFinite(scale.step()) && scale.step() > 0, which);
            assertTrue(scale.min() <= range[0] && scale.max() >= range[1] || range[0] == range[1],
                    which);
            int ticks = scale.tickCount();
            assertTrue(ticks >= 2 && ticks <= ChartAxis.Scale.MOST_TICKS, which + " ticks " + ticks);
        }
    }

    /** What an ordinary large range resolved to before is what it resolves to now. */
    @Test
    void anOrdinaryRangeKeepsItsScale() {
        ChartAxis.Scale scale = new ChartAxis(true, true).resolve(0, 1e300);
        assertEquals(new ChartAxis.Scale(0, 1e300, 2e299), scale);
        assertEquals(6, scale.tickCount());
        ChartAxis.Scale small = new ChartAxis(true, true).resolve(0, 97);
        assertEquals(new ChartAxis.Scale(0, 100, 20), small);
        assertEquals(6, small.tickCount());
    }
}
