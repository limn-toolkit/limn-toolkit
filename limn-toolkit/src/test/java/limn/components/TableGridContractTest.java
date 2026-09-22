package limn.components;

import limn.accessibility.CellFacet;
import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.i18n.I18n;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import limn.testing.a11y.GridContract;
import limn.testing.a11y.GridSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@link Table}'s grid under the grid contract (ADR 045 §4; decision 99): a header of three
 * columns, two of which sort, ten rows of cells that take the cursor, and a footer left out so
 * that every cell below the header is a data cell.
 */
class TableGridContractTest extends ComponentTestBase {

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
    Stream<DynamicTest> theGridContract() {
        return ContractTests.of(GridContract.cases(new Subject(), runtime));
    }

    private record Person(String name, String city, int age) {
    }

    private static final class Subject implements GridSubject {
        private static final List<String> COLUMNS = List.of("Name", "City", "Age");
        private final List<Column<Person>> columns = new ArrayList<>();
        private Table<Person> table;

        @Override
        public Widget build() {
            List<Person> people = new ArrayList<>();
            String[] cities = {"Lima", "Quito", "Bogotá", "Sucre", "Caracas"};
            for (int i = 0; i < 10; i++) {
                people.add(new Person("Person " + i, cities[i % cities.length], 20 + (i * 7) % 11));
            }
            columns.clear();
            columns.add(Column.text("Name", Person::name).width(120).sortable(true));
            columns.add(Column.text("City", Person::city).width(90));
            columns.add(Column.numeric("Age", Person::age).width(60).sortable(true));
            table = new Table<>(columns);
            table.setRows(people);
            table.setAccessibleName("People");
            limn.scene.layout.Column root = new limn.scene.layout.Column();
            root.add(new SizedBox(300, 160, table));
            return root;
        }

        @Override
        public Widget widget() {
            return table;
        }

        @Override
        public List<String> columnNames() {
            return COLUMNS;
        }

        @Override
        public boolean isSortable(int column) {
            return columns.get(column).isSortable();
        }

        @Override
        public CellFacet.Sort sortOf(int column) {
            if (table.sortColumn() != columns.get(column) || table.sortOrder() == SortOrder.NONE) {
                return CellFacet.Sort.NONE;
            }
            return table.sortOrder() == SortOrder.ASCENDING
                    ? CellFacet.Sort.ASCENDING : CellFacet.Sort.DESCENDING;
        }

        @Override
        public int[] cursor() {
            return table.focusRow() < 0 ? null : new int[] {table.focusRow(), table.focusColumn()};
        }
    }
}
