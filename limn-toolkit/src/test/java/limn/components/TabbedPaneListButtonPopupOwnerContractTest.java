package limn.components;

import limn.input.Keys;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.PopupOwnerContract;
import limn.testing.a11y.PopupOwnerSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static limn.testing.SceneDriver.drive;

/**
 * The button of a {@link TabbedPane} whose tabs overflow its strip, under the popup-owner contract
 * (ADR 045 §4): it opens the list of every tab, drawn in the scene on a window that cannot place
 * one of its own.
 */
class TabbedPaneListButtonPopupOwnerContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> thePopupOwnerContract() {
        return ContractTests.of(PopupOwnerContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements PopupOwnerSubject {
        private TabbedPane pane;

        @Override
        public Widget<?> build() {
            pane = new TabbedPane();
            for (String title : new String[] {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"}) {
                pane.addTab(title, new SizedBox(60, 60));
            }
            Column root = new Column();
            root.add(new SizedBox(120, 220, pane)); // narrower than six tabs: the strip overflows
            return root;
        }

        @Override
        public Widget<?> widget() {
            return pane;
        }

        @Override
        public boolean isOpen() {
            return pane.isTabListOpen();
        }

        /** The pane has no method that closes its list: Escape does, as the user would. */
        @Override
        public void close() {
            if (pane.isTabListOpen()) {
                drive(pane.scene()).press(Keys.ESCAPE, 0);
            }
        }
    }
}
