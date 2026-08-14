package limn.demo;

import limn.components.Button;
import limn.components.Label;
import limn.components.Theme;
import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartFormats;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * The chart API: grouped and stacked bars, a filled line, and a donut whose hole holds
 * real widgets. Every chart here is configured with what an application would actually
 * write (labels, series, one or two options) and takes its colors, scale, legend,
 * tooltip and animation from the defaults.
 *
 * <p>The donut's centre is the part worth clicking: it is a {@code Column} of two labels
 * over a {@code Button}, laid out inside the ring, and the button re-pushes every series in
 * the scene so the value animation is visible on demand.
 */
final class ChartsScene {

    private ChartsScene() {
    }

    /** Standalone {@code --scene charts}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(16), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Label status = new Label("Hover a mark for its value; click one to select it.")
                .setMuted(true);

        BarChart revenue = revenueChart(status);
        BarChart channels = channelChart(status);
        LineChart latency = latencyChart(status);
        DonutChart traffic = trafficChart(status);

        Runnable shuffle = () -> {
            revenue.series().forEach(s -> s.setValues(random(4, 40, 180)));
            channels.series().forEach(s -> s.setValues(random(5, 5, 60)));
            latency.series().forEach(s -> s.setValues(random(12, 18, 90)));
            traffic.series().forEach(s -> s.setValues(random(4, 10, 50)));
        };
        traffic.setCenter(donutCentre(shuffle));

        Column column = new Column();
        column.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Charts").setRole(Label.Role.TITLE).setStrong(true));
        column.add(status);
        column.add(new SizedBox(SizedBox.UNSET, 260, row(revenue, traffic)));
        column.add(new SizedBox(SizedBox.UNSET, 260, row(latency, channels)));
        return column;
    }

    private static Widget row(Widget left, Widget right) {
        Row row = new Row();
        row.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        row.add(Expanded.of(left, 3));
        row.add(Expanded.of(right, 2));
        return row;
    }

    /** Grouped bars: two series side by side, with a title and a value format. */
    private static BarChart revenueChart(Label status) {
        BarChart chart = new BarChart();
        chart.setTitle("Revenue by quarter");
        chart.setLabels("Q1", "Q2", "Q3", "Q4");
        chart.addSeries(ChartSeries.of("Direct", 120, 145, 132, 168));
        chart.addSeries(ChartSeries.of("Partner", 80, 92, 105, 99));
        chart.setValueFormat(ChartFormats.prefix("$"));
        chart.valueAxis().setFormat(ChartFormats.compact());
        report(chart, status);
        return chart;
    }

    /** Stacked and horizontal: the layout long category labels want. */
    private static BarChart channelChart(Label status) {
        BarChart chart = new BarChart();
        chart.setTitle("Support load by channel");
        chart.setLabels("Email", "Chat", "Phone", "Forum", "Social");
        chart.addSeries(ChartSeries.of("Open", 18, 32, 9, 24, 6));
        chart.addSeries(ChartSeries.of("Waiting", 12, 8, 14, 6, 11));
        chart.addSeries(ChartSeries.of("Closed", 40, 55, 22, 31, 18));
        chart.setStacked(true);
        chart.setHorizontal(true);
        report(chart, status);
        return chart;
    }

    /** A filled, smoothed line with a gap in it: NaN is a gap, not a zero. */
    private static LineChart latencyChart(Label status) {
        LineChart chart = new LineChart();
        chart.setTitle("Latency, last 12 hours");
        chart.setLabels("00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11");
        chart.addSeries(ChartSeries.of("p50", 24, 21, 26, 30, 28, 25, 27, 33, 41, 38, 30, 26)
                .setFilled(true));
        chart.addSeries(ChartSeries.of("p99", 62, 58, 71, 88, Double.NaN, Double.NaN, 74, 91, 120,
                104, 83, 70));
        chart.setSmooth(true);
        chart.setPointRadius(2.5f);
        chart.valueAxis().setFormat(ChartFormats.unit(" ms"));
        chart.setValueFormat(ChartFormats.unit(" ms"));
        report(chart, status);
        return chart;
    }

    private static DonutChart trafficChart(Label status) {
        DonutChart chart = new DonutChart();
        chart.setTitle("Traffic sources");
        chart.setLabels("Direct", "Search", "Social", "Mail");
        chart.addSeries(ChartSeries.of("Sessions", 42, 31, 18, 9));
        chart.setLegendPosition(Chart.LegendPosition.RIGHT);
        report(chart, status);
        return chart;
    }

    /** The hole's content: a headline, a caption and a button, laid out inside the ring. */
    private static Widget donutCentre(Runnable shuffle) {
        Column centre = new Column();
        centre.gap(2).crossAlignment(Flex.CrossAlignment.CENTER);
        centre.add(new Label("100").setRole(Label.Role.TITLE).setStrong(true));
        centre.add(new Label("sessions").setRole(Label.Role.LABEL).setMuted(true));
        centre.add(new Button("Shuffle").onAction(shuffle).withControlSize(ControlSize.XSMALL));
        return centre;
    }

    /** Both callbacks, on every chart: hovering narrates, clicking selects. */
    private static void report(Chart chart, Label status) {
        chart.onPointHover(point -> {
            if (point != null) {
                status.setText(point.label() + " · " + point.series().name() + " · "
                        + chart.valueFormat().apply(point.value()));
            }
        });
        chart.onPointClick(point -> status.setText("Selected " + point.label()
                + " · " + point.series().name() + " · "
                + chart.valueFormat().apply(point.value())));
    }

    /**
     * Values for the shuffle button. Deliberately not seeded from the clock: the demo is
     * also a screenshot target, and a capture that differs run to run is not a reference.
     */
    private static double[] random(int count, double low, double high) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double t = ((seed >>> 11) & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;
            values[i] = Math.round(low + t * (high - low));
        }
        return values;
    }

    private static long seed = 0x5DEECE66DL;
}
