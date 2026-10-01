package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.SelectionFacet;
import limn.accessibility.ValueFacet;
import limn.i18n.I18n;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a screen reader is told of a combo holding many (ADR 052 §4), and of a combo with a
 * placeholder: the field's value is the count and every name, read-only; an editable one's field
 * says it as its description; the list is multiselectable and not required, an option's selection
 * is whether it is checked, and a reader's verbs toggle, replace and move the cursor.
 */
class MultiComboBoxAccessibilityTest extends AccessibleComponentTestBase {

    private static final List<String> STATUSES = List.of("Open", "Invoiced", "Shipped");

    private ComboBox<String> combo;
    private int handled;

    @AfterEach
    void english() {
        I18n.setLocale(Locale.ENGLISH);
    }

    private void build(boolean editable) {
        I18n.setLocale(Locale.ENGLISH);
        combo = new ComboBox<>(STATUSES).setDisplayMode(DisplayMode.IN_SCENE)
                .setSelectionMode(SelectionMode.MULTI).setEditable(editable);
        combo.onSelect(() -> handled++);
        Column root = new Column();
        root.add(combo);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
    }

    private AccessibleNode field() {
        return node(Accessible.Role.COMBO_BOX);
    }

    private List<AccessibleNode> options() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(node(Accessible.Role.LIST))) {
            if (child.role() == Accessible.Role.LIST_ITEM) {
                found.add(child);
            }
        }
        return found;
    }

    @Test
    void theFieldsValueIsTheCountAndEveryNameAndCannotBeSet() throws InterruptedException {
        build(false);
        ValueFacet none = field().value();
        assertNotNull(none, describe(tree()));
        assertEquals("None selected", none.text());
        combo.setSelectedIndices(0, 2);
        frame();
        ValueFacet value = field().value();
        assertEquals("2 selected: Open, Shipped", value.text());
        assertEquals(2, value.value());
        assertTrue(value.readOnly(), "a selection of many is not one value to set");
        perform(field().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfText("Invoiced"));
        frame();
        assertEquals(List.of("Open", "Shipped"), combo.selectedItems(), "a set is refused");
    }

    @Test
    void theSummaryFollowsTheLanguage() {
        build(false);
        combo.setSelectedIndices(0, 1);
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        combo.invalidate();
        frame();
        assertEquals("2 selecionados: Open, Invoiced", field().value().text());
    }

    @Test
    void theListIsMultiselectableAndAnOptionsSelectionIsItsCheck() {
        build(false);
        combo.setSelectedIndices(1);
        combo.open();
        frame();
        SelectionFacet selection = node(Accessible.Role.LIST).selection();
        assertNotNull(selection);
        assertTrue(selection.multiSelectable());
        assertFalse(selection.required(), "a selection of many may be empty");
        List<AccessibleNode> options = options();
        assertEquals(List.of(false, true, false),
                options.stream().map(o -> o.selectionItem().selected()).toList(), describe(tree()));
        assertTrue(options.get(0).actions().actions().contains(Accessible.Action.ADD_TO_SELECTION));
        assertTrue(options.get(1).actions().actions().contains(Accessible.Action.DESELECT));
        assertTrue(options.get(0).actions().actions().contains(Accessible.Action.FOCUS),
                "the cursor is apart from the selection");
    }

    @Test
    void aReadersVerbsToggleReplaceAndMoveTheCursor() throws InterruptedException {
        build(false);
        combo.open();
        frame();
        assertTrue(perform(options().get(0).id(), Accessible.Action.ADD_TO_SELECTION,
                Accessible.Argument.NONE));
        frame();
        assertTrue(perform(options().get(2).id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("Open", "Shipped"), combo.selectedItems());
        assertTrue(combo.isOpen(), "a toggle leaves the list down");
        assertTrue(perform(options().get(0).id(), Accessible.Action.DESELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("Shipped"), combo.selectedItems());
        assertTrue(perform(options().get(1).id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();
        assertEquals(List.of("Invoiced"), combo.selectedItems(), "SELECT makes it the whole selection");
        assertTrue(perform(options().get(2).id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        frame();
        assertEquals(2, combo.highlightedIndex());
        assertEquals(4, handled, "every verb that moved the selection is the user's");
    }

    @Test
    void anEditableOnesFieldTellsTheSelectionAsItsDescription() {
        build(true);
        combo.setSelectedIndices(0, 1);
        frame();
        AccessibleNode field = node(Accessible.Role.TEXT_FIELD);
        assertEquals("2 selected: Open, Invoiced", field.description(), describe(tree()));
    }

    @Test
    void aPlaceholderNamesTheComboAndShowsWhileItReadsNothing() {
        build(false);
        combo.setPlaceholder("Filter by status");
        frame();
        assertEquals("Filter by status", field().name());
        assertEquals(Accessible.NameFrom.PLACEHOLDER, field().nameFrom());

        ComboBox<String> single = new ComboBox<>(STATUSES).setEditable(true)
                .setPlaceholder("Choose a status");
        TextField editor = (TextField) single.children().get(0);
        assertEquals("Choose a status", editor.placeholder(), "an editable one's field shows it");
    }
}
