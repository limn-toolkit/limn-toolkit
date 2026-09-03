package limn.scene;

import limn.scene.layout.Column;
import limn.scene.layout.Row;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A fixed child of a Flex is measured once per layout pass. Layout used to ask it again with
 * the box's own loose size as the bound, which is not the bound measure asked with, so the
 * one-answer cache in {@code Widget.measure} missed and every fixed leaf ran onMeasure twice.
 */
class FlexMeasureCountTest extends SceneTestBase {

    /** A fixed-size leaf that counts how often it is asked. */
    private static final class CountingBox extends Widget {
        int measures;

        void dirty() {
            markNeedsLayout();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return constraints.constrain(80, 20);
        }
    }

    @Test
    void aFixedChildOfAColumnInALooseParentIsMeasuredOncePerPass() {
        CountingBox leaf = new CountingBox();
        Column column = new Column();
        column.add(leaf);
        Row loose = new Row(); // a Row gives its column a loose cross bound: the shape that missed
        loose.add(column);
        Scene scene = new Scene(loose);
        scene.layoutPass(300, 300);
        leaf.measures = 0;
        for (int pass = 0; pass < 3; pass++) {
            leaf.dirty();
            scene.layoutPass(300, 300);
        }
        assertEquals(3, leaf.measures, "one onMeasure per pass, not one per measure and one per layout");
        assertEquals(80, leaf.width(), 1e-3);
        assertEquals(20, leaf.height(), 1e-3);
    }
}
