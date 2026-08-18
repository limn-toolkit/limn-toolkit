/**
 * Charts: {@link limn.components.chart.BarChart}, {@link limn.components.chart.LineChart}
 * and {@link limn.components.chart.DonutChart}, over a shared
 * {@link limn.components.chart.Chart} that carries the data, the palette, the legend, the
 * tooltip, the click callback and the animation.
 *
 * <p>Every chart is a plain widget: it measures, lays out and paints like any other, takes
 * its ink from the active theme, and needs nothing configured to be readable. Feed it
 * labels and a {@link limn.components.chart.ChartSeries} and it picks colors, rounds the
 * scale, draws the legend and animates the values in.
 *
 * <pre>{@code
 * BarChart chart = new BarChart();
 * chart.setLabels("Mon", "Tue", "Wed");
 * chart.addSeries(ChartSeries.of("Signups", 12, 19, 3));
 * chart.onPointClick(point -> status.setText(point.label()));
 * }</pre>
 */
package limn.components.chart;
