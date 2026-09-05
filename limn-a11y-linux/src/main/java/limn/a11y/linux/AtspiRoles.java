package limn.a11y.linux;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each of the toolkit's roles is called on this platform, in AT-SPI2's own numbering.
 *
 * <p><b>Every value here was read off the machine, and none was written from memory.</b> The
 * numbers are {@code AtspiRole} enumerators taken from the GObject-introspection typelib on the
 * system under test, the same file {@code libatspi} and therefore Orca read:
 *
 * <pre>
 * python3 -c 'from gi.repository import Atspi; print(int(Atspi.Role.PUSH_BUTTON))'
 * </pre>
 *
 * <p>That rule is the reason this class is a table with a hole in it rather than a complete guess.
 * A wrong role number does not fail: it announces a slider as a menu item, in a voice the person
 * relying on it has no way to check against the screen. Nothing is more likely to be silently
 * wrong than a constant copied from a memory of a header, and nothing is harder to notice
 * afterwards. What is missing is named in {@code AtspiRolesTest}, which asserts the two halves
 * exact in both directions, so a role gains a number only by being read off the machine and the
 * build says how many are left.
 */
final class AtspiRoles {

    private AtspiRoles() {
    }

    /** Read from the typelib on Ubuntu 24.04, at-spi2-core 2.52.0-1build1, aarch64. */
    private static final Map<Accessible.Role, Integer> KNOWN = new EnumMap<>(Accessible.Role.class);

    static {
        KNOWN.put(Accessible.Role.WINDOW, 23);       // Atspi.Role.FRAME
        KNOWN.put(Accessible.Role.BUTTON, 43);       // Atspi.Role.PUSH_BUTTON
        KNOWN.put(Accessible.Role.UNKNOWN, 0);       // Atspi.Role.INVALID
    }

    /**
     * The AT-SPI2 enumerator for {@code role}, or {@code null} while nobody has read it off the
     * machine.
     *
     * @param role the toolkit's role
     * @return the platform's number, or {@code null}
     */
    static Integer of(Accessible.Role role) {
        return KNOWN.get(role);
    }

    /**
     * The platform's own name for {@code role}, for {@code GetRoleName}.
     *
     * @param role the toolkit's role
     * @return the AT-SPI role name, or {@code "unknown"} while the number is unread
     */
    static String nameOf(Accessible.Role role) {
        Integer n = KNOWN.get(role);
        if (n == null) {
            return "unknown";
        }
        return switch (n) {
            case 23 -> "frame";
            case 43 -> "push button";
            default -> "unknown";
        };
    }

    /** @return the roles that have a number, for the test that keeps the list honest */
    static java.util.Set<Accessible.Role> mapped() {
        return java.util.Collections.unmodifiableSet(KNOWN.keySet());
    }
}
