package limn.components.chart;

/**
 * One datum a chart can point at: what was hovered, what was clicked, what a tooltip row
 * is about.
 *
 * @param series      the series the value belongs to (in a donut, the ring's series)
 * @param seriesIndex the series' position in {@link Chart#series()}
 * @param index       the position within the series, the category index, or the slice
 * @param label       the category label for {@code index}, or {@code ""} when the chart
 *                    has fewer labels than values
 * @param value       the value itself, as the application set it (never the animated
 *                    in-between)
 * @param share       what fraction of its whole this value is (of the ring in a donut, of
 *                    its stack in a stacked bar or area), or {@link Double#NaN} when the
 *                    chart stacks nothing and the question has no answer
 * @param x           the mark's anchor x in the chart's local coordinates: the middle of a
 *                    bar's outer end, the point on a line, the middle of a slice's arc
 * @param y           the mark's anchor y in the chart's local coordinates
 */
public record ChartPoint(ChartSeries series, int seriesIndex, int index, String label,
                         double value, double share, float x, float y) {
}
