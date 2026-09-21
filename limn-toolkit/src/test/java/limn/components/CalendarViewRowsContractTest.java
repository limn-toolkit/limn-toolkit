package limn.components;

import limn.components.date.CalendarView;
import limn.i18n.I18n;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.a11y.RowsContract;
import limn.testing.a11y.RowsSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@link CalendarView}'s day cells under the rows contract (ADR 045 §4): the variant whose
 * members are a grid's cells, with a cursor separate from the selection, no activation of
 * their own. September 2024, far from today, so that no cell is named as today, and a month
 * that starts on the first day of its week, so that the first rows the contract addresses are
 * days of the month shown: picking a leading day of the month before pages the grid to that
 * month, and the members the contract mapped by name are then another month's. At its natural
 * size: inside a scroll view the calendar's week rows and day cells beyond the pane are
 * published SHOWING, which the invariants refuse (ADR 045 §8), so the reveal case of decision 81
 * waits on that and the subject declares that nothing scrolls. The forty-two cell names are
 * derived from the month the widget shows and its first day of week, not guessed.
 */
class CalendarViewRowsContractTest extends ComponentTestBase {

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

    private static final class Subject implements RowsSubject {
        private static final int CELLS = 42;
        private static final DateTimeFormatter NAME =
                DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);

        private CalendarView calendar;
        private LocalDate firstCell;
        private List<String> names;

        private LocalDate dayAt(int row) {
            return firstCell.plusDays(row);
        }

        private int rowOf(LocalDate day) {
            return day == null ? -1 : (int) (day.toEpochDay() - firstCell.toEpochDay());
        }

        @Override
        public Widget build() {
            calendar = new CalendarView();
            calendar.setFirstDayOfWeek(DayOfWeek.SUNDAY);
            calendar.setVisibleMonth(LocalDate.of(2024, 9, 1));
            calendar.setAccessibleName("Calendar");
            LocalDate first = calendar.visibleMonth();
            firstCell = first.minusDays(first.getDayOfWeek().getValue() % 7);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < CELLS; i++) {
                out.add(NAME.format(firstCell.plusDays(i)));
            }
            names = List.copyOf(out);
            Column root = new Column();
            root.add(calendar);
            return root;
        }

        @Override
        public Widget widget() {
            return calendar;
        }

        @Override
        public List<String> rowNames() {
            return names;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return false;
        }

        @Override
        public Scrolling scrolling() {
            return Scrolling.NONE; // see the class comment: the scroll view case is owed
        }

        @Override
        public boolean rowsActivate() {
            return false; // a day is picked, and has no activation apart from that
        }

        @Override
        public boolean enterMultipleSelection() {
            return false; // a range is a band, not a set to add to
        }

        @Override
        public void select(int row) {
            calendar.setSelectedDate(dayAt(row));
        }

        @Override
        public List<Integer> selectedRows() {
            LocalDate day = calendar.selectedDate();
            return day == null ? List.of() : List.of(rowOf(day));
        }

        @Override
        public int cursorRow() {
            return rowOf(calendar.focusedDate());
        }

        @Override
        public int lastActivated() {
            return -1;
        }
    }
}
