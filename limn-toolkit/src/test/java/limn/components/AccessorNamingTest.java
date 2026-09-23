package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every setter has a getter that reads back what it wrote, and a boolean getter is named
 * {@code isX} like the rest of the toolkit's.
 */
class AccessorNamingTest extends ComponentTestBase {

    @Test
    void aCheckboxWithoutAVariantIsABox() {
        assertEquals(Checkbox.Variant.BOX, new Checkbox("Bold").variant());
    }

    @Test
    void aSliderReadsItsStepBack() {
        Slider slider = new Slider(0, 100);
        assertEquals(0, slider.step());
        assertEquals(5, slider.setStep(5).step());
    }

    @Test
    void aTableReadsItsLooksBack() {
        Table<String> table = new Table<>(List.of(Column.text("name", (String s) -> s)));
        assertTrue(table.isStriped());
        assertTrue(table.isShowHeader());
        assertEquals(ScrollBar.Policy.AUTO, table.scrollbarPolicy());

        table.setStriped(false).setShowHeader(false).setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        assertFalse(table.isStriped());
        assertFalse(table.isShowHeader());
        assertEquals(ScrollBar.Policy.ALWAYS, table.scrollbarPolicy());
    }

    @Test
    void booleanGettersAreNamedIs() {
        assertFalse(new TextArea().setSoftWrap(false).isSoftWrap());
        assertFalse(new Spinner(0, 10, 1).setSnapToStep(false).isSnapToStep());
    }
}
