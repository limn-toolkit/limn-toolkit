package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * {@link ScrollGutters.Layout#RESERVED} on the two row widgets that scroll on both axes, {@link Tree}
 * and {@link Table}: the strip is the bar's, so no row is placed, painted or pressed inside it.
 *
 * <p>{@code ListViewMirroringTest} holds the list to the same promise on one axis. A tree adds the
 * second, and the one easy to get wrong: its content is as wide as its deepest open row, so a cell
 * can run past the vertical strip, and the last row always runs past the horizontal one. Every
 * expectation is arithmetic against the box and {@link ScrollBar#thickness()}, because a still of
 * grey rows under a bar shows nothing of the defect.
 */
class ReservedBarStripTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float BOX = 220;
    private static final float HEIGHT = 100;
    private static final float ROW_HEIGHT = 40;
    private static final float STRIP = ScrollBar.thickness();

    private record Node(String name, List<Node> children) {
    }

    /** A row of fixed height that paints nothing: only its box is ever asserted. */
    private static final class Cell extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), ROW_HEIGHT);
        }
    }

    private Scene scene;
    /** The scene's clock, so a bar's first-overflow flash can be let expire rather than raced. */
    private final long[] now = {0};

    /** Forty roots and nothing under them: the shape that overflows downward and never sideways. */
    private static List<Node> flat() {
        List<Node> roots = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            roots.add(new Node("row-" + i, List.of()));
        }
        return roots;
    }

    /** A chain of fourteen levels, one child each: the shape that overflows both ways once open. */
    private static Node chain() {
        Node node = new Node("level-14", List.of());
        for (int i = 13; i >= 1; i--) {
            node = new Node("level-" + i, List.of(node));
        }
        return node;
    }

    private Tree<Node> tree(List<Node> roots, Map<String, Widget> cells, LayoutDirection direction,
                            ScrollGutters.Layout layout, ScrollBar.Policy policy) {
        Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
            @Override
            public List<Node> roots() {
                return roots;
            }

            @Override
            public List<Node> children(Node node) {
                return node.children();
            }

            @Override
            public Widget cellFor(Node node) {
                Cell cell = new Cell();
                cells.put(node.name(), cell);
                return cell;
            }
        });
        // The hold's clock before the policy: setting a policy refreshes the bar, and a first
        // overflow stamped on the wall clock would hold the bar up for hours of this one.
        for (Widget child : tree.children()) {
            if (child instanceof ScrollBar bar) {
                bar.clock(() -> now[0]);
            }
        }
        tree.setBarLayout(layout).setScrollbarPolicy(policy);
        tree.setLayoutDirection(direction);
        for (Node root : roots) {
            for (Node node = root; !node.children().isEmpty(); node = node.children().get(0)) {
                tree.expand(node);
            }
        }
        scene = new Scene(tree, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(BOX, HEIGHT);
        return tree;
    }

    /**
     * Ends the flash a bar gives when content first overflows, and runs the fade to its end on
     * the scene's clock. A faded bar answers no hit test, which is the case a reserved strip has to
     * survive: the pointer is over the strip and nothing drawn there claims it.
     */
    private void letTheBarsFade(Widget host) {
        scene.renderFrame(new FakeCanvas(BOX, HEIGHT));
        now[0] += 5_000_000_000L;
        for (Widget child : host.children()) {
            if (child instanceof ScrollBar bar) {
                bar.onHoldElapsed();
            }
        }
        for (int frame = 0; frame < 20; frame++) {
            now[0] += 50_000_000L;
            scene.requestRender();
            scene.renderFrame(new FakeCanvas(BOX, HEIGHT));
        }
        assertTrue(verticalBar(host).shownOpacity() < 0.05f,
                "the fixture has to fade the bar: it paints at " + verticalBar(host).shownOpacity());
    }

    /** The vertical bar: a scroll bar one strip wide and taller than one. */
    private static ScrollBar verticalBar(Widget host) {
        return bar(host, true);
    }

    /** The horizontal bar: a scroll bar one strip tall and wider than one. */
    private static ScrollBar horizontalBar(Widget host) {
        return bar(host, false);
    }

    private static ScrollBar bar(Widget host, boolean vertical) {
        for (Widget child : host.children()) {
            if (child instanceof ScrollBar found
                    && (vertical ? found.height() > found.width() : found.width() > found.height())) {
                return found;
            }
        }
        throw new AssertionError("no " + (vertical ? "vertical" : "horizontal") + " bar mounted");
    }

    private static List<Widget> cellsOf(Widget host) {
        List<Widget> cells = new ArrayList<>();
        for (Widget child : host.children()) {
            if (child instanceof Cell) {
                cells.add(child);
            }
        }
        assertTrue(!cells.isEmpty(), "no row was ever mounted");
        return cells;
    }

    // -------------------------------------------------------------------- the tree

    @Test
    void aTreeOverlaysByDefaultAndKeepsTheWholeBox() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Tree<Node> tree = new Tree<>(new Tree.Model<Node>() {
                @Override
                public List<Node> roots() {
                    return flat();
                }

                @Override
                public List<Node> children(Node node) {
                    return node.children();
                }

                @Override
                public Widget cellFor(Node node) {
                    return new Cell();
                }
            });
            assertSame(ScrollGutters.Layout.OVERLAY, tree.barLayout(), "the default is unchanged");
            tree.setLayoutDirection(direction);
            scene = new Scene(tree);
            scene.setTextRuler(RULER);
            scene.layoutPass(BOX, HEIGHT);
            for (Widget cell : cellsOf(tree)) {
                float far = direction == LayoutDirection.RTL ? cell.x() : cell.x() + cell.width();
                assertEquals(direction == LayoutDirection.RTL ? 0 : BOX, far, EPS,
                        "an overlaid bar takes nothing from a row reading " + direction);
            }
        }
    }

    @Test
    void aReservedVerticalStripIsOnTheReadingEndAndNoRowRunsUnderIt() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            boolean rtl = direction == LayoutDirection.RTL;
            Tree<Node> tree = tree(flat(), new java.util.HashMap<>(), direction,
                    ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ALWAYS);
            assertSame(ScrollGutters.Layout.RESERVED, tree.barLayout());

            ScrollBar bar = verticalBar(tree);
            assertEquals(rtl ? 0 : BOX - STRIP, bar.x(), EPS, "the bar is on the trailing side");
            for (Widget cell : cellsOf(tree)) {
                // The row ends where the viewport does, which is the strip's edge.
                float far = rtl ? cell.x() : cell.x() + cell.width();
                assertEquals(rtl ? STRIP : BOX - STRIP, far, EPS,
                        "a row ran under the bar reading " + direction);
            }
        }
    }

    @Test
    void aReservedHorizontalStripIsWhereScrollingToTheEndStopsTheLastRow() {
        // The tree's vertical clamp, its page and its reveal all measured against the box. Under
        // RESERVED that leaves the last row one strip too low: scrolled to the end, under the bar.
        Map<String, Widget> cells = new java.util.HashMap<>();
        Tree<Node> tree = tree(List.of(chain()), cells, LayoutDirection.LTR,
                ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ALWAYS);
        assertEquals(HEIGHT - STRIP, horizontalBar(tree).y(), EPS,
                "a fourteen-level chain has to overflow sideways for this to reserve anything");
        assertEquals(HEIGHT - STRIP, verticalBar(tree).height(), EPS,
                "and the vertical bar stops where the horizontal strip begins");

        tree.scrollBy(10_000);
        scene.layoutPass(BOX, HEIGHT);

        Widget last = cells.get("level-14");
        assertNotNull(last, "scrolled to the end, the last row is mounted");
        assertEquals(HEIGHT - STRIP, last.y() + last.height(), EPS,
                "the end of the rows is the top of the strip, not the bottom of the box");
    }

    @Test
    void aReservedTreeClipsItsRowsToTheViewportInBothDirections() {
        // A deep tree's cells are as wide as its content, which runs past the vertical strip; the
        // clip is what keeps them out of it.
        for (LayoutDirection direction : LayoutDirection.values()) {
            tree(List.of(chain()), new java.util.HashMap<>(), direction,
                    ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ALWAYS);
            RecordingTestCanvas canvas = new RecordingTestCanvas(BOX, HEIGHT);
            scene.renderFrame(canvas);

            float expectedX = direction == LayoutDirection.RTL ? STRIP : 0;
            boolean found = false;
            for (Rect clip : canvas.clips) {
                if (Math.abs(clip.x() - expectedX) < EPS && Math.abs(clip.y()) < EPS
                        && Math.abs(clip.width() - (BOX - STRIP)) < EPS
                        && Math.abs(clip.height() - (HEIGHT - STRIP)) < EPS) {
                    found = true;
                }
            }
            assertTrue(found, "no clip was the viewport reading " + direction + ": " + canvas.clips);
        }
    }

    @Test
    void aPressOnAReservedStripReachesNoRowWhileItsBarHasFaded() {
        // ON_SCROLL, so the bar is faded at rest and the pointer does not bring it back: a faded bar
        // answers no hit test, and the strip under it has to stay the bar's anyway.
        for (LayoutDirection direction : LayoutDirection.values()) {
            boolean rtl = direction == LayoutDirection.RTL;
            Tree<Node> tree = tree(flat(), new java.util.HashMap<>(), direction,
                    ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ON_SCROLL);
            letTheBarsFade(tree);

            float stripX = rtl ? STRIP / 2 : BOX - STRIP / 2;
            assertSame(tree, tree.hitTest(stripX, ROW_HEIGHT / 2),
                    "the bar has faded, so the tree itself is what the strip reaches");
            press(stripX, ROW_HEIGHT / 2);
            assertTrue(tree.selectedNodes().isEmpty(),
                    "a press on the strip selected a row reading " + direction);

            float insideX = rtl ? STRIP + 10 : 10;
            press(insideX, ROW_HEIGHT / 2);
            assertEquals(1, tree.selectedNodes().size(),
                    "and a press just inside the viewport does reach a row, so the one above is not"
                            + " passing for want of a press at all");
        }

        // The bottom strip, over the row a viewport of 100 cuts in half.
        tree(List.of(chain()), new java.util.HashMap<>(), LayoutDirection.LTR,
                ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ON_SCROLL);
        Tree<?> deep = (Tree<?>) scene.root();
        letTheBarsFade(deep);
        assertSame(deep, deep.hitTest(10, HEIGHT - STRIP / 2),
                "the horizontal bar has faded too, so the tree is what the bottom strip reaches");
        press(10, HEIGHT - STRIP / 2);
        assertTrue(deep.selectedNodes().isEmpty(), "a press on the bottom strip selected a row");
    }

    /**
     * A selection move damages the rows' viewport and never a strip: the strip is the bar's, and
     * damaging it repaints the bar for a highlight that was never drawn there (TREE-MISS-7; the
     * spinner's damage already made this promise). The open chain reserves both strips, and a
     * selected row scrolled to straddle the horizontal one is the row whose band reaches both if
     * any does: clearing the selection damages that band alone — no reveal, no cursor move, no
     * layout — and the frame's own repaint passes say where the damage went. They carry the
     * one-point feather every damage carries, and nothing more.
     */
    @Test
    void aSelectionChangeDamagesTheViewportAndNotTheStrips() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Node top = chain();
            Tree<Node> tree = tree(List.of(top), new java.util.HashMap<>(), direction,
                    ScrollGutters.Layout.RESERVED, ScrollBar.Policy.ALWAYS);
            Node third = top.children().get(0).children().get(0);
            tree.setSelected(third); // revealed: its foot on the strip's edge
            tree.scrollBy(-20); // and back up, so it straddles the strip
            scene.layoutPass(BOX, HEIGHT);
            RecordingTestCanvas settled = new RecordingTestCanvas(BOX, HEIGHT);
            for (int frame = 0; frame < 60; frame++) {
                now[0] += 50_000_000L; // the bars' own fades run on this clock
                settled.reset();
                scene.renderFrame(settled);
                if (settled.nothingPainted()) {
                    break;
                }
            }
            assertTrue(settled.nothingPainted(), "the fixture has to be at rest first");
            Widget cell = cellsOf(tree).stream()
                    .filter(c -> c.y() < HEIGHT - STRIP && c.y() + c.height() > HEIGHT - STRIP)
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "the fixture has to put a row across the horizontal strip"));
            assertTrue(cell.y() > 0, "and that row starts inside the viewport: " + cell.y());

            tree.clearSelection();
            RecordingTestCanvas canvas = new RecordingTestCanvas(BOX, HEIGHT);
            scene.renderFrame(canvas);
            assertFalse(canvas.cleared, "a selection change is a band, not a frame");
            List<Rect> passes = canvas.passClips();
            assertTrue(!passes.isEmpty(), "clearing the selection repaints the row's band");
            boolean rtl = direction == LayoutDirection.RTL;
            float viewLeft = rtl ? STRIP : 0;
            float viewRight = rtl ? BOX : BOX - STRIP;
            float feather = 1;
            for (Rect pass : passes) {
                assertTrue(pass.x() >= viewLeft - feather - EPS,
                        "damage reached into the vertical strip reading " + direction + ": " + pass);
                assertTrue(pass.x() + pass.width() <= viewRight + feather + EPS,
                        "damage reached into the vertical strip reading " + direction + ": " + pass);
                assertTrue(pass.y() + pass.height() <= HEIGHT - STRIP + feather + EPS,
                        "damage reached into the horizontal strip reading " + direction + ": "
                                + pass);
            }
        }
    }

    private void press(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
        scene.layoutPass(BOX, HEIGHT);
    }

    // ------------------------------------------------------------------- the table

    record Person(String name, int age) {
    }

    @Test
    void aReservedTableReportsItAndClipsItsRowsToTheViewportInBothDirections() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            List<Person> people = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                people.add(new Person("Person " + i, i));
            }
            Table<Person> table = new Table<>(List.of(
                    Column.text("Name", Person::name).width(100),
                    Column.numeric("Age", Person::age).width(60)));
            assertSame(ScrollGutters.Layout.OVERLAY, table.barLayout(), "the default is unchanged");
            table.setRows(people);
            table.setBarLayout(ScrollGutters.Layout.RESERVED).setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
            assertSame(ScrollGutters.Layout.RESERVED, table.barLayout());
            table.setLayoutDirection(direction);

            Scene tableScene = new Scene(table);
            tableScene.setTextRuler(RULER);
            RecordingTestCanvas canvas = new RecordingTestCanvas(BOX, HEIGHT * 2);
            tableScene.renderFrame(canvas);

            boolean rtl = direction == LayoutDirection.RTL;
            assertEquals(rtl ? 0 : BOX - STRIP, verticalBar(table).x(), EPS);
            float header = Theme.current().tokensFor(table).controlHeight();
            float expectedX = rtl ? STRIP : 0;
            boolean found = false;
            for (Rect clip : canvas.clips) {
                if (Math.abs(clip.x() - expectedX) < EPS && Math.abs(clip.y() - header) < EPS
                        && Math.abs(clip.width() - (BOX - STRIP)) < EPS) {
                    found = true;
                }
            }
            assertTrue(found, "the rows' clip was not the viewport reading " + direction + ": "
                    + canvas.clips);
        }
    }
}
