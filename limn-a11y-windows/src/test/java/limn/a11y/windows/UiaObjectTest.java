package limn.a11y.windows;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code IUnknown} contract, exercised the way a client exercises it: through the vtable.
 *
 * <p>Its rules are the ones a hand-written object gets wrong, and every one of them fails quietly.
 * A {@code QueryInterface} for {@code IUnknown} that answers different pointers makes a client
 * comparing two references to one object find two. A refused query that leaves the out parameter
 * alone hands a caller that checks the pointer instead of the result whatever was in its variable.
 * A count that misses a query leaks the object or frees it under someone.
 */
class UiaObjectTest {

    private static final int SLOT_QUERY_INTERFACE = 0;
    private static final int SLOT_ADD_REF = 1;
    private static final int SLOT_RELEASE = 2;

    /** Enough slots to satisfy an interface, each answering nothing in particular. */
    private static List<CallbackI> fillerFor(UiaInterfaces.Vtable iface) {
        List<CallbackI> slots = new ArrayList<>();
        for (int i = 0; i < iface.slots().size(); i++) {
            slots.add((UiaCom.P) self -> UiaIds.S_OK);
        }
        return slots;
    }

    private static UiaObject anElementServing(UiaInterfaces.Vtable... interfaces) {
        return anElementServing(() -> { }, interfaces);
    }

    private static UiaObject anElementServing(Runnable onLastRelease,
                                              UiaInterfaces.Vtable... interfaces) {
        List<UiaObject.Served> served = new ArrayList<>();
        for (UiaInterfaces.Vtable iface : interfaces) {
            served.add(new UiaObject.Served(iface, fillerFor(iface)));
        }
        return UiaObject.create(served, onLastRelease);
    }

    /** Calls QueryInterface through the vtable, as a client does, and returns the HRESULT. */
    private static int queryInterface(long on, String iid, long out) {
        long riid = MemoryUtil.nmemAllocChecked(16);
        try {
            byte[] bytes = UiaInterfaces.iidBytes(iid);
            for (int i = 0; i < bytes.length; i++) {
                MemoryUtil.memPutByte(riid + i, bytes[i]);
            }
            return JNI.invokePPPI(on, riid, out, UiaCom.slotOf(on, SLOT_QUERY_INTERFACE));
        } finally {
            MemoryUtil.nmemFree(riid);
        }
    }

    private static int addRef(long on) {
        return JNI.invokePI(on, UiaCom.slotOf(on, SLOT_ADD_REF));
    }

    private static int release(long on) {
        return JNI.invokePI(on, UiaCom.slotOf(on, SLOT_RELEASE));
    }

    @Test
    void anInterfacePointerFindsItsOwnVtable() {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        try {
            long simple = element.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
            long fragment = element.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);

            assertNotEquals(simple, fragment,
                    "two interfaces are two addresses, because a client walking slot 5 of one must "
                            + "not land in the other");
            assertNotEquals(MemoryUtil.memGetAddress(simple), MemoryUtil.memGetAddress(fragment),
                    "and each address holds its own vtable");
            assertEquals(simple, element.pointer(), "the first served is the object's identity");
        } finally {
            element.free();
        }
    }

    @Test
    void aQueryForAServedInterfaceAnswersItsPointerAndCountsTheReference() {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            assertEquals(1, element.references(), "handed out with one reference already");

            int hresult = queryInterface(element.pointer(),
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT.iid(), out);

            assertEquals(UiaIds.S_OK, hresult);
            assertEquals(element.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT),
                    MemoryUtil.memGetAddress(out));
            assertEquals(2, element.references(),
                    "a successful query is a reference the caller now owns");
        } finally {
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    /**
     * The identity rule. A client compares two references by querying both for {@code IUnknown} and
     * comparing the pointers, so answering anything but one fixed address makes one object look
     * like several.
     */
    @Test
    void everyInterfaceAnswersTheSameIUnknown() {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            List<Long> answers = new ArrayList<>();
            for (long on : element.pointers()) {
                assertEquals(UiaIds.S_OK,
                        queryInterface(on, UiaInterfaces.UNKNOWN.iid(), out));
                answers.add(MemoryUtil.memGetAddress(out));
            }

            assertEquals(3, answers.size());
            for (long answer : answers) {
                assertEquals(element.pointer(), answer,
                        "asked through any interface, IUnknown is one address -- it is how a "
                                + "client decides whether two references are the same object");
            }
        } finally {
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    /**
     * The half that gets forgotten: refusing has to write the null as well as return the code, or a
     * caller that checks the pointer follows whatever its variable held. The guest was asked what a
     * real object answers here, and it answered 0x80004002 with the out parameter at zero.
     */
    @Test
    void aQueryForSomethingUnservedRefusesAndWritesANull() {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            MemoryUtil.memPutAddress(out, 0x0BADF00DL);

            int hresult = queryInterface(element.pointer(),
                    UiaInterfaces.WINDOW_PROVIDER.iid(), out);

            assertEquals(UiaIds.E_NO_INTERFACE, hresult);
            assertEquals(0L, MemoryUtil.memGetAddress(out),
                    "the caller's variable held a plausible-looking address a moment ago");
            assertEquals(1, element.references(), "and a refused query is not a reference");
        } finally {
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    @Test
    void theCountRisesAndFallsAndTheLastReleaseIsAnnouncedOnce() {
        AtomicInteger lastReleases = new AtomicInteger();
        UiaObject element = anElementServing(lastReleases::incrementAndGet,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        try {
            assertEquals(2, addRef(element.pointer()));
            assertEquals(3, addRef(element.pointer()));
            assertEquals(2, release(element.pointer()));
            assertEquals(1, release(element.pointer()));
            assertEquals(0, lastReleases.get(), "still held");

            assertEquals(0, release(element.pointer()));

            assertEquals(1, lastReleases.get(),
                    "the registry is told once, and freeing is its decision and not the count's");
        } finally {
            element.free();
        }
    }

    /**
     * Released through one interface and counted on the object, because a client that queried for
     * the fragment interface releases through that pointer and means this object.
     */
    @Test
    void releasingThroughAnyInterfaceReleasesTheOneObject() {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);
        try {
            long simple = element.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
            long fragment = element.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT);

            addRef(simple);
            assertEquals(2, element.references());
            assertEquals(1, release(fragment));
        } finally {
            element.free();
        }
    }

    /**
     * Three RPC threads at once is what §3.4 says this object meets, so the count is asked to
     * survive it.
     */
    @Test
    void theCountSurvivesThreadsRacingOnIt() throws Exception {
        UiaObject element = anElementServing(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE);
        int threads = 4;
        int each = 500;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Thread racer = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int n = 0; n < each; n++) {
                        addRef(element.pointer());
                        release(element.pointer());
                    }
                }, "rpc-" + i);
                racers.add(racer);
                racer.start();
            }
            go.countDown();
            for (Thread racer : racers) {
                racer.join(TimeUnit.SECONDS.toMillis(20));
                assertFalse(racer.isAlive());
            }

            assertEquals(1, element.references(),
                    "as many releases as adds, from four threads, leaves the one reference the "
                            + "object was created with");
        } finally {
            element.free();
        }
    }

    @Test
    void anInterfaceGivenTheWrongNumberOfSlotsIsRefusedRatherThanBuilt() {
        try {
            UiaObject.create(List.of(new UiaObject.Served(
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE, List.of())), () -> { });
            throw new AssertionError("built a vtable with holes in it");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage().contains("IRawElementProviderSimple"), "names which");
        }
    }

    @Test
    void anObjectServingNothingHasNoIdentityAndIsRefused() {
        try {
            UiaObject.create(List.of(), () -> { });
            throw new AssertionError("built an object nobody could hold");
        } catch (IllegalArgumentException refused) {
            // what should happen
        }
    }
}
