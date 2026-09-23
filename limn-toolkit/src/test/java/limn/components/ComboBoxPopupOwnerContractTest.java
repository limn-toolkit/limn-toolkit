package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.PopupOwnerContract;
import limn.testing.a11y.PopupOwnerSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@link ComboBox} under the popup-owner contract (ADR 045 §4): in the scene presentation, where
 * the open list is an overlay that owns the input and the field beneath it offers nothing until
 * it closes (ADR 039 §1.13).
 */
class ComboBoxPopupOwnerContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> thePopupOwnerContract() {
        return ContractTests.of(PopupOwnerContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements PopupOwnerSubject {
        private ComboBox combo;

        @Override
        public Widget<?> build() {
            combo = new ComboBox(List.of("English", "Portuguese", "French"));
            combo.setAccessibleName("Language");
            Column root = new Column();
            root.add(new SizedBox(200, 32, combo));
            root.add(new SizedBox(200, 200));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return combo;
        }

        @Override
        public boolean isOpen() {
            return combo.isOpen();
        }

        @Override
        public void close() {
            combo.close();
        }
    }
}
