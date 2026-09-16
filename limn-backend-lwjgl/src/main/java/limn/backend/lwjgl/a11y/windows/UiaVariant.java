package limn.backend.lwjgl.a11y.windows;

import java.nio.ByteBuffer;

/**
 * Writing a {@code VARIANT}, which is the one structure this bridge lays out by hand.
 *
 * <p>UI Automation hands a provider a pointer to a caller-allocated {@code VARIANT} and expects it
 * filled in. There is no header to include and no marshaller to lean on, so the layout is written
 * here: a 16-bit type tag, three reserved 16-bit fields nobody reads, and then the union, aligned
 * to eight because its widest member is a pointer or a double. Twenty-four bytes in total on every
 * architecture this ships to.
 *
 * <p><b>The whole structure is zeroed before every write, and that is not tidiness.</b> The union
 * is a union: writing a tag of {@code VT_EMPTY} over a payload that still holds a pointer leaves a
 * caller free to read a field the tag says is absent, and some do. Worse is the other order — a
 * four-byte integer written over an eight-byte pointer leaves the pointer's top half behind, and a
 * caller that trusts the tag reads a number nobody wrote. Zeroing is one instruction per eight
 * bytes and removes the whole class.
 *
 * <p>The type tags were read off the guest, from {@code System.Runtime.InteropServices.VarEnum},
 * which is the same numbering {@code wtypes.h} declares. {@link #TRUE} could not be: it lives in
 * that header and the guest carries no Windows SDK, so it is recorded here with its source rather
 * than presented as a reading.
 */
final class UiaVariant {

    private UiaVariant() {
    }

    /** How many bytes a {@code VARIANT} occupies: the tag, three reserved fields, and the union. */
    static final int SIZE = 24;

    /** Where the union begins, after the tag and the three reserved fields, aligned to eight. */
    static final int PAYLOAD = 8;

    // Read from System.Runtime.InteropServices.VarEnum on the guest.
    static final short VT_EMPTY = 0;
    static final short VT_I4 = 3;
    static final short VT_R8 = 5;
    static final short VT_BSTR = 8;
    static final short VT_BOOL = 11;
    static final short VT_UNKNOWN = 13;
    static final short VT_ARRAY = 8192;

    /**
     * What a {@code VT_BOOL} carries for true: every bit set, and not one.
     *
     * <p>From {@code wtypes.h}, which needs an SDK the guest does not carry. It is the oldest trap
     * in this API — a {@code VARIANT_BOOL} is a 16-bit integer where false is zero and true is
     * {@code -1}, so writing {@code 1} produces a value that is neither, and a caller comparing
     * against {@code VARIANT_TRUE} reads it as false. Every boolean <em>property</em> this bridge
     * answers goes through here for that reason -- and only a property: a pattern getter declared
     * {@code BOOL*} takes four bytes and a true of {@code 1} ({@link UiaIds#BOOL_TRUE}), which is
     * where this constant was wrongly written until 2026-09-15 (WINDOWS-NEW-11).
     */
    static final short TRUE = -1;

    /** And false, which is the ordinary zero. */
    static final short FALSE = 0;

    /**
     * Clears a {@code VARIANT} whole and tags it as holding nothing.
     *
     * <p>The answer to every property this bridge does not answer, and the first step of every one
     * it does.
     *
     * @param at     the buffer holding the structure
     * @param offset where the structure begins in it
     */
    static void empty(ByteBuffer at, int offset) {
        for (int i = 0; i < SIZE; i += 8) {
            at.putLong(offset + i, 0L);
        }
    }

    /**
     * @param at     the buffer
     * @param offset where the structure begins
     * @param value  a 32-bit integer, which is what a control type and a heading level are
     */
    static void i4(ByteBuffer at, int offset, int value) {
        empty(at, offset);
        at.putShort(offset, VT_I4);
        at.putInt(offset + PAYLOAD, value);
    }

    /**
     * @param at     the buffer
     * @param offset where the structure begins
     * @param value  a double, which is what every coordinate and every scroll percentage is
     */
    static void r8(ByteBuffer at, int offset, double value) {
        empty(at, offset);
        at.putShort(offset, VT_R8);
        at.putDouble(offset + PAYLOAD, value);
    }

    /**
     * @param at     the buffer
     * @param offset where the structure begins
     * @param value  a boolean, written as the platform's own true rather than as one
     */
    static void bool(ByteBuffer at, int offset, boolean value) {
        empty(at, offset);
        at.putShort(offset, VT_BOOL);
        at.putShort(offset + PAYLOAD, value ? TRUE : FALSE);
    }

    /**
     * @param at      the buffer
     * @param offset  where the structure begins
     * @param pointer a {@code BSTR} the caller now owns and must free, or {@code 0} for no string
     *                at all — which is tagged empty rather than as a null string, because a caller
     *                reading a {@code VT_BSTR} is entitled to dereference it
     */
    static void bstr(ByteBuffer at, int offset, long pointer) {
        empty(at, offset);
        if (pointer == 0) {
            return;
        }
        at.putShort(offset, VT_BSTR);
        at.putLong(offset + PAYLOAD, pointer);
    }

    /**
     * @param at      the buffer
     * @param offset  where the structure begins
     * @param pointer an interface pointer whose reference the caller now owns, or {@code 0}
     */
    static void unknown(ByteBuffer at, int offset, long pointer) {
        empty(at, offset);
        if (pointer == 0) {
            return;
        }
        at.putShort(offset, VT_UNKNOWN);
        at.putLong(offset + PAYLOAD, pointer);
    }

    /**
     * A {@code SAFEARRAY} of 32-bit integers, which is the form a runtime id takes.
     *
     * @param at      the buffer
     * @param offset  where the structure begins
     * @param pointer the array the caller now owns, or {@code 0}
     */
    static void i4Array(ByteBuffer at, int offset, long pointer) {
        empty(at, offset);
        if (pointer == 0) {
            return;
        }
        at.putShort(offset, (short) (VT_ARRAY | VT_I4));
        at.putLong(offset + PAYLOAD, pointer);
    }

    /**
     * A {@code SAFEARRAY} of {@code IUnknown*}, which is the form {@code DescribedBy} and
     * {@code ControllerFor} take.
     *
     * @param at      the buffer
     * @param offset  where the structure begins
     * @param pointer the array the caller now owns, elements referenced, or {@code 0}
     */
    static void unknownArray(ByteBuffer at, int offset, long pointer) {
        empty(at, offset);
        if (pointer == 0) {
            return;
        }
        at.putShort(offset, (short) (VT_ARRAY | VT_UNKNOWN));
        at.putLong(offset + PAYLOAD, pointer);
    }

    /** @return the tag currently written at {@code offset}, for a test and for a log line */
    static short tagOf(ByteBuffer at, int offset) {
        return at.getShort(offset);
    }

    /**
     * What a filled {@code VARIANT} says, as one word for the trace.
     *
     * <p>The tag by name, then the value where the union holds one outright and the pointer where
     * it holds a reference. A {@code VT_BSTR} is the one case worth reading back, because the
     * string is what a reader speaks and a line saying only {@code 0x1f4a20} answers nothing —
     * and it is read <b>only where {@code oleaut32} opened</b>, which is the only machine where
     * the payload is a real {@code BSTR}: {@link UiaStrings#system()} answers {@code 0} for every
     * string elsewhere, and this leaves the tag {@code VT_EMPTY} there, so no other machine ever
     * reaches the four bytes in front of the pointer.
     *
     * @param at     the buffer holding the structure
     * @param offset where the structure begins
     * @param limit  the longest string to repeat
     * @return the description, which allocates and is therefore for a caller behind a trace gate
     */
    static String describe(ByteBuffer at, int offset, int limit) {
        short tag = tagOf(at, offset);
        return switch (tag) {
            case VT_EMPTY -> "VT_EMPTY";
            case VT_I4 -> "VT_I4(" + at.getInt(offset + PAYLOAD) + ')';
            case VT_R8 -> "VT_R8(" + at.getDouble(offset + PAYLOAD) + ')';
            case VT_BOOL -> "VT_BOOL(" + (at.getShort(offset + PAYLOAD) != FALSE) + ')';
            case VT_BSTR -> "VT_BSTR(" + bstrText(at.getLong(offset + PAYLOAD), limit) + ')';
            case VT_UNKNOWN -> "VT_UNKNOWN(0x"
                    + Long.toHexString(at.getLong(offset + PAYLOAD)) + ')';
            default -> "tag" + tag + "(0x" + Long.toHexString(at.getLong(offset + PAYLOAD)) + ')';
        };
    }

    /**
     * @param bstr  the pointer written into the union
     * @param limit the longest string to repeat
     * @return the string in quotes, or the pointer where it may not be read
     */
    private static String bstrText(long bstr, int limit) {
        if (bstr == 0 || !UiaStrings.isAvailable()) {
            return "0x" + Long.toHexString(bstr);
        }
        // A BSTR's length in BYTES sits in the four immediately before the pointer, and the data
        // is UTF-16. Guarded against a length that is not one a string could have, because a log
        // line must never be the thing that walks off the end of a buffer.
        int bytes = org.lwjgl.system.MemoryUtil.memGetInt(bstr - 4);
        if (bytes < 0 || bytes > 1 << 20 || (bytes & 1) != 0) {
            return "0x" + Long.toHexString(bstr) + " length" + bytes;
        }
        int units = Math.min(bytes / 2, limit);
        StringBuilder text = new StringBuilder(units);
        for (int i = 0; i < units; i++) {
            text.append((char) org.lwjgl.system.MemoryUtil.memGetShort(bstr + (long) i * 2));
        }
        return '"' + text.toString().replace('\n', ' ') + (units < bytes / 2 ? "…\"" : "\"");
    }
}
