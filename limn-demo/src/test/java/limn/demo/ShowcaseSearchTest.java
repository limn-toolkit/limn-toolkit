package limn.demo;

import limn.components.Label;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search over the Showcase's navigation finds a page by its name or by a component on it,
 * however the component is spelled: "checkbox", "check box" and "CheckBox" are one word.
 */
class ShowcaseSearchTest {

    private static List<String> found(String query) {
        String folded = AppScene.fold(query);
        List<String> pages = new ArrayList<>();
        for (AppScene.Place top : AppScene.places(new Label(""), () -> null)) {
            List<AppScene.Place> all = top.under.isEmpty() ? List.of(top) : top.under;
            for (AppScene.Place place : all) {
                if (place.matches(folded) || top.matches(folded)) {
                    pages.add(place.id());
                }
            }
        }
        return pages;
    }

    @Test
    void foldingMakesSpellingsOneWord() {
        assertEquals("checkbox", AppScene.fold("Check-box"));
        assertEquals("checkbox", AppScene.fold("check box"));
        assertEquals("secao", AppScene.fold("Seção"));
    }

    @Test
    void aComponentFindsThePageItIsOn() {
        assertEquals(List.of("Buttons and inputs"), found("checkbox"));
        assertEquals(List.of("Buttons and inputs"), found("Check box"));
        assertEquals(List.of("Dates"), found("DatePicker"));
        assertEquals(List.of("Split panes"), found("splitpane"));
        assertTrue(found("ComboBox").contains("Combo boxes"));
        assertTrue(found("table").contains("Tables"));
    }

    @Test
    void aPageIsFoundByItsNameAndAHeadingKeepsItsPages() {
        assertEquals(List.of("Tray icon"), found("tray"));
        assertTrue(found("media").containsAll(List.of("Video", "Sound", "Sprites")));
    }

    @Test
    void nothingIsFoundForAWordNoPageHas() {
        assertEquals(List.of(), found("zzqx"));
    }
}
