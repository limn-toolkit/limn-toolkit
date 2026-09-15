package limn.accessibility;

import limn.graphics.Rect;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One element of an {@link AccessibleTree}: a role, a name with its provenance, a description, a
 * language, a set of states, a rectangle, relations, and zero or more typed facets.
 *
 * <p><b>It is a value and holds no widget.</b> Not a simplification: a client on the other side of
 * a platform boundary can hold one element for minutes, and a node that pinned the widget it was
 * described from would keep a detached subtree alive with nothing on screen referring to it and
 * nothing able to see the leak.
 *
 * <p>Its links to its parent and siblings are <b>indices into its own tree</b> rather than
 * references, because sibling navigation is a first-class operation on one of the three platforms
 * and a children-list model makes every step of it scan a list.
 */
public final class AccessibleNode {

    /** Neither a parent, a child nor a sibling: the value every absent link carries. */
    public static final int NONE = -1;

    private static final AccessibleRelation[] NO_RELATIONS = new AccessibleRelation[0];

    private final long id;
    private final Accessible.Role role;
    private final String name;
    private final Accessible.NameFrom nameFrom;
    private final String description;
    private final Locale locale;
    private final long states;
    private final float x;
    private final float y;
    private final float width;
    private final float height;
    private final AccessibleRelation[] relations;
    private final ToggleFacet toggle;
    private final ValueFacet value;
    private final SelectionFacet selection;
    private final SelectionItemFacet selectionItem;
    private final ExpandFacet expand;
    private final TextFacet text;
    private final ScrollFacet scroll;
    private final WindowFacet window;
    private final TableFacet table;
    private final CellFacet cell;
    private final HierarchyFacet hierarchy;
    private final ActionFacet actions;
    private final int parent;
    private final int firstChild;
    private final int lastChild;
    private final int nextSibling;
    private final int previousSibling;
    private final int selectionContainer;

    AccessibleNode(long id, Accessible.Role role, String name, Accessible.NameFrom nameFrom,
                   String description, Locale locale, long states,
                   float x, float y, float width, float height,
                   AccessibleRelation[] relations,
                   ToggleFacet toggle, ValueFacet value, SelectionFacet selection,
                   SelectionItemFacet selectionItem, ExpandFacet expand, TextFacet text,
                   ScrollFacet scroll, WindowFacet window, TableFacet table, CellFacet cell,
                   HierarchyFacet hierarchy, ActionFacet actions,
                   int parent, int firstChild, int lastChild,
                   int nextSibling, int previousSibling, int selectionContainer) {
        this.id = id;
        this.role = role;
        this.name = name;
        this.nameFrom = nameFrom;
        this.description = description;
        this.locale = locale;
        this.states = states;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.relations = relations == null || relations.length == 0 ? NO_RELATIONS : relations;
        this.toggle = toggle;
        this.value = value;
        this.selection = selection;
        this.selectionItem = selectionItem;
        this.expand = expand;
        this.text = text;
        this.scroll = scroll;
        this.window = window;
        this.table = table;
        this.cell = cell;
        this.hierarchy = hierarchy;
        this.actions = actions;
        this.parent = parent;
        this.firstChild = firstChild;
        this.lastChild = lastChild;
        this.nextSibling = nextSibling;
        this.previousSibling = previousSibling;
        this.selectionContainer = selectionContainer;
    }

    /**
     * @return this node's process-wide identifier, stable across publishes for as long as the
     *         thing it describes stays in the widget tree. Never {@code 0}, which is what an
     *         absent identifier is.
     */
    public long id() {
        return id;
    }

    /** @return what this node is; never {@code null} */
    public Accessible.Role role() {
        return role;
    }

    /**
     * @return this node's name, already resolved under {@link #locale()}; empty when it has none.
     *         Never {@code null}, and never resolved by a reader: a bridge reads this string from
     *         a thread where no locale scope is open, and resolving there would answer in the
     *         process language rather than the subtree's.
     */
    public String name() {
        return name;
    }

    /**
     * @return where {@link #name()} came from; never {@code null}. One platform maps a name that
     *         is the control's own text and a name that describes it to different attributes, and
     *         publishes into exactly one of them.
     */
    public Accessible.NameFrom nameFrom() {
        return nameFrom;
    }

    /** @return this node's longer description, already resolved; empty when it has none */
    public String description() {
        return description;
    }

    /**
     * @return the language this node's text is in; never {@code null}. Per node and not per
     *         process, because a subtree can declare its own: a Hebrew interface holding an
     *         English code pane must reach the screen reader's pronunciation intact.
     */
    public Locale locale() {
        return locale;
    }

    /**
     * @param state the state to test
     * @return whether it is set on this node
     */
    public boolean has(Accessible.State state) {
        return (states & (1L << state.ordinal())) != 0;
    }

    /**
     * @return every state set on this node. Builds a fresh set on each call; {@link
     *         #has(Accessible.State)} is the one to ask in a loop.
     */
    public Set<Accessible.State> states() {
        EnumSet<Accessible.State> set = EnumSet.noneOf(Accessible.State.class);
        for (Accessible.State state : Accessible.State.values()) {
            if (has(state)) {
                set.add(state);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * @return the left edge of this node's box, in its scene's logical points. Already physical
     *         and left-origin in a right-to-left interface as in a left-to-right one, because the
     *         toolkit mirrors by placing rather than by transforming. Converting to the screen is
     *         the reader's, once, from {@link AccessibleTree}'s window stamp.
     */
    public float x() {
        return x;
    }

    /** @return the top edge of this node's box, in its scene's logical points */
    public float y() {
        return y;
    }

    /** @return the width of this node's box, in logical points */
    public float width() {
        return width;
    }

    /** @return the height of this node's box, in logical points */
    public float height() {
        return height;
    }

    /**
     * @return this node's box as a rectangle. Allocates; the four accessors above do not.
     */
    public Rect bounds() {
        return new Rect(x, y, width, height);
    }

    /** @return this node's relations; empty when it has none, and never modifiable */
    public List<AccessibleRelation> relations() {
        return relations.length == 0 ? List.of() : List.of(relations);
    }

    /** @return this node's checked state, or {@code null} when it has none */
    public ToggleFacet toggle() {
        return toggle;
    }

    /** @return this node's numeric value, or {@code null} when it has none */
    public ValueFacet value() {
        return value;
    }

    /** @return this node's selection, or {@code null} when it holds none */
    public SelectionFacet selection() {
        return selection;
    }

    /** @return this node's membership of a selection, or {@code null} when it is in none */
    public SelectionItemFacet selectionItem() {
        return selectionItem;
    }

    /** @return whether this node is open, or {@code null} when it does not open */
    public ExpandFacet expand() {
        return expand;
    }

    /** @return this node's text, or {@code null} when it has none */
    public TextFacet text() {
        return text;
    }

    /** @return this node's scroll position, or {@code null} when it does not scroll */
    public ScrollFacet scroll() {
        return scroll;
    }

    /** @return this node's window, or {@code null} when it is not one */
    public WindowFacet window() {
        return window;
    }

    /** @return this node's grid shape, or {@code null} when it is not a table */
    public TableFacet table() {
        return table;
    }

    /** @return where this node sits in a table, or {@code null} when it is not a cell */
    public CellFacet cell() {
        return cell;
    }

    /** @return where this node stands in an outline, or {@code null} when it is not a row of one */
    public HierarchyFacet hierarchy() {
        return hierarchy;
    }

    /** @return the verbs this node offers, or {@code null} when it offers none */
    public ActionFacet actions() {
        return actions;
    }

    /**
     * Whether this node accepts a verb now, read off the snapshot alone: semantics 5 of the
     * 2026-09-13 pass (ADR 039 §1.5, amended 2026-09-14 and 2026-09-15), in the one place a
     * bridge and a test ask it.
     *
     * <p>A parameterless verb is accepted exactly when this node's {@link ActionFacet} publishes
     * it. A setter is implied by a facet, and only on a node that is
     * {@link Accessible.State#ENABLED} (fix round 2e): {@link Accessible.Action#SET_VALUE} by a
     * {@link ValueFacet} that is not read-only, {@link Accessible.Action#SET_TEXT} by a
     * {@link TextFacet} on a node without {@link Accessible.State#READ_ONLY}, and
     * {@link Accessible.Action#SET_CARET} and {@link Accessible.Action#SET_SELECTION} by any
     * {@link TextFacet}: moving the caret or selecting is reading, which read-only text allows and
     * which every text widget performs through its own caret whether or not it may be written
     * (2026-09-15, correcting the round-2e wording that tied all three to writability). {@code ENABLED} is
     * the operable bit on both of the axes the scene refuses on: the walk clears it on a disabled
     * widget and under a disabled ancestor, on a synthetic child its owner narrowed, and on every
     * node outside the layer that owns input (§1.13). So a disabled text field keeps
     * {@code EDITABLE} and never gains {@code READ_ONLY} (§1.2: the two are never conflated), and
     * still accepts no {@code SET_TEXT}. Allocates nothing.
     *
     * @param action the verb
     * @return whether a platform may post it to this node; a bridge refuses it synchronously
     *         otherwise
     */
    public boolean accepts(Accessible.Action action) {
        return switch (action) {
            case SET_VALUE -> value != null && !value.readOnly() && has(Accessible.State.ENABLED);
            case SET_TEXT -> text != null
                    && !has(Accessible.State.READ_ONLY) && has(Accessible.State.ENABLED);
            case SET_CARET, SET_SELECTION -> text != null && has(Accessible.State.ENABLED);
            default -> actions != null && actions.has(action);
        };
    }

    /** @return the index of this node's parent, or {@link #NONE} at a root */
    public int parent() {
        return parent;
    }

    /** @return the index of this node's first child, or {@link #NONE} when it has none */
    public int firstChild() {
        return firstChild;
    }

    /** @return the index of this node's last child, or {@link #NONE} when it has none */
    public int lastChild() {
        return lastChild;
    }

    /** @return the index of the sibling after this one, or {@link #NONE} when it is the last */
    public int nextSibling() {
        return nextSibling;
    }

    /** @return the index of the sibling before this one, or {@link #NONE} when it is the first */
    public int previousSibling() {
        return previousSibling;
    }

    /**
     * The container whose selection this node is a member of (semantics 1 of the 2026-09-13
     * pass; ADR 039 §1.2, amended 2026-09-14): the nearest ancestor carrying a
     * {@link SelectionFacet}, reached from this node's published parent by climbing only through
     * synthetic ancestors that lack one — so a calendar day belongs to the grid and not to the
     * week row it hangs under, a tab header to the strip, a list row to the list. Resolved once
     * at publish; what the differ addresses {@code SELECTION_CHANGED} to, and what a bridge
     * answers for the member's container and the container's members.
     *
     * @return the container's index, or {@link #NONE} when this node carries no
     *         {@link SelectionItemFacet}, declared itself
     *         {@linkplain SelectionItemFacet#containerless() containerless}, or the climb
     *         reached a widget node or the root without finding a container
     */
    public int selectionContainer() {
        return selectionContainer;
    }

    @Override
    public String toString() {
        return "AccessibleNode[" + id + " " + role + " \"" + name + "\"]";
    }
}
