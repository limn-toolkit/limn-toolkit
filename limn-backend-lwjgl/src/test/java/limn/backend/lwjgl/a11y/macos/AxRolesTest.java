package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §1.12's rule made mechanical: a role with no macOS row cannot reach a release. */
class AxRolesTest {

    @Test
    void everyRoleHasAMapping() {
        Set<String> missing = new TreeSet<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            try {
                assertNotNull(AxRoles.of(role).roleSymbol(), role + " maps to a null role symbol");
            } catch (IllegalStateException e) {
                missing.add(role.name());
            }
        }
        assertTrue(missing.isEmpty(), "roles with no macOS mapping: " + missing);
    }

    @Test
    void aRoleIsNarrowedByASubroleOrDescribedByAPhrase_neverBoth() {
        for (Accessible.Role role : Accessible.Role.values()) {
            AxRoles.Mapping mapping = AxRoles.of(role);
            if (mapping.subroleSymbol() != null) {
                // A subrole is AppKit's own word for the distinction. A phrase beside it would be
                // a second, competing answer to the same question, and a client is free to speak
                // either -- so which one it spoke would depend on the client.
                assertNull(mapping.roleDescriptionKey(),
                        role + " has both a subrole and a role-description key; AppKit narrows a "
                                + "role with one or the other, and a client may speak either");
            }
        }
    }

    @Test
    void theRolesThatDegradeAreExactlyTheOnesCarryingAPhrase() {
        // The point of this assertion is not the set; it is that changing the set is a deliberate
        // act. A role acquires a phrase when AppKit has no word for it, and quietly adding one is
        // how a table stops being a record of what the platform cannot say.
        Set<Accessible.Role> described = EnumSet.noneOf(Accessible.Role.class);
        for (Accessible.Role role : Accessible.Role.values()) {
            if (AxRoles.of(role).roleDescriptionKey() != null) described.add(role);
        }
        assertEquals(EnumSet.of(
                Accessible.Role.ALERT,       // no alert role, no alert subrole
                Accessible.Role.SEPARATOR,   // the subrole does not exist (§12.3)
                Accessible.Role.HEADING,     // no heading role and no heading level
                Accessible.Role.VIDEO,       // no video role; image would promise a still
                Accessible.Role.CANVAS,
                Accessible.Role.CHART), described);
    }

    @Test
    void rolesThatShareAWordShareItForAStatedReason() {
        Map<String, Set<String>> byRole = new HashMap<>();
        for (Accessible.Role role : Accessible.Role.values()) {
            AxRoles.Mapping mapping = AxRoles.of(role);
            String key = mapping.roleSymbol()
                    + (mapping.subroleSymbol() == null ? "" : "/" + mapping.subroleSymbol());
            byRole.computeIfAbsent(key, ignored -> new TreeSet<>()).add(role.name());
        }
        // AXMenuItem carries three of the toolkit's roles because AppKit has one menu-item word and
        // the difference is read from the value and the selection instead. AXGroup carries the rest
        // of what this platform has no word for. Anything else sharing a word is a mistake, and the
        // shape of the assertion is what makes a fourth menu-item role visible in review.
        assertEquals(Set.of("CHECK_MENU_ITEM", "MENU_ITEM", "RADIO_MENU_ITEM"),
                byRole.get("NSAccessibilityMenuItemRole"));
        assertEquals(Set.of("ALERT", "CANVAS", "CHART", "CHART_SERIES", "GROUP", "SEPARATOR",
                        "TAB_PANEL", "VIDEO"),
                byRole.get("NSAccessibilityGroupRole"));
        assertEquals(Set.of("HEADING", "LABEL"), byRole.get("NSAccessibilityStaticTextRole"));
    }
}
