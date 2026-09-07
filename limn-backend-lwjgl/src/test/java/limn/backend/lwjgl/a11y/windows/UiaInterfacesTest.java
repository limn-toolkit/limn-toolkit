package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the vtable table says what the guest said, and that a canonical identifier becomes the bytes
 * the platform expects.
 *
 * <p>The second half is the one worth the trouble. A COM {@code GUID} is not sixteen bytes in
 * reading order — its first three fields are integers and are stored in the machine's byte order,
 * and only the last eight are as written — so a conversion that reads the canonical form left to
 * right produces an identifier matching nothing, and the whole failure a client sees is being told
 * this object does not implement the interface it is looking at. The expected bytes below are not
 * arithmetic anyone did here: they are what {@code System.Guid.ToByteArray} answered on the guest,
 * which is the same layout {@code QueryInterface} is handed.
 */
class UiaInterfacesTest {

    /**
     * Each interface's identifier as the guest laid it out in memory, from
     * {@code scripts/a11y/windows/dump-uia-interfaces.ps1}'s companion reading.
     */
    private static final Map<String, String> AS_THE_GUEST_LAID_THEM_OUT = Map.ofEntries(
            Map.entry("a407b27b-0f6d-4427-9292-473c7bf93258",
                    "7bb207a46d0f27449292473c7bf93258"),
            Map.entry("d6dd68d1-86fd-4332-8666-9abedea2d24c",
                    "d168ddd6fd86324386669abedea2d24c"),
            Map.entry("f7063da8-8359-439c-9297-bbc5299a7d87",
                    "a83d06f759839c439297bbc5299a7d87"),
            Map.entry("620ce2a5-ab8f-40a9-86cb-de3c75599b58",
                    "a5e20c628faba94086cbde3c75599b58"),
            Map.entry("54fcb24b-e18e-47a2-b4d3-eccbe77599a2",
                    "4bb2fc548ee1a247b4d3eccbe77599a2"),
            Map.entry("56d00bd0-c4f4-433c-a836-1a52a57e0892",
                    "d00bd056f4c43c43a8361a52a57e0892"),
            Map.entry("36dc7aef-33e6-4691-afe1-2be7274b3d33",
                    "ef7adc36e6339146afe12be7274b3d33"),
            Map.entry("c7935180-6fb3-4201-b174-7df73adbf64a",
                    "805193c7b36f0142b1747df73adbf64a"),
            Map.entry("fb8b03af-3bdf-48d4-bd36-1a65793be168",
                    "af038bfbdf3bd448bd361a65793be168"),
            Map.entry("2acad808-b2d4-452d-a407-91ff1ad167b2",
                    "08d8ca2ad4b22d45a40791ff1ad167b2"),
            Map.entry("d847d3a5-cab0-4a98-8c32-ecb45c59ad24",
                    "a5d347d8b0ca984a8c32ecb45c59ad24"),
            Map.entry("b38b8077-1fc3-42a5-8cae-d40c2215055a",
                    "77808bb3c31fa5428caed40c2215055a"),
            Map.entry("2360c714-4bf1-4b26-ba65-9b21316127eb",
                    "14c76023f14b264bba659b21316127eb"),
            Map.entry("987df77b-db06-4d77-8f8a-86a9c3bb90b9",
                    "7bf77d9806db774d8f8a86a9c3bb90b9"),
            Map.entry("6829ddc4-4f91-4ffa-b86f-bd3e2987cb4c",
                    "c4dd2968914ffa4fb86fbd3e2987cb4c"),
            Map.entry("00000000-0000-0000-c000-000000000046",
                    "0000000000000000c000000000000046"));

    @Test
    void everyIdentifierBecomesTheBytesTheGuestLaidOut() {
        List<UiaInterfaces.Vtable> every = new ArrayList<>(UiaInterfaces.ALL);
        every.add(UiaInterfaces.UNKNOWN);

        for (UiaInterfaces.Vtable vtable : every) {
            String expected = AS_THE_GUEST_LAID_THEM_OUT.get(vtable.iid());
            assertEquals(expected, HexFormat.of().formatHex(vtable.iidBytes()),
                    vtable.name() + ": a QueryInterface argument carries these sixteen bytes, and "
                            + "an identifier read left to right matches nothing at all");
        }
    }

    /**
     * The conversion in the one direction that is easy to get subtly right and wholly wrong: the
     * first three fields swap and the last eight do not.
     */
    @Test
    void onlyTheFirstThreeFieldsAreByteSwapped() {
        byte[] bytes = UiaInterfaces.iidBytes("01020304-0506-0708-090a-0b0c0d0e0f10");

        assertEquals("0403020106050807090a0b0c0d0e0f10", HexFormat.of().formatHex(bytes),
                "Data1 reversed, Data2 and Data3 each reversed, Data4 as written");
    }

    @Test
    void anIdentifierThatIsNotOneIsRefusedRatherThanTruncated() {
        for (String bad : new String[] {"", "not-a-guid", "d6dd68d1-86fd-4332-8666"}) {
            try {
                UiaInterfaces.iidBytes(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException refused) {
                // what should happen
            }
        }
    }

    /**
     * Slot counts, so that the array of function pointers is sized from a reading. Every interface
     * begins with IUnknown's three, which is what makes a QueryInterface arriving on any of them
     * reach the same place.
     */
    @Test
    void everyVtableCountsItsOwnSlotsPlusIUnknownsThree() {
        assertEquals(7, UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE.slotCount());
        assertEquals(9, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT.slotCount());
        assertEquals(5, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT.slotCount());
        assertEquals(4, UiaInterfaces.INVOKE_PROVIDER.slotCount());
        assertEquals(12, UiaInterfaces.WINDOW_PROVIDER.slotCount());
        assertEquals(3, UiaInterfaces.UNKNOWN.slotCount(),
                "IUnknown is the three every other one starts with");

        for (UiaInterfaces.Vtable vtable : UiaInterfaces.ALL) {
            assertTrue(vtable.slotCount() > 3,
                    vtable.name() + " has nothing of its own, which cannot be right");
        }
    }

    /**
     * The order is the whole point of having read it: a client calling slot 3 of
     * {@code IRawElementProviderSimple} means {@code get_ProviderOptions}, and a vtable that put
     * {@code GetPropertyValue} there would answer it with the arguments of the other.
     */
    @Test
    void theSlotOrderIsTheOneTheGuestReported() {
        assertEquals(List.of("get_ProviderOptions", "GetPatternProvider", "GetPropertyValue",
                        "get_HostRawElementProvider"),
                UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE.slots());
        assertEquals(List.of("Navigate", "GetRuntimeId", "get_BoundingRectangle",
                        "GetEmbeddedFragmentRoots", "SetFocus", "get_FragmentRoot"),
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT.slots());
        assertEquals(List.of("ElementProviderFromPoint", "GetFocus"),
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT.slots());
        assertEquals(List.of("SetValue", "get_Value", "get_IsReadOnly"),
                UiaInterfaces.VALUE_PROVIDER.slots(),
                "the setter comes first, which is not where anyone would have guessed it");
        assertEquals(List.of("AdviseEventAdded", "AdviseEventRemoved"),
                UiaInterfaces.ADVISE_EVENTS.slots());
    }

    @Test
    void noTwoInterfacesShareAnIdentifier() {
        Set<String> seen = new TreeSet<>();
        for (UiaInterfaces.Vtable vtable : UiaInterfaces.ALL) {
            assertTrue(seen.add(vtable.iid()),
                    vtable.name() + " repeats an identifier, so a QueryInterface for one would "
                            + "answer the other");
        }
        assertEquals(UiaInterfaces.ALL.size(), seen.size());
    }
}
