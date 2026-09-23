package limn.components;

import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.graphics.Icon;
import limn.scene.Change;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five places a watcher heard the wrong thing or nothing (API-12): what each change now says,
 * and with which origin.
 */
class WatcherContractTest extends ComponentTestBase {

    private static List<String> watch(Widget<?> widget) {
        List<String> heard = new ArrayList<>();
        widget.observeChanges((w, change) -> heard.add(change.aspect() + "/" + change.origin()));
        return heard;
    }

    @Test
    void aRadioDisplacedByAPreselectedNewcomerSaysSo() {
        ButtonGroup group = new ButtonGroup();
        RadioButton first = new RadioButton("First");
        group.add(first);
        group.setSelectedIndex(0);
        List<String> heard = watch(first);
        RadioButton second = new RadioButton("Second");
        second.select(); // selected before it joins, standalone
        group.add(second);
        assertTrue(!first.isSelected() && second.isSelected());
        assertEquals(List.of("VALUE/ADJUSTMENT"), heard.stream().filter(h -> h.startsWith("VALUE")).toList(),
                "the group moved it; it says so, as the group's: " + heard);
    }

    @Test
    void aValueTheGranularityTrimmedIsAnAdjustment() {
        DateField field = new DateField().setDate(LocalDate.of(2026, 9, 23));
        List<String> heard = watch(field);
        field.setGranularity(DateField.Granularity.MONTH);
        assertTrue(heard.contains("VALUE/ADJUSTMENT"), heard.toString());
        assertTrue(heard.stream().noneMatch(h -> h.equals("VALUE/CODE")), heard.toString());
    }

    @Test
    void aMonthTheClockMovedIsAnAdjustment() {
        CalendarView calendar = new CalendarView();
        List<String> heard = watch(calendar);
        calendar.setClock(Clock.fixed(Instant.parse("2031-02-10T12:00:00Z"), ZoneOffset.UTC));
        assertEquals(List.of("VALUE/ADJUSTMENT"), heard);
    }

    @Test
    void theSameFilterAgainIsNoChange() {
        CalendarView calendar = new CalendarView();
        Predicate<LocalDate> weekdays = day -> day.getDayOfWeek().getValue() < 6;
        calendar.setDateFilter(weekdays);
        List<String> heard = watch(calendar);
        calendar.setDateFilter(weekdays);
        assertEquals(List.of(), heard);
        calendar.setDateFilter(day -> true);
        assertEquals(List.of("RANGE/CODE"), heard, "a different filter is announced");
    }

    @Test
    void aNameSetFromCodeIsHeardOnceAndOnlyWhenItMoves() {
        Button button = new Button("");
        List<String> heard = watch(button);
        button.setAccessibleName("Save");
        button.setAccessibleName("Save");
        assertEquals(List.of("NAME/CODE"), heard);
    }

    @Test
    void replacingTheTrailingButtonIsAnnounced() {
        TextField field = new TextField();
        List<String> heard = watch(field);
        Icon glyph = limn.graphics.SvgIcon.fromResource("/limn/components/icons/search.svg");
        field.setTrailingButton(glyph, limn.i18n.I18nString.literal("Search"), () -> { });
        field.setTrailingButton(glyph, limn.i18n.I18nString.literal("Clear"), () -> { });
        assertEquals(List.of("CHILDREN/CODE", "CHILDREN/CODE"), heard);
    }
}
