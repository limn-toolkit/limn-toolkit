package limn.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The UI Automation bridge: what an application hands its window so that a screen reader on
 * Windows can read it.
 *
 * <p>An application installs it by handing it to the window and nothing else happens until a client
 * asks. {@link #openIfEnabled} answers {@link AccessibilityBridge#NONE} on a machine with no UI
 * Automation, which is every machine that is not Windows, so a cross-platform application can call
 * it unconditionally.
 *
 * <p><b>What lives here and which thread owns it</b>, from ADR&nbsp;039&nbsp;§3.4. The published
 * tree is a {@code volatile} field written by the user-interface thread and read by any RPC thread;
 * the registry is concurrent because an element is minted the first time a client navigates to a
 * node and up to three RPC threads may reach the same unvisited node at once; the listening gate is
 * no state at all, because UI Automation answers it. Nothing here holds a widget, and no widget can
 * reach anything here — a client may hold an element for minutes, and one that reached a widget
 * would pin a detached subtree for exactly that long.
 *
 * <p><b>What is not yet verified.</b> Everything above the platform calls is exercised by this
 * module's own tests on any machine. The calls into {@code uiautomationcore} and the window
 * procedure this attaches to one are not, and cannot be from here: they need a window, a client and
 * a screen reader. Phase 6 is finished when a client attaching to an idle window gets a tree and
 * NVDA reads the demo, and that run is the remaining work.
 */
public final class UiaBridge implements AccessibilityBridge {

    /** Written by the user-interface thread on publish, read by every RPC thread. */
    private volatile AccessibleTree published = AccessibleTree.EMPTY;

    /** The host, for the verbs a client performs. Written on attach, read on RPC threads. */
    private volatile Host host;

    /** Node identifier to element, and interface pointer back to it. */
    private final UiaElements elements = new UiaElements();

    /** Interface pointer to the object behind it, for every pointer an element publishes. */
    private final Map<Long, UiaObject> objects = new ConcurrentHashMap<>();

    /** The window this tree is drawn in. */
    private final long hwnd;

    /** The last root provider handed to UI Automation, so detaching can disconnect it. */
    private volatile long rootProviderForDisconnect;

    /** The procedure this bridge put in front of the window's own, or {@code null}. */
    private volatile UiaWindow attachedWindow;

    private UiaBridge(long hwnd) {
        this.hwnd = hwnd;
    }

    /**
     * Opens a bridge if this machine has UI Automation, and otherwise nothing.
     *
     * <p>The gate is read before anything is allocated: a window on a machine with no screen reader
     * is meant to cost one call and never a registry, an element or a thread.
     *
     * @param hwnd the window handle to place the tree under, from the backend's native window
     * @return a bridge, or {@link AccessibilityBridge#NONE} on a machine with no UI Automation
     */
    public static AccessibilityBridge openIfEnabled(long hwnd) {
        if (!Uia.isAvailable() || !UiaWindow.isAvailable() || hwnd == 0) {
            return AccessibilityBridge.NONE;
        }
        UiaBridge bridge = new UiaBridge(hwnd);
        bridge.attachedWindow = UiaWindow.attach(hwnd, bridge);
        // A window whose procedure could not be replaced is a window no client can ask, so there
        // is nothing for this bridge to answer and no reason to make the scene walk for it.
        return bridge.attachedWindow == null ? AccessibilityBridge.NONE : bridge;
    }

    /**
     * The same bridge without the platform gate, so that everything above the platform calls can
     * be exercised on a machine that has none.
     *
     * <p>Package-private and used by this module's own tests only. It is not a way to install a
     * bridge on a machine with no UI Automation: the platform calls it makes are all no-ops there,
     * so what it serves is elements nobody can reach.
     *
     * @param hwnd a window handle, which may be anything on a machine with no windows
     * @return a bridge
     */
    static UiaBridge withoutTheGate(long hwnd) {
        return new UiaBridge(hwnd);
    }

    /** @param nodeId a node from the published tree
     *  @return the object serving it, or {@code null} if the tree does not hold it. For tests. */
    UiaObject objectFor(long nodeId) {
        UiaElement element = elementOf(nodeId);
        return element == null ? null : objects.get(element.pointer());
    }

    /** @return how many nodes this bridge currently holds an element for */
    int elementCount() {
        return elements.size();
    }

    /** @return the snapshot as of now. For tests. */
    AccessibleTree tree() {
        return published;
    }

    /**
     * <p>Asked once per frame on the user-interface thread. This is the whole of the cost a window
     * pays when nobody is reading it.
     */
    @Override
    public boolean isListening() {
        return Uia.clientsAreListening();
    }

    /**
     * <p>Yes: a client that attached while this window was idle asked for a tree and got the empty
     * one, and nothing else will prompt a walk until something moves.
     */
    @Override
    public boolean needsPrimingPublish() {
        return true;
    }

    /**
     * <p>The snapshot is swapped whole. A client reading on an RPC thread sees the previous tree or
     * the next one and never half of either, which is the property the whole record is built on.
     *
     * @param tree      the new snapshot
     * @param reentrant whether this publish happened inside a client's own call
     */
    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        published = tree;
    }

    /**
     * <p>Raised straight through, on whichever thread the scene drained on. Every one of these is a
     * call into UI Automation that returns without waiting for a client, which is why there is no
     * queue between here and it on this platform.
     *
     * @param event what moved
     */
    @Override
    public void emit(AccessibleEvent event) {
        int eventId = switch (event.type()) {
            case FOCUS_CHANGED -> UiaIds.AUTOMATION_FOCUS_CHANGED;
            case INVOKED -> UiaIds.INVOKE_INVOKED;
            case SELECTION_CHANGED -> UiaIds.SELECTION_ITEM_ELEMENT_SELECTED;
            case STRUCTURE_CHANGED -> UiaIds.STRUCTURE_CHANGED;
            case ANNOUNCEMENT -> UiaIds.NOTIFICATION;
            case WINDOW_OPENED -> UiaIds.WINDOW_OPENED;
            case WINDOW_CLOSED -> UiaIds.WINDOW_CLOSED;
            case TEXT_CHANGED -> UiaIds.TEXT_CHANGED;
            case TEXT_SELECTION_CHANGED -> UiaIds.TEXT_SELECTION_CHANGED;
            case INVALIDATED -> UiaIds.LAYOUT_INVALIDATED;
            // The rest are property changes, which UI Automation takes through a call of its own.
            default -> 0;
        };
        UiaElement element = elements.peek(event.nodeId());
        if (element == null) {
            // Nothing has ever asked for this node, so no client is holding an element to be told
            // about. It will read whatever is current the first time it does ask.
            return;
        }
        if (eventId != 0) {
            Uia.raiseAutomationEvent(element.pointer(), eventId);
            return;
        }
        raisePropertyChange(element, event);
    }

    /**
     * A property that moved, which UI Automation is told about with both values.
     *
     * <p><b>Which property depends on the node and not only on the event</b>: a value that moved is
     * a number on a slider and a string in a text field, and the two are different properties to a
     * client. A state that moved is whichever property carries that state — a check mark is the
     * toggle pattern's, an enabled flag is the element's own — so a state this bridge has no
     * property for is not raised rather than raised as something else.
     *
     * <p>Both values are written into {@code VARIANT}s allocated for the call and freed after it.
     * A string among them is a {@code BSTR} the callee reads and does not keep, which is the one
     * case where this bridge frees a string it allocated.
     */
    private void raisePropertyChange(UiaElement element, AccessibleEvent event) {
        AccessibleTree tree = published;
        int index = tree.indexOf(event.nodeId());
        AccessibleNode node = index < 0 ? null : tree.node(index);
        int propertyId = switch (event.type()) {
            case NAME_CHANGED -> UiaIds.NAME;
            case DESCRIPTION_CHANGED -> UiaIds.HELP_TEXT;
            case VALUE_CHANGED -> node != null && node.value() != null
                    ? UiaIds.RANGE_VALUE_VALUE : UiaIds.VALUE_VALUE;
            case STATE_CHANGED -> switch (event.state()) {
                case CHECKED, MIXED -> UiaIds.TOGGLE_STATE;
                case ENABLED -> UiaIds.IS_ENABLED;
                case SELECTED -> UiaIds.SELECTION_ITEM_IS_SELECTED;
                case EXPANDED -> UiaIds.EXPAND_COLLAPSE_EXPAND_COLLAPSE_STATE;
                case READ_ONLY -> UiaIds.VALUE_IS_READ_ONLY;
                default -> 0;
            };
            // A rectangle that moved is not raised: UI Automation watches an HWND's own bounds and
            // a client re-reads a fragment's when it needs them, so raising it per node would be a
            // storm of events during every drag for something nobody asked to be told.
            default -> 0;
        };
        if (propertyId == 0) {
            return;
        }
        long before = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        long after = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        try {
            java.nio.ByteBuffer oldOne = MemoryUtil.memByteBuffer(before, UiaVariant.SIZE);
            java.nio.ByteBuffer newOne = MemoryUtil.memByteBuffer(after, UiaVariant.SIZE);
            write(oldOne, event.oldValue());
            write(newOne, event.newValue());
            Uia.raisePropertyChangedEvent(element.pointer(), propertyId, before, after);
            freeIfString(oldOne);
            freeIfString(newOne);
        } finally {
            MemoryUtil.nmemFree(before);
            MemoryUtil.nmemFree(after);
        }
    }

    /** Writes one of the model's values into a variant, or leaves it empty for anything else. */
    private void write(java.nio.ByteBuffer variant, Object value) {
        if (value instanceof Boolean flag) {
            UiaVariant.bool(variant, 0, flag);
        } else if (value instanceof Number number) {
            UiaVariant.r8(variant, 0, number.doubleValue());
        } else if (value instanceof String text) {
            UiaVariant.bstr(variant, 0, UiaStrings.system().allocate(text));
        } else {
            UiaVariant.empty(variant, 0);
        }
    }

    private void freeIfString(java.nio.ByteBuffer variant) {
        if (UiaVariant.tagOf(variant, 0) == UiaVariant.VT_BSTR) {
            UiaStrings.free(variant.getLong(UiaVariant.PAYLOAD));
        }
    }

    /**
     * <p>Over a live host, the registry is emptied first: the elements it holds stand for a tree
     * that is about to be replaced, and an element that outlived its host would answer about a node
     * from someone else's window.
     *
     * @param newHost what to perform verbs through
     */
    @Override
    public void attach(Host newHost) {
        emptyRegistry();
        host = newHost;
    }

    @Override
    public void detach() {
        host = null;
        published = AccessibleTree.EMPTY;
        emptyRegistry();
        // Our own root provider, and not the window's: the host provider is UI Automation's own
        // and every reference to it was handed over already.
        Uia.disconnectProvider(rootProviderForDisconnect);
        rootProviderForDisconnect = 0;
        UiaWindow window = attachedWindow;
        if (window != null) {
            window.detach();
            attachedWindow = null;
        }
    }

    /**
     * The whole-registry empty of §3.4.
     *
     * <p><b>By identity, because one object answers to several pointers.</b> An element serving the
     * two interfaces every node has, plus one per pattern it vends, appears in the pointer map
     * three or four times — and freeing it once per pointer frees closures that are already gone.
     * That is a crash in this process rather than a leak, which is the better failure to have found
     * here.
     */
    private void emptyRegistry() {
        java.util.Set<UiaObject> distinct =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        distinct.addAll(objects.values());
        objects.clear();
        elements.empty();
        distinct.forEach(UiaObject::free);
    }

    /**
     * What a window procedure answers {@code WM_GETOBJECT} with.
     *
     * <p>The one place UI Automation is told this window has a provider at all. It is answered
     * synchronously, on the user-interface thread, inside the message — so the tree it hands over
     * is the one already published, and nothing walks here.
     *
     * @param wparam the message's own
     * @param lparam the same
     * @return the {@code LRESULT} for the window procedure to return
     */
    public long answerGetObject(long wparam, long lparam) {
        long root = rootElement();
        return root == 0 ? 0 : Uia.returnRawElementProvider(hwnd, wparam, lparam, root);
    }

    /** @return the root node's element, minting it if this is the first ask, or {@code 0} */
    private long rootElement() {
        AccessibleTree tree = published;
        // The simple interface, because that is what UiaReturnRawElementProvider is declared to
        // take -- and referenced, because UI Automation keeps it.
        if (tree.nodeCount() == 0) {
            return 0;
        }
        long provider = handOver(tree.root().id(), UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        rootProviderForDisconnect = provider;
        return provider;
    }

    /**
     * Everything the slots read, in one place, so that no element holds anything but its own
     * identifier.
     */
    private final UiaProvider.Context context = new UiaProvider.Context() {

        @Override
        public AccessibleTree tree() {
            return published;
        }

        @Override
        public long patternProviderFor(long nodeId, int patternId) {
            return handOver(nodeId, UiaPatternProviders.interfaceFor(patternId));
        }

        /**
         * <p>Asked for afresh every time, and never cached.
         *
         * <p>{@code UiaHostProviderFromHwnd} answers with a reference the caller owns, and the
         * caller here is UI Automation, which releases it when it is done. Handing the same
         * pointer over twice hands over a reference we no longer have — the second client to ask
         * gets an object that has already been freed. That is not a subtle failure and it is not a
         * slow one: the live run crashed on the second {@code WM_GETOBJECT}, in native code, with
         * no Java frame to name.
         */
        @Override
        public long hostProvider() {
            return Uia.hostProviderFromHwnd(hwnd);
        }

        @Override
        public UiaStrings.Allocator strings() {
            return UiaStrings.system();
        }

        @Override
        public long int32Array(int[] values) {
            return UiaStrings.int32Array(values);
        }

        /**
         * <p><b>The fragment interface and not the object's primary one.</b> Every pointer a COM
         * method returns has a declared type, and a client walking slot 5 of what it believes is
         * {@code IRawElementProviderFragment} finds slot 5 of whatever vtable it was actually
         * handed. Answering navigation with the simple interface is the same misdispatch the slot
         * order was read to prevent, arriving through the other door -- and it is what the live run
         * caught: every call answered S_OK and UI Automation refused the provider anyway.
         */
        @Override
        public long elementFor(long nodeId) {
            return handOver(nodeId, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        }

        /** <p>And the fragment <em>root</em> interface here, for the same reason. */
        @Override
        public long rootElement() {
            AccessibleTree tree = published;
            return tree.nodeCount() == 0 ? 0
                    : handOver(tree.root().id(), UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT);
        }

        @Override
        public boolean requestFocus(long nodeId) {
            return perform(nodeId, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        }

        @Override
        public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            Host current = host;
            return current != null && current.perform(nodeId, action, arg);
        }
    };

    /**
     * One interface pointer of a node's element, counted for the caller.
     *
     * <p>Every pointer this bridge returns from a COM method is a reference the caller owns and
     * will release, so it is counted here. Handing one over uncounted is an object freed while a
     * client still holds it.
     *
     * @param nodeId the node
     * @param iface  which of its interfaces, or {@code null} for none
     * @return the pointer, already referenced, or {@code 0}
     */
    private long handOver(long nodeId, UiaInterfaces.Vtable iface) {
        if (iface == null) {
            return 0;
        }
        UiaElement element = elementOf(nodeId);
        if (element == null) {
            return 0;
        }
        UiaObject object = objects.get(element.pointer());
        long pointer = object == null ? 0 : object.pointerFor(iface);
        if (pointer != 0) {
            object.addRef();
        }
        return pointer;
    }

    /**
     * The element for a node, made once however many RPC threads ask at once.
     *
     * <p>An element serves the two interfaces every node has, the fragment root's two where it is
     * the root, and one interface per pattern the node vends — all on one object, so a client that
     * queried for the toggle interface and one that queried for the fragment interface are holding
     * the same thing and can tell.
     *
     * @param nodeId a node from the published tree
     * @return its element, or {@code null} when the tree no longer holds that node
     */
    private UiaElement elementOf(long nodeId) {
        AccessibleTree tree = published;
        int index = tree.indexOf(nodeId);
        if (index < 0) {
            return null;
        }
        boolean isRoot = tree.root().id() == nodeId;
        AccessibleNode node = tree.node(index);
        return elements.forNode(nodeId, id -> {
            List<UiaObject.Served> served = new ArrayList<>();
            served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                    UiaProvider.simpleSlots(id, context)));
            served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT,
                    UiaProvider.fragmentSlots(id, context)));
            if (isRoot) {
                served.add(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                        UiaProvider.fragmentRootSlots(context)));
            }
            for (int patternId : PATTERNS) {
                if (!UiaPatterns.supports(tree, node, patternId)) {
                    continue;
                }
                UiaInterfaces.Vtable iface = UiaPatternProviders.interfaceFor(patternId);
                Map<String, org.lwjgl.system.CallbackI> slots =
                        UiaPatternProviders.slotsFor(patternId, id, context);
                if (iface != null && slots != null) {
                    served.add(new UiaObject.Served(iface, slots));
                }
            }
            UiaObject object = UiaObject.create(served, () -> { });
            object.pointers().forEach(pointer -> objects.put(pointer, object));
            return new Element(id, object);
        });
    }

    /** The patterns a node may vend, asked in a fixed order so an element is built the same way. */
    private static final int[] PATTERNS = {
            UiaIds.INVOKE_PATTERN, UiaIds.TOGGLE_PATTERN, UiaIds.VALUE_PATTERN,
            UiaIds.RANGE_VALUE_PATTERN, UiaIds.EXPAND_COLLAPSE_PATTERN,
            UiaIds.SELECTION_ITEM_PATTERN, UiaIds.SCROLL_ITEM_PATTERN,
    };

    /** One node's COM object, as the registry sees it. */
    private record Element(long nodeId, UiaObject object) implements UiaElement {

        @Override
        public long pointer() {
            return object.pointer();
        }

        @Override
        public void release() {
            // Removal is not release: the object survives until every client reference drops, and
            // the whole-registry empty is what frees it. See UiaElements.
        }
    }
}
