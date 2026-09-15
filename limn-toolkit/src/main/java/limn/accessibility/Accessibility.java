package limn.accessibility;

import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.i18n.I18n;
import limn.i18n.I18nString;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a widget fills in to say what it is, and the scratch the publish step fills it into.
 *
 * <p><b>One object per scene, reused down every walk and across every frame.</b> A widget's
 * describe hook is handed this and writes into whichever node the walk is currently on; nothing it
 * writes constructs a facet, a string or a set. That is the whole reason the setters take a
 * facet's <em>fields</em> rather than a facet: a record per node per walk would allocate on exactly
 * the frames this design promises allocate nothing, and a blinking caret in a window of two hundred
 * nodes would then be two hundred short-lived objects a second. The records are built once, in the
 * copy, and only for nodes that carry them.
 *
 * <p><b>Two rules a widget author has to know, and they are the same rule twice.</b>
 *
 * <p>First: <em>a name is an {@link I18nString} and is compared by source.</em> Hand over the
 * string object the widget holds, not a resolved string. The comparison is then a reference, a
 * locale and an epoch, and a repaint that changed nothing re-resolves nothing. A subtree that
 * changes language moves the locale, so every name under it is re-resolved exactly once and
 * diffed, with no subscription anywhere.
 *
 * <p>Second: <em>a derived string is never built here.</em> A formatted value, a masked line, a
 * text field's contents have no source to compare, so they are handed over from the widget's own
 * cache together with the counter that cache was filled against — {@link #name(String, long,
 * Accessible.NameFrom)}, {@link #valueText(String, long)}, {@link #text}. The comparison is two
 * {@code long}s and never two strings. A widget that formats a string <em>inside</em> its describe
 * hook stays correct and stops being free: it allocates one string per damaged frame to conclude
 * that nothing changed, which is the defect the publish-cost test exists to catch. The general
 * form, for whoever adds the next one: <b>if a field's comparison allocates, the field needs a
 * cheaper witness, and a monotonic counter on the model is usually it.</b>
 *
 * <p>The publish step drives the rest of this class — the methods that begin and end nodes, apply
 * the inherited state bits and produce the tree. Those are public because the scene is in another
 * package and for no other reason; a widget calls none of them.
 */
public final class Accessibility {

    static {
        // The role-name catalogue registers a bundle when it is initialised, and registering one
        // prepares its tables on the thread that asks -- which I18n requires to be the
        // user-interface thread. A bridge is the wrong first toucher: two of the three answer a
        // platform's questions from the platform's own threads, and one of those loading the
        // catalogue would throw inside a native callback. The walk is user-interface-thread by
        // construction, so it is where the class is forced to load. The state phrases, for the same
        // reason and the same bridge.
        RoleNames.ensureRegistered();
        StateNames.ensureRegistered();
    }

    /**
     * How many differing nodes a single publish reports individually before the difference stops
     * being a list of events and becomes "everything changed".
     *
     * <p>The bound is here rather than in each bridge so that all three get the same guarantee
     * from one implementation, and so that it can be asserted without a platform. Past it the
     * publish emits one {@link AccessibleEvent.Type#INVALIDATED}, which a bridge answers by
     * reconciling whatever it holds against the tree it was just handed — a full sweep, which is
     * stronger than replaying the events that were dropped and is the operation it needs on a
     * rebind anyway. <b>Outside the budget</b>, and handed over after the collapse as after any
     * publish, is the reserved tail (ADR 039 §1.10, amended 2026-09-14; CRIT-3): the per-parent
     * structure changes, the final focus change, the single cursor change, the per-container
     * selection changes and the window activation events — the events a reader is directed by,
     * which the kitchen dialog opening was three events short of losing.
     */
    public static final int EVENT_BUDGET = 256;

    /**
     * How many nodes may move at once before their boxes are reported as one window-level event.
     * A scroll moves every row it shows, and a client is better served by "re-read the window"
     * than by four hundred rectangles.
     */
    private static final int BOUNDS_BUDGET = 32;

    /**
     * Interned identifiers the table starts out holding. Not a ceiling: a walk that needs more
     * pairs than the table holds and finds none it may drop grows the table instead (ADR 039 §1.3,
     * amended 2026-09-14), because dropping a pair a walk is about to ask for again mints it a
     * new identifier, and that is identity churn on every frame for as long as the walk stays
     * that large.
     */
    private static final int INTERN_CAPACITY = 4096;

    /** One node, as the walk builds it. Mutable, reused, and never handed out. */
    private static final class Slot {
        long id;
        Accessible.Role role;
        Accessible.NameFrom nameFrom;
        // A name is either a source to be compared by reference, or a cached string with a
        // witness. Exactly one of nameSource and nameWitness is meaningful; nameText is the
        // resolved answer either way, and is what the published node carries.
        I18nString nameSource;
        long nameWitness;
        Locale nameLocale;
        long nameEpoch;
        String nameText;
        I18nString descriptionSource;
        long descriptionWitness;
        Locale descriptionLocale;
        long descriptionEpoch;
        String descriptionText;
        Locale locale;
        long states;
        long declaredStates;
        float x;
        float y;
        float width;
        float height;
        float originX;
        float originY;
        int parent;
        boolean ignored;
        boolean synthetic;
        long syntheticKey;
        // One past the last slot of a synthetic child's subtree, stamped when it is closed, so a
        // scan over an owner's synthetic children steps from child to child instead of through
        // every cell of every row between them. Meaningless on a widget's own slot.
        int subtreeEnd;
        boolean hosted;
        boolean roleDeclared;
        boolean offScreen;
        boolean disabled;

        int toggle;                     // -1 none, else a ToggleFacet.State ordinal
        boolean hasValue;
        double value;
        double valueMin;
        double valueMax;
        double valueStep;
        String valueText;
        boolean valueReadOnly;
        boolean valueEmpty;
        long valueWitness;
        boolean hasSelection;
        boolean multiSelectable;
        boolean selectionRequired;
        boolean hasSelectionItem;
        boolean selected;
        int positionInSet;
        int sizeOfSet;
        boolean selectionContainerless;
        // The container this member belongs to (semantics 1), as an index into the same walk;
        // resolved by the publish step, never declared, and never compared: it is a function of
        // the facets and the parents, which are.
        int selectionContainer;
        int expand;                     // -1 none, 0 collapsed, 1 expanded
        boolean hasText;
        String text;
        long textWitness;
        int caretOffset;
        ShapedText.Affinity caretAffinity;
        int selectionStart;
        int selectionEnd;
        int lineCount;
        Rect caretRect;
        boolean hasScroll;
        double horizontalPercent;
        double verticalPercent;
        double horizontalViewSize;
        double verticalViewSize;
        boolean horizontallyScrollable;
        boolean verticallyScrollable;
        boolean hasWindow;
        boolean windowModal;
        boolean windowCanMaximize;
        boolean windowCanMinimize;
        WindowFacet.State windowState;
        boolean hasTable;
        int tableRowCount;
        int tableColumnCount;
        boolean hasCell;
        int cellRow;
        int cellColumn;
        boolean hasHierarchy;
        int level;
        int hierarchyRow;
        int hierarchyRowCount;
        int verbs;                      // bit per Accessible.Action ordinal
        // The subset of verbs a container claimed on this widget child (ADR 039 §1.5, amended
        // 2026-09-14): published on the child like the rest, performed by the container. Not
        // compared by differs(): who performs a verb is the walk's routing table, not a fact a
        // reader can see, and the walk records it on every walk whether or not the tree moved.
        int delegated;
        String keyBinding;
        int relationCount;
        Accessible.Relation[] relationKinds = new Accessible.Relation[0];
        Object[] relationTargets = new Object[0];
        long[] relationResolved = new long[0];

        void reset(long id, int parent, Locale locale,
                   float x, float y, float width, float height) {
            this.id = id;
            this.parent = parent;
            this.locale = locale;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.originX = x;
            this.originY = y;
            role = Accessible.Role.GROUP;
            nameFrom = Accessible.NameFrom.CONTENT;
            nameSource = null;
            nameWitness = 0;
            nameLocale = null;
            nameEpoch = 0;
            nameText = "";
            descriptionSource = null;
            descriptionWitness = 0;
            descriptionLocale = null;
            descriptionEpoch = 0;
            descriptionText = "";
            states = 0;
            declaredStates = 0;
            ignored = false;
            synthetic = false;
            syntheticKey = 0;
            subtreeEnd = 0;
            hosted = false;
            roleDeclared = false;
            offScreen = false;
            disabled = false;
            toggle = -1;
            hasValue = false;
            valueReadOnly = false;
            valueEmpty = false;
            value = 0;
            valueMin = 0;
            valueMax = 0;
            valueStep = 0;
            valueText = null;
            valueWitness = 0;
            hasSelection = false;
            multiSelectable = false;
            selectionRequired = false;
            hasSelectionItem = false;
            selected = false;
            positionInSet = 0;
            sizeOfSet = 0;
            selectionContainerless = false;
            selectionContainer = AccessibleNode.NONE;
            expand = -1;
            hasText = false;
            text = null;
            textWitness = 0;
            caretOffset = 0;
            caretAffinity = ShapedText.Affinity.DOWNSTREAM;
            selectionStart = 0;
            selectionEnd = 0;
            lineCount = 0;
            caretRect = null;
            hasScroll = false;
            horizontalPercent = 0;
            verticalPercent = 0;
            horizontalViewSize = 1;
            verticalViewSize = 1;
            horizontallyScrollable = false;
            verticallyScrollable = false;
            hasWindow = false;
            windowModal = false;
            windowCanMaximize = false;
            windowCanMinimize = false;
            windowState = WindowFacet.State.NORMAL;
            hasTable = false;
            tableRowCount = 0;
            tableColumnCount = 0;
            hasCell = false;
            cellRow = 0;
            cellColumn = 0;
            hasHierarchy = false;
            level = 0;
            hierarchyRow = 0;
            hierarchyRowCount = 0;
            verbs = 0;
            delegated = 0;
            keyBinding = null;
            relationCount = 0;
        }

        boolean declaresNothing() {
            return role == Accessible.Role.GROUP
                    && nameText.isEmpty()
                    && descriptionText.isEmpty()
                    && declaredStates == 0
                    && verbs == 0
                    && relationCount == 0
                    && toggle < 0 && expand < 0
                    && !hasValue && !hasSelection && !hasSelectionItem
                    && !hasText && !hasScroll && !hasWindow
                    && !hasTable && !hasCell && !hasHierarchy;
        }

        void addRelation(Accessible.Relation kind, Object target) {
            if (relationCount == relationKinds.length) {
                int grown = Math.max(4, relationKinds.length * 2);
                relationKinds = java.util.Arrays.copyOf(relationKinds, grown);
                relationTargets = java.util.Arrays.copyOf(relationTargets, grown);
                relationResolved = java.util.Arrays.copyOf(relationResolved, grown);
            }
            relationKinds[relationCount] = kind;
            relationTargets[relationCount] = target;
            relationCount++;
        }
    }

    private Slot[] slots = newSlots(64);
    private Slot[] previous = newSlots(64);
    private int count;
    private int previousCount;
    private int current = AccessibleNode.NONE;

    private float sceneWidth;
    private float sceneHeight;
    private Locale sceneLocale = Locale.ROOT;
    private long generation;

    // The synthetic-key intern table: (owner id, local key) -> identifier, so a menu row or a list
    // row scrolled away and back keeps the identifier a client is holding. Open-addressed over
    // three parallel arrays, because a map keyed by a pair would allocate a key per lookup and
    // this is on the walk.
    private long[] internOwners = new long[INTERN_CAPACITY * 2];
    private long[] internKeys = new long[INTERN_CAPACITY * 2];
    private long[] internIds = new long[INTERN_CAPACITY * 2];
    private long[] internSeen = new long[INTERN_CAPACITY * 2];
    private int internSize;
    private int internCapacity = INTERN_CAPACITY;

    /**
     * Which walk this is, from one. What an interned pair is stamped with when it is looked up,
     * so that eviction can tell a pair this walk or the last one asked for -- which the next walk
     * will ask for again -- from one nothing has asked for since. Not the publish generation: a
     * walk that changed nothing publishes nothing and the generation stands still, while the pair
     * it looked up is as live as any.
     */
    private long walkSerial;

    /** What the last publish hands the bridge: the budgeted events, or their collapse, then the tail. */
    private final List<AccessibleEvent> events = new ArrayList<>();

    /**
     * The per-node events of the publish in progress, which are what the budget bounds; and
     * the reserved tail, which is what a collapse keeps (semantics 7 of the 2026-09-13 pass;
     * ADR 039 §1.10, amended 2026-09-14): the per-parent structure changes, the final focus
     * change, the single cursor change, the per-container selection changes and the window
     * activation events. A bridge that swept on INVALIDATED and heard nothing else would have
     * lost the focus that moved in the same frame as three hundred labels, which is exactly the
     * frame in which a reader needs to be told where the user is.
     */
    private final List<AccessibleEvent> budgeted = new ArrayList<>();
    private final List<AccessibleEvent> tail = new ArrayList<>();

    /**
     * The node the focus arrived on in the publish in progress: a surviving node whose FOCUSED
     * bit came on, or a node that arrived already focused (WINDOWS-NEW-12), which the old
     * per-node loop skipped with the rest of a new node's bits. Zero when the focus stood.
     */
    private long focusArrived;

    // What a parent says about a child's identity before the child is begun (ADR 039 §1.3, rule
    // 1, amended 2026-09-14): held here between the identity hook and begin(), because there is
    // no slot yet to hold it -- the key is what decides the slot's identifier.
    private boolean namingChild;
    private boolean hasPendingKey;
    private long pendingKey;
    private boolean hasPendingHost;
    private long pendingHostKey;

    /**
     * Whether a parent's describe-a-child hook is running, with the child's node open: the only
     * window in which {@link #delegate} may be called, because a delegated verb is a container's
     * claim on a child and the claim has to be made where the container speaks about the child.
     */
    private boolean describingChild;

    /** How many names and descriptions were carried over from the last walk unresolved. */
    private long carriedOver;

    /**
     * The cursor as of the last publish — {@link AccessibleTree#activeDescendant()} of the tree
     * published last — which is what the next publish's cursor is compared with, and the popup
     * cursor the walk handed over for that publish, which is what a quiet frame compares with.
     */
    private long publishedActiveDescendant;
    private long publishedForeignActiveDescendant;

    /** What {@link #foreignActiveDescendant(long)} was told for the walk in progress. */
    private long foreignActiveDescendant;

    private static Slot[] newSlots(int size) {
        Slot[] made = new Slot[size];
        for (int i = 0; i < size; i++) {
            made[i] = new Slot();
        }
        return made;
    }

    /** A builder with no nodes and nothing published. One per scene. */
    public Accessibility() {
    }

    // ------------------------------------------------------------ widget-facing

    /**
     * Declares what this node is. A node that declares nothing else is scaffolding and is removed
     * from the tree with its children hoisted in its place, so declaring a role is how a widget
     * stays in the tree at all.
     *
     * @param role the role; never {@code null}
     * @throws NullPointerException if {@code role} is {@code null}
     */
    public void role(Accessible.Role role) {
        Slot s = slot();
        s.role = Objects.requireNonNull(role, "role");
        s.roleDeclared = true;
    }

    /**
     * Whether anything has said what the node being described is.
     *
     * <p>The publish step asks, because a widget the keyboard can land on and nobody named is a
     * defect it has to report rather than a group box it can quietly publish.
     *
     * @return whether {@link #role(Accessible.Role)} was called on this node
     */
    public boolean hasRole() {
        return slot().roleDeclared;
    }

    /**
     * Names this node from a localizable string the widget holds, and says where the name came
     * from.
     *
     * <p>Resolved under the node's own language, once, and carried over unresolved on every
     * later frame where the string, the language and the translation epoch are all unchanged.
     *
     * <p><b>A name that resolves to nothing carries no provenance.</b> A widget hands over the
     * string it holds whether or not the application filled it — a text field's placeholder is
     * {@link I18nString#EMPTY} until one is set — and the empty string names nothing, which is
     * what lets the walk's own defaults still land. The provenance has to follow the same rule,
     * or the node says it was named by a placeholder it does not have: a fact about where
     * nothing came from, which one platform would then act on by choosing an attribute for it.
     * So an empty name is published with the default, {@link Accessible.NameFrom#CONTENT},
     * whatever the caller said, and the caller's provenance is kept only for a name that exists.
     *
     * @param source the widget's own string; {@code null} clears the name
     * @param from   where the name came from, which decides which attribute one platform
     *               publishes it in
     * @throws NullPointerException if {@code from} is {@code null}
     */
    public void name(I18nString source, Accessible.NameFrom from) {
        Objects.requireNonNull(from, "from");
        Slot s = slot();
        s.nameWitness = 0;
        if (source == null) {
            s.nameSource = null;
            s.nameText = "";
            s.nameFrom = Accessible.NameFrom.CONTENT;
            return;
        }
        Locale locale = s.locale;
        long epoch = I18n.epoch();
        Slot old = previousOf(s.id, current);
        if (old != null && old.nameSource == source && old.nameWitness == 0
                && epoch == old.nameEpoch && locale.equals(old.nameLocale)) {
            s.nameSource = source;
            s.nameLocale = old.nameLocale;
            s.nameEpoch = old.nameEpoch;
            s.nameText = old.nameText;
            carriedOver++;
        } else {
            s.nameSource = source;
            s.nameLocale = locale;
            s.nameEpoch = epoch;
            s.nameText = source.get();
        }
        s.nameFrom = s.nameText.isEmpty() ? Accessible.NameFrom.CONTENT : from;
    }

    /**
     * Names this node from a localizable string, with the provenance
     * {@link Accessible.NameFrom#CONTENT}: the widget's own painted text.
     *
     * @param source the widget's own string; {@code null} clears the name
     */
    public void name(I18nString source) {
        name(source, Accessible.NameFrom.CONTENT);
    }

    /**
     * Names this node from a string the widget <em>derived</em> and is already holding, together
     * with the counter its cache was filled against.
     *
     * <p>This is the form for a formatted name — anything built with a format call, which is never
     * cached and therefore has no source to compare. Building the string inside the describe hook
     * instead leaves the node correct and makes every damaged frame allocate.
     *
     * <p>An empty string carries no provenance, for the reason {@link #name(I18nString,
     * Accessible.NameFrom)} gives.
     *
     * @param cached  the string the widget is holding; {@code null} clears the name
     * @param witness a value that changes whenever {@code cached} does, and is otherwise
     *                meaningless
     * @param from    where the name came from
     * @throws NullPointerException if {@code from} is {@code null}
     */
    public void name(String cached, long witness, Accessible.NameFrom from) {
        Objects.requireNonNull(from, "from");
        Slot s = slot();
        s.nameSource = null;
        s.nameWitness = witness;
        s.nameLocale = s.locale;
        s.nameEpoch = I18n.epoch();
        s.nameText = cached == null ? "" : cached;
        s.nameFrom = s.nameText.isEmpty() ? Accessible.NameFrom.CONTENT : from;
    }

    /**
     * Describes this node at more length than its name does: what a tooltip says when the tooltip
     * is not already the name.
     *
     * @param source the widget's own string; {@code null} clears the description
     */
    public void description(I18nString source) {
        Slot s = slot();
        s.descriptionWitness = 0;
        if (source == null) {
            s.descriptionSource = null;
            s.descriptionText = "";
            return;
        }
        Locale locale = s.locale;
        long epoch = I18n.epoch();
        Slot old = previousOf(s.id, current);
        if (old != null && old.descriptionSource == source && old.descriptionWitness == 0
                && epoch == old.descriptionEpoch && locale.equals(old.descriptionLocale)) {
            s.descriptionSource = source;
            s.descriptionLocale = old.descriptionLocale;
            s.descriptionEpoch = old.descriptionEpoch;
            s.descriptionText = old.descriptionText;
            carriedOver++;
            return;
        }
        s.descriptionSource = source;
        s.descriptionLocale = locale;
        s.descriptionEpoch = epoch;
        s.descriptionText = source.get();
    }

    /**
     * Describes this node from a string the widget derived and is holding.
     *
     * @param cached  the string; {@code null} clears the description
     * @param witness a value that changes whenever {@code cached} does
     */
    public void description(String cached, long witness) {
        Slot s = slot();
        s.descriptionSource = null;
        s.descriptionWitness = witness;
        s.descriptionLocale = s.locale;
        s.descriptionEpoch = I18n.epoch();
        s.descriptionText = cached == null ? "" : cached;
    }

    /**
     * Sets a state on this node.
     *
     * <p>Two groups of states are <b>refused</b> here rather than stored, and refused loudly,
     * the way {@link #action(Accessible.Action)} refuses a verb that takes an argument (ADR 039
     * §1.2, amended 2026-09-14). The states a facet expresses — checked, mixed, expanded,
     * expandable, selected, read-only — are derived from that facet, so one fact keeps one home:
     * declare the facet. The five the publish step owns — enabled, visible, showing, focusable and focused —
     * belong to the walk, because a widget's own flag answers only for itself while the tree has
     * to agree with a keyboard whose traversal stops at the first ancestor that is hidden or
     * disabled; a synthetic child narrows two of them through {@link #offScreen()} and
     * {@link #disabled()}. Until this amendment such a call was dropped in silence, and a widget
     * carried a dead line for months that no reader ever heard.
     *
     * @param state what to set; never {@code null}
     * @param on    whether it holds
     * @throws NullPointerException     if {@code state} is {@code null}
     * @throws IllegalArgumentException if the state is a facet's or the publish step's to derive
     */
    public void state(Accessible.State state, boolean on) {
        Objects.requireNonNull(state, "state");
        String owner = switch (state) {
            case CHECKED, MIXED -> "the toggle facet: call toggle()";
            case EXPANDED, EXPANDABLE -> "the expand facet: call expand()";
            case SELECTED -> "the selection-item facet: call selectionItem()";
            case READ_ONLY -> "the text or value facet: pass readOnly there";
            case ENABLED, VISIBLE, SHOWING, FOCUSABLE, FOCUSED ->
                    "the publish step, which inherits it down the walk";
            default -> null;
        };
        if (owner != null) {
            throw new IllegalArgumentException(
                    state + " is not a widget's to set; it is derived from " + owner);
        }
        Slot s = slot();
        long bit = 1L << state.ordinal();
        if (on) {
            s.states |= bit;
            s.declaredStates |= bit;
        } else {
            s.states &= ~bit;
            s.declaredStates &= ~bit;
        }
    }

    /**
     * Sets a state on this node; the same as {@link #state(Accessible.State, boolean)} with
     * {@code true}.
     *
     * @param state what to set
     */
    public void state(Accessible.State state) {
        state(state, true);
    }

    /**
     * Declares that this node carries a checked state.
     *
     * @param state whether it is off, on, or neither; {@code null} removes the facet
     */
    public void toggle(ToggleFacet.State state) {
        Slot s = slot();
        s.toggle = state == null ? -1 : state.ordinal();
        if (state == ToggleFacet.State.ON) {
            s.states |= 1L << Accessible.State.CHECKED.ordinal();
            s.states &= ~(1L << Accessible.State.MIXED.ordinal());
        } else if (state == ToggleFacet.State.MIXED) {
            s.states |= 1L << Accessible.State.MIXED.ordinal();
            s.states &= ~(1L << Accessible.State.CHECKED.ordinal());
        } else {
            s.states &= ~((1L << Accessible.State.CHECKED.ordinal())
                    | (1L << Accessible.State.MIXED.ordinal()));
        }
    }

    /**
     * Declares that this node's state is a number in a range.
     *
     * <p>A value that advances on its own is rounded <b>here, by the widget</b>, to the resolution
     * a user can act on: a playing video writes whole seconds. The comparison sees what it is
     * given, so an unrounded position copies the whole tree once per frame for the length of the
     * film with nobody touching anything.
     *
     * @param value the current value
     * @param min   the smallest the node accepts
     * @param max   the largest
     * @param step  one increment, or {@code 0} for none
     */
    public void value(double value, double min, double max, double step) {
        value(value, min, max, step, false);
    }

    /**
     * {@link #value(double, double, double, double)}, for a value the user may read and not set.
     *
     * <p>The facet's presence is what advertises a set on every platform, so a widget that
     * publishes a range it refuses -- a progress bar -- has to say so here and nowhere else:
     * {@link Accessible.State#READ_ONLY} is derived from this and {@link #state} drops it when
     * declared, for the same reason it drops {@code CHECKED}: a fact the facet carries is never
     * stored twice.
     *
     * @param value    the current value
     * @param min      the smallest the node accepts
     * @param max      the largest
     * @param step     one increment, or {@code 0} for none
     * @param readOnly whether the value may be read and not set
     */
    /**
     * A slider in one call: the role, the axis its value runs along, the value and the two
     * step verbs. Six widgets describe themselves this way and each used to write the four
     * lines; the pair of verbs is the two-argument form, which allocates nothing.
     *
     * @param orientation {@link Accessible.State#HORIZONTAL} or {@link Accessible.State#VERTICAL}
     * @param value       the current value
     * @param min         the least value
     * @param max         the greatest value
     * @param step        what one increment moves by
     */
    public void slider(Accessible.State orientation, double value, double min, double max,
                       double step) {
        role(Accessible.Role.SLIDER);
        state(orientation);
        value(value, min, max, step);
        action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
    }

    public void value(double value, double min, double max, double step, boolean readOnly) {
        Slot s = slot();
        s.hasValue = true;
        s.valueEmpty = false;
        s.value = value;
        s.valueMin = min;
        s.valueMax = max;
        s.valueStep = step;
        s.valueReadOnly = readOnly;
        if (readOnly) {
            s.states |= 1L << Accessible.State.READ_ONLY.ordinal();
        }
    }

    /**
     * Declares that this node's state is a number in a range and that it holds <b>no number
     * right now</b>: a date segment nobody has typed into (decision 16). The range stands, the
     * {@linkplain #valueText(String, long) text} may say what is shown, and the number published
     * is the minimum, for the platforms that must have one; {@link ValueFacet#empty()} is what says
     * empty. Filling the number later is a {@link AccessibleEvent.Type#VALUE_CHANGED}, even when
     * the number filled in is the minimum.
     *
     * @param min      the smallest the node accepts
     * @param max      the largest
     * @param step     one increment, or {@code 0} for none
     * @param readOnly whether the value may be read and not set
     */
    public void emptyValue(double min, double max, double step, boolean readOnly) {
        value(min, min, max, step, readOnly);
        slot().valueEmpty = true;
    }

    /**
     * Gives the value a display form: what the widget shows instead of the number, handed over
     * from the widget's own cache with the counter that cache was filled against.
     *
     * @param cached  the displayed string; {@code null} means the number is the whole of it
     * @param witness a value that changes whenever {@code cached} does
     */
    public void valueText(String cached, long witness) {
        Slot s = slot();
        s.valueText = cached;
        s.valueWitness = witness;
    }

    /**
     * Declares that this node holds a selection among its descendants.
     *
     * <p>Which of them are selected is on the descendants, in their own facet. Which of them the
     * keyboard cursor is on is not this container's fact at all: it is the tree's, resolved by
     * the publish step from the first descendant of the <em>focused</em> node published with
     * {@link Accessible.State#ACTIVE} ({@link AccessibleTree#activeDescendant()}), which a widget
     * publishes only while it holds the keyboard.
     *
     * @param multiSelectable whether more than one may be selected at once
     * @param required        whether at least one always is
     */
    public void selection(boolean multiSelectable, boolean required) {
        Slot s = slot();
        s.hasSelection = true;
        s.multiSelectable = multiSelectable;
        s.selectionRequired = required;
    }

    /**
     * Declares that this node is one member of a container's selection.
     *
     * <p>The position and the size are the model's numbers, not the tree's: a list that publishes
     * only its realized rows still reports the true row count, so a user hears where they are in
     * the data.
     *
     * @param selected      whether this member is selected
     * @param positionInSet its one-based position, or {@code 0} when it has none
     * @param sizeOfSet     how many members the set holds, or {@code 0} when that is unknown
     */
    public void selectionItem(boolean selected, int positionInSet, int sizeOfSet) {
        Slot s = slot();
        s.hasSelectionItem = true;
        s.selected = selected;
        s.positionInSet = positionInSet;
        s.sizeOfSet = sizeOfSet;
        s.selectionContainerless = false;
        long bit = 1L << Accessible.State.SELECTED.ordinal();
        if (selected) {
            s.states |= bit;
        } else {
            s.states &= ~bit;
        }
    }

    /**
     * Declares that this node is one member of a selection that <b>no node holds</b>: a radio
     * button, whose group is not a widget and has no box (semantics 1; ADR 039 §1.2, amended
     * 2026-09-14). The numbers are as for {@link #selectionItem}; what differs is that no
     * selection change is raised on the node's published parent, which would be whatever layout
     * ancestor survived transparency, and a bridge asked for the member's container answers none.
     *
     * @param selected      whether this member is selected
     * @param positionInSet its one-based position among the members it counts, or {@code 0}
     * @param sizeOfSet     how many members there are, or {@code 0} when there is no set
     */
    public void containerlessSelectionItem(boolean selected, int positionInSet, int sizeOfSet) {
        selectionItem(selected, positionInSet, sizeOfSet);
        slot().selectionContainerless = true;
    }

    /**
     * Declares that this node opens and closes.
     *
     * <p>The facet's presence is what makes the node {@link Accessible.State#EXPANDABLE}, on every
     * node that declares it (decision 41: menu titles and submenu rows included), and its value is
     * what makes it {@link Accessible.State#EXPANDED}; the Linux bridge derives its own
     * {@code COLLAPSED} from the two. Neither bit is a widget's to set.
     *
     * @param expanded whether it is open now
     */
    public void expand(boolean expanded) {
        Slot s = slot();
        s.expand = expanded ? 1 : 0;
        long bit = 1L << Accessible.State.EXPANDED.ordinal();
        if (expanded) {
            s.states |= bit;
        } else {
            s.states &= ~bit;
        }
        s.states |= 1L << Accessible.State.EXPANDABLE.ordinal();
    }

    /**
     * Declares that this node holds text, handing over the string the widget already has together
     * with the counter its editing model bumps.
     *
     * <p>Every offset is a UTF-16 code unit. A masked field passes its <b>mask</b> here and offsets
     * into that mask, never its own text and never the secret's offsets.
     *
     * @param cached         the text; {@code null} removes the facet
     * @param witness        a value that changes whenever {@code cached} does
     * @param caretOffset    where the caret sits
     * @param caretAffinity  which side of it the caret is on; never {@code null}
     * @param selectionStart where the selection begins, equal to its end when there is none
     * @param selectionEnd   where it ends
     * @param lineCount      how many lines the text has
     * @param caretRect      the caret's box in this node's own coordinates, or {@code null}
     * @param readOnly       whether the text may be read and not written
     * @throws NullPointerException if {@code caretAffinity} is {@code null}
     */
    public void text(String cached, long witness, int caretOffset,
                     ShapedText.Affinity caretAffinity, int selectionStart, int selectionEnd,
                     int lineCount, Rect caretRect, boolean readOnly) {
        Objects.requireNonNull(caretAffinity, "caretAffinity");
        Slot s = slot();
        long bit = 1L << Accessible.State.READ_ONLY.ordinal();
        if (cached == null) {
            s.hasText = false;
            s.text = null;
            s.states &= ~bit;
            return;
        }
        s.hasText = true;
        s.text = cached;
        s.textWitness = witness;
        s.caretOffset = caretOffset;
        s.caretAffinity = caretAffinity;
        s.selectionStart = selectionStart;
        s.selectionEnd = selectionEnd;
        s.lineCount = lineCount;
        s.caretRect = caretRect;
        if (readOnly) {
            s.states |= bit;
        } else {
            s.states &= ~bit;
        }
    }

    /**
     * Declares that this node scrolls its content.
     *
     * @param horizontalPercent      where the viewport sits along the width, in {@code 0..1}
     * @param verticalPercent        where it sits along the height
     * @param horizontalViewSize     the fraction of the width it shows
     * @param verticalViewSize       the fraction of the height it shows
     * @param horizontallyScrollable whether the content is wider than the viewport
     * @param verticallyScrollable   whether it is taller
     */
    /**
     * The scroll facet folded from the geometry every scrolling widget holds: per axis, how far
     * it has scrolled, how far it can, and how big the viewport and the content are.
     *
     * <p>Seven widgets used to fold these into the facet's percentages themselves, and three of
     * them forgot the one guard that matters: a viewport larger than its content is a fraction
     * over one, which a platform reads as more than all of it. Here the view size is held to one,
     * an axis with nothing to scroll answers zero and one, and a content of zero is "all of it"
     * rather than a division by zero &mdash; which is also what keeps a {@code NaN} out of a field
     * the tree's difference compares with {@code !=}.
     *
     * @param offsetX        how far the content has scrolled along x, from zero
     * @param maxX           how far it can; zero or less when the axis does not scroll
     * @param viewportWidth  the width shown
     * @param contentWidth   the width there is; zero or less when unknown
     * @param offsetY        how far the content has scrolled along y, from zero
     * @param maxY           how far it can; zero or less when the axis does not scroll
     * @param viewportHeight the height shown
     * @param contentHeight  the height there is; zero or less when unknown
     */
    public void scrollFrom(double offsetX, double maxX, double viewportWidth, double contentWidth,
                           double offsetY, double maxY, double viewportHeight, double contentHeight) {
        scroll(maxX > 0 ? offsetX / maxX : 0,
                maxY > 0 ? offsetY / maxY : 0,
                contentWidth > 0 ? Math.min(1, viewportWidth / contentWidth) : 1,
                contentHeight > 0 ? Math.min(1, viewportHeight / contentHeight) : 1,
                maxX > 0, maxY > 0);
    }

    public void scroll(double horizontalPercent, double verticalPercent,
                       double horizontalViewSize, double verticalViewSize,
                       boolean horizontallyScrollable, boolean verticallyScrollable) {
        Slot s = slot();
        s.hasScroll = true;
        s.horizontalPercent = horizontalPercent;
        s.verticalPercent = verticalPercent;
        s.horizontalViewSize = horizontalViewSize;
        s.verticalViewSize = verticalViewSize;
        s.horizontallyScrollable = horizontallyScrollable;
        s.verticallyScrollable = verticallyScrollable;
    }

    /**
     * Declares that this node is a grid of rows and columns over data; ADR 041 §7.
     *
     * <p>Both counts are the model's, as {@link TableFacet} says: a table publishes only the
     * rows it has realized and still reports how many there are.
     *
     * @param rowCount    how many data rows the model holds
     * @param columnCount how many columns are shown
     */
    public void table(int rowCount, int columnCount) {
        Slot s = slot();
        s.hasTable = true;
        s.tableRowCount = rowCount;
        s.tableColumnCount = columnCount;
    }

    /**
     * Declares that this node is one cell of a table, at a row and a column as shown.
     *
     * @param row    the row as shown, from zero, or {@code -1} for a cell of the header row
     * @param column the column as shown, from zero
     */
    public void cell(int row, int column) {
        Slot s = slot();
        s.hasCell = true;
        s.cellRow = row;
        s.cellColumn = column;
    }

    /**
     * Declares where this node stands in an outline: a tree row's depth and its flat index among
     * the rows the outline shows open, the two numbers {@link HierarchyFacet} explains. Its place
     * among its siblings is {@link #selectionItem}'s.
     *
     * @param level    how deep the row is, from one at a root, or {@code 0} when unknown
     * @param row      which row of the outline this is, from one at the top, or {@code 0}
     * @param rowCount how many rows the outline shows open, or {@code 0} when unknown
     */
    public void hierarchy(int level, int row, int rowCount) {
        Slot s = slot();
        s.hasHierarchy = true;
        s.level = level;
        s.hierarchyRow = row;
        s.hierarchyRowCount = rowCount;
    }

    /**
     * Declares that this node is a real top-level window. A dialog drawn inside another window's
     * scene is not one and must not call this.
     *
     * @param modal       whether it blocks the windows it owns
     * @param canMaximize whether the desktop offers a maximize action
     * @param canMinimize whether it offers a minimize action
     * @param state       normal, minimized or maximized; never {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void window(boolean modal, boolean canMaximize, boolean canMinimize,
                       WindowFacet.State state) {
        Objects.requireNonNull(state, "state");
        Slot s = slot();
        s.hasWindow = true;
        s.windowModal = modal;
        s.windowCanMaximize = canMaximize;
        s.windowCanMinimize = canMinimize;
        s.windowState = state;
    }

    /**
     * Offers a verb on this node.
     *
     * <p>Only the {@linkplain Accessible.Action#isParameterless() parameterless} verbs are
     * publishable. A parameterised setter is advertised by the presence of the facet it sets, and
     * offering it here would make one platform's action list unanswerable.
     *
     * @param action the verb to offer
     * @throws NullPointerException     if {@code action} is {@code null}
     * @throws IllegalArgumentException if it takes an argument
     */
    public void action(Accessible.Action action) {
        Objects.requireNonNull(action, "action");
        if (!action.isParameterless()) {
            throw new IllegalArgumentException(
                    action + " takes an argument and is never published in an action list");
        }
        slot().verbs |= 1 << action.ordinal();
    }

    /**
     * Offers two verbs on this node.
     *
     * <p>A pair rather than the variable-argument form below, because that one allocates an array
     * per call and the publish step offers the same two on every focusable widget in the window,
     * on every frame that walks. The general form is for a widget describing itself, where a
     * handful of arrays a frame is not the same kind of cost.
     *
     * @param first  one verb
     * @param second the other
     * @throws NullPointerException     if either is {@code null}
     * @throws IllegalArgumentException if either takes an argument
     */
    public void action(Accessible.Action first, Accessible.Action second) {
        action(first);
        action(second);
    }

    /**
     * Offers several verbs on this node.
     *
     * @param actions the verbs to offer
     * @throws NullPointerException     if {@code actions} or any of them is {@code null}
     * @throws IllegalArgumentException if one of them takes an argument
     */
    public void action(Accessible.Action... actions) {
        Objects.requireNonNull(actions, "actions");
        Slot s = slot();
        for (Accessible.Action action : actions) {
            Objects.requireNonNull(action, "action");
            if (!action.isParameterless()) {
                throw new IllegalArgumentException(
                        action + " takes an argument and is never published in an action list");
            }
            s.verbs |= 1 << action.ordinal();
        }
    }

    /**
     * Offers a verb on this widget child that <b>this widget's parent</b> performs: a row's
     * {@code SELECT}, published on the row's cell where the platform addresses it and routed by
     * the scene to the container's {@code onAccessibilityChildAction} hook with the child's key
     * (ADR 039 §1.5, amended 2026-09-14; decision 7 of the 2026-09-13 pass).
     *
     * <p>From {@code onAccessibilityChild} only, because that is where a container speaks about
     * a child with the child's node open; and never for a verb the child already claimed in its
     * own hook &mdash; a cell that is a {@code Button} keeps its {@code PRESS}, and a container
     * that claimed the same verb would have stolen it in silence, so the walk is refused rather
     * than publish one verb with two performers. The child keeps every other verb it
     * declared, and its identity is untouched: the node is still the child's, owned by the
     * child for every verb but the delegated ones.
     *
     * @param action the verb the parent claims
     * @throws NullPointerException     if {@code action} is {@code null}
     * @throws IllegalArgumentException if it takes an argument
     * @throws IllegalStateException    if called outside {@code onAccessibilityChild}, or if the
     *                                  child already offers the verb itself
     */
    public void delegate(Accessible.Action action) {
        Objects.requireNonNull(action, "action");
        if (!action.isParameterless()) {
            throw new IllegalArgumentException(
                    action + " takes an argument and is never published in an action list");
        }
        if (!describingChild) {
            throw new IllegalStateException(
                    "a verb is delegated from onAccessibilityChild, where the container speaks "
                            + "about the child whose verb it claims; " + action + " was delegated "
                            + "from elsewhere");
        }
        Slot s = slot();
        int bit = 1 << action.ordinal();
        if ((s.verbs & bit) != 0) {
            throw new IllegalStateException(
                    action + " is claimed by both the child and its container: the child offered "
                            + "it in its own hook and the container delegated it, and a verb with "
                            + "two performers is refused rather than routed to one of them");
        }
        s.verbs |= bit;
        s.delegated |= bit;
    }

    /**
     * Offers the two free verbs, {@code FOCUS} and {@code SCROLL_INTO_VIEW}, on the focusable
     * widget node being described. The publish step calls this after both describe hooks ran;
     * a widget never does.
     *
     * <p>They are the walk's and the scene performs them (ADR 039 §1.5), so a container that
     * {@linkplain #delegate delegated} one of them on a focusable child claimed a verb the walk
     * owns and the scene would have performed both ways at once; that walk is refused.
     *
     * @throws IllegalStateException if either verb was delegated on this node
     */
    public void freeVerbs() {
        Slot s = slot();
        int free = (1 << Accessible.Action.FOCUS.ordinal())
                | (1 << Accessible.Action.SCROLL_INTO_VIEW.ordinal());
        if ((s.delegated & free) != 0) {
            throw new IllegalStateException(
                    "FOCUS and SCROLL_INTO_VIEW on a focusable widget are the walk's free verbs, "
                            + "performed by the scene, and a container delegated one of them on "
                            + "a focusable child");
        }
        s.verbs |= free;
    }

    /**
     * Gives this node's primary action the keystroke that performs it, as a user would read it.
     *
     * @param display the accelerator's display form, or {@code null} for none
     */
    public void keyBinding(String display) {
        slot().keyBinding = display;
    }

    /**
     * Links this node to another one.
     *
     * <p>The target is resolved by the publish step to the nearest node it actually published, and
     * the relation is dropped when there is none: naming a node that was never published is worse
     * than naming nothing, because every platform answers it with an element that does not resolve.
     *
     * @param kind   what the link means; never {@code null}
     * @param target the {@link limn.scene.Widget} at the other end. Typed as an object because
     *               this package sits below the widget tree and names nothing in it; anything else
     *               resolves to no node and the relation is dropped.
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public void relation(Accessible.Relation kind, Object target) {
        Objects.requireNonNull(kind, "kind");
        if (target != null) {
            slot().addRelation(kind, target);
        }
    }

    /**
     * Removes this node and its children from the tree: a spacer, a decorative rule, a mark a
     * widget draws that carries no information.
     *
     * <p>Not for a control that can be operated. Removing one of those is not a deferral, it is
     * hiding it from the only user who cannot see it.
     */
    public void ignore() {
        slot().ignored = true;
    }

    /**
     * Says that the synthetic child being described is scrolled out of its owner's viewport, so
     * that it publishes without {@code SHOWING} while its owner still shows.
     *
     * <p>A widget cannot set that state itself — {@link #state(Accessible.State, boolean)} refuses
     * the five the publish step owns, because a widget's own flag answers only for itself — and
     * the publish step cannot work it out either, since a row clipped away inside a scrolled list
     * is a fact only the owner's own row formula knows. So this is the one bit of the inherited
     * set a widget contributes to, and it contributes it the way it can be checked: by handing
     * over the answer its paint loop already computes, rather than by naming a rectangle twice.
     *
     * <p>Clear by default and cleared for every child, so a scrolled-away row does not leave the
     * next one marked. It says nothing about a widget's own node, which takes its showing bit from
     * the widget tree.
     *
     * @throws IllegalStateException if no synthetic child is open
     */
    public void offScreen() {
        Slot s = slot();
        if (!s.synthetic) {
            throw new IllegalStateException("offScreen outside a synthetic child");
        }
        s.offScreen = true;
    }

    /**
     * Says that the synthetic child being described cannot be operated even though its owner
     * can: a calendar day the bounds or the filter refuse, a scroll chevron with nothing left to
     * scroll (decision 30, 2026-09-14). It publishes without {@code ENABLED} while its owner
     * keeps it.
     *
     * <p>{@link #offScreen()}'s shape, for {@link #offScreen()}'s reason: enabled is one of the
     * states the publish step owns and a widget cannot set, and the step inherits it from the
     * owner down onto every synthetic child, which is right for a menu row inside a disabled menu
     * and wrong for a refused day inside an enabled calendar. So this is <b>narrowing only</b>: a
     * child may be less enabled than its owner and never more, because the owner's bit is the
     * keyboard's and a child published operable inside a disabled owner would be refused by
     * §1.9's gate with nothing said about why. Clear by default and cleared for every child. It
     * narrows through the nesting: a synthetic child declared inside a disabled one publishes
     * without {@code ENABLED} too, whether or not it called this itself.
     *
     * <p><b>It does not reach a widget, and the walk refuses the combination.</b> A widget child a
     * container hangs under a disabled synthetic node with {@link #under(long)} would take its
     * enabled bit from the widget tree, as the scene's action gate does, and publish
     * {@code ENABLED} and its verbs beneath its disabled row. No widget combines the two —
     * {@code Table} is the only caller of {@code under} and never disables a row, and this
     * method's callers (a calendar's refused day and month, a segmented control's dead chevron,
     * a combo's options and a menu's rows) hang no widget under the node — so the walk throws
     * when an enabled container does, and the previous tree stands (2026-09-15, the 2d review). A
     * widget that needs it owes the walk and the gate the same narrowing in the same change.
     *
     * <p>A disabled child carries no verb: the publish step withdraws every verb and setter from a
     * node that is not {@code ENABLED} ({@link #inoperableAt}, ADR 039 §1.5, amended 2026-09-15),
     * so a refused day may declare its {@code SELECT} and still publishes none, and a bridge reads
     * the absence.
     *
     * @throws IllegalStateException if no synthetic child is open
     */
    public void disabled() {
        Slot s = slot();
        if (!s.synthetic) {
            throw new IllegalStateException("disabled outside a synthetic child");
        }
        s.disabled = true;
    }

    /**
     * Gives the child about to be described the identity key its parent chose for it, from inside
     * the parent's <em>identity</em> hook and nowhere else.
     *
     * <p>This is what keeps a recycled list cell from carrying row three's identifier to row nine.
     * A container that pools its children owns their identity, and nothing else can: the widget
     * object is the wrong key the moment it is reused, and the data index is the right one.
     *
     * <p>It is answered <b>before</b> the child describes itself, and that ordering is the whole
     * point (ADR 039 §1.3, amended 2026-09-14). The child's node takes its identifier from the key
     * before its own hook runs, so a name it hands over is looked up under the identifier it was
     * published with last frame and carried over unresolved; and everything the child declares
     * inside itself &mdash; a synthetic child of its own, a widget it holds &mdash; is scoped
     * under that identifier too, so a composite cell recycled to another row carries none of the
     * old row's inner elements with it. A key given from the describe-a-child hook would arrive
     * after both, which is why that hook refuses it.
     *
     * @param key a value unique among the children of the node this child hangs under, of both
     *            kinds &mdash; a widget cell keyed {@code 2} and a synthetic cell keyed {@code 2}
     *            under one row would be one element &mdash; and stable for as long as the child
     *            stands for the same thing
     * @throws IllegalStateException if called outside the identity hook
     */
    public void key(long key) {
        if (!namingChild) {
            throw new IllegalStateException(
                    "a child's key is answered before it describes itself: call key() from "
                            + "onAccessibilityChildIdentity, not from onAccessibilityChild");
        }
        pendingKey = key;
        hasPendingKey = true;
    }

    /**
     * Hangs the child about to be described under one of this widget's own synthetic children
     * rather than under this widget's node: a {@code Table}'s widget cell under the synthetic
     * {@code ROW} it sits in, so a reader walking the row finds the control among its cells.
     *
     * <p>From the identity hook only, like {@link #key(long)}, because where a node hangs is
     * decided before it is begun. The synthetic child is named by the key its owner gave it in
     * {@link #child(long)} during this same walk, and must exist: a parent naming a row it did
     * not declare is a defect the walk reports rather than a child it quietly publishes elsewhere.
     * The child's identity is then scoped under that synthetic node, not under this widget, so
     * {@link #key(long)} need only be unique among the row's children. Among them it takes its
     * place by column when it and they carry a {@link CellFacet}, and last otherwise. Its verbs
     * stay its own: the walk still routes an action on it to the widget, never to the owner's
     * synthetic hook.
     *
     * @param syntheticKey the key of the synthetic child of this widget the child hangs under
     * @throws IllegalStateException if called outside the identity hook
     */
    public void under(long syntheticKey) {
        if (!namingChild) {
            throw new IllegalStateException(
                    "where a child hangs is answered before it describes itself: call under() "
                            + "from onAccessibilityChildIdentity, not from onAccessibilityChild");
        }
        pendingHostKey = syntheticKey;
        hasPendingHost = true;
    }

    /**
     * Begins a synthetic child of the node being described: something the widget draws but never
     * instantiated as a widget — a menu column, a menu row, a combo option, a chart series.
     *
     * <p>Synthetic children nest, so a menu row that opens a submenu owns that column. Every one
     * of them must be closed with {@link #endChild()}, and each supplies its own
     * {@linkplain #bounds bounds}, because nothing else has them.
     *
     * @param key a value unique among this owner's synthetic children and stable for as long as
     *            the child stands for the same thing
     */
    public void child(long key) {
        Slot owner = slot();
        long id = intern(owner.id, key);
        int slot = begin(id, current, owner.locale,
                owner.x, owner.y, owner.width, owner.height);
        slots[slot].originX = owner.originX;
        slots[slot].originY = owner.originY;
        slots[slot].synthetic = true;
        slots[slot].syntheticKey = key;
    }

    /**
     * Closes the synthetic child {@link #child(long)} opened.
     *
     * @throws IllegalStateException if no synthetic child is open
     */
    public void endChild() {
        Slot s = slot();
        if (s.parent == AccessibleNode.NONE) {
            throw new IllegalStateException("endChild without child");
        }
        s.subtreeEnd = count;
        current = s.parent;
    }

    /**
     * Places a synthetic child's box, in the coordinates of the widget that declared it.
     *
     * <p>A widget's own node takes its box from its layout and never calls this; a synthetic child
     * has no layout and must.
     *
     * @param x      the left edge, in the declaring widget's own coordinates
     * @param y      the top edge
     * @param width  the width
     * @param height the height
     */
    public void bounds(float x, float y, float width, float height) {
        Slot s = slot();
        s.x = s.originX + x;
        s.y = s.originY + y;
        s.width = width;
        s.height = height;
    }

    // ------------------------------------------------------- publish-step driver

    /**
     * Starts a walk. The publish step calls this; a widget never does.
     *
     * @param sceneWidth  the scene's width in logical points
     * @param sceneHeight its height
     * @param sceneLocale the scene's own language; never {@code null}
     */
    public void beginWalk(float sceneWidth, float sceneHeight, Locale sceneLocale) {
        this.sceneWidth = sceneWidth;
        this.sceneHeight = sceneHeight;
        this.sceneLocale = Objects.requireNonNull(sceneLocale, "sceneLocale");
        count = 0;
        current = AccessibleNode.NONE;
        walkSerial++;
        foreignActiveDescendant = 0;
    }

    /**
     * Begins one node and makes it the one the setters write to. The publish step calls this.
     *
     * @param id     the node's identifier, minted over the widget tree
     * @param parent the index of the node's parent, or {@link AccessibleNode#NONE} at a root
     * @param locale the language this node's text is in; never {@code null}
     * @param x      the node's left edge in scene coordinates
     * @param y      its top edge
     * @param width  its width
     * @param height its height
     * @return the node's index
     */
    public int begin(long id, int parent, Locale locale,
                     float x, float y, float width, float height) {
        if (count == slots.length) {
            Slot[] grown = java.util.Arrays.copyOf(slots, slots.length * 2);
            for (int i = slots.length; i < grown.length; i++) {
                grown[i] = new Slot();
            }
            slots = grown;
        }
        int index = count++;
        slots[index].reset(id, parent, locale, x, y, width, height);
        current = index;
        return index;
    }

    /**
     * Ends the node {@link #begin} began, making its parent current again. The publish step calls
     * this.
     */
    public void end() {
        current = slot().parent;
    }

    /**
     * Discards the node {@link #begin} began, as though it had never been started: what the publish
     * step does to a container that turned out to be scaffolding.
     *
     * @throws IllegalStateException if the node has already had children
     */
    public void drop() {
        if (current != count - 1) {
            throw new IllegalStateException("a node with children cannot be dropped");
        }
        int parent = slots[current].parent;
        count--;
        current = parent;
    }

    /**
     * Whether the node being described has already produced children, which a container that
     * turns out to be scaffolding cannot have: a synthetic child is declared by a node that has,
     * by declaring it, said it is not scaffolding.
     *
     * @return whether anything was begun under the node being described
     */
    public boolean hasChildren() {
        return current != count - 1;
    }

    /**
     * Whether the node being described declared nothing of its own: no role, no name, no
     * description, no state, no verb, no relation and no facet.
     *
     * <p>Asked on the node's <b>own</b> declarations and never on the states it inherits, which is
     * what keeps a disabled form from growing a skeleton of grouping boxes on the frame it is
     * disabled.
     *
     * @return whether this node is scaffolding
     */
    public boolean declaresNothing() {
        return slot().declaresNothing();
    }

    /**
     * Whether the node being described asked to be removed along with its children.
     *
     * @return whether the widget called {@link #ignore()}
     */
    public boolean isIgnored() {
        return slot().ignored;
    }

    /**
     * Opens the window in which a parent's identity hook may call {@link #key(long)} and
     * {@link #under(long)} for the child the publish step is about to begin, and may call
     * nothing else: the node open meanwhile is the parent's, so every describe setter throws
     * until {@link #endChildIdentity()} rather than writing onto it. The publish step calls
     * this; a widget never does.
     */
    public void beginChildIdentity() {
        namingChild = true;
        hasPendingKey = false;
        hasPendingHost = false;
    }

    /** Closes what {@link #beginChildIdentity()} opened. The publish step calls this. */
    public void endChildIdentity() {
        namingChild = false;
    }

    /**
     * Opens the window in which a parent's describe-a-child hook runs with the child's node
     * open: the only window in which {@link #delegate} is accepted. The publish step calls this
     * around that hook; a widget never does.
     */
    public void beginChildDescription() {
        describingChild = true;
    }

    /** Closes what {@link #beginChildDescription()} opened. The publish step calls this. */
    public void endChildDescription() {
        describingChild = false;
    }

    /**
     * The verbs a container delegated on the node at an index of the walk in progress, as a bit
     * set over {@link Accessible.Action#ordinal()}. The publish step reads it to route an action
     * on that node to the container rather than to the child.
     *
     * @param index the node's index
     * @return the bit set; {@code 0} when nothing was delegated
     */
    public int delegatedVerbsAt(int index) {
        return slots[index].delegated;
    }

    /** @return whether the identity hook gave the child about to be begun a key */
    public boolean hasPendingKey() {
        return hasPendingKey;
    }

    /** @return the key the identity hook gave, meaningful only when {@link #hasPendingKey()} */
    public long pendingKey() {
        return pendingKey;
    }

    /** @return whether the identity hook hung the child under a synthetic node of its parent */
    public boolean hasPendingHost() {
        return hasPendingHost;
    }

    /**
     * The synthetic child of the parent, by index in this walk, that the child about to be begun
     * hangs under.
     *
     * @param owner the parent's own node index
     * @return the index of the synthetic child the identity hook named with {@link #under(long)}
     * @throws IllegalStateException if the parent declared no synthetic child with that key in
     *                               this walk
     */
    public int pendingHostIndex(int owner) {
        // A widget's synthetic children are begun inside its describe hook, so they sit right
        // after its own slot and before any widget child, each followed by its own subtree; the
        // scan steps from one direct child to the next over each subtree, so a table asking for
        // a row visits its rows and not every cell of the rows before it, and stops at the first
        // slot that is not one of them.
        for (int i = owner + 1; i < count && slots[i].synthetic;
                i = Math.max(slots[i].subtreeEnd, i + 1)) {
            if (slots[i].parent == owner && slots[i].syntheticKey == pendingHostKey) {
                return i;
            }
        }
        throw new IllegalStateException(
                "under(" + pendingHostKey + ") names no synthetic child this widget declared");
    }

    /**
     * Marks the node being described as hung under a synthetic node of its parent, which decides
     * its place among that node's children. The publish step calls this right after
     * {@link #begin}.
     */
    public void markHosted() {
        slot().hosted = true;
    }

    /**
     * How many names and descriptions this builder has carried over unresolved from the previous
     * walk, in total. A count and not a saving: the walk's tests read it to prove that a keyed
     * row's name is looked up under the identifier it was published with, which no output can
     * show.
     *
     * @return the running count
     */
    public long carriedOver() {
        return carriedOver;
    }

    /**
     * Applies the four states a widget's own flags cannot answer for. The publish step calls this
     * after the widget has described itself, carrying each flag down the walk.
     *
     * <p>Enabled and visible are inherited and focusable is pruned by both, because the toolkit's
     * own predicates answer only for the widget they are asked of while the keyboard's traversal
     * stops at the first ancestor that is hidden or disabled. A tree that read the predicates
     * directly would offer a whole disabled form as operable and have every invocation refused with
     * nothing said about why.
     *
     * @param enabled   whether this node and every ancestor are enabled, and it is not shadowed by
     *                  a modal
     * @param visible   whether this node and every ancestor are visible
     * @param showing   whether this node has pixels on screen
     * @param focusable whether the keyboard's traversal can reach this node
     * @param focused   whether it holds the focus
     */
    public void inherited(boolean enabled, boolean visible, boolean showing,
                          boolean focusable, boolean focused) {
        Slot s = slot();
        s.states = set(s.states, Accessible.State.ENABLED, enabled);
        s.states = set(s.states, Accessible.State.VISIBLE, visible);
        s.states = set(s.states, Accessible.State.SHOWING, showing);
        s.states = set(s.states, Accessible.State.FOCUSABLE, focusable);
        s.states = set(s.states, Accessible.State.FOCUSED, focused);
    }

    /**
     * Applies the inherited bits to a synthetic child, which {@link #inherited} cannot reach.
     *
     * <p>{@link #inherited} writes to the node the walk currently has open, and by the time the
     * walk has one, every synthetic child the widget declared has already been closed. Without
     * this a combo option, a menu row or a chart series would publish with enabled, visible and
     * showing all clear — a disabled, invisible, off-screen element on all three platforms — and
     * no widget could correct it, because those states are refused on the widget-facing surface by
     * design.
     *
     * <p>Focusable and focused are deliberately not parameters. A synthetic child is drawn by its
     * owner and is not a tab stop: the keyboard reaches the owner, and a set published focusable
     * that is larger than the set the keyboard reaches is the disagreement the modal rule states.
     *
     * @param index   the node's index in this walk, which the publish step already holds
     * @param enabled whether the owner and every ancestor of it are enabled; a child that called
     *                {@link #disabled()} publishes without it either way
     * @param visible whether the owner and every ancestor of it are visible
     * @param showing whether the owner has pixels on screen; a child that called
     *                {@link #offScreen()} publishes without it either way
     * @throws IndexOutOfBoundsException if {@code index} names no node in this walk
     */
    public void inheritedAt(int index, boolean enabled, boolean visible, boolean showing) {
        Objects.checkIndex(index, count);
        Slot s = slots[index];
        // Through the nesting, not one level deep (2026-09-14): a synthetic child under a
        // synthetic child that declared itself disabled is no more enabled than that parent, so
        // a cell of a refused row is refused with it. The parent was begun before this node and
        // so sits at a lower index, which is the order the publish step calls this in, so its
        // bit has already been settled by the time this one is read.
        boolean parentEnabled = s.parent == AccessibleNode.NONE || !slots[s.parent].synthetic
                || (slots[s.parent].states & (1L << Accessible.State.ENABLED.ordinal())) != 0;
        s.states = set(s.states, Accessible.State.ENABLED, enabled && !s.disabled && parentEnabled);
        s.states = set(s.states, Accessible.State.VISIBLE, visible);
        s.states = set(s.states, Accessible.State.SHOWING, showing && !s.offScreen);
    }

    /**
     * Whether the node at an index of the walk in progress is published {@code ENABLED}, as
     * {@link #inherited} or {@link #inheritedAt} settled it. Only the publish step reads it, after
     * that call, to know whether the node is one {@link #inoperableAt} applies to; a widget never
     * does. Asked from a describe hook it answers before the bit is settled, so it says nothing
     * about the widget's own node or any child the hook declared.
     *
     * @param index the node's index in this walk
     * @return whether the node carries {@link Accessible.State#ENABLED}
     * @throws IndexOutOfBoundsException if {@code index} names no node in this walk
     */
    public boolean isEnabledAt(int index) {
        Objects.checkIndex(index, count);
        return (slots[index].states & (1L << Accessible.State.ENABLED.ordinal())) != 0;
    }

    /**
     * Takes every operation off a node the scene will not operate, because it is not
     * {@code ENABLED}: it or an ancestor is disabled, or it lies outside the layer that owns
     * input &mdash; beneath an overlay of the scene, or in a window a native modal blocks
     * (ADR 039 §1.5 and §1.13, amended 2026-09-15) &mdash; or it is a synthetic child its owner
     * {@linkplain #disabled() narrowed}. The scene or the owner refuses every verb there, and the
     * platform is answered from the snapshot (semantics 5), so the node publishes no verb &mdash;
     * neither one it declared nor one its container claimed on it &mdash; and none of the setters
     * a facet implies: a value is published read-only and a text {@link Accessible.State#READ_ONLY}.
     * What the node says it is and holds is untouched. The publish step calls this after both
     * describe hooks ran and after the delegate routing was read; a widget never does.
     *
     * @param index the node's index in this walk
     * @throws IndexOutOfBoundsException if {@code index} names no node in this walk
     */
    public void inoperableAt(int index) {
        Objects.checkIndex(index, count);
        Slot s = slots[index];
        s.verbs = 0;
        s.delegated = 0;
        s.keyBinding = null; // the action facet's, and there is none
        if (s.hasValue) {
            s.valueReadOnly = true;
        }
        if (s.hasValue || s.hasText) {
            s.states |= 1L << Accessible.State.READ_ONLY.ordinal();
        }
    }

    private static long set(long states, Accessible.State state, boolean on) {
        long bit = 1L << state.ordinal();
        return on ? states | bit : states & ~bit;
    }

    /** @return how many nodes the walk has produced so far */
    public int nodeCount() {
        return count;
    }

    /**
     * Whether the node being described has a name yet, so that the publish step knows whether one
     * of its free defaults still has somewhere to go.
     *
     * @return whether anything named this node
     */
    public boolean hasName() {
        return !slot().nameText.isEmpty();
    }

    /**
     * Whether the node being described has a description yet.
     *
     * @return whether anything described this node
     */
    public boolean hasDescription() {
        return !slot().descriptionText.isEmpty();
    }

    /**
     * Whether the node at an index of the walk in progress is a synthetic child rather than a
     * widget's own node. The publish step asks, so that it can send an action to the right hook.
     *
     * @param index the node's index
     * @return whether it was declared with {@link #child(long)}
     */
    public boolean isSyntheticAt(int index) {
        return slots[index].synthetic;
    }

    /**
     * The key a synthetic child's owner gave it.
     *
     * @param index the node's index
     * @return the key, or {@code 0} when that node is a widget's own
     */
    public long syntheticKeyAt(int index) {
        return slots[index].syntheticKey;
    }

    /**
     * Links a node the walk has already finished to another one: the mirror half of a relation,
     * which by definition belongs to a node other than the one that declared it.
     *
     * @param index  the node's index
     * @param kind   what the link means; never {@code null}
     * @param target the widget at the other end, resolved at publish time
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public void relationAt(int index, Accessible.Relation kind, Object target) {
        Objects.requireNonNull(kind, "kind");
        if (target != null) {
            slots[index].addRelation(kind, target);
        }
    }

    /**
     * The identifier for one (owner, key) pair, minted on first use and handed back every time
     * that pair is asked for again.
     *
     * <p>The publish step uses it for a child whose parent claimed the right to key it — a pooled
     * list cell, whose identity is its data index and not the widget it is currently mounted in.
     *
     * @param owner the identifier of the node that owns the key's namespace
     * @param key   the key, unique within that namespace
     * @return the identifier that pair keeps
     */
    public long identify(long owner, long key) {
        return intern(owner, key);
    }

    /**
     * The identifier of the node at an index of the walk in progress.
     *
     * @param index the node's index
     * @return its identifier
     */
    public long idAt(int index) {
        return slots[index].id;
    }

    /**
     * Whether what the walk just produced differs, anywhere, from what was published last.
     *
     * <p>This is the question a damaged frame asks and the answer is usually no: damage is a coarse
     * trigger, and a caret blink, a hover ripple and a chart tween all damage something and change
     * no accessible fact. Answering it costs a comparison per field and allocates nothing.
     *
     * @return whether anything a reader could see has moved
     */
    public boolean changed() {
        if (count != previousCount) {
            return true;
        }
        for (int i = 0; i < count; i++) {
            if (differs(slots[i], previous[i])) {
                return true;
            }
        }
        // Every slot agrees, so a cursor inside this window is where it was; the one thing that
        // can still have moved is a cursor read off a popup window's tree, and it counts only
        // while the focused node's own subtree has none (decision 5).
        return foreignActiveDescendant != publishedForeignActiveDescendant
                && resolveActiveDescendant() != publishedActiveDescendant;
    }

    private static boolean differs(Slot a, Slot b) {
        return a.id != b.id
                || a.role != b.role
                || a.nameFrom != b.nameFrom
                || !a.nameText.equals(b.nameText)
                || !a.descriptionText.equals(b.descriptionText)
                || !a.locale.equals(b.locale)
                || a.states != b.states
                || a.x != b.x || a.y != b.y || a.width != b.width || a.height != b.height
                || a.parent != b.parent
                || a.toggle != b.toggle
                || a.expand != b.expand
                || a.hasValue != b.hasValue
                || (a.hasValue && (a.value != b.value || a.valueReadOnly != b.valueReadOnly
                        || a.valueEmpty != b.valueEmpty
                        || a.valueMin != b.valueMin
                        || a.valueMax != b.valueMax || a.valueStep != b.valueStep
                        || !Objects.equals(a.valueText, b.valueText)))
                || a.hasSelection != b.hasSelection
                || (a.hasSelection && (a.multiSelectable != b.multiSelectable
                        || a.selectionRequired != b.selectionRequired))
                || a.hasSelectionItem != b.hasSelectionItem
                || (a.hasSelectionItem && (a.selected != b.selected
                        || a.positionInSet != b.positionInSet || a.sizeOfSet != b.sizeOfSet
                        || a.selectionContainerless != b.selectionContainerless))
                || a.hasText != b.hasText
                || (a.hasText && (a.textWitness != b.textWitness
                        || a.caretOffset != b.caretOffset || a.caretAffinity != b.caretAffinity
                        || a.selectionStart != b.selectionStart || a.selectionEnd != b.selectionEnd
                        || a.lineCount != b.lineCount
                        || !Objects.equals(a.caretRect, b.caretRect)))
                || a.hasScroll != b.hasScroll
                || (a.hasScroll && (a.horizontalPercent != b.horizontalPercent
                        || a.verticalPercent != b.verticalPercent
                        || a.horizontalViewSize != b.horizontalViewSize
                        || a.verticalViewSize != b.verticalViewSize
                        || a.horizontallyScrollable != b.horizontallyScrollable
                        || a.verticallyScrollable != b.verticallyScrollable))
                || a.hasWindow != b.hasWindow
                || (a.hasWindow && (a.windowModal != b.windowModal
                        || a.windowCanMaximize != b.windowCanMaximize
                        || a.windowCanMinimize != b.windowCanMinimize
                        || a.windowState != b.windowState))
                || a.hasTable != b.hasTable
                || (a.hasTable && (a.tableRowCount != b.tableRowCount
                        || a.tableColumnCount != b.tableColumnCount))
                || a.hasCell != b.hasCell
                || (a.hasCell && (a.cellRow != b.cellRow || a.cellColumn != b.cellColumn))
                || a.hasHierarchy != b.hasHierarchy
                || (a.hasHierarchy && (a.level != b.level || a.hierarchyRow != b.hierarchyRow
                        || a.hierarchyRowCount != b.hierarchyRowCount))
                || a.verbs != b.verbs
                || !Objects.equals(a.keyBinding, b.keyBinding)
                || relationsDiffer(a, b);
    }

    private static boolean relationsDiffer(Slot a, Slot b) {
        if (a.relationCount != b.relationCount) {
            return true;
        }
        for (int i = 0; i < a.relationCount; i++) {
            if (a.relationKinds[i] != b.relationKinds[i]
                    || a.relationResolved[i] != b.relationResolved[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns every relation's target into the identifier of a node this walk published.
     *
     * <p><b>Between the walk and {@link #changed()}, and never after it.</b> A relation is declared
     * as a target rather than an identifier, because the node it names may not have been walked
     * yet; the identifier is only knowable once the walk is over. The comparison reads the
     * resolved identifier, so resolving it after the comparison would compare a target this walk
     * never filled in against one an earlier walk did — which reports a difference the first time
     * a relation starts resolving, and, worse, misses the one that matters: a relation whose target
     * moves to another node while nothing else about either end changes would keep publishing the
     * identifier of a node that is gone.
     *
     * @param resolve what turns a target into a node identifier, answering {@code 0} when this walk
     *                published none; never {@code null}
     * @throws NullPointerException if {@code resolve} is {@code null}
     */
    public void resolveRelations(RelationResolver resolve) {
        Objects.requireNonNull(resolve, "resolve");
        for (int i = 0; i < count; i++) {
            Slot s = slots[i];
            for (int r = 0; r < s.relationCount; r++) {
                s.relationResolved[r] = resolve.resolve(s.relationKinds[r], s.relationTargets[r]);
            }
        }
    }

    /**
     * What {@link #resolveRelations} turns a relation's target into a node identifier with. It
     * is handed the kind as well as the target, because one kind resolves differently: a
     * {@code LABEL_FOR} naming a composite lands on the child the composite says carries its
     * label (ADR 039 §1.5, amended 2026-09-14; decision 55), and only the walk knows that.
     */
    @FunctionalInterface
    public interface RelationResolver {
        /**
         * @param kind   what the link means
         * @param target the object the declaring hook named
         * @return the identifier of the node this walk published for it, or {@code 0} for none
         */
        long resolve(Accessible.Relation kind, Object target);
    }

    /**
     * Turns the walk into an immutable tree, and the difference from the last one into events.
     *
     * <p>This is the only step that allocates: the facet records are built here, and only for nodes
     * that carry them.
     *
     * @param focusedId   the identifier of the node holding the keyboard focus, or {@code 0}
     * @param screenX     the window's content origin in native screen coordinates
     * @param screenY     the same, vertically
     * @param factor      the multiplier from logical points to native screen coordinates
     * @param positioning whether the platform lets this window know where it is
     * @return the tree, ready to publish
     */
    public AccessibleTree publish(long focusedId, int screenX, int screenY, float factor,
                                  boolean positioning) {
        events.clear();
        resolveSelectionContainers();
        long activeDescendant = resolveActiveDescendant();
        // The links first, because the difference reads them: a child's index among its
        // siblings, and the order the siblings stood in, are facts of the published tree and
        // not of the walk -- a widget cell hung under a row takes its place by column, which is
        // not its slot order.
        linkSiblings();
        diff(activeDescendant);
        publishedActiveDescendant = activeDescendant;
        publishedForeignActiveDescendant = foreignActiveDescendant;
        AccessibleNode[] nodes = new AccessibleNode[count];
        for (int i = 0; i < count; i++) {
            nodes[i] = materialise(i, firstChild[i], lastChild[i],
                    nextSibling[i], previousSibling[i]);
        }
        generation++;
        AccessibleTree tree = new AccessibleTree(nodes, focusedId, activeDescendant, screenX,
                screenY, factor, positioning, sceneWidth, sceneHeight, sceneLocale, generation,
                sceneTag);
        swap();
        return tree;
    }

    // The sibling links of the walk being published and of the one published before it, kept
    // beside the slots and swapped with them: the difference reads a child's index and the
    // order its siblings stood in from both. Allocated on publishing frames only, and only when
    // a walk outgrows them.
    private int[] firstChild = new int[64];
    private int[] lastChild = new int[64];
    private int[] nextSibling = new int[64];
    private int[] previousSibling = new int[64];
    private int[] childIndex = new int[64];
    private int[] previousFirstChild = new int[64];
    private int[] previousNextSibling = new int[64];
    private int[] previousChildIndex = new int[64];

    /**
     * Builds the sibling links of the walk being published, in slot order except for a widget
     * hung under a synthetic row, which takes its place among the row's cells by column (ADR 039
     * §1.3, amended 2026-09-14); and stamps every node with its index among its siblings.
     */
    private void linkSiblings() {
        if (firstChild.length < count) {
            int grown = Math.max(count, firstChild.length * 2);
            firstChild = new int[grown];
            lastChild = new int[grown];
            nextSibling = new int[grown];
            previousSibling = new int[grown];
            childIndex = new int[grown];
        }
        java.util.Arrays.fill(firstChild, 0, count, AccessibleNode.NONE);
        java.util.Arrays.fill(lastChild, 0, count, AccessibleNode.NONE);
        java.util.Arrays.fill(nextSibling, 0, count, AccessibleNode.NONE);
        java.util.Arrays.fill(previousSibling, 0, count, AccessibleNode.NONE);
        for (int i = 0; i < count; i++) {
            int parent = slots[i].parent;
            if (parent == AccessibleNode.NONE) {
                continue;
            }
            if (slots[i].hosted && slots[i].hasCell) {
                // A widget hung under a synthetic row is walked after the row's own cells, so
                // slot order would put it last; among cells it takes its place by column, which
                // is the reading order a row has.
                int before = AccessibleNode.NONE;
                for (int c = firstChild[parent]; c != AccessibleNode.NONE; c = nextSibling[c]) {
                    if (slots[c].hasCell && slots[c].cellColumn > slots[i].cellColumn) {
                        before = c;
                        break;
                    }
                }
                if (before != AccessibleNode.NONE) {
                    int after = previousSibling[before];
                    nextSibling[i] = before;
                    previousSibling[i] = after;
                    previousSibling[before] = i;
                    if (after == AccessibleNode.NONE) {
                        firstChild[parent] = i;
                    } else {
                        nextSibling[after] = i;
                    }
                    continue;
                }
            }
            if (firstChild[parent] == AccessibleNode.NONE) {
                firstChild[parent] = i;
            } else {
                int last = lastChild[parent];
                nextSibling[last] = i;
                previousSibling[i] = last;
            }
            lastChild[parent] = i;
        }
        for (int i = 0; i < count; i++) {
            int at = 0;
            for (int c = firstChild[i]; c != AccessibleNode.NONE; c = nextSibling[c]) {
                childIndex[c] = at++;
            }
        }
    }

    private AccessibleNode materialise(int index, int firstChild, int lastChild,
                                       int nextSibling, int previousSibling) {
        Slot s = slots[index];
        AccessibleRelation[] relations = null;
        if (s.relationCount > 0) {
            int kept = 0;
            for (int r = 0; r < s.relationCount; r++) {
                if (s.relationResolved[r] != 0) {
                    kept++;
                }
            }
            if (kept > 0) {
                relations = new AccessibleRelation[kept];
                int at = 0;
                for (int r = 0; r < s.relationCount; r++) {
                    if (s.relationResolved[r] != 0) {
                        relations[at++] =
                                new AccessibleRelation(s.relationKinds[r], s.relationResolved[r]);
                    }
                }
            }
        }
        SelectionFacet selection = null;
        if (s.hasSelection) {
            selection = new SelectionFacet(s.multiSelectable, s.selectionRequired);
        }
        return new AccessibleNode(s.id, s.role, s.nameText, s.nameFrom, s.descriptionText,
                s.locale, s.states, s.x, s.y, s.width, s.height, relations,
                s.toggle < 0 ? null : new ToggleFacet(ToggleFacet.State.values()[s.toggle]),
                s.hasValue ? new ValueFacet(s.value, s.valueMin, s.valueMax, s.valueStep,
                        s.valueText, s.valueReadOnly, s.valueEmpty) : null,
                selection,
                s.hasSelectionItem ? new SelectionItemFacet(s.selected, s.positionInSet,
                        s.sizeOfSet, s.selectionContainerless) : null,
                s.expand < 0 ? null : new ExpandFacet(s.expand == 1),
                s.hasText ? new TextFacet(s.text, s.caretOffset, s.caretAffinity,
                        s.selectionStart, s.selectionEnd, s.lineCount, s.caretRect) : null,
                s.hasScroll ? new ScrollFacet(s.horizontalPercent, s.verticalPercent,
                        s.horizontalViewSize, s.verticalViewSize, s.horizontallyScrollable,
                        s.verticallyScrollable) : null,
                s.hasWindow ? new WindowFacet(s.windowModal, s.windowCanMaximize,
                        s.windowCanMinimize, s.windowState) : null,
                s.hasTable ? new TableFacet(s.tableRowCount, s.tableColumnCount) : null,
                s.hasCell ? new CellFacet(s.cellRow, s.cellColumn) : null,
                s.hasHierarchy ? new HierarchyFacet(s.level, s.hierarchyRow, s.hierarchyRowCount)
                        : null,
                s.verbs == 0 ? null : new ActionFacet(verbsOf(s.verbs), s.keyBinding),
                s.parent, firstChild, lastChild, nextSibling, previousSibling,
                s.selectionContainer);
    }

    /**
     * Resolves every member's selection container (semantics 1; ADR 039 §1.2, amended
     * 2026-09-14): the nearest ancestor with a {@link SelectionFacet}, climbing from the
     * member's published parent only through synthetic ancestors that lack one. A widget
     * ancestor carrying the facet counts (the tab strip for a tab header); a widget ancestor
     * without one ends the climb with no container, as the root does, and a member that
     * declared itself containerless is not climbed for. Once per publish, allocating nothing.
     */
    private void resolveSelectionContainers() {
        for (int i = 0; i < count; i++) {
            Slot s = slots[i];
            s.selectionContainer = AccessibleNode.NONE;
            if (!s.hasSelectionItem || s.selectionContainerless) {
                continue;
            }
            for (int at = s.parent; at != AccessibleNode.NONE; at = slots[at].parent) {
                if (slots[at].hasSelection) {
                    s.selectionContainer = at;
                    break;
                }
                if (!slots[at].synthetic) {
                    break;
                }
            }
        }
    }

    private static EnumSet<Accessible.Action> verbsOf(int mask) {
        EnumSet<Accessible.Action> set = EnumSet.noneOf(Accessible.Action.class);
        for (Accessible.Action action : Accessible.Action.values()) {
            if ((mask & (1 << action.ordinal())) != 0) {
                set.add(action);
            }
        }
        return set;
    }

    /**
     * The node the keyboard cursor is on, resolved once per publish and once per quiet-frame
     * comparison (ADR 039 §1.10, amended 2026-09-14; semantics 4): the first node published
     * {@code ACTIVE} strictly below the focused node, or, when the focused node's own subtree has
     * none, the cursor the walk read off the tree of a popup window that node opened. The
     * window node's own {@code ACTIVE} — which says the window is the desktop's — is never
     * counted, and no {@link SelectionFacet} is consulted on the way: a container that is not
     * the focused node, or below it, has no cursor of its own to publish.
     *
     * @return the active descendant's identifier, or {@code 0}
     */
    private long resolveActiveDescendant() {
        int focused = focusedIndex();
        if (focused < 0) {
            return 0;
        }
        long own = activeDescendantIn(slots, count, focused);
        return own != 0 ? own : foreignActiveDescendant;
    }

    /** The index of the node published {@code FOCUSED} in this walk, or {@code -1}. */
    private int focusedIndex() {
        long bit = 1L << Accessible.State.FOCUSED.ordinal();
        for (int i = 0; i < count; i++) {
            if ((slots[i].states & bit) != 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tells this walk where the cursor is inside a popup window the focused node opened, which
     * the walk reads off that window's published tree (decision 5): the answer
     * {@link AccessibleTree#activeDescendant()} falls back to when the focused node's own
     * subtree holds no {@code ACTIVE} node. The publish step calls this once per walk, after the
     * relations are resolved; {@code 0} when there is no such popup. Reset on every walk.
     *
     * @param id the identifier of the popup's cursor node, minted by the popup's own scene, or
     *           {@code 0}
     */
    public void foreignActiveDescendant(long id) {
        foreignActiveDescendant = id;
    }

    /**
     * The popup window's node that a node of this walk, or one of its ancestors, is the
     * controller for: the target of the nearest {@link Accessible.Relation#CONTROLLER_FOR}
     * relation resolved to an identifier another scene minted, climbing from the node through
     * its published parents. What the publish step asks for the focused node, so that it can
     * read the popup's cursor off that scene's tree (decision 5). Allocates nothing.
     *
     * @param nodeId the node to climb from, by identifier
     * @return the popup root's identifier, or {@code 0} when no such relation is published on
     *         the node or above it, or the identifier names nothing this walk published
     */
    public long foreignControllerTargetFrom(long nodeId) {
        for (int at = indexIn(slots, count, nodeId); at != AccessibleNode.NONE;
                at = slots[at].parent) {
            Slot s = slots[at];
            for (int r = 0; r < s.relationCount; r++) {
                long target = s.relationResolved[r];
                if (s.relationKinds[r] == Accessible.Relation.CONTROLLER_FOR && target != 0
                        && sceneTagOf(target) != sceneTag) {
                    return target;
                }
            }
        }
        return 0;
    }

    /**
     * The events the last {@link #publish} produced, in the order a bridge is handed them.
     *
     * @return the events; empty when the two trees agreed, and never modifiable
     */
    public List<AccessibleEvent> events() {
        return java.util.Collections.unmodifiableList(events);
    }

    private void diff(long activeDescendant) {
        // A publish that did not finish -- nothing on the path throws today; a describe hook
        // that throws aborts the walk before it -- leaves no note of itself for the next.
        clearPublishNotes();
        int boundsChanges = 0;
        for (int i = 0; i < count; i++) {
            Slot now = slots[i];
            Slot was = previousOf(now.id, i);
            if (was == null) {
                noteArrival(i);
                if ((now.states & (1L << Accessible.State.FOCUSED.ordinal())) != 0) {
                    // A node that arrives holding the focus is a focus change like any other
                    // (WINDOWS-NEW-12): a dialog's first field, a popup's list. Its states are
                    // read on discovery, as every new node's are, but the focus is the one
                    // thing a reader has to be told about rather than asked for.
                    focusArrived = now.id;
                }
                if (now.hasSelectionItem && now.selectionContainer != AccessibleNode.NONE) {
                    // A member that arrived selected is a selection that moved onto it: End
                    // onto an unrealized row publishes a brand-new selected node, and the
                    // container's selection changed as surely as if the row had been there
                    // (MODEL-NEW-6). No STATE_CHANGED for a new node: a client reads a node's
                    // states when it discovers it.
                    noteSelectionMove(now, null);
                }
                continue;
            }
            noteMove(i, was);
            if (!now.nameText.equals(was.nameText)) {
                add(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, now.id,
                        was.nameText, now.nameText));
            }
            if (!now.descriptionText.equals(was.descriptionText)) {
                add(AccessibleEvent.property(AccessibleEvent.Type.DESCRIPTION_CHANGED, now.id,
                        was.descriptionText, now.descriptionText));
            }
            long moved = now.states ^ was.states;
            if (moved != 0) {
                for (Accessible.State state : Accessible.State.values()) {
                    long bit = 1L << state.ordinal();
                    if ((moved & bit) == 0) {
                        continue;
                    }
                    boolean on = (now.states & bit) != 0;
                    add(AccessibleEvent.state(now.id, state, on));
                    if (state == Accessible.State.FOCUSED && on) {
                        focusArrived = now.id;
                    }
                }
            }
            if (now.hasValue && was.hasValue
                    && (now.value != was.value || now.valueEmpty != was.valueEmpty
                            || !Objects.equals(now.valueText, was.valueText))) {
                // The number, the text or the emptiness: a segment filled with its minimum
                // changes only the last two, and a reader has to hear it (CRIT-4). The event
                // carries the numbers; a bridge that speaks the text reads it off the tree it is
                // handed with the event, which is the tree the event came from.
                add(AccessibleEvent.property(AccessibleEvent.Type.VALUE_CHANGED, now.id,
                        was.value, now.value));
            }
            if (now.hasSelectionItem && now.selectionContainer != AccessibleNode.NONE) {
                noteSelectionMove(now, was);
            }
            if (now.hasText && was.hasText) {
                if (now.textWitness != was.textWitness && !now.text.equals(was.text)) {
                    addTextChange(now, was);
                }
                if (now.caretOffset != was.caretOffset || now.caretAffinity != was.caretAffinity) {
                    add(AccessibleEvent.of(AccessibleEvent.Type.CARET_MOVED, now.id));
                }
                if (now.selectionStart != was.selectionStart
                        || now.selectionEnd != was.selectionEnd) {
                    add(AccessibleEvent.of(AccessibleEvent.Type.TEXT_SELECTION_CHANGED, now.id));
                }
            }
            if (now.x != was.x || now.y != was.y
                    || now.width != was.width || now.height != was.height) {
                boundsChanges++;
                if (boundsChanges <= BOUNDS_BUDGET) {
                    add(AccessibleEvent.property(AccessibleEvent.Type.BOUNDS_CHANGED, now.id,
                            new Rect(was.x, was.y, was.width, was.height),
                            new Rect(now.x, now.y, now.width, now.height)));
                }
            }
        }
        if (boundsChanges > BOUNDS_BUDGET) {
            budgeted.removeIf(event -> event.type() == AccessibleEvent.Type.BOUNDS_CHANGED);
            add(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 0));
        }
        for (int i = 0; i < previousCount; i++) {
            Slot gone = previous[i];
            if (currentOf(gone.id, i) == null) {
                add(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, gone.id));
                noteDeparture(i);
                if (gone.hasSelectionItem && gone.selected
                        && gone.selectionContainer != AccessibleNode.NONE) {
                    // A selected member that left the tree left its container's selection too,
                    // and the container, when it survives, is told (MODEL-NEW-6).
                    int container = indexIn(slots, count, previous[gone.selectionContainer].id);
                    if (container >= 0) {
                        noteSelectionMove(container, gone.id, false);
                    }
                }
            }
        }
        // The reserved tail, in this order: what a reader is directed by, kept outside the
        // budget so that a collapse never loses it (semantics 7).
        addStructureChanges();
        if (focusArrived != 0) {
            reserve(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, focusArrived));
            focusArrived = 0;
        }
        // Exactly one cursor event per publish, on the focused node, when the cursor it
        // resolves moved -- a newly focused node whose cursor differs from the last focused
        // node's included -- and none from an unfocused container or a scene with nothing
        // focused (decision 6; semantics 4). The event carries the cursor as of the previous
        // publish and now, so a bridge can address the node it is leaving as well.
        int focused = focusedIndex();
        if (focused >= 0 && activeDescendant != publishedActiveDescendant) {
            reserve(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    slots[focused].id, publishedActiveDescendant, activeDescendant));
        }
        addSelectionChanges();
        addWindowActivation();
        // Past the budget the per-node events become one INVALIDATED, which a bridge answers by
        // sweeping what it holds against the tree it was handed (§1.10); the tail follows
        // either way, so a bridge that swept still hears where the user is.
        if (budgeted.size() > EVENT_BUDGET) {
            events.add(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
        } else {
            events.addAll(budgeted);
        }
        events.addAll(tail);
        clearPublishNotes();
    }

    /**
     * Drops what one publish noted on the way to its events: the budgeted list, the reserved
     * tail, the structure and selection notes, a focus that arrived. Called before a diff and
     * after it, and when the published tree is forgotten, so that nothing of one publish reaches
     * the next whatever happened in between. Clearing an empty list allocates nothing.
     */
    private void clearPublishNotes() {
        budgeted.clear();
        tail.clear();
        structCount = 0;
        moveCount = 0;
        focusArrived = 0;
    }

    /** A per-node event, bounded by the budget: one past it is kept only to say it was passed. */
    private void add(AccessibleEvent event) {
        if (budgeted.size() <= EVENT_BUDGET) {
            budgeted.add(event);
        }
    }

    /** A tail event, outside the budget. */
    private void reserve(AccessibleEvent event) {
        tail.add(event);
    }

    /**
     * The window's activation, derived by the difference from the window node's {@code ACTIVE}
     * bit (settled window-activation-order; LINUX-NEW-15, LAB-NEW-2): a window node — the
     * root, when it is a real window — whose bit came on, or that arrived with it on, is
     * {@code WINDOW_ACTIVATED}; one whose bit went off is {@code WINDOW_DEACTIVATED}. Named on
     * that node and emitted in the same publish as the tree that says so, never handed to a
     * bridge ahead of it: Orca reads the frame's state when the event arrives, and an event
     * before the tree found a frame that still said it was not active.
     */
    private void addWindowActivation() {
        if (count == 0 || slots[0].role != Accessible.Role.WINDOW) {
            return;
        }
        Slot now = slots[0];
        Slot was = previousOf(now.id, 0);
        long bit = 1L << Accessible.State.ACTIVE.ordinal();
        boolean active = (now.states & bit) != 0;
        boolean wasActive = was != null && (was.states & bit) != 0;
        if (active && !wasActive) {
            reserve(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_ACTIVATED, now.id));
        } else if (!active && wasActive) {
            reserve(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_DEACTIVATED, now.id));
        }
    }

    // The selection moves one publish found, before they are grouped per container: the
    // container's index in this walk, the member's identifier, and whether it entered or left.
    // Grown once, reused; a publish that moves no selection touches none of it.
    private int[] moveContainers = new int[16];
    private long[] moveMembers = new long[16];
    private boolean[] moveEntered = new boolean[16];
    private int moveCount;

    /**
     * Notes what a surviving or new member did to its container's selection this publish: a
     * member whose container is the same as last time and whose selected bit flipped entered
     * or left it; one whose container changed left the old one (when that survives) and entered
     * the new one when selected; one that is new entered it when selected.
     */
    private void noteSelectionMove(Slot now, Slot was) {
        int container = now.selectionContainer;
        // A container that is itself new in this publish is read on discovery, its selection
        // with it: the first publish of a scene, or a popup opening with an option chosen, is
        // not a selection that moved, and the structure change that reports the container's
        // arrival is the whole of it (as a new node's states are not diffed either).
        boolean containerSurvived = previousOf(slots[container].id, container) != null;
        if (was == null) {
            if (now.selected && containerSurvived) {
                noteSelectionMove(container, now.id, true);
            }
            return;
        }
        long wasContainerId = was.selectionContainer == AccessibleNode.NONE
                ? 0 : previous[was.selectionContainer].id;
        if (wasContainerId == slots[container].id) {
            if (now.selected != was.selected) {
                noteSelectionMove(container, now.id, now.selected);
            }
            return;
        }
        if (was.selected && wasContainerId != 0) {
            int former = indexIn(slots, count, wasContainerId);
            if (former >= 0) {
                noteSelectionMove(former, now.id, false);
            }
        }
        if (now.selected && containerSurvived) {
            noteSelectionMove(container, now.id, true);
        }
    }

    private void noteSelectionMove(int container, long member, boolean entered) {
        if (moveCount == moveContainers.length) {
            int grown = moveCount * 2;
            moveContainers = java.util.Arrays.copyOf(moveContainers, grown);
            moveMembers = java.util.Arrays.copyOf(moveMembers, grown);
            moveEntered = java.util.Arrays.copyOf(moveEntered, grown);
        }
        moveContainers[moveCount] = container;
        moveMembers[moveCount] = member;
        moveEntered[moveCount] = entered;
        moveCount++;
    }

    // The structure moves one publish found, before they are grouped per parent: the parent's
    // index in this walk, the child's identifier, its index (now, or former for a removal), the
    // other parent for a child that moved between two, and which of the three lists it goes in.
    // Grown once, reused; a publish that moves no structure touches none of it.
    private static final byte ADDED = 0;
    private static final byte REMOVED = 1;
    private static final byte REORDERED = 2;
    private int[] structParents = new int[16];
    private long[] structChildren = new long[16];
    private int[] structIndices = new int[16];
    private long[] structOthers = new long[16];
    private byte[] structKinds = new byte[16];
    private int structCount;

    /**
     * A node new in this publish is one child added under its parent -- when that parent
     * survives. Under a parent that is itself new it is nothing of its own: the parent's
     * arrival on the nearest surviving ancestor already says the whole subtree appeared, and
     * one event per node inside a new subtree is what pushed a fifty-option popup into the
     * budget collapse (MODEL-NEW-8).
     */
    private void noteArrival(int index) {
        int parent = slots[index].parent;
        if (parent == AccessibleNode.NONE || previousOf(slots[parent].id, parent) == null) {
            return;
        }
        noteStructure(parent, slots[index].id, childIndex[index], 0, ADDED);
    }

    /**
     * A surviving node whose parent is not the one it had is one child removed from the old
     * parent and added under the new, on each of them that survives, each naming the other
     * (MODEL-NEW-4). Its place among unchanged siblings is the reorder pass's.
     */
    private void noteMove(int index, Slot was) {
        int parent = slots[index].parent;
        long nowParent = parent == AccessibleNode.NONE ? 0 : slots[parent].id;
        long wasParent = was.parent == AccessibleNode.NONE ? 0 : previous[was.parent].id;
        if (nowParent == wasParent) {
            return;
        }
        if (wasParent != 0) {
            int former = slotIndexOf(slots, count, wasParent, was.parent);
            if (former >= 0) {
                noteStructure(former, slots[index].id,
                        previousChildIndex[slotIndexOf(previous, previousCount, slots[index].id,
                                index)], nowParent, REMOVED);
            }
        }
        if (parent != AccessibleNode.NONE && previousOf(nowParent, parent) != null) {
            noteStructure(parent, slots[index].id, childIndex[index], wasParent, ADDED);
        }
    }

    /**
     * A node gone from the tree is one child removed from its former parent -- when that
     * parent survives; under a parent that is gone too it is nothing of its own, for
     * {@link #noteArrival}'s reason in reverse. Its {@code NODE_DESTROYED} stands either way.
     */
    private void noteDeparture(int previousIndex) {
        int parent = previous[previousIndex].parent;
        if (parent == AccessibleNode.NONE) {
            return;
        }
        int survivor = slotIndexOf(slots, count, previous[parent].id, parent);
        if (survivor < 0) {
            return;
        }
        noteStructure(survivor, previous[previousIndex].id, previousChildIndex[previousIndex],
                0, REMOVED);
    }

    private void noteStructure(int parent, long child, int index, long other, byte kind) {
        if (structCount == structParents.length) {
            int grown = structCount * 2;
            structParents = java.util.Arrays.copyOf(structParents, grown);
            structChildren = java.util.Arrays.copyOf(structChildren, grown);
            structIndices = java.util.Arrays.copyOf(structIndices, grown);
            structOthers = java.util.Arrays.copyOf(structOthers, grown);
            structKinds = java.util.Arrays.copyOf(structKinds, grown);
        }
        structParents[structCount] = parent;
        structChildren[structCount] = child;
        structIndices[structCount] = index;
        structOthers[structCount] = other;
        structKinds[structCount] = kind;
        structCount++;
    }

    /**
     * The surviving children of a surviving parent that stand in another order than they did:
     * the sequence of children under it now that were under it before, against the sequence of
     * children under it before that are under it now, both read off the published links. An
     * insertion or a removal between them moves no rank and is not a reorder; a child whose
     * rank in the two sequences differs is one (MODEL-NEW-4: a table sorted with its rows'
     * identifiers kept raised nothing).
     */
    private void noteReorders() {
        for (int p = 0; p < count; p++) {
            if (firstChild[p] == AccessibleNode.NONE) {
                continue;
            }
            Slot was = previousOf(slots[p].id, p);
            if (was == null) {
                continue;
            }
            int before = slotIndexOf(previous, previousCount, was.id, p);
            // The children still under this parent, in their former order, into the scratch.
            int kept = 0;
            for (int c = previousFirstChild[before]; c != AccessibleNode.NONE;
                    c = previousNextSibling[c]) {
                Slot now = currentOf(previous[c].id, c);
                if (now == null || now.parent != p) {
                    continue;
                }
                if (kept == reorderScratch.length) {
                    reorderScratch = java.util.Arrays.copyOf(reorderScratch, kept * 2);
                }
                reorderScratch[kept++] = previous[c].id;
            }
            // The same children in their order now: a child whose rank differs is a reorder.
            int rank = 0;
            boolean moved = false;
            for (int c = firstChild[p]; c != AccessibleNode.NONE; c = nextSibling[c]) {
                if (!stayedUnder(c, before)) {
                    continue;
                }
                moved |= reorderScratch[rank++] != slots[c].id;
            }
            if (!moved) {
                continue;
            }
            rank = 0;
            for (int c = firstChild[p]; c != AccessibleNode.NONE; c = nextSibling[c]) {
                if (!stayedUnder(c, before)) {
                    continue;
                }
                if (reorderScratch[rank++] != slots[c].id) {
                    noteStructure(p, slots[c].id, childIndex[c], 0, REORDERED);
                }
            }
        }
    }

    /** Whether the node at {@code index} was published under the same parent last time. */
    private boolean stayedUnder(int index, int previousParent) {
        Slot was = previousOf(slots[index].id, index);
        return was != null && was.parent == previousParent;
    }

    private long[] reorderScratch = new long[64];

    /**
     * One {@code STRUCTURE_CHANGED} per surviving parent whose children moved, in reading order
     * of the parents, carrying the added, removed and reordered children (ADR 039 §1.10,
     * amended 2026-09-14). The notes were taken in walk order, so each list comes out in
     * reading order too.
     */
    private void addStructureChanges() {
        noteReorders();
        if (structCount == 0) {
            return;
        }
        bucketBySlot(structParents, structCount);
        for (int p = 0; p < count; p++) {
            int from = bucketOffsets[p];
            int to = bucketOffsets[p + 1];
            if (from == to) {
                continue;
            }
            List<AccessibleEvent.Child> added = new ArrayList<>();
            List<AccessibleEvent.Child> removed = new ArrayList<>();
            List<AccessibleEvent.Child> reordered = new ArrayList<>();
            for (int i = from; i < to; i++) {
                int n = bucketOrder[i];
                AccessibleEvent.Child child = new AccessibleEvent.Child(structChildren[n],
                        structIndices[n], structOthers[n]);
                switch (structKinds[n]) {
                    case ADDED -> added.add(child);
                    case REMOVED -> removed.add(child);
                    default -> reordered.add(child);
                }
            }
            reserve(AccessibleEvent.structure(slots[p].id, added, removed, reordered));
        }
        structCount = 0;
    }

    // The notes of one kind grouped by the slot they are on, for the two passes above: after
    // bucketBySlot the notes on slot s are bucketOrder[bucketOffsets[s] .. bucketOffsets[s + 1]),
    // in the order they were noted. Grown once, reused; a publish with no notes touches neither.
    private int[] bucketOffsets = new int[64];
    private int[] bucketOrder = new int[16];

    /**
     * Groups {@code notes} notes by the slot each is on -- a counting sort over the slots,
     * linear in the notes and the nodes -- so that a per-slot pass reads one contiguous run per
     * slot instead of scanning every note for every node, which on a table whose sort moved
     * fifty of five thousand rows was a quarter of a million comparisons per publish. Stable,
     * so walk order survives into reading order.
     *
     * @param slotOfNote the slot each note is on, {@code [0, count)}
     * @param notes      how many notes
     */
    private void bucketBySlot(int[] slotOfNote, int notes) {
        int size = count + 2;
        if (bucketOffsets.length < size) {
            bucketOffsets = new int[Math.max(size, bucketOffsets.length * 2)];
        }
        if (bucketOrder.length < notes) {
            bucketOrder = new int[Math.max(notes, bucketOrder.length * 2)];
        }
        java.util.Arrays.fill(bucketOffsets, 0, size, 0);
        for (int n = 0; n < notes; n++) {
            bucketOffsets[slotOfNote[n] + 2]++;
        }
        for (int s = 2; s < size; s++) {
            bucketOffsets[s] += bucketOffsets[s - 1];
        }
        // Placed through the slot's start cursor, which each placement advances: when the last
        // note is down, bucketOffsets[s] is slot s's start and bucketOffsets[s + 1] its end.
        for (int n = 0; n < notes; n++) {
            bucketOrder[bucketOffsets[slotOfNote[n] + 1]++] = n;
        }
    }

    /**
     * One {@code SELECTION_CHANGED} per container whose selection moved, in reading order of
     * the containers, carrying the members that entered and left it (decision 9; semantics 1).
     * The moves were noted in walk order, so the members come out in reading order too.
     */
    private void addSelectionChanges() {
        if (moveCount == 0) {
            return;
        }
        bucketBySlot(moveContainers, moveCount);
        for (int c = 0; c < count; c++) {
            int from = bucketOffsets[c];
            int to = bucketOffsets[c + 1];
            if (from == to) {
                continue;
            }
            int entered = 0;
            for (int i = from; i < to; i++) {
                if (moveEntered[bucketOrder[i]]) {
                    entered++;
                }
            }
            long[] added = new long[entered];
            long[] removed = new long[to - from - entered];
            int a = 0;
            int r = 0;
            for (int i = from; i < to; i++) {
                int m = bucketOrder[i];
                if (moveEntered[m]) {
                    added[a++] = moveMembers[m];
                } else {
                    removed[r++] = moveMembers[m];
                }
            }
            reserve(AccessibleEvent.selection(slots[c].id, slots[c].multiSelectable, added,
                    removed));
        }
        moveCount = 0;
    }

    private void addTextChange(Slot now, Slot was) {
        String from = was.text;
        String to = now.text;
        int prefix = 0;
        int limit = Math.min(from.length(), to.length());
        while (prefix < limit && from.charAt(prefix) == to.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && from.charAt(from.length() - 1 - suffix) == to.charAt(to.length() - 1 - suffix)) {
            suffix++;
        }
        add(AccessibleEvent.text(now.id, prefix,
                from.length() - prefix - suffix, to.length() - prefix - suffix, from, to));
    }

    private static long activeDescendantIn(Slot[] pool, int size, int container) {
        if (container < 0) {
            return 0;
        }
        long bit = 1L << Accessible.State.ACTIVE.ordinal();
        for (int i = container + 1; i < size; i++) {
            boolean descendant = false;
            for (int at = pool[i].parent; at != AccessibleNode.NONE; at = pool[at].parent) {
                if (at == container) {
                    descendant = true;
                    break;
                }
            }
            if (descendant && (pool[i].states & bit) != 0) {
                return pool[i].id;
            }
        }
        return 0;
    }

    private static int indexIn(Slot[] pool, int size, long id) {
        for (int i = 0; i < size; i++) {
            if (pool[i].id == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The previously published slot for an identifier, found by index hint first.
     *
     * <p>A frame that changed nothing walks the same nodes in the same order, so the hint hits
     * every time and this is a single comparison. A structural change misses and falls back to a
     * scan, which is the frame that was going to publish anyway.
     */
    private Slot previousOf(long id, int hint) {
        return slotOf(previous, previousCount, id, hint);
    }

    /** The slot for an identifier in the walk just finished, found by index hint first. */
    private Slot currentOf(long id, int hint) {
        return slotOf(slots, count, id, hint);
    }

    private static Slot slotOf(Slot[] pool, int size, long id, int hint) {
        int index = slotIndexOf(pool, size, id, hint);
        return index < 0 ? null : pool[index];
    }

    /** The index of an identifier's slot in a pool, found by index hint first; {@code -1} for none. */
    private static int slotIndexOf(Slot[] pool, int size, long id, int hint) {
        if (hint >= 0 && hint < size && pool[hint].id == id) {
            return hint;
        }
        return indexIn(pool, size, id);
    }

    private void swap() {
        Slot[] held = previous;
        previous = slots;
        previousCount = count;
        slots = held.length >= slots.length ? held : newSlots(slots.length);
        count = 0;
        current = AccessibleNode.NONE;
        int[] heldFirst = previousFirstChild;
        int[] heldNext = previousNextSibling;
        int[] heldIndex = previousChildIndex;
        previousFirstChild = firstChild;
        previousNextSibling = nextSibling;
        previousChildIndex = childIndex;
        firstChild = heldFirst.length >= previousFirstChild.length
                ? heldFirst : new int[previousFirstChild.length];
        nextSibling = heldNext.length >= previousNextSibling.length
                ? heldNext : new int[previousNextSibling.length];
        childIndex = heldIndex.length >= previousChildIndex.length
                ? heldIndex : new int[previousChildIndex.length];
    }

    /**
     * Forgets what was published, so the next walk differs from nothing and publishes everything.
     * The publish step calls this when a scene is bound over a window that held another one.
     */
    public void forgetPublished() {
        previousCount = 0;
        publishedActiveDescendant = 0;
        publishedForeignActiveDescendant = 0;
        clearPublishNotes();
    }

    private Slot slot() {
        if (namingChild) {
            // The node open right now is the PARENT's, and the identity hook is running for a
            // child that has no slot yet: a role or a name written here would land on the parent
            // silently. The hook answers key() and under(), which read no slot, and nothing else;
            // refused in the same voice key() uses when called from the wrong hook.
            throw new IllegalStateException(
                    "onAccessibilityChildIdentity answers only key() and under(): describe the "
                            + "child from onAccessibilityChild, and the parent from onAccessibility");
        }
        if (current == AccessibleNode.NONE) {
            throw new IllegalStateException("no node is being described");
        }
        return slots[current];
    }

    // --------------------------------------------------------- identity

    /**
     * How many low bits of an identifier are the serial minted inside one scene; the bits above
     * them are the scene's tag. Forty-two bits is four trillion identifiers per window, which no
     * window mints, and twenty-one bits above it is two million scenes per process, which no
     * process opens; and a tag is never zero, so a serial minted here is never mistaken for one a
     * test wrote by hand.
     */
    private static final int SERIAL_BITS = 42;

    /** One tag per builder, which is one per scene, handed out for the life of the process. */
    private static final java.util.concurrent.atomic.AtomicLong SCENES =
            new java.util.concurrent.atomic.AtomicLong();

    private final long sceneTag = SCENES.incrementAndGet();

    private long nextIdentifier;

    /**
     * Mints one identifier that no node in any window of this process has had.
     *
     * <p>The publish step calls this for a widget's own serial; this class calls it for a
     * synthetic child's. The counter is this scene's and the identifier carries the scene's tag
     * above it (ADR 039 §1.3, amended 2026-09-14): a relation can therefore name a node in another
     * window &mdash; a native popup's root names the field that opened it &mdash; and a bridge
     * holding several windows' trees can tell from the number alone which tree to ask
     * ({@link AccessibleTree#holds(long)}), without a registry that would have to be kept in
     * step with every publish.
     *
     * @return an identifier no other node in the process has had, and never {@code 0}
     */
    public long mint() {
        return (sceneTag << SERIAL_BITS) | ++nextIdentifier;
    }

    /**
     * @return the tag every identifier this builder mints carries: the scene's, never {@code 0}
     */
    public long sceneTag() {
        return sceneTag;
    }

    /**
     * The tag of the scene that minted an identifier.
     *
     * @param id a node identifier
     * @return the tag of the builder that minted it, or {@code 0} for an identifier no builder
     *         minted
     */
    public static long sceneTagOf(long id) {
        return id >>> SERIAL_BITS;
    }

    /**
     * The identifier a synthetic child keeps: minted once per (owner, key) pair and handed back
     * every time that pair is asked for again, so a row scrolled away and back is the same element
     * to whoever is holding it.
     *
     * <p>The table is bounded by what is live, not by a number. When it is full, the pairs
     * neither this walk nor the previous one asked for are dropped &mdash; a row scrolled away
     * two frames ago or longer &mdash; and dropping one raises nothing: a node that left the tree
     * was already destroyed by the difference between two trees, and the entry only ever existed
     * so that the identifier could come back. When every pair is that recent the table grows
     * instead, because a pair dropped now is one this walk is about to ask for again, and a
     * fresh identifier for it is a new element to every client on every frame for as long as the
     * walk stays this large (MODEL-NEW-7). Growth happens on a walk that is minting, which is a
     * walk that publishes; a quiet frame finds every pair and allocates nothing.
     */
    private long intern(long owner, long key) {
        int mask = internOwners.length - 1;
        int at = (int) mix(owner * 31 + key) & mask;
        for (int probe = 0; probe < internOwners.length; probe++) {
            int index = (at + probe) & mask;
            if (internIds[index] == 0) {
                break;
            }
            if (internOwners[index] == owner && internKeys[index] == key) {
                internSeen[index] = walkSerial;
                return internIds[index];
            }
        }
        if (internSize >= internCapacity) {
            if (!evictInternedNotSeenLately()) {
                internCapacity *= 2;
            }
            rebuildInternTable();
            mask = internOwners.length - 1;
            at = (int) mix(owner * 31 + key) & mask;
        }
        int index = at;
        while (internIds[index] != 0) {
            index = (index + 1) & mask;
        }
        internOwners[index] = owner;
        internKeys[index] = key;
        internIds[index] = mint();
        internSeen[index] = walkSerial;
        internSize++;
        return internIds[index];
    }

    /**
     * Forgets every pair neither this walk nor the previous one looked up, by zeroing its
     * identifier in place; {@link #rebuildInternTable()} closes the holes.
     *
     * @return whether anything was forgotten
     */
    private boolean evictInternedNotSeenLately() {
        boolean any = false;
        for (int i = 0; i < internIds.length; i++) {
            if (internIds[i] != 0 && internSeen[i] < walkSerial - 1) {
                internIds[i] = 0;
                any = true;
            }
        }
        return any;
    }

    /**
     * Re-inserts every live pair into fresh arrays sized for {@link #internCapacity}. Rebuild
     * rather than tombstone: an open-addressed table cannot have a hole punched in it without
     * breaking every probe that ran past it, and a rebuild happens once per overflow rather than
     * once per lookup.
     */
    private void rebuildInternTable() {
        long[] owners = internOwners;
        long[] keys = internKeys;
        long[] ids = internIds;
        long[] seen = internSeen;
        internOwners = new long[internCapacity * 2];
        internKeys = new long[internCapacity * 2];
        internIds = new long[internCapacity * 2];
        internSeen = new long[internCapacity * 2];
        internSize = 0;
        int mask = internOwners.length - 1;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == 0) {
                continue;
            }
            int index = (int) mix(owners[i] * 31 + keys[i]) & mask;
            while (internIds[index] != 0) {
                index = (index + 1) & mask;
            }
            internOwners[index] = owners[i];
            internKeys[index] = keys[i];
            internIds[index] = ids[i];
            internSeen[index] = seen[i];
            internSize++;
        }
    }

    private static long mix(long value) {
        long x = value * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        return x & Long.MAX_VALUE;
    }
}
