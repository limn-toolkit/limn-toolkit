package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link BarChart} becomes in the accessible tree: one chart node, named by its title when
 * it has one and carrying which way the bars' value axis runs; under it one series node per
 * series in paint order, named by the series' own name, carrying a toggle read from the model's
 * visibility bit, placed at the legend box a click is tested against when the legend draws one
 * and at the chart's own box when it does not, and toggled from an assistive technology through
 * the same path a click on that box reaches.
 *
 * <p>ADR 039 §7's row for the chart family is wrong about this widget in six places, and each has
 * a case here. It names one hook for five classes, when a hook on the shared base would describe
 * the line chart before its own step and a hook on {@code Chart} would lie for the donut, whose
 * legend entries are slices; the hook is the bar chart's own. It promises a description generated
 * from the axes and series, when nothing in the chart holds such a string and one built in the
 * hook would allocate on every frame of the entry animation and every hover move; nothing is
 * generated, and the tooltip default and {@code setAccessibleDescription} remain. It is silent on
 * the legend, which is the chart's one operable control apart from mark clicks, and §1.6 forbids
 * deleting an operation with the box that carried it; the series carries the toggle. It names no
 * key for the series node, and the casual one, the index, hands a reader the identifier of a
 * removed series' neighbour; the key is a serial the series carries for life. It gives the series
 * nodes no bounds, when the source has two per-series geometries and only one of them, the legend
 * row, stands still through the entry animation. And it is silent on orientation, which the class
 * exposes and the model can carry.
 *
 * <p>What a blind user still loses, recorded as §11 asks: the bars are not nodes, so a click on a
 * mark, the one path that reaches {@code onPointClick}, is unreachable from a reader; and the
 * values, the category labels, the axis ticks and titles and the stacking are not published. An
 * application that hangs behaviour on a mark click offers it elsewhere until the marks become
 * nodes.
 *
 * <p>Every case drives the chart's public setters on a bound scene, clicks the scene where a
 * pointer would, or calls the scene from where a bridge stands, and reads what the scene
 * published. Nothing here builds a node, and nothing reaches into the chart: the boxes are
 * asserted by clicking them. The walk's log is captured around every case, because the role is
 * what keeps the paints-and-says-nothing warning off a toolkit class.
 */
class BarChartAccessibilityTest extends AccessibleComponentTestBase {

    private static final I18nString REVENUE = new I18nString("chart.revenue", "Revenue");
    private static final I18nString DIRECT = new I18nString("chart.direct", "Direct");
    private static final I18nString PARTNER = new I18nString("chart.partner", "Partner");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The only bundle that answers these keys; anything else falls through to the English. */
    private static final StringBundle STRINGS = (key, locale) -> {
        if (!BRAZILIAN.equals(locale)) {
            return null;
        }
        return switch (key) {
            case "chart.revenue" -> "Receita";
            case "chart.direct" -> "Direto";
            case "chart.partner" -> "Parceiro";
            default -> null;
        };
    };

    /**
     * Counts the bars a frame fills. A bar arrives as a shaped {@link RoundRect}; the legend's
     * swatches arrive through the four-float overload, which is kept out of the count so that
     * hiding a series is seen as its bars going and not as its swatch changing.
     */
    private static final class BarCountingCanvas extends FakeCanvas {
        int bars;

        BarCountingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            bars++;
        }

        @Override
        public void fillRoundRect(float x, float y, float width, float height, float radius,
                                  Paint paint) {
        }
    }

    private BarChart chart;

    /** The column the chart is bound in, for the cases that disable or mirror the container. */
    private Column root;

    /** The two series of the fixture, kept so a case can drive them through their own setters. */
    private ChartSeries direct;
    private ChartSeries partner;

    /** The scene's clock, held still unless a case is about animation. */
    private final long[] now = {1_000_000_000L};

    /** Every record the walk logged while a test was running; see the class comment. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void theChartIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
                        // The PARAMETER and not the message: the walk logs a parameterised record, so
            // getMessage() answers the unformatted "{0} paints its own content..." pattern and the
            // class name is in getParameters()[0]. Read the message here and the assertion passes
            // whatever the walk does, which is what it did until a verification read both sides.
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.chart"),
                    "the chart paints, and it declares a role, so the walk must never say it "
                            + "paints and is deleted; a warning here names a toolkit class an "
                            + "application cannot correct: " + subject + " " + record.getMessage());
        }
    }

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(STRINGS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** Four quarters in two series, titled, legend left to AUTO, unanimated. */
    private BarChart quarters() {
        BarChart under = new BarChart();
        under.setAnimationDuration(0);
        under.setTitle("Quarterly revenue");
        under.setLabels("Q1", "Q2", "Q3", "Q4");
        direct = ChartSeries.of("Direct", 120, 145, 132, 168);
        partner = ChartSeries.of("Partner", 80, 92, 105, 99);
        under.addSeries(direct);
        under.addSeries(partner);
        return under;
    }

    /**
     * Binds {@code under} as the only widget in a column, on a clock this test owns and under the
     * deterministic ruler, so that a legend box's width is a function of its label's length and
     * an animation moves only when a case moves the clock.
     */
    private void bindChart(BarChart under) {
        chart = under;
        root = new Column();
        root.add(chart);
        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new BarCountingCanvas(400, 300);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** Renders one frame the chart has been damaged for, and answers how many bars it drew. */
    private int barsDrawn() {
        BarCountingCanvas counting = (BarCountingCanvas) canvas;
        counting.bars = 0;
        chart.invalidate();
        frame();
        return counting.bars;
    }

    /** @return the one chart node in the tree */
    private AccessibleNode chartNode() {
        return node(Accessible.Role.CHART);
    }

    /** @return the series nodes under the chart, in tree order, which is paint order */
    private List<AccessibleNode> seriesNodes() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(chartNode())) {
            if (child.role() == Accessible.Role.CHART_SERIES) {
                found.add(child);
            }
        }
        return found;
    }

    private static List<String> namesOf(List<AccessibleNode> nodes) {
        List<String> names = new ArrayList<>();
        for (AccessibleNode node : nodes) {
            names.add(node.name());
        }
        return names;
    }

    private static List<Long> idsOf(List<AccessibleNode> nodes) {
        List<Long> ids = new ArrayList<>();
        for (AccessibleNode node : nodes) {
            ids.add(node.id());
        }
        return ids;
    }

    private static List<float[]> boxesOf(List<AccessibleNode> nodes) {
        List<float[]> boxes = new ArrayList<>();
        for (AccessibleNode node : nodes) {
            boxes.add(new float[] {node.x(), node.y(), node.width(), node.height()});
        }
        return boxes;
    }

    private static void assertSameBoxes(List<float[]> expected, List<float[]> actual, String why) {
        assertEquals(expected.size(), actual.size(), why);
        for (int i = 0; i < expected.size(); i++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(expected.get(i)[c], actual.get(i)[c], 0.001f,
                        why + " (series " + i + ", component " + c + ")");
            }
        }
    }

    /** Clicks the centre of {@code node}'s published box, as a pointer would. */
    private void click(AccessibleNode node) {
        float x = node.x() + node.width() / 2;
        float y = node.y() + node.height() / 2;
        scene.mouseMoved(x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    /** @return every checked-state event raised so far, in order */
    private List<AccessibleEvent> checkedEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.CHECKED) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return every state event about {@code state}, in order */
    private List<AccessibleEvent> stateEvents(Accessible.State state) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED && event.state() == state) {
                found.add(event);
            }
        }
        return found;
    }

    /** Asserts {@code inner} lies inside {@code outer}. */
    private void assertInside(AccessibleNode inner, AccessibleNode outer) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
                        && inner.x() + inner.width() <= outer.x() + outer.width()
                        && inner.y() + inner.height() <= outer.y() + outer.height(),
                inner.role() + " \"" + inner.name() + "\" lies outside " + outer.role()
                        + describe(tree()));
    }

    /** Asserts two boxes share no point. */
    private void assertDisjoint(AccessibleNode a, AccessibleNode b) {
        boolean apart = a.x() + a.width() <= b.x() || b.x() + b.width() <= a.x()
                || a.y() + a.height() <= b.y() || b.y() + b.height() <= a.y();
        assertTrue(apart, "\"" + a.name() + "\" and \"" + b.name() + "\" overlap"
                + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aTitledBarChartIsOneVerticalChartNodeWithOneToggleableSeriesNodePerSeries() {
        bindChart(quarters());

        AccessibleNode node = chartNode();
        assertEquals("Quarterly revenue", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the title is the chart's own painted text" + describe(tree()));
        assertEquals("", node.description(),
                "nothing is generated from the axes: no string in the chart holds such a thing, "
                        + "and one built in the hook would cost memory per damaged frame"
                        + describe(tree()));
        assertTrue(node.has(Accessible.State.VERTICAL),
                "the bars stand up, so the value axis runs up and down" + describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a chart is not a tab stop and the hook declares no such state" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertNull(node.actions(),
                "the chart node offers nothing: a click on a mark is pointer-only and the marks "
                        + "are not nodes in this cut" + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.selection(), describe(tree()));

        List<AccessibleNode> series = seriesNodes();
        assertEquals(List.of("Direct", "Partner"), namesOf(series),
                "one child per series, in the order they were added, which is paint and palette "
                        + "order" + describe(tree()));
        int chartIndex = tree().indexOf(node.id());
        for (AccessibleNode s : series) {
            assertEquals(Accessible.NameFrom.CONTENT, s.nameFrom(),
                    "the legend paints the series name" + describe(tree()));
            assertEquals(chartIndex, s.parent(), describe(tree()));
            assertEquals(new ToggleFacet(ToggleFacet.State.ON), s.toggle(),
                    "drawn, so on" + describe(tree()));
            assertTrue(s.has(Accessible.State.CHECKED),
                    "the builder derives the bit from the facet" + describe(tree()));
            assertTrue(s.actions().has(Accessible.Action.TOGGLE),
                    "the one verb the legend's click performs" + describe(tree()));
            assertEquals(1, s.actions().actions().size(),
                    "and nothing else: no press, no select, and not the two the walk adds for a "
                            + "tab stop, because a painted entry is not one" + describe(tree()));
            assertFalse(s.has(Accessible.State.FOCUSABLE), describe(tree()));
            assertTrue(s.has(Accessible.State.ENABLED), describe(tree()));
            assertTrue(s.has(Accessible.State.SHOWING), describe(tree()));
            assertNull(s.value(), "the numbers are chart data and are deferred by §11"
                    + describe(tree()));
            assertNull(s.selectionItem(),
                    "a shown series is not an unselected item" + describe(tree()));
            assertEquals(List.of(), s.relations(), describe(tree()));
            assertEquals(List.of(), childrenOf(s), describe(tree()));
        }
        assertEquals(4, tree().nodeCount(),
                "the window, the chart and two series; the column is scaffolding and the bars "
                        + "are not nodes" + describe(tree()));
        assertEquals(Accessible.Role.WINDOW, tree().node(0).role(), describe(tree()));

        BarChart bare = new BarChart();
        bare.setAnimationDuration(0);
        bindChart(bare);

        AccessibleNode empty = chartNode();
        assertEquals("", empty.name(),
                "nothing configured, and the hook invents no word: the role alone is what keeps "
                        + "the node out of the predicate's deletion" + describe(tree()));
        assertEquals(List.of(), childrenOf(empty), describe(tree()));
        assertTrue(empty.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(2, tree().nodeCount(), describe(tree()));
    }

    // ------------------------------------------------------------------------------- the names

    @Test
    void theNameIsTheHeldTitleAndTheTooltipFillsInOrDescribes() {
        I18n.addBundle(STRINGS);
        I18n.setLocale(Locale.ENGLISH);
        BarChart localized = quarters();
        localized.setTitle(REVENUE);
        bindChart(localized);
        long id = chartNode().id();
        assertEquals("Revenue", chartNode().name(), describe(tree()));

        I18n.setLocale(BRAZILIAN);
        frame();

        assertEquals("Receita", chartNode().name(),
                "the name is the held source re-resolved under the new language, which a string "
                        + "read through title() inside the hook could not be" + describe(tree()));
        assertEquals(id, chartNode().id(), describe(tree()));
        assertEquals(BRAZILIAN, chartNode().locale(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "the title moved and the two literal series names did not: " + bridge.events);
        I18n.setLocale(Locale.ENGLISH);
        frame();
        bridge.events.clear();

        chart.setTitle((String) null);
        frame();

        assertEquals("", chartNode().name(),
                "no title is legal and leaves the node nameless rather than deleted"
                        + describe(tree()));
        assertEquals(id, chartNode().id(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        bridge.events.clear();

        chart.setTooltip("Revenue by quarter and channel");
        frame();

        assertEquals("Revenue by quarter and channel", chartNode().name(),
                "with no title the walk's tooltip default names the chart; declaring a name in "
                        + "the hook when there is no title would shadow it" + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, chartNode().nameFrom(), describe(tree()));
        assertEquals("", chartNode().description(), describe(tree()));

        chart.setTitle("Quarterly revenue");
        frame();

        assertEquals("Quarterly revenue", chartNode().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, chartNode().nameFrom(), describe(tree()));
        assertEquals("Revenue by quarter and channel", chartNode().description(),
                "once the title supplies the name the walk turns the tooltip into the "
                        + "description; the hook declares none of its own" + describe(tree()));
    }

    @Test
    void seriesNamesFollowTheLanguageAndARenameKeepsTheKey() {
        I18n.addBundle(STRINGS);
        I18n.setLocale(Locale.ENGLISH);
        BarChart localized = new BarChart();
        localized.setAnimationDuration(0);
        localized.setLabels("Q1", "Q2");
        direct = ChartSeries.of(DIRECT, 1, 2);
        partner = ChartSeries.of(PARTNER, 3, 4);
        localized.addSeries(direct);
        localized.addSeries(partner);
        bindChart(localized);
        List<Long> ids = idsOf(seriesNodes());
        assertEquals(List.of("Direct", "Partner"), namesOf(seriesNodes()), describe(tree()));

        I18n.setLocale(BRAZILIAN);
        frame();

        assertEquals(List.of("Direto", "Parceiro"), namesOf(seriesNodes()),
                "the names are the sources the series hold, re-resolved; never name()"
                        + describe(tree()));
        assertEquals(ids, idsOf(seriesNodes()), "same nodes" + describe(tree()));
        assertEquals(2, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
        bridge.events.clear();

        partner.setName("Channel");
        frame();

        assertEquals(List.of("Direto", "Channel"), namesOf(seriesNodes()), describe(tree()));
        assertEquals(ids, idsOf(seriesNodes()),
                "a rename is a new name on the same node, never a new node" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        AccessibleEvent renamed = bridge.events.stream()
                .filter(e -> e.type() == AccessibleEvent.Type.NAME_CHANGED)
                .findFirst().orElseThrow();
        assertEquals(ids.get(1), renamed.nodeId(), bridge.events.toString());
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "a shorter name re-centres the legend row, and the boxes follow it in the same "
                        + "frame: " + bridge.events);
    }

    // ---------------------------------------------------------------------------- the identity

    @Test
    void aSeriesKeepsItsNodeWhenItsNeighbourIsRemovedAndANewOneTakesANewId() {
        BarChart three = quarters();
        ChartSeries online = ChartSeries.of("Online", 40, 44, 51, 60);
        three.addSeries(online);
        bindChart(three);
        List<AccessibleNode> before = seriesNodes();
        assertEquals(List.of("Direct", "Partner", "Online"), namesOf(before), describe(tree()));
        long partnerId = before.get(1).id();
        long onlineId = before.get(2).id();

        chart.removeSeries(direct);
        frame();

        List<AccessibleNode> after = seriesNodes();
        assertEquals(List.of("Partner", "Online"), namesOf(after), describe(tree()));
        assertEquals(List.of(partnerId, onlineId), idsOf(after),
                "the key is the series' own serial and not its position: removing the first "
                        + "series must not hand the reader its identifier for the second"
                        + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "one series went, one node destroyed: " + bridge.events);
        assertEquals(before.get(0).id(), bridge.events.stream()
                .filter(e -> e.type() == AccessibleEvent.Type.NODE_DESTROYED)
                .findFirst().orElseThrow().nodeId(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "nobody was renamed, which an index key would have made it look like: "
                        + bridge.events);
        bridge.events.clear();

        ChartSeries retail = ChartSeries.of("Retail", 10, 12, 9, 14);
        chart.addSeries(retail);
        frame();

        after = seriesNodes();
        assertEquals(List.of("Partner", "Online", "Retail"), namesOf(after), describe(tree()));
        assertEquals(partnerId, after.get(0).id(), describe(tree()));
        assertEquals(onlineId, after.get(1).id(), describe(tree()));
        assertNotEquals(before.get(0).id(), after.get(2).id(),
                "a new series is a new node, not the departed one's identifier reused"
                        + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
        bridge.events.clear();

        chart.clearSeries();
        frame();

        assertEquals(List.of(), childrenOf(chartNode()), describe(tree()));
        assertEquals(3, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
        assertEquals("Quarterly revenue", chartNode().name(),
                "the chart node stays with its name" + describe(tree()));
    }

    // -------------------------------------------------------------------------- the orientation

    @Test
    void theOrientationFollowsSetHorizontalAndIsPublished() {
        bindChart(quarters());
        int published = bridge.published.size();

        chart.setHorizontal(true);
        frame();

        AccessibleNode node = chartNode();
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "on its side the value axis runs left to right" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(published + 1, bridge.published.size(),
                "the flip invalidates, and the difference is what publishes it");
        assertEquals(1, stateEvents(Accessible.State.HORIZONTAL).size(), bridge.events.toString());
        assertEquals(Boolean.TRUE, stateEvents(Accessible.State.HORIZONTAL).get(0).newValue(),
                bridge.events.toString());
        assertEquals(1, stateEvents(Accessible.State.VERTICAL).size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, stateEvents(Accessible.State.VERTICAL).get(0).newValue(),
                bridge.events.toString());
        assertEquals(List.of("Direct", "Partner"), namesOf(seriesNodes()),
                "the series are the same either way up" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the boxes

    /**
     * Clicks the centre of every published series box and asserts that exactly that series
     * toggled, then restores it; and that every box lies inside the chart's.
     */
    private void assertEveryBoxIsTheBoxAClickLandsIn(String when) {
        AccessibleNode outer = chartNode();
        List<AccessibleNode> series = seriesNodes();
        assertEquals(2, series.size(), when + describe(tree()));
        ChartSeries[] model = {direct, partner};
        for (int i = 0; i < series.size(); i++) {
            AccessibleNode s = series.get(i);
            assertTrue(s.width() > 0 && s.height() > 0, when + describe(tree()));
            assertInside(s, outer);
            click(s);
            for (int j = 0; j < model.length; j++) {
                assertEquals(j != i, model[j].isVisible(),
                        when + ": the centre of series " + i + "'s published box toggled series "
                                + j + ". The published box has to be the box the legend's hit "
                                + "test answers, as it stands at publish time" + describe(tree()));
            }
            model[i].setVisible(true);
            frame();
        }
    }

    @Test
    void everySeriesBoxIsTheLegendBoxAClickLandsInBelowBesideAndMirrored() {
        bindChart(quarters());
        AccessibleNode outer = chartNode();
        List<AccessibleNode> below = seriesNodes();

        assertDisjoint(below.get(0), below.get(1));
        for (AccessibleNode s : below) {
            assertTrue(s.y() >= outer.y() + outer.height() / 2,
                    "AUTO with two entries draws the legend under the plot, so both rows sit in "
                            + "the chart's lower half" + describe(tree()));
        }
        assertTrue(below.get(0).x() < below.get(1).x(),
                "reading left to right the first entry is leftmost" + describe(tree()));
        assertEveryBoxIsTheBoxAClickLandsIn("on the first frame, legend below");

        chart.setLegendPosition(Chart.LegendPosition.LEFT);
        frame();

        List<AccessibleNode> beside = seriesNodes();
        assertEquals(idsOf(below), idsOf(beside), "moving the legend moves boxes, not nodes"
                + describe(tree()));
        assertEquals(beside.get(0).x(), beside.get(1).x(), 0.001f,
                "a legend on the left stacks its rows at one edge" + describe(tree()));
        assertTrue(beside.get(0).x() < outer.x() + outer.width() / 4,
                "and that edge is the chart's left" + describe(tree()));
        assertTrue(beside.get(0).y() + beside.get(0).height() <= beside.get(1).y(),
                "the first series above the second" + describe(tree()));
        assertEveryBoxIsTheBoxAClickLandsIn("legend on the left");

        chart.setLegendPosition(Chart.LegendPosition.BOTTOM);
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> mirrored = seriesNodes();
        assertEquals(namesOf(below), namesOf(mirrored),
                "the legend mirrors the boxes and not the order: the first series is still the "
                        + "one the reader meets first" + describe(tree()));
        assertEquals(idsOf(below), idsOf(mirrored), describe(tree()));
        assertTrue(mirrored.get(0).x() > mirrored.get(1).x(),
                "reading right to left the first entry is rightmost" + describe(tree()));
        assertEveryBoxIsTheBoxAClickLandsIn("mirrored");
    }

    @Test
    void aSeriesWithNoLegendRowTakesTheChartsBoxAndOffersNoVerb() throws Exception {
        BarChart single = new BarChart();
        single.setAnimationDuration(0);
        single.setLabels("Q1", "Q2", "Q3", "Q4");
        direct = ChartSeries.of("Direct", 120, 145, 132, 168);
        single.addSeries(direct);
        bindChart(single);

        AccessibleNode outer = chartNode();
        List<AccessibleNode> series = seriesNodes();
        assertEquals(List.of("Direct"), namesOf(series),
                "unlike a donut's slice, a lone series is drawn whether or not the legend is, so "
                        + "it is a node" + describe(tree()));
        AccessibleNode only = series.get(0);
        assertEquals(outer.x(), only.x(), 0.001f, describe(tree()));
        assertEquals(outer.y(), only.y(), 0.001f, describe(tree()));
        assertEquals(outer.width(), only.width(), 0.001f, describe(tree()));
        assertEquals(outer.height(), only.height(), 0.001f,
                "AUTO with one entry draws no legend, so the series has no row of its own and "
                        + "\"somewhere in this chart\" is the honest box; the bars' union would "
                        + "move on every frame of the entry animation" + describe(tree()));
        assertEquals(new ToggleFacet(ToggleFacet.State.ON), only.toggle(), describe(tree()));
        assertNull(only.actions(),
                "no legend row, so no pointer could toggle it, so no verb" + describe(tree()));

        perform(only.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(direct.isVisible(),
                "refused the way the pointer is: there is no row to click. The scene accepts "
                        + "any id it published, and it is the hook that answers no");
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());
    }

    @Test
    void theBoxesPublishedInAResizedFrameAreAlreadyTheFinalOnes() {
        bindChart(quarters());
        List<float[]> before = boxesOf(seriesNodes());

        chart.setPreferredSize(300, 160);
        frame();

        List<float[]> resized = boxesOf(seriesNodes());
        assertNotEquals(before.get(0)[1], resized.get(0)[1],
                "the legend hangs from the bottom edge, so a shorter chart moves it up: the case "
                        + "has to have moved something" + describe(tree()));
        bridge.events.clear();

        chart.invalidate();
        frame();

        assertSameBoxes(resized, boxesOf(seriesNodes()),
                "the frame that laid the chart out published the boxes that layout produced, so "
                        + "the next damaged frame has nothing to correct; a hook reading the "
                        + "region pass's array as the paint left it would publish the previous "
                        + "frame's boxes and raise a bounds change here");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED), bridge.events.toString());
        assertEveryBoxIsTheBoxAClickLandsIn("after a resize");
    }

    // ------------------------------------------------------------------------------ the action

    @Test
    void aToggleFromTheBridgeReachesTheSamePathAClickReachesAndIsHeardAsAStateChange()
            throws Exception {
        bindChart(quarters());
        assertEquals(8, barsDrawn(), "the fixture has to draw every bar first");
        bridge.events.clear();
        AccessibleNode partnerNode = seriesNodes().get(1);

        assertTrue(perform(partnerNode.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertFalse(partner.isVisible(),
                "the hook reaches toggleLegendEntry, which is what a click on the entry reaches");
        assertTrue(direct.isVisible());
        assertEquals(4, barsDrawn(), "the next painted frame drops its four bars");

        AccessibleNode hidden = seriesNodes().get(1);
        assertEquals(partnerNode.id(), hidden.id(), describe(tree()));
        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), hidden.toggle(), describe(tree()));
        assertFalse(hidden.has(Accessible.State.CHECKED), describe(tree()));
        assertTrue(hidden.width() > 0,
                "a hidden series' legend row is still drawn, struck through, so it is not off "
                        + "screen" + describe(tree()));
        List<AccessibleEvent> raised = checkedEvents();
        assertEquals(1, raised.size(), "one flip, one event: " + bridge.events);
        assertEquals(partnerNode.id(), raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.FALSE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a toggle is acknowledged by its state change and not by an invocation: "
                        + bridge.events);

        bridge.events.clear();
        assertTrue(perform(partnerNode.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE));

        assertTrue(partner.isVisible());
        assertEquals(8, barsDrawn());
        assertEquals(new ToggleFacet(ToggleFacet.State.ON), seriesNodes().get(1).toggle(),
                describe(tree()));
        raised = checkedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(Boolean.TRUE, raised.get(0).newValue(), bridge.events.toString());
    }

    @Test
    void aSeriesHiddenThroughItsOwnSetterPublishesOffOnce() {
        bindChart(quarters());
        long partnerId = seriesNodes().get(1).id();
        int published = bridge.published.size();

        partner.setVisible(false);
        frame();

        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), seriesNodes().get(1).toggle(),
                describe(tree()));
        assertEquals(partnerId, seriesNodes().get(1).id(), describe(tree()));
        assertEquals(published + 1, bridge.published.size(), "one change, one snapshot");
        assertEquals(1, checkedEvents().size(), bridge.events.toString());
        assertEquals(partnerId, checkedEvents().get(0).nodeId(), bridge.events.toString());
    }

    @Test
    void noVerbIsOfferedWhereNoPointerCouldToggleAndEveryOtherVerbIsRefused() throws Exception {
        bindChart(quarters());
        long partnerId = seriesNodes().get(1).id();

        chart.setLegendPosition(Chart.LegendPosition.NONE);
        frame();

        assertEquals(List.of("Direct", "Partner"), namesOf(seriesNodes()),
                "the series are still drawn, so they are still nodes" + describe(tree()));
        for (AccessibleNode s : seriesNodes()) {
            assertNull(s.actions(), "no legend, no row, no verb" + describe(tree()));
            assertEquals(chartNode().x(), s.x(), 0.001f, describe(tree()));
            assertEquals(chartNode().width(), s.width(), 0.001f, describe(tree()));
        }
        perform(partnerId, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(partner.isVisible(), "a verb not offered was performed");
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        chart.setLegendPosition(Chart.LegendPosition.BOTTOM);
        chart.setLegendInteractive(false);
        frame();

        for (AccessibleNode s : seriesNodes()) {
            assertNull(s.actions(),
                    "the pointer's toggle requires the flag, so the verb is not offered without it"
                            + describe(tree()));
            assertEquals(new ToggleFacet(ToggleFacet.State.ON), s.toggle(),
                    "the state is still a fact: the swatch paints it either way" + describe(tree()));
            assertTrue(s.height() < chartNode().height(),
                    "the row is drawn, so the box is the row's" + describe(tree()));
        }
        perform(partnerId, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(partner.isVisible(), "a verb not offered was performed");

        chart.setLegendInteractive(true);
        frame();
        assertTrue(seriesNodes().get(1).actions().has(Accessible.Action.TOGGLE), describe(tree()));
        bridge.events.clear();

        perform(partnerId, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(partnerId, Accessible.Action.SELECT, Accessible.Argument.NONE);
        perform(partnerId, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0));
        perform(chartNode().id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();

        assertTrue(partner.isVisible(), "the hook answers false for everything but TOGGLE");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        chart.setEnabled(false);
        frame();

        assertFalse(seriesNodes().get(1).has(Accessible.State.ENABLED), describe(tree()));
        perform(partnerId, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(partner.isVisible(),
                "a disabled chart refuses the toggle, as its click path does");
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        chart.setEnabled(true);
        root.setEnabled(false);
        frame();

        assertTrue(chart.isEnabled(), "the fixture has to leave the chart's own flag alone");
        assertFalse(seriesNodes().get(1).has(Accessible.State.ENABLED),
                "the owner's bits ride on every node it drew" + describe(tree()));
        perform(partnerId, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(partner.isVisible(),
                "a control inside a disabled container is one the pointer refuses too");
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * Stacked, because that is the one shape in which a hidden series is still drawn for a
     * while: it keeps its slot and shrinks out of the column as what stood on it slides down.
     * Grouped, the slot itself is gone on the next paint and there is no fade to be misread.
     */
    @Test
    void theToggleStateIsTheVisibilityBitAndNotTheFade() {
        BarChart animated = quarters();
        animated.setStacked(true);
        animated.setAnimationDuration(0.55);
        bindChart(animated);
        int published = bridge.published.size();
        // Run the entry animation out first: a bar that has not grown yet is not drawn, and the
        // case below counts bars to see the fade still drawing the series a reader was told is
        // off. The tree must not move while the bars grow.
        for (int i = 0; i < 8; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(250);
            frame();
        }
        assertEquals(8, barsDrawn(), "the bars have to have grown all the way");
        assertEquals(published, bridge.published.size(),
                "the entry animation moves every bar on every frame and nothing a reader hears: "
                        + "the boxes are the legend rows, not the bars");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        partner.setVisible(false);

        assertEquals(8, barsDrawn(),
                "the clock has not moved, so the stack has not begun to shrink the series out");
        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), seriesNodes().get(1).toggle(),
                "and the tree already says off: the facet is the model's bit, which the setter "
                        + "flipped before arming the fade" + describe(tree()));
        assertEquals(published + 1, bridge.published.size(), "one change, one snapshot");
        assertEquals(1, checkedEvents().size(), bridge.events.toString());
        published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 6; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(30);
            int drawn = barsDrawn();
            if (i < 3) {
                assertEquals(8, drawn, "the series is still shrinking on frame " + i
                        + ": a hook that read the fade would move on every one of these");
            }
        }

        assertEquals(published, bridge.published.size(),
                "nothing further while the fade runs");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void newValuesAndAHoverPublishNothing() {
        bindChart(quarters());
        int published = bridge.published.size();

        partner.setValues(10, 20, 30, 40);
        frame();

        assertEquals(published, bridge.published.size(),
                "the numbers are not in the tree, so a re-valued series is not a change a reader "
                        + "hears");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        AccessibleNode outer = chartNode();
        scene.mouseMoved(outer.x() + outer.width() / 2, outer.y() + outer.height() / 2);
        scene.inputBatchEnded();
        frame();

        assertTrue(chart.hoveredPoint() != null,
                "the pointer over the middle of the plot has to have found a datum, or the case "
                        + "proves nothing");
        assertEquals(published, bridge.published.size(),
                "the hover band and the tooltip are painted and are not nodes");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    @Test
    void aQuietBarChartPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindChart(quarters());
        partner.setVisible(false); // a struck entry, so both facet values are on the walk
        frame();
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            chart.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A title read with title(), a series name with name(), a description composed from the
        // axes, the variable-argument action call, or an unconditional region pass under a ruler
        // that does not memoize is memory spent per damaged frame concluding that nothing moved,
        // and a chart is damaged on every frame of its entry animation and every hover move. The
        // paint's own rounded rectangles are the widget's cost and cancel between the two
        // measurements; what must not remain is the walk's.
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            chart.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            chart.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a bar chart that did not move must cost no memory: the title and the "
                        + "series names are I18nStrings the chart already holds, compared by "
                        + "reference, the boxes are four floats each read from the array the hit "
                        + "test reads, and the toggle and the orientation are bits");
    }

    /** @return whether any node in {@code tree} carries {@code role} */
    private static boolean anyNodeIs(AccessibleTree tree, Accessible.Role role) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aChartWithNoSeriesHasNoSeriesNodesAndStillTheChart() {
        bindChart(quarters());
        assertTrue(anyNodeIs(tree(), Accessible.Role.CHART_SERIES));

        chart.setSeries();
        frame();

        assertFalse(anyNodeIs(tree(), Accessible.Role.CHART_SERIES), describe(tree()));
        assertEquals("Quarterly revenue", chartNode().name(), describe(tree()));
    }
}
