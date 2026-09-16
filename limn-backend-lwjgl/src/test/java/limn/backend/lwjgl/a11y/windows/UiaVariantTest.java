package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The one structure this bridge lays out by hand, asserted byte by byte.
 *
 * <p>A {@code ByteBuffer} in the machine's own order stands in for the caller's memory, which is
 * what makes this checkable anywhere: the layout is arithmetic, and arithmetic does not need
 * Windows to be wrong.
 */
class UiaVariantTest {

    /** A caller's VARIANT with a byte of poison before and after, so an overrun is visible. */
    private static ByteBuffer poisoned() {
        ByteBuffer memory = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        for (int i = 0; i < memory.capacity(); i++) {
            memory.put(i, (byte) 0xAB);
        }
        return memory;
    }

    /**
     * The union's offset written out rather than read from {@link UiaVariant#PAYLOAD}: a test that
     * reads where the code writes moves with it, and would agree with a layout that put the payload
     * anywhere at all.
     */
    private static final int UNION = 8;

    private static long payloadAt(ByteBuffer memory, int offset) {
        return memory.getLong(offset + UNION);
    }

    @Test
    void anIntegerIsTaggedAndWrittenAtTheUnion() {
        ByteBuffer memory = poisoned();

        UiaVariant.i4(memory, 8, 50_000);

        assertEquals(UiaVariant.VT_I4, UiaVariant.tagOf(memory, 8));
        assertEquals(50_000, memory.getInt(8 + UNION));
        assertEquals((byte) 0xAB, memory.get(7), "nothing written before the structure");
        assertEquals((byte) 0xAB, memory.get(8 + UiaVariant.SIZE), "nor after it");
    }

    @Test
    void aDoubleTakesTheWholeEightBytesOfTheUnion() {
        ByteBuffer memory = poisoned();

        UiaVariant.r8(memory, 0, 1234.5);

        assertEquals(UiaVariant.VT_R8, UiaVariant.tagOf(memory, 0));
        assertEquals(1234.5, memory.getDouble(UNION));
    }

    /**
     * The oldest trap in this API: a {@code VARIANT_BOOL} is a 16-bit integer where true is every
     * bit set. Writing one produces a value that is neither true nor false, and a caller comparing
     * against {@code VARIANT_TRUE} reads it as false — so a checkbox would report unchecked while
     * checked, and nothing would fail anywhere.
     */
    @Test
    void trueIsEveryBitSetAndNotOne() {
        ByteBuffer memory = poisoned();

        UiaVariant.bool(memory, 0, true);

        assertEquals(UiaVariant.VT_BOOL, UiaVariant.tagOf(memory, 0));
        assertEquals((short) -1, memory.getShort(UNION));
        assertNotEquals((short) 1, memory.getShort(UNION),
                "one is the value a caller reads as neither");

        UiaVariant.bool(memory, 0, false);
        assertEquals((short) 0, memory.getShort(UNION));
    }

    /**
     * The reason every write begins by clearing the whole structure. A union is a union: a
     * four-byte integer written over an eight-byte pointer leaves the pointer's top half behind,
     * and a caller that trusts the tag reads a number nobody wrote.
     */
    @Test
    void aNarrowWriteOverAWideOneLeavesNothingOfIt() {
        ByteBuffer memory = poisoned();

        UiaVariant.bstr(memory, 0, 0x0000_7FFF_DEAD_BEEFL);
        assertEquals(0x0000_7FFF_DEAD_BEEFL, payloadAt(memory, 0));

        UiaVariant.i4(memory, 0, 7);

        assertEquals(UiaVariant.VT_I4, UiaVariant.tagOf(memory, 0));
        assertEquals(7L, payloadAt(memory, 0),
                "the pointer's upper half survived, and a caller reading eight bytes would find "
                        + "an address that is no longer one");
    }

    @Test
    void emptyClearsTheTagAndThePayloadTogether() {
        ByteBuffer memory = poisoned();
        UiaVariant.bstr(memory, 0, 0x0000_7FFF_DEAD_BEEFL);

        UiaVariant.empty(memory, 0);

        assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(memory, 0));
        assertEquals(0L, payloadAt(memory, 0),
                "tagging a stale payload as absent invites a caller to read a field the tag says "
                        + "is not there, and some do");
        for (int i = 0; i < UiaVariant.SIZE; i++) {
            assertEquals((byte) 0, memory.get(i), "byte " + i + " of the structure");
        }
    }

    /**
     * A null string is not a {@code VT_BSTR} holding nothing: a caller reading that tag is entitled
     * to dereference what it finds.
     */
    @Test
    void aNullStringIsEmptyRatherThanAStringThatIsNotThere() {
        ByteBuffer memory = poisoned();

        UiaVariant.bstr(memory, 0, 0);

        assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(memory, 0));
        assertEquals(0L, payloadAt(memory, 0));
    }

    @Test
    void aNullInterfacePointerIsEmptyForTheSameReason() {
        ByteBuffer memory = poisoned();

        UiaVariant.unknown(memory, 0, 0);

        assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(memory, 0));
    }

    /**
     * A runtime id travels as an array of integers, and the tag is the two flags together — the
     * array bit over the element type, which is one number and not two fields.
     */
    @Test
    void anArrayTagCarriesItsElementTypeInTheSameNumber() {
        ByteBuffer memory = poisoned();

        UiaVariant.i4Array(memory, 0, 0x1000);

        assertEquals((short) (UiaVariant.VT_ARRAY | UiaVariant.VT_I4),
                UiaVariant.tagOf(memory, 0));
        assertEquals(0x1000L, payloadAt(memory, 0));
        assertEquals((short) 8195, UiaVariant.tagOf(memory, 0),
                "8192 for the array and 3 for the element type, which is what the guest numbered");
    }

    /**
     * What the trace says a client was handed, one tag at a time.
     *
     * <p>{@code describe} had no test on any branch when it was written (2026-09-16 review) and its
     * only caller is {@code GetPropertyValue}, so the first run of most of this would have been the
     * guest's. The tags are the ones this bridge writes plus one it never does, which is the branch
     * a client's own {@code VARIANT} could still arrive on.
     */
    @Test
    void everyTagTheTraceCanMeetDescribesItself() {
        ByteBuffer memory = poisoned();

        UiaVariant.empty(memory, 0);
        assertEquals("VT_EMPTY", UiaVariant.describe(memory, 0, 40));

        UiaVariant.i4(memory, 0, 50_020);
        assertEquals("VT_I4(50020)", UiaVariant.describe(memory, 0, 40));

        UiaVariant.r8(memory, 0, 1234.5);
        assertEquals("VT_R8(1234.5)", UiaVariant.describe(memory, 0, 40));

        UiaVariant.bool(memory, 0, true);
        assertEquals("VT_BOOL(true)", UiaVariant.describe(memory, 0, 40));
        UiaVariant.bool(memory, 0, false);
        assertEquals("VT_BOOL(false)", UiaVariant.describe(memory, 0, 40));

        UiaVariant.unknown(memory, 0, 0x1f4a20);
        assertEquals("VT_UNKNOWN(0x1f4a20)", UiaVariant.describe(memory, 0, 40));

        UiaVariant.i4Array(memory, 0, 0x1000);
        assertEquals("tag8195(0x1000)", UiaVariant.describe(memory, 0, 40),
                "an array is named by its number, which is the two flags together");

        // A string is the one payload that is a pointer to be followed, and it is followed only
        // where oleaut32 opened -- which is not here, and is the whole of the guard.
        UiaVariant.bstr(memory, 0, 0x0000_7FFF_DEAD_BEEFL);
        assertEquals("VT_BSTR(0x7fffdeadbeef)", UiaVariant.describe(memory, 0, 40),
                "on a machine with no oleaut32 the pointer is not dereferenced");
    }

    /**
     * The length-prefixed read itself, over a buffer this test builds: four bytes of length in
     * front of UTF-16 data, which is what {@code SysAllocStringLen} hands back.
     *
     * <p>It is the one piece of raw memory arithmetic in this file, and until now it could run for
     * the first time on the machine it was added to diagnose. A log line must never be the thing
     * that walks off the end of a buffer, so every length that is not one a string could have is
     * refused with the pointer printed instead.
     */
    @Test
    void aStringIsReadBackThroughItsLengthAndAnImpossibleLengthIsRefused() {
        assertEquals("0x0", UiaVariant.bstrText(0, 40, true), "a null is not followed");
        assertEquals("0x1000", UiaVariant.bstrText(0x1000, 40, false),
                "nor is anything where the payload is not a real BSTR");

        long block = org.lwjgl.system.MemoryUtil.nmemCallocChecked(1, 4 + 2L * 8);
        try {
            long bstr = block + 4;
            writeUtf16(bstr, "Save as…");
            org.lwjgl.system.MemoryUtil.memPutInt(block, 16);
            assertEquals("\"Save as…\"", UiaVariant.bstrText(bstr, 40, true));
            assertEquals("\"Save…\"", UiaVariant.bstrText(bstr, 4, true),
                    "cut to the limit, and said to be cut");

            org.lwjgl.system.MemoryUtil.memPutInt(block, 15);
            assertEquals("0x" + Long.toHexString(bstr) + " length15",
                    UiaVariant.bstrText(bstr, 40, true), "no UTF-16 string is an odd byte long");
            org.lwjgl.system.MemoryUtil.memPutInt(block, -2);
            assertEquals("0x" + Long.toHexString(bstr) + " length-2",
                    UiaVariant.bstrText(bstr, 40, true), "nor a negative one");
            org.lwjgl.system.MemoryUtil.memPutInt(block, (1 << 20) + 2);
            assertEquals("0x" + Long.toHexString(bstr) + " length1048578",
                    UiaVariant.bstrText(bstr, 40, true),
                    "nor one longer than any name a provider hands over");
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(block);
        }
    }

    /** Writes UTF-16 code units where a {@code BSTR}'s data sits. */
    private static void writeUtf16(long at, String text) {
        for (int i = 0; i < text.length(); i++) {
            org.lwjgl.system.MemoryUtil.memPutShort(at + (long) i * 2, (short) text.charAt(i));
        }
    }

    @Test
    void everyWriterLeavesTheStructureExactlyTwentyFourBytesLong() {
        ByteBuffer memory = poisoned();
        int offset = 16;

        UiaVariant.r8(memory, offset, 3.5);

        assertEquals((byte) 0xAB, memory.get(offset - 1));
        assertEquals((byte) 0xAB, memory.get(offset + UiaVariant.SIZE));
        assertEquals(24, UiaVariant.SIZE,
                "the tag, three reserved fields nobody reads, and a union aligned to eight");
    }
}
