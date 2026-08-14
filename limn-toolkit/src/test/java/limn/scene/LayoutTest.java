package limn.scene;

import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LayoutTest extends SceneTestBase {

    private static final float EPS = 1e-3f;

    @Test
    void constraintsMath() {
        Constraints c = new Constraints(10, 100, 20, 200);
        assertEquals(10, c.constrainWidth(5), EPS);
        assertEquals(100, c.constrainWidth(500), EPS);
        assertEquals(new Size(50, 60), c.constrain(50, 60));

        Constraints deflated = Constraints.loose(100, 100).deflate(Insets.all(10));
        assertEquals(80, deflated.maxWidth(), EPS);
        assertEquals(80, deflated.maxHeight(), EPS);

        Constraints unbounded = new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, 50);
        assertEquals(false, unbounded.hasBoundedWidth());
        assertEquals(true, unbounded.hasBoundedHeight());
    }

    @Test
    void columnStacksChildrenWithGap() {
        Column column = new Column();
        column.gap(10);
        FixedBox a = new FixedBox(100, 20);
        FixedBox b = new FixedBox(80, 30);
        column.add(a);
        column.add(b);

        column.measure(Constraints.tight(200, 300));
        column.layoutBox(0, 0, 200, 300);

        assertEquals(0, a.y(), EPS);
        assertEquals(20, a.height(), EPS);
        assertEquals(30, b.y(), EPS, "second child sits below the first + gap");
        assertEquals(100, a.width(), EPS);
    }

    @Test
    void rowDistributesLeftoverByFlexWeight() {
        Row row = new Row();
        FixedBox fixed = new FixedBox(60, 40);
        FixedBox flex1Content = new FixedBox(10, 40);
        FixedBox flex2Content = new FixedBox(10, 40);
        row.add(fixed);
        row.add(Expanded.of(flex1Content, 1));
        row.add(Expanded.of(flex2Content, 2));

        row.measure(Constraints.tight(300, 40));
        row.layoutBox(0, 0, 300, 40);

        assertEquals(60, fixed.width(), EPS);
        assertEquals(80, row.children().get(1).width(), EPS, "flex 1 of 240 leftover");
        assertEquals(160, row.children().get(2).width(), EPS, "flex 2 of 240 leftover");
        assertEquals(60, row.children().get(1).x(), EPS);
        assertEquals(140, row.children().get(2).x(), EPS);
    }

    /**
     * A leaf that remembers the width each measure pass handed it. Flex measures its
     * children with a tight main axis, so this is the share as {@code onMeasure} resolved
     * it: the number {@code onLayout} has to arrive at independently.
     */
    private static final class MeasuredBox extends Widget {
        float lastMeasuredWidth = Float.NaN;

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = constraints.constrain(10, 20);
            lastMeasuredWidth = size.width();
            return size;
        }
    }

    @Test
    void aFloorChangesNothingWhileThereIsSlack() {
        // Same row as rowDistributesLeftoverByFlexWeight, with a floor small enough that
        // it never binds: a declared floor must not perturb the weighted split.
        Row row = new Row();
        FixedBox fixed = new FixedBox(60, 40);
        row.add(fixed);
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(50));
        row.add(Expanded.of(new FixedBox(10, 40), 2));

        row.measure(Constraints.tight(300, 40));
        row.layoutBox(0, 0, 300, 40);

        assertEquals(80, row.children().get(1).width(), EPS, "flex 1 of 240 leftover");
        assertEquals(160, row.children().get(2).width(), EPS, "flex 2 of 240 leftover");
        assertEquals(140, row.children().get(2).x(), EPS);
    }

    @Test
    void aFlooredChildKeepsItsFloorAndItsSiblingGivesUpTheSpace() {
        // The measured bug: an even split hands a stepper less than its own chrome. The
        // floor is what the stepper's chrome needs; the label beside it has none and
        // absorbs the whole loss, which is the trade the call site is making.
        Row row = new Row();
        row.add(Expanded.of(new FixedBox(10, 40), 1));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(116));

        row.measure(Constraints.tight(200, 40));
        row.layoutBox(0, 0, 200, 40);

        assertEquals(116, row.children().get(1).width(), EPS, "the floor was not honoured");
        assertEquals(84, row.children().get(0).width(), EPS, "the unfloored sibling pays for it");
        assertEquals(200, row.children().get(0).width() + row.children().get(1).width(), EPS,
                "floors that fit must not overflow the row");
    }

    @Test
    void freezingOneChildCanPushAnotherUnderItsOwnFloor() {
        // The reason this is a loop and not one pass. Even thirds of 200 are 66.67: only
        // the 90 floor is violated at first. Freezing it leaves 110 for two, i.e. 55 each,
        // which puts the 60 floor under water, and a single-pass split would ship 55.
        Row row = new Row();
        row.add(Expanded.of(new FixedBox(10, 40), 1));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(60));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(90));

        row.measure(Constraints.tight(200, 40));
        row.layoutBox(0, 0, 200, 40);

        assertEquals(90, row.children().get(2).width(), EPS, "the first floor found");
        assertEquals(60, row.children().get(1).width(), EPS,
                "the floor that only came under water once the first one froze");
        assertEquals(50, row.children().get(0).width(), EPS, "the unfloored child takes the rest");
    }

    @Test
    void floorsThatCannotFitOverflowTheRowRatherThanCollapsing() {
        // 140pt of floors in a 100pt row. Every floor is still honoured and the row runs
        // past its own edge, the same thing an over-wide row of fixed children does. The
        // pool goes negative here, which is where a share could turn negative if the
        // distribution did not clamp.
        Row row = new Row();
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(80));
        row.add(Expanded.of(new FixedBox(10, 40), 1).atLeast(60));
        row.add(Expanded.of(new FixedBox(10, 40), 1));

        row.measure(Constraints.tight(100, 40));
        row.layoutBox(0, 0, 100, 40);

        assertEquals(80, row.children().get(0).width(), EPS, "a floor is not negotiable");
        assertEquals(60, row.children().get(1).width(), EPS, "nor is the second one");
        assertEquals(0, row.children().get(2).width(), EPS,
                "the unfloored child is squeezed to nothing, never below it");
        assertEquals(140, row.children().get(2).x(), EPS,
                "and the row overflows: the third child starts past the 100pt edge");
        assertEquals(100, row.width(), EPS, "the row itself still fills what it was given");
    }

    @Test
    void measureAndLayoutResolveTheSameFlooredShares() {
        // Measure and layout run the distribution independently. Capture what measure
        // handed each child BEFORE laying out: comparing afterwards would let a layout
        // pass that re-measured overwrite the very number under test.
        Row row = new Row();
        MeasuredBox free = new MeasuredBox();
        MeasuredBox floored = new MeasuredBox();
        row.add(Expanded.of(free, 3));
        row.add(Expanded.of(floored, 1).atLeast(116));

        row.measure(Constraints.tight(200, 40));
        float freeAtMeasure = free.lastMeasuredWidth;
        float flooredAtMeasure = floored.lastMeasuredWidth;
        row.layoutBox(0, 0, 200, 40);

        assertEquals(116, flooredAtMeasure, EPS, "measure ignored the floor");
        assertEquals(84, freeAtMeasure, EPS, "measure gave the sibling the wrong remainder");
        assertEquals(flooredAtMeasure, row.children().get(1).width(), EPS,
                "layout disagreed with measure about the floored child");
        assertEquals(freeAtMeasure, row.children().get(0).width(), EPS,
                "layout disagreed with measure about its sibling");
    }

    @Test
    void spaceBetweenPushesChildrenApart() {
        Column column = new Column();
        column.mainAlignment(Flex.MainAlignment.SPACE_BETWEEN);
        FixedBox a = new FixedBox(50, 50);
        FixedBox b = new FixedBox(50, 50);
        column.add(a);
        column.add(b);

        column.layoutBox(0, 0, 100, 300);
        assertEquals(0, a.y(), EPS);
        assertEquals(250, b.y(), EPS);
    }

    @Test
    void crossAlignmentStretchAndCenter() {
        Column stretch = new Column();
        stretch.crossAlignment(Flex.CrossAlignment.STRETCH);
        FixedBox a = new FixedBox(50, 20);
        stretch.add(a);
        stretch.layoutBox(0, 0, 200, 100);
        assertEquals(200, a.width(), EPS, "STRETCH fills the cross axis");

        Column center = new Column();
        center.crossAlignment(Flex.CrossAlignment.CENTER);
        FixedBox b = new FixedBox(50, 20);
        center.add(b);
        center.layoutBox(0, 0, 200, 100);
        assertEquals(75, b.x(), EPS);
    }

    @Test
    void spacerConsumesLeftover() {
        Row row = new Row();
        FixedBox left = new FixedBox(50, 20);
        FixedBox right = new FixedBox(50, 20);
        row.add(left);
        row.add(Expanded.spacer(1));
        row.add(right);
        row.layoutBox(0, 0, 200, 20);
        assertEquals(150, right.x(), EPS, "spacer pushes the second box to the far edge");
    }

    @Test
    void paddingInsetsChild() {
        FixedBox box = new FixedBox(50, 50);
        Padding padding = Padding.all(10, box);
        Size size = padding.measure(Constraints.loose(200, 200));
        assertEquals(70, size.width(), EPS);
        padding.layoutBox(0, 0, 70, 70);
        assertEquals(10, box.x(), EPS);
        assertEquals(10, box.y(), EPS);
        assertEquals(50, box.width(), EPS);
    }

    @Test
    void sizedBoxForcesDimensions() {
        SizedBox sized = new SizedBox(80, 40, new FixedBox(200, 200));
        Size size = sized.measure(Constraints.loose(500, 500));
        assertEquals(80, size.width(), EPS);
        assertEquals(40, size.height(), EPS);
    }

    @Test
    void stackSizesToBiggestChildAndAligns() {
        Stack stack = new Stack();
        stack.alignment(Stack.Alignment.BOTTOM_RIGHT);
        FixedBox big = new FixedBox(200, 100);
        FixedBox small = new FixedBox(50, 20);
        stack.add(big);
        stack.add(small);

        Size size = stack.measure(Constraints.loose(400, 400));
        assertEquals(200, size.width(), EPS);
        assertEquals(100, size.height(), EPS);

        stack.layoutBox(0, 0, 200, 100);
        assertEquals(150, small.x(), EPS);
        assertEquals(80, small.y(), EPS);
    }
}
