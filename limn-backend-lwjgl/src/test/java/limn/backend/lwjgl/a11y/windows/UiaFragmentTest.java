package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The five questions a fragment answers, over trees built the way a scene builds one.
 *
 * <p>Every case here is about the snapshot and nothing else — no widget, no window, no thread — so
 * the answers a client would get on a guest are the answers asserted here.
 */
class UiaFragmentTest {

    /**
     * A window at a screen origin with a scale factor, holding two buttons side by side and a
     * nested group inside the first.
     */
    private static AccessibleTree scene(int screenX, int screenY, float factor, long focusedId) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);

        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.GROUP);
        a.name(I18nString.literal("Left"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1002, 1, Locale.ENGLISH, 10, 20, 100, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, focusedId == 1002);
        a.end();
        a.end();

        a.begin(1003, 0, Locale.ENGLISH, 200, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Cancel"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, focusedId == 1003);
        a.end();
        a.end();
        return a.publish(focusedId, screenX, screenY, factor, true);
    }

    @Test
    void navigationFollowsTheLinksTheSnapshotStores() {
        AccessibleTree tree = scene(0, 0, 1f, 0);
        int window = tree.indexOf(1000);
        int left = tree.indexOf(1001);
        int save = tree.indexOf(1002);
        int cancel = tree.indexOf(1003);

        assertEquals(left, UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
        assertEquals(cancel, UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_LAST_CHILD));
        assertEquals(window, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_PARENT));
        assertEquals(cancel, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals(left,
                UiaFragment.navigate(tree, cancel, UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING));
        assertEquals(save, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
    }

    /**
     * A tree the way Tree publishes one: rows flat under the tree, each with its level in a
     * hierarchy facet, the second row's cell content a label child; a row of level 2 first whose
     * parent row is not realized; and the tree's scroll bar after the rows.
     *
     * <pre>
     * 2000 TREE
     *   2009 TREE_ITEM "Orphan" level 2 (its parent row is not published)
     *   2001 TREE_ITEM "A" level 1
     *   2002 TREE_ITEM "A1" level 2      2010 LABEL "A1's cell"
     *   2003 TREE_ITEM "A1a" level 3
     *   2004 TREE_ITEM "A1b" level 3
     *   2005 TREE_ITEM "A2" level 2
     *   2006 TREE_ITEM "B" level 1
     *   2007 TREE_ITEM "B1" level 2
     *   2008 SCROLL_BAR
     * </pre>
     */
    private static AccessibleTree aTree() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int tree = a.begin(2000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TREE);
        a.inherited(true, true, true, true, false);
        long[] ids = {2009, 2001, 2002, 2003, 2004, 2005, 2006, 2007};
        int[] levels = {2, 1, 2, 3, 3, 2, 1, 2};
        for (int i = 0; i < ids.length; i++) {
            int row = a.begin(ids[i], tree, Locale.ENGLISH, 0, 20 * i, 400, 20);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal("row " + ids[i]), Accessible.NameFrom.EXPLICIT);
            a.hierarchy(levels[i], i + 1, ids.length);
            a.inherited(true, true, true, false, false);
            if (ids[i] == 2002) {
                a.begin(2010, row, Locale.ENGLISH, 0, 20 * i, 100, 20);
                a.role(Accessible.Role.LABEL);
                a.inherited(true, true, true, false, false);
                a.end();
            }
            a.end();
        }
        a.begin(2008, tree, Locale.ENGLISH, 390, 0, 10, 300);
        a.role(Accessible.Role.SCROLL_BAR);
        a.state(Accessible.State.VERTICAL);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * A tree a virtualized Tree publishes while scrolled into a branch with its cursor row kept
     * realized off screen (decision 22): the kept root row first, then viewport rows whose flat row
     * indices jump past it, and a row whose index is unknown.
     *
     * <pre>
     * 3000 TREE
     *   3001 TREE_ITEM "R"    level 1, row 1   (the kept cursor row)
     *   3002 TREE_ITEM "S49"  level 2, row 51  (its parent S, row 2, is scrolled away)
     *   3003 TREE_ITEM "S49a" level 3, row 52
     *   3004 TREE_ITEM "S50"  level 2, row 53
     *   3005 TREE_ITEM "?"    level 3, row 0   (no index: nothing proves the row above is its parent)
     * </pre>
     */
    private static AccessibleTree aScrolledTree() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int tree = a.begin(3000, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TREE);
        a.inherited(true, true, true, true, false);
        long[] ids = {3001, 3002, 3003, 3004, 3005};
        int[] levels = {1, 2, 3, 2, 3};
        int[] rows = {1, 51, 52, 53, 0};
        for (int i = 0; i < ids.length; i++) {
            a.begin(ids[i], tree, Locale.ENGLISH, 0, 20 * i, 400, 20);
            a.role(Accessible.Role.TREE_ITEM);
            a.name(I18nString.literal("row " + ids[i]), Accessible.NameFrom.EXPLICIT);
            a.hierarchy(levels[i], rows[i], 90);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * The windows-B review of 2026-09-15: the parent search walks back only through rows whose
     * flat row indices run unbroken down to the row's own. Before, it took the nearest earlier row
     * of a lower level wherever it stood, so the kept cursor row R became the parent of S's
     * children in the viewport. A row whose parent row is not published hangs under the tree, and
     * so does a row with no index.
     */
    @Test
    void aRowNestsOnlyUnderARowItsUnbrokenRowIndicesReach() {
        AccessibleTree tree = aScrolledTree();

        assertEquals(java.util.List.of(3001L, 3002L, 3004L, 3005L), childrenOf(tree, 3000),
                "S49 and S50 hang under the tree, not under the kept R; the unindexed row too");
        assertEquals(java.util.List.of(3003L), childrenOf(tree, 3002),
                "S49a's index follows S49's, so it nests");
        assertEquals(java.util.List.of(), childrenOf(tree, 3001), "R has no child published");
        assertEquals(java.util.List.of(), childrenOf(tree, 3004),
                "the unindexed level-3 row is not taken for S50's child");
    }

    /** Every child navigation names, in order, by FirstChild then NextSibling. */
    private static java.util.List<Long> childrenOf(AccessibleTree tree, long id) {
        java.util.List<Long> children = new java.util.ArrayList<>();
        for (int at = UiaFragment.navigate(tree, tree.indexOf(id), UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD);
                at != AccessibleNode.NONE;
                at = UiaFragment.navigate(tree, at, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING)) {
            children.add(tree.node(at).id());
        }
        return children;
    }

    /**
     * Decision 4 and semantics 6 (W4): tree rows nest in navigation by their level, because NVDA
     * 2024.4.2 counts a tree item's TreeItem ancestors for its level and ignores UIA's Level
     * (readings/nvda-2024.4.2-uia.md §2), and a native tree nests its items in the raw view
     * (readings/windows-read-native-tree-levels.txt). A row's parent is the nearest earlier row of
     * a lower level; its children are its own, then the rows that makes it the parent of. Until
     * 2026-09-15 every row was a child of the tree and NVDA would have said level 1 for all.
     */
    @Test
    void treeRowsNestByTheirLevelSoAReaderCountingTreeItemAncestorsHearsTheLevel() {
        AccessibleTree tree = aTree();

        assertEquals(java.util.List.of(2009L, 2001L, 2006L, 2008L), childrenOf(tree, 2000),
                "the tree holds its level-1 rows, a row whose parent is not published, and its bar");
        assertEquals(java.util.List.of(2002L, 2005L), childrenOf(tree, 2001));
        assertEquals(java.util.List.of(2010L, 2003L, 2004L), childrenOf(tree, 2002),
                "a row's own cell content first, then its child rows");
        assertEquals(java.util.List.of(2007L), childrenOf(tree, 2006));
        assertEquals(java.util.List.of(), childrenOf(tree, 2003));
        assertEquals(tree.indexOf(2002),
                UiaFragment.navigate(tree, tree.indexOf(2004), UiaIds.NAVIGATE_DIRECTION_PARENT));
        assertEquals(tree.indexOf(2001),
                UiaFragment.navigate(tree, tree.indexOf(2002), UiaIds.NAVIGATE_DIRECTION_PARENT));
        assertEquals(tree.indexOf(2000),
                UiaFragment.navigate(tree, tree.indexOf(2009), UiaIds.NAVIGATE_DIRECTION_PARENT),
                "no earlier row of a lower level: the tree");
        assertEquals(tree.indexOf(2004),
                UiaFragment.navigate(tree, tree.indexOf(2002), UiaIds.NAVIGATE_DIRECTION_LAST_CHILD));
        assertEquals(tree.indexOf(2010),
                UiaFragment.navigate(tree, tree.indexOf(2003), UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING),
                "a first child row is preceded by its parent row's own content");
        assertEquals(tree.indexOf(2003),
                UiaFragment.navigate(tree, tree.indexOf(2010), UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, tree.indexOf(2005), UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING),
                "the last child of A ends at B, which is A's sibling");
    }

    /**
     * What UI Automation relies on of any navigation, checked over every node of a nested tree and
     * a plain scene: each child's Parent is the node it was reached from, LastChild is the last of
     * the FirstChild/NextSibling chain, PreviousSibling walks that chain backwards, and a walk from
     * the root meets every node exactly once.
     */
    @Test
    void navigationIsOneConsistentTreeReachingEveryNodeOnce() {
        for (AccessibleTree tree : new AccessibleTree[] {aTree(), aScrolledTree(), scene(0, 0, 1f, 0)}) {
            java.util.List<Long> met = new java.util.ArrayList<>();
            java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
            pending.add(0);
            while (!pending.isEmpty()) {
                int at = pending.poll();
                met.add(tree.node(at).id());
                java.util.List<Integer> chain = new java.util.ArrayList<>();
                for (int child = UiaFragment.navigate(tree, at, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD);
                        child != AccessibleNode.NONE;
                        child = UiaFragment.navigate(tree, child, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING)) {
                    assertEquals(at, UiaFragment.navigate(tree, child, UiaIds.NAVIGATE_DIRECTION_PARENT),
                            "the parent of a child of " + tree.node(at).id());
                    chain.add(child);
                    pending.add(child);
                }
                assertEquals(chain.isEmpty() ? AccessibleNode.NONE : chain.get(chain.size() - 1),
                        UiaFragment.navigate(tree, at, UiaIds.NAVIGATE_DIRECTION_LAST_CHILD),
                        "the last child of " + tree.node(at).id());
                java.util.List<Integer> backwards = new java.util.ArrayList<>();
                if (!chain.isEmpty()) {
                    for (int child = chain.get(chain.size() - 1); child != AccessibleNode.NONE;
                            child = UiaFragment.navigate(tree, child, UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING)) {
                        backwards.add(0, child);
                    }
                }
                assertEquals(chain, backwards, "the children of " + tree.node(at).id() + " backwards");
            }
            java.util.List<Long> every = new java.util.ArrayList<>();
            for (int i = 0; i < tree.nodeCount(); i++) {
                every.add(tree.node(i).id());
            }
            assertEquals(every.stream().sorted().toList(), met.stream().sorted().toList(),
                    "every node once");
        }
    }

    @Test
    void theEndsOfTheTreeAnswerNothingRatherThanWrappingAround() {
        AccessibleTree tree = scene(0, 0, 1f, 0);
        int window = tree.indexOf(1000);
        int save = tree.indexOf(1002);

        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_PARENT),
                "the root of this fragment has no parent inside it");
        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, save, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, save, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals(AccessibleNode.NONE, UiaFragment.navigate(tree, save, 99),
                "a direction nobody defined is not a navigation");
    }

    /**
     * The identifier is 64 bits and the array is of 32-bit integers, so it takes two slots.
     * Truncating instead would make two nodes one element to a client the moment the counter
     * outran 32 bits, which §1.3 says it will in a long-lived application.
     */
    @Test
    void theRuntimeIdCarriesTheWholeIdentifierBehindTheMarker() {
        assertArrayEquals(new int[] {UiaIds.APPEND_RUNTIME_ID, 0, 1002},
                UiaFragment.runtimeId(1002));

        long big = (7L << 32) | 0x0000_0000_DEAD_BEEFL;
        assertArrayEquals(new int[] {UiaIds.APPEND_RUNTIME_ID, 7, 0xDEADBEEF},
                UiaFragment.runtimeId(big));

        assertNotEquals(UiaFragment.runtimeId(1L << 32)[1], UiaFragment.runtimeId(0)[1],
                "two identifiers differing only above 32 bits must not share a runtime id");
    }

    @Test
    void theRectangleIsTheWindowsOriginPlusTheNodesBoxTimesTheFactor() {
        AccessibleTree tree = scene(200, 100, 2f, 0);
        AccessibleNode save = tree.node(tree.indexOf(1002));

        assertArrayEquals(new double[] {200 + (0 + 10) * 2, 100 + (0 + 20) * 2, 100 * 2, 40 * 2},
                UiaFragment.boundingRectangle(tree, save), 1e-9,
                "logical and window-relative in, native screen coordinates out -- and both the "
                        + "origin and the factor come off the snapshot, because the window's own "
                        + "position may only be read on the thread that owns it");
    }

    @Test
    void theFocusedNodeIsTheOneTheSnapshotNamesAndNothingWhenNoneIs() {
        AccessibleTree focused = scene(0, 0, 1f, 1003);
        assertEquals(focused.indexOf(1003), UiaFragment.focus(focused));

        assertEquals(AccessibleNode.NONE, UiaFragment.focus(scene(0, 0, 1f, 0)),
                "a window nothing in is focused answers nothing, not its root");
    }

    /**
     * W3, LAB-NEW-4: GetFocus answers where the user is. A focused table whose cursor cell is
     * ACTIVE answers the cell, and the Context's default HasKeyboardFocus agrees; before 2026-09-15
     * both answered the table.
     */
    @Test
    void theFocusIsTheActiveDescendantOfTheFocusedContainer() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.selection(false, false);
        a.inherited(true, true, true, true, true);
        a.begin(1002, 1, Locale.ENGLISH, 0, 0, 400, 20);
        a.role(Accessible.Role.ROW);
        a.inherited(true, true, true, false, false);
        a.begin(1003, 2, Locale.ENGLISH, 0, 0, 400, 20);
        a.role(Accessible.Role.CELL);
        a.state(Accessible.State.ACTIVE, true);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(1001, 0, 0, 1f, true);

        assertEquals(tree.indexOf(1003), UiaFragment.focus(tree),
                "the cursor cell, not the table that holds the keyboard");

        UiaProvider.Context context = new UiaProvider.Context() {
            @Override public AccessibleTree tree() { return tree; }
            @Override public long patternProviderFor(long nodeId, int patternId) { return 0; }
            @Override public long hostProvider() { return 0; }
            @Override public UiaStrings.Allocator strings() { return text -> 0; }
            @Override public long int32Array(int[] values) { return 0; }
            @Override public long unknownArray(long[] pointers) { return 0; }
            @Override public long elementFor(long nodeId) { return 0; }
            @Override public long simpleElementFor(long nodeId) { return 0; }
            @Override public long rootElement() { return 0; }
            @Override public boolean requestFocus(long nodeId) { return false; }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) { return false; }
        };
        assertEquals(true, context.hasKeyboardFocus(1003));
        assertEquals(false, context.hasKeyboardFocus(1001));
        assertEquals(false, context.hasKeyboardFocus(9999), "a node this tree does not hold");
    }

    @Test
    void theElementUnderAPointIsTheDeepestOneWhoseBoxHoldsIt() {
        AccessibleTree tree = scene(200, 100, 2f, 0);

        // The Save button's screen box is (220, 140) 200x80.
        assertEquals(tree.indexOf(1002), UiaFragment.elementFromPoint(tree, 300, 180));
        // Inside the left group but outside the button.
        assertEquals(tree.indexOf(1001), UiaFragment.elementFromPoint(tree, 205, 105));
        // The other button.
        assertEquals(tree.indexOf(1003), UiaFragment.elementFromPoint(tree, 700, 150));
        // Inside the window and in neither.
        assertEquals(tree.indexOf(1000), UiaFragment.elementFromPoint(tree, 300, 500));
        // Outside the window.
        assertEquals(AccessibleNode.NONE, UiaFragment.elementFromPoint(tree, 5, 5));
    }

    /**
     * The reason this walks the snapshot rather than calling {@code Widget#hitTest}: that method
     * answers null for a disabled subtree at every level, because it routes input and input does
     * not reach a disabled control. UI Automation expects to find the disabled button and be told
     * it is disabled.
     */
    @Test
    void aDisabledControlIsStillFoundUnderThePointer() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 100, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.inherited(false, true, true, false, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        int found = UiaFragment.elementFromPoint(tree, 50, 30);

        assertEquals(tree.indexOf(1001), found,
                "hitTest would answer the window here, and the user hovering a greyed-out button "
                        + "would hear the panel behind it");
        assertEquals(Boolean.FALSE,
                UiaProperties.valueOf(tree.node(found), UiaIds.IS_ENABLED),
                "and what they hear instead is that it is disabled");
    }

    /**
     * A node scrolled out of its viewport is not on the glass, so nothing can be over it — which is
     * the same fact {@code IsOffscreen} publishes, read here for a different purpose.
     */
    @Test
    void aScrolledAwayNodeDoesNotAnswerForAPointInsideItsViewport() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.5, 1, 0.5, false, true);
        a.inherited(true, true, true, false, false);
        // Its box overlaps the viewport, and the walk says it is not showing.
        a.begin(1002, 1, Locale.ENGLISH, 0, 0, 200, 40);
        a.role(Accessible.Role.LIST_ITEM);
        a.name(I18nString.literal("Row"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, false, false, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        assertEquals(tree.indexOf(1001), UiaFragment.elementFromPoint(tree, 10, 10),
                "the pane answers, because the row it holds is not on the glass");
    }

    /**
     * Two siblings over one point, which is the shape an in-scene popup makes over the content
     * beneath it. The later one is painted on top, so it is what the pointer is over; answering
     * the earlier one would put a reader on the control the menu is covering.
     */
    @Test
    void whereSiblingsOverlapTheLaterOneAnswersBecauseItIsPaintedOnTop() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Underneath"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(1002, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.MENU);
        a.name(I18nString.literal("On top"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        assertEquals(tree.indexOf(1002), UiaFragment.elementFromPoint(tree, 50, 50),
                "the menu covers the button, and a reader told it was over the button would be "
                        + "describing a control the user cannot see or reach");
    }

    /**
     * A split pane as it publishes: the first pane's content, the splitter, the second pane's
     * content, with the splitter's box the 24-point grab band reaching over both. The pointer drags
     * the divider anywhere in that band, and the band's half over the second pane answered that
     * pane's button here, because the later sibling was taken to be on top.
     */
    @Test
    void aSplitterAnswersAcrossItsWholeGrabBandThoughItIsPublishedBetweenThePanes() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int split = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.SPLIT_PANE);
        a.inherited(true, true, true, false, false);
        a.begin(1002, split, Locale.ENGLISH, 0, 0, 195.5f, 300);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Left"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(1003, split, Locale.ENGLISH, 188, 0, 24, 300);
        a.role(Accessible.Role.SPLITTER);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(1004, split, Locale.ENGLISH, 204.5f, 0, 195.5f, 300);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Right"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        for (int x = 188; x < 212; x++) {
            assertEquals(tree.indexOf(1003), UiaFragment.elementFromPoint(tree, x + 0.5, 150),
                    "the pointer drags the divider at x=" + x + ", so a reader there is on it");
        }
        assertEquals(tree.indexOf(1002), UiaFragment.elementFromPoint(tree, 187.5, 150));
        assertEquals(tree.indexOf(1004), UiaFragment.elementFromPoint(tree, 212.5, 150));
    }

    @Test
    void anEmptyTreeAnswersNothingRatherThanThrowing() {
        assertEquals(AccessibleNode.NONE, UiaFragment.elementFromPoint(AccessibleTree.EMPTY, 0, 0));
        assertEquals(AccessibleNode.NONE, UiaFragment.focus(AccessibleTree.EMPTY));
    }
}
