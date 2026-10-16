package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/**
 * {@link RangeSlider} under the value contract (ADR 045 §4), each thumb on its own: a range of 0
 * to 100 by 5, set to 30 to 70 with a gap of 10, where a thumb's bounds are what the other leaves
 * it.
 */
class RangeSliderValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theLowerThumb() {
        return ContractTests.of(ValueContract.cases(new Subject(true), runtime));
    }

    @TestFactory
    Stream<DynamicTest> theUpperThumb() {
        return ContractTests.of(ValueContract.cases(new Subject(false), runtime));
    }

    private static final class Subject implements ValueSubject {
        private final boolean lower;
        private RangeSlider range;
        private int changes;

        Subject(boolean lower) {
            this.lower = lower;
        }

        @Override
        public Widget<?> build() {
            range = new RangeSlider(0, 100).setStep(5).setMinimumGap(10).setRange(30, 70);
            range.setAccessibleName("Price");
            changes = 0;
            range.onChange((low, high) -> changes++);
            Column root = new Column();
            root.add(new SizedBox(300, 30, range));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return range;
        }

        @Override
        public double value() {
            return lower ? range.lower() : range.upper();
        }

        @Override
        public double min() {
            return lower ? range.min() : range.lower() + range.minimumGap();
        }

        @Override
        public double max() {
            return lower ? range.upper() - range.minimumGap() : range.max();
        }

        @Override
        public double step() {
            return 5;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public void set(double value) {
            if (lower) {
                range.setRange((float) value, range.upper());
            } else {
                range.setRange(range.lower(), (float) value);
            }
        }

        @Override
        public int changes() {
            return changes;
        }

        @Override
        public int valueNodes() {
            return 2;
        }

        @Override
        public int valueNode() {
            return lower ? 0 : 1;
        }
    }
}
