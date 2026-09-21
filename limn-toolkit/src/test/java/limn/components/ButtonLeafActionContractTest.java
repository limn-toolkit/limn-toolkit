package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.LeafActionContract;
import limn.testing.a11y.LeafActionSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link Button} under the leaf contract (ADR 045 §4). */
class ButtonLeafActionContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theLeafContract() {
        return ContractTests.of(LeafActionContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements LeafActionSubject {
        private Button button;
        private int presses;

        @Override
        public Widget build() {
            button = new Button("Save");
            presses = 0;
            button.onAction(() -> presses++);
            Column root = new Column();
            root.add(button);
            return root;
        }

        @Override
        public Widget widget() {
            return button;
        }

        @Override
        public int presses() {
            return presses;
        }
    }
}
