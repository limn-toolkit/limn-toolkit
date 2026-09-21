package limn.components;

import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link ListView} under the rows contract (ADR 045 §4): the variant where the cursor is the
 * selection, with one selectable row, no multiple selection, and rows it scrolls itself. Ten
 * rows of ten points in a box of forty-five, so that the fifth row is half out and the rest
 * are not realized at all.
 */
class ListViewRowsContractTest extends ComponentTestBase {

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private static final class Subject implements RowsSubject {
        private static final int ROWS = 10;
        private static final float ROW_H = 10;
        private static final List<String> NAMES = names();

        private ListView list;
        private int activated = -1;

        private static List<String> names() {
            String[] out = new String[ROWS];
            for (int i = 0; i < ROWS; i++) {
                out[i] = "Row " + i;
            }
            return List.of(out);
        }

        @Override
        public Widget build() {
            list = new ListView(new Rows());
            list.setAccessibleName("Rows");
            activated = -1;
            list.onActivate(index -> activated = index);
            Column root = new Column();
            root.add(new SizedBox(300, 45, list));
            return root;
        }

        @Override
        public Widget widget() {
            return list;
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
            return Scrolling.ITSELF;
        }

        @Override
        public boolean enterMultipleSelection() {
            return false;
        }

        @Override
        public void select(int row) {
            list.setSelectedIndex(row);
        }

        @Override
        public List<Integer> selectedRows() {
            return list.selectedIndex() < 0 ? List.of() : List.of(list.selectedIndex());
        }

        @Override
        public int cursorRow() {
            return list.selectedIndex();
        }

        @Override
        public int lastActivated() {
            return activated;
        }

        /** A plain cell of a fixed height that cannot take the keyboard. */
        private static final class Cell extends Widget {
            @Override
            protected Size onMeasure(Constraints constraints) {
                return constraints.constrain(constraints.maxWidth(), ROW_H);
            }
        }

        private static final class Rows implements ListView.Adapter {
            private final Deque<Widget> pool = new ArrayDeque<>();

            @Override
            public int rowCount() {
                return ROWS;
            }

            @Override
            public Widget rowAt(int index) {
                return pool.isEmpty() ? new Cell() : pool.pop();
            }

            @Override
            public void recycle(Widget widget) {
                pool.push(widget);
            }

            @Override
            public I18nString rowName(int index) {
                return I18nString.literal(NAMES.get(index));
            }
        }
    }
}
