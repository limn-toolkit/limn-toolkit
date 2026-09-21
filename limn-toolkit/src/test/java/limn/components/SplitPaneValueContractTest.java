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
 * {@link SplitPane}'s divider under the value contract (ADR 045 §4): the value is the first
 * pane's extent in points, whose bounds and step are the widget's own, so the subject leaves
 * them to the facet and reads the number back off the laid-out pane.
 */
class SplitPaneValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ContractTests.of(ValueContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements ValueSubject {
        private SplitPane split;
        private int changes;

        @Override
        public Widget build() {
            split = SplitPane.horizontal(new SizedBox(80, 40), new SizedBox(80, 40));
            split.setDividerFocusable(true);
            changes = 0;
            split.onRatioChange(ratio -> changes++);
            Column root = new Column();
            root.add(new SizedBox(300, 60, split));
            return root;
        }

        @Override
        public Widget widget() {
            return split;
        }

        @Override
        public double value() {
            return split.children().get(0).width();
        }

        @Override
        public double min() {
            return Double.NaN;
        }

        @Override
        public double max() {
            return Double.NaN;
        }

        @Override
        public double step() {
            return Double.NaN;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public void set(double value) {
            split.setRatio((float) (value / split.width()));
        }

        @Override
        public int changes() {
            return changes;
        }
    }
}
