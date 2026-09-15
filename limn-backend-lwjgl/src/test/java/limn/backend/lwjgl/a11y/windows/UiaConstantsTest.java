package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.RoleNames;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every role this toolkit publishes has a control type this platform understands, and that
 * the readings are the ones the guest gave.
 *
 * <p>Asserted exact in both directions, which is what makes it a ratchet rather than a snapshot: a
 * role added to the toolkit with no reading fails here rather than announcing as something a
 * client picked for itself, and a role on the phrase list that has since gained a platform word of
 * its own fails too, so the list cannot rot.
 *
 * <p>The numbers came from the guest and not from anyone's memory; see
 * {@code scripts/a11y/windows/dump-uia-constants.ps1} and the note on {@link UiaIds}. Nothing here
 * needs Windows: a table is a table on any machine, which is the point of reading it once and
 * compiling it in.
 */
class UiaConstantsTest {

    /**
     * Roles that answer {@code LocalizedControlType} with a phrase of this bridge's own, and why
     * each one has to.
     *
     * <p>The list is asserted exact because the alternative is a phrase nobody removes. A role
     * here is a place where UI Automation's vocabulary has no word, or has one that would mislead
     * — and either is a claim about the platform that should fail loudly if it stops being true.
     */
    private static final Set<String> SPEAK_A_PHRASE_OF_OUR_OWN = new TreeSet<>(Set.of(
            // No alert control type at all.
            "ALERT",
            // Thumb is right, and "thumb" spoken after a splitter's name describes scroll-bar
            // furniture rather than the divider between two panes.
            "SPLITTER",
            // No toggle-button type: Button plus a Toggle pattern is the platform's shape for one.
            "TOGGLE_BUTTON",
            // §1.12: no switch control type. CheckBox plus a phrase is what the platform takes.
            "SWITCH",
            // No video type; Pane would otherwise be spoken as an unnamed container.
            "VIDEO",
            // Custom's own guidance requires a phrase: "custom" is not worth speaking.
            "CANVAS",
            "CHART",
            // No search-field type; Edit alone would lose what the field is for.
            "SEARCH_FIELD"
    ));

    @Test
    void everyRoleTheToolkitPublishesHasAControlTypeReadOffTheMachine() {
        Set<String> missing = EnumSet.allOf(Accessible.Role.class).stream()
                .filter(role -> {
                    try {
                        UiaRoles.of(role);
                        return false;
                    } catch (IllegalArgumentException absent) {
                        return true;
                    }
                })
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(), missing,
                "a role with no control type is a control the client classifies for itself, and "
                        + "what it picks is not something this repository will ever hear. ADR 039 "
                        + "§1.12: a new role owes a row in §2.1 naming a real platform constant");
    }

    @Test
    void everyControlTypeIsOneTheGuestNumbered() {
        for (Accessible.Role role : EnumSet.allOf(Accessible.Role.class)) {
            int type = UiaRoles.of(role);
            assertTrue(type >= UiaIds.CONTROL_BUTTON && type <= UiaIds.CONTROL_SEPARATOR,
                    role + " maps to " + type + ", which is outside the block of control type ids "
                            + "the interop assembly declares (" + UiaIds.CONTROL_BUTTON + ".."
                            + UiaIds.CONTROL_SEPARATOR + "): a number in no block at all is a "
                            + "number nobody read");
        }
    }

    @Test
    void theRolesThatSpeakAPhraseOfTheirOwnAreExactlyTheOnesRecorded() {
        Set<String> speaking = UiaRoles.rolesWithAPhraseOfTheirOwn().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(SPEAK_A_PHRASE_OF_OUR_OWN, speaking,
                "a phrase of our own is a claim that the platform has no word for this control, "
                        + "and a claim nobody re-checks is one that rots");
    }

    @Test
    void aRoleWithThePlatformsOwnWordSaysNothingExtra() {
        for (Accessible.Role role : new Accessible.Role[] {Accessible.Role.BUTTON,
                Accessible.Role.CHECK_BOX, Accessible.Role.SLIDER, Accessible.Role.MENU_ITEM}) {
            assertFalse(UiaRoles.speaksOurOwnPhrase(role),
                    role + " speaks a phrase of ours over a control type the platform has its own "
                            + "word for, and the platform's word is what makes a reader sound "
                            + "like every other application on the machine");
        }
    }

    /**
     * The three constants no identifier table carries, pinned so that a change to any of them is a
     * deliberate edit rather than a typo nobody sees. Each was read on the guest on 2026-09-13 from
     * the managed side (the provider API's AppendRuntimeId; the exceptions' HResults and the
     * internal constants), and each reading is cited in {@link UiaIds}'s javadoc; their header
     * spellings are not read.
     */
    @Test
    void theConstantsNoInteropAssemblyCarriesAreTheDocumentedOnes() {
        assertEquals(3, UiaIds.APPEND_RUNTIME_ID,
                "the marker UI Automation replaces with the host window's own runtime id");
        assertEquals(0x80040201, UiaIds.E_ELEMENT_NOT_AVAILABLE);
        assertEquals(0x80131509, UiaIds.E_INVALID_OPERATION);
    }

    /** WINDOWS-NEW-11, read 2026-09-13 (readings/windows-dump-uia-marshalling.txt). */
    @Test
    void aBoolOutParameterIsFourBytesOfOneOrZeroAndNotAVariantBool() {
        assertEquals(1, UiaIds.BOOL_TRUE, "the guest's managed provider wrote 01 00 00 00");
        assertEquals(0, UiaIds.BOOL_FALSE, "and 00 00 00 00");
        assertEquals(-1, UiaVariant.TRUE, "which is not the VARIANT_BOOL a VARIANT carries");
    }

    /** Read 2026-09-15 (readings/windows-dump-uia-invalidate-limits.txt). */
    @Test
    void theSelectionBulkThresholdIsTheProviderApisInvalidateLimit() {
        assertEquals(20, UiaIds.INVALIDATE_LIMIT,
                "AutomationInteropProvider.InvalidateLimit, and SelectorAutomationPeer's own ble.s 20");
    }

    /**
     * What IScrollProvider takes and answers (decision 39), each read on the guest: ScrollAmount and
     * NoScroll on 2026-09-13 (readings/windows-dump-uia-constants.txt and -typelib.txt), the managed
     * HResult of ArgumentOutOfRangeException on 2026-09-15
     * (readings/windows-dump-uia-provider-conventions.txt §2), UIA_E_ELEMENTNOTENABLED on 2026-09-13
     * (readings/windows-dump-uia-hresults.txt).
     */
    @Test
    void theScrollPatternsNumbersAreTheOnesTheGuestGave() {
        assertEquals(0, UiaIds.SCROLL_AMOUNT_LARGE_DECREMENT);
        assertEquals(1, UiaIds.SCROLL_AMOUNT_SMALL_DECREMENT);
        assertEquals(2, UiaIds.SCROLL_AMOUNT_NO_AMOUNT);
        assertEquals(3, UiaIds.SCROLL_AMOUNT_LARGE_INCREMENT);
        assertEquals(4, UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT);
        assertEquals(-1.0, UiaIds.SCROLL_NO_SCROLL, "UIA_ScrollPatternNoScroll, VT_R8");
        assertEquals(0x80040200, UiaIds.E_ELEMENT_NOT_ENABLED);
        assertEquals(0x80131502, UiaIds.E_ARGUMENT_OUT_OF_RANGE);
    }

    /**
     * What UiaRaiseStructureChangedEvent and UiaRaiseNotificationEvent take, read on the guest
     * 2026-09-13 from the managed enumerations and the type libraries, which agree
     * (readings/windows-dump-uia-constants.txt, -typelib.txt), and the provider API's limit for a
     * container of items (AutomationInteropProvider.ItemsInvalidateLimit).
     */
    @Test
    void theStructureAndNotificationNumbersAreTheOnesTheGuestGave() {
        assertEquals(0, UiaIds.STRUCTURE_CHANGE_CHILD_ADDED);
        assertEquals(1, UiaIds.STRUCTURE_CHANGE_CHILD_REMOVED);
        assertEquals(2, UiaIds.STRUCTURE_CHANGE_CHILDREN_INVALIDATED);
        assertEquals(3, UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_ADDED);
        assertEquals(4, UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED);
        assertEquals(5, UiaIds.STRUCTURE_CHANGE_CHILDREN_REORDERED);
        assertEquals(0, UiaIds.NOTIFICATION_KIND_ITEM_ADDED);
        assertEquals(1, UiaIds.NOTIFICATION_KIND_ITEM_REMOVED);
        assertEquals(2, UiaIds.NOTIFICATION_KIND_ACTION_COMPLETED);
        assertEquals(3, UiaIds.NOTIFICATION_KIND_ACTION_ABORTED);
        assertEquals(4, UiaIds.NOTIFICATION_KIND_OTHER);
        assertEquals(0, UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_ALL);
        assertEquals(1, UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT);
        assertEquals(2, UiaIds.NOTIFICATION_PROCESSING_ALL);
        assertEquals(3, UiaIds.NOTIFICATION_PROCESSING_MOST_RECENT);
        assertEquals(4, UiaIds.NOTIFICATION_PROCESSING_CURRENT_THEN_MOST_RECENT);
        assertEquals(5, UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_CURRENT_THEN_MOST_RECENT,
                "the type library's; the managed enumeration stops at 4");
        assertEquals(5, UiaIds.ITEMS_INVALIDATE_LIMIT);
    }

    /**
     * UIA_LevelPropertyId, read 2026-09-13 from UIAutomationCore.dll's type library and the
     * internal managed table (readings/windows-dump-uia-typelib.txt, -constants.txt), beside the two
     * position ids the interop assembly carries.
     */
    @Test
    void theLevelIdIsTheTypeLibrarysAndSitsBesideThePositionIds() {
        assertEquals(30152, UiaIds.POSITION_IN_SET);
        assertEquals(30153, UiaIds.SIZE_OF_SET);
        assertEquals(30154, UiaIds.LEVEL);
    }

    /**
     * A handful of readings spot-checked against what §2.1 says the bridge answers with, so that a
     * re-run of the dump script that renamed or renumbered something fails here rather than in a
     * guest three commits later.
     */
    @Test
    void theReadingsSection21DependsOnAreTheOnesInTheTable() {
        assertEquals(2, UiaIds.PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER,
                "and 1 is ClientSideProvider, which is the trap this reading exists to avoid");
        assertEquals(0, UiaIds.NAVIGATE_DIRECTION_PARENT);
        assertEquals(3, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD);
        assertEquals(30005, UiaIds.NAME);
        assertEquals(30003, UiaIds.CONTROL_TYPE);
        assertEquals(30016, UiaIds.IS_CONTROL_ELEMENT);
        assertEquals(30017, UiaIds.IS_CONTENT_ELEMENT);
        assertEquals(30174, UiaIds.IS_DIALOG);
        assertEquals(10000, UiaIds.INVOKE_PATTERN);
        assertEquals(20009, UiaIds.INVOKE_INVOKED);
    }

    @Test
    void everyPhraseIsNonEmptyAndDistinct() {
        // The phrases live in the core catalogue now, shared with the macOS bridge, so what this
        // checks is that the roles THIS platform chose to speak for itself have phrases there and
        // that no two of them say the same word -- which would describe two different controls
        // identically to a reader.
        Set<String> phrases = new TreeSet<>();
        for (Accessible.Role role : UiaRoles.rolesWithAPhraseOfTheirOwn()) {
            String phrase = RoleNames.englishOf(role);
            assertNotNull(phrase, role.name());
            assertTrue(!phrase.isBlank(), role + " has a blank phrase");
            assertTrue(phrases.add(phrase),
                    role + " reuses the phrase \"" + phrase + "\", which would speak two different "
                            + "controls the same way");
        }
    }
}
