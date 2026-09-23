package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link Slider} under the value contract (ADR 045 §4): a stepped range of 0 to 100 by 5. */
class SliderValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ContractTests.of(ValueContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements ValueSubject {
        private Slider slider;
        private int changes;

        @Override
        public Widget<?> build() {
            slider = new Slider(0, 100).setStep(5).setValue(40);
            slider.setAccessibleName("Volume");
            changes = 0;
            slider.onChange(value -> changes++);
            Column root = new Column();
            root.add(new SizedBox(300, 30, slider));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return slider;
        }

        @Override
        public double value() {
            return slider.value();
        }

        @Override
        public double min() {
            return slider.min();
        }

        @Override
        public double max() {
            return slider.max();
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
            slider.setValue((float) value);
        }

        @Override
        public int changes() {
            return changes;
        }
    }
}
