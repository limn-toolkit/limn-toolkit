package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.Shape;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.putBool;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.refusal;
import static limn.backend.lwjgl.a11y.windows.UiaPatternProviders.postFirstAccepted;

/**
 * The {@code ROWS} shape's half of the Windows bridge (ADR 045 §5): a selection and its members: Selection on the container, SelectionItem and ScrollItem on a member, and the outline nesting a tree's rows get under their parent row, which UI Automation navigates rather than reads off a level.
 *
 * <p>Moved here verbatim from {@code UiaPatternProviders} on 2026-09-21, under the shape's
 * name and nothing else; the patterns are vended by facet in {@link UiaPatterns} and the
 * provider's switch dispatches each to the shape that owns it. The tests that pin every
 * answer below still address {@code UiaPatternProviders.slotsFor}, which is unchanged.
 */
final class UiaRowsShape {

    private UiaRowsShape() {
    }

    /** The patterns this shape owns: SELECTION_PATTERN, SELECTION_ITEM_PATTERN, SCROLL_ITEM_PATTERN. */
    static void slots(int patternId, Map<String, CallbackI> slots, long nodeId,
                      UiaProvider.Context context) {
        switch (patternId) {
            // ISelectionProvider (W1's Selection half; semantics 1): the container's members are the
            // realized nodes whose selection container, resolved once at publish, is this node.
            case UiaIds.SELECTION_PATTERN -> {
                slots.put("GetSelection", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    int container = tree.indexOf(nodeId);
                    if (container < 0 || tree.node(container).selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    long[] pointers = selectedMembersOf(tree, container, context);
                    MemoryUtil.memPutAddress(out, context.unknownArray(pointers));
                    return UiaIds.S_OK;
                });
                slots.put("get_CanSelectMultiple", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selection().multiSelectable());
                    return UiaIds.S_OK;
                });
                slots.put("get_IsSelectionRequired", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selection() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selection().required());
                    return UiaIds.S_OK;
                });
            }

            // Decision 10 and semantics 5's candidate lists: Select is a click, SELECT; "add" is
            // ADD_TO_SELECTION where the container offers it and a click where it does not (a
            // single-select container's only way to add is to select); remove is DESELECT. The
            // first verb the node publishes is posted, and a node publishing none is refused
            // synchronously rather than told S_OK for a verb its widget will refuse.
            case UiaIds.SELECTION_ITEM_PATTERN -> {
                slots.put("Select", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.SELECT));
                slots.put("AddToSelection", (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                        Accessible.Action.ADD_TO_SELECTION, Accessible.Action.SELECT));
                slots.put("RemoveFromSelection", (UiaCom.P) self -> postFirstAccepted(context,
                        nodeId, Accessible.Action.DESELECT));
                slots.put("get_IsSelected", (UiaCom.PP) (self, out) -> {
                    AccessibleNode node = context.tree().find(nodeId);
                    if (node == null || node.selectionItem() == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    putBool(out, node.selectionItem().selected());
                    return UiaIds.S_OK;
                });
                slots.put("get_SelectionContainer", (UiaCom.PP) (self, out) -> {
                    AccessibleTree tree = context.tree();
                    AccessibleNode item = tree.find(nodeId);
                    if (item == null) {
                        return UiaIds.E_ELEMENT_NOT_AVAILABLE;
                    }
                    // The member's container by semantics 1, resolved once at publish: the nearest
                    // ancestor with a selection facet, climbed to through synthetic ancestors only
                    // (a calendar day's grid past its week row), and none for a member that
                    // declared itself containerless or whose climb met a widget first. The same
                    // rule GetSelection, the model's SELECTION_CHANGED and the other two bridges
                    // read. Until 2026-09-15 this climbed through any ancestor.
                    int at = item.selectionContainer();
                    // The simple interface: get_SelectionContainer's declared out type.
                    MemoryUtil.memPutAddress(out, at == AccessibleNode.NONE ? 0
                            : context.simpleElementFor(tree.node(at).id()));
                    return UiaIds.S_OK;
                });
            }

            // Vended only on a node that publishes the verb (UiaPatterns), and gated on it here as
            // well, because the pointer a client holds outlives the snapshot that vended it.
            //
            // Its refusal on a node that is not ENABLED is 0x80040200, a choice and not a reading:
            // the platform's own providers disagree here, the Win32 controls'
            // client-side ListViewItem and WindowsTabItem proxies throwing ElementNotEnabledException
            // before anything else while WPF's item peers scroll whatever the item's state (read
            // 2026-09-15, readings/windows-dump-uia-focus-and-scroll-item.txt). See refusal()'s
            // javadoc for the reasoning and Windows open question 1.
            case UiaIds.SCROLL_ITEM_PATTERN -> slots.put("ScrollIntoView",
                    (UiaCom.P) self -> postFirstAccepted(context, nodeId,
                            Accessible.Action.SCROLL_INTO_VIEW));
            default -> {
            }
        }
    }

    /**
     * The simple pointers of a container's realized selected members, in reading order: every node
     * of the snapshot carrying a selected {@code SelectionItemFacet} whose resolved selection
     * container is this one. A selected member the widget has not realized (a row scrolled far
     * away) has no node and is not listed, the degradation ADR 039 §4.1 accepts.
     */
    private static long[] selectedMembersOf(AccessibleTree tree, int container,
                                            UiaProvider.Context context) {
        int count = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.selectionContainer() == container && node.selectionItem().selected()) {
                count++;
            }
        }
        long[] pointers = new long[count];
        int at = 0;
        for (int i = 0; i < tree.nodeCount() && at < count; i++) {
            AccessibleNode node = tree.node(i);
            if (node.selectionContainer() == container && node.selectionItem().selected()) {
                pointers[at++] = context.simpleElementFor(node.id());
            }
        }
        return pointers;
    }
    /**
     * A row of an outline below its top level, which UI Automation nests under its parent row
     * rather than beside it (NVDA counts TreeItem ancestors and ignores the Level property):
     * the rows shape's member carrying a hierarchy facet, whatever widget published it (ADR 045
     * §1: a shape is derived, not a role).
     *
     * @param node a node
     * @return whether it is nested under an earlier row
     */
    static boolean isOutlineRow(AccessibleNode node) {
        return Shape.of(node) == Shape.ROWS && node.hierarchy() != null
                && node.hierarchy().level() > 0;
    }

}
