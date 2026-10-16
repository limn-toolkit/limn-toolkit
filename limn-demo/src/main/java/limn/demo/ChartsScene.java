package limn.demo;

import limn.components.Button;
import limn.components.Label;
import limn.components.Theme;
import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartPalette;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.graphics.Color;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Currency;
import java.util.function.DoubleFunction;

/**
 * A sales and support dashboard: six charts in cards, refreshed from a page toolbar. Between
 * them they show grouped, stacked and horizontal bars, stacks in groups with a palette of the
 * application's own, a line with a gap in its data, an area, and a donut whose hole holds
 * widgets.
 */
final class ChartsScene {

    /** The strings of this page, ready for translation. */
    static final class Text {
        static final I18nString REFRESH = new I18nString("showcase.charts.refresh", "Refresh data");
        static final I18nString LEGEND_HINT = new I18nString("showcase.charts.legendHint",
                "Click a legend entry to hide or show its data.");
        static final I18nString SELECTED = new I18nString("showcase.charts.selected", "{0} · {1}: {2}");

        static final I18nString REVENUE = new I18nString("showcase.charts.revenue", "Revenue by quarter");
        static final I18nString THIS_YEAR = new I18nString("showcase.charts.thisYear", "This year");
        static final I18nString LAST_YEAR = new I18nString("showcase.charts.lastYear", "Last year");
        static final I18nString Q1 = new I18nString("showcase.charts.q1", "Q1");
        static final I18nString Q2 = new I18nString("showcase.charts.q2", "Q2");
        static final I18nString Q3 = new I18nString("showcase.charts.q3", "Q3");
        static final I18nString Q4 = new I18nString("showcase.charts.q4", "Q4");

        static final I18nString CHANNELS = new I18nString("showcase.charts.channels", "Sales by channel");
        static final I18nString REVENUE_SHARE = new I18nString("showcase.charts.revenueShare", "Revenue");
        static final I18nString DIRECT = new I18nString("showcase.charts.direct", "Direct");
        static final I18nString PARTNERS = new I18nString("showcase.charts.partners", "Partners");
        static final I18nString ONLINE_STORE = new I18nString("showcase.charts.onlineStore", "Online store");
        static final I18nString MARKETPLACE = new I18nString("showcase.charts.marketplace", "Marketplace");
        static final I18nString YEAR_TO_DATE = new I18nString("showcase.charts.yearToDate", "year to date");

        static final I18nString RESPONSE = new I18nString("showcase.charts.response", "Response time today");
        static final I18nString MEDIAN = new I18nString("showcase.charts.median", "Median");
        static final I18nString SLOWEST = new I18nString("showcase.charts.slowest", "Slowest 1%");
        static final I18nString NO_SAMPLES = new I18nString("showcase.charts.noSamples",
                "Slowest 1%: no samples from {0} to {1}");

        static final I18nString TICKETS = new I18nString("showcase.charts.tickets", "Support tickets by channel");
        static final I18nString OPEN = new I18nString("showcase.charts.open", "Open");
        static final I18nString WAITING = new I18nString("showcase.charts.waiting", "Waiting on customer");
        static final I18nString CLOSED = new I18nString("showcase.charts.closed", "Closed");
        static final I18nString EMAIL = new I18nString("showcase.charts.email", "Email");
        static final I18nString CHAT = new I18nString("showcase.charts.chat", "Chat");
        static final I18nString PHONE = new I18nString("showcase.charts.phone", "Phone");
        static final I18nString PORTAL = new I18nString("showcase.charts.portal", "Portal");

        static final I18nString WAREHOUSES = new I18nString("showcase.charts.warehouses",
                "Orders shipped by warehouse");
        static final I18nString NORTH_PICKED = new I18nString("showcase.charts.northPicked", "North, picked");
        static final I18nString NORTH_PACKED = new I18nString("showcase.charts.northPacked", "North, packed");
        static final I18nString SOUTH_PICKED = new I18nString("showcase.charts.southPicked", "South, picked");
        static final I18nString SOUTH_PACKED = new I18nString("showcase.charts.southPacked", "South, packed");

        static final I18nString CUSTOMERS = new I18nString("showcase.charts.customers", "New customers");
        static final I18nString TRIAL = new I18nString("showcase.charts.trial", "Trial");
        static final I18nString PAID = new I18nString("showcase.charts.paid", "Paid");

        private Text() {
        }
    }

    /** What the amounts on this page are in. */
    private static final Currency DOLLARS = Currency.getInstance("USD");

    /** The response-time chart's first hour, and the hours its slowest series has no samples for. */
    private static final int FIRST_HOUR = 8;
    private static final int GAP_FROM = 4;
    private static final int GAP_TO = 5;

    /** Chart and card heights: a row of two cards reads as one band of the dashboard. */
    private static final float ROW_HEIGHT = 300;

    private ChartsScene() {
    }

    /** Standalone {@code --scene charts}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(16), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        BarChart revenue = revenueChart();
        DonutChart channels = channelChart();
        LineChart response = responseChart();
        BarChart tickets = ticketChart();
        BarChart warehouses = warehouseChart();
        LineChart customers = customerChart();

        Label total = new Label("").setStrong(true);
        Runnable writeTotal = () -> total.setText(wholeDollars().apply(sum(channels.series().get(0))));
        followLanguage(writeTotal);
        channels.setCenter(donutCentre(total));

        Button refresh = new Button(Text.REFRESH).setSecondary(true);
        refresh.setIcon(Tabler.outline("refresh"));
        refresh.onAction(() -> {
            revenue.series().get(0).setValues(random(4, 150_000, 240_000));
            revenue.series().get(1).setValues(random(4, 140_000, 200_000));
            channels.series().get(0).setValues(random(4, 60_000, 340_000));
            writeTotal.run();
            response.series().get(0).setValues(random(12, 120, 260));
            double[] slowest = random(12, 600, 1_400);
            slowest[GAP_FROM] = Double.NaN;
            slowest[GAP_TO] = Double.NaN;
            response.series().get(1).setValues(slowest);
            tickets.series().forEach(s -> s.setValues(random(4, 4, 60)));
            warehouses.series().forEach(s -> s.setValues(random(5, 20, 90)));
            customers.series().forEach(s -> s.setValues(random(6, 20, 120)));
        });

        Column page = Page.column();
        page.add(Page.row(12, refresh, Page.hint(Text.LEGEND_HINT)));
        page.add(band(Page.card(revenue), Page.card(channels)));
        page.add(band(Page.card(withFootnote(response, gapFootnote())), Page.card(tickets)));
        page.add(band(Page.card(warehouses), Page.card(customers)));
        return page;
    }

    /** Two cards side by side, the first wider: one band of the dashboard. */
    private static Widget<?> band(Widget<?> wide, Widget<?> narrow) {
        Row row = new Row();
        row.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        row.add(Expanded.of(wide, 3));
        row.add(Expanded.of(narrow, 2));
        return new SizedBox(SizedBox.UNSET, ROW_HEIGHT, row);
    }

    /** Grouped bars in money: two years side by side, the axis in whole dollars. */
    private static BarChart revenueChart() {
        BarChart chart = new BarChart();
        chart.setTitle(Text.REVENUE);
        chart.setLabels(Text.Q1, Text.Q2, Text.Q3, Text.Q4);
        chart.addSeries(ChartSeries.of(Text.THIS_YEAR, 182_400, 205_100, 196_800, 231_500));
        chart.addSeries(ChartSeries.of(Text.LAST_YEAR, 150_200, 171_900, 178_300, 190_600));
        chart.setValueFormat(NumberFormats.currency(DOLLARS));
        chart.valueAxis().setFormat(wholeDollars());
        reportClicks(chart);
        return chart;
    }

    /** A thin ring with gaps between its slices, the year's total in its hole. */
    private static DonutChart channelChart() {
        DonutChart chart = new DonutChart();
        chart.setTitle(Text.CHANNELS);
        chart.setLabels(Text.DIRECT, Text.PARTNERS, Text.ONLINE_STORE, Text.MARKETPLACE);
        chart.addSeries(ChartSeries.of(Text.REVENUE_SHARE, 342_600, 253_000, 146_800, 73_400));
        chart.setInnerRadius(0.72f);
        chart.setSliceGap(3);
        chart.setValueFormat(NumberFormats.currency(DOLLARS));
        chart.setLegendPosition(Chart.LegendPosition.BOTTOM);
        reportClicks(chart);
        return chart;
    }

    /** The hole's content: the total, and what it totals. */
    private static Widget<?> donutCentre(Label total) {
        Column centre = new Column();
        centre.gap(2).crossAlignment(Flex.CrossAlignment.CENTER);
        centre.add(total);
        // Wrapped and centred: in some languages "year to date" is three words wider than the hole.
        centre.add(new Label(Text.YEAR_TO_DATE).setMuted(true).setWrap(true)
                .setAlign(Label.HAlign.CENTER, Label.VAlign.TOP));
        return centre;
    }

    /**
     * Two lines through the working day, the slowest one with an hour missing: a gap in the data
     * is drawn as a gap, not as a zero, and the card's footnote says why it is there. A hover
     * reports only the mark under the pointer.
     */
    private static LineChart responseChart() {
        LineChart chart = new LineChart();
        chart.setTitle(Text.RESPONSE);
        followLanguage(() -> chart.setLabels(hours()));
        chart.addSeries(ChartSeries.of(Text.MEDIAN, 140, 152, 171, 188, 205, 196, 183, 214, 236, 198, 176, 161));
        double[] slowest = {720, 810, 905, 1_080, Double.NaN, Double.NaN, 940, 1_150, 1_320, 1_010, 870, 790};
        chart.addSeries(ChartSeries.of(Text.SLOWEST, slowest));
        chart.setSmooth(true);
        chart.setPointRadius(2.5f);
        chart.setTooltipMode(Chart.TooltipMode.POINT);
        chart.valueAxis().setFormat(NumberFormats.unit(" ms"));
        chart.setValueFormat(NumberFormats.unit(" ms"));
        reportClicks(chart);
        return chart;
    }

    /** The response chart's footnote: the hours the slowest series has no samples for. */
    private static Label gapFootnote() {
        Label note = new Label("").setMuted(true).setOverflow(Label.Overflow.ELLIPSIS);
        followLanguage(() -> note.setText(Text.NO_SAMPLES.format(
                clock(FIRST_HOUR + GAP_FROM), clock(FIRST_HOUR + GAP_TO))));
        return note;
    }

    private static Widget<?> withFootnote(Widget<?> chart, Widget<?> footnote) {
        Column column = new Column();
        column.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(Expanded.of(chart, 1));
        column.add(footnote);
        return column;
    }

    /** Stacked and horizontal: the layout long category names want. */
    private static BarChart ticketChart() {
        BarChart chart = new BarChart();
        chart.setTitle(Text.TICKETS);
        chart.setLabels(Text.EMAIL, Text.CHAT, Text.PHONE, Text.PORTAL);
        chart.addSeries(ChartSeries.of(Text.OPEN, 18, 32, 9, 24));
        chart.addSeries(ChartSeries.of(Text.WAITING, 12, 8, 14, 6));
        chart.addSeries(ChartSeries.of(Text.CLOSED, 40, 55, 22, 31));
        chart.setStacked(true);
        chart.setHorizontal(true);
        reportClicks(chart);
        return chart;
    }

    /**
     * Two stacks a day, one per warehouse, each of two series: series that share a stack key pile
     * up, and different keys stand side by side. The palette is the application's own, a dark and
     * a light tone of one hue per warehouse, so the pairs read as pairs.
     */
    private static BarChart warehouseChart() {
        BarChart chart = new BarChart();
        chart.setTitle(Text.WAREHOUSES);
        followLanguage(() -> chart.setLabels(weekdays()));
        chart.addSeries(ChartSeries.of(Text.NORTH_PICKED, 42, 55, 48, 61, 70).setStack("north"));
        chart.addSeries(ChartSeries.of(Text.NORTH_PACKED, 30, 38, 35, 44, 52).setStack("north"));
        chart.addSeries(ChartSeries.of(Text.SOUTH_PICKED, 36, 41, 52, 47, 58).setStack("south"));
        chart.addSeries(ChartSeries.of(Text.SOUTH_PACKED, 25, 33, 40, 36, 45).setStack("south"));
        chart.setStacked(true);
        chart.setPalette(ChartPalette.of(
                Color.rgb(0x1D4ED8), Color.rgb(0x93C5FD), Color.rgb(0x0F766E), Color.rgb(0x5EEAD4)));
        reportClicks(chart);
        return chart;
    }

    /** An area chart over the half year. */
    private static LineChart customerChart() {
        LineChart chart = new LineChart();
        chart.setTitle(Text.CUSTOMERS);
        followLanguage(() -> chart.setLabels(months()));
        chart.addSeries(ChartSeries.of(Text.TRIAL, 64, 72, 58, 81, 95, 88));
        chart.addSeries(ChartSeries.of(Text.PAID, 28, 31, 35, 42, 47, 55));
        chart.setArea(true);
        chart.setSmooth(true);
        chart.valueAxis().setBeginAtZero(true);
        chart.setPointRadius(0);
        reportClicks(chart);
        return chart;
    }

    /** A click on a mark says in the status line what it is. */
    private static void reportClicks(Chart<?> chart) {
        chart.onPointClick(point -> Page.report(Text.SELECTED.format(point.label(),
                point.series().name(), chart.valueFormat().apply(point.value()))));
    }

    /** Runs {@code apply} now and again whenever the language changes. */
    private static void followLanguage(Runnable apply) {
        apply.run();
        I18n.observeChanges(apply); // for the life of the page, which is the life of the demo
    }

    /** Whole dollars, as the language in effect writes money: what an axis tick wants. */
    private static DoubleFunction<String> wholeDollars() {
        return v -> {
            NumberFormat format = NumberFormat.getCurrencyInstance(I18n.locale());
            format.setCurrency(DOLLARS);
            format.setMaximumFractionDigits(0);
            return I18n.localizeDigits(I18n.toAsciiDigits(format.format(v)));
        };
    }

    /** Twelve working hours from {@link #FIRST_HOUR}, as the language writes a time of day. */
    private static String[] hours() {
        String[] hours = new String[12];
        for (int i = 0; i < hours.length; i++) {
            hours[i] = clock(FIRST_HOUR + i);
        }
        return hours;
    }

    private static String clock(int hour) {
        return I18n.localizeDigits(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(I18n.locale()).format(LocalTime.of(hour, 0)));
    }

    /** Monday to Friday, short, in the language in effect. */
    private static String[] weekdays() {
        String[] days = new String[5];
        for (int i = 0; i < days.length; i++) {
            days[i] = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, I18n.locale());
        }
        return days;
    }

    /** January to June, short, in the language in effect. */
    private static String[] months() {
        String[] months = new String[6];
        for (int i = 0; i < months.length; i++) {
            months[i] = Month.of(i + 1).getDisplayName(TextStyle.SHORT_STANDALONE, I18n.locale());
        }
        return months;
    }

    private static double sum(ChartSeries series) {
        return Arrays.stream(series.values()).filter(Double::isFinite).sum();
    }

    /**
     * Values for the refresh button. Deliberately not seeded from the clock: the demo is
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
