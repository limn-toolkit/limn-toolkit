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
 * {@link ScrollBar} under the value contract (ADR 045 §4): a thousand points of content in a
 * viewport of two hundred, so the value runs from 0 to 800 and one step is a viewport. The bar
 * has no listener of its own; what it does reaches the host's model, which counts.
 */
class ScrollBarValueContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theValueContract() {
        return ContractTests.of(ValueContract.cases(new Subject(), runtime));
    }

    private static final class Model implements ScrollBar.Model {
        float offset = 100;
        int sets;

        @Override
        public float contentLength() {
            return 1000;
        }

        @Override
        public float viewportLength() {
            return 200;
        }

        @Override
        public float offset() {
            return offset;
        }

        @Override
        public void setOffset(float value) {
            offset = value;
            sets++;
        }
    }

    private static final class Subject implements ValueSubject {
        private ScrollBar bar;
        private Model model;

        @Override
        public Widget build() {
            model = new Model();
            bar = new ScrollBar(ScrollBar.Orientation.VERTICAL, model);
            Column root = new Column();
            root.add(new SizedBox(20, 200, bar));
            return root;
        }

        @Override
        public Widget widget() {
            return bar;
        }

        @Override
        public double value() {
            return model.offset();
        }

        @Override
        public double min() {
            return 0;
        }

        @Override
        public double max() {
            return 800;
        }

        @Override
        public double step() {
            return 200;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public void set(double value) {
            model.setOffset((float) value);
            bar.invalidate();
        }

        @Override
        public int changes() {
            return model.sets;
        }
    }
}
