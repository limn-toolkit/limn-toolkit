package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link Spinner} under the value contract (ADR 045 §4): whole numbers from 0 to 99. */
class SpinnerValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ContractTests.of(ValueContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements ValueSubject {
        private Spinner spinner;
        private int changes;

        @Override
        public Widget<?> build() {
            spinner = new Spinner(0, 99, 1).setValue(10);
            spinner.setAccessibleName("Quantity");
            changes = 0;
            spinner.onChange(value -> changes++);
            Column root = new Column();
            root.add(new SizedBox(160, 32, spinner));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return spinner;
        }

        @Override
        public double value() {
            return spinner.value();
        }

        @Override
        public double min() {
            return spinner.min();
        }

        @Override
        public double max() {
            return spinner.max();
        }

        @Override
        public double step() {
            return 1;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public void set(double value) {
            spinner.setValue(value);
        }

        @Override
        public int changes() {
            return changes;
        }
    }
}
