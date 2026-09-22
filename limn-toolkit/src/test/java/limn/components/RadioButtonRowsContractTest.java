package limn.components;

import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link ButtonGroup} of {@link RadioButton}s under the rows contract (ADR 045 §4; decision
 * 107): the variant whose members are widgets with no container node, whose cursor is the
 * selection (the group's one tab stop moves with it), which announce the one change as
 * {@code VALUE} on each member that moved, and which neither scroll nor activate.
 */
class RadioButtonRowsContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements RowsSubject {
        private static final List<String> NAMES = List.of("Small", "Medium", "Large", "Huge");
        private final List<RadioButton> radios = new ArrayList<>();
        private ButtonGroup group;

        @Override
        public Widget build() {
            radios.clear();
            group = new ButtonGroup();
            Column root = new Column();
            for (String name : NAMES) {
                RadioButton radio = new RadioButton(name);
                group.add(radio);
                radios.add(radio);
                root.add(radio);
            }
            return root;
        }

        @Override
        public Widget widget() {
            int selected = group.selectedIndex();
            return radios.get(selected < 0 ? 0 : selected);
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
        public boolean containerless() {
            return true;
        }

        @Override
        public Change.Aspect selectionAspect() {
            return Change.Aspect.VALUE;
        }

        @Override
        public void select(int row) {
            radios.get(row).select();
        }

        @Override
        public List<Integer> selectedRows() {
            int selected = group.selectedIndex();
            return selected < 0 ? List.of() : List.of(selected);
        }

        @Override
        public int cursorRow() {
            for (int i = 0; i < radios.size(); i++) {
                if (radios.get(i).isFocused()) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int lastActivated() {
            return -1;
        }
    }
}
