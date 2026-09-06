package limn.a11y.windows;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Handing a string to a caller that will free it, which is the one thing this bridge cannot do for
 * itself.
 *
 * <p>A {@code BSTR} is not a string this bridge can allocate: it is length-prefixed, UTF-16,
 * null-terminated, and — the part that matters — <b>freed by the caller with
 * {@code SysFreeString}</b>, which only accepts memory that came from the matching allocator. So
 * every name, description and value a client reads is allocated by {@code oleaut32} and owned by
 * the client from the moment the call returns. Allocating one with {@code malloc} would work until
 * the first client freed it.
 *
 * <p><b>The allocator is a seam, and that is what makes the layer above it testable.</b> On a
 * machine with no {@code oleaut32} — which is most of the machines this repository is built on —
 * {@link #system()} answers a null pointer for every string, and a caller writes {@code VT_EMPTY}
 * instead of a name. A test hands the same layer an allocator of its own and reads back what was
 * written. Neither is a special case in the code that uses it.
 */
final class UiaStrings {

    private UiaStrings() {
    }

    /**
     * What turns a Java string into memory a client owns.
     *
     * <p>Answering {@code 0} is legitimate and means "no string": the caller writes an empty
     * variant rather than a string pointing nowhere, which is what {@link UiaVariant#bstr} already
     * does with it.
     */
    @FunctionalInterface
    interface Allocator {

        /**
         * @param text what to hand over, never {@code null}
         * @return a pointer the caller now owns and will free, or {@code 0} when none could be made
         */
        long allocate(String text);
    }

    /** The library, or {@code null} on a machine that has none. */
    private static final SharedLibrary OLEAUT = open();

    /** {@code SysAllocStringLen(const OLECHAR*, UINT)}, or {@code 0}. */
    private static final long SYS_ALLOC_STRING_LEN =
            OLEAUT == null ? 0L : address("SysAllocStringLen");

    /** {@code SysFreeString(BSTR)}, for the strings this bridge frees rather than hands over. */
    private static final long SYS_FREE_STRING = OLEAUT == null ? 0L : address("SysFreeString");

    private static SharedLibrary open() {
        try {
            return Library.loadNative(UiaStrings.class, "limn.a11y.windows", "oleaut32");
        } catch (Throwable absent) {
            return null;
        }
    }

    private static long address(String function) {
        try {
            return APIUtil.apiGetFunctionAddress(OLEAUT, function);
        } catch (Throwable missing) {
            return 0L;
        }
    }

    /** @return whether this machine can allocate a string a client is able to free */
    static boolean isAvailable() {
        return SYS_ALLOC_STRING_LEN != 0L && SYS_FREE_STRING != 0L;
    }

    /**
     * The platform's own allocator.
     *
     * <p><b>By length and not by null termination.</b> {@code SysAllocString} stops at the first
     * zero code unit; a name legitimately containing one — and a text facet's contents can — would
     * be silently truncated, and the truncation is invisible to everyone but the person listening.
     *
     * @return an allocator, which answers {@code 0} for every string on a machine with no
     *         {@code oleaut32}
     */
    static Allocator system() {
        return text -> {
            if (!isAvailable()) {
                return 0L;
            }
            // Two bytes per code unit, and the length in code units rather than in characters:
            // a string outside the basic plane is two units and one character, and the caller is
            // told how much memory to copy rather than how many letters it spells.
            int units = text.length();
            long utf16 = MemoryUtil.nmemAllocChecked(Math.max(2L, (long) units * 2));
            try {
                for (int i = 0; i < units; i++) {
                    MemoryUtil.memPutShort(utf16 + (long) i * 2, (short) text.charAt(i));
                }
                return JNI.invokePP(utf16, units, SYS_ALLOC_STRING_LEN);
            } finally {
                MemoryUtil.nmemFree(utf16);
            }
        };
    }

    /**
     * Frees a string this bridge allocated and did <em>not</em> hand over.
     *
     * <p>A string that reached a caller is the caller's, and freeing it is a use-after-free in that
     * caller's process. This is for the other case: one allocated for a call that then failed.
     *
     * @param bstr what {@link #system()} returned, or {@code 0}
     */
    static void free(long bstr) {
        if (bstr != 0 && SYS_FREE_STRING != 0) {
            JNI.invokePV(bstr, SYS_FREE_STRING);
        }
    }
}
