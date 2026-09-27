package limn.scene;

import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Wrap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a {@link Wrap} costs its children per layout pass: one measure each, because every child is
 * asked against the whole width and never against what is left of a run, and layout asks the
 * question measure already asked. {@code STRETCH} is the one exception, and it is priced here: a
 * child shorter than its run is asked a second time, at the run's height, which only exists once
 * the run is formed.
 *
 * <p>The shape that would miss is the one {@code FlexMeasureCountTest} records for a column: a wrap
 * in a loose parent is measured against the parent's bound and laid out at its own natural width,
 * which is narrower. Asking the children again against that box would miss the one-answer cache in
 * {@code Widget.measure} and double every leaf's work.
 */
class WrapMeasureCountTest extends SceneTestBase {

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

    /** Three runs at 300 with a gap of 10: 120 and 150, then 200, then 100. */
    private static List<CountingBox> leaves() {
        return List.of(new CountingBox(120, 20), new CountingBox(150, 30), new CountingBox(200, 20),
                new CountingBox(100, 10));
    }

    private static Wrap wrapOf(List<CountingBox> leaves) {
        Wrap wrap = new Wrap().gap(10).runGap(6);
        for (CountingBox leaf : leaves) {
            wrap.add(leaf);
        }
        return wrap;
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

    @Test
    void aChildOfAWrapInALooseParentIsMeasuredOncePerPass() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            List<CountingBox> leaves = leaves();
            Wrap wrap = wrapOf(leaves);
            Column loose = new Column(); // START across: the wrap gets 0..300 and takes 280
            loose.add(wrap);
            Scene scene = new Scene(loose);
            scene.setLayoutDirection(direction);

            threePasses(scene, leaves);

            for (CountingBox leaf : leaves) {
                assertEquals(3, leaf.measures, direction + ": one onMeasure per pass, not one per "
                        + "measure and one per layout");
            }
            assertEquals(280, wrap.width(), 1e-3, "laid out at its natural width, narrower than "
                    + "the bound it was measured against: the shape that would miss");
            assertEquals(30 + 6 + 20 + 6 + 10, wrap.height(), 1e-3, "three runs");
            assertEquals(36, leaves.get(2).y(), 1e-3, "the third leaf opens the second run");
        }
    }

    @Test
    void aChildOfAWrapThatIsTheRootIsMeasuredOncePerPass() {
        List<CountingBox> leaves = leaves();
        Scene scene = new Scene(wrapOf(leaves));
        threePasses(scene, leaves);
        for (CountingBox leaf : leaves) {
            assertEquals(3, leaf.measures, "a tight parent is the easy case, and still once");
        }
    }

    /**
     * {@code STRETCH}'s price, pinned so that it is paid only where it has to be: the tallest child
     * of each run already is the run's height and is not asked again; a shorter one is asked at the
     * run's height, and asked the open question again at the next pass's measure.
     */
    @Test
    void aStretchedChildShorterThanItsRunIsMeasuredTwicePerPassAndTheTallestOnce() {
        List<CountingBox> leaves = leaves();
        Wrap wrap = wrapOf(leaves).crossAlignment(Flex.CrossAlignment.STRETCH);
        Column loose = new Column();
        loose.add(wrap);
        Scene scene = new Scene(loose);

        threePasses(scene, leaves);

        assertEquals(6, leaves.get(0).measures, "20 in a run 30 tall: asked again at 30");
        assertEquals(3, leaves.get(1).measures, "the run's tallest: once");
        assertEquals(3, leaves.get(2).measures, "alone on its run, so already its height: once");
        assertEquals(3, leaves.get(3).measures, "alone on its run too");
        assertEquals(30, leaves.get(0).height(), 1e-3, "and it was stretched");
    }
}
