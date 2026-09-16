package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.RoleNames;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
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

    /** The bridge's own source, where a platform number is used rather than declared. */
    private static final java.nio.file.Path WINDOWS_PACKAGE = java.nio.file.Path.of(
            "limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/a11y/windows");

    private static String sourceOf(String file) {
        try {
            return java.nio.file.Files.readString(
                    limn.testing.RepositoryRoot.find().resolve(WINDOWS_PACKAGE).resolve(file),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    /**
     * One documented member of a source file: the javadoc that opens it, its signature and its
     * body, from the {@code /**} that begins that javadoc to the one that begins the next member's.
     *
     * <p><b>Why a slice and not a count.</b> The ratchets below pin a citation <i>inside the member
     * that uses the number</i> rather than counting occurrences over the whole file. A count goes
     * red when a later lane does the very thing these tests exist to encourage — citing the same
     * reading at a fourth honest use, or marking a third mapping as a choice — and it fails with a
     * message that reads as if something had been removed. A ratchet that punishes what it asks for
     * is one the next lane deletes, and then nothing pins anything.
     */
    private static String documentedMember(String file, String signature) {
        String source = sourceOf(file);
        int at = source.indexOf(signature);
        assertTrue(at >= 0, file + " has no member spelled \"" + signature + "\", so the citation "
                + "this test pins inside it is pinned nowhere: rename the signature here when the "
                + "member is renamed, rather than leaving a test that passes over nothing");
        assertTrue(source.indexOf(signature, at + 1) < 0,
                file + " spells \"" + signature + "\" more than once, so the slice read here is "
                        + "not certainly the member it means");
        int javadoc = source.lastIndexOf("/**", at);
        assertTrue(javadoc >= 0, file + "'s " + signature + " has no javadoc above it at all");
        int next = source.indexOf("/**", at);
        return source.substring(javadoc, next < 0 ? source.length() : next);
    }

    /**
     * The comment block written above {@code anchor}, back to the blank line that opens it, for the
     * one site below that is a {@code switch} arm rather than a member of its own.
     */
    private static String commentAbove(String file, String anchor) {
        String source = sourceOf(file);
        int at = source.indexOf(anchor);
        assertTrue(at >= 0, file + " no longer contains \"" + anchor + "\", so the comment this "
                + "test pins is pinned nowhere");
        assertTrue(source.indexOf(anchor, at + 1) < 0,
                file + " contains \"" + anchor + "\" more than once, so the block read here is not "
                        + "certainly the one it means");
        int from = source.lastIndexOf("\n\n", at);
        return source.substring(from < 0 ? 0 : from, at);
    }

    /**
     * §12.3: a platform number is cited where it is <b>used</b> and not only where it is declared.
     * A reader of {@link UiaPatternProviders#refusal} — the one place that chooses between the two
     * refusal HRESULTs, and the place every verb, setter and pattern entry point of this bridge
     * routes its refusal through — should not have to open another file to learn that 0x80040200
     * and 0x80131509 came off a guest and which script read them. The phase-3 critic listed both
     * uses among the constants "without a reading (or a choice between disagreeing readings)".
     *
     * <p>The two direct uses outside it are {@code IScrollProvider}'s own refusal order. Each of the
     * three is pinned in its own member, so that this fails for the use that lost its citation and
     * names it, and so that a fourth use citing the same reading is an addition and not a failure.
     */
    @Test
    void theTwoRefusalHresultsCiteTheirReadingWhereTheyAreUsed() {
        for (String member : java.util.List.of("static int refusal(", "static int scroll(",
                "static int setScrollPercent(")) {
            assertTrue(documentedMember("UiaPatternProviders.java", member)
                            .contains("readings/windows-dump-uia-hresults.txt"),
                    "\"" + member + "\" in UiaPatternProviders.java answers "
                            + "UIA_E_ELEMENTNOTENABLED or the managed InvalidOperationException "
                            + "HResult without saying where the number came from: a number with no "
                            + "reading beside it is one nobody can re-check, and the declaration "
                            + "alone is another file away");
        }
    }

    /**
     * The one use of {@code UIA_E_ELEMENTNOTENABLED} no reading settles, marked as the choice it is
     * (the settled list, 2026-09-15: "Windows answering 0x80040200 for a verb on a node that is not
     * ENABLED is kept, the same reading as the setter case"). {@code SetFocus} and
     * {@code ScrollIntoView} were read on the guest and came back split — the Win32 controls'
     * client-side proxies refuse a disabled element first, WPF checks nothing — so what this bridge
     * answers is argued rather than read, and all three places that answer it say so in the words
     * §12.3 asks for: the refusal itself, the {@code ScrollIntoView} slot and {@code SetFocus}.
     */
    @Test
    void setFocusAndScrollIntoViewMarkTheirRefusalAsAChoiceBetweenDisagreeingReadings() {
        Map<String, String> answeredAt = new java.util.LinkedHashMap<>();
        answeredAt.put("UiaPatternProviders.refusal",
                documentedMember("UiaPatternProviders.java", "static int refusal("));
        answeredAt.put("UiaPatternProviders' ScrollIntoView slot",
                commentAbove("UiaPatternProviders.java",
                        "case UiaIds.SCROLL_ITEM_PATTERN -> slots.put(\"ScrollIntoView\""));
        answeredAt.put("UiaProvider.setFocus",
                documentedMember("UiaProvider.java", "private static int setFocus("));

        answeredAt.forEach((where, text) -> {
            assertTrue(text.contains("readings/windows-dump-uia-focus-and-scroll-item.txt"),
                    where + " answers SetFocus or ScrollIntoView without citing the reading that "
                            + "found the platform's own providers disagreeing");
            assertTrue(text.contains("choice and not a reading"),
                    where + " does not mark that refusal as a choice: a number the guest did not "
                            + "settle, presented as one it did, is what §12.3 calls a defect that "
                            + "compiles");
        });
    }

    /**
     * The other two mappings the phase-3 critic listed as this bridge's own rather than a guest's —
     * an announcement's kind and processing, and {@code Value.Value} raised when only the number
     * moved — each marked a choice where it is made, with the reasoning beside it. The numbers they
     * use are read (the five kinds, the six processings, the property ids); what is chosen is which
     * of them a model fact becomes, and a reader of the mapping should be told which is which.
     *
     * <p>Pinned in each mapping's own member for {@link #documentedMember}'s reason: phase 5 is
     * expected to revisit open questions 1, 4 and 8, and a third mapping honestly marked as a
     * choice must be able to land without this test going red.
     */
    @Test
    void theTwoMappingsThisBridgeChoseRatherThanReadSayThatTheyAreChoices() {
        for (String member : java.util.List.of("static int[] notificationFor(",
                "static int[] valueRaises(")) {
            assertTrue(documentedMember("UiaBridge.java", member)
                            .contains("choice and not a reading"),
                    "\"" + member + "\" in UiaBridge.java is this bridge's reasoning and not a "
                            + "guest's answer — the announcement's kind and processing (question 4), "
                            + "Value.Value raised for a number-only move (open question 8) — and "
                            + "must say so where the mapping is made: a mapping that reads like a "
                            + "reading is one nobody revisits when a reader disagrees with it");
        }
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
     * The property, event and pattern ids phase 3's mappings raise and serve, each as
     * UIAutomationCore.dll's type library numbers it (read on the Windows 11 guest 2026-09-13,
     * readings/windows-dump-uia-typelib-all-members.txt: {@code UIA_PropertyIds},
     * {@code UIA_EventIds}, {@code UIA_PatternIds}). ItemStatus is also the property File Explorer's
     * sorted column header answers its direction in (readings/windows-read-native-sort-direction.txt,
     * 2026-09-15). Pinned so that a value change raised as the wrong property, or a pattern served
     * under another's id, is a deliberate edit here and not a silence on the guest.
     */
    @Test
    void theIdsPhaseThreeRaisesAndServesAreTheTypeLibrarysOwn() {
        // Properties a change is raised as (ADR 039 §2.4 as amended 2026-09-15).
        assertEquals(30008, UiaIds.HAS_KEYBOARD_FOCUS, "UIA_HasKeyboardFocusPropertyId");
        assertEquals(30010, UiaIds.IS_ENABLED, "UIA_IsEnabledPropertyId");
        assertEquals(30013, UiaIds.HELP_TEXT, "UIA_HelpTextPropertyId");
        assertEquals(30026, UiaIds.ITEM_STATUS, "UIA_ItemStatusPropertyId");
        assertEquals(30045, UiaIds.VALUE_VALUE, "UIA_ValueValuePropertyId");
        assertEquals(30046, UiaIds.VALUE_IS_READ_ONLY, "UIA_ValueIsReadOnlyPropertyId");
        assertEquals(30047, UiaIds.RANGE_VALUE_VALUE, "UIA_RangeValueValuePropertyId");
        assertEquals(30070, UiaIds.EXPAND_COLLAPSE_EXPAND_COLLAPSE_STATE,
                "UIA_ExpandCollapseExpandCollapseStatePropertyId");
        assertEquals(30079, UiaIds.SELECTION_ITEM_IS_SELECTED, "UIA_SelectionItemIsSelectedPropertyId");
        assertEquals(30086, UiaIds.TOGGLE_STATE, "UIA_ToggleToggleStatePropertyId");
        // Events raised on items and the root.
        assertEquals(20005, UiaIds.AUTOMATION_FOCUS_CHANGED, "UIA_AutomationFocusChangedEventId");
        assertEquals(20008, UiaIds.LAYOUT_INVALIDATED, "UIA_LayoutInvalidatedEventId");
        assertEquals(20010, UiaIds.SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION,
                "UIA_SelectionItem_ElementAddedToSelectionEventId");
        assertEquals(20011, UiaIds.SELECTION_ITEM_ELEMENT_REMOVED_FROM_SELECTION,
                "UIA_SelectionItem_ElementRemovedFromSelectionEventId");
        assertEquals(20012, UiaIds.SELECTION_ITEM_ELEMENT_SELECTED,
                "UIA_SelectionItem_ElementSelectedEventId");
        assertEquals(20013, UiaIds.SELECTION_INVALIDATED, "UIA_Selection_InvalidatedEventId");
        assertEquals(20014, UiaIds.TEXT_SELECTION_CHANGED, "UIA_Text_TextSelectionChangedEventId");
        // Patterns served from facets and verbs (ADR 039 §2.1 as amended 2026-09-15).
        assertEquals(10001, UiaIds.SELECTION_PATTERN, "UIA_SelectionPatternId");
        assertEquals(10002, UiaIds.VALUE_PATTERN, "UIA_ValuePatternId");
        assertEquals(10003, UiaIds.RANGE_VALUE_PATTERN, "UIA_RangeValuePatternId");
        assertEquals(10004, UiaIds.SCROLL_PATTERN, "UIA_ScrollPatternId");
        assertEquals(10005, UiaIds.EXPAND_COLLAPSE_PATTERN, "UIA_ExpandCollapsePatternId");
        assertEquals(10006, UiaIds.GRID_PATTERN, "UIA_GridPatternId");
        assertEquals(10007, UiaIds.GRID_ITEM_PATTERN, "UIA_GridItemPatternId");
        assertEquals(10010, UiaIds.SELECTION_ITEM_PATTERN, "UIA_SelectionItemPatternId");
        assertEquals(10012, UiaIds.TABLE_PATTERN, "UIA_TablePatternId");
        assertEquals(10013, UiaIds.TABLE_ITEM_PATTERN, "UIA_TableItemPatternId");
        assertEquals(10015, UiaIds.TOGGLE_PATTERN, "UIA_TogglePatternId");
        assertEquals(10017, UiaIds.SCROLL_ITEM_PATTERN, "UIA_ScrollItemPatternId");
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
