package limn.components;

import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@link TabbedPane}'s strip under the rows contract (ADR 045 §4): the variant whose rows are
 * widgets of their own that hold the keyboard themselves (roving focus), so that the cursor is
 * the selection and {@code FOCUS} and {@code SCROLL_INTO_VIEW} are the walk's free verbs on
 * them; a press dives into the panel. Four tabs that fit their box, so nothing scrolls.
 */
class TabbedPaneRowsContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements RowsSubject {
        private static final List<String> NAMES = List.of("Alpha", "Beta", "Gamma", "Delta");

        private TabbedPane pane;
        private int activated = -1;

        @Override
        public Widget<?> build() {
            pane = new TabbedPane();
            for (String name : NAMES) {
                pane.addTab(name, new SizedBox(60, 60));
            }
            activated = -1;
            pane.onSelect(index -> activated = index);
            Column root = new Column();
            root.add(new SizedBox(360, 120, pane));
            root.setAccessibleName("Settings");
            return root;
        }

        @Override
        public Widget<?> widget() {
            return pane;
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
            return true; // a press selects the tab and dives into its panel
        }

        @Override
        public boolean enterMultipleSelection() {
            return false;
        }

        @Override
        public void select(int row) {
            pane.setSelectedIndex(row);
        }

        @Override
        public List<Integer> selectedRows() {
            return pane.selectedIndex() < 0 ? List.of() : List.of(pane.selectedIndex());
        }

        @Override
        public int cursorRow() {
            return pane.selectedIndex();
        }

        @Override
        public int lastActivated() {
            return activated;
        }
    }
}
