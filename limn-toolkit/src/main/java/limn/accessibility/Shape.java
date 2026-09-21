package limn.accessibility;

import java.util.Objects;

/**
 * What a published node <i>is</i>, read off its role and its facets and never declared: a menu
 * row, a grid cell, a row of a selection, the owner of a popup, a toggle, a value, a text, a
 * pressable leaf, a sentence, or a surface (ADR 045). A widget publishes facts through the
 * builder as it always did; this is the one place that says which of the ten shapes those facts
 * add up to, and it is the name a widget-side helper, a per-shape contract and a bridge adapter
 * all refer to. A node that is none of them is {@link #UNCLASSIFIED}, which the gallery's ratchet
 * refuses, so that a new widget cannot publish a combination nothing downstream has a name for.
 *
 * <p><b>Why derived and not a field (decision 89).</b> A field on the node would be one more thing
 * for three platform tables to translate and for a reader on a guest to be shown, and it could
 * disagree with the facets beside it. A derivation costs the bridges nothing, is exact by
 * construction, and is tested in the model; what a field would have caught — a widget that
 * declares one shape and publishes another — the shape's contract catches instead.
 *
 * <p><b>The order below is the rule (decision 90, amended by what the gallery published).</b>
 * Each shape is claimed by a facet <i>or by the closed family of roles that implies that facet</i>,
 * and a node with two candidates takes the first that matches, reading the constants top to
 * bottom. The role families are there so that a shape does not change with state: a disabled
 * button loses its verbs to the walk and an indeterminate progress bar has no value, and each is
 * still what it was. Where a facet and a role disagree the earlier shape wins, which is how:
 * <ol>
 *   <li>a check menu item carrying a toggle, and a menu title carrying a popup, are {@link #MENU}
 *       rows (the family comes before any facet, decision 94);</li>
 *   <li>a table's row, a selection member without a cell, is a {@link #ROWS} member under a
 *       {@link #GRID} container, and a calendar's week row, which is in no selection, is a
 *       {@code GRID} row;</li>
 *   <li>a widget hung under a synthetic row (decision 3) keeps its own shape: the
 *       {@link CellFacet} the table writes onto it is its <i>position</i>, which a grid adapter
 *       reads on any node, and not its shape — a switch in a cell is a {@link #TOGGLE};</li>
 *   <li>a combo box, which carries a value (its index in its options), is the
 *       {@link #POPUP_OWNER} of its list, because the popup is decided ahead of the value;</li>
 *   <li>a search field, which offers {@code PRESS}, is a {@link #TEXT}, because the text is decided
 *       ahead of the leaf.</li>
 * </ol>
 * The role enum stays closed and nothing here adds to it. Nothing here reads a widget: the
 * classification is over the published node alone, so the same function serves the toolkit's
 * tests, the demo's gallery ratchet and every bridge.
 */
public enum Shape {

    /**
     * A menu bar, a menu, or a row of one: {@code MENU_BAR}, {@code MENU}, {@code MENU_ITEM},
     * {@code CHECK_MENU_ITEM}, {@code RADIO_MENU_ITEM}. Decided by the role family alone (decision
     * 94), because the family never appears outside a menu and its rows carry the facets of other
     * shapes (a toggle, a popup, a selection membership) without being those shapes to a reader.
     */
    MENU,

    /**
     * A table and its grid: the {@code TABLE} (or any node carrying a {@link TableFacet}), its
     * {@code COLUMN_HEADER}s and {@code CELL}s, and a {@code ROW} that is in no selection. A grid
     * is rows whose members carry cells, so a table's selectable row is a {@link #ROWS} member
     * under a {@code GRID} container, and the grid's rules (a cell is found by its facet,
     * decision 8; a header sorts) are added on top of the rows' rules. A cell facet on a node of
     * another role is that node's position in the grid, not its shape.
     */
    GRID,

    /**
     * A container that holds a selection and the members that are in it: a {@code LIST}, a
     * {@code TREE}, a {@code TAB_LIST}, a {@code RADIO_GROUP}, a table's or a calendar's rows;
     * a {@code LIST_ITEM}, a {@code TREE_ITEM}, a {@code TAB}, a {@code RADIO_BUTTON}, a segment,
     * a selectable {@code ROW}; and any other node carrying a {@link SelectionFacet} or a
     * {@link SelectionItemFacet}. What the reader does to a row — select, add, deselect, focus,
     * press, expand, collapse, scroll into view — is one set of rules (decisions 10, 11, 20, 79,
     * 80, 81), whichever widget publishes the row.
     */
    ROWS,

    /**
     * A node that opens something: {@code HAS_POPUP} on it, an {@link ExpandFacet}, or the
     * {@code COMBO_BOX} role. A combo box, a date field's or a date picker's group, a colour
     * picker button, a context region, a tab strip's overflow button, and a button that discloses
     * content in place (a calendar's month title). What it opens is described where it lives
     * (ADR 039 §1.11) and gated by the layer it opens in (§1.13).
     */
    POPUP_OWNER,

    /**
     * A node with a {@link ToggleFacet}, or a {@code CHECK_BOX}, a {@code SWITCH}, a
     * {@code TOGGLE_BUTTON}, a {@code CHART_SERIES}.
     */
    TOGGLE,

    /**
     * A node with a {@link ValueFacet}, or a {@code SLIDER}, a {@code SPIN_BUTTON}, a
     * {@code PROGRESS_BAR} (also while indeterminate, when it carries no value), a
     * {@code SCROLL_BAR}, a {@code SPLITTER}: a date field's segment and a colour picker's rail
     * are spin buttons and sliders.
     */
    VALUE,

    /**
     * A node with a {@link TextFacet}, or a {@code TEXT_FIELD}, a {@code TEXT_AREA}, a
     * {@code PASSWORD_FIELD}, a {@code SEARCH_FIELD}.
     */
    TEXT,

    /**
     * A leaf that can be pressed and is nothing else: a {@code BUTTON}, or any node offering
     * {@code PRESS} that no earlier shape claimed.
     */
    LEAF_ACTION,

    /** A sentence a reader reads and cannot act on: a {@code LABEL}, a {@code HEADING}, an {@code ALERT}. */
    STATIC,

    /**
     * A surface with a role and none of the above: a window, a dialog, a group, a scroll pane, a
     * tool bar, a tab panel, a split pane, a canvas, an image, a video, a chart, a separator, a
     * colour chooser. The walk's free verbs and, on a scroll pane, the {@link ScrollFacet} are all
     * it carries.
     */
    SURFACE,

    /**
     * No shape: only a node whose role is {@code UNKNOWN}, which the walk already refuses to
     * publish. Kept so that the classification is total and the gallery ratchet has something to
     * refuse.
     */
    UNCLASSIFIED;

    /**
     * Classifies a published node by the order the constants are declared in.
     *
     * @param node a node of a published tree
     * @return its shape; {@link #UNCLASSIFIED} only for an {@code UNKNOWN} role
     */
    public static Shape of(AccessibleNode node) {
        Objects.requireNonNull(node, "node");
        Accessible.Role role = node.role();
        if (role == Accessible.Role.UNKNOWN) {
            return UNCLASSIFIED;
        }
        if (isMenuRole(role)) {
            return MENU;
        }
        if (isGrid(node, role)) {
            return GRID;
        }
        if (node.selection() != null || node.selectionItem() != null || isRowsRole(role)) {
            return ROWS;
        }
        if (node.has(Accessible.State.HAS_POPUP) || node.expand() != null
                || role == Accessible.Role.COMBO_BOX) {
            return POPUP_OWNER;
        }
        if (node.toggle() != null || isToggleRole(role)) {
            return TOGGLE;
        }
        if (node.value() != null || isValueRole(role)) {
            return VALUE;
        }
        if (node.text() != null || isTextRole(role)) {
            return TEXT;
        }
        ActionFacet actions = node.actions();
        if (role == Accessible.Role.BUTTON
                || (actions != null && actions.has(Accessible.Action.PRESS))) {
            return LEAF_ACTION;
        }
        if (isStaticRole(role)) {
            return STATIC;
        }
        return SURFACE;
    }

    /**
     * Whether a role belongs to the menu family, which decides {@link #MENU} before any facet.
     *
     * @param role a role
     * @return true for {@code MENU_BAR}, {@code MENU}, {@code MENU_ITEM}, {@code CHECK_MENU_ITEM}
     *     and {@code RADIO_MENU_ITEM}
     */
    public static boolean isMenuRole(Accessible.Role role) {
        return switch (role) {
            case MENU_BAR, MENU, MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM -> true;
            default -> false;
        };
    }

    private static boolean isGrid(AccessibleNode node, Accessible.Role role) {
        return switch (role) {
            case TABLE, COLUMN_HEADER, CELL -> true;
            case ROW -> node.selectionItem() == null;
            default -> node.table() != null;
        };
    }

    private static boolean isRowsRole(Accessible.Role role) {
        return switch (role) {
            case LIST, TREE, TAB_LIST, RADIO_GROUP, LIST_ITEM, TREE_ITEM, TAB, RADIO_BUTTON -> true;
            default -> false;
        };
    }

    private static boolean isToggleRole(Accessible.Role role) {
        return switch (role) {
            case CHECK_BOX, SWITCH, TOGGLE_BUTTON, CHART_SERIES -> true;
            default -> false;
        };
    }

    private static boolean isValueRole(Accessible.Role role) {
        return switch (role) {
            case SLIDER, SPIN_BUTTON, PROGRESS_BAR, SCROLL_BAR, SPLITTER -> true;
            default -> false;
        };
    }

    private static boolean isTextRole(Accessible.Role role) {
        return switch (role) {
            case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD -> true;
            default -> false;
        };
    }

    private static boolean isStaticRole(Accessible.Role role) {
        return switch (role) {
            case LABEL, HEADING, ALERT -> true;
            default -> false;
        };
    }
}
