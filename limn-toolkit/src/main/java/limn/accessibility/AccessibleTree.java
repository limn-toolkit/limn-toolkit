package limn.accessibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One window's accessible tree, as it stood at the instant the user-interface thread published it.
 *
 * <p><b>Immutable, and read from any thread with no lock.</b> That is the whole shape of this
 * design and it is what the three platforms decide between them: one of them calls a provider from
 * several of its own threads at once while the user-interface thread sleeps, and another never
 * calls at all but wants the entire tree in a single message. A snapshot answers both, and the
 * staleness argument is short &mdash; a snapshot goes stale only while the user-interface thread
 * is running, and the user-interface thread publishes before it goes back to sleep.
 *
 * <p>The node at index {@code 0} is the root. Every other node is reachable from it through the
 * link indices on {@link AccessibleNode}, in tree order, which is paint order, which is the order
 * the Tab key walks: reading order is defined to be that order and never a sort by geometry, which
 * would be right in a left-to-right interface and backwards in a right-to-left one.
 *
 * <p>The window stamp on this type is the half a reader needs and cannot ask for. A window's screen
 * origin and its scale are user-interface-thread-confined, so a reader on a platform thread that
 * tried to ask would throw; they are captured here, once, by the thread that may.
 */
public final class AccessibleTree {

    /** The tree of a window that has nothing to say: no nodes, no stamp, no identifiers. */
    public static final AccessibleTree EMPTY = new AccessibleTree(
            new AccessibleNode[0], 0, 0, 0, 0, 1, false, 0, 0, Locale.ROOT, 0, 0);

    private final AccessibleNode[] nodes;
    private final long focused;
    private final long activeDescendant;
    private final int screenX;
    private final int screenY;
    private final float logicalToScreenFactor;
    private final boolean absolutePositioning;
    private final float sceneWidth;
    private final float sceneHeight;
    private final Locale locale;
    private final long generation;
    private final long sceneTag;

    AccessibleTree(AccessibleNode[] nodes, long focused, long activeDescendant, int screenX,
                   int screenY, float logicalToScreenFactor, boolean absolutePositioning,
                   float sceneWidth, float sceneHeight, Locale locale, long generation,
                   long sceneTag) {
        this.nodes = nodes;
        this.focused = focused;
        this.activeDescendant = activeDescendant;
        this.screenX = screenX;
        this.screenY = screenY;
        this.logicalToScreenFactor = logicalToScreenFactor;
        this.absolutePositioning = absolutePositioning;
        this.sceneWidth = sceneWidth;
        this.sceneHeight = sceneHeight;
        this.locale = locale;
        this.generation = generation;
        this.sceneTag = sceneTag;
    }

    /**
     * The same tree with a new window stamp and every node untouched.
     *
     * <p>A window drag changes every screen rectangle this tree publishes and changes nothing in
     * it: the boxes are scene-local, and a move does not touch one of them. So a move re-stamps
     * and walks nothing, which is the difference between a drag costing four numbers and a drag
     * costing a whole tree walk per callback the compositor sends.
     *
     * @param newScreenX  the window's content origin in native screen coordinates
     * @param newScreenY  the same, vertically
     * @param newFactor   the multiplier from logical points to native screen coordinates
     * @param positioning whether the platform lets this window know where it is at all
     * @return a tree with the new stamp, or this one when nothing in the stamp moved
     */
    public AccessibleTree restamp(int newScreenX, int newScreenY, float newFactor,
                                  boolean positioning) {
        if (newScreenX == screenX && newScreenY == screenY
                && newFactor == logicalToScreenFactor && positioning == absolutePositioning) {
            return this;
        }
        return new AccessibleTree(nodes, focused, activeDescendant, newScreenX, newScreenY,
                newFactor, positioning, sceneWidth, sceneHeight, locale, generation + 1, sceneTag);
    }

    /**
     * @return the tag every identifier minted for this window carries above its serial, which is
     *         what makes an identifier process-wide; {@code 0} for the empty tree
     */
    public long sceneTag() {
        return sceneTag;
    }

    /**
     * Whether an identifier was minted for this window's scene, which is the question a bridge
     * holding several windows' trees asks before {@link #find(long)}: a relation may name a node
     * in another window &mdash; a native popup's root names the field that opened it &mdash; and
     * the number alone says which tree to look in. Answered from the identifier's high bits and
     * nothing else, so it costs no lookup and no registry; it does not say whether the node is
     * in <em>this</em> snapshot, which {@link #find(long)} does.
     *
     * @param id a node identifier
     * @return whether it belongs to this window's scene
     */
    public boolean holds(long id) {
        return sceneTag != 0 && Accessibility.sceneTagOf(id) == sceneTag;
    }

    /** @return how many nodes this tree holds; {@code 0} for a window with nothing to say */
    public int nodeCount() {
        return nodes.length;
    }

    /**
     * @param index the node's index, as carried by the link accessors on {@link AccessibleNode}
     * @return the node at that index
     * @throws IndexOutOfBoundsException if the index is not one of this tree's
     */
    public AccessibleNode node(int index) {
        return nodes[index];
    }

    /** @return the root node, or {@code null} when this tree is empty */
    public AccessibleNode root() {
        return nodes.length == 0 ? null : nodes[0];
    }

    /**
     * @param id the identifier to look for
     * @return the index of the node carrying it, or {@link AccessibleNode#NONE}. Linear in the
     *         node count: a bridge that resolves identifiers on every platform call keeps a map of
     *         its own rather than asking this repeatedly.
     */
    public int indexOf(long id) {
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i].id() == id) {
                return i;
            }
        }
        return AccessibleNode.NONE;
    }

    /**
     * @param id the identifier to look for
     * @return the node carrying it, or {@code null}
     */
    public AccessibleNode find(long id) {
        int index = indexOf(id);
        return index == AccessibleNode.NONE ? null : nodes[index];
    }

    /**
     * The children of a node, in order, walked along the sibling links. Resolved through the
     * node's identifier rather than its links directly, so a node held from an earlier snapshot
     * answers with its children in <em>this</em> one, or with none when it is gone.
     *
     * @param node a node of this tree or of an earlier one
     * @return its children in this tree, first to last; empty when it has none or is not here
     */
    public List<AccessibleNode> children(AccessibleNode node) {
        int index = indexOf(node.id());
        if (index == AccessibleNode.NONE) {
            return List.of();
        }
        List<AccessibleNode> out = new ArrayList<>();
        for (int child = nodes[index].firstChild(); child != AccessibleNode.NONE;
                child = nodes[child].nextSibling()) {
            out.add(nodes[child]);
        }
        return out;
    }

    /**
     * Where a node sits among its siblings.
     *
     * @param node a node of this tree or of an earlier one
     * @return its position under its parent, from zero; {@code -1} for the root, which has no
     *         siblings, and for a node that is not in this tree
     */
    public int indexInParent(AccessibleNode node) {
        int index = indexOf(node.id());
        if (index == AccessibleNode.NONE || nodes[index].parent() == AccessibleNode.NONE) {
            return -1;
        }
        int at = 0;
        for (int child = nodes[nodes[index].parent()].firstChild(); child != AccessibleNode.NONE;
                child = nodes[child].nextSibling(), at++) {
            if (child == index) {
                return at;
            }
        }
        return -1;
    }

    /**
     * @return the identifier of the node holding the keyboard focus, or {@code 0} when nothing in
     *         this window does
     */
    public long focused() {
        return focused;
    }

    /**
     * The node the keyboard cursor is on inside the focused node: the first node published
     * {@link Accessible.State#ACTIVE} strictly below {@link #focused()} in reading order, whichever
     * containers lie between them. Never the window node's own {@code ACTIVE}, and never resolved
     * through a {@link SelectionFacet}: a container that is not the focused node, or below it, has
     * no cursor to publish. When the focused node's own subtree holds no {@code ACTIVE} node and it
     * opened a popup that is a window of its own, the cursor is read across the
     * {@link Accessible.Relation#CONTROLLER_FOR} relation into that window's tree, so the
     * identifier here may belong to another window — {@link #holds(long)} says which.
     *
     * @return the identifier of the active descendant, or {@code 0} when the focused node has
     *         none or nothing is focused
     */
    public long activeDescendant() {
        return activeDescendant;
    }

    /**
     * Where the user is, for a platform that puts its focus on the item rather than on the
     * widget: the {@linkplain #activeDescendant() active descendant} when there is one, and the
     * {@linkplain #focused() focused node} otherwise.
     *
     * @return the identifier of the node a reader should be standing on, or {@code 0} when
     *         nothing in this window holds the focus
     */
    public long effectiveFocus() {
        return activeDescendant != 0 ? activeDescendant : focused;
    }

    /**
     * The first node published {@link Accessible.State#ACTIVE} strictly below a node, in reading
     * order: what {@link #activeDescendant()} is resolved from, exposed so that the walk of a
     * window whose focused node opened a popup can read the popup's cursor off the popup's own
     * tree, and so that a bridge can ask the same of any subtree. Allocates nothing.
     *
     * @param index the index of the node whose subtree is searched; its own bit is never counted
     * @return the identifier of the first active node below it, or {@code 0} when there is none
     * @throws IndexOutOfBoundsException if the index is not one of this tree's
     */
    public long firstActiveBelow(int index) {
        java.util.Objects.checkIndex(index, nodes.length);
        for (int i = index + 1; i < nodes.length; i++) {
            if (!nodes[i].has(Accessible.State.ACTIVE)) {
                continue;
            }
            for (int at = nodes[i].parent(); at != AccessibleNode.NONE; at = nodes[at].parent()) {
                if (at == index) {
                    return nodes[i].id();
                }
            }
        }
        return 0;
    }

    /**
     * @return the window's content origin in native screen coordinates, captured on the
     *         user-interface thread at publish time. A placeholder when
     *         {@link #supportsAbsolutePositioning()} is false.
     */
    public int screenX() {
        return screenX;
    }

    /** @return the same, vertically */
    public int screenY() {
        return screenY;
    }

    /**
     * @return the multiplier from this scene's logical points to native screen coordinates: one on
     *         a platform that measures in points, the monitor scale on one that measures in pixels.
     *         It already folds in an application's content-scale override, so one multiplier gives
     *         all three platforms the answer each of them wants.
     */
    public float logicalToScreenFactor() {
        return logicalToScreenFactor;
    }

    /**
     * @return whether this window can know where it is on the screen at all. Where it cannot, the
     *         origin above is a placeholder and a bridge reports window-relative extents
     *         truthfully rather than dressing zeros up as a position.
     */
    public boolean supportsAbsolutePositioning() {
        return absolutePositioning;
    }

    /** @return the scene's width in logical points at publish time */
    public float sceneWidth() {
        return sceneWidth;
    }

    /**
     * @return the scene's height in logical points at publish time. This is the content view's
     *         height, and it is what a bridge flips against on the one platform whose accessibility
     *         coordinates run from the bottom.
     */
    public float sceneHeight() {
        return sceneHeight;
    }

    /** @return the scene's own language, which a node inherits when it declares none */
    public Locale locale() {
        return locale;
    }

    /**
     * @return a counter that moves on every publish and every re-stamp of this window. Meaningless
     *         except compared with itself: it is what lets a bridge holding a derived form of a
     *         tree &mdash; a pre-marshalled message, a cached element list &mdash; know that the
     *         form is still current without comparing anything.
     */
    public long generation() {
        return generation;
    }
}
