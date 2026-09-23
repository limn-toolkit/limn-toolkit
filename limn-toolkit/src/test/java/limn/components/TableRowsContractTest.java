package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.i18n.I18n;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@link Table} under the rows contract (ADR 045 §4): the variant whose rows are synthetic
 * children the container describes and performs for, with a cursor that is a cell under the
 * row, a multiple selection to enter, and rows it scrolls itself. Ten rows in a box that holds a
 * header and a few of them, so that the rest are realized as the box scrolls and not before.
 */
class TableRowsContractTest extends ComponentTestBase {

    private Locale before;

    @BeforeEach
    void pinTheLanguage() {
        before = I18n.processLocale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void releaseTheLanguage() {
        I18n.setLocale(before);
    }

    @TestFactory
    Stream<DynamicTest> theRowsContract() {
        return ContractTests.of(RowsContract.cases(new Subject(), runtime));
    }

    private record Person(String name, int age) {
    }

    private static final class Subject implements RowsSubject {
        private static final int ROWS = 10;
        private static final List<String> NAMES = names();

        private Table<Person> table;
        private int activated = -1;

        private static List<String> names() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < ROWS; i++) {
                out.add("Person " + i + " " + (20 + i));
            }
            return List.copyOf(out);
        }

        @Override
        public Widget<?> build() {
            List<Person> people = new ArrayList<>();
            for (int i = 0; i < ROWS; i++) {
                people.add(new Person("Person " + i, 20 + i));
            }
            table = new Table<>(List.of(
                    Column.text("Name", Person::name).width(120),
                    Column.numeric("Age", Person::age).width(60)));
            table.setRows(people);
            table.setAccessibleName("People");
            activated = -1;
            table.onActivate(model -> activated = model);
            limn.scene.layout.Column root = new limn.scene.layout.Column();
            root.add(new SizedBox(300, 160, table));
            return root;
        }

        @Override
        public Widget<?> widget() {
            return table;
        }

        @Override
        public List<String> rowNames() {
            return NAMES;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return false;
        }

        @Override
        public Scrolling scrolling() {
            return Scrolling.ITSELF;
        }

        @Override
        public boolean rowsActivate() {
            return true;
        }

        @Override
        public boolean enterMultipleSelection() {
            table.setSelectionMode(SelectionMode.MULTI);
            return true;
        }

        @Override
        public void select(int row) {
            table.setSelectedRow(row);
        }

        @Override
        public List<Integer> selectedRows() {
            List<Integer> rows = new ArrayList<>();
            for (int model : table.selectedRows()) {
                rows.add(model);
            }
            rows.sort(null);
            return rows;
        }

        @Override
        public int cursorRow() {
            return table.focusRow();
        }

        @Override
        public int lastActivated() {
            return activated;
        }
    }
}
