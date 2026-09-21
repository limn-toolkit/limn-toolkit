package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.ToggleContract;
import limn.testing.a11y.ToggleSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** {@link Checkbox} under the toggle contract (ADR 045 §4), in its box and switch variants. */
class CheckboxToggleContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theBox() {
        return ContractTests.of(ToggleContract.cases(new Subject(Checkbox.Variant.BOX), runtime));
    }

    @TestFactory
    Stream<DynamicTest> theSwitch() {
        return ContractTests.of(ToggleContract.cases(new Subject(Checkbox.Variant.SWITCH),
                runtime));
    }

    private static final class Subject implements ToggleSubject {
        private final Checkbox.Variant variant;
        private Checkbox box;
        private int changes;

        Subject(Checkbox.Variant variant) {
            this.variant = variant;
        }

        @Override
        public Widget build() {
            box = new Checkbox(variant, "Send me a copy");
            changes = 0;
            box.onChange(on -> changes++);
            Column root = new Column();
            root.add(box);
            return root;
        }

        @Override
        public Widget widget() {
            return box;
        }

        @Override
        public boolean isOn() {
            return box.isChecked();
        }

        @Override
        public boolean operable() {
            return true;
        }

        @Override
        public int changes() {
            return changes;
        }
    }
}
