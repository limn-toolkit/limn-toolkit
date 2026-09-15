package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IRawElementProviderFragment} and its root, answered through their own vtables.
 *
 * <p>The decisions were settled in {@link UiaFragment} against a snapshot; what these cases add is
 * that a client reaching them through a function pointer gets the same answers, and that the
 * places where "nothing" is the answer say so with {@code S_OK} rather than with a failure a client
 * would read as a broken provider.
 */
class UiaFragmentProviderTest {

    private final AtomicReference<AccessibleTree> published =
            new AtomicReference<>(AccessibleTree.EMPTY);
    private final Map<Long, Long> elementPointers = new HashMap<>();
    private final List<Long> focusRequests = new ArrayList<>();
    private final List<long[]> arrays = new ArrayList<>();
    private final long root = 0x1200_0000L;

    private final UiaProvider.Context context = new UiaProvider.Context() {
        @Override
        public AccessibleTree tree() {
            return published.get();
        }

        @Override
        public long patternProviderFor(long nodeId, int patternId) {
            return 0;
        }

        @Override
        public long hostProvider() {
            return 0;
        }

        @Override
        public UiaStrings.Allocator strings() {
            return text -> 0;
        }

        @Override
        public long int32Array(int[] values) {
            long memory = MemoryUtil.nmemAllocChecked(4L * values.length + 4);
            MemoryUtil.memPutInt(memory, values.length);
            for (int i = 0; i < values.length; i++) {
                MemoryUtil.memPutInt(memory + 4 + (long) i * 4, values[i]);
            }
            arrays.add(new long[] {memory});
            return memory;
        }

        @Override
        public long unknownArray(long[] pointers) {
            return 0;
        }

        @Override
        public long elementFor(long nodeId) {
            return elementPointers.computeIfAbsent(nodeId, id -> 0xE0000000L + id);
        }

        @Override
        public long simpleElementFor(long nodeId) {
            return elementFor(nodeId);
        }

        @Override
        public long rootElement() {
            return root;
        }

        @Override
        public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            return published.get().indexOf(nodeId) >= 0;
        }

        @Override
        public boolean requestFocus(long nodeId) {
            focusRequests.add(nodeId);
            return published.get().indexOf(nodeId) >= 0;
        }
    };

    private final List<UiaObject> made = new ArrayList<>();

    @AfterEach
    void freeWhatWasMade() {
        made.forEach(UiaObject::free);
        arrays.forEach(a -> MemoryUtil.nmemFree(a[0]));
    }

    /**
     * A window holding a group, a button inside it publishing FOCUS (the walk's free verb on a
     * focusable widget), and a second button beside the group publishing none.
     */
    private void publishAScene(long focusedId) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.GROUP);
        a.inherited(true, true, true, false, false);
        a.begin(1002, 1, Locale.ENGLISH, 10, 20, 100, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.FOCUS);
        a.inherited(true, true, true, true, focusedId == 1002);
        a.end();
        a.end();
        a.begin(1003, 0, Locale.ENGLISH, 200, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Cancel"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, focusedId == 1003);
        a.end();
        a.end();
        published.set(a.publish(focusedId, 0, 0, 1f, true));
    }

    private static int slotOf(UiaInterfaces.Vtable iface, String member) {
        int at = iface.slots().indexOf(member);
        assertTrue(at >= 0, member + " is not a member of " + iface.name());
        return 3 + at;
    }

    private long fragmentFor(long nodeId) {
        UiaObject object = UiaObject.create(List.of(new UiaObject.Served(
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT,
                UiaProvider.fragmentSlots(nodeId, context))), () -> { });
        made.add(object);
        return object.pointer();
    }

    private long fragmentRoot() {
        Map<String, CallbackI> slots =
                new HashMap<>(UiaProvider.fragmentRootSlots(context));
        UiaObject object = UiaObject.create(List.of(new UiaObject.Served(
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT, slots)), () -> { });
        made.add(object);
        return object.pointer();
    }

    @Test
    void navigatingFindsTheNeighbourTheSnapshotStores() {
        publishAScene(0);
        long group = fragmentFor(1001);
        long out = MemoryUtil.nmemAllocChecked(8);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "Navigate");
        try {
            assertEquals(UiaIds.S_OK, JNI.invokePPI(group, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD,
                    out, UiaCom.slotOf(group, slot)));
            assertEquals(context.elementFor(1002), MemoryUtil.memGetAddress(out));

            JNI.invokePPI(group, UiaIds.NAVIGATE_DIRECTION_PARENT, out,
                    UiaCom.slotOf(group, slot));
            assertEquals(context.elementFor(1000), MemoryUtil.memGetAddress(out));

            JNI.invokePPI(group, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING, out,
                    UiaCom.slotOf(group, slot));
            assertEquals(context.elementFor(1003), MemoryUtil.memGetAddress(out));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * A client walks until it is told there is no more, so the end of a list is a null with
     * {@code S_OK}. A failure there reads as a broken provider and stops the walk early.
     */
    @Test
    void theEndOfTheTreeIsANullAndNotAFailure() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long out = MemoryUtil.nmemAllocChecked(8);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "Navigate");
        try {
            MemoryUtil.memPutAddress(out, 0x0BADF00DL);

            assertEquals(UiaIds.S_OK, JNI.invokePPI(button, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD,
                    out, UiaCom.slotOf(button, slot)));
            assertEquals(0L, MemoryUtil.memGetAddress(out), "a leaf has no first child");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void theRuntimeIdIsTheMarkerAndTheIdentifierInTwoHalves() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long out = MemoryUtil.nmemAllocChecked(8);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "GetRuntimeId");
        try {
            assertEquals(UiaIds.S_OK, JNI.invokePPI(button, out, UiaCom.slotOf(button, slot)));

            long array = MemoryUtil.memGetAddress(out);
            assertNotEquals(0L, array);
            assertEquals(3, MemoryUtil.memGetInt(array), "three integers");
            assertEquals(UiaIds.APPEND_RUNTIME_ID, MemoryUtil.memGetInt(array + 4));
            assertEquals(0, MemoryUtil.memGetInt(array + 8), "the identifier's high half");
            assertEquals(1002, MemoryUtil.memGetInt(array + 12));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void theRectangleIsWrittenAsFourDoublesInTheCallersOwnMemory() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long out = MemoryUtil.nmemAllocChecked(32);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "get_BoundingRectangle");
        try {
            assertEquals(UiaIds.S_OK, JNI.invokePPI(button, out, UiaCom.slotOf(button, slot)));

            assertEquals(10.0, MemoryUtil.memGetDouble(out));
            assertEquals(20.0, MemoryUtil.memGetDouble(out + 8));
            assertEquals(100.0, MemoryUtil.memGetDouble(out + 16));
            assertEquals(40.0, MemoryUtil.memGetDouble(out + 24));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * A client asks for this while drawing a highlight, so a node that has gone answers an empty
     * rectangle — which draws nothing — rather than a failure it would have to handle mid-paint.
     */
    @Test
    void aNodeThatHasGoneAnswersAnEmptyRectangle() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long out = MemoryUtil.nmemAllocChecked(32);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "get_BoundingRectangle");
        try {
            published.set(AccessibleTree.EMPTY);

            assertEquals(UiaIds.S_OK, JNI.invokePPI(button, out, UiaCom.slotOf(button, slot)));

            for (int i = 0; i < 4; i++) {
                assertEquals(0.0, MemoryUtil.memGetDouble(out + (long) i * 8), "field " + i);
            }
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * §2.1: always none. An in-scene popup is inside this fragment and reached by navigation, and
     * one the platform put in a window of its own is that window's fragment root.
     */
    @Test
    void thereAreNoEmbeddedFragmentRoots() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long out = MemoryUtil.nmemAllocChecked(8);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "GetEmbeddedFragmentRoots");
        try {
            MemoryUtil.memPutAddress(out, 0x0BADF00DL);

            assertEquals(UiaIds.S_OK, JNI.invokePPI(button, out, UiaCom.slotOf(button, slot)));
            assertEquals(0L, MemoryUtil.memGetAddress(out));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void everyFragmentPointsAtTheOneRoot() {
        publishAScene(0);
        long out = MemoryUtil.nmemAllocChecked(8);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "get_FragmentRoot");
        try {
            for (long nodeId : new long[] {1000, 1001, 1002, 1003}) {
                long element = fragmentFor(nodeId);
                assertEquals(UiaIds.S_OK,
                        JNI.invokePPI(element, out, UiaCom.slotOf(element, slot)));
                assertEquals(root, MemoryUtil.memGetAddress(out), "node " + nodeId);
            }
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * Semantics 5 (SetFocus [FOCUS]): the request reaches the toolkit where the node publishes
     * FOCUS, and is refused synchronously where it does not. Until 2026-09-15 it was posted for any
     * node (this case read "setFocusReachesTheToolkitAndSaysSoWhenTheNodeHasGone").
     */
    @Test
    void setFocusReachesTheToolkitOnlyWhereTheNodePublishesFocusAndSaysSoWhenTheNodeHasGone() {
        publishAScene(0);
        long button = fragmentFor(1002);
        long other = fragmentFor(1003);
        int slot = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "SetFocus");

        assertEquals(UiaIds.S_OK, JNI.invokePI(button, UiaCom.slotOf(button, slot)));
        assertEquals(UiaIds.E_INVALID_OPERATION, JNI.invokePI(other, UiaCom.slotOf(other, slot)),
                "a node that publishes no FOCUS is refused, and nothing reaches the toolkit");
        assertEquals(List.of(1002L), focusRequests);

        published.set(AccessibleTree.EMPTY);
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                JNI.invokePI(button, UiaCom.slotOf(button, slot)));
    }

    @Test
    void theRootAnswersWhatIsUnderAPointAndWhatHasTheKeyboard() {
        publishAScene(1003);
        long rootElement = fragmentRoot();
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            int fromPoint = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                    "ElementProviderFromPoint");
            assertEquals(UiaIds.S_OK, JNI.callPPI(rootElement, 50.0, 40.0, out,
                    UiaCom.slotOf(rootElement, fromPoint)));
            assertEquals(context.elementFor(1002), MemoryUtil.memGetAddress(out),
                    "the button inside the group, which is the deepest box holding that point");

            int getFocus = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT, "GetFocus");
            assertEquals(UiaIds.S_OK,
                    JNI.invokePPI(rootElement, out, UiaCom.slotOf(rootElement, getFocus)));
            assertEquals(context.elementFor(1003), MemoryUtil.memGetAddress(out));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aPointOutsideTheWindowAndAWindowWithNoFocusBothAnswerNothing() {
        publishAScene(0);
        long rootElement = fragmentRoot();
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            int fromPoint = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                    "ElementProviderFromPoint");
            MemoryUtil.memPutAddress(out, 0x0BADF00DL);
            assertEquals(UiaIds.S_OK, JNI.callPPI(rootElement, 5000.0, 5000.0, out,
                    UiaCom.slotOf(rootElement, fromPoint)));
            assertEquals(0L, MemoryUtil.memGetAddress(out),
                    "the client asked whether anything of ours is there, and no is an answer");

            int getFocus = slotOf(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT, "GetFocus");
            MemoryUtil.memPutAddress(out, 0x0BADF00DL);
            assertEquals(UiaIds.S_OK,
                    JNI.invokePPI(rootElement, out, UiaCom.slotOf(rootElement, getFocus)));
            assertEquals(0L, MemoryUtil.memGetAddress(out));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }
}
