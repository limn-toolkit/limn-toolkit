package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Every slot an interface names, each answering nothing in particular. */
    private static Map<String, CallbackI> fillerFor(UiaInterfaces.Vtable iface) {
        Map<String, CallbackI> slots = new LinkedHashMap<>();
        for (String name : iface.slots()) {
            slots.put(name, (UiaCom.P) self -> UiaIds.S_OK);
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

    /** A varying set holding one candidate, served while {@code on[0]} is true. */
    private static UiaObject.Varying oneVarying(UiaInterfaces.Vtable candidate, boolean[] on,
                                                AtomicInteger builds) {
        return new UiaObject.Varying() {
            @Override
            public List<UiaInterfaces.Vtable> candidates() {
                return List.of(candidate);
            }

            @Override
            public boolean servesNow(UiaInterfaces.Vtable iface) {
                return on[0];
            }

            @Override
            public Map<String, ? extends CallbackI> slotsFor(UiaInterfaces.Vtable iface) {
                builds.incrementAndGet();
                return fillerFor(iface);
            }
        };
    }

    /**
     * Review of windows-A: UI Automation probes an element for many interfaces it does not serve,
     * and since W2 each probe compared the asked identifier against every fixed and candidate one by
     * parsing their canonical strings into fresh arrays (2.1 us a miss, 3.1 us a candidate hit, over
     * a 1802-node table on the host). The comparison now reads the asked bytes in place against
     * bytes parsed once, so a miss, and a candidate the snapshot does not serve, allocate nothing.
     */
    @Test
    void aQueryForAnInterfaceNotServedAllocatesNothing() {
        org.junit.jupiter.api.Assumptions.assumeTrue(limn.testing.AllocationProbe.isSupported(),
                "this virtual machine does not count allocation");
        boolean[] on = {false};
        UiaObject element = UiaObject.create(
                List.of(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                                fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE)),
                        new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT,
                                fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT))),
                // The bridge's candidate list is a constant, so this one is too: List.of per call
                // would be the fake's allocation, not the object's.
                new UiaObject.Varying() {
                    private final List<UiaInterfaces.Vtable> candidates =
                            List.of(UiaInterfaces.INVOKE_PROVIDER);

                    @Override
                    public List<UiaInterfaces.Vtable> candidates() {
                        return candidates;
                    }

                    @Override
                    public boolean servesNow(UiaInterfaces.Vtable iface) {
                        return on[0];
                    }

                    @Override
                    public Map<String, ? extends CallbackI> slotsFor(UiaInterfaces.Vtable iface) {
                        return fillerFor(iface);
                    }
                }, () -> { });
        long out = MemoryUtil.nmemAllocChecked(8);
        long transform = MemoryUtil.nmemAllocChecked(16);
        long invoke = MemoryUtil.nmemAllocChecked(16);
        try {
            byte[] bytes = UiaInterfaces.TRANSFORM_PROVIDER.iidBytes();
            byte[] invokeBytes = UiaInterfaces.INVOKE_PROVIDER.iidBytes();
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutByte(transform + i, bytes[i]);
                MemoryUtil.memPutByte(invoke + i, invokeBytes[i]);
            }
            long on0 = element.pointer();
            long query = UiaCom.slotOf(on0, SLOT_QUERY_INTERFACE);
            assertEquals(UiaIds.E_NO_INTERFACE, JNI.invokePPPI(on0, transform, out, query));
            assertEquals(UiaIds.E_NO_INTERFACE, JNI.invokePPPI(on0, invoke, out, query));
            long allocated = limn.testing.AllocationProbe.leastAllocatedBy(() -> {
                for (int i = 0; i < 1_000; i++) {
                    JNI.invokePPPI(on0, transform, out, query);
                    JNI.invokePPPI(on0, invoke, out, query);
                }
            }, 5);
            assertEquals(0, allocated,
                    "a thousand misses and a thousand unserved candidates allocate nothing");
        } finally {
            MemoryUtil.nmemFree(invoke);
            MemoryUtil.nmemFree(transform);
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    /**
     * W2: an interface the snapshot starts serving after the object was made is answered to a
     * query from then on, on the same object: the identity pointer is unchanged, and the query
     * counts a reference like any other while the change itself counts none.
     */
    @Test
    void aVaryingInterfaceGainedLaterIsAnsweredOnTheSameObjectAndCountsLikeAnyOther() {
        boolean[] on = {false};
        AtomicInteger builds = new AtomicInteger();
        UiaObject element = UiaObject.create(
                List.of(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                        fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE))),
                oneVarying(UiaInterfaces.INVOKE_PROVIDER, on, builds), () -> { });
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            long identity = element.pointer();
            assertEquals(UiaIds.E_NO_INTERFACE,
                    queryInterface(identity, UiaInterfaces.INVOKE_PROVIDER.iid(), out));
            assertEquals(0L, MemoryUtil.memGetAddress(out));
            assertEquals(0, builds.get(), "nothing is built while nothing is served");

            on[0] = true;
            assertEquals(1, element.references(), "a change in the snapshot counts no reference");
            assertEquals(UiaIds.S_OK,
                    queryInterface(identity, UiaInterfaces.INVOKE_PROVIDER.iid(), out));
            long invoke = MemoryUtil.memGetAddress(out);
            assertNotEquals(0L, invoke);
            assertEquals(invoke, element.pointerFor(UiaInterfaces.INVOKE_PROVIDER));
            assertEquals(2, element.references(), "the query's reference, as for any interface");
            assertEquals(identity, element.pointer(), "the identity never moves");
            assertEquals(UiaIds.S_OK, queryInterface(invoke, UiaInterfaces.UNKNOWN.iid(), out));
            assertEquals(identity, MemoryUtil.memGetAddress(out),
                    "and IUnknown through the gained interface is that identity");
            assertEquals(1, builds.get(), "built once, the first time it was wanted");
            assertEquals(2, element.pointers().size());
        } finally {
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    /**
     * W2, the set shrinking: an interface the snapshot no longer serves is refused to a new query,
     * and a pointer to it a client already holds still reaches its closures, because nothing is
     * freed before the whole-registry empty.
     */
    @Test
    void aVaryingInterfaceLostIsRefusedToANewQueryWhileAPointerHandedOutStillCalls() {
        boolean[] on = {true};
        AtomicInteger builds = new AtomicInteger();
        UiaObject element = UiaObject.create(
                List.of(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                        fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE))),
                oneVarying(UiaInterfaces.INVOKE_PROVIDER, on, builds), () -> { });
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            long invoke = element.pointerFor(UiaInterfaces.INVOKE_PROVIDER);
            assertNotEquals(0L, invoke, "served from the start, so built with the object");

            on[0] = false;

            assertEquals(0L, element.pointerFor(UiaInterfaces.INVOKE_PROVIDER));
            assertEquals(UiaIds.E_NO_INTERFACE,
                    queryInterface(element.pointer(), UiaInterfaces.INVOKE_PROVIDER.iid(), out));
            assertEquals(0L, MemoryUtil.memGetAddress(out));
            assertEquals(1, element.references());
            assertEquals(UiaIds.S_OK, JNI.invokePI(invoke, UiaCom.slotOf(invoke, 3)),
                    "the held pointer's Invoke slot is still live code");

            on[0] = true;
            assertEquals(invoke, element.pointerFor(UiaInterfaces.INVOKE_PROVIDER),
                    "served again, the same interface pointer and no second build");
            assertEquals(1, builds.get());
        } finally {
            MemoryUtil.nmemFree(out);
            element.free();
        }
    }

    /** Three RPC threads wanting one gained interface at once build it once. */
    @Test
    void aGainedInterfaceIsBuiltOnceHoweverManyThreadsWantItAtOnce() throws Exception {
        boolean[] on = {false};
        AtomicInteger builds = new AtomicInteger();
        UiaObject element = UiaObject.create(
                List.of(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                        fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE))),
                new UiaObject.Varying() {
                    @Override
                    public List<UiaInterfaces.Vtable> candidates() {
                        return List.of(UiaInterfaces.TOGGLE_PROVIDER);
                    }

                    @Override
                    public boolean servesNow(UiaInterfaces.Vtable iface) {
                        return on[0];
                    }

                    @Override
                    public Map<String, ? extends CallbackI> slotsFor(UiaInterfaces.Vtable iface) {
                        builds.incrementAndGet();
                        return fillerFor(iface);
                    }
                }, () -> { });
        // Not served at creation, so nothing is built then and the threads race to build it.
        assertEquals(0, builds.get());
        UiaObject racedOn = element;
        on[0] = true;
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> racers = new ArrayList<>();
            long[] answers = new long[4];
            for (int i = 0; i < answers.length; i++) {
                int at = i;
                Thread racer = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    answers[at] = racedOn.pointerFor(UiaInterfaces.TOGGLE_PROVIDER);
                }, "rpc-" + i);
                racers.add(racer);
                racer.start();
            }
            go.countDown();
            for (Thread racer : racers) {
                racer.join(TimeUnit.SECONDS.toMillis(20));
                assertFalse(racer.isAlive());
            }
            for (long answer : answers) {
                assertEquals(answers[0], answer);
            }
            assertNotEquals(0L, answers[0]);
            assertEquals(1, builds.get(), "one build, whoever asked first");
        } finally {
            element.free();
        }
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

    /**
     * The check that makes a misdispatch impossible rather than unlikely: slots are placed by the
     * name the guest reported, so one that is missing fails here and says which position it was
     * meant to occupy, instead of leaving a hole a client falls into.
     */
    @Test
    void anInterfaceMissingASlotIsRefusedAndSaysWhichOne() {
        Map<String, CallbackI> incomplete =
                new LinkedHashMap<>(fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE));
        incomplete.remove("GetPropertyValue");
        try {
            UiaObject.create(List.of(new UiaObject.Served(
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE, incomplete)), () -> { });
            throw new AssertionError("built a vtable with a hole in it");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage().contains("GetPropertyValue"), refused.getMessage());
            assertTrue(refused.getMessage().contains("position 5"),
                    "and says where it belonged, which is the guest's reading: "
                            + refused.getMessage());
        }
    }

    /** A name that is not in the interface is a slot nobody would ever call. */
    @Test
    void anInterfaceGivenASlotItDoesNotHaveIsRefused() {
        Map<String, CallbackI> wrong =
                new LinkedHashMap<>(fillerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE));
        wrong.put("Navigate", (UiaCom.P) self -> UiaIds.S_OK);
        try {
            UiaObject.create(List.of(new UiaObject.Served(
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE, wrong)), () -> { });
            throw new AssertionError("accepted a member of another interface");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage().contains("Navigate"), refused.getMessage());
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
