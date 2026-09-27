package limn.components.chart;

import limn.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * A scale that reaches 1e15 is written in scientific notation, with the decimals its step
     * needs; below it, or where ticks sit too close for six decimals, every digit is written.
     */
    @Test
    void aLargeScaleIsWrittenInScientificNotation() {
        Locale original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
        try {
            assertEquals(List.of("0", "2E299", "4E299", "6E299", "8E299", "1E300"),
                    ticks(new ChartAxis.Scale(0, 1e300, 2e299)));
            assertEquals(List.of("0", "2.5E19", "5E19", "7.5E19", "1E20"),
                    ticks(new ChartAxis.Scale(0, 1e20, 2.5e19)), "a 2.5 step keeps its half");
            assertEquals(List.of("-1E15", "-5E14", "0", "5E14", "1E15"),
                    ticks(new ChartAxis.Scale(-1e15, 1e15, 5e14)), "the sign counts, not the side");
            assertEquals(List.of("1E16", "1.02E16", "1.04E16"),
                    ticks(new ChartAxis.Scale(1e16, 1.04e16, 2e14)), "decimals from the step");
            assertEquals(List.of("0", "200,000,000,000,000", "400,000,000,000,000"),
                    ticks(new ChartAxis.Scale(0, 4e14, 2e14)), "below 1e15 the digits stay");
            assertEquals(List.of("1,000,000,000,000,000", "1,000,000,000,000,001",
                            "1,000,000,000,000,002"),
                    ticks(new ChartAxis.Scale(1e15, 1e15 + 2, 1)),
                    "ticks six decimals cannot tell apart keep their digits");
        } finally {
            I18n.setLocale(original);
        }
    }

    private static List<String> ticks(ChartAxis.Scale scale) {
        var format = new ChartAxis(true, true).tickFormat(scale);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < scale.tickCount(); i++) {
            labels.add(format.apply(scale.tick(i)));
        }
        return labels;
    }
}
