package limn.a11y.linux;

import limn.accessibility.Accessible;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which roles this bridge can name to a screen reader, and which are still owed a number.
 *
 * <p>A ratchet rather than a wall, for the reason the coverage test gives about describing
 * components: a table that simply failed until every role had a number would leave the build red
 * for the length of the work and stop being a signal. So the list of what is missing is carried
 * here and asserted <em>exact in both directions</em> — nothing outside it may be unmapped, so a
 * new role cannot slip in unnamed, and nothing inside it may already be mapped, so the list cannot
 * rot as the numbers arrive.
 *
 * <p>A number leaves this list by being read off the machine and not by being remembered. The
 * three that are mapped came from the typelib on the guest; the rest are owed the same, and until
 * a role has one this bridge cannot honestly tell a reader what the control is.
 */
class AtspiRolesTest {

    /**
     * Roles with no AT-SPI2 number yet, each owed one reading of the guest's typelib.
     *
     * <p>Strike a name off in the same commit that adds its number.
     */
    private static final Set<String> UNMAPPED = new TreeSet<>(Set.of(
            "ALERT", "CANVAS", "CHART", "CHART_SERIES", "CHECK_BOX", "CHECK_MENU_ITEM",
            "COLOR_CHOOSER", "COMBO_BOX", "DIALOG", "GROUP", "HEADING", "IMAGE", "LABEL",
            "LIST", "LIST_ITEM", "MENU", "MENU_BAR", "MENU_ITEM", "PASSWORD_FIELD",
            "PROGRESS_BAR", "RADIO_BUTTON", "RADIO_GROUP", "RADIO_MENU_ITEM", "SCROLL_BAR",
            "SCROLL_PANE", "SEARCH_FIELD", "SEPARATOR", "SLIDER", "SPIN_BUTTON", "SPLITTER",
            "SPLIT_PANE", "SWITCH", "TAB", "TAB_LIST", "TAB_PANEL", "TEXT_AREA", "TEXT_FIELD",
            "TOGGLE_BUTTON", "TOOL_BAR", "VIDEO"));

    @Test
    void everyRoleIsEitherNamedToThePlatformOrOnTheListOfWhatIsLeft() {
        Set<String> unmapped = EnumSet.allOf(Accessible.Role.class).stream()
                .filter(role -> AtspiRoles.of(role) == null)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(UNMAPPED, unmapped,
                "the list is exact in both directions: a role missing from it is one this bridge "
                        + "would announce as nothing, and a role still on it that now has a number "
                        + "is bookkeeping the build is meant to enforce rather than a reader "
                        + "remember");
    }

    @Test
    void aMappedRoleKeepsTheNumberTheMachineGave() {
        // The three the spike read off the typelib, and the evidence for them is in the lab's
        // RESULT.md: libatspi and Orca both found the spike's button by name, as role 43.
        assertEquals(23, AtspiRoles.of(Accessible.Role.WINDOW), "Atspi.Role.FRAME");
        assertEquals(43, AtspiRoles.of(Accessible.Role.BUTTON), "Atspi.Role.PUSH_BUTTON");
        assertEquals(0, AtspiRoles.of(Accessible.Role.UNKNOWN), "Atspi.Role.INVALID");
    }
}
