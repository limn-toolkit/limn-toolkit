package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.internal.AccessibilityAccess;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A combo box that is not editable is named on AT-SPI by the value it shows, its caption reached
 * through labelled-by, as GTK 3's and Qt's are (read on the Fedora 44 guest, 2026-09-30): Orca 50.2
 * speaks a focused combo's label and name and never its value, so a box named by its caption was
 * never heard holding anything. An editable one keeps its name: its text field says the text.
 */
class AtspiComboNameTest {

    private static AccessibleTree tree(boolean editable, String shown) {
        Accessibility a = new Accessibility();
        AccessibilityAccess.beginWalk(a, 400, 300, Locale.ENGLISH);
        long window = AccessibilityAccess.mint(a);
        long combo = AccessibilityAccess.mint(a);
        long field = AccessibilityAccess.mint(a);
        AccessibilityAccess.begin(a, window, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("Gallery"), Accessible.NameFrom.EXPLICIT);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        AccessibilityAccess.begin(a, combo, 0, Locale.ENGLISH, 10, 20, 160, 32);
        a.role(Accessible.Role.COMBO_BOX);
        a.name(I18nString.literal("Mountain ranges"), Accessible.NameFrom.EXPLICIT);
        a.value(2, 0, 5, 1);
        a.valueText(shown, 1);
        AccessibilityAccess.inherited(a, true, true, true, true, false);
        if (editable) {
            AccessibilityAccess.begin(a, field, 1, Locale.ENGLISH, 10, 20, 160, 32); // under the combo, slot 1
            a.role(Accessible.Role.TEXT_FIELD);
            a.name(I18nString.literal("Mountain ranges"), Accessible.NameFrom.EXPLICIT);
            AccessibilityAccess.inherited(a, true, true, true, true, false);
            a.end();
        }
        a.end();
        a.end();
        AccessibilityAccess.resolveRelations(a, (kind, target) -> (Long) target);
        return a.publish(0, 0, 0, 1f, true);
    }

    private static AccessibleNode combo(AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.COMBO_BOX) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no combo box");
    }

    @Test
    void aComboThatIsNotEditableIsNamedByWhatItShows() {
        AccessibleTree tree = tree(false, "Atlas");
        assertEquals("Atlas", AtspiTree.nameOf(tree, combo(tree)));
    }

    @Test
    void anEditableOneKeepsItsNameForItsFieldSaysTheText() {
        AccessibleTree tree = tree(true, "Atlas");
        assertEquals("Mountain ranges", AtspiTree.nameOf(tree, combo(tree)));
    }

    @Test
    void aComboShowingNothingKeepsItsName() {
        AccessibleTree tree = tree(false, "");
        assertEquals("Mountain ranges", AtspiTree.nameOf(tree, combo(tree)));
    }
}
