package limn.scene;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Walks one scene into the accessible tree, and remembers which widget each published node came
 * from so that an action can be sent back to it.
 *
 * <p>One instance per scene, reused every frame. Everything it keeps is grown once and then
 * reused, so a frame that damaged something and changed no accessible fact walks the tree,
 * compares every field, and allocates nothing at all.
 *
 * <p><b>Identity is minted over the widget tree and never over the published one.</b> That is the
 * rule the rest of this class is arranged around. A container stops being scaffolding the moment
 * an application names it, and under a key derived from the published parent that one property
 * change would re-key every node beneath it — which a screen reader experiences as every element
 * it is holding becoming invalid at once, in the middle of reading, because of something the user
 * never touched. So an ordinary widget's identifier is a serial in a weak map keyed by the widget
 * object itself, which depends on no ancestor whatsoever; a child whose parent claimed the right
 * to key it takes an identifier interned under the parent's own; and a synthetic child takes one
 * interned under its owner's.
 */
final class AccessibleWalk {

    private static final System.Logger LOG = System.getLogger(AccessibleWalk.class.getName());

    /**
     * A serial per widget, and the whole of rule three. Weak, so a widget that never meets an
     * assistive technology costs nothing and one that is detached and re-attached keeps the
     * identifier a client is holding, because it is the same object.
     */
    private final WeakHashMap<Widget, Long> serials = new WeakHashMap<>();

    private final Accessibility builder = new Accessibility();

    /**
     * Held rather than written as a method reference at the call site: the resolution runs at the
     * end of every walk, and a method reference to an instance method is an object per evaluation,
     * so writing it inline would allocate once per damaged frame to conclude that nothing moved.
     */
    private final java.util.function.ToLongFunction<Object> resolver = this::resolve;

    /** Per published node: the widget that owns it. Grown once, reused. */
    private Widget[] owners = new Widget[64];
    private long[] ids = new long[64];
    private long[] keys = new long[64];
    private boolean[] synthetic = new boolean[64];
    private int count;

    /**
     * Classes already warned about, one set per warning, so a window painted every frame is named
     * once and not sixty times.
     *
     * <p>Two sets and not one keyed by a class name plus a suffix, because that key is built on
     * every frame <em>after</em> the first: both guards sit on paths a widget takes on every
     * damaged frame for as long as it is on screen, so the concatenation was a per-frame
     * allocation whose only purpose was to conclude that the warning had already been logged.
     * A class name is cached by the class itself, so keying on it directly costs nothing.
     */
    private static final java.util.Set<String> PAINT_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The same, for a focusable widget that declared no role. */
    private static final java.util.Set<String> ROLE_WARNED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The identifier of the node holding the focus, for the tree's header. */
    private long focusedId;

    /**
     * The window's own node, minted once per scene.
     *
     * <p>It is not the root widget's identifier, and the two must never be the same number: the
     * root widget usually hoists away into this node, but a root that declares something of its
     * own publishes a node beside it, and one identifier for two elements is a merged element on
     * every platform rather than a cosmetic clash.
     */
    private long windowNodeId;

    /** Pairs of (popup node index, opener widget), for the mirror half of a popup relation. */
    private int[] popupNodes = new int[8];
    private Widget[] popupOpeners = new Widget[8];
    private int popupCount;

    /** @return the builder every describe hook writes into */
    Accessibility builder() {
        return builder;
    }

    /**
     * Walks {@code scene} into the builder without publishing it.
     *
     * @param scene       the scene to describe
     * @param sceneWidth  its width in logical points
     * @param sceneHeight its height
     */
    void walk(Scene scene, float sceneWidth, float sceneHeight) {
        Locale sceneLocale = scene.locale() != null ? scene.locale() : limn.i18n.I18n.processLocale();
        builder.beginWalk(sceneWidth, sceneHeight, sceneLocale);
        count = 0;
        popupCount = 0;
        focusedId = 0;

        Widget root = scene.root();
        if (windowNodeId == 0) {
            windowNodeId = builder.mint();
        }
        builder.begin(windowNodeId, AccessibleNode.NONE, sceneLocale, 0, 0, sceneWidth, sceneHeight);
        record(null, windowNodeId, 0, false);
        builder.role(Accessible.Role.WINDOW);
        limn.backend.NativeWindow window = scene.window();
        if (window != null) {
            builder.name(window.title(), 0, Accessible.NameFrom.EXPLICIT);
            // The window's own bit -- whether it blocks what it owns -- and not isModalBlocked(),
            // which is its owner's. Filled from the latter, a native dialog published itself
            // non-modal and the window it had frozen as the modal one.
            builder.window(window.isModal(), true, true,
                    limn.accessibility.WindowFacet.State.NORMAL);
        }
        builder.inherited(true, true, true, false, false);

        Widget top = scene.topOverlay();
        walkWidget(scene, root, null, 0, true, true, top == null);
        List<Widget> overlays = scene.overlays();
        for (int i = 0; i < overlays.size(); i++) {
            Widget overlay = overlays.get(i);
            // An overlay is parentless, so the tree cannot say whether the control that opened it
            // is enabled -- and that is exactly the case inheritanceHost() is documented for: the
            // widget an inherited axis resolves through when the tree cannot say. Passing a
            // literal true here published a disabled control's popup with every option ENABLED,
            // and the assistive technology was then the only caller refused, because the hooks
            // guard on the owner while the scene's own ancestor gate walks the same parentless
            // chain and finds nothing to stop. Climbing parent-then-host is the idiom resolve()
            // already uses, and for the same reason: a menu's host is often an item inside
            // another overlay. Only the enabled axis resolves this way; visibility does not,
            // because a popup is routinely opened from a control that has since scrolled out of
            // its viewport, and that popup is still on screen and still being read.
            boolean hostEnabled = true;
            for (Widget at = overlay.inheritanceHost(); at != null;
                    at = at.parent() != null ? at.parent() : at.inheritanceHost()) {
                hostEnabled &= at.isEnabled();
            }
            walkWidget(scene, overlay, null, 0, hostEnabled, true, overlay == top);
        }
        builder.end();
        count = builder.nodeCount();

        for (int i = 0; i < popupCount; i++) {
            builder.relationAt(popupNodes[i], Accessible.Relation.POPUP_FOR, popupOpeners[i]);
            int opener = indexOfWidget(popupOpeners[i]);
            if (opener >= 0) {
                builder.relationAt(opener, Accessible.Relation.CONTROLLER_FOR,
                        owners[popupNodes[i]]);
            }
        }
        // Last, because a target's identifier is only knowable once every node it could name has
        // been walked, and before anything compares this walk with the last one: the comparison
        // reads what this fills in.
        builder.resolveRelations(resolver);
    }

    /**
     * Describes one widget and its subtree.
     *
     * @param scene    the scene being walked
     * @param widget   the widget to describe
     * @param parent   the widget that will be asked to add what only it knows, or {@code null}
     * @param into     the index of the node this one hangs under
     * @param enabled  whether every ancestor is enabled and nothing modal shadows this subtree
     * @param visible  whether every ancestor is visible
     * @param reachable whether this subtree is inside the layer that currently owns input
     */
    private void walkWidget(Scene scene, Widget widget, Widget parent, int into,
                            boolean enabled, boolean visible, boolean reachable) {
        if (widget.isAccessibleIgnored()) {
            return;
        }
        boolean ownEnabled = enabled && widget.isEnabled() && reachable;
        boolean ownVisible = visible && widget.isVisible();
        long id = identify(widget);
        int slot = builder.begin(id, into, widget.locale(),
                widget.localToSceneX(), widget.localToSceneY(), widget.width(), widget.height());

        widget.describeAccessible(builder);
        if (parent != null) {
            parent.describeAccessibleChild(widget, builder);
        }
        if (builder.hasChildKey() && parent != null) {
            id = builder.identify(identify(parent), builder.childKey());
            builder.reidentify(id);
        }
        // Under the widget's own language, as the two hooks above were: the node records that
        // language and the model re-resolves a name when it moves, so a string the walk hands
        // over on the widget's behalf -- the application's override, a bound label's caption,
        // the tooltip default -- has to be resolved under it too, or a tooltip-named icon button
        // in a subtree declaring another language records that language and speaks the process's.
        Locale enclosing = limn.i18n.I18n.pushScope(widget.locale());
        try {
            applyOverrides(widget);
            if (!builder.hasName() && widget.tooltipSource() != null) {
                builder.name(widget.tooltipSource(), Accessible.NameFrom.TOOLTIP);
            } else if (!builder.hasDescription() && widget.tooltipSource() != null) {
                builder.description(widget.tooltipSource());
            }
        } finally {
            limn.i18n.I18n.popScope(enclosing);
        }
        if (widget.parent() == null && scene.isTopOverlay(widget)) {
            // The layer that owns input carries the fact, so that a reader hears "dialog" rather
            // than discovering it by having everything underneath refuse to be operated.
            builder.state(Accessible.State.MODAL);
        }

        if (builder.isIgnored()) {
            builder.drop();
            return;              // ignored: no node, and no children either
        }
        boolean focusable = widget.isFocusable() && ownEnabled && ownVisible;
        if (builder.declaresNothing() && !focusable && !builder.hasChildren()) {
            warnIfItPaints(widget);
            builder.drop();
            walkChildren(scene, widget, into, ownEnabled, ownVisible, reachable);
            return;              // transparent: no node, children hoisted in its place
        }

        record(widget, id, slot, false);
        boolean showing = widget.isShowing();
        for (int i = slot + 1; i < builder.nodeCount(); i++) {
            record(widget, builder.idAt(i), i, builder.isSyntheticAt(i));
            keys[i] = builder.syntheticKeyAt(i);
            // The owner's bits, on every node the owner drew. They cannot ride on the call below:
            // that one writes to the node the walk has open, and these were closed the moment the
            // describe hook finished with them. Focusable and focused are not passed on, because a
            // thing a widget paints is not a tab stop and never holds the keyboard.
            builder.inheritedAt(i, ownEnabled, ownVisible, showing);
        }
        boolean focused = scene.focusedWidget() == widget;
        if (focused) {
            focusedId = id;
        }
        builder.inherited(ownEnabled, ownVisible, showing, focusable, focused);
        if (focusable) {
            builder.action(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW);
        }
        warnIfUnnamedRole(widget, focusable);
        if (widget.parent() == null && widget.inheritanceHost() != null) {
            addPopup(slot, widget.inheritanceHost());
        }

        walkChildren(scene, widget, slot, ownEnabled, ownVisible, reachable);
        builder.end();
    }

    private void walkChildren(Scene scene, Widget widget, int into,
                              boolean enabled, boolean visible, boolean reachable) {
        List<Widget> children = widget.children();
        for (int i = 0; i < children.size(); i++) {
            walkWidget(scene, children.get(i), widget, into, enabled, visible, reachable);
        }
    }

    private void applyOverrides(Widget widget) {
        limn.accessibility.Accessible.Role role = widget.accessibleRole();
        if (role != null) {
            builder.role(role);
        }
        // Before the explicit name and after the widget's own, which is the whole of the
        // precedence: a caption the application bound wins over what the widget derived, and a
        // name the application wrote wins over the caption. The text is read here rather than
        // copied when the link was made, so a label that changes its string renames what it
        // labels with nothing to keep in step. The relation stands even when the label offers no
        // text: it is still the truth about the tree, and a client that resolves the label's own
        // node does not need the copy.
        Widget label = widget.accessibleLabelledBy();
        if (label != null) {
            limn.i18n.I18nString caption = label.accessibleLabelText();
            if (caption != null) {
                builder.name(caption, Accessible.NameFrom.LABEL);
            }
            builder.relation(Accessible.Relation.LABELLED_BY, label);
        }
        limn.i18n.I18nString name = widget.accessibleName();
        if (name != null) {
            builder.name(name, Accessible.NameFrom.EXPLICIT);
        }
        limn.i18n.I18nString description = widget.accessibleDescription();
        if (description != null) {
            builder.description(description);
        }
    }

    /**
     * A widget that draws its own content and declared nothing is about to be deleted, and the
     * interface it drew would simply be absent from the tree with nothing said. Only the
     * application has a name for such a thing, so this says so once per class rather than
     * publishing a nameless box.
     *
     * <p>Unless the class says the drawing is decoration, which is the one answer reflection
     * cannot supply: {@code paintsItself} asks which method a class overrode, and a wash painted
     * behind somebody else's controls overrides the same one a gauge does. The widget is asked
     * before the set is touched, so a decorative class never takes a slot in it and the warning it
     * is exempt from stays available to the class that needs it.
     */
    private static void warnIfItPaints(Widget widget) {
        if (!widget.paintsItself() || widget.paintsDecoration()) {
            return;
        }
        if (!PAINT_WARNED.add(widget.getClass().getName())) {
            return;
        }
        LOG.log(Level.WARNING,
                "{0} paints its own content and says nothing about itself, so it is absent from "
                        + "the accessible tree. One line fixes it: setAccessibleName, "
                        + "setAccessibleRole, or setAccessibleIgnored(true) if the drawing really "
                        + "is decorative.",
                widget.getClass().getName());
    }

    private void warnIfUnnamedRole(Widget widget, boolean focusable) {
        if (!focusable || builder.hasRole()) {
            return;
        }
        builder.role(Accessible.Role.UNKNOWN);
        if (ROLE_WARNED.add(widget.getClass().getName())) {
            LOG.log(Level.WARNING,
                    "{0} is focusable and declares no accessible role, so it is published as "
                            + "UNKNOWN. Give it a role in onAccessibility, or call "
                            + "setAccessibleRole.",
                    widget.getClass().getName());
        }
    }

    private void addPopup(int slot, Widget opener) {
        if (popupCount == popupNodes.length) {
            popupNodes = java.util.Arrays.copyOf(popupNodes, popupCount * 2);
            popupOpeners = java.util.Arrays.copyOf(popupOpeners, popupCount * 2);
        }
        popupNodes[popupCount] = slot;
        popupOpeners[popupCount] = opener;
        popupCount++;
    }

    /** The serial a widget keeps for as long as it exists. */
    private long identify(Widget widget) {
        Long known = serials.get(widget);
        if (known != null) {
            return known;
        }
        long minted = builder.mint();
        serials.put(widget, minted);
        return minted;
    }

    private void record(Widget owner, long id, int slot, boolean isSynthetic) {
        if (slot >= owners.length) {
            int grown = Math.max(slot + 1, owners.length * 2);
            owners = java.util.Arrays.copyOf(owners, grown);
            ids = java.util.Arrays.copyOf(ids, grown);
            keys = java.util.Arrays.copyOf(keys, grown);
            synthetic = java.util.Arrays.copyOf(synthetic, grown);
        }
        owners[slot] = owner;
        ids[slot] = id;
        keys[slot] = 0;
        synthetic[slot] = isSynthetic;
        if (slot >= count) {
            count = slot + 1;
        }
    }

    private int indexOfWidget(Widget widget) {
        for (int i = 0; i < count; i++) {
            if (owners[i] == widget && !synthetic[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Turns the walk into a tree. The relations were resolved when the walk ended, so that the
     * difference could see them.
     *
     * @param screenX     the window's content origin in native screen coordinates
     * @param screenY     the same, vertically
     * @param factor      the multiplier from logical points to native screen coordinates
     * @param positioning whether the platform lets this window know where it is
     * @return the tree
     */
    AccessibleTree publish(int screenX, int screenY, float factor, boolean positioning) {
        return builder.publish(focusedId, screenX, screenY, factor, positioning);
    }

    /**
     * A relation's target, as an identifier.
     *
     * <p>The inheritance host is an axis-resolution host and not an accessible parent, and the two
     * disagree exactly where it matters: a menu's host is the item that opened it, which is often a
     * row inside a container the transparency rule deleted. So the walk climbs from the target
     * through those deletions to the first node it actually published. Landing on the window's own
     * root is the same as landing on nothing: that node is already the popup's accessible ancestor,
     * so the relation would say what the tree's own shape says, and it is dropped.
     */
    private long resolve(Object target) {
        if (!(target instanceof Widget widget)) {
            return 0;
        }
        for (Widget at = widget; at != null;
                at = at.parent() != null ? at.parent() : at.inheritanceHost()) {
            int index = indexOfWidget(at);
            if (index >= 0) {
                return ids[index];
            }
        }
        return 0;
    }

    /**
     * The widget that owns a published node, for an action that has arrived.
     *
     * @param nodeId the node's identifier
     * @return its owner, or {@code null} when the identifier names nothing this walk published
     */
    Widget ownerOf(long nodeId) {
        for (int i = 0; i < count; i++) {
            if (ids[i] == nodeId) {
                return owners[i];
            }
        }
        return null;
    }

    /**
     * Whether a published node is a synthetic child rather than a widget's own.
     *
     * @param nodeId the node's identifier
     * @return whether an action for it goes to the synthetic hook
     */
    boolean isSynthetic(long nodeId) {
        for (int i = 0; i < count; i++) {
            if (ids[i] == nodeId) {
                return synthetic[i];
            }
        }
        return false;
    }

    /**
     * The key a synthetic node's owner gave it.
     *
     * @param nodeId the node's identifier
     * @return the key, or {@code 0}
     */
    long keyOf(long nodeId) {
        for (int i = 0; i < count; i++) {
            if (ids[i] == nodeId) {
                return keys[i];
            }
        }
        return 0;
    }

    /** Forgets what was published, so the next walk differs from nothing. */
    void forget() {
        builder.forgetPublished();
    }
}
