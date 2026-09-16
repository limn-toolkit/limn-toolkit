package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * The slots of {@code IRawElementProviderSimple}, wired to the layers that already decide what they
 * answer.
 *
 * <p>Nothing here decides anything. The control type comes from {@link UiaRoles}, the properties
 * from {@link UiaProperties}, the pattern list from {@link UiaPatterns} — each of them settled and
 * asserted on its own — and this turns their answers into the memory a client reads. Keeping the
 * two apart is what let every one of those be tested without a COM object anywhere near it.
 *
 * <p><b>A call arrives on a thread this bridge does not own, for a node that may have left the
 * tree.</b> Both are ordinary. The tree is an immutable snapshot fetched once per call, so a
 * republish between two calls cannot be seen half-applied; and a node the current snapshot no
 * longer holds answers {@code UIA_E_ELEMENTNOTAVAILABLE}, which is what §1.3 promises a client
 * holding an element for something that has gone.
 */
final class UiaProvider {

    private UiaProvider() {
    }

    /**
     * Everything a slot needs that is not in the snapshot, so that the element itself holds no
     * widget, no scene and no window.
     */
    interface Context {

        /** @return the tree as of now, which is immutable and may differ between two calls */
        AccessibleTree tree();

        /**
         * @param patternId one of {@link UiaIds}' pattern ids
         * @param nodeId    the node being asked
         * @return the interface pointer implementing that pattern for that node, already
         *         referenced for the caller, or {@code 0} when this node does not vend it
         */
        long patternProviderFor(long nodeId, int patternId);

        /**
         * @return the provider UI Automation gave for the window this tree is drawn in, which the
         *         root answers and every child answers {@code null} to, or {@code 0} when there is
         *         no window to ask
         */
        long hostProvider();

        /** @return how to hand a string to a client that will free it */
        UiaStrings.Allocator strings();

        /**
         * @param values the runtime id, marker included
         * @return a {@code SAFEARRAY} of 32-bit integers the caller now owns, or {@code 0} when
         *         none could be made — which is the same seam the strings go through and for the
         *         same reason: the caller frees it with {@code SafeArrayDestroy}, so it has to
         *         come from the matching allocator
         */
        long int32Array(int[] values);

        /**
         * @param pointers interface pointers, each already referenced for the array
         * @return a {@code SAFEARRAY} of {@code IUnknown*} the caller now owns, or {@code 0}
         */
        long unknownArray(long[] pointers);

        /**
         * @param nodeId the node a client asked to navigate to, or whose element it asked for
         * @return that node's fragment pointer, already referenced for the caller, or {@code 0}
         *         when the node is not in the current tree
         */
        long elementFor(long nodeId);

        /**
         * The same node through its <em>simple</em> interface, for the methods whose declared
         * out type is {@code IRawElementProviderSimple**}: a grid's {@code GetItem}, a cell's
         * {@code get_ContainingGrid}, an item's {@code get_SelectionContainer}, and every element
         * of a headers array. Measured on the guest: a fragment pointer handed back through one of
         * those is read as the simple vtable and the client fails its cast, while the same pointer
         * through {@code Navigate}, whose declared type is the fragment, is right.
         *
         * @param nodeId the node
         * @return that node's simple pointer, already referenced for the caller, or {@code 0}
         */
        long simpleElementFor(long nodeId);

        /** @return the fragment root's own pointer, referenced for the caller */
        long rootElement();

        /**
         * Asks the toolkit to move the keyboard here, on the thread that owns it.
         *
         * @param nodeId the node a client asked to focus
         * @return whether the request was accepted, which is not whether the widget took it
         */
        boolean requestFocus(long nodeId);

        /**
         * Whether a node has the keyboard as UI Automation means it: where the user is, which is
         * the tree's {@linkplain AccessibleTree#effectiveFocus() effective focus} -- the cursor
         * item of a focused list, tree, table or calendar, or the focused node itself when it has
         * none (decision 1; semantics 4). A bridge holding several windows also answers true for
         * the node another window's cursor resolved into this one (decision 5).
         *
         * @param nodeId a node of this tree
         * @return what {@code HasKeyboardFocus} answers for it
         */
        default boolean hasKeyboardFocus(long nodeId) {
            return nodeId != 0 && tree().indexOf(nodeId) >= 0
                    && tree().effectiveFocus() == nodeId;
        }

        /**
         * The element for a node another window's tree holds, which is where this window's
         * effective focus may point once a focused field's cursor lives in its native popup
         * (decision 5): that window's fragment pointer, from that window's own provider.
         *
         * @param nodeId a node this tree does not hold
         * @return its fragment pointer, referenced for the caller, or {@code 0} when no window this
         *         context knows holds it
         */
        default long elementInAnotherWindowFor(long nodeId) {
            return 0;
        }

        /**
         * The <em>simple</em> element for a node another window's tree holds, which is what an
         * element-valued property whose target is not in this window answers with (CRIT-2;
         * ADR 039 §1.11): the opener of a native popup carries {@code CONTROLLER_FOR} naming the
         * popup's root, which the platform put in a window of its own, and node identifiers have
         * been process-wide since §1.3's 2026-09-14 amendment, so such a target is a real node and
         * not a dangling number.
         *
         * <p>The simple interface and not the fragment one, for the reason
         * {@link #simpleElementFor} gives: {@code LabeledBy}, {@code DescribedBy} and
         * {@code ControllerFor} all declare {@code IRawElementProviderSimple**}.
         *
         * @param nodeId a node this tree does not hold
         * @return its simple pointer, referenced for the caller, or {@code 0} when no window this
         *         context knows holds it
         */
        default long simpleElementInAnotherWindowFor(long nodeId) {
            return 0;
        }

        /**
         * The node of <em>this</em> tree that another window's effective focus names: the day of a
         * native popup whose opener's cursor resolved into it (decision 5). What this window's own
         * {@code GetFocus} answers when nothing of its own tree is focused, so that it agrees with
         * the {@code HasKeyboardFocus} {@link #hasKeyboardFocus} answers for that node.
         *
         * @return that node's identifier, or {@code 0} when no other window's cursor is here
         */
        default long cursorFromAnotherWindow() {
            return 0;
        }

        /**
         * A client subscribed to, or unsubscribed from, an event that covers this window.
         *
         * <p>The one thing UI Automation tells a provider about its <em>clients</em>, and the only
         * per-window answer to "is anyone reading this" the platform has: the process-wide
         * {@code UiaClientsAreListening()} is true on an ordinary desktop with no reader (ADR 039
         * §13.5). Called on an RPC thread.
         *
         * @param eventId     which event, in {@link UiaIds}' numbering
         * @param propertyIds for a property-changed subscription, which properties; empty otherwise
         * @param added       {@code true} on subscribe, {@code false} on unsubscribe
         */
        default void eventAdvised(int eventId, int[] propertyIds, boolean added) {
            // A context that is not a bridge has no clients to be told about.
        }

        /**
         * Asks the toolkit to perform a verb, on the thread that owns the widget.
         *
         * <p><b>Accepted is not done.</b> The call returns as soon as the request is posted,
         * because a client's thread must not wait on the user-interface thread — §3.1's rule — and
         * because the widget performs it through its own private path with its own guards. What a
         * client is told is that the request was understood.
         *
         * @param nodeId the node a client acted on
         * @param action what it asked for
         * @param arg    the value, where the verb takes one
         * @return whether the request was accepted
         */
        boolean perform(long nodeId, limn.accessibility.Accessible.Action action,
                        limn.accessibility.Accessible.Argument arg);
    }

    /**
     * The four members after {@code IUnknown}'s, <b>by name</b>: where each lands in the vtable is
     * {@link UiaInterfaces}' business, and that table was read off a guest.
     *
     * @param nodeId  which node this element stands for
     * @param context what the slots read
     * @return the slots, ready to be handed to {@link UiaObject}
     */
    static Map<String, CallbackI> simpleSlots(long nodeId, Context context) {
        return Map.of(
                "get_ProviderOptions",
                (UiaCom.PP) (self, out) -> providerOptions(nodeId, out),
                "GetPatternProvider",
                (UiaCom.PIP) (self, patternId, out) -> patternProvider(nodeId, patternId, out,
                        context),
                "GetPropertyValue",
                (UiaCom.PIP) (self, propertyId, out) -> propertyValue(nodeId, propertyId, out,
                        context),
                "get_HostRawElementProvider",
                (UiaCom.PP) (self, out) -> hostProvider(nodeId, out, context));
    }

    /**
     * The six members of {@code IRawElementProviderFragment}, by name.
     *
     * @param nodeId  which node this element stands for
     * @param context what the slots read
     * @return the slots, ready to be handed to {@link UiaObject}
     */
    static Map<String, CallbackI> fragmentSlots(long nodeId, Context context) {
        return Map.of(
                "Navigate",
                (UiaCom.PIP) (self, direction, out) -> navigate(nodeId, direction, out, context),
                "GetRuntimeId",
                (UiaCom.PP) (self, out) -> runtimeId(nodeId, out, context),
                "get_BoundingRectangle",
                (UiaCom.PP) (self, out) -> boundingRectangle(nodeId, out, context),
                "GetEmbeddedFragmentRoots",
                (UiaCom.PP) (self, out) -> embeddedFragmentRoots(nodeId, out),
                "SetFocus",
                (UiaCom.P) self -> setFocus(nodeId, context),
                "get_FragmentRoot",
                (UiaCom.PP) (self, out) -> fragmentRoot(out, context));
    }

    /**
     * The two members of {@code IRawElementProviderFragmentRoot}, by name. Only the root node's
     * element serves this interface: it is what a client asks "what is under this point" and
     * "what has the keyboard" of, and those are questions about a window.
     *
     * @param context what the slots read
     * @return the slots, ready to be handed to {@link UiaObject}
     */
    static Map<String, CallbackI> fragmentRootSlots(Context context) {
        return Map.of(
                "ElementProviderFromPoint",
                (UiaCom.PDDP) (self, x, y, out) -> elementFromPoint(x, y, out, context),
                "GetFocus",
                (UiaCom.PP) (self, out) -> focus(out, context));
    }

    /**
     * The root's {@code IRawElementProviderAdviseEvents}: both slots take the event and a
     * {@code SAFEARRAY} of property identifiers, which is decoded here and never kept.
     *
     * @param context who is told
     * @return the slots, ready to be handed to {@link UiaObject}
     */
    static Map<String, CallbackI> adviseEventsSlots(Context context) {
        return Map.of(
                "AdviseEventAdded",
                (UiaCom.PIP) (self, eventId, properties) -> advise(eventId, properties, true,
                        context),
                "AdviseEventRemoved",
                (UiaCom.PIP) (self, eventId, properties) -> advise(eventId, properties, false,
                        context));
    }

    /**
     * One subscription arriving or leaving, traced <b>whole</b>.
     *
     * <p>The most valuable two lines in the file, and the reason this half of the instrumentation
     * exists: the 2026-09-16 Windows reading could not say what NVDA had subscribed to, only that
     * it heard nothing. The event and the property array are what a client asks for, by name and
     * number, on the thread it asked from.
     */
    private static int advise(int eventId, long properties, boolean added, Context context) {
        int[] ids = int32sOf(properties);
        context.eventAdvised(eventId, ids, added);
        if (UiaTrace.on()) {
            UiaTrace.inbound(added ? "AdviseEventAdded" : "AdviseEventRemoved",
                    UiaTrace.event(eventId) + ' ' + UiaTrace.properties(ids), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * Reads a one-dimensional {@code SAFEARRAY} of 32-bit integers.
     *
     * <p>The layout is the Win32 one on a 64-bit process: {@code cDims} (2 bytes),
     * {@code fFeatures} (2), {@code cbElements} (4), {@code cLocks} (4), padding (4),
     * {@code pvData} (8), then one {@code SAFEARRAYBOUND} of {@code cElements} (4) and
     * {@code lLbound} (4). Anything that is not one dimension of four-byte elements is answered
     * as empty rather than read as if it were.
     */
    static int[] int32sOf(long safeArray) {
        if (safeArray == 0) {
            return new int[0];
        }
        int dims = MemoryUtil.memGetShort(safeArray) & 0xFFFF;
        int elementSize = MemoryUtil.memGetInt(safeArray + 4);
        if (dims != 1 || elementSize != 4) {
            return new int[0];
        }
        long data = MemoryUtil.memGetAddress(safeArray + 16);
        int count = MemoryUtil.memGetInt(safeArray + 24);
        if (data == 0 || count < 0 || count > 4096) {
            return new int[0];
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = MemoryUtil.memGetInt(data + 4L * i);
        }
        return values;
    }

    private static int navigate(long nodeId, int direction, long out, Context context) {
        if (out == 0) {
            return refused("Navigate", direction, nodeId, UiaIds.E_NO_INTERFACE);
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return refused("Navigate", direction, nodeId, UiaIds.E_ELEMENT_NOT_AVAILABLE);
        }
        int found = UiaFragment.navigate(tree, index, direction);
        // Nothing in that direction is a null and S_OK: a client walks until it is told there is
        // no more, and a failure would read as a broken provider rather than as an edge.
        if (found != AccessibleNode.NONE) {
            MemoryUtil.memPutAddress(out, context.elementFor(tree.node(found).id()));
        }
        if (UiaTrace.onForRead()) {
            UiaTrace.inbound("Navigate", UiaTrace.direction(direction) + " from=" + nodeId
                    + " answer=" + (found == AccessibleNode.NONE ? "none"
                            : UiaTrace.element(tree, tree.node(found).id())), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * Says that {@code Navigate} answered a failure, and answers it.
     *
     * <p>The formatting is inside the gate and not at the call site, which is the rule the whole
     * of this instrumentation is written to: with the trace off nothing here allocates a string,
     * a {@code StringBuilder} or a boxed number.
     *
     * @param entryPoint the member
     * @param direction  which way it was asked to go
     * @param nodeId     the node it was asked on
     * @param hresult    what it answers
     * @return that {@code HRESULT}
     */
    private static int refused(String entryPoint, int direction, long nodeId, int hresult) {
        if (UiaTrace.on()) {
            UiaTrace.inbound(entryPoint, UiaTrace.direction(direction) + " from=" + nodeId
                    + " answer=none", hresult);
        }
        return hresult;
    }

    /**
     * The same for an entry point whose whole question is the node, or the root's two, which are
     * asked of a window and carry none.
     *
     * @param entryPoint the member
     * @param nodeId     the node it was asked on, or {@code 0} for the root's own two
     * @param hresult    what it answers
     * @return that {@code HRESULT}
     */
    private static int refused(String entryPoint, long nodeId, int hresult) {
        if (UiaTrace.on()) {
            UiaTrace.inbound(entryPoint, (nodeId == 0 ? "" : "node=" + nodeId + ' ')
                    + "answer=none", hresult);
        }
        return hresult;
    }

    /**
     * <p>How UI Automation establishes an element's identity, so it is among the first things a
     * client that arrives asks and among the last it stops asking: traced for that reason, with the
     * same two failures {@code Navigate} names.
     */
    private static int runtimeId(long nodeId, long out, Context context) {
        int answer = runtimeIdAnswering(nodeId, out, context);
        if (UiaTrace.onForRead(answer)) {
            UiaTrace.inbound("GetRuntimeId", UiaTrace.element(context.tree(), nodeId)
                    + " answer=" + (answer == UiaIds.S_OK ? "runtimeId" : "none"), answer);
        }
        return answer;
    }

    /** The answer itself, so that {@link #runtimeId} can say what it was. */
    private static int runtimeIdAnswering(long nodeId, long out, Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, 0);
        if (context.tree().find(nodeId) == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        MemoryUtil.memPutAddress(out, context.int32Array(UiaFragment.runtimeId(nodeId)));
        return UiaIds.S_OK;
    }

    /**
     * <p>Four doubles written straight into the caller's {@code UiaRect}, in screen coordinates.
     * A node that has gone answers an empty rectangle rather than a failure, because a client asks
     * this while drawing a highlight and a zero rectangle draws nothing.
     */
    private static int boundingRectangle(long nodeId, long out, Context context) {
        if (out == 0) {
            return refused("get_BoundingRectangle", nodeId, UiaIds.E_NO_INTERFACE);
        }
        double[] box = {0, 0, 0, 0};
        AccessibleTree tree = context.tree();
        AccessibleNode node = tree.find(nodeId);
        if (node != null) {
            box = UiaFragment.boundingRectangle(tree, node);
        }
        for (int i = 0; i < 4; i++) {
            MemoryUtil.memPutDouble(out + (long) i * 8, box[i]);
        }
        if (UiaTrace.onForRead()) {
            UiaTrace.inbound("get_BoundingRectangle", "node=" + nodeId + " answer=["
                    + box[0] + ',' + box[1] + ',' + box[2] + ',' + box[3] + ']', UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * <p>Always none. An in-scene popup is inside this fragment and reached by navigation; a popup
     * the platform put in a window of its own is that window's fragment root and is found through
     * the desktop rather than through here.
     */
    private static int embeddedFragmentRoots(long nodeId, long out) {
        if (out == 0) {
            return refused("GetEmbeddedFragmentRoots", nodeId, UiaIds.E_NO_INTERFACE);
        }
        MemoryUtil.memPutAddress(out, 0);
        if (UiaTrace.onForRead()) {
            UiaTrace.inbound("GetEmbeddedFragmentRoots", "node=" + nodeId + " answer=none",
                    UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * <p>{@code FOCUS}, posted only where the node publishes it now (semantics 5: SetFocus
     * [FOCUS]), and refused synchronously otherwise the way every pattern verb is
     * ({@link UiaPatternProviders#refusal}, whose javadoc says which platform providers were read
     * answering {@code SetFocus} not-enabled first, and which do not). A focusable widget's FOCUS
     * is the walk's free verb; an item publishes it where moving the cursor does not select
     * (decision 11). Until 2026-09-15 it was posted for any node, the root and a disabled button
     * included.
     *
     * <p><b>0x80040200 here is a choice and not a reading.</b> The two refusal numbers themselves
     * were read on the guest 2026-09-13 (readings/windows-dump-uia-hresults.txt), but which of them
     * {@code SetFocus} answers on a node that is not enabled was read 2026-09-15 and came back
     * split: the Win32 controls' client-side {@code ProxySimple} throws
     * {@code ElementNotEnabledException} for a disabled window and {@code InvalidOperationException}
     * for an element that cannot take the keyboard, while WPF's {@code ElementProxy.SetFocus}
     * checks no enabled bit at all (readings/windows-dump-uia-focus-and-scroll-item.txt). This
     * bridge answers as it answers every other verb; {@link UiaPatternProviders#refusal} carries
     * the reasoning and names it as Windows open question 1.
     */
    private static int setFocus(long nodeId, Context context) {
        int answer = setFocusAnswering(nodeId, context);
        if (UiaTrace.on()) {
            // A client ACTING on the widget and not reading it, which is why this one is described
            // whole: what it was asked on, and whether the request was posted or refused.
            UiaTrace.inbound("SetFocus", UiaTrace.element(context.tree(), nodeId) + " answer="
                    + (answer == UiaIds.S_OK ? "posted" : "refused"), answer);
        }
        return answer;
    }

    /** The answer itself, so that {@link #setFocus} can say what it was. */
    private static int setFocusAnswering(long nodeId, Context context) {
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!node.accepts(limn.accessibility.Accessible.Action.FOCUS)) {
            return UiaPatternProviders.refusal(node);
        }
        return context.requestFocus(nodeId) ? UiaIds.S_OK : UiaIds.E_ELEMENT_NOT_AVAILABLE;
    }

    private static int fragmentRoot(long out, Context context) {
        if (out == 0) {
            return refused("get_FragmentRoot", 0, UiaIds.E_NO_INTERFACE);
        }
        long root = context.rootElement();
        MemoryUtil.memPutAddress(out, root);
        if (UiaTrace.onForRead()) {
            AccessibleTree tree = context.tree();
            UiaTrace.inbound("get_FragmentRoot", "answer=" + (tree.nodeCount() == 0 ? "none"
                    : UiaTrace.element(tree, tree.root().id())) + " provider=0x"
                    + Long.toHexString(root), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    private static int elementFromPoint(double x, double y, long out, Context context) {
        if (out == 0) {
            return refused("ElementProviderFromPoint", 0, UiaIds.E_NO_INTERFACE);
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        int found = UiaFragment.elementFromPoint(tree, x, y);
        // A point outside this window is a null and S_OK: the client asked whether anything of
        // ours is there, and "no" is an answer.
        if (found != AccessibleNode.NONE) {
            MemoryUtil.memPutAddress(out, context.elementFor(tree.node(found).id()));
        }
        if (UiaTrace.on()) {
            UiaTrace.inbound("ElementProviderFromPoint", "x=" + x + " y=" + y + " answer="
                    + (found == AccessibleNode.NONE ? "none"
                            : UiaTrace.element(tree, tree.node(found).id())), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    private static int focus(long out, Context context) {
        if (out == 0) {
            return refused("GetFocus", 0, UiaIds.E_NO_INTERFACE);
        }
        int answer = focusing(out, context);
        if (UiaTrace.on()) {
            // The node is named by identifier alone and not described: what a fragment root
            // answers here may be a node of ANOTHER window's tree (decision 5, a cursor resolved
            // into a native popup), and describing it against this tree would say "gone" about a
            // node that is perfectly alive next door.
            AccessibleTree tree = context.tree();
            long provider = MemoryUtil.memGetAddress(out);
            UiaTrace.inbound("GetFocus", "effectiveFocus=" + tree.effectiveFocus()
                    + " cursorFromAnotherWindow=" + context.cursorFromAnotherWindow()
                    + " answer=" + (provider == 0 ? "none" : "0x" + Long.toHexString(provider)),
                    answer);
        }
        return answer;
    }

    private static int focusing(long out, Context context) {
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        int focused = UiaFragment.focus(tree);
        if (focused != AccessibleNode.NONE) {
            MemoryUtil.memPutAddress(out, context.elementFor(tree.node(focused).id()));
        } else if (tree.effectiveFocus() != 0) {
            // The cursor lives in a native popup's tree (decision 5): the element is that
            // window's, handed over by its own provider.
            MemoryUtil.memPutAddress(out, context.elementInAnotherWindowFor(tree.effectiveFocus()));
        } else {
            // And this may be that popup's own root, asked directly: nothing here is focused,
            // but the opener's cursor is on a node of this tree, which answers HasKeyboardFocus
            // true, so GetFocus names it too.
            long foreign = context.cursorFromAnotherWindow();
            if (foreign != 0) {
                MemoryUtil.memPutAddress(out, context.elementFor(foreign));
            }
        }
        return UiaIds.S_OK;
    }

    /**
     * Server-side, always: this provider runs inside the process it describes, which is what lets a
     * call reach the widget tree at all.
     */
    private static int providerOptions(long nodeId, long out) {
        if (out == 0) {
            return refused("get_ProviderOptions", nodeId, UiaIds.E_NO_INTERFACE);
        }
        MemoryUtil.memPutInt(out, UiaIds.PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER);
        if (UiaTrace.onForRead()) {
            // The first thing UI Automation asks of an element it has been handed, so an IN line
            // for it is the earliest evidence in the file that a client's process arrived.
            UiaTrace.inbound("get_ProviderOptions", "node=" + nodeId + " answer=SERVER_SIDE("
                    + UiaIds.PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER + ')', UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * <p>A pattern this node does not vend is a null and {@code S_OK}, not a failure: the question
     * "can you do this" has "no" as an ordinary answer, and an {@code HRESULT} failure would be
     * read as a broken provider.
     */
    private static int patternProvider(long nodeId, int patternId, long out, Context context) {
        if (out == 0) {
            return refused("GetPatternProvider", nodeId, UiaIds.E_NO_INTERFACE);
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        AccessibleNode node = tree.find(nodeId);
        if (node == null) {
            return refused("GetPatternProvider", nodeId, UiaIds.E_ELEMENT_NOT_AVAILABLE);
        }
        boolean vends = UiaPatterns.supports(tree, node, patternId);
        if (vends) {
            MemoryUtil.memPutAddress(out, context.patternProviderFor(nodeId, patternId));
        }
        if (UiaTrace.onForRead()) {
            UiaTrace.inbound("GetPatternProvider", UiaTrace.pattern(patternId) + " node=" + nodeId
                    + " answer=" + (vends ? "0x" + Long.toHexString(MemoryUtil.memGetAddress(out))
                            : "notVended"), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }

    /**
     * <p>The out parameter is a {@code VARIANT} the caller allocated, and it is cleared before
     * anything else on every path — including the failing ones, where a caller is entitled to look
     * at it anyway.
     */
    private static int propertyValue(long nodeId, int propertyId, long out, Context context) {
        int answer = propertyValueAnswering(nodeId, propertyId, out, context);
        if (UiaTrace.onForRead(answer)) {
            // Named by identifier and not described, unlike a raise: a reader asks this dozens of
            // times per node and the node's role and name are established by the lines around it.
            // What is repeated is the variant's own tag and value -- which is what the provider
            // hands the client and nothing more, so a masked password stays masked here.
            UiaTrace.inbound("GetPropertyValue", UiaTrace.property(propertyId) + " node=" + nodeId
                    + " answer=" + (out == 0 ? "none"
                            : UiaVariant.describe(MemoryUtil.memByteBuffer(out, UiaVariant.SIZE), 0,
                                    UiaTrace.STRING_LIMIT)), answer);
        }
        return answer;
    }

    /** The answer itself, so that {@link #propertyValue} can say what it was. */
    private static int propertyValueAnswering(long nodeId, int propertyId, long out,
                                              Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
        UiaVariant.empty(variant, 0);
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        // The element-valued properties, answered here because a provider pointer is what they
        // carry and the decision layer mints none: LabeledBy is one element, DescribedBy and
        // ControllerFor are arrays. Through the simple interface, which is the type the property
        // declares (see Context.simpleElementFor). A node without the relation stays VT_EMPTY,
        // which is the platform's default for all three.
        //
        // A target another window's tree holds is handed back as THAT window's element, from that
        // window's own provider (CRIT-2, 2026-09-15; ADR 039 §1.11's 2026-09-14 amendment): since
        // node identifiers became process-wide (§1.3, 2026-09-14) the opener of a native popup
        // carries CONTROLLER_FOR naming the popup's root in the OTHER window's tree, and a client
        // asking a combo what it controls while the popup is open is asking exactly that. Until
        // 2026-09-15 such a target was compacted away, so the property was VT_EMPTY -- a relation
        // the node does not declare -- while Linux was already naming the popup's frame node.
        //
        // A target NO open window holds is still left out rather than handed over as a NULL entry,
        // which is what UI Automation would otherwise be given: a SAFEARRAY(VT_UNKNOWN) whose
        // entries SafeArrayDestroy releases one by one.
        if (propertyId == UiaIds.LABELED_BY) {
            long[] labels = UiaProperties.relatedNodes(node,
                    limn.accessibility.Accessible.Relation.LABELLED_BY);
            if (labels.length > 0) {
                long element = relationElement(labels[0], context);
                if (element != 0) {
                    UiaVariant.unknown(variant, 0, element);
                }
            }
            return UiaIds.S_OK;
        }
        if (propertyId == UiaIds.DESCRIBED_BY || propertyId == UiaIds.CONTROLLER_FOR) {
            long[] targets = UiaProperties.relatedNodes(node, propertyId == UiaIds.DESCRIBED_BY
                    ? limn.accessibility.Accessible.Relation.DESCRIBED_BY
                    : limn.accessibility.Accessible.Relation.CONTROLLER_FOR);
            if (targets.length > 0) {
                long[] pointers = new long[targets.length];
                int held = 0;
                for (int i = 0; i < targets.length; i++) {
                    long element = relationElement(targets[i], context);
                    if (element != 0) {
                        pointers[held++] = element;
                    }
                }
                if (held > 0) {
                    if (held < pointers.length) {
                        pointers = java.util.Arrays.copyOf(pointers, held);
                    }
                    UiaVariant.unknownArray(variant, 0, context.unknownArray(pointers));
                }
            }
            return UiaIds.S_OK;
        }
        if (propertyId == UiaIds.HAS_KEYBOARD_FOCUS) {
            // A fact of the tree and not of the node (semantics 4): the cursor item of a focused
            // container has the keyboard, the container does not, and NVDA 2024.4.2 reads this
            // live when it hears a focus change (readings/nvda-2024.4.2-uia.md §1).
            UiaVariant.bool(variant, 0, context.hasKeyboardFocus(nodeId));
            return UiaIds.S_OK;
        }
        Object value = UiaProperties.valueOf(node, propertyId);
        if (value instanceof Boolean flag) {
            UiaVariant.bool(variant, 0, flag);
        } else if (value instanceof Integer number) {
            UiaVariant.i4(variant, 0, number);
        } else if (value instanceof Double number) {
            UiaVariant.r8(variant, 0, number);
        } else if (value instanceof String text) {
            // A string this bridge cannot allocate is not an error: the variant stays empty and the
            // client hears nothing for that property rather than being handed a pointer to free
            // that came from the wrong allocator.
            UiaVariant.bstr(variant, 0, context.strings().allocate(text));
        }
        // Anything else, including null, is the VT_EMPTY already written.
        return UiaIds.S_OK;
    }

    /**
     * One element-valued relation target, from whichever window holds it (CRIT-2).
     *
     * <p>{@code POPUP_FOR}, the mirror the popup's own root carries, is answered by no property
     * here: UI Automation has no "popup for" among its element-valued properties, and the three
     * this provider answers are the whole list {@link UiaProperties}' javadoc names. What a client
     * follows from the popup back to its opener is the opener's own {@code ControllerFor}, which
     * this now answers across the window boundary.
     *
     * <p><b>That absence is read and not assumed.</b> The platform's element-valued properties are
     * {@code LabeledBy} (30018), {@code ControllerFor} (30104), {@code DescribedBy} (30105),
     * {@code FlowsTo} (30106) and {@code FlowsFrom} (30148), and no member of
     * {@code UIA_PropertyIds} has "popup" in its name at all: read off the guest's own
     * {@code UIAutomationCore.dll} 7.2.26100.9278 on 2026-09-13
     * (readings/windows-dump-uia-typelib-all-members.txt, {@code
     * scripts/a11y/windows/dump-uia-typelib.ps1 -AllMembers}). Answering the opener as the popup's
     * own {@code ControllerFor} instead would say the popup controls the field that opened it,
     * which is the relation backwards. The settled list's Windows line reads "hands back a
     * ControllerFor/PopupFor element from the other HWND's provider"; only the first half is
     * answerable on this platform, and ADR 039 §1.11's 2026-09-15 amendment amends it on that
     * point rather than leaving the departure in a javadoc. Pinned by
     * {@code UiaProviderTest.aPopupForIsCarriedByNoPropertyBecauseThePlatformHasNone}.
     *
     * @param target  the node at the other end of the relation
     * @param context what the slots read
     * @return its simple pointer, referenced for the caller, or {@code 0} when no open window
     *         holds the target at all
     */
    private static long relationElement(long target, Context context) {
        long element = context.simpleElementFor(target);
        return element != 0 ? element : context.simpleElementInAnotherWindowFor(target);
    }

    /**
     * <p>The root answers the provider UI Automation made for the window, and every other node
     * answers null. That is what places this tree inside the desktop's own tree rather than beside
     * it: a client walking down from the window finds the root through the host, and finds every
     * other node through this provider's own navigation.
     */
    private static int hostProvider(long nodeId, long out, Context context) {
        if (out == 0) {
            return refused("get_HostRawElementProvider", nodeId, UiaIds.E_NO_INTERFACE);
        }
        AccessibleTree tree = context.tree();
        boolean isRoot = tree.nodeCount() > 0 && tree.root().id() == nodeId;
        long host = isRoot ? context.hostProvider() : 0;
        MemoryUtil.memPutAddress(out, host);
        if (UiaTrace.onForRead()) {
            // The CALL line from UiaHostProviderFromHwnd sits inside this one, for the root; every
            // other node answers none without asking the platform anything.
            UiaTrace.inbound("get_HostRawElementProvider", "node=" + nodeId + " answer="
                    + (host == 0 ? "none" : "0x" + Long.toHexString(host)), UiaIds.S_OK);
        }
        return UiaIds.S_OK;
    }
}
