package limn.scene;

import limn.components.Button;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Row;
import limn.scene.layout.Wrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A widget's minimum and maximum size narrow what its parent allows and never override it, and a
 * flexible child's ceiling is held the way its floor is: frozen at it, with what it gives up going
 * to its siblings, or to main alignment when every flexible child is held.
 */
class SizeBoundsTest extends SceneTestBase {

    private static final float EPS = 1e-3f;

    // ------------------------------------------------------------------ a widget's own bounds

    @Test
    void aMaximumCapsTheNaturalSizeWhereTheParentLeavesTheChoiceOpen() {
        FixedBox wide = new FixedBox(300, 20).setMaxWidth(120);
        assertEquals(new Size(120, 20), wide.measure(Constraints.loose(400, 300)));

        Column column = new Column(); // START across: each child picks its own width
        column.add(wide);
        column.measure(Constraints.tight(400, 300));
        column.layoutBox(0, 0, 400, 300);
        assertEquals(120, wide.width(), EPS, "and a column lays it out at that width");
    }

    @Test
    void aMinimumRaisesIt() {
        FixedBox small = new FixedBox(50, 20).setMinWidth(100).setMinHeight(40);
        assertEquals(new Size(100, 40), small.measure(Constraints.loose(400, 300)));
    }

    @Test
    void theParentWinsWhereItFixesTheSize() {
        FixedBox capped = new FixedBox(50, 20).setMaxWidth(30);
        assertEquals(200, capped.measure(Constraints.tight(200, 20)).width(), EPS,
                "a tight parent: the root, a stretched column, a flexible share");
        FixedBox floored = new FixedBox(50, 20).setMinWidth(500);
        assertEquals(200, floored.measure(Constraints.loose(200, 50)).width(), EPS,
                "a minimum above what the parent allows is the parent's maximum");

        FixedBox stretched = new FixedBox(50, 20).setMaxWidth(120);
        Column column = new Column().crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(stretched);
        column.measure(Constraints.tight(400, 300));
        column.layoutBox(0, 0, 400, 300);
        assertEquals(400, stretched.width(), EPS, "STRETCH is the column's decision");
    }

    @Test
    void anOpenAxisTakesTheMaximumAsItsBound() {
        FixedBox wide = new FixedBox(900, 20).setMaxWidth(600);
        assertEquals(600, wide.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                Constraints.UNBOUNDED_LIMIT)).width(), EPS);
    }

    @Test
    void changingABoundMeasuresAgain() {
        FixedBox box = new FixedBox(300, 20);
        Constraints loose = Constraints.loose(400, 300);
        assertEquals(300, box.measure(loose).width(), EPS);
        box.setMaxWidth(100);
        assertEquals(100, box.measure(loose).width(), EPS, "not the cached 300");
        box.setMaxWidth(Constraints.UNBOUNDED_LIMIT);
        assertEquals(300, box.measure(loose).width(), EPS, "and the open maximum clears it");
    }

    @Test
    void theBoundsReadBackAndRefuseWhatNoSizeSatisfies() {
        FixedBox box = new FixedBox(10, 10);
        assertEquals(0, box.minWidth());
        assertEquals(Constraints.UNBOUNDED_LIMIT, box.maxWidth());
        box.setMinWidth(20).setMaxWidth(80).setMinHeight(5).setMaxHeight(60);
        assertEquals(20, box.minWidth());
        assertEquals(80, box.maxWidth());
        assertEquals(5, box.minHeight());
        assertEquals(60, box.maxHeight());

        assertThrows(IllegalArgumentException.class, () -> box.setMaxWidth(10), "below the minimum");
        assertThrows(IllegalArgumentException.class, () -> box.setMinHeight(61), "above the maximum");
        assertThrows(IllegalArgumentException.class, () -> box.setMinWidth(-1));
        assertThrows(IllegalArgumentException.class, () -> box.setMaxHeight(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> box.setMinWidth(Constraints.UNBOUNDED_LIMIT), "a minimum is a number");
    }

    @Test
    void theSettersChainAsTheWidgetsOwnType() {
        Button button = new Button("OK").setMinWidth(80).setMaxWidth(200); // compiles only as Button
        assertEquals(80, button.minWidth());
    }

    // ------------------------------------------------------------------ bounds and a wrap

    @Test
    void aWrapsOwnMaximumWidthIsWhereItsRunsBreak() {
        Wrap wrap = new Wrap().gap(10).runGap(8).setMaxWidth(220);
        wrap.add(new FixedBox(120, 40));
        wrap.add(new FixedBox(80, 30));
        wrap.add(new FixedBox(100, 20));
        Size size = wrap.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                Constraints.UNBOUNDED_LIMIT));
        assertEquals(210, size.width(), EPS, "120 and 80 share a run; 100 more would pass 220");
        assertEquals(40 + 8 + 20, size.height(), EPS, "two runs where the open width gave one");
    }

    @Test
    void aChildsMinimumWiderThanTheWrapIsCutToTheWrapsWidth() {
        FixedBox before = new FixedBox(50, 20);
        FixedBox floored = new FixedBox(50, 20).setMinWidth(500);
        Wrap wrap = new Wrap().gap(10);
        wrap.add(before);
        wrap.add(floored);
        wrap.measure(Constraints.tight(200, 100));
        wrap.layoutBox(0, 0, 200, 100);
        assertEquals(200, floored.width(), EPS, "the parent wins: at most the wrap's width");
        assertEquals(20, floored.y(), EPS, "and as wide as that, it takes a run of its own");
    }

    @Test
    void aChildsMaximumHoldsInsideAWrap() {
        FixedBox capped = new FixedBox(300, 20).setMaxWidth(90);
        FixedBox next = new FixedBox(90, 20);
        Wrap wrap = new Wrap().gap(10);
        wrap.add(capped);
        wrap.add(next);
        wrap.measure(Constraints.tight(200, 100));
        wrap.layoutBox(0, 0, 200, 100);
        assertEquals(90, capped.width(), EPS, "a wrap leaves the width to the child");
        assertEquals(0, next.y(), EPS, "so the capped width is the one the run is broken with");
        assertEquals(100, next.x(), EPS);
    }

    // ------------------------------------------------------------------ bounds and a grid

    @Test
    void aGridsOwnMaximumWidthIsWhatItsExpandedColumnFills() {
        FixedBox field = new FixedBox(80, 20);
        Grid grid = new Grid(2).gap(10).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH).setMaxWidth(260);
        grid.add(new FixedBox(50, 20));
        grid.add(field);
        Size size = grid.measure(Constraints.loose(600, 100));
        grid.layoutBox(0, 0, size.width(), size.height());
        assertEquals(260, size.width(), EPS, "the grid fills its own bound, not its parent's");
        assertEquals(200, field.width(), EPS, "and the field column is what that leaves");
    }

    @Test
    void aCellsMinimumWiderThanTheGridIsCutToTheGridsWidth() {
        FixedBox floored = new FixedBox(50, 20).setMinWidth(500);
        Grid grid = new Grid(1);
        grid.add(new FixedBox(50, 20));
        grid.add(floored);
        grid.measure(Constraints.tight(200, 100));
        grid.layoutBox(0, 0, 200, 100);
        assertEquals(200, floored.width(), EPS, "the parent wins: at most the grid's width");
    }

    @Test
    void aCellsMaximumHoldsInsideAGridAndSizesItsColumn() {
        FixedBox capped = new FixedBox(300, 20).setMaxWidth(90);
        FixedBox next = new FixedBox(40, 20);
        Grid grid = new Grid(2).gap(10);
        grid.add(capped);
        grid.add(next);
        Size size = grid.measure(Constraints.loose(400, 100));
        grid.layoutBox(0, 0, size.width(), size.height());
        assertEquals(90, capped.width(), EPS, "a grid leaves the width to the cell");
        assertEquals(100, next.x(), EPS, "so the capped width is the one its column is sized from");
        assertEquals(140, size.width(), EPS);
    }

    // ------------------------------------------------------------------ a flexible child's ceiling

    @Test
    void aCeilingHoldsAChildAndItsSiblingTakesTheRest() {
        Row row = new Row();
        row.add(Expanded.of(new FixedBox(10, 40), 1).atMost(100));
        row.add(Expanded.of(new FixedBox(10, 40), 1));
        row.measure(Constraints.tight(400, 40));
        row.layoutBox(0, 0, 400, 40);
        assertEquals(100, row.children().get(0).width(), EPS);
        assertEquals(300, row.children().get(1).width(), EPS);
        assertEquals(100, row.children().get(1).x(), EPS);
    }

    @Test
    void whenEveryFlexibleChildIsHeldMainAlignmentPlacesTheRest() {
        Row row = new Row().mainAlignment(Flex.MainAlignment.CENTER);
        row.add(Expanded.of(new FixedBox(10, 40), 1).atMost(100));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atMost(100));
        row.measure(Constraints.tight(400, 40));
        row.layoutBox(0, 0, 400, 40);
        assertEquals(100, row.children().get(0).x(), EPS, "200 left over, half before");
        assertEquals(200, row.children().get(1).x(), EPS);
        assertEquals(100, row.children().get(1).width(), EPS);
    }

    @Test
    void floorsAreHeldBeforeCeilings() {
        Row row = new Row();
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(250));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atMost(50));
        row.add(Expanded.of(new FixedBox(10, 40), 1));
        row.measure(Constraints.tight(400, 40));
        row.layoutBox(0, 0, 400, 40);
        assertEquals(250, row.children().get(0).width(), EPS, "the floor, frozen first");
        assertEquals(50, row.children().get(1).width(), EPS, "then the ceiling");
        assertEquals(100, row.children().get(2).width(), EPS, "and the rest");
    }

    @Test
    void aCeilingReadsBackAndRefusesToCrossTheFloor() {
        Expanded flexible = Expanded.of(new FixedBox(10, 10));
        assertEquals(Constraints.UNBOUNDED_LIMIT, flexible.maxMain());
        assertEquals(120, flexible.atLeast(20).atMost(120).maxMain());
        assertThrows(IllegalArgumentException.class, () -> flexible.atMost(10), "below the floor");
        assertThrows(IllegalArgumentException.class, () -> flexible.atLeast(121), "above the ceiling");
        assertThrows(IllegalArgumentException.class, () -> flexible.atMost(-1));
        assertEquals(Constraints.UNBOUNDED_LIMIT,
                flexible.atMost(Constraints.UNBOUNDED_LIMIT).maxMain(), "the open ceiling clears it");
    }

    @Test
    void alongAnOpenAxisAFlexibleChildAsksForItsFloor() {
        Column column = new Column();
        column.add(new FixedBox(10, 20));
        column.add(Expanded.of(new FixedBox(10, 10)).atLeast(80));
        column.add(Expanded.of(new FixedBox(10, 10)));
        Size size = column.measure(new Constraints(0, 400, 0, Constraints.UNBOUNDED_LIMIT));
        assertEquals(100, size.height(), EPS,
                "the fixed child's 20 and the floor's 80; a flexible child with none asks for nothing");
    }
}
