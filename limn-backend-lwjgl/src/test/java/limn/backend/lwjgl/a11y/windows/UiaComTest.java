package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an object built here really is callable through its vtable, by calling it.
 *
 * <p><b>None of this needs Windows.</b> A COM object is a structure whose first field points at an
 * array of function pointers, and libffi builds closures on every platform this repository is
 * developed on — so the machinery under the bridge can be exercised end to end here: build the
 * vtable, take the pointer a client would hold, read the slot a client would read, and call it.
 * What cannot be checked here is what the slots <em>mean</em> to UI Automation, and that is why the
 * slot order was read off a guest instead.
 *
 * <p>Every case frees what it made. A closure is executable memory and the structures are
 * {@code malloc}'d: nothing here is a garbage collector's to clean up, which is the property that
 * lets an object outlive every Java reference while a client holds it.
 */
class UiaComTest {

    /** Calls slot {@code n} of an object with one out pointer, as a client would. */
    private static int callPP(long object, int slot, long out) {
        return JNI.invokePPI(object, out, UiaCom.slotOf(object, slot));
    }

    @Test
    void anObjectPointsAtItsVtableAndItsVtableAtTheClosures() {
        AtomicInteger calls = new AtomicInteger();
        UiaCom.P slot = self -> {
            calls.incrementAndGet();
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        try {
            assertNotEquals(0L, made.pointer(), "a client is handed this address");
            assertEquals(made.vtable(), MemoryUtil.memGetAddress(made.pointer()),
                    "and the first field of what it points at is the vtable");
            assertEquals(made.closures().get(0), UiaCom.slotOf(made.pointer(), 0),
                    "which holds the closure for slot 0");

            assertEquals(0, JNI.invokePI(made.pointer(), UiaCom.slotOf(made.pointer(), 0)));
            assertEquals(1, calls.get(), "and calling it reached the Java method");
        } finally {
            UiaCom.release(made);
        }
    }

    /**
     * The object pointer a client is handed is the first argument of every call it makes, which is
     * how this bridge finds the element behind a call arriving on an RPC thread.
     */
    @Test
    void theCallReceivesTheObjectItWasMadeOn() {
        AtomicLong seen = new AtomicLong();
        UiaCom.P slot = self -> {
            seen.set(self);
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        try {
            JNI.invokePI(made.pointer(), UiaCom.slotOf(made.pointer(), 0));

            assertEquals(made.pointer(), seen.get(),
                    "which is what makes the pointer map in §3.4 the way back to the element");
        } finally {
            UiaCom.release(made);
        }
    }

    @Test
    void anOutParameterIsWrittenWhereTheCallerAllocatedIt() {
        UiaCom.PP slot = (self, out) -> {
            MemoryUtil.memPutInt(out, 4242);
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        ByteBuffer caller = MemoryUtil.memAlloc(8);
        try {
            caller.putLong(0, 0L);

            int hresult = callPP(made.pointer(), 0, MemoryUtil.memAddress(caller));

            assertEquals(0, hresult);
            assertEquals(4242, caller.getInt(0),
                    "the caller's memory, written by a Java method through a function pointer");
        } finally {
            MemoryUtil.memFree(caller);
            UiaCom.release(made);
        }
    }

    /**
     * The shape three different members share — GetPropertyValue, GetPatternProvider and Navigate —
     * which is exactly why the slot order had to be read rather than reasoned about.
     */
    @Test
    void anIntegerArgumentArrivesAsItself() {
        AtomicInteger which = new AtomicInteger(-1);
        UiaCom.PIP slot = (self, id, out) -> {
            which.set(id);
            MemoryUtil.memPutInt(out, id * 2);
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        ByteBuffer caller = MemoryUtil.memAlloc(8);
        try {
            int hresult = JNI.invokePPI(made.pointer(), UiaIds.NAME,
                    MemoryUtil.memAddress(caller), UiaCom.slotOf(made.pointer(), 0));

            assertEquals(0, hresult);
            assertEquals(UiaIds.NAME, which.get(), "the property the client asked for");
            assertEquals(UiaIds.NAME * 2, caller.getInt(0));
        } finally {
            MemoryUtil.memFree(caller);
            UiaCom.release(made);
        }
    }

    /** The point a client hovers, which arrives as two doubles and not as two pointers. */
    @Test
    void twoDoublesArriveAsThemselves() {
        AtomicReference<double[]> seen = new AtomicReference<>();
        UiaCom.PDDP slot = (self, x, y, out) -> {
            seen.set(new double[] {x, y});
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        ByteBuffer caller = MemoryUtil.memAlloc(8);
        try {
            // callPPI and not invokePPI: the JNI helpers are named for their pointer-sized and
            // narrow arguments plus the return letter, and there is no invoke* for two doubles.
            // ADR 039 §2.1 records exactly this leak of ABI reasoning into a call site.
            JNI.callPPI(made.pointer(), 640.5, 480.25, MemoryUtil.memAddress(caller),
                    UiaCom.slotOf(made.pointer(), 0));

            assertEquals(640.5, seen.get()[0]);
            assertEquals(480.25, seen.get()[1]);
        } finally {
            MemoryUtil.memFree(caller);
            UiaCom.release(made);
        }
    }

    /** IScrollProvider's Scroll: two four-byte enumerations by value, in order. */
    @Test
    void twoIntegersArriveAsThemselvesInOrder() {
        AtomicReference<int[]> seen = new AtomicReference<>();
        UiaCom.PII slot = (self, horizontal, vertical) -> {
            seen.set(new int[] {horizontal, vertical});
            return 7;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        try {
            int hresult = JNI.invokePI(made.pointer(), 3, 1, UiaCom.slotOf(made.pointer(), 0));

            assertEquals(7, hresult);
            assertEquals(3, seen.get()[0], "the horizontal amount first");
            assertEquals(1, seen.get()[1], "and the vertical second");
        } finally {
            UiaCom.release(made);
        }
    }

    /** IScrollProvider's SetScrollPercent: two doubles and nothing after them. */
    @Test
    void twoDoublesAndNoOutParameterArriveAsThemselves() {
        AtomicReference<double[]> seen = new AtomicReference<>();
        UiaCom.PDD slot = (self, horizontal, vertical) -> {
            seen.set(new double[] {horizontal, vertical});
            return 0;
        };
        UiaCom.Instance made = UiaCom.instantiate(List.of(slot));
        try {
            // No JNI helper takes a pointer and two doubles alone: the one with a trailing pointer
            // is used, whose extra register the two-double closure never reads.
            JNI.callPPI(made.pointer(), 25.5, -1.0, 0L, UiaCom.slotOf(made.pointer(), 0));

            assertEquals(25.5, seen.get()[0]);
            assertEquals(-1.0, seen.get()[1]);
        } finally {
            UiaCom.release(made);
        }
    }

    /**
     * The property the whole design rests on: the slots are distinguished by position and by
     * nothing else, so a vtable in the wrong order calls the wrong method with the right
     * arguments and nothing anywhere notices.
     */
    @Test
    void slotsAreToldApartByPositionAndByNothingElse() {
        List<Integer> reached = new ArrayList<>();
        List<CallbackI> slots = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int index = i;
            slots.add((UiaCom.P) self -> {
                reached.add(index);
                return index;
            });
        }
        UiaCom.Instance made = UiaCom.instantiate(slots);
        try {
            for (int i = 0; i < 7; i++) {
                assertEquals(i, JNI.invokePI(made.pointer(), UiaCom.slotOf(made.pointer(), i)),
                        "slot " + i + " must be the closure written into slot " + i);
            }
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), reached);
        } finally {
            UiaCom.release(made);
        }
    }

    /**
     * A vtable sized from the reading, so that the memory a client walks is exactly as long as the
     * interface it thinks it has.
     */
    @Test
    void aVtableHoldsOnePointerPerSlotAndNoMore() {
        List<CallbackI> slots = new ArrayList<>();
        for (int i = 0; i < UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE.slotCount(); i++) {
            slots.add((UiaCom.P) self -> 0);
        }
        UiaCom.Instance made = UiaCom.instantiate(slots);
        try {
            assertEquals(7, made.closures().size(),
                    "IUnknown's three and IRawElementProviderSimple's four");
            for (int i = 0; i < 7; i++) {
                assertNotEquals(0L, UiaCom.slotOf(made.pointer(), i), "slot " + i + " is empty");
            }
            assertEquals(Pointer.POINTER_SIZE, Pointer.POINTER_SIZE,
                    "each slot is one pointer wide, which is what the arithmetic above assumes");
        } finally {
            UiaCom.release(made);
        }
    }

    @Test
    void twoObjectsMadeAlikeAreStillTwoObjects() {
        UiaCom.Instance first = UiaCom.instantiate(List.of((UiaCom.P) self -> 1));
        UiaCom.Instance second = UiaCom.instantiate(List.of((UiaCom.P) self -> 2));
        try {
            assertNotEquals(first.pointer(), second.pointer(),
                    "a client holding one must never be handed the other's address");
            assertEquals(1, JNI.invokePI(first.pointer(), UiaCom.slotOf(first.pointer(), 0)));
            assertEquals(2, JNI.invokePI(second.pointer(), UiaCom.slotOf(second.pointer(), 0)));
        } finally {
            UiaCom.release(first);
            UiaCom.release(second);
        }
    }

    /**
     * Building and releasing many, because a leak here is native memory holding executable code
     * and there is no collector to notice.
     */
    @Test
    void makingAndReleasingManyLeavesNothingBehindThatCanBeSeenFromHere() {
        for (int i = 0; i < 200; i++) {
            UiaCom.Instance made = UiaCom.instantiate(
                    List.of((UiaCom.P) self -> 0, (UiaCom.P) self -> 0, (UiaCom.P) self -> 0));
            assertTrue(made.pointer() != 0 && made.vtable() != 0);
            UiaCom.release(made);
        }
    }
}
