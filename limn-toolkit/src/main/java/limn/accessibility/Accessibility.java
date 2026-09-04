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
import java.util.function.ToLongFunction;

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

    /**
     * How many differing nodes a single publish reports individually before the difference stops
     * being a list of events and becomes "everything changed".
     *
     * <p>The bound is here rather than in each bridge so that all three get the same guarantee
     * from one implementation, and so that it can be asserted without a platform. Past it the
     * publish emits one {@link AccessibleEvent.Type#INVALIDATED}, which a bridge answers by
     * reconciling whatever it holds against the tree it was just handed — a full sweep, which is
     * stronger than replaying the events that were dropped and is the operation it needs on a
     * rebind anyway.
     */
    public static final int EVENT_BUDGET = 256;

    /**
     * How many nodes may move at once before their boxes are reported as one window-level event.
     * A scroll moves every row it shows, and a client is better served by "re-read the window"
     * than by four hundred rectangles.
     */
    private static final int BOUNDS_BUDGET = 32;

    /** Interned synthetic identifiers kept, past which the least recently published are dropped. */
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
        long childKey;
        boolean hasChildKey;
        boolean synthetic;
        long syntheticKey;
        boolean roleDeclared;

        int toggle;                     // -1 none, else a ToggleFacet.State ordinal
        boolean hasValue;
        double value;
        double valueMin;
        double valueMax;
        double valueStep;
        String valueText;
        long valueWitness;
        boolean hasSelection;
        boolean multiSelectable;
        boolean selectionRequired;
        boolean hasSelectionItem;
        boolean selected;
        int positionInSet;
        int sizeOfSet;
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
        int verbs;                      // bit per Accessible.Action ordinal
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
            childKey = 0;
            hasChildKey = false;
            synthetic = false;
            syntheticKey = 0;
            roleDeclared = false;
            toggle = -1;
            hasValue = false;
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
            verbs = 0;
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
                    && !hasText && !hasScroll && !hasWindow;
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

    private final List<AccessibleEvent> events = new ArrayList<>();

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
     * @param source the widget's own string; {@code null} clears the name
     * @param from   where the name came from, which decides which attribute one platform
     *               publishes it in
     * @throws NullPointerException if {@code from} is {@code null}
     */
    public void name(I18nString source, Accessible.NameFrom from) {
        Objects.requireNonNull(from, "from");
        Slot s = slot();
        s.nameFrom = from;
        s.nameWitness = 0;
        if (source == null) {
            s.nameSource = null;
            s.nameText = "";
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
            return;
        }
        s.nameSource = source;
        s.nameLocale = locale;
        s.nameEpoch = epoch;
        s.nameText = source.get();
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
     * @param cached  the string the widget is holding; {@code null} clears the name
     * @param witness a value that changes whenever {@code cached} does, and is otherwise
     *                meaningless
     * @param from    where the name came from
     * @throws NullPointerException if {@code from} is {@code null}
     */
    public void name(String cached, long witness, Accessible.NameFrom from) {
        Objects.requireNonNull(from, "from");
        Slot s = slot();
        s.nameFrom = from;
        s.nameSource = null;
        s.nameWitness = witness;
        s.nameLocale = s.locale;
        s.nameEpoch = I18n.epoch();
        s.nameText = cached == null ? "" : cached;
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
     * <p>The five states a facet expresses — checked, mixed, expanded, selected, read-only — are
     * derived from that facet and setting them here is ignored, so one fact keeps one home. The
     * five the publish step owns — enabled, visible, showing, focusable and focused — are ignored
     * for a different reason: a widget's own flag answers only for itself, while the tree has to
     * agree with a keyboard whose traversal stops at the first ancestor that is hidden or
     * disabled.
     *
     * @param state what to set; never {@code null}
     * @param on    whether it holds
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void state(Accessible.State state, boolean on) {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case CHECKED, MIXED, EXPANDED, SELECTED, READ_ONLY,
                 ENABLED, VISIBLE, SHOWING, FOCUSABLE, FOCUSED -> {
                return;
            }
            default -> { }
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
        Slot s = slot();
        s.hasValue = true;
        s.value = value;
        s.valueMin = min;
        s.valueMax = max;
        s.valueStep = step;
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
     * keyboard cursor is on is the first descendant published with
     * {@link Accessible.State#ACTIVE}, and the publish step fills that in.
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
        long bit = 1L << Accessible.State.SELECTED.ordinal();
        if (selected) {
            s.states |= bit;
        } else {
            s.states &= ~bit;
        }
    }

    /**
     * Declares that this node opens and closes.
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
     * Gives this node the identity key its parent chose for it, from inside that parent's
     * describe-a-child hook.
     *
     * <p>This is what keeps a recycled list cell from carrying row three's identifier to row nine.
     * A container that pools its children owns their identity, and nothing else can: the widget
     * object is the wrong key the moment it is reused, and the data index is the right one.
     *
     * @param key a value unique among this parent's children and stable for as long as the child
     *            stands for the same thing
     */
    public void key(long key) {
        Slot s = slot();
        s.childKey = key;
        s.hasChildKey = true;
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
     * The identity key the node's parent chose for it, if it chose one.
     *
     * @return the key, or {@code 0} when the parent chose none
     */
    public long childKey() {
        return slot().childKey;
    }

    /**
     * Whether the node's parent chose an identity key for it.
     *
     * @return whether {@link #key(long)} was called while describing this node
     */
    public boolean hasChildKey() {
        return slot().hasChildKey;
    }

    /**
     * Replaces the node's identifier: what the publish step does once a parent has claimed the
     * right to key its own child.
     *
     * @param id the identifier to carry
     */
    public void reidentify(long id) {
        slot().id = id;
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
        return false;
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
                || (a.hasValue && (a.value != b.value || a.valueMin != b.valueMin
                        || a.valueMax != b.valueMax || a.valueStep != b.valueStep
                        || !Objects.equals(a.valueText, b.valueText)))
                || a.hasSelection != b.hasSelection
                || (a.hasSelection && (a.multiSelectable != b.multiSelectable
                        || a.selectionRequired != b.selectionRequired))
                || a.hasSelectionItem != b.hasSelectionItem
                || (a.hasSelectionItem && (a.selected != b.selected
                        || a.positionInSet != b.positionInSet || a.sizeOfSet != b.sizeOfSet))
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
     * @param resolve     what turns a relation's target into a node identifier, answering {@code 0}
     *                    when it published none; never {@code null}
     * @return the tree, ready to publish
     * @throws NullPointerException if {@code resolve} is {@code null}
     */
    public AccessibleTree publish(long focusedId, int screenX, int screenY, float factor,
                                  boolean positioning, ToLongFunction<Object> resolve) {
        Objects.requireNonNull(resolve, "resolve");
        for (int i = 0; i < count; i++) {
            Slot s = slots[i];
            for (int r = 0; r < s.relationCount; r++) {
                s.relationResolved[r] = resolve.applyAsLong(s.relationTargets[r]);
            }
        }
        events.clear();
        diff();
        AccessibleNode[] nodes = new AccessibleNode[count];
        int[] firstChild = new int[count];
        int[] lastChild = new int[count];
        int[] nextSibling = new int[count];
        int[] previousSibling = new int[count];
        java.util.Arrays.fill(firstChild, AccessibleNode.NONE);
        java.util.Arrays.fill(lastChild, AccessibleNode.NONE);
        java.util.Arrays.fill(nextSibling, AccessibleNode.NONE);
        java.util.Arrays.fill(previousSibling, AccessibleNode.NONE);
        for (int i = 0; i < count; i++) {
            int parent = slots[i].parent;
            if (parent == AccessibleNode.NONE) {
                continue;
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
            nodes[i] = materialise(i, firstChild[i], lastChild[i],
                    nextSibling[i], previousSibling[i]);
        }
        generation++;
        AccessibleTree tree = new AccessibleTree(nodes, focusedId, screenX, screenY, factor,
                positioning, sceneWidth, sceneHeight, sceneLocale, generation);
        swap();
        return tree;
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
            selection = new SelectionFacet(s.multiSelectable, s.selectionRequired,
                    activeDescendantOf(index));
        }
        return new AccessibleNode(s.id, s.role, s.nameText, s.nameFrom, s.descriptionText,
                s.locale, s.states, s.x, s.y, s.width, s.height, relations,
                s.toggle < 0 ? null : new ToggleFacet(ToggleFacet.State.values()[s.toggle]),
                s.hasValue ? new ValueFacet(s.value, s.valueMin, s.valueMax, s.valueStep,
                        s.valueText) : null,
                selection,
                s.hasSelectionItem ? new SelectionItemFacet(s.selected, s.positionInSet,
                        s.sizeOfSet) : null,
                s.expand < 0 ? null : new ExpandFacet(s.expand == 1),
                s.hasText ? new TextFacet(s.text, s.caretOffset, s.caretAffinity,
                        s.selectionStart, s.selectionEnd, s.lineCount, s.caretRect) : null,
                s.hasScroll ? new ScrollFacet(s.horizontalPercent, s.verticalPercent,
                        s.horizontalViewSize, s.verticalViewSize, s.horizontallyScrollable,
                        s.verticallyScrollable) : null,
                s.hasWindow ? new WindowFacet(s.windowModal, s.windowCanMaximize,
                        s.windowCanMinimize, s.windowState) : null,
                s.verbs == 0 ? null : new ActionFacet(verbsOf(s.verbs), s.keyBinding),
                s.parent, firstChild, lastChild, nextSibling, previousSibling);
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

    /** The first node inside {@code container}'s subtree that declared itself active. */
    private long activeDescendantOf(int container) {
        long bit = 1L << Accessible.State.ACTIVE.ordinal();
        for (int i = container + 1; i < count; i++) {
            if (!isDescendant(i, container)) {
                continue;
            }
            if ((slots[i].states & bit) != 0) {
                return slots[i].id;
            }
        }
        return 0;
    }

    private boolean isDescendant(int node, int ancestor) {
        for (int at = slots[node].parent; at != AccessibleNode.NONE; at = slots[at].parent) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The events the last {@link #publish} produced, in the order a bridge is handed them.
     *
     * @return the events; empty when the two trees agreed, and never modifiable
     */
    public List<AccessibleEvent> events() {
        return java.util.Collections.unmodifiableList(events);
    }

    private void diff() {
        int boundsChanges = 0;
        for (int i = 0; i < count; i++) {
            Slot now = slots[i];
            Slot was = previousOf(now.id, i);
            if (was == null) {
                long parentId = now.parent == AccessibleNode.NONE ? 0 : slots[now.parent].id;
                add(AccessibleEvent.of(AccessibleEvent.Type.STRUCTURE_CHANGED, parentId));
                continue;
            }
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
                        add(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, now.id));
                    }
                }
            }
            if (now.hasValue && was.hasValue && now.value != was.value) {
                add(AccessibleEvent.property(AccessibleEvent.Type.VALUE_CHANGED, now.id,
                        was.value, now.value));
            }
            if (now.hasSelectionItem && was.hasSelectionItem
                    && now.parent != AccessibleNode.NONE && now.selected != was.selected) {
                add(AccessibleEvent.of(AccessibleEvent.Type.SELECTION_CHANGED,
                        slots[now.parent].id));
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
            events.removeIf(event -> event.type() == AccessibleEvent.Type.BOUNDS_CHANGED);
            add(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 0));
        }
        for (int i = 0; i < previousCount; i++) {
            if (currentOf(previous[i].id) == null) {
                add(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, previous[i].id));
            }
        }
        // The active descendant is resolved in the copy, so it is diffed from the same place.
        for (int i = 0; i < count; i++) {
            if (!slots[i].hasSelection) {
                continue;
            }
            Slot was = previousOf(slots[i].id, i);
            if (was == null || !was.hasSelection) {
                continue;
            }
            long now = activeDescendantOf(i);
            long before = activeDescendantIn(previous, previousCount, indexIn(previous,
                    previousCount, was.id));
            if (now != before) {
                add(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                        slots[i].id, before, now));
            }
        }
        if (events.size() > EVENT_BUDGET) {
            events.clear();
            events.add(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
        }
    }

    private void add(AccessibleEvent event) {
        if (events.size() <= EVENT_BUDGET) {
            events.add(event);
        }
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
        if (hint >= 0 && hint < previousCount && previous[hint].id == id) {
            return previous[hint];
        }
        for (int i = 0; i < previousCount; i++) {
            if (previous[i].id == id) {
                return previous[i];
            }
        }
        return null;
    }

    private Slot currentOf(long id) {
        for (int i = 0; i < count; i++) {
            if (slots[i].id == id) {
                return slots[i];
            }
        }
        return null;
    }

    private void swap() {
        Slot[] held = previous;
        previous = slots;
        previousCount = count;
        slots = held.length >= slots.length ? held : newSlots(slots.length);
        count = 0;
        current = AccessibleNode.NONE;
    }

    /**
     * Forgets what was published, so the next walk differs from nothing and publishes everything.
     * The publish step calls this when a scene is bound over a window that held another one.
     */
    public void forgetPublished() {
        previousCount = 0;
    }

    private Slot slot() {
        if (current == AccessibleNode.NONE) {
            throw new IllegalStateException("no node is being described");
        }
        return slots[current];
    }

    // --------------------------------------------------------- identity

    private long nextIdentifier;

    /**
     * Mints one identifier from the counter every node in the process shares.
     *
     * <p>The publish step calls this for a widget's own serial; this class calls it for a
     * synthetic child's. One counter and not two, because an identifier is the whole of identity
     * on every platform and two counters would eventually hand out the same number twice.
     *
     * @return an identifier no other node has had, and never {@code 0}
     */
    public long mint() {
        return ++nextIdentifier;
    }

    /**
     * The identifier a synthetic child keeps: minted once per (owner, key) pair and handed back
     * every time that pair is asked for again, so a row scrolled away and back is the same element
     * to whoever is holding it.
     *
     * <p>The table is bounded. Past its capacity the pair published longest ago is dropped, and
     * dropping one raises nothing: a node that left the tree was already destroyed by the
     * difference between two trees, and the entry only ever existed so that the identifier could
     * come back.
     */
    private long intern(long owner, long key) {
        int mask = internOwners.length - 1;
        int at = (int) mix(owner * 31 + key) & mask;
        int firstFree = -1;
        for (int probe = 0; probe < internOwners.length; probe++) {
            int index = (at + probe) & mask;
            if (internIds[index] == 0) {
                if (firstFree < 0) {
                    firstFree = index;
                }
                break;
            }
            if (internOwners[index] == owner && internKeys[index] == key) {
                internSeen[index] = generation;
                return internIds[index];
            }
        }
        if (internSize >= INTERN_CAPACITY) {
            evictOldestInterned();
            return intern(owner, key);
        }
        int index = firstFree >= 0 ? firstFree : at;
        internOwners[index] = owner;
        internKeys[index] = key;
        internIds[index] = mint();
        internSeen[index] = generation;
        internSize++;
        return internIds[index];
    }

    private void evictOldestInterned() {
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < internIds.length; i++) {
            if (internIds[i] != 0) {
                oldest = Math.min(oldest, internSeen[i]);
            }
        }
        // Rebuild rather than tombstone: an open-addressed table cannot have a hole punched in it
        // without breaking every probe that ran past it, and a rebuild happens once per eviction
        // burst rather than once per lookup.
        long[] owners = internOwners;
        long[] keys = internKeys;
        long[] ids = internIds;
        long[] seen = internSeen;
        internOwners = new long[owners.length];
        internKeys = new long[keys.length];
        internIds = new long[ids.length];
        internSeen = new long[seen.length];
        internSize = 0;
        int mask = internOwners.length - 1;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == 0 || seen[i] == oldest) {
                continue;
            }
            int at = (int) mix(owners[i] * 31 + keys[i]) & mask;
            for (int probe = 0; probe < internOwners.length; probe++) {
                int index = (at + probe) & mask;
                if (internIds[index] == 0) {
                    internOwners[index] = owners[i];
                    internKeys[index] = keys[i];
                    internIds[index] = ids[i];
                    internSeen[index] = seen[i];
                    internSize++;
                    break;
                }
            }
        }
    }

    private static long mix(long value) {
        long x = value * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        return x & Long.MAX_VALUE;
    }
}
