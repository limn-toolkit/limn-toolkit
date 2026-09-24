package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.CellFacet;
import limn.accessibility.RoleNames;
import limn.accessibility.StateNames;

/**
 * What one node answers to {@code IRawElementProviderSimple::GetPropertyValue}.
 *
 * <p>A decision layer and not a marshalling one: this turns a property id into a Java value — a
 * {@link String}, a {@link Boolean}, an {@link Integer} — or {@code null}, and the step above it
 * writes that into a {@code VARIANT}. {@code null} is the common answer and not a failure:
 * {@code VT_EMPTY} for anything unanswered is accepted, and the spike watched UI Automation ask for
 * ids we do not answer and carry on without complaint. Splitting it here is what lets every one of
 * these choices be asserted on a machine with no COM.
 *
 * <p><b>Only what a node alone can answer.</b> The bounding rectangle and the runtime id are asked
 * of a node but answered by the window — one needs the window's screen origin and its scale factor, the
 * other needs the host's own runtime id to be prefixed onto it — so both belong to the fragment
 * layer, which has the window, and neither is answered here.
 *
 * <p><b>Three properties are deliberately unanswered, and each is a missing reading rather than a
 * decision.</b> {@code LocalizedControlType} is the phrase {@link UiaRoles} already chooses for the
 * eight roles UI Automation has no word for, and it is spoken to the user, so it has to resolve
 * under the node's own locale — which needs this module to carry a bundle of its own, and it does
 * not yet. {@code HeadingLevel} needs a constant from {@code uiautomationcoreapi.h}: the guest's
 * interop assembly knows the property (it is in {@link UiaIds}) and not the enumerators, and a
 * recalled constant is a defect that compiles. {@code Culture} is an LCID, and Java has no mapping
 * from a {@link java.util.Locale} to one; the guest can be asked for the whole table, and that is a
 * reading, not a guess. Until then each answers {@code VT_EMPTY}, which costs a switch control the
 * word "switch" and a heading the word "heading" — real losses, named here so they are visible
 * rather than discovered by a listener.
 *
 * <p><b>Three properties are elements and not values</b> -- {@code LabeledBy}, {@code DescribedBy}
 * and {@code ControllerFor} -- and are answered by the provider from {@link #relatedNodes}, which
 * is the decision, with the pointers minted there, which is not.
 */
final class UiaProperties {

    private UiaProperties() {
    }

    /**
     * The nodes at the other end of one kind of relation, in the order the node declared them:
     * what {@code LabeledBy} (the first, alone), {@code DescribedBy} and {@code ControllerFor}
     * (all, as an array) are answered with.
     *
     * @param node a node from the published tree
     * @param kind the relation
     * @return the target ids, empty when the node has none of that kind
     */
    static long[] relatedNodes(AccessibleNode node, Accessible.Relation kind) {
        int count = 0;
        for (var relation : node.relations()) {
            if (relation.kind() == kind) {
                count++;
            }
        }
        long[] targets = new long[count];
        int at = 0;
        for (var relation : node.relations()) {
            if (relation.kind() == kind) {
                targets[at++] = relation.target();
            }
        }
        return targets;
    }

    /**
     * @param node       a node from the published tree
     * @param propertyId one of {@link UiaIds}' property ids
     * @return the value, or {@code null} for {@code VT_EMPTY}
     */
    static Object valueOf(AccessibleNode node, int propertyId) {
        switch (propertyId) {
            case UiaIds.CONTROL_TYPE:
                return UiaRoles.of(node.role());

            case UiaIds.LOCALIZED_CONTROL_TYPE: {
                // Only for the eight roles UI Automation has no word for. Every other role gets the
                // platform's own phrase, already localized and already what every other application
                // on the machine says -- and answering here would replace it with ours for no gain.
                // Microsoft's guidance makes it REQUIRED for Custom, which is where three of the
                // eight are: "custom" is not a word worth speaking.
                // Under the node's own locale, not the process's: a dialog in another language
                // names its own controls in that language (§1.7).
                return UiaRoles.speaksOurOwnPhrase(node.role())
                        ? RoleNames.of(node.role(), node.locale()) : null;
            }

            // Both true for every published node, on purpose. They are how UI Automation builds
            // its control view and its content view, and a provider that leaves them empty is
            // asking every client to guess which of its elements are worth showing. The walk has
            // already deleted the scaffolding (§1.6), so what survives to be published is a
            // control a user can meet.
            case UiaIds.IS_CONTROL_ELEMENT:
            case UiaIds.IS_CONTENT_ELEMENT:
                return Boolean.TRUE;

            case UiaIds.NAME:
                // Never null: a client asking for the name of a node that has none should hear an
                // empty string, and a VT_EMPTY here reads to some clients as "ask somewhere else".
                return node.name();

            case UiaIds.HELP_TEXT:
                return node.description().isEmpty() ? null : node.description();

            // The node's own identifier, which is stable across republishes by §1.3 and is what a
            // script driving this application would key on. Not the runtime id, which is the
            // window's business and carries the host's prefix.
            case UiaIds.AUTOMATION_ID:
                return Long.toString(node.id());

            case UiaIds.IS_ENABLED:
                return node.has(Accessible.State.ENABLED);
            // Where SetFocus is accepted, and not only where a widget of its own takes the keyboard:
            // an item the cursor can be put on publishes FOCUS without being FOCUSABLE (the list
            // is), and SetFocus posts FOCUS. Answering the state alone made every row, header item
            // and group say false while its SetFocus worked, so a managed client refused the call
            // before asking; and NVDA says "not selected" of the focus only when it is FOCUSABLE,
            // so taking a row out of a MULTI selection was silent
            // (readings/nvda-2024.4.2-negative-states.txt, 2026-09-23).
            case UiaIds.IS_KEYBOARD_FOCUSABLE:
                return node.has(Accessible.State.FOCUSABLE)
                        || node.accepts(Accessible.Action.FOCUS);
            // HAS_KEYBOARD_FOCUS is not answered here: since 2026-09-15 it is where the user is,
            // the tree's effective focus (semantics 4), which a node alone cannot say -- the
            // focused table is FOCUSED and does not have it, its ACTIVE cell does. The provider
            // answers it from the tree (UiaProvider.Context#hasKeyboardFocus), as it answers the
            // element-valued properties below.

            // The inversion §1.2 warns about: a node scrolled out of a viewport is VISIBLE and not
            // SHOWING, and UI Automation's word for that state is IsOffscreen. Answering it from
            // VISIBLE instead would call every scrolled-away row on screen.
            case UiaIds.IS_OFFSCREEN:
                return !node.has(Accessible.State.SHOWING);

            // Either says it: the role is what a password field declares, and the state is what a
            // field that is masking for another reason carries.
            case UiaIds.IS_PASSWORD:
                return node.role() == Accessible.Role.PASSWORD_FIELD
                        || node.has(Accessible.State.PASSWORD);

            // §2.1: UI Automation's answer for a dialog rendered inside a window, which is why the
            // role maps to a neutral container rather than to a window control type. An alert is
            // one too -- the platform has no alert control type, and this is how a client learns
            // there is something to read now.
            case UiaIds.IS_DIALOG:
                return node.role() == Accessible.Role.DIALOG
                        || node.role() == Accessible.Role.ALERT;

            // Semantics 6 (decision 4): "n of m" from the selection item's numbers and
            // the depth from the hierarchy facet, each only when it is not zero, which is the
            // model's "no number" and a client's too (NVDA 2024.4.2 uses each only when positive,
            // readings/nvda-2024.4.2-uia.md §2). The level passes through: the platform's base is
            // one, read off a native Win32 tree on 2026-09-15 (UiaIds#LEVEL). NVDA ignores Level on
            // a tree item and counts TreeItem ancestors instead, which is why UiaFragment nests
            // tree rows; the property is for every other client.
            case UiaIds.POSITION_IN_SET:
                return node.selectionItem() != null && node.selectionItem().positionInSet() > 0
                        ? Integer.valueOf(node.selectionItem().positionInSet()) : null;
            case UiaIds.SIZE_OF_SET:
                return node.selectionItem() != null && node.selectionItem().sizeOfSet() > 0
                        ? Integer.valueOf(node.selectionItem().sizeOfSet()) : null;
            case UiaIds.LEVEL:
                return node.hierarchy() != null && node.hierarchy().level() > 0
                        ? Integer.valueOf(node.hierarchy().level()) : null;

            // UI Automation has no busy bit; ItemStatus is its field for "the state of this item" as
            // text a client reads out. So BUSY is a word here, in the node's own language, and an
            // item that is not busy has no status at all rather than a status saying it is idle.
            //
            // A sorted column's header says its direction here too (decision 36), because this is
            // where the desktop puts one: File Explorer's own column header — a SplitButton (50031)
            // of class UIColumnHeader — answers ItemStatus "Classificado (Crescente)" /
            // "(Descrescente)", read on the guest 2026-09-15
            // (readings/windows-read-native-sort-direction.txt). The phrase is the model's, taken
            // from the node's description rather than built here: this method runs on a platform
            // thread with no locale scope open and could not resolve one, which is why the model
            // kept the description beside the enumeration the other two platforms read (ADR 041
            // §7's amendment of this date). HelpText answers the same description already, which is
            // the "AND HelpText" half of the settled list and needs no code: NVDA 2024.4.2 has no
            // ItemStatus handler and speaks a description.
            //
            // A CHOICE AND NOT A READING: a busy sorted header answers the busy word alone. Nothing
            // was read about composing the two — File Explorer's header was not busy, and no guest
            // reading shows a client joining two status phrases — and joining two translated
            // fragments with punctuation chosen here would invent a sentence in twenty-one
            // languages from a thread with no locale. Busy is the transient state the property
            // exists for; the direction is not lost while it holds, because HelpText carries it and
            // is what the one reader measured on the guest speaks. What would settle it is a live
            // Narrator or Inspect run over a header that is both (phase 5).
            case UiaIds.ITEM_STATUS:
                if (node.has(Accessible.State.BUSY)) {
                    return StateNames.of(Accessible.State.BUSY, node.locale());
                }
                return statusWhenNotBusy(node);

            default:
                return null;
        }
    }

    /**
     * What {@code ItemStatus} carries for a node that is <b>not</b> busy: a sorted column header's
     * direction, as the phrase the model published, and nothing at all for every other node.
     *
     * <p>Package-private because the events half needs the same answer: a {@code BUSY} state that
     * moves is raised as this property, and the value for the side of that change where busy does
     * not hold has to be what {@link #valueOf} answers there, or a client is told the status is one
     * thing and reads another ({@code UiaBridge#changedValue}). This is the not-busy answer and not
     * {@code valueOf} itself because the node it is asked about is the one in the published tree,
     * which carries {@code BUSY} on the busy side of the change: asking {@code valueOf} would
     * answer the busy word for the old value of a busy that has just been set.
     *
     * @param node the node asked, or {@code null} when it has left the tree
     * @return the status, or {@code null} for a node that has none
     */
    static String statusWhenNotBusy(AccessibleNode node) {
        return statusOf(node, node == null ? "" : node.description());
    }

    /**
     * The same, for a description this node had or is about to have rather than the one it carries
     * now: the description itself where the node is a sorted column header, and nothing anywhere
     * else. A {@code DESCRIPTION_CHANGED} on a header cell raises this property with the event's
     * two descriptions, and they pass through here so that what is announced and what
     * {@link #valueOf} answers cannot disagree.
     *
     * @param node        the node the description belongs to, or {@code null} when it has left the
     *                    tree
     * @param description the description at that moment
     * @return the status that description makes, or {@code null} where it makes none
     */
    static String statusOf(AccessibleNode node, String description) {
        return node != null && isSortedHeader(node) && description != null && !description.isEmpty()
                ? description : null;
    }

    /**
     * @param node the node asked
     * @return whether it is the header cell of a column the table is sorted on: a cell in the
     *         header row ({@code -1}) whose {@code CellFacet.Sort} is not {@code NONE}. A footer
     *         cell carries {@code -2} and a data cell a row from zero, and neither heads a column,
     *         whatever direction its facet holds
     */
    private static boolean isSortedHeader(AccessibleNode node) {
        return node.cell() != null && node.cell().row() == -1
                && node.cell().sort() != CellFacet.Sort.NONE;
    }
}
