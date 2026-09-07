package limn.backend.lwjgl.a11y.windows;

import java.util.List;

/**
 * The COM interfaces this bridge serves: each one's identifier, and the order of its vtable.
 *
 * <p><b>Every identifier and every slot here was read off a guest and none was written from
 * memory.</b> The script is {@code scripts/a11y/windows/dump-uia-interfaces.ps1}, and what it asks
 * is the runtime's own question: {@code Marshal.GetComSlotForMethodInfo} maps a member to its
 * position in the vtable, {@code IUnknown}'s three slots included. The readings came from the
 * interop assemblies on Windows 11 ARM64, build 10.0.26200.
 *
 * <p><b>The order is the part that fails silently.</b> A vtable is an array of function pointers,
 * and getting its order wrong does not fail to compile, does not throw and does not log — the
 * client calls what it believes is {@code get_ProviderOptions} and reaches {@code Navigate}, with
 * the arguments of the one it meant, on a thread belonging to a screen reader. There is no
 * plausible way to find that from the Java side, which is why it is read rather than transcribed.
 *
 * <p><b>The identifier is stored as the canonical string and turned into bytes here.</b> A COM
 * {@code GUID} is not sixteen bytes in reading order: its first three fields are integers, so they
 * are laid out little-endian on every architecture this ships to, and only the last eight bytes are
 * in the order they are written. Keeping the string and converting once means the table stays
 * checkable against the dump by eye, and the conversion is asserted rather than each of fourteen
 * byte arrays being transcribed by hand.
 */
final class UiaInterfaces {

    private UiaInterfaces() {
    }

    /**
     * One interface: what a client asks for by identifier, and what it finds at each slot.
     *
     * @param name  the interface's name, for a message a person has to read
     * @param iid   its identifier, canonically spelled
     * @param slots what occupies slots 3 and up, in order; {@code IUnknown}'s first three are
     *              every interface's and are not repeated here
     */
    record Vtable(String name, String iid, List<String> slots) {

        /** @return how many function pointers the vtable holds, IUnknown's three included */
        int slotCount() {
            return 3 + slots.size();
        }

        /** @return the identifier in the byte order a {@code QueryInterface} argument carries */
        byte[] iidBytes() {
            return UiaInterfaces.iidBytes(iid);
        }
    }

    static final Vtable RAW_ELEMENT_PROVIDER_SIMPLE = new Vtable(
            "IRawElementProviderSimple", "d6dd68d1-86fd-4332-8666-9abedea2d24c",
            List.of("get_ProviderOptions", "GetPatternProvider", "GetPropertyValue",
                    "get_HostRawElementProvider"));

    static final Vtable RAW_ELEMENT_PROVIDER_FRAGMENT = new Vtable(
            "IRawElementProviderFragment", "f7063da8-8359-439c-9297-bbc5299a7d87",
            List.of("Navigate", "GetRuntimeId", "get_BoundingRectangle",
                    "GetEmbeddedFragmentRoots", "SetFocus", "get_FragmentRoot"));

    static final Vtable RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT = new Vtable(
            "IRawElementProviderFragmentRoot", "620ce2a5-ab8f-40a9-86cb-de3c75599b58",
            List.of("ElementProviderFromPoint", "GetFocus"));

    static final Vtable INVOKE_PROVIDER = new Vtable(
            "IInvokeProvider", "54fcb24b-e18e-47a2-b4d3-eccbe77599a2",
            List.of("Invoke"));

    static final Vtable TOGGLE_PROVIDER = new Vtable(
            "IToggleProvider", "56d00bd0-c4f4-433c-a836-1a52a57e0892",
            List.of("Toggle", "get_ToggleState"));

    static final Vtable RANGE_VALUE_PROVIDER = new Vtable(
            "IRangeValueProvider", "36dc7aef-33e6-4691-afe1-2be7274b3d33",
            List.of("SetValue", "get_Value", "get_IsReadOnly", "get_Maximum", "get_Minimum",
                    "get_LargeChange", "get_SmallChange"));

    static final Vtable VALUE_PROVIDER = new Vtable(
            "IValueProvider", "c7935180-6fb3-4201-b174-7df73adbf64a",
            List.of("SetValue", "get_Value", "get_IsReadOnly"));

    static final Vtable SELECTION_PROVIDER = new Vtable(
            "ISelectionProvider", "fb8b03af-3bdf-48d4-bd36-1a65793be168",
            List.of("GetSelection", "get_CanSelectMultiple", "get_IsSelectionRequired"));

    static final Vtable SELECTION_ITEM_PROVIDER = new Vtable(
            "ISelectionItemProvider", "2acad808-b2d4-452d-a407-91ff1ad167b2",
            List.of("Select", "AddToSelection", "RemoveFromSelection", "get_IsSelected",
                    "get_SelectionContainer"));

    static final Vtable EXPAND_COLLAPSE_PROVIDER = new Vtable(
            "IExpandCollapseProvider", "d847d3a5-cab0-4a98-8c32-ecb45c59ad24",
            List.of("Expand", "Collapse", "get_ExpandCollapseState"));

    static final Vtable SCROLL_PROVIDER = new Vtable(
            "IScrollProvider", "b38b8077-1fc3-42a5-8cae-d40c2215055a",
            List.of("Scroll", "SetScrollPercent", "get_HorizontalScrollPercent",
                    "get_VerticalScrollPercent", "get_HorizontalViewSize", "get_VerticalViewSize",
                    "get_HorizontallyScrollable", "get_VerticallyScrollable"));

    static final Vtable SCROLL_ITEM_PROVIDER = new Vtable(
            "IScrollItemProvider", "2360c714-4bf1-4b26-ba65-9b21316127eb",
            List.of("ScrollIntoView"));

    static final Vtable WINDOW_PROVIDER = new Vtable(
            "IWindowProvider", "987df77b-db06-4d77-8f8a-86a9c3bb90b9",
            List.of("SetVisualState", "Close", "WaitForInputIdle", "get_Maximizable",
                    "get_Minimizable", "get_IsModal", "get_VisualState", "get_InteractionState",
                    "get_IsTopmost"));

    static final Vtable TRANSFORM_PROVIDER = new Vtable(
            "ITransformProvider", "6829ddc4-4f91-4ffa-b86f-bd3e2987cb4c",
            List.of("Move", "Resize", "Rotate", "get_CanMove", "get_CanResize", "get_CanRotate"));

    /** {@code IUnknown} itself, which every one of the above begins with. */
    /**
     * The one interface that is about the client and not about a node: UI Automation calls it on
     * the <b>root</b> when a client subscribes to events that cover this window, and again when it
     * unsubscribes. Read off the guest on 2026-09-07, for ADR&nbsp;039 &sect;13.5's second half —
     * whether a gate can be per window rather than per process.
     */
    static final Vtable ADVISE_EVENTS = new Vtable(
            "IRawElementProviderAdviseEvents", "a407b27b-0f6d-4427-9292-473c7bf93258",
            List.of("AdviseEventAdded", "AdviseEventRemoved"));

    static final Vtable UNKNOWN = new Vtable(
            "IUnknown", "00000000-0000-0000-c000-000000000046", List.of());

    /** Every interface this bridge serves, for a test that has to check all of them. */
    static final List<Vtable> ALL = List.of(
            RAW_ELEMENT_PROVIDER_SIMPLE, RAW_ELEMENT_PROVIDER_FRAGMENT,
            RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT, INVOKE_PROVIDER, TOGGLE_PROVIDER,
            RANGE_VALUE_PROVIDER, VALUE_PROVIDER, SELECTION_PROVIDER, SELECTION_ITEM_PROVIDER,
            EXPAND_COLLAPSE_PROVIDER, SCROLL_PROVIDER, SCROLL_ITEM_PROVIDER, WINDOW_PROVIDER,
            TRANSFORM_PROVIDER, ADVISE_EVENTS);

    /**
     * Turns a canonically spelled identifier into the sixteen bytes a {@code QueryInterface}
     * argument carries.
     *
     * <p>The first three fields of a {@code GUID} are a 32-bit and two 16-bit integers, so they are
     * stored in the machine's own byte order — little-endian on every architecture this ships to —
     * while the fourth field is an array of bytes and is stored as written. Reading the canonical
     * form left to right into sixteen bytes therefore produces an identifier that matches nothing,
     * and the failure is a client being told this object does not implement the interface it is
     * looking at.
     *
     * @param canonical the identifier as {@code 8-4-4-4-12} hexadecimal
     * @return its in-memory bytes
     */
    static byte[] iidBytes(String canonical) {
        String hex = canonical.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("not a GUID: " + canonical);
        }
        byte[] read = new byte[16];
        for (int i = 0; i < 16; i++) {
            read[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] memory = new byte[16];
        // Data1, little-endian.
        memory[0] = read[3];
        memory[1] = read[2];
        memory[2] = read[1];
        memory[3] = read[0];
        // Data2 and Data3, each little-endian.
        memory[4] = read[5];
        memory[5] = read[4];
        memory[6] = read[7];
        memory[7] = read[6];
        // Data4, as written.
        System.arraycopy(read, 8, memory, 8, 8);
        return memory;
    }
}
