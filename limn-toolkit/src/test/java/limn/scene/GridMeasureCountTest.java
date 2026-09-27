package limn.scene;

import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Row;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a {@link Grid} costs its cells per layout pass: one measure each, because every cell is
 * first asked against the whole width and never against its neighbours, the columns are sized
 * from those answers, and layout asks the questions measure already asked. The exceptions are
 * priced here, each one a cell whose final size is only known once the others have answered: a
 * stretched cell narrower than its column is asked again at the column's width, and a cell
 * shorter than a stretched row again at the row's height. The expanded column is not one of
 * them: under a bound its width is what the others leave, so its cells are asked once, at that
 * width, as an {@code Expanded} child is asked once at its share.
 *
 * <p>The shape that would miss is the one {@code WrapMeasureCountTest} records: a grid in a loose
 * parent is measured against the parent's bound and laid out at its own natural width, which is
 * narrower, and asking the cells again against that box would miss the one-answer cache in
 * {@code Widget.measure} and double every leaf's work.
 */
class GridMeasureCountTest extends SceneTestBase {

    /** A fixed-size leaf that counts how often it is asked. */
    private static final class CountingBox extends Widget<CountingBox> {
        private final float prefWidth;
        private final float prefHeight;
        int measures;

        CountingBox(float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        void dirty() {
            markNeedsLayout();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    /** Two rows of a caption and a field: columns of 90 and 150, rows of 30 and 20. */
    private static List<CountingBox> leaves() {
        return List.of(new CountingBox(60, 20), new CountingBox(150, 30), new CountingBox(90, 20),
                new CountingBox(120, 20));
    }

    private static Grid gridOf(List<CountingBox> leaves) {
        Grid grid = new Grid(2).gap(10).rowGap(6);
        for (CountingBox leaf : leaves) {
            grid.add(leaf);
        }
        return grid;
    }

    /** Settles one pass, then runs three more with every leaf dirtied, and counts those. */
    private static void threePasses(Scene scene, List<CountingBox> leaves) {
        scene.layoutPass(300, 300);
        for (CountingBox leaf : leaves) {
            leaf.measures = 0;
        }
        for (int pass = 0; pass < 3; pass++) {
            for (CountingBox leaf : leaves) {
                leaf.dirty();
            }
            scene.layoutPass(300, 300);
        }
    }

    private static Scene inLooseParent(Grid grid, LayoutDirection direction) {
        Column loose = new Column(); // START across: the grid gets 0..300 and takes what it needs
        loose.add(grid);
        Scene scene = new Scene(loose);
        scene.setLayoutDirection(direction);
        return scene;
    }

    @Test
    void aCellOfAGridInALooseParentIsMeasuredOncePerPass() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            List<CountingBox> leaves = leaves();
            Grid grid = gridOf(leaves);

            threePasses(inLooseParent(grid, direction), leaves);

            for (CountingBox leaf : leaves) {
                assertEquals(3, leaf.measures, direction + ": one onMeasure per pass, not one per "
                        + "measure and one per layout");
            }
            assertEquals(250, grid.width(), 1e-3, "laid out at its natural width, narrower than "
                    + "the bound it was measured against: the shape that would miss");
        }
    }

    @Test
    void aCellOfAGridThatIsTheRootIsMeasuredOncePerPass() {
        List<CountingBox> leaves = leaves();
        threePasses(new Scene(gridOf(leaves)), leaves);
        for (CountingBox leaf : leaves) {
            assertEquals(3, leaf.measures, "a tight parent is the easy case, and still once");
        }
    }

    /**
     * The expanded column's cells are asked once, at the width the others leave, even stretched:
     * they have no say in that width, so the open question would be a second one for nothing.
     */
    @Test
    void theExpandedColumnsCellsAreMeasuredOncePerPassEvenStretched() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            List<CountingBox> leaves = leaves();
            Grid grid = gridOf(leaves).expandedColumn(1)
                    .columnAlignment(1, Flex.CrossAlignment.STRETCH);

            threePasses(inLooseParent(grid, direction), leaves);

            for (CountingBox leaf : leaves) {
                assertEquals(3, leaf.measures, direction + ": once per pass");
            }
            assertEquals(200, leaves.get(3).width(), 1e-3, "and stretched to the 300 less the "
                    + "caption column's 90 and the gap");
        }
    }

    /**
     * {@code STRETCH}'s price across a column, pinned so that it is paid only where it has to be:
     * the widest cell already is the column's width and is not asked again; a narrower one is
     * asked at the column's width, and asked the open question again at the next pass's measure.
     */
    @Test
    void aStretchedCellNarrowerThanItsColumnIsMeasuredTwicePerPassAndTheWidestOnce() {
        List<CountingBox> leaves = leaves();
        Grid grid = gridOf(leaves).columnAlignment(1, Flex.CrossAlignment.STRETCH);

        threePasses(inLooseParent(grid, LayoutDirection.LTR), leaves);

        assertEquals(3, leaves.get(0).measures, "a caption column left at START: once");
        assertEquals(3, leaves.get(1).measures, "the widest field: once");
        assertEquals(6, leaves.get(3).measures, "120 in a column 150 wide: asked again at 150");
        assertEquals(150, leaves.get(3).width(), 1e-3, "and it was stretched");
    }

    /** And {@code STRETCH}'s price down a row, the one a {@code Wrap} pays within a run. */
    @Test
    void aCellShorterThanAStretchedRowIsMeasuredTwicePerPassAndTheTallestOnce() {
        List<CountingBox> leaves = leaves();
        Grid grid = gridOf(leaves).crossAlignment(Flex.CrossAlignment.STRETCH);

        threePasses(inLooseParent(grid, LayoutDirection.LTR), leaves);

        assertEquals(6, leaves.get(0).measures, "20 in a row 30 tall: asked again at 30");
        assertEquals(3, leaves.get(1).measures, "the row's tallest: once");
        assertEquals(3, leaves.get(2).measures, "a row of equal heights asks nobody again");
        assertEquals(3, leaves.get(3).measures);
        assertEquals(30, leaves.get(0).height(), 1e-3, "and it was stretched");
    }

    /** Both prices at once, and no more: a cell short of its row and narrower than its column. */
    @Test
    void aCellStretchedBothWaysAndShortOfBothIsMeasuredThreeTimesPerPass() {
        List<CountingBox> leaves = leaves();
        Grid grid = gridOf(leaves).crossAlignment(Flex.CrossAlignment.STRETCH)
                .columnAlignment(0, Flex.CrossAlignment.STRETCH);

        threePasses(inLooseParent(grid, LayoutDirection.LTR), leaves);

        assertEquals(9, leaves.get(0).measures, "60 by 20 in a column 90 wide and a row 30 tall: "
                + "the open question, the column's width, and the row's height");
        assertEquals(3, leaves.get(1).measures, "the row's tallest, in a column left at START");
        assertEquals(3, leaves.get(2).measures, "the caption column's widest, in a row of equal "
                + "heights");
        assertEquals(90, leaves.get(0).width(), 1e-3);
        assertEquals(30, leaves.get(0).height(), 1e-3);
    }

    /**
     * With no bound on the width the expanded column is sized from its cells, and the grid is then
     * laid out at its natural width, which a row does to a child that is not flexible. Layout must
     * find the column measure found, or it asks the stretched cell a question one float away from
     * the one it answered. The widths are the ones {@code GridTest} shows disagree.
     */
    @Test
    void anExpandedColumnLaidOutAtItsNaturalWidthIsMeasuredOncePerPass() {
        List<CountingBox> leaves = List.of(new CountingBox(0.1f, 20), new CountingBox(0.3f, 20));
        Grid grid = new Grid(2).gap(7.1f).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);
        for (CountingBox leaf : leaves) {
            grid.add(leaf);
        }
        Row row = new Row();
        row.add(grid);

        threePasses(new Scene(row), leaves);

        for (CountingBox leaf : leaves) {
            assertEquals(3, leaf.measures, "once per pass");
        }
    }
}
