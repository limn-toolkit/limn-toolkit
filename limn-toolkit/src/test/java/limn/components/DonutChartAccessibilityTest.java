package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link DonutChart} becomes in the accessible tree: one chart node, named by its title and
 * described by the name of the series it draws as the ring, or named by that series when there is
 * no title; under it one series node per <em>legend entry</em>, which for a donut is one per slice,
 * named by the category label, carrying a toggle read from the hidden-slice bit, placed at the
 * legend box a click is tested against, and toggled from an assistive technology through the same
 * path a click on that box reaches; and after them, whatever real widget the hole holds.
 *
 * <p>ADR 039 §7's row for the chart family is wrong about this widget in five places, and each has
 * a case here. It promises one node per series, when the donut draws only its first visible series
 * and leaves the rest undrawn on purpose, so a node per series would publish something that is
 * nowhere on screen while the slices the legend names and a click operates went unmentioned. It
 * names the children by the series name, when a slice is named by its label and the series name
 * is what the tooltip uses as a heading, which is the chart node's description here. It promises
 * a description generated from the axes, when a donut has no axes and a generated string has no
 * source to compare and would allocate on every damaged frame. It lists no action, when a click on
 * a legend entry hides its slice and §1.6 forbids deleting an operation with the box that carried
 * it. And it says nothing about geometry: the legend boxes are written by the region pass, which
 * runs before every paint and every pointer test but not before the tree of the frame is
 * published, so the hook has to bring them up to date itself, and on the first frame there were
 * none at all. The one thing the row could not have said and the source settles is that the hook
 * lives on the donut and not on the abstract chart, because the coverage ratchet strikes a name
 * only for a class that declares its own hook, and a hook on the base would have described the
 * bar and the line chart before their own steps.
 *
 * <p>What a blind user still loses, recorded as §11 asks: the arcs are not nodes, so a click on a
 * mark, the one path that reaches {@code onPointClick}, is unreachable from a reader, and the
 * numbers behind the slices are not published; the second cut owns a per-slice value text with a
 * witness of its own.
 *
 * <p>Every case drives the chart's public setters on a bound scene, clicks the scene where a
 * pointer would, or calls the scene from where a bridge stands, and reads what the scene
 * published. Nothing here builds a node, and nothing reaches into the chart: the boxes are
 * asserted by clicking them.
 */
class DonutChartAccessibilityTest extends AccessibleComponentTestBase {

    private static final I18nString DIRECT = new I18nString("traffic.direct", "Direct");
    private static final I18nString SEARCH = new I18nString("traffic.search", "Search");
    private static final I18nString SOCIAL = new I18nString("traffic.social", "Social");
    private static final I18nString MAIL = new I18nString("traffic.mail", "Mail");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** The only bundle that answers these keys; anything else falls through to the English. */
    private static final StringBundle LABELS = (key, locale) -> {
        if (!BRAZILIAN.equals(locale)) {
            return null;
        }
        return switch (key) {
            case "traffic.direct" -> "Direto";
            case "traffic.search" -> "Busca";
            case "traffic.social" -> "Social";
            case "traffic.mail" -> "E-mail";
            default -> null;
        };
    };

    /** Counts the paths a frame fills, which for a donut is the slices its ring drew. */
    private static final class PathCountingCanvas extends FakeCanvas {
        int paths;

        PathCountingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillPath(Path2D path, Paint paint) {
            paths++;
        }
    }

    private DonutChart chart;

    /** The column the chart is bound in, for the cases that disable or mirror the container. */
    private Column root;

    /** The ring's series, kept so a case can hide it through the series' own setter. */
    private ChartSeries sessions;

    /** The scene's clock, held still unless a case is about animation. */
    private final long[] now = {1_000_000_000L};

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(LABELS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** Four traffic sources in one series, titled, with the legend beside the ring, unanimated. */
    private DonutChart traffic() {
        DonutChart under = new DonutChart();
        under.setAnimationDuration(0);
        under.setTitle("Traffic sources");
        under.setLabels("Direct", "Search", "Social", "Mail");
        sessions = ChartSeries.of("Sessions", 42, 31, 18, 9);
        under.addSeries(sessions);
        under.setLegendPosition(Chart.LegendPosition.RIGHT);
        return under;
    }

    /**
     * Binds {@code under} as the only widget in a column, on a clock this test owns and under the
     * deterministic ruler, so that a legend box's width is a function of its label's length and
     * a fade moves only when a case moves the clock.
     */
    private void bindChart(DonutChart under) {
        chart = under;
        root = new Column();
        root.add(chart);
        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new PathCountingCanvas(400, 300);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /** Renders one frame the chart has been damaged for, and answers how many slices it drew. */
    private int slicesDrawn() {
        PathCountingCanvas counting = (PathCountingCanvas) canvas;
        counting.paths = 0;
        chart.invalidate();
        frame();
        return counting.paths;
    }

    /** @return the one chart node in the tree */
    private AccessibleNode chartNode() {
        return node(Accessible.Role.CHART);
    }

    /** @return the slice nodes under the chart, in tree order, which is label order */
    private List<AccessibleNode> slices() {
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

    /** @return whether any node in {@code tree} carries {@code role} */
    private static boolean anyNodeIs(AccessibleTree tree, Accessible.Role role) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return true;
            }
        }
        return false;
    }

    /** Asserts {@code inner} lies inside {@code outer}. */
    private void assertInside(AccessibleNode inner, AccessibleNode outer) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
                        && inner.x() + inner.width() <= outer.x() + outer.width()
                        && inner.y() + inner.height() <= outer.y() + outer.height(),
                inner.role() + " \"" + inner.name() + "\" lies outside " + outer.role()
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aTitledDonutIsOneChartNodeWithOneToggleableSliceNodePerLegendEntry() {
        bindChart(traffic());

        AccessibleNode node = chartNode();
        assertEquals("Traffic sources", node.name(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(),
                "the title is the chart's own painted text" + describe(tree()));
        assertEquals("Sessions", node.description(),
                "the ring's series name is what the tooltip heads its rows with; with the title in "
                        + "the name slot it is the description, by reference" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a chart is not a tab stop and the hook declares no state" + describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertNull(node.actions(),
                "the chart node offers nothing: a click on a mark is pointer-only and the marks "
                        + "are not nodes in this cut" + describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.selection(), describe(tree()));

        List<AccessibleNode> slices = slices();
        assertEquals(List.of("Direct", "Search", "Social", "Mail"), namesOf(slices),
                "one child per legend entry, which for a donut is one per slice, in label order"
                        + describe(tree()));
        int chartIndex = tree().indexOf(node.id());
        for (AccessibleNode slice : slices) {
            assertEquals(Accessible.NameFrom.CONTENT, slice.nameFrom(),
                    "the legend paints the label" + describe(tree()));
            assertEquals(chartIndex, slice.parent(), describe(tree()));
            assertEquals(new ToggleFacet(ToggleFacet.State.ON), slice.toggle(),
                    "drawn, so on" + describe(tree()));
            assertTrue(slice.has(Accessible.State.CHECKED),
                    "the builder derives the bit from the facet" + describe(tree()));
            assertTrue(slice.actions().has(Accessible.Action.TOGGLE),
                    "the one verb the legend's click performs" + describe(tree()));
            assertEquals(1, slice.actions().actions().size(),
                    "and nothing else: no press, no select, and not the two the walk adds for a "
                            + "tab stop, because a painted entry is not one" + describe(tree()));
            assertFalse(slice.has(Accessible.State.FOCUSABLE), describe(tree()));
            assertTrue(slice.has(Accessible.State.ENABLED), describe(tree()));
            assertTrue(slice.has(Accessible.State.SHOWING), describe(tree()));
            assertNull(slice.value(), "the numbers are chart data and are deferred by §11"
                    + describe(tree()));
            assertNull(slice.selectionItem(), describe(tree()));
            assertEquals(List.of(), slice.relations(), describe(tree()));
            assertEquals(List.of(), childrenOf(slice), describe(tree()));
        }
        assertEquals(6, tree().nodeCount(),
                "the window, the chart and four slices; the column is scaffolding and the ring's "
                        + "marks are not nodes" + describe(tree()));
        assertEquals(Accessible.Role.WINDOW, tree().node(0).role(), describe(tree()));
        assertEquals(4, childrenOf(node).size(), describe(tree()));
    }

    @Test
    void anUntitledDonutIsNamedByItsSeriesAndAnUnnamedOneStillPublishes() {
        DonutChart untitled = traffic();
        untitled.setTitle((String) null);
        bindChart(untitled);

        AccessibleNode node = chartNode();
        assertEquals("Sessions", node.name(),
                "with no title the series name is the best name the widget holds" + describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, node.nameFrom(), describe(tree()));
        assertEquals("", node.description(),
                "one string is not both the name and the description" + describe(tree()));

        DonutChart bare = DonutChart.of(List.of("a", "b"), 3, 1);
        bare.setAnimationDuration(0);
        bindChart(bare);

        AccessibleNode nameless = chartNode();
        assertEquals("", nameless.name(),
                "the factory names its series \"\", and the hook does not invent a word for it: "
                        + "the application names the chart with setTitle or setAccessibleName"
                        + describe(tree()));
        assertEquals("", nameless.description(), describe(tree()));
        assertEquals(2, slices().size(),
                "AUTO with two entries draws the legend below the ring" + describe(tree()));
    }

    @Test
    void aSecondSeriesIsNotANodeAndTheDescriptionFollowsTheRing() {
        bindChart(traffic());
        List<Long> ids = idsOf(slices());

        ChartSeries bounces = ChartSeries.of("Bounces", 5, 4, 3, 2);
        chart.addSeries(bounces);
        frame();

        assertEquals(ids, idsOf(slices()),
                "the donut draws its first visible series and leaves the second undrawn on "
                        + "purpose, so a node per series would publish a series that is nowhere on "
                        + "screen; the children are still the four slices" + describe(tree()));
        assertEquals("Sessions", chartNode().description(), describe(tree()));
        assertTrue(bridge.events.isEmpty(),
                "adding an undrawn series changes nothing a reader hears: " + bridge.events);

        sessions.setVisible(false);
        frame();

        assertEquals("Bounces", chartNode().description(),
                "the ring moved to the next visible series and the description followed it"
                        + describe(tree()));
        assertEquals(ids, idsOf(slices()), "same slices, same identifiers" + describe(tree()));
        assertEquals(List.of("Direct", "Search", "Social", "Mail"), namesOf(slices()),
                describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.DESCRIPTION_CHANGED),
                bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                bridge.events.toString());
    }

    @Test
    void withNoLegendThereAreNoChildrenAndTheIdentityIsTheCategoryAndNotThePosition() {
        bindChart(traffic());
        List<Long> ids = idsOf(slices());

        chart.setLegendPosition(Chart.LegendPosition.NONE);
        frame();

        assertEquals(List.of(), childrenOf(chartNode()),
                "without the legend a slice has no box and no operation of its own, and the arcs "
                        + "are not nodes" + describe(tree()));
        assertEquals("Traffic sources", chartNode().name(), describe(tree()));
        assertEquals(4, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());

        chart.setLegendPosition(Chart.LegendPosition.BOTTOM);
        frame();

        assertEquals(ids, idsOf(slices()),
                "the key is the category index scoped by the chart, so the entries below the ring "
                        + "are the entries that were beside it" + describe(tree()));

        DonutChart one = new DonutChart();
        one.setAnimationDuration(0);
        one.setLabels("All");
        one.addSeries(ChartSeries.of("Total", 7));
        bindChart(one);

        assertEquals(List.of(), childrenOf(chartNode()),
                "AUTO with one entry draws no legend, so there is nothing to publish"
                        + describe(tree()));
        assertEquals("Total", chartNode().name(), describe(tree()));
    }

    // ------------------------------------------------------------------------------- the boxes

    /**
     * Clicks the centre of every published slice box and asserts that exactly that slice toggled,
     * then restores it; and that every box lies inside the chart's.
     */
    private void assertEveryBoxIsTheBoxAClickLandsIn(String when) {
        AccessibleNode outer = chartNode();
        List<AccessibleNode> slices = slices();
        assertEquals(4, slices.size(), when + describe(tree()));
        for (int i = 0; i < slices.size(); i++) {
            AccessibleNode slice = slices.get(i);
            assertTrue(slice.width() > 0 && slice.height() > 0, when + describe(tree()));
            assertInside(slice, outer);
            click(slice);
            for (int j = 0; j < 4; j++) {
                assertEquals(j != i, chart.isSliceVisible(j),
                        when + ": the centre of slice " + i + "'s published box toggled slice "
                                + j + ". The published box has to be the box the legend's hit "
                                + "test answers, as it stands at publish time" + describe(tree()));
            }
            chart.setSliceVisible(i, true);
            frame();
        }
    }

    @Test
    void everySliceBoxIsTheLegendBoxAClickLandsInFreshAtPublishAndMirroredUnderRtl() {
        DonutChart below = traffic();
        below.setLegendPosition(Chart.LegendPosition.BOTTOM);
        bindChart(below);

        assertEveryBoxIsTheBoxAClickLandsIn("on the first frame");
        List<AccessibleNode> before = slices();
        assertTrue(before.get(0).x() < before.get(3).x(),
                "reading left to right the first entry is leftmost" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> mirrored = slices();
        assertEquals(namesOf(before), namesOf(mirrored),
                "the legend mirrors the boxes and not the order: entry zero is still the one the "
                        + "reader meets first" + describe(tree()));
        assertEquals(idsOf(before), idsOf(mirrored), describe(tree()));
        assertTrue(mirrored.get(0).x() > mirrored.get(3).x(),
                "reading right to left the first entry is rightmost" + describe(tree()));
        assertEveryBoxIsTheBoxAClickLandsIn("mirrored");

        // Much longer labels re-wrap the legend into three rows and move it up the box, and
        // setLabels runs no layout pass: the boxes move under the tree with nothing else to
        // re-run the region pass before the publish step reads them.
        chart.setLabels("Direct traffic from bookmarks", "Organic search engines",
                "Social networks", "Mail campaigns");
        frame();

        assertEquals(List.of("Direct traffic from bookmarks", "Organic search engines",
                "Social networks", "Mail campaigns"), namesOf(slices()), describe(tree()));
        assertEveryBoxIsTheBoxAClickLandsIn("after a rename on a quiet scene");
    }

    // ------------------------------------------------------------------------------ the action

    @Test
    void aToggleFromTheBridgeReachesTheSamePathAClickReachesAndIsHeardAsAStateChange()
            throws Exception {
        bindChart(traffic());
        assertEquals(4, slicesDrawn(), "the fixture has to draw every slice first");
        bridge.events.clear();
        AccessibleNode social = slices().get(2);

        assertTrue(perform(social.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE),
                "accepted, which is not the same as done");

        assertFalse(chart.isSliceVisible(2),
                "the hook reaches toggleLegendEntry, which is what a click on the entry reaches");
        assertTrue(chart.isSliceVisible(0) && chart.isSliceVisible(1) && chart.isSliceVisible(3));
        assertEquals(3, slicesDrawn(), "the next painted frame closes the ring over it");

        AccessibleNode hidden = slices().get(2);
        assertEquals(social.id(), hidden.id(), describe(tree()));
        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), hidden.toggle(), describe(tree()));
        assertFalse(hidden.has(Accessible.State.CHECKED), describe(tree()));
        List<AccessibleEvent> raised = checkedEvents();
        assertEquals(1, raised.size(), "one flip, one event: " + bridge.events);
        assertEquals(social.id(), raised.get(0).nodeId(), bridge.events.toString());
        assertEquals(Boolean.FALSE, raised.get(0).newValue(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a toggle is acknowledged by its state change and not by an invocation: "
                        + bridge.events);

        bridge.events.clear();
        assertTrue(perform(social.id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE));

        assertTrue(chart.isSliceVisible(2));
        assertEquals(4, slicesDrawn());
        assertEquals(new ToggleFacet(ToggleFacet.State.ON), slices().get(2).toggle(),
                describe(tree()));
        raised = checkedEvents();
        assertEquals(1, raised.size(), bridge.events.toString());
        assertEquals(Boolean.TRUE, raised.get(0).newValue(), bridge.events.toString());
    }

    @Test
    void aNonInteractiveLegendOffersNoVerbAndEveryOtherVerbIsRefused() throws Exception {
        bindChart(traffic());
        long social = slices().get(2).id();

        chart.setLegendInteractive(false);
        frame();

        for (AccessibleNode slice : slices()) {
            assertNull(slice.actions(),
                    "the pointer's toggle requires the flag, so the verb is not offered without it; "
                            + "the setter paints nothing and has to flag the tree itself"
                            + describe(tree()));
            assertEquals(new ToggleFacet(ToggleFacet.State.ON), slice.toggle(),
                    "the state is still a fact: the swatch paints it either way" + describe(tree()));
        }
        perform(social, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(chart.isSliceVisible(2), "a verb not offered was performed");
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        chart.setLegendInteractive(true);
        frame();
        assertTrue(slices().get(2).actions().has(Accessible.Action.TOGGLE), describe(tree()));
        bridge.events.clear();

        perform(social, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(social, Accessible.Action.SELECT, Accessible.Argument.NONE);
        perform(social, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(0));
        perform(chartNode().id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();

        assertTrue(chart.isSliceVisible(2), "the hook answers false for everything but TOGGLE");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not acknowledged: " + bridge.events);
        assertEquals(List.of(), checkedEvents(), bridge.events.toString());

        root.setEnabled(false);
        frame();

        assertTrue(chart.isEnabled(), "the fixture has to leave the chart's own flag alone");
        assertFalse(slices().get(2).has(Accessible.State.ENABLED),
                "the owner's bits ride on every node it drew" + describe(tree()));
        perform(social, Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        frame();
        assertTrue(chart.isSliceVisible(2),
                "a control inside a disabled container is one the pointer refuses too");
    }

    // ------------------------------------------------------------------------------- the state

    @Test
    void theToggleStateIsTheHiddenBitAndNotTheFade() {
        DonutChart animated = traffic();
        animated.setAnimationDuration(0.55);
        bindChart(animated);
        // Run the entry wipe out first: a slice with no sweep yet fills no path, and the case
        // below counts paths to see the fade still drawing the slice a reader was told is off.
        for (int i = 0; i < 8; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(250);
            frame();
        }
        assertEquals(4, slicesDrawn(), "the ring has to have wiped all the way round");
        int published = bridge.published.size();
        bridge.events.clear();

        chart.setSliceVisible(1, false);

        assertEquals(4, slicesDrawn(),
                "the clock has not moved, so the fade has not begun to close the slice");
        assertEquals(new ToggleFacet(ToggleFacet.State.OFF), slices().get(1).toggle(),
                "and the tree already says off: the facet is the hidden bit, which the setter "
                        + "flipped before arming the weight" + describe(tree()));
        assertEquals(published + 1, bridge.published.size(), "one change, one snapshot");
        assertEquals(1, checkedEvents().size(), bridge.events.toString());
        assertEquals(Boolean.FALSE, checkedEvents().get(0).newValue(), bridge.events.toString());
        published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 6; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(30);
            int drawn = slicesDrawn();
            if (i < 3) {
                assertEquals(4, drawn, "the slice is still closing on frame " + i
                        + ": a hook that read the eased weight would move on every one of these");
            }
        }

        assertEquals(published, bridge.published.size(),
                "nothing further while the weight transition runs");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // ------------------------------------------------------------------------------- the hole

    @Test
    void theHoleIsARealChildPublishedUnderTheChartAfterTheSlices() {
        bindChart(traffic());

        chart.setCenter(new Button("Shuffle"));
        frame();

        AccessibleNode button = node(Accessible.Role.BUTTON);
        AccessibleNode outer = chartNode();
        assertEquals(tree().indexOf(outer.id()), button.parent(),
                "the walk appends a real child's subtree under the owner" + describe(tree()));
        List<AccessibleNode> children = childrenOf(outer);
        assertEquals(5, children.size(), describe(tree()));
        for (int i = 0; i < 4; i++) {
            assertEquals(Accessible.Role.CHART_SERIES, children.get(i).role(),
                    "the synthetic children come first" + describe(tree()));
        }
        assertEquals(Accessible.Role.BUTTON, children.get(4).role(), describe(tree()));
        assertEquals("Shuffle", button.name(), describe(tree()));
        assertTrue(button.has(Accessible.State.FOCUSABLE),
                "a button in the hole is a tab stop like any other" + describe(tree()));
        assertInside(button, outer);

        chart.setCenter(null);
        frame();

        assertFalse(anyNodeIs(tree(), Accessible.Role.BUTTON), describe(tree()));
        assertEquals(4, childrenOf(chartNode()).size(), describe(tree()));

        Column column = new Column();
        column.add(new Label("100"));
        column.add(new Label("visits"));
        chart.setCenter(column);
        frame();

        children = childrenOf(chartNode());
        assertEquals(6, children.size(),
                "the column is scaffolding and hoists its labels into the chart" + describe(tree()));
        assertEquals(Accessible.Role.LABEL, children.get(4).role(), describe(tree()));
        assertEquals("100", children.get(4).name(), describe(tree()));
        assertEquals("visits", children.get(5).name(), describe(tree()));
    }

    // ------------------------------------------------------------------------------- the names

    @Test
    void sliceNamesFollowTheLanguageAndARenameKeepsTheKeys() {
        I18n.addBundle(LABELS);
        I18n.setLocale(Locale.ENGLISH);
        DonutChart localized = traffic();
        localized.setLabels(DIRECT, SEARCH, SOCIAL, MAIL);
        bindChart(localized);
        List<Long> ids = idsOf(slices());
        assertEquals(List.of("Direct", "Search", "Social", "Mail"), namesOf(slices()),
                describe(tree()));

        I18n.setLocale(BRAZILIAN);
        frame();

        assertEquals(List.of("Direto", "Busca", "Social", "E-mail"), namesOf(slices()),
                "the names are the held sources re-resolved under the new language, which a "
                        + "string resolved inside the hook could not be" + describe(tree()));
        assertEquals(ids, idsOf(slices()), "same nodes" + describe(tree()));
        assertEquals(BRAZILIAN, slices().get(0).locale(), describe(tree()));
        assertEquals(3, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "three names moved and \"Social\" did not, so three events: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                bridge.events.toString());
        bridge.events.clear();

        chart.setLabels("A", "B", "C", "D");
        frame();

        assertEquals(List.of("A", "B", "C", "D"), namesOf(slices()), describe(tree()));
        assertEquals(ids, idsOf(slices()),
                "re-meaning under setLabels is the same shape as a list's data index: a rename, "
                        + "never a re-key" + describe(tree()));
        assertEquals(4, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                bridge.events.toString());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietDonutPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindChart(traffic());
        chart.setSliceVisible(3, false); // a struck entry, so both facet values are on the walk
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

        // A label read with label(i), a title with title(), a series name with name(), a
        // formatted value, the variable-argument action call, or an unconditional region pass
        // under a ruler that does not memoize is memory spent per damaged frame concluding that
        // nothing moved, and a value tween or a hover pop damages every frame. This is the only
        // place any of them is visible.
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            chart.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            chart.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a donut that did not move must cost no memory: the title, the series "
                        + "name and every label are I18nStrings the chart already holds, compared "
                        + "by reference, the boxes are four floats each read from the array the "
                        + "hit test reads, and the toggle is a bit");
    }
}
