package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every role and state this toolkit publishes has a number this platform understands.
 *
 * <p>Asserted exact in both directions, which is what makes it a ratchet rather than a snapshot:
 * a role added to the toolkit with no reading fails here rather than announcing as {@code invalid}
 * in a voice nobody in this repository will ever hear, and a state on the exempt list that has
 * since been mapped fails too, so the list cannot rot.
 *
 * <p>The numbers came from the guest and not from anyone's memory; see
 * {@code scripts/a11y/linux/dump-atspi-constants.py} and the note on {@link AtspiRoles}.
 */
class AtspiConstantsTest {

    /**
     * States the platform does not carry as states, each with the reason it does not.
     *
     * <p>{@code PASSWORD} is the only one: being a password is the <em>role</em> here, and the
     * nearest-looking bit means the entry was rejected, so mapping it would tell a reader that
     * every password field on screen is in error.
     */
    private static final Set<String> NOT_A_STATE_HERE = new TreeSet<>(Set.of("PASSWORD"));

    @Test
    void everyRoleTheToolkitPublishesHasANumberReadOffTheMachine() {
        Set<String> missing = EnumSet.allOf(Accessible.Role.class).stream()
                .filter(role -> AtspiRoles.of(role) == null)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(), missing,
                "a role with no reading announces as 'invalid', which is a control a reader cannot "
                        + "name; run scripts/a11y/linux/dump-atspi-constants.py on the guest");
    }

    @Test
    void everyRelationTheToolkitPublishesHasANumber() {
        Set<String> missing = EnumSet.allOf(Accessible.Relation.class).stream()
                .filter(relation -> AtspiRelations.of(relation) == null)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(), missing,
                "a relation with no number is dropped from every relation set in silence; read "
                        + "it from the AtspiRelationType enum and confirm it with the dump script");
    }

    @Test
    void everyStateIsEitherABitOrOnTheListOfWhatThisPlatformCarriesOtherwise() {
        Set<String> unmapped = EnumSet.allOf(Accessible.State.class).stream()
                .filter(state -> AtspiStates.bitOf(state) == null)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(NOT_A_STATE_HERE, unmapped,
                "exact in both directions: a state missing from the list is one this bridge drops "
                        + "in silence, and a state still on it that now has a bit is bookkeeping "
                        + "the build is meant to enforce rather than a reader remember");
    }

    @Test
    void noTwoRolesThatAReaderMustTellApartShareANumber() {
        // A tab and the page it opens are the pair this went wrong for first: both were page tab,
        // which is a tree in which the header and its content are indistinguishable.
        assertTrue(AtspiRoles.of(Accessible.Role.TAB) != null);
        assertEquals(37, AtspiRoles.of(Accessible.Role.TAB), "page tab");
        assertEquals(39, AtspiRoles.of(Accessible.Role.TAB_PANEL),
                "the platform has no tab-panel concept, so the neutral container is the honest "
                        + "answer and never the tab's own role");
    }

    @Test
    void enabledCarriesTheSensitiveBitTheReaderActuallyChecks() {
        long enabled = AtspiStates.setOf(s -> s == Accessible.State.ENABLED);
        assertTrue((enabled & (1L << 8)) != 0, "enabled");
        assertTrue((enabled & (1L << AtspiStates.SENSITIVE)) != 0,
                "and sensitive, which is one fact to this toolkit and two bits here: a reader that "
                        + "checks only sensitivity would find every control inert without it");

        long none = AtspiStates.setOf(s -> false);
        assertEquals(0, none, "nothing claimed, nothing published");
    }

    /**
     * A node that can open is {@code EXPANDABLE} here as in the model, and while it is closed it
     * is also {@code COLLAPSED}, which is a state this platform has and the model does not: bits 9
     * and 5, read off the Fedora KDE 44 guest on 2026-09-13 (decision 27).
     */
    @Test
    void aClosedExpandableNodeIsCollapsedHereAndAnOpenOneIsNot() {
        long closed = AtspiStates.setOf(s -> s == Accessible.State.EXPANDABLE);
        assertTrue((closed & (1L << 9)) != 0, "expandable");
        assertTrue((closed & (1L << 5)) != 0, "and collapsed, derived: expandable and not expanded");
        assertTrue((closed & (1L << 10)) == 0, "not expanded");

        long open = AtspiStates.setOf(
                s -> s == Accessible.State.EXPANDABLE || s == Accessible.State.EXPANDED);
        assertTrue((open & (1L << 9)) != 0, "expandable");
        assertTrue((open & (1L << 10)) != 0, "expanded");
        assertTrue((open & (1L << 5)) == 0, "and no longer collapsed");

        long leaf = AtspiStates.setOf(s -> s == Accessible.State.ENABLED);
        assertTrue((leaf & (1L << 5)) == 0,
                "a node with no expand facet is not collapsed: it cannot open at all");
    }

    /**
     * The numbers and names the interfaces served since 2026-09-15 take from readings, pinned so an
     * edit cannot drift from them in silence: the TextGranularity, TextBoundaryType and CoordType
     * enumerators read off the Fedora KDE 44 guest's typelib (libatspi 2.60.6, 2026-09-13,
     * readings/fedora-atspi-constants-all.txt), and the interface names the guest's ATK bridge
     * declares (readings/fedora-dbus-*.xml).
     */
    @Test
    void theTextEnumeratorsAndTheInterfaceNamesAreTheOnesReadOffTheGuest() {
        assertEquals(java.util.List.of(0, 1, 2, 3, 4), java.util.List.of(Atspi.TEXT_GRANULARITY_CHAR,
                Atspi.TEXT_GRANULARITY_WORD, Atspi.TEXT_GRANULARITY_SENTENCE,
                Atspi.TEXT_GRANULARITY_LINE, Atspi.TEXT_GRANULARITY_PARAGRAPH),
                "TextGranularity CHAR WORD SENTENCE LINE PARAGRAPH");
        assertEquals(java.util.List.of(0, 1, 2, 3, 4, 5, 6), java.util.List.of(
                Atspi.TEXT_BOUNDARY_CHAR, Atspi.TEXT_BOUNDARY_WORD_START,
                Atspi.TEXT_BOUNDARY_WORD_END, Atspi.TEXT_BOUNDARY_SENTENCE_START,
                Atspi.TEXT_BOUNDARY_SENTENCE_END, Atspi.TEXT_BOUNDARY_LINE_START,
                Atspi.TEXT_BOUNDARY_LINE_END),
                "TextBoundaryType CHAR WORD_START WORD_END SENTENCE_START SENTENCE_END LINE_START "
                        + "LINE_END");
        assertEquals(java.util.List.of(0, 1, 2), java.util.List.of(Atspi.COORD_SCREEN,
                Atspi.COORD_WINDOW, Atspi.COORD_PARENT), "CoordType SCREEN WINDOW PARENT");
        assertEquals(java.util.List.of("org.a11y.atspi.Selection", "org.a11y.atspi.Value",
                        "org.a11y.atspi.Text", "org.a11y.atspi.EditableText"),
                java.util.List.of(Atspi.I_SELECTION, Atspi.I_VALUE, Atspi.I_TEXT,
                        Atspi.I_EDITABLE_TEXT));
        for (String[] pair : new String[][] {
                {Atspi.I_SELECTION, Atspi.XML_SELECTION}, {Atspi.I_VALUE, Atspi.XML_VALUE},
                {Atspi.I_TEXT, Atspi.XML_TEXT}, {Atspi.I_EDITABLE_TEXT, Atspi.XML_EDITABLE_TEXT},
                {Atspi.I_TABLE, Atspi.XML_TABLE}, {Atspi.I_TABLE_CELL, Atspi.XML_TABLE_CELL}}) {
            assertTrue(pair[1].startsWith("<interface name=\"" + pair[0] + "\">")
                    && pair[1].endsWith("</interface>"), "the XML served for " + pair[0]
                    + " is that interface's, whole");
        }
    }

    @Test
    void aRoleCarriesThePlatformsOwnNameForIt() {
        assertEquals("push button", AtspiRoles.nameOf(Accessible.Role.BUTTON));
        assertEquals("frame", AtspiRoles.nameOf(Accessible.Role.WINDOW));
        assertEquals("entry", AtspiRoles.nameOf(Accessible.Role.SEARCH_FIELD),
                "the platform has no search role, and saying so is better than inventing one");
        assertNotNull(AtspiRoles.nameOf(Accessible.Role.UNKNOWN));
    }
}
