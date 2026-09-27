package limn.scene;

import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.Wrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a {@link Wrap} does with the width it is given: where a run breaks, what the wrap measures
 * to, and how each run places its children. The direction's half is in
 * {@code ContainerMirroringTest}, the measure count in {@code WrapMeasureCountTest}, and what a
 * reader is handed in {@code WrapAccessibilityTest}.
 */
class WrapTest extends SceneTestBase {

    private static final float EPS = 1e-3f;

    /** A leaf whose text baseline sits where it is told, the way a control carrying text does. */
    private static final class Ascent extends Widget<Ascent> {
        private final float prefWidth;
        private final float prefHeight;
        private final float baseline;

        Ascent(float prefWidth, float prefHeight, float baseline) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
            this.baseline = baseline;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }

        @Override
        protected float baselineOffset() {
            return baseline;
        }
    }

    private static Wrap wrap(float gap, Widget<?>... children) {
        Wrap wrap = new Wrap().gap(gap);
        for (Widget<?> child : children) {
            wrap.add(child);
        }
        return wrap;
    }

    /** Measures under a loose bound and lays out at the answer, as a column's START child is. */
    private static Size layoutLoose(Widget<?> widget, float width, float height) {
        Size size = widget.measure(Constraints.loose(width, height));
        widget.layoutBox(0, 0, size.width(), size.height());
        return size;
    }

    private static void layoutTight(Widget<?> widget, float width, float height) {
        widget.measure(Constraints.tight(width, height));
        widget.layoutBox(0, 0, width, height);
    }

    // ------------------------------------------------------------------ breaking

    @Test
    void childrenThatFitStayOnOneRunWhereARowWouldPutThem() {
        FixedBox[] inWrap = {new FixedBox(50, 20), new FixedBox(60, 30), new FixedBox(70, 10)};
        FixedBox[] inRow = {new FixedBox(50, 20), new FixedBox(60, 30), new FixedBox(70, 10)};
        Wrap wrap = wrap(10, inWrap);
        Row row = new Row().gap(10);
        for (FixedBox box : inRow) {
            row.add(box);
        }
        for (Flex.MainAlignment alignment : Flex.MainAlignment.values()) {
            wrap.mainAlignment(alignment);
            row.mainAlignment(alignment);
            layoutTight(wrap, 300, 30);
            layoutTight(row, 300, 30);
            for (int i = 0; i < inWrap.length; i++) {
                assertEquals(inRow[i].x(), inWrap[i].x(), EPS, alignment + ": child " + i);
                assertEquals(inRow[i].y(), inWrap[i].y(), EPS, alignment + ": child " + i);
                assertEquals(inRow[i].width(), inWrap[i].width(), EPS);
            }
        }
    }

    @Test
    void theNextChildThatDoesNotFitStartsARunBelow() {
        FixedBox a = new FixedBox(80, 20);
        FixedBox b = new FixedBox(70, 30);
        FixedBox c = new FixedBox(60, 20);
        FixedBox d = new FixedBox(50, 10);
        Wrap wrap = wrap(10, a, b, c, d).runGap(8);

        Size size = layoutLoose(wrap, 200, 300);

        assertEquals(0, a.x(), EPS);
        assertEquals(90, b.x(), EPS, "80 and a gap");
        assertEquals(0, a.y(), EPS);
        assertEquals(0, b.y(), EPS);
        assertEquals(0, c.x(), EPS, "160 + 10 + 60 is past 200, so c starts the second run");
        assertEquals(38, c.y(), EPS, "below the first run's tallest child and the run gap");
        assertEquals(70, d.x(), EPS);
        assertEquals(38, d.y(), EPS);
        assertEquals(160, size.width(), EPS, "as wide as the widest run, not as the bound");
        assertEquals(30 + 8 + 20, size.height(), EPS, "each run's tallest child and one run gap");
    }

    @Test
    void aChildThatExactlyFillsWhatIsLeftStaysOnTheRun() {
        FixedBox a = new FixedBox(100, 20);
        FixedBox b = new FixedBox(90, 20);
        Wrap wrap = wrap(10, a, b);

        Size size = layoutLoose(wrap, 200, 100);

        assertEquals(0, b.y(), EPS, "100 + 10 + 90 is exactly 200: it fits");
        assertEquals(110, b.x(), EPS);
        assertEquals(20, size.height(), EPS, "one run");
    }

    @Test
    void aChildWiderThanTheWrapTakesARunOfItsOwnAndIsGivenAtMostTheWidth() {
        FixedBox before = new FixedBox(50, 20);
        FixedBox wide = new FixedBox(300, 20);
        FixedBox after = new FixedBox(40, 20);
        Wrap wrap = wrap(10, before, wide, after);

        Size size = layoutLoose(wrap, 200, 300);

        assertEquals(0, before.y(), EPS);
        assertEquals(0, wide.x(), EPS);
        assertEquals(20, wide.y(), EPS, "a run of its own, below the first");
        assertEquals(200, wide.width(), EPS, "measured against the wrap's width, so given at most that");
        assertEquals(40, after.y(), EPS, "and the next child cannot share its run");
        assertEquals(200, size.width(), EPS);
        assertEquals(60, size.height(), EPS);

        FixedBox first = new FixedBox(300, 20);
        FixedBox second = new FixedBox(40, 20);
        layoutLoose(wrap(10, first, second), 200, 300);
        assertEquals(0, first.y(), EPS, "a wide first child opens the first run; no empty run before it");
        assertEquals(20, second.y(), EPS);
    }

    @Test
    void withNoBoundOnTheWidthEverythingIsOneRun() {
        Wrap wrap = wrap(10, new FixedBox(120, 40), new FixedBox(800, 30), new FixedBox(100, 20))
                .runGap(8);
        Size natural = wrap.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                Constraints.UNBOUNDED_LIMIT));
        assertEquals(120 + 10 + 800 + 10 + 100, natural.width(), EPS, "one run, as long as a row");
        assertEquals(40, natural.height(), EPS, "its tallest child, and no run gap");
    }

    /**
     * The natural width is a run's extent, and a wrap laid out at exactly that width must break
     * nowhere. These widths are chosen because {@code (0.1 + 7.1) + 25} and {@code 0.1 + (7.1 + 25)}
     * round to different floats, so a break test that summed in one order and an extent that summed
     * in the other would push the last child onto a run of its own at its own natural width.
     */
    @Test
    void aWrapLaidOutAtItsNaturalWidthBreaksNowhere() {
        FixedBox thin = new FixedBox(0.1f, 20);
        FixedBox wide = new FixedBox(25f, 20);
        Wrap wrap = wrap(7.1f, thin, wide);
        Column column = new Column(); // START across: the wrap is laid out at its natural width
        column.add(wrap);

        Size natural = column.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                Constraints.UNBOUNDED_LIMIT));
        column.layoutBox(0, 0, natural.width(), natural.height());

        assertEquals(natural.width(), wrap.width(), "laid out at exactly its natural width");
        assertEquals(0, wide.y(), EPS, "the last child dropped to a run of its own");
        assertEquals(20, wrap.height(), EPS);
        Size atWidth = wrap.measure(Constraints.tight(wrap.width(), 20));
        assertEquals(20, atWidth.height(), EPS, "and measuring at that width says one run too");
    }

    @Test
    void hiddenChildrenTakeNoRoomAndNoGap() {
        FixedBox a = new FixedBox(80, 20);
        FixedBox hidden = new FixedBox(70, 20);
        FixedBox c = new FixedBox(60, 20);
        Wrap wrap = wrap(10, a, hidden, c);
        layoutLoose(wrap, 200, 100);
        assertEquals(20, c.y(), EPS, "on the second run while the middle one shows");

        hidden.setVisible(false);
        Size size = layoutLoose(wrap, 200, 100);

        assertEquals(0, c.y(), EPS, "back on the first run");
        assertEquals(90, c.x(), EPS, "with one gap before it, not two");
        assertEquals(150, size.width(), EPS);
        assertEquals(20, size.height(), EPS);
    }

    @Test
    void aWrapWithNothingVisibleIsEmpty() {
        FixedBox only = new FixedBox(80, 20);
        Wrap wrap = wrap(10, only).runGap(8);
        only.setVisible(false);
        assertEquals(Size.ZERO, wrap.measure(Constraints.loose(200, 100)));
        assertEquals(Size.ZERO, new Wrap().measure(Constraints.loose(200, 100)));
    }

    // ------------------------------------------------------------------ alignment along a run

    @Test
    void mainAlignmentPlacesEachRunOnItsOwn() {
        FixedBox a = new FixedBox(80, 20);
        FixedBox b = new FixedBox(70, 20);
        FixedBox c = new FixedBox(60, 20);
        Wrap wrap = wrap(10, a, b, c);

        // First run 160 of 200, so 40 left over; second run 60, so 140.
        wrap.mainAlignment(Flex.MainAlignment.CENTER);
        layoutTight(wrap, 200, 40);
        assertEquals(20, a.x(), EPS);
        assertEquals(110, b.x(), EPS);
        assertEquals(70, c.x(), EPS, "each run is centred on its own leftover");

        wrap.mainAlignment(Flex.MainAlignment.END);
        layoutTight(wrap, 200, 40);
        assertEquals(40, a.x(), EPS);
        assertEquals(130, b.x(), EPS);
        assertEquals(140, c.x(), EPS);

        wrap.mainAlignment(Flex.MainAlignment.SPACE_BETWEEN);
        layoutTight(wrap, 200, 40);
        assertEquals(0, a.x(), EPS);
        assertEquals(130, b.x(), EPS, "the first run spread to both edges");
        assertEquals(0, c.x(), EPS, "a run of one child sits where reading starts");

        wrap.mainAlignment(Flex.MainAlignment.RIGHT);
        layoutTight(wrap, 200, 40);
        assertEquals(40, a.x(), EPS, "RIGHT is END reading left to right");
        assertEquals(140, c.x(), EPS);
    }

    // ------------------------------------------------------------------ placement within a run

    @Test
    void crossAlignmentPlacesAChildWithinItsRunsHeight() {
        FixedBox shortOne = new FixedBox(40, 20);
        FixedBox tall = new FixedBox(40, 30);
        FixedBox next = new FixedBox(150, 10);
        FixedBox beside = new FixedBox(40, 16);
        Wrap wrap = wrap(10, shortOne, tall, next, beside).runGap(4);

        wrap.crossAlignment(Flex.CrossAlignment.START);
        layoutLoose(wrap, 200, 200);
        assertEquals(0, shortOne.y(), EPS);
        assertEquals(34, next.y(), EPS, "the second run starts below the first run's 30 and the gap");

        wrap.crossAlignment(Flex.CrossAlignment.CENTER);
        layoutLoose(wrap, 200, 200);
        assertEquals(5, shortOne.y(), EPS, "centred in the 30 of its run");
        assertEquals(0, tall.y(), EPS);
        assertEquals(34 + 3, next.y(), EPS, "and in the second run, in the 16 of that run");
        assertEquals(34, beside.y(), EPS);

        wrap.crossAlignment(Flex.CrossAlignment.END);
        layoutLoose(wrap, 200, 200);
        assertEquals(10, shortOne.y(), EPS);
        assertEquals(34 + 6, next.y(), EPS);
    }

    @Test
    void stretchMakesEveryChildOfARunAsTallAsItsRunAndNoTaller() {
        FixedBox shortOne = new FixedBox(40, 20);
        FixedBox tall = new FixedBox(40, 30);
        FixedBox next = new FixedBox(150, 10);
        FixedBox beside = new FixedBox(40, 16);
        Wrap wrap = wrap(10, shortOne, tall, next, beside).runGap(4);
        wrap.crossAlignment(Flex.CrossAlignment.STRETCH);

        Size size = layoutLoose(wrap, 200, 200);

        assertEquals(30, shortOne.height(), EPS, "as tall as the first run");
        assertEquals(40, shortOne.width(), EPS, "and no wider than it was");
        assertEquals(30, tall.height(), EPS);
        assertEquals(16, next.height(), EPS, "as tall as the second run, not the first");
        assertEquals(34, next.y(), EPS);
        assertEquals(30 + 4 + 16, size.height(), EPS, "stretching changes no run's height");
    }

    @Test
    void baselineLinesUpTheTextOfEachRunAndStaysInsideIt() {
        Ascent shallow = new Ascent(60, 20, 16);
        Ascent deep = new Ascent(60, 30, 20);
        FixedBox box = new FixedBox(80, 10);
        Ascent low = new Ascent(40, 20, 18);
        Ascent high = new Ascent(40, 20, 2);
        Wrap wrap = wrap(10, shallow, deep, box, low, high).runGap(4);
        wrap.crossAlignment(Flex.CrossAlignment.BASELINE);

        layoutLoose(wrap, 200, 200);

        assertEquals(0, box.x(), EPS, "130 + 10 + 80 is past 200: the box opens the second run");
        assertEquals(4, shallow.y(), EPS, "moved down onto the deeper baseline, 20 - 16; CENTER "
                + "would say 5 and END 10");
        assertEquals(0, deep.y(), EPS);
        // The second run is 20 tall and starts below the first run's 30 and the gap, at 34. Its
        // deepest baseline is low's 18; high wants 16 down and has no room inside its run.
        assertEquals(34, low.y(), EPS);
        assertEquals(34, high.y(), EPS, "clamped inside its run instead of into the next");
        assertEquals(34 + 8, box.y(), EPS, "a box with no text aligns its bottom edge, 18 - 10");
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    void anExpandedIsRefusedWhenItIsAdded() {
        Wrap wrap = new Wrap();
        assertThrows(IllegalArgumentException.class, () -> wrap.add(Expanded.of(new FixedBox(10, 10))));
        assertThrows(IllegalArgumentException.class, () -> wrap.add(Expanded.spacer(1)));
        assertThrows(IllegalArgumentException.class,
                () -> wrap.add(0, Expanded.of(new FixedBox(10, 10))));
        assertEquals(0, wrap.children().size(), "and nothing was added");
        wrap.add(new FixedBox(10, 10));
        wrap.add(0, new FixedBox(10, 10));
        assertEquals(2, wrap.children().size(), "anything else is");
    }

    @Test
    void theSettersClampANegativeGapAndRefuseWhatIsNotANumber() {
        FixedBox a = new FixedBox(80, 20);
        FixedBox b = new FixedBox(80, 20);
        FixedBox c = new FixedBox(80, 20);
        Wrap wrap = wrap(-5, a, b, c).runGap(-3);
        layoutLoose(wrap, 170, 100);
        assertEquals(80, b.x(), EPS, "a negative gap is none");
        assertEquals(20, c.y(), EPS, "and so is a negative run gap");

        assertThrows(IllegalArgumentException.class, () -> wrap.gap(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> wrap.runGap(Float.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> wrap.mainAlignment(null));
        assertThrows(NullPointerException.class, () -> wrap.crossAlignment(null));
        layoutLoose(wrap, 170, 100);
        assertEquals(80, b.x(), EPS, "a refused value changed nothing");
    }

    /** A setter given the value the wrap already holds asks for no layout, so no measure runs. */
    @Test
    void aSetterGivenTheValueItHoldsAsksForNoLayout() {
        int[] measures = {0};
        Wrap wrap = new Wrap() {
            @Override
            protected Size onMeasure(Constraints constraints) {
                measures[0]++;
                return super.onMeasure(constraints);
            }
        };
        wrap.add(new FixedBox(40, 20));
        wrap.gap(8).runGap(4).mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.END);
        Constraints loose = Constraints.loose(200, 100);
        wrap.measure(loose);
        assertEquals(1, measures[0]);

        wrap.gap(8).runGap(4).mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.END);
        wrap.measure(loose);
        assertEquals(1, measures[0], "nothing changed, so the wrap answered from its cache");

        for (Runnable change : new Runnable[] {
                () -> wrap.gap(9), () -> wrap.runGap(5),
                () -> wrap.mainAlignment(Flex.MainAlignment.END),
                () -> wrap.crossAlignment(Flex.CrossAlignment.START)}) {
            int before = measures[0];
            change.run();
            wrap.measure(loose);
            assertEquals(before + 1, measures[0], "and each real change measures again");
        }
    }
}
