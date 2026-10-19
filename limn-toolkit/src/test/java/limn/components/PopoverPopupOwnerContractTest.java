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
 * A button a {@link Popover} is attached to, under the popup-owner contract (ADR 045 §4): the
 * button says it opens a popup and whether it is open, offers expand or collapse by state in place
 * of press, and the popover's panel is what appears.
 */
class PopoverPopupOwnerContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> thePopupOwnerContract() {
        return ContractTests.of(PopupOwnerContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements PopupOwnerSubject {
        private Button button;
        private Popover popover;

        @Override
        public Widget<?> build() {
            button = new Button("Filter");
            Column fields = new Column();
            fields.add(new TextField());
            fields.add(new Checkbox("Overdue only"));
            popover = new Popover(fields).setAccessibleName("Filter orders").attachTo(button);
            Column root = new Column();
            root.add(button);
            root.add(new SizedBox(300, 200, new Label("Orders")));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return button;
        }

        @Override
        public boolean isOpen() {
            return popover.isOpen();
        }

        @Override
        public void close() {
            popover.close();
        }
    }
}
