package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each of the toolkit's roles is called on this platform.
 *
 * <p><b>This table holds symbol names, not strings.</b> On the other two platforms a role is a
 * number and a bridge can carry the number; on this one it is an {@code NSString} global exported by
 * AppKit, and §2.2 resolves it with {@code dlsym}. So the entry for {@code BUTTON} is
 * {@code "NSAccessibilityButtonRole"} and never {@code "AXButton"}: the framework says what the
 * string is, and a build that hard-coded it would still compile on the day AppKit changed it.
 * {@code scripts/a11y/macos/dump-appkit-constants.swift} is the reader, {@code AxConstantsTest} is
 * the assertion, and between them no symbol can enter this file without having been seen exported.
 *
 * <p><b>AppKit says a control's identity in two halves, and this is the platform where the second
 * half does the most work.</b> A subrole narrows a role — {@code AXTextField} plus
 * {@code AXSecureTextField} is a password field, {@code AXCheckBox} plus {@code AXSwitch} is a
 * switch — so several of this toolkit's roles that would collapse into one word elsewhere keep
 * their distinction here for free. Where AppKit has neither a role nor a subrole for something, the
 * third column is a {@code roleDescription} key: the phrase VoiceOver speaks after the name,
 * resolved under the node's own locale at the moment the attribute is answered, which is why this
 * file names the key and not the phrase.
 *
 * <p><b>Where a role degrades, the note says so.</b> A bridge that quietly maps two different
 * things to one word is how a screen reader ends up describing a chart as a group, and §1.12's rule
 * is that every such choice is visible in one file per platform.
 */
final class AxRoles {

    private AxRoles() {
    }

    /** Role symbol, subrole symbol or {@code null}, and a role-description key or {@code null}. */
    record Mapping(String roleSymbol, String subroleSymbol, String roleDescriptionKey) {
    }

    private static final Map<Accessible.Role, Mapping> BY_ROLE = new EnumMap<>(Accessible.Role.class);

    private static void map(Accessible.Role role, String roleSymbol) {
        BY_ROLE.put(role, new Mapping(roleSymbol, null, null));
    }

    private static void map(Accessible.Role role, String roleSymbol, String subroleSymbol) {
        BY_ROLE.put(role, new Mapping(roleSymbol, subroleSymbol, null));
    }

    private static void described(Accessible.Role role, String roleSymbol, String roleDescriptionKey) {
        BY_ROLE.put(role, new Mapping(roleSymbol, null, roleDescriptionKey));
    }

    static {
        // The window itself. In practice a bridge never vends this one: §2.2 elides the window root
        // because AppKit already vends the window, and a second one would be announced twice. It is
        // mapped anyway, because a native popup IS its own NSWindow and because a table with a hole
        // in it is a table a reader has to reason about.
        map(Accessible.Role.WINDOW, "NSAccessibilityWindowRole", "NSAccessibilityStandardWindowSubrole");

        // An in-scene dialog has no NSWindow, so it is a group carrying AXModal rather than a
        // window: giving it AXWindow would offer a client AXRaise and a close button over an
        // overlay that has neither. AppKit's dialog subrole is the honest half, and it is a subrole
        // of a group here rather than of a window on purpose.
        map(Accessible.Role.DIALOG, "NSAccessibilityGroupRole", "NSAccessibilityDialogSubrole");
        // AppKit has no alert role and no alert subrole. What a client learns "read this now" from
        // is the announcement, not the role, so the word is ours.
        described(Accessible.Role.ALERT, "NSAccessibilityGroupRole", "alert");

        map(Accessible.Role.GROUP, "NSAccessibilityGroupRole");
        map(Accessible.Role.SCROLL_PANE, "NSAccessibilityScrollAreaRole");
        map(Accessible.Role.SCROLL_BAR, "NSAccessibilityScrollBarRole");
        map(Accessible.Role.SPLIT_PANE, "NSAccessibilitySplitGroupRole");
        map(Accessible.Role.SPLITTER, "NSAccessibilitySplitterRole");

        map(Accessible.Role.TOOL_BAR, "NSAccessibilityToolbarRole");
        map(Accessible.Role.MENU_BAR, "NSAccessibilityMenuBarRole");
        map(Accessible.Role.MENU, "NSAccessibilityMenuRole");
        map(Accessible.Role.MENU_ITEM, "NSAccessibilityMenuItemRole");
        // All three share AXMenuItem, because AppKit has one menu-item role and says the rest
        // elsewhere: a check item answers AXValue, a radio item is in a single-selection group.
        map(Accessible.Role.CHECK_MENU_ITEM, "NSAccessibilityMenuItemRole");
        map(Accessible.Role.RADIO_MENU_ITEM, "NSAccessibilityMenuItemRole");
        // The degradation this platform forces, and it is worth naming because the obvious symbol
        // is not there: AppKit exports no separator subrole (§12.3), and AXSplitter is a thing the
        // user drags, which a menu separator is not. AXMenuItem with a phrase would be worse still,
        // because a client would offer it as something to pick. AXGroup with nothing in it is the
        // honest shape -- a divider a reader can skip past.
        described(Accessible.Role.SEPARATOR, "NSAccessibilityGroupRole", "separator");

        map(Accessible.Role.BUTTON, "NSAccessibilityButtonRole");
        // AppKit's toggle subrole exists exactly for this, and it is why the toolkit keeps
        // TOGGLE_BUTTON separate from BUTTON at all.
        map(Accessible.Role.TOGGLE_BUTTON, "NSAccessibilityButtonRole", "NSAccessibilityToggleSubrole");
        map(Accessible.Role.CHECK_BOX, "NSAccessibilityCheckBoxRole");
        // §1.12's case, and the platform that makes it cheapest: a check box with the switch
        // subrole is what AppKit's own switches are.
        map(Accessible.Role.SWITCH, "NSAccessibilityCheckBoxRole", "NSAccessibilitySwitchSubrole");
        map(Accessible.Role.RADIO_BUTTON, "NSAccessibilityRadioButtonRole");
        map(Accessible.Role.RADIO_GROUP, "NSAccessibilityRadioGroupRole");

        map(Accessible.Role.LABEL, "NSAccessibilityStaticTextRole");
        // AppKit has no heading role and no heading level. Static text plus the word is all this
        // platform can be told, and it is less than the other two get: UI Automation carries a
        // HeadingLevel property and AT-SPI2 has ROLE_HEADING.
        described(Accessible.Role.HEADING, "NSAccessibilityStaticTextRole", "heading");
        map(Accessible.Role.IMAGE, "NSAccessibilityImageRole");
        // Group and not image: a client that treats a video as an image will try to describe a
        // still that does not exist. The phrase carries what the role cannot.
        described(Accessible.Role.VIDEO, "NSAccessibilityGroupRole", "video");
        described(Accessible.Role.CANVAS, "NSAccessibilityGroupRole", "drawing");
        described(Accessible.Role.CHART, "NSAccessibilityGroupRole", "chart");
        // A series is a labelled cluster of the chart's own points, which is what a group means.
        map(Accessible.Role.CHART_SERIES, "NSAccessibilityGroupRole");

        map(Accessible.Role.PROGRESS_BAR, "NSAccessibilityProgressIndicatorRole");
        map(Accessible.Role.SLIDER, "NSAccessibilitySliderRole");
        map(Accessible.Role.SPIN_BUTTON, "NSAccessibilityIncrementorRole");

        map(Accessible.Role.TEXT_FIELD, "NSAccessibilityTextFieldRole");
        map(Accessible.Role.TEXT_AREA, "NSAccessibilityTextAreaRole");
        // Two subroles AppKit exports for exactly these, which is why neither needs a phrase.
        map(Accessible.Role.PASSWORD_FIELD, "NSAccessibilityTextFieldRole",
                "NSAccessibilitySecureTextFieldSubrole");
        map(Accessible.Role.SEARCH_FIELD, "NSAccessibilityTextFieldRole",
                "NSAccessibilitySearchFieldSubrole");

        map(Accessible.Role.COMBO_BOX, "NSAccessibilityComboBoxRole");
        map(Accessible.Role.LIST, "NSAccessibilityListRole");
        map(Accessible.Role.LIST_ITEM, "NSAccessibilityRowRole");
        map(Accessible.Role.TAB_LIST, "NSAccessibilityTabGroupRole");
        // A tab is a radio button with the tab-button subrole here, not a control type of its own:
        // that is what AppKit's own NSTabView vends, and matching it is what makes VoiceOver
        // navigate the tabs the way a user of this platform already expects.
        map(Accessible.Role.TAB, "NSAccessibilityRadioButtonRole", "NSAccessibilityTabButtonSubrole");
        map(Accessible.Role.TAB_PANEL, "NSAccessibilityGroupRole");

        map(Accessible.Role.COLOR_CHOOSER, "NSAccessibilityColorWellRole");

        // ADR 041 §7. A row carries the table-row subrole so VoiceOver reads it as one of a table's
        // rows and not as a list's; a cell is AppKit's own cell; and a column header is what
        // NSTableHeaderView vends for its own cells, a button with the sort-button subrole, which
        // is what makes VoiceOver offer "sort" on it. All four read off the platform on 2026-09-08.
        map(Accessible.Role.TABLE, "NSAccessibilityTableRole");
        map(Accessible.Role.COLUMN_HEADER, "NSAccessibilityButtonRole",
                "NSAccessibilitySortButtonSubrole");
        map(Accessible.Role.ROW, "NSAccessibilityRowRole", "NSAccessibilityTableRowSubrole");
        map(Accessible.Role.CELL, "NSAccessibilityCellRole");
        // ADR 044 §4. An outline, and a row with the outline-row subrole, which is what
        // NSOutlineView vends; both constants are in the AppKit dump taken for the table's pass.
        // The disclosure a native outline row answers beside them (AXDisclosing, AXDisclosureLevel,
        // AXDisclosedByRow, AXDisclosedRows) is AxGrid's since 2026-09-15.
        map(Accessible.Role.TREE, "NSAccessibilityOutlineRole");
        map(Accessible.Role.TREE_ITEM, "NSAccessibilityRowRole", "NSAccessibilityOutlineRowSubrole");
        map(Accessible.Role.UNKNOWN, "NSAccessibilityUnknownRole");
    }

    /**
     * @param role the toolkit's role
     * @return how this platform is told about it, or {@code null} for a role with no row here.
     *         {@link AxRolesTest} refuses a build in which one exists, so at run time this is the
     *         same answer the other two platforms give for the same gap: the platform's own
     *         "unknown", never an exception in a callback the platform is standing on
     */
    static Mapping of(Accessible.Role role) {
        return BY_ROLE.get(role);
    }

    /**
     * The role a table's column elements answer (M4): no toolkit role stands for a column, so it is not
     * a row of the table above, and a native NSTableView's columns answer it (read on the macOS 26.6.2
     * guest, 2026-09-15, {@code scripts/a11y/macos/table-probe.swift}).
     */
    static final String COLUMN_ROLE_SYMBOL = "NSAccessibilityColumnRole";

    /** Every symbol this table names, for the test that checks them against AppKit's own export list. */
    static java.util.Set<String> symbols() {
        java.util.Set<String> symbols = new java.util.LinkedHashSet<>();
        symbols.add(COLUMN_ROLE_SYMBOL);
        for (Mapping mapping : BY_ROLE.values()) {
            symbols.add(mapping.roleSymbol());
            if (mapping.subroleSymbol() != null) symbols.add(mapping.subroleSymbol());
        }
        return symbols;
    }
}
