package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.Map;

/**
 * Which bit of AT-SPI2's state set each of the toolkit's states is, read off the machine.
 *
 * <p>Bit indices into the 64-bit set the platform passes as two words, from the same typelib and
 * the same script as {@link AtspiRoles}.
 *
 * <p><b>One state deliberately maps to nothing.</b> There is no password state here: on this
 * platform being a password is the <em>role</em>, {@code PASSWORD_TEXT}, and the nearest-looking
 * bit, {@code INVALID_ENTRY}, means the entry's contents were rejected. Mapping a password onto it
 * would tell a reader that every password field on screen is in error, which is worse than saying
 * nothing, so a password says nothing here and everything in its role.
 */
final class AtspiStates {

    private AtspiStates() {
    }

    /** The platform's own bit for a control that accepts input; it rides with {@code ENABLED}. */
    static final int SENSITIVE = 24;

    private static final Map<Accessible.State, Integer> BIT = new EnumMap<>(Accessible.State.class);

    static {
        BIT.put(Accessible.State.ENABLED, 8);
        BIT.put(Accessible.State.FOCUSABLE, 11);
        BIT.put(Accessible.State.FOCUSED, 12);
        BIT.put(Accessible.State.VISIBLE, 30);
        BIT.put(Accessible.State.SHOWING, 25);
        BIT.put(Accessible.State.SELECTABLE, 22);
        BIT.put(Accessible.State.SELECTED, 23);
        BIT.put(Accessible.State.CHECKED, 4);
        BIT.put(Accessible.State.MIXED, 32);            // INDETERMINATE
        BIT.put(Accessible.State.PRESSED, 20);
        BIT.put(Accessible.State.EXPANDED, 10);
        BIT.put(Accessible.State.HAS_POPUP, 42);
        BIT.put(Accessible.State.READ_ONLY, 43);
        BIT.put(Accessible.State.EDITABLE, 7);
        BIT.put(Accessible.State.MULTI_LINE, 17);
        BIT.put(Accessible.State.INVALID, 36);          // INVALID_ENTRY
        BIT.put(Accessible.State.REQUIRED, 33);
        BIT.put(Accessible.State.BUSY, 3);
        BIT.put(Accessible.State.MODAL, 16);
        BIT.put(Accessible.State.ACTIVE, 1);
        BIT.put(Accessible.State.DEFAULT, 39);          // IS_DEFAULT
        BIT.put(Accessible.State.HORIZONTAL, 14);
        BIT.put(Accessible.State.VERTICAL, 29);
    }

    /**
     * @param state the toolkit's state
     * @return its bit index, or {@code null} when the platform carries it as something other than
     *         a state
     */
    static Integer bitOf(Accessible.State state) {
        return BIT.get(state);
    }

    /**
     * The platform's state set for one node.
     *
     * @param has answers whether the node holds a state
     * @return the 64-bit set
     */
    static long setOf(java.util.function.Predicate<Accessible.State> has) {
        long out = 0;
        for (Accessible.State state : Accessible.State.values()) {
            Integer bit = BIT.get(state);
            if (bit != null && has.test(state)) {
                out |= 1L << bit;
            }
        }
        if (has.test(Accessible.State.ENABLED)) {
            // Enabled and sensitive are one fact to this toolkit and two bits here; a reader that
            // checks only sensitivity would find every control inert without the second.
            out |= 1L << SENSITIVE;
        }
        return out;
    }
}
