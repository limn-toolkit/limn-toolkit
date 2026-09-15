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
                (UiaCom.PP) (self, out) -> providerOptions(out),
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
                (UiaCom.PP) (self, out) -> embeddedFragmentRoots(out),
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
                (UiaCom.PIP) (self, eventId, properties) -> {
                    context.eventAdvised(eventId, int32sOf(properties), true);
                    return UiaIds.S_OK;
                },
                "AdviseEventRemoved",
                (UiaCom.PIP) (self, eventId, properties) -> {
                    context.eventAdvised(eventId, int32sOf(properties), false);
                    return UiaIds.S_OK;
                });
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
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        int found = UiaFragment.navigate(tree, index, direction);
        // Nothing in that direction is a null and S_OK: a client walks until it is told there is
        // no more, and a failure would read as a broken provider rather than as an edge.
        if (found != AccessibleNode.NONE) {
            MemoryUtil.memPutAddress(out, context.elementFor(tree.node(found).id()));
        }
        return UiaIds.S_OK;
    }

    private static int runtimeId(long nodeId, long out, Context context) {
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
            return UiaIds.E_NO_INTERFACE;
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
        return UiaIds.S_OK;
    }

    /**
     * <p>Always none. An in-scene popup is inside this fragment and reached by navigation; a popup
     * the platform put in a window of its own is that window's fragment root and is found through
     * the desktop rather than through here.
     */
    private static int embeddedFragmentRoots(long out) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, 0);
        return UiaIds.S_OK;
    }

    /**
     * <p>{@code FOCUS}, posted only where the node publishes it now (semantics 5: SetFocus
     * [FOCUS]), and refused synchronously otherwise the way every pattern verb is
     * ({@link UiaPatternProviders#refusal}). A focusable widget's FOCUS is the walk's free verb; an
     * item publishes it where moving the cursor does not select (decision 11). Until 2026-09-15 it
     * was posted for any node, the root and a disabled button included.
     */
    private static int setFocus(long nodeId, Context context) {
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
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, context.rootElement());
        return UiaIds.S_OK;
    }

    private static int elementFromPoint(double x, double y, long out, Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        int found = UiaFragment.elementFromPoint(tree, x, y);
        // A point outside this window is a null and S_OK: the client asked whether anything of
        // ours is there, and "no" is an answer.
        if (found != AccessibleNode.NONE) {
            MemoryUtil.memPutAddress(out, context.elementFor(tree.node(found).id()));
        }
        return UiaIds.S_OK;
    }

    private static int focus(long out, Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
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
    private static int providerOptions(long out) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutInt(out, UiaIds.PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER);
        return UiaIds.S_OK;
    }

    /**
     * <p>A pattern this node does not vend is a null and {@code S_OK}, not a failure: the question
     * "can you do this" has "no" as an ordinary answer, and an {@code HRESULT} failure would be
     * read as a broken provider.
     */
    private static int patternProvider(long nodeId, int patternId, long out, Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, 0);
        AccessibleTree tree = context.tree();
        AccessibleNode node = tree.find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!UiaPatterns.supports(tree, node, patternId)) {
            return UiaIds.S_OK;
        }
        MemoryUtil.memPutAddress(out, context.patternProviderFor(nodeId, patternId));
        return UiaIds.S_OK;
    }

    /**
     * <p>The out parameter is a {@code VARIANT} the caller allocated, and it is cleared before
     * anything else on every path — including the failing ones, where a caller is entitled to look
     * at it anyway.
     */
    private static int propertyValue(long nodeId, int propertyId, long out, Context context) {
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
        // A target this window's tree does not hold answers no element (simpleElementFor says 0)
        // and is left out rather than handed over: since node identifiers became process-wide
        // (ADR 039 §1.3, 2026-09-14) the opener of a native popup carries CONTROLLER_FOR naming
        // the popup's root in the OTHER window's tree, and a SAFEARRAY(VT_UNKNOWN) with a NULL
        // entry is what UI Automation would otherwise have been given for it. Mapping such a
        // target to the other HWND's provider (AccessibleTree.holds says which tree) is CRIT-2
        // phase 3, the Windows lane's; until then the array is compacted and, when nothing of it
        // is held here, the property is VT_EMPTY like a relation the node does not declare.
        if (propertyId == UiaIds.LABELED_BY) {
            long[] labels = UiaProperties.relatedNodes(node,
                    limn.accessibility.Accessible.Relation.LABELLED_BY);
            if (labels.length > 0) {
                long element = context.simpleElementFor(labels[0]);
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
                    long element = context.simpleElementFor(targets[i]);
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
     * <p>The root answers the provider UI Automation made for the window, and every other node
     * answers null. That is what places this tree inside the desktop's own tree rather than beside
     * it: a client walking down from the window finds the root through the host, and finds every
     * other node through this provider's own navigation.
     */
    private static int hostProvider(long nodeId, long out, Context context) {
        if (out == 0) {
            return UiaIds.E_NO_INTERFACE;
        }
        AccessibleTree tree = context.tree();
        boolean isRoot = tree.nodeCount() > 0 && tree.root().id() == nodeId;
        MemoryUtil.memPutAddress(out, isRoot ? context.hostProvider() : 0);
        return UiaIds.S_OK;
    }
}
