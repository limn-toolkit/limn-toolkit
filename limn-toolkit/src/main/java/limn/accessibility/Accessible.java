package limn.accessibility;

/**
 * The vocabulary the accessible tree is written in: what a node <em>is</em>, what is true of it,
 * what can be done to it, and where its name came from.
 *
 * <p>Every enum here is closed, and closed for a reason that is not tidiness. The three platform
 * accessibility interfaces disagree about the vocabulary, so each bridge owns a table indexed by
 * ordinal; a constant with no truthful row in one of those tables is a lie a client will act on.
 * A new constant therefore costs three tables, and a bridge that would have to invent a platform
 * name for one is an argument for reusing a constant that already exists.
 *
 * <p>This class is a namespace and is never instantiated.
 */
public final class Accessible {

    private Accessible() {
    }

    /**
     * What a node is. Not a string and not a per-platform constant: each bridge maps this to its
     * own platform's control type, so every degradation is visible in one file per platform.
     *
     * <p>Three constants exist only because a platform would otherwise be told something untrue.
     * {@link #SWITCH} is not {@link #CHECK_BOX}, because the three platforms have three different
     * answers for it and a single constant would collapse them. {@link #HEADING} exists because a
     * label carrying a title's typographic role is a heading and every platform has one.
     * {@link #RADIO_MENU_ITEM} exists because a single-selection menu group built out of check
     * items reports independent checkboxes, which is a lie this constant retires.
     */
    public enum Role {
        /** A top-level window. */
        WINDOW,
        /** A dialog, in its own window or as an in-scene overlay. */
        DIALOG,
        /** A message that demands attention rather than a dialog that awaits input. */
        ALERT,
        /** A grouping box with no behaviour of its own; the role a described container takes. */
        GROUP,
        /** A scrolling viewport. */
        SCROLL_PANE,
        /** A scroll bar. */
        SCROLL_BAR,
        /** A pane split by a draggable divider. */
        SPLIT_PANE,
        /** The draggable divider of a {@link #SPLIT_PANE}. */
        SPLITTER,
        /** A bar of commands. */
        TOOL_BAR,
        /** The bar of menu titles along the top of a window. */
        MENU_BAR,
        /** An open menu: one column of a cascade. */
        MENU,
        /** A row of a menu that runs a command. */
        MENU_ITEM,
        /** A row of a menu that carries an independent checked state. */
        CHECK_MENU_ITEM,
        /** A row of a menu that is one option of a single-selection group. */
        RADIO_MENU_ITEM,
        /** A rule between groups of items. */
        SEPARATOR,
        /** A push button. */
        BUTTON,
        /** A button that stays pressed. */
        TOGGLE_BUTTON,
        /** A checkbox. */
        CHECK_BOX,
        /** A switch: a checkbox whose platform presentation is an on/off control. */
        SWITCH,
        /** One option of a single-selection group. */
        RADIO_BUTTON,
        /** The group a {@link #RADIO_BUTTON} belongs to. */
        RADIO_GROUP,
        /** Static text. */
        LABEL,
        /** Static text that titles the section under it. */
        HEADING,
        /** A picture. */
        IMAGE,
        /** A video surface. */
        VIDEO,
        /** A surface a widget draws itself, whose content the tree cannot describe. */
        CANVAS,
        /** A chart. */
        CHART,
        /** One data series of a {@link #CHART}. */
        CHART_SERIES,
        /** A progress indicator. */
        PROGRESS_BAR,
        /** A slider. */
        SLIDER,
        /** A number field with increment and decrement. */
        SPIN_BUTTON,
        /** A single-line text field. */
        TEXT_FIELD,
        /** A multi-line text field. */
        TEXT_AREA,
        /** A text field whose content is masked. */
        PASSWORD_FIELD,
        /** A text field that runs a search. */
        SEARCH_FIELD,
        /** A control that chooses one option from a list it can open. */
        COMBO_BOX,
        /** A list. */
        LIST,
        /** One row of a {@link #LIST}. */
        LIST_ITEM,
        /** The strip of tabs above a tabbed pane. */
        TAB_LIST,
        /** One tab of a {@link #TAB_LIST}. */
        TAB,
        /** The content a {@link #TAB} selects. */
        TAB_PANEL,
        /** A colour picker. */
        COLOR_CHOOSER,
        /** A grid of rows and columns over data. */
        TABLE,
        /** The header cell of one column of a {@link #TABLE}. */
        COLUMN_HEADER,
        /** One data row of a {@link #TABLE}. */
        ROW,
        /** One cell of a {@link #ROW}. */
        CELL,
        /** An outline of rows at a depth, where a row can open onto its children. */
        TREE,
        /** One row of a {@link #TREE}. */
        TREE_ITEM,
        /**
         * Nothing better applies. A focusable node that publishes this is a defect: the toolkit
         * logs a warning naming the class, once per class.
         */
        UNKNOWN
    }

    /**
     * What is true of a node. Only what all three platforms carry as a flag is here; anything a
     * facet expresses is <em>derived</em> from that facet rather than stored twice, which is why
     * {@link #CHECKED}, {@link #MIXED}, {@link #EXPANDED}, {@link #EXPANDABLE}, {@link #SELECTED}
     * and {@link #READ_ONLY} are on this list and are still not a widget's to set directly.
     *
     * <p>Three pairs are deliberately separate and are never conflated. {@link #ENABLED} against
     * {@link #READ_ONLY}: every platform separates them, and merging them makes a disabled field
     * announce as read-only and leaves a genuinely read-only enabled field inexpressible.
     * {@link #VISIBLE} against {@link #SHOWING}: offscreen is not invisible, and a scrolled-away
     * list row is visible and not showing. And {@link #ENABLED} against {@link #FOCUSABLE}:
     * enabled is what may be operated, focusable is what the Tab key reaches, and a label is the
     * first and not the second.
     */
    public enum State {
        /**
         * This node may be operated. Inherited: a node is enabled only if it and every ancestor
         * are, and a node outside the topmost modal overlay is not, whatever its own flag says.
         */
        ENABLED,
        /**
         * The Tab key can land here. Inherited the same way {@link #ENABLED} is, so the set of
         * nodes carrying this is exactly the set the scene's focus traversal can reach.
         */
        FOCUSABLE,
        /** This node holds the keyboard focus. */
        FOCUSED,
        /** This node is not hidden. Inherited: a descendant of a hidden widget is not visible. */
        VISIBLE,
        /** This node has pixels on screen: visible, laid out, and not clipped fully away. */
        SHOWING,
        /** This node can be selected. */
        SELECTABLE,
        /** This node is selected. Derived from {@link SelectionItemFacet}. */
        SELECTED,
        /** This node is checked. Derived from {@link ToggleFacet}. */
        CHECKED,
        /** This node is neither checked nor unchecked. Derived from {@link ToggleFacet}. */
        MIXED,
        /** This node is being pressed right now. */
        PRESSED,
        /** This node is expanded. Derived from {@link ExpandFacet}. */
        EXPANDED,
        /**
         * This node opens and closes. Derived from the <em>presence</em> of an {@link ExpandFacet},
         * on every node that carries one (a menu title, a submenu row, a combo box, a tree row, the
         * calendar's title), so a reader on the one platform that speaks "collapsed" as a state of
         * its own can be told the difference between a row that is closed and a row that cannot
         * open.
         */
        EXPANDABLE,
        /** Operating this node opens a menu or a list. */
        HAS_POPUP,
        /** This node's value may be read and not written. Derived from {@link TextFacet}. */
        READ_ONLY,
        /** This node's value may be edited. */
        EDITABLE,
        /** This node's text is more than one line. */
        MULTI_LINE,
        /** This node's text is masked and must never be spoken. */
        PASSWORD,
        /** This node's value has failed validation. */
        INVALID,
        /** This node must be filled in. */
        REQUIRED,
        /** This node is working and its value is not yet meaningful. */
        BUSY,
        /** This node blocks input to everything outside it. */
        MODAL,
        /** This node is the active descendant of its container. */
        ACTIVE,
        /** This node is what Enter activates when nothing else has the focus. */
        DEFAULT,
        /** This node's value runs left to right or right to left rather than up and down. */
        HORIZONTAL,
        /** This node's value runs up and down. */
        VERTICAL
    }

    /**
     * What an assistive technology can ask of a node.
     *
     * <p>The list is in two parts and the split is not tidiness. The first thirteen are
     * <b>parameterless verbs</b>, and those are what a node publishes in its {@link ActionFacet}.
     * The last four take an {@link Argument} and are <b>never</b> published in that list, because
     * one of the three platforms cannot express a parameterised action in its action list at all
     * and puts these on its value and text interfaces instead. What advertises a parameterised
     * setter is a facet's presence: a node with a writable {@link ValueFacet} is settable, a node
     * with a {@link TextFacet} and without {@link State#READ_ONLY} is editable.
     *
     * <p><b>A node accepts exactly the parameterless verbs it publishes, plus the setters its
     * writable facets imply</b>: the published snapshot is the only synchronous authority a bridge
     * has, so a verb a widget accepts but does not publish is one every platform refuses before it
     * is posted. A widget that answers a synonym publishes the synonym. <b>A facet implies its
     * setter only on a node that is {@link State#ENABLED}</b>: a disabled field keeps its true
     * writability and accepts no text, rather than being published read-only.
     * {@link AccessibleNode#accepts} is the rule in one call.
     *
     * <p>Every one of the seventeen is dispatched the same way, through the one inbound call a
     * bridge makes.
     */
    public enum Action {
        /** Activate this node, as a click would. */
        PRESS,
        /** Flip this node's checked state. */
        TOGGLE,
        /** Open this node. */
        EXPAND,
        /** Close this node. */
        COLLAPSE,
        /**
         * Make this node its container's selection, as a click would: whatever was selected
         * before is not any more, in a single- or a multi-select container alike. Published on a
         * member of a container whose selection mode allows it. Moves the cursor onto the node.
         */
        SELECT,
        /**
         * Add this node to its container's selection, keeping what is already selected: the
         * platform's "add to selection", which is only meaningful where more than one member may
         * be selected. Published on an <em>unselected</em> member of a multi-select container, and
         * nowhere else.
         */
        ADD_TO_SELECTION,
        /**
         * Remove this node from its container's selection, keeping the rest. Published on a
         * <em>selected</em> member of a multi-select container.
         */
        DESELECT,
        /** Open this node's menu. */
        SHOW_MENU,
        /** Move this node's value one step up. */
        INCREMENT,
        /** Move this node's value one step down. */
        DECREMENT,
        /** Scroll this node into view. */
        SCROLL_INTO_VIEW,
        /**
         * Move the keyboard focus here. On a widget it is the walk's free verb, offered on every
         * focusable node. On an item inside a container it means <em>move the cursor here without
         * selecting</em>, so it is published only where the cursor and the selection are separate
         * things — a tree row, a table cell, a calendar day, a date segment, a menu row, a combo
         * option — and refused where they are one (a list row, a segment), because there a focus
         * that selected would be a select by another name.
         */
        FOCUS,
        /** Dismiss this node. */
        CANCEL,
        /** Set this node's numeric value; takes an {@link Argument.OfValue}. */
        SET_VALUE,
        /** Replace this node's text; takes an {@link Argument.OfText}. */
        SET_TEXT,
        /** Move this node's caret; takes an {@link Argument.OfRange} whose ends are equal. */
        SET_CARET,
        /** Set this node's text selection; takes an {@link Argument.OfRange}. */
        SET_SELECTION;

        /**
         * Whether this verb takes no argument and is therefore publishable in an
         * {@link ActionFacet}.
         *
         * @return {@code true} for the thirteen parameterless verbs
         */
        public boolean isParameterless() {
            return ordinal() < SET_VALUE.ordinal();
        }
    }

    /**
     * What a parameterised {@link Action} carries. A sealed value with no widget and no callback
     * in it, because it crosses from a platform thread into a snapshot a reader may hold for
     * minutes.
     */
    public sealed interface Argument {

        /** The argument of every parameterless verb. */
        Argument NONE = new None();

        /**
         * The number a {@code SET_VALUE} carried, or {@code NaN} when the argument is not an
         * {@link OfValue} or its value is not finite. One check for every widget with a value:
         * a request that is not a finite number is refused before it reaches a clamp, because a
         * clamp passes {@code NaN} straight through, and this is where every value verb makes
         * that refusal rather than each spelling the two tests for itself.
         *
         * @param arg the verb's argument
         * @return the finite value asked for, or {@code NaN}
         */
        static double finiteValueOf(Argument arg) {
            return arg instanceof OfValue of && Double.isFinite(of.value()) ? of.value() : Double.NaN;
        }

        /** The argument of a verb that takes nothing. */
        record None() implements Argument {
        }

        /**
         * A number, for {@link Action#SET_VALUE}.
         *
         * @param value the value to set, in the units the node's {@link ValueFacet} publishes
         */
        record OfValue(double value) implements Argument {
        }

        /**
         * A string, for {@link Action#SET_TEXT}.
         *
         * @param text the whole new text; never {@code null}
         */
        record OfText(String text) implements Argument {

            /** @throws NullPointerException if {@code text} is {@code null} */
            public OfText {
                java.util.Objects.requireNonNull(text, "text");
            }
        }

        /**
         * A range of text, for {@link Action#SET_CARET} and {@link Action#SET_SELECTION}. Offsets
         * are UTF-16 code units, the unit the whole model counts in.
         *
         * @param start where the range begins
         * @param end   where it ends; equal to {@code start} for a caret
         */
        record OfRange(int start, int end) implements Argument {
        }
    }

    /** Whether an announcement waits for the assistive technology to finish, or interrupts it. */
    public enum Politeness {
        /** Spoken when whatever is being read finishes. */
        POLITE,
        /** Spoken now, cutting off whatever is being read. */
        ASSERTIVE
    }

    /**
     * Where a node's name came from. It exists because one of the three platforms maps a name
     * that is the control's own text and a name that describes it to <em>different</em>
     * attributes, and a bridge that guesses publishes into the attribute a client walking the
     * tree does not read &mdash; which looks exactly like an empty application rather than an
     * error.
     */
    public enum NameFrom {
        /** The widget's own painted text: a button's caption, a label's string. */
        CONTENT,
        /** Another node, named by a {@link Relation#LABELLED_BY} relation. */
        LABEL,
        /** The widget's tooltip, taken because nothing else supplied a name. */
        TOOLTIP,
        /** A text field's placeholder, taken because nothing else supplied a name. */
        PLACEHOLDER,
        /** The application set it. Always wins over anything a widget derived. */
        EXPLICIT
    }

    /**
     * A link between two nodes that the tree's parent-and-child shape cannot carry.
     *
     * <p>A relation is resolved to the nearest <em>published</em> ancestor of its target and is
     * dropped when there is none: a relation naming a node that was never published is worse than
     * no relation at all, because every platform answers it with an element that does not resolve.
     */
    public enum Relation {
        /** This node's name comes from the target. */
        LABELLED_BY,
        /** This node is the name of the target; the mirror of {@link #LABELLED_BY}. */
        LABEL_FOR,
        /** This node's description comes from the target. */
        DESCRIBED_BY,
        /** Operating this node changes the target. */
        CONTROLLER_FOR,
        /** This node is changed by the target; the mirror of {@link #CONTROLLER_FOR}. */
        CONTROLLED_BY,
        /** This node is one member of the target group. */
        MEMBER_OF,
        /** This node is the popup the target opened. */
        POPUP_FOR
    }
}
