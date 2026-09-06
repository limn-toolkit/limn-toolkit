package limn.a11y.windows;

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
         * @param nodeId the node a client asked to navigate to, or whose element it asked for
         * @return that node's fragment pointer, already referenced for the caller, or {@code 0}
         *         when the node is not in the current tree
         */
        long elementFor(long nodeId);

        /** @return the fragment root's own pointer, referenced for the caller */
        long rootElement();

        /**
         * Asks the toolkit to move the keyboard here, on the thread that owns it.
         *
         * @param nodeId the node a client asked to focus
         * @return whether the request was accepted, which is not whether the widget took it
         */
        boolean requestFocus(long nodeId);
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
        if (context.tree().indexOf(nodeId) < 0) {
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
        int index = tree.indexOf(nodeId);
        if (index >= 0) {
            box = UiaFragment.boundingRectangle(tree, tree.node(index));
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

    private static int setFocus(long nodeId, Context context) {
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
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!UiaPatterns.supports(tree, tree.node(index), patternId)) {
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
        AccessibleTree tree = context.tree();
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        AccessibleNode node = tree.node(index);
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
