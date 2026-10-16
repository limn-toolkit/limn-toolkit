package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.PopupOwnerContract;
import limn.testing.a11y.PopupOwnerSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/**
 * {@link Expander}'s header under the popup-owner contract (ADR 045 §4): the header is the node
 * that says {@code HAS_POPUP} and carries the expand facet, and the section it opens is the
 * expander's own content.
 */
class ExpanderPopupOwnerContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> thePopupOwnerContract() {
        return ContractTests.of(PopupOwnerContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements PopupOwnerSubject {
        private Expander expander;

        @Override
        public Widget<?> build() {
            expander = new Expander("Delivery", new Button("Change address"));
            Column root = new Column();
            root.add(new SizedBox(240, 200, expander));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return expander;
        }

        @Override
        public boolean isOpen() {
            return expander.isExpanded();
        }

        @Override
        public void close() {
            expander.setExpanded(false);
        }

        @Override
        public boolean disclosesInPlace() {
            return true;
        }
    }
}
