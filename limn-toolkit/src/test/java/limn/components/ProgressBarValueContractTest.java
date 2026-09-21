package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.ValueContract;
import limn.testing.a11y.ValueSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link ProgressBar} under the value contract (ADR 045 §4): the read-only variant, at 40%. */
class ProgressBarValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ContractTests.of(ValueContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements ValueSubject {
        private ProgressBar bar;

        @Override
        public Widget build() {
            bar = new ProgressBar().setProgress(0.4f);
            bar.setAccessibleName("Upload");
            Column root = new Column();
            root.add(new SizedBox(200, 12, bar));
            return root;
        }

        @Override
        public Widget widget() {
            return bar;
        }

        @Override
        public double value() {
            return Math.round(bar.progress() * 100f);
        }

        @Override
        public double min() {
            return 0;
        }

        @Override
        public double max() {
            return 100;
        }

        @Override
        public double step() {
            return 0;
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        @Override
        public void set(double value) {
            bar.setProgress((float) (value / 100));
        }

        @Override
        public int changes() {
            return 0;
        }
    }
}
