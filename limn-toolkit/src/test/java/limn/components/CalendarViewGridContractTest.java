package limn.components;

import limn.accessibility.CellFacet;
import limn.components.date.CalendarView;
import limn.i18n.I18n;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.GridContract;
import limn.testing.a11y.GridSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@link CalendarView}'s grid under the grid contract (ADR 045 §4; decision 99): seven weekday
 * headers that sort nothing, six week rows of day cells, and a cursor that is a day. September
 * 2024 starting on a Sunday, as the rows subject has it, so the cursor's cell is where its
 * date says.
 */
class CalendarViewGridContractTest extends ComponentTestBase {

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

    private static final class Subject implements GridSubject {
        private CalendarView calendar;
        private LocalDate firstCell;

        @Override
        public Widget build() {
            calendar = new CalendarView();
            calendar.setFirstDayOfWeek(DayOfWeek.SUNDAY);
            calendar.setVisibleMonth(LocalDate.of(2024, 9, 1));
            calendar.setAccessibleName("Calendar");
            LocalDate first = calendar.visibleMonth();
            firstCell = first.minusDays(first.getDayOfWeek().getValue() % 7);
            Column root = new Column();
            root.add(calendar);
            return root;
        }

        @Override
        public Widget widget() {
            return calendar;
        }

        @Override
        public List<String> columnNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                names.add(DayOfWeek.SUNDAY.plus(i).getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            }
            return names;
        }

        @Override
        public boolean isSortable(int column) {
            return false;
        }

        @Override
        public CellFacet.Sort sortOf(int column) {
            return CellFacet.Sort.NONE;
        }

        @Override
        public int[] cursor() {
            LocalDate day = calendar.focusedDate();
            if (day == null) {
                return null;
            }
            int index = (int) (day.toEpochDay() - firstCell.toEpochDay());
            return new int[] {index / 7, index % 7};
        }
    }
}
