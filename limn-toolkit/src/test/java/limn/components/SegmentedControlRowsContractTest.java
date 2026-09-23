package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@link SegmentedControl} under the rows contract (ADR 045 §4): the variant whose rows are
 * synthetic children, where the cursor is the selection, nothing activates and nothing scrolls.
 */
class SegmentedControlRowsContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements RowsSubject {
        private static final List<String> NAMES = List.of("Day", "Week", "Month", "Year");

        private SegmentedControl control;

        @Override
        public Widget<?> build() {
            control = new SegmentedControl(NAMES);
            control.setAccessibleName("Period");
            Column root = new Column();
            root.crossAlignment(Flex.CrossAlignment.STRETCH);
            root.add(control);
            return root;
        }

        @Override
        public Widget<?> widget() {
            return control;
        }

        @Override
        public List<String> rowNames() {
            return NAMES;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return true;
        }

        @Override
        public Scrolling scrolling() {
            return Scrolling.NONE;
        }

        @Override
        public boolean rowsActivate() {
            return false;
        }

        @Override
        public boolean enterMultipleSelection() {
            return false;
        }

        @Override
        public void select(int row) {
            control.setSelectedIndex(row);
        }

        @Override
        public List<Integer> selectedRows() {
            return control.selectedIndex() < 0 ? List.of() : List.of(control.selectedIndex());
        }

        @Override
        public int cursorRow() {
            return control.selectedIndex();
        }

        @Override
        public int lastActivated() {
            return -1;
        }
    }
}
