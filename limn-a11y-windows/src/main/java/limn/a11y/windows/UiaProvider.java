package limn.a11y.windows;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

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
    }

    /**
     * The four slots after {@code IUnknown}'s, in the order the guest reported.
     *
     * @param nodeId  which node this element stands for
     * @param context what the slots read
     * @return the slots, ready to be handed to {@link UiaObject}
     */
    static List<CallbackI> simpleSlots(long nodeId, Context context) {
        return List.of(
                (UiaCom.PP) (self, out) -> providerOptions(out),
                (UiaCom.PIP) (self, patternId, out) -> patternProvider(nodeId, patternId, out,
                        context),
                (UiaCom.PIP) (self, propertyId, out) -> propertyValue(nodeId, propertyId, out,
                        context),
                (UiaCom.PP) (self, out) -> hostProvider(nodeId, out, context));
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
