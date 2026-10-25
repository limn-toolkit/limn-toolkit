package limn.components;

import limn.components.chart.BarChart;
import limn.components.table.Column;
import limn.components.table.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Each setter whose {@code null} takes something away, overloaded so that {@code null} fits two of
 * its forms and does not compile, has a typed {@code clear…()} that does what the null would.
 */
class ClearMethodsTest extends ComponentTestBase {

    @Test
    void eachClearTakesAwayWhatItsSetterGave() {
        Card card = new Card(new Label("x")).setTitle("Title").setSubtitle("Sub").setMedia(new Label("m"));
        card.clearTitle().clearSubtitle().clearMedia();
        assertNull(card.title());
        assertNull(card.subtitle());
        assertNull(card.media());

        Expander expander = new Expander("Head", new Label("x")).setSummary("2 items");
        assertNull(expander.clearSummary().summary());

        Field field = new Field("Name", new TextField()).setHelperText("help");
        field.clearLabel().clearHelperText();
        assertNull(field.label());
        assertNull(field.helperText());

        BarChart chart = new BarChart();
        chart.setTitle("Sales");
        assertNull(chart.clearTitle().title());

        Column<String> column = Column.<String>text("Name", s -> s).footer("Total");
        assertFalse(column.clearFooter().hasFooter());

        // No getter to read these back; that they compile and run is the point.
        new ListView<String>(Label::new).setEmptyMessage("none").clearEmptyMessage();
        new Table<>(List.of(Column.<String>text("A", s -> s))).setEmptyMessage("none").clearEmptyMessage();
        new Popover(new Label("x")).setAccessibleName("Filter").clearAccessibleName();
    }
}
