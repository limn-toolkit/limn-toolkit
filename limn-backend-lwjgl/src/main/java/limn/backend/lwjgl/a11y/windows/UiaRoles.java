package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What each of the toolkit's roles is called on this platform, in UI Automation's own numbering.
 *
 * <p>The numbers are {@link UiaIds}' control types, read off the guest. What this file adds is the
 * choice of which one each role takes, and that choice is a translation between two vocabularies
 * that do not line up — so every place they disagree is written down here rather than left for a
 * reader to discover by being told the wrong thing.
 *
 * <p><b>UI Automation has a second half to a control's identity, and this is the platform where
 * that matters most.</b> {@code LocalizedControlType} is the phrase a screen reader speaks after
 * the name — "button", "check box" — and a client is free to speak it instead of anything derived
 * from the control type itself. Microsoft's own guidance is that {@code Custom} <em>must</em> carry
 * one, because "custom" is not a word worth saying. Six of this toolkit's roles are cases where the
 * platform's own word is absent or wrong, and {@link #localizedTypeKey} names them; a role not in
 * that set takes the platform's word and says nothing extra, which is what makes the reader sound
 * like every other application on the machine.
 *
 * <p><b>Where two of the toolkit's roles share one control type, they share it because UI
 * Automation has no second word</b>, and the note says so rather than inventing a distinction.
 * What keeps them apart is what a client actually reads next: a check menu item is a menu item
 * with a Toggle pattern, a radio menu item is one with a SelectionItem pattern, and a password
 * field is an edit that answers {@code IsPassword}. The facets carry the difference, which is
 * §2.1's whole point about this platform: here the pattern list <em>is</em> the control's
 * behaviour.
 */
final class UiaRoles {

    private UiaRoles() {
    }

    private static final Map<Accessible.Role, Integer> TYPE =
            new EnumMap<>(Accessible.Role.class);

    /**
     * The roles whose platform word is absent or wrong, and for which this bridge speaks its own.
     *
     * <p><b>A set and not a table of phrases.</b> The phrases live in {@code RoleNames}, in the
     * core, where all three bridges reach them and where their translations are; this file's job is
     * the choice — which roles UI Automation cannot name — and that choice is this platform's
     * alone. It used to hold the English words too, which meant the same eight nouns existed in two
     * places and could disagree.
     */
    private static final Set<Accessible.Role> SPEAKS_OUR_OWN_PHRASE =
            EnumSet.noneOf(Accessible.Role.class);

    private static void map(Accessible.Role role, int controlType) {
        TYPE.put(role, controlType);
    }

    /** Maps a role, and records that UI Automation has no word for it worth speaking. */
    private static void mapAndSayOurOwn(Accessible.Role role, int controlType) {
        TYPE.put(role, controlType);
        SPEAKS_OUR_OWN_PHRASE.add(role);
    }

    static {
        // The window itself. A native popup gets this too: it is an HWND of its own and a client
        // walking the desktop finds it as a window.
        map(Accessible.Role.WINDOW, UiaIds.CONTROL_WINDOW);

        // An in-scene dialog is NOT a window control type and is not given WindowPattern: it has
        // no HWND, so Close(), SetVisualState() and CanMaximize would all be advertised over an
        // overlay that has none of them (§2.1). UI Automation's answer for a dialog rendered
        // inside a window is the IsDialog property, which the node carries separately; the control
        // type underneath it is the neutral container.
        map(Accessible.Role.DIALOG, UiaIds.CONTROL_PANE);
        // The same shape for an alert: UI Automation has no alert control type at all, and a
        // client learns "this is a thing to read now" from IsDialog plus the notification event
        // rather than from the type. The word is ours because the platform has none.
        mapAndSayOurOwn(Accessible.Role.ALERT, UiaIds.CONTROL_PANE);

        map(Accessible.Role.GROUP, UiaIds.CONTROL_GROUP);
        // Pane and not Group: a scroll pane is a viewport with a Scroll pattern, and Group is
        // what a client is told to treat as a labelled cluster of related controls.
        map(Accessible.Role.SCROLL_PANE, UiaIds.CONTROL_PANE);
        map(Accessible.Role.SCROLL_BAR, UiaIds.CONTROL_SCROLL_BAR);
        map(Accessible.Role.SPLIT_PANE, UiaIds.CONTROL_PANE);
        // Thumb is the platform's word for the part of a control a user drags, which is exactly
        // what a splitter is; but a client speaking "thumb" after the splitter's name would be
        // describing scroll-bar furniture, so the phrase is ours.
        mapAndSayOurOwn(Accessible.Role.SPLITTER, UiaIds.CONTROL_THUMB);

        map(Accessible.Role.TOOL_BAR, UiaIds.CONTROL_TOOL_BAR);
        map(Accessible.Role.MENU_BAR, UiaIds.CONTROL_MENU_BAR);
        map(Accessible.Role.MENU, UiaIds.CONTROL_MENU);
        map(Accessible.Role.MENU_ITEM, UiaIds.CONTROL_MENU_ITEM);
        // Both share MenuItem, because UI Automation has one menu-item type and says the rest with
        // patterns: a Toggle on the first, a SelectionItem on the second.
        map(Accessible.Role.CHECK_MENU_ITEM, UiaIds.CONTROL_MENU_ITEM);
        map(Accessible.Role.RADIO_MENU_ITEM, UiaIds.CONTROL_MENU_ITEM);
        map(Accessible.Role.SEPARATOR, UiaIds.CONTROL_SEPARATOR);

        map(Accessible.Role.BUTTON, UiaIds.CONTROL_BUTTON);
        // UI Automation has no toggle-button type. Button plus a Toggle pattern is the shape every
        // Windows application uses for one, and the phrase says which kind of button it is.
        mapAndSayOurOwn(Accessible.Role.TOGGLE_BUTTON, UiaIds.CONTROL_BUTTON);
        map(Accessible.Role.CHECK_BOX, UiaIds.CONTROL_CHECK_BOX);
        // §1.12's case: the platform has no switch, and CheckBox plus a phrase is what it takes.
        // Collapsing this into CHECK_BOX would lose the distinction on all three platforms at once.
        mapAndSayOurOwn(Accessible.Role.SWITCH, UiaIds.CONTROL_CHECK_BOX);
        map(Accessible.Role.RADIO_BUTTON, UiaIds.CONTROL_RADIO_BUTTON);
        // Group and not List: the members are radio buttons with a SelectionItem pattern each, and
        // a List would make a client offer list navigation over controls that are not list items.
        map(Accessible.Role.RADIO_GROUP, UiaIds.CONTROL_GROUP);

        map(Accessible.Role.LABEL, UiaIds.CONTROL_TEXT);
        // A heading is Text carrying the HeadingLevel property, which is how UI Automation has
        // said "heading" since Windows 10 1809; the level is the node's, not this table's.
        map(Accessible.Role.HEADING, UiaIds.CONTROL_TEXT);
        map(Accessible.Role.IMAGE, UiaIds.CONTROL_IMAGE);
        // No video control type. Pane rather than Image, because a client that treats it as an
        // image will try to describe a still that does not exist; the phrase carries the fact.
        mapAndSayOurOwn(Accessible.Role.VIDEO, UiaIds.CONTROL_PANE);
        // Custom is the platform's own answer for "a control that matches nothing here", and it is
        // the one control type whose guidance says a localized phrase is required rather than
        // optional: "custom" is not a word worth speaking.
        mapAndSayOurOwn(Accessible.Role.CANVAS, UiaIds.CONTROL_CUSTOM);
        mapAndSayOurOwn(Accessible.Role.CHART, UiaIds.CONTROL_CUSTOM);
        // A series is a labelled cluster of the chart's own points, which is what Group means.
        map(Accessible.Role.CHART_SERIES, UiaIds.CONTROL_GROUP);

        map(Accessible.Role.PROGRESS_BAR, UiaIds.CONTROL_PROGRESS_BAR);
        map(Accessible.Role.SLIDER, UiaIds.CONTROL_SLIDER);
        map(Accessible.Role.SPIN_BUTTON, UiaIds.CONTROL_SPINNER);

        // All four are Edit, and the differences are read from elsewhere: a text area is an edit a
        // client finds more than one line in, a password field answers IsPassword, and a search
        // field says what it is in a phrase because the platform has no word for one.
        map(Accessible.Role.TEXT_FIELD, UiaIds.CONTROL_EDIT);
        map(Accessible.Role.TEXT_AREA, UiaIds.CONTROL_EDIT);
        map(Accessible.Role.PASSWORD_FIELD, UiaIds.CONTROL_EDIT);
        mapAndSayOurOwn(Accessible.Role.SEARCH_FIELD, UiaIds.CONTROL_EDIT);

        map(Accessible.Role.COMBO_BOX, UiaIds.CONTROL_COMBO_BOX);
        map(Accessible.Role.LIST, UiaIds.CONTROL_LIST);
        map(Accessible.Role.LIST_ITEM, UiaIds.CONTROL_LIST_ITEM);
        // UI Automation's Tab is the strip and TabItem is one header, which is the opposite way
        // round from the word "tab" in most other vocabularies and is worth reading twice.
        map(Accessible.Role.TAB_LIST, UiaIds.CONTROL_TAB);
        map(Accessible.Role.TAB, UiaIds.CONTROL_TAB_ITEM);
        map(Accessible.Role.TAB_PANEL, UiaIds.CONTROL_PANE);

        // The picker is a cluster of real controls -- rails, a preview, a field -- so Group rather
        // than Custom: a client should walk into it, not be told it is one opaque thing.
        map(Accessible.Role.COLOR_CHOOSER, UiaIds.CONTROL_GROUP);

        // What the walk publishes for a widget that declared no role. Custom rather than Pane so
        // that it is visibly a gap when one appears, and no phrase, because there is nothing
        // truthful to say about it.
        map(Accessible.Role.UNKNOWN, UiaIds.CONTROL_CUSTOM);
    }

    /**
     * @param role a role the toolkit publishes
     * @return its UI Automation control type
     *         For a role with no reading, which the constants test keeps from shipping, the
     *         platform's own {@code Custom}: the same answer the other two platforms give for a
     *         gap, and never an exception inside a provider a client is calling
     */
    static int of(Accessible.Role role) {
        Integer type = TYPE.get(role);
        return type == null ? UiaIds.CONTROL_CUSTOM : type;
    }

    /**
     * @param role a role the toolkit publishes
     * @return the key of the phrase to answer {@code LocalizedControlType} with, or {@code null}
     *         where the platform's own word for the control type is the right one
     */
    /**
     * @param role a role
     * @return whether this bridge speaks its own phrase for it, because UI Automation's word is
     *         absent or wrong. A role not in this set takes the platform's word and says nothing
     *         extra, which is what makes a reader sound like every other application on the machine
     */
    static boolean speaksOurOwnPhrase(Accessible.Role role) {
        return SPEAKS_OUR_OWN_PHRASE.contains(role);
    }

    /** @return every role that answers {@code LocalizedControlType} with a phrase of its own */
    static Set<Accessible.Role> rolesWithAPhraseOfTheirOwn() {
        return EnumSet.copyOf(SPEAKS_OUR_OWN_PHRASE);
    }
}
