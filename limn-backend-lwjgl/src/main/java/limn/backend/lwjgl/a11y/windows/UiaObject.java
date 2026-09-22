package limn.backend.lwjgl.a11y.windows;

import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One object serving several COM interfaces, with the {@code IUnknown} every one of them begins
 * with.
 *
 * <p>The layout is the one every C++ compiler produces for a class implementing several interfaces:
 * a block of vtable pointers, one per interface, and the address a client is handed for interface
 * <i>i</i> is the address of field <i>i</i> — so dereferencing it finds that interface's vtable and
 * a client walking slot 5 finds slot 5 of the interface it asked for. Nothing else is stored in the
 * object. What this bridge needs to know about it is kept on the Java side, keyed by pointer, for
 * §3.4's reason: a client may be reading the object on an RPC thread while the user-interface
 * thread would be writing it.
 *
 * <p><b>{@code QueryInterface} has rules, and they are the ones a hand-written object gets wrong.</b>
 * Asking for {@code IUnknown} must answer the <em>same</em> pointer every time and through every
 * interface, because that pointer is how a client decides whether two references are the same
 * object — get it wrong and a screen reader holding an element compares it against itself and finds
 * two. Asking for anything not served must answer {@code E_NOINTERFACE} <em>and</em> write a null,
 * which is the half that gets forgotten: a caller that checks the pointer instead of the result
 * then follows whatever was in its variable. And every successful query is a reference the caller
 * now owns, so it counts.
 *
 * <p><b>Some interfaces come and go while the object lives (W2, 2026-09-15).</b> A node's pattern
 * set is a fact of the snapshot, and the snapshot moves: a tree gains {@code Invoke} once it has a
 * cursor row, a leaf that gains children gains {@code ExpandCollapse}, a disabled button loses
 * {@code Invoke}. An object built once with the set of its first ask answered the gained pattern
 * with a null forever and kept answering a lost one to a query. So beside the interfaces it always
 * serves, an object may be made with {@link Varying} ones, asked afresh on every query and every
 * hand-over: one served now is built the first time it is wanted, in a slot the block reserved for
 * it, and one not served now is refused to a new query. <b>The object is never replaced</b>, which
 * is the reason for the reservation: its identity pointer, its reference count and every pointer
 * already handed to a client stay exactly what they were, so the root a client was given by
 * {@code UiaReturnRawElementProvider} is the root it keeps, and a pointer to an interface since
 * withdrawn still reaches live closures (whose slots read the snapshot and refuse what it no longer
 * allows) until the whole-registry empty frees them.
 *
 * <p>The reference count lives here in Java rather than in the object's memory, because it is
 * touched from every RPC thread at once and an {@code AtomicInteger} says what a hand-rolled
 * interlocked field would have to prove.
 */
final class UiaObject {

    /**
     * What one interface contributes: its identity, and its slots <b>by name</b>.
     *
     * <p><b>By name and not in order, which is the whole point.</b> A vtable is an array of
     * function pointers and the signatures repeat, so a list handed over in the wrong order is a
     * silent misdispatch — the client calls what it believes is {@code get_ProviderOptions} and
     * reaches {@code Navigate} with the arguments of the other. Keyed by name, the only thing that
     * decides where a slot lands is {@link UiaInterfaces}' own list, which was read off a guest;
     * a name that is not in it, or one that is missing, fails here and says which.
     *
     * @param iface    the interface being served
     * @param ownSlots its members after {@code IUnknown}'s three, keyed by the names the guest
     *                 reported
     */
    record Served(UiaInterfaces.Vtable iface, Map<String, ? extends CallbackI> ownSlots) {
    }

    /**
     * The interfaces an object may serve at one moment and not at another, decided outside it.
     * Asked on RPC threads; every answer is read off an immutable snapshot, so none blocks.
     */
    interface Varying {

        /** @return every interface this object may ever serve beyond its fixed ones; never changes */
        List<UiaInterfaces.Vtable> candidates();

        /**
         * @param iface one of {@link #candidates()}
         * @return whether the object serves it right now
         */
        boolean servesNow(UiaInterfaces.Vtable iface);

        /**
         * @param iface one of {@link #candidates()}, about to be built
         * @return its slots by name
         */
        Map<String, ? extends CallbackI> slotsFor(UiaInterfaces.Vtable iface);
    }

    /** The varying set of an object that has none. */
    private static final Varying NONE = new Varying() {
        @Override
        public List<UiaInterfaces.Vtable> candidates() {
            return List.of();
        }

        @Override
        public boolean servesNow(UiaInterfaces.Vtable iface) {
            return false;
        }

        @Override
        public Map<String, ? extends CallbackI> slotsFor(UiaInterfaces.Vtable iface) {
            throw new IllegalStateException("an object with no varying interfaces builds none");
        }
    };

    /** Every interface built so far, fixed and varying, by IID; written under {@code this}. */
    private final Map<String, Long> byIid = new ConcurrentHashMap<>();
    /** The fixed interfaces, answered to every query whatever the snapshot says. */
    private final List<UiaInterfaces.Vtable> fixed = new ArrayList<>();
    private final List<Long> vtables = new ArrayList<>();
    private final List<Long> closures = new ArrayList<>();
    private final long block;
    private final int capacity;
    private int built;
    private final long primary;
    private final Varying varying;
    private final CallbackI queryInterface;
    private final CallbackI addRef;
    private final CallbackI release;
    private final AtomicInteger references = new AtomicInteger(1);
    private final Runnable onLastRelease;

    private UiaObject(List<Served> served, Varying varying, Runnable onLastRelease) {
        this.onLastRelease = onLastRelease;
        this.varying = varying;
        this.capacity = served.size() + varying.candidates().size();
        this.block = MemoryUtil.nmemAllocChecked((long) capacity * Pointer.POINTER_SIZE);
        // IUnknown's three, shared by every vtable: a client calling Release through the fragment
        // interface and one calling it through the simple interface are releasing one object.
        this.queryInterface = (UiaCom.PPP) (self, riid, out) -> query(riid, out);
        this.addRef = (UiaCom.P) self -> references.incrementAndGet();
        this.release = (UiaCom.P) self -> release();
        synchronized (this) {
            for (Served one : served) {
                build(one.iface(), one.ownSlots());
                fixed.add(one.iface());
            }
            // What the snapshot says now is built now, so an object answers from its first ask
            // the set a client would read, and a hand-over later builds only what was gained.
            for (UiaInterfaces.Vtable candidate : varying.candidates()) {
                if (varying.servesNow(candidate)) {
                    build(candidate, varying.slotsFor(candidate));
                }
            }
        }
        // The first interface served is this object's identity, and IUnknown always answers it.
        this.primary = block;
    }

    /**
     * @param served        the interfaces to serve, in order; the first is this object's identity
     * @param onLastRelease what to run when the final reference is dropped, which is the registry's
     *                      business and never this class's
     * @return a live object with one outstanding reference, as COM requires of anything handed out
     */
    static UiaObject create(List<Served> served, Runnable onLastRelease) {
        return create(served, NONE, onLastRelease);
    }

    /**
     * @param served        the interfaces always served, in order; the first is this object's
     *                      identity
     * @param varying       the interfaces served only while it says so
     * @param onLastRelease what to run when the final reference is dropped
     * @return a live object with one outstanding reference
     */
    static UiaObject create(List<Served> served, Varying varying, Runnable onLastRelease) {
        if (served.isEmpty()) {
            throw new IllegalArgumentException("an object with no interfaces has no identity");
        }
        return new UiaObject(served, varying, onLastRelease);
    }

    /** Builds one interface's vtable into the next reserved field; under {@code this}. */
    private long build(UiaInterfaces.Vtable iface, Map<String, ? extends CallbackI> ownSlots) {
        if (built == capacity) {
            throw new IllegalStateException(iface.name() + " has no field left in this object");
        }
        List<CallbackI> slots = new ArrayList<>(iface.slotCount());
        slots.add(queryInterface);
        slots.add(addRef);
        slots.add(release);
        // The order comes from the table and from nowhere else.
        for (String name : iface.slots()) {
            CallbackI slot = ownSlots.get(name);
            if (slot == null) {
                throw new IllegalArgumentException(iface.name() + " has no slot for "
                        + name + ", which the guest reported at position "
                        + (3 + iface.slots().indexOf(name)));
            }
            slots.add(slot);
        }
        for (String given : ownSlots.keySet()) {
            if (!iface.slots().contains(given)) {
                throw new IllegalArgumentException(iface.name() + " was given a slot "
                        + "named " + given + ", which it does not have: " + iface.slots());
            }
        }
        long vtable = UiaCom.vtable(slots, closures);
        vtables.add(vtable);
        long pointer = block + (long) built * Pointer.POINTER_SIZE;
        MemoryUtil.memPutAddress(pointer, vtable);
        built++;
        byIid.put(iface.iid(), pointer);
        return pointer;
    }

    /** @return the pointer a client holds for this object, which is also its {@code IUnknown} */
    long pointer() {
        return primary;
    }

    /**
     * @param iface one of the interfaces this object serves
     * @return the pointer for it, built now if it is a varying one the snapshot serves and has not
     *         been wanted before; {@code 0} if the object does not serve it at this moment
     */
    long pointerFor(UiaInterfaces.Vtable iface) {
        if (fixed.contains(iface)) {
            return byIid.get(iface.iid());
        }
        return varying.candidates().contains(iface) ? varyingPointer(iface) : 0L;
    }

    /** A varying interface's pointer while the snapshot serves it, else {@code 0}. */
    private long varyingPointer(UiaInterfaces.Vtable iface) {
        if (!varying.servesNow(iface)) {
            return 0L;
        }
        Long pointer = byIid.get(iface.iid());
        if (pointer != null) {
            return pointer;
        }
        synchronized (this) {
            // Two RPC threads wanting the same gained pattern build it once.
            Long again = byIid.get(iface.iid());
            return again != null ? again : build(iface, varying.slotsFor(iface));
        }
    }

    /** @return every pointer built so far, for the registry to resolve back to here */
    synchronized List<Long> pointers() {
        List<Long> all = new ArrayList<>(built);
        for (int i = 0; i < built; i++) {
            all.add(block + (long) i * Pointer.POINTER_SIZE);
        }
        return all;
    }

    /** @return how many references are outstanding, for a test and for a leak hunt */
    int references() {
        return references.get();
    }

    /**
     * Counts a reference this bridge is about to hand a caller.
     *
     * <p>Every interface pointer returned from a COM method is a reference the caller owns and
     * will release. Handing one over without counting it is a use-after-free waiting for the
     * caller to be tidy: it releases what it was given, the count reaches zero early, and the
     * object is freed while UI Automation still has it.
     */
    void addRef() {
        references.incrementAndGet();
    }

    /**
     * One reference dropped, by the platform through {@code IUnknown::Release} or by a test
     * standing in for it.
     *
     * <p>A retired object whose last reference this was is queued for {@link #freeRetired}
     * rather than freed here: this runs inside one of the object's own closures, and freeing the
     * trampoline that is executing is a return into freed memory.
     *
     * @return the references left
     */
    int release() {
        int left = references.decrementAndGet();
        if (left == 0) {
            onLastRelease.run();
            if (retired) {
                RETIRED.add(this);
            }
        }
        return left;
    }

    /**
     * Objects the registry let go of while the platform still held them, whose last platform
     * reference has since been released; freed by the next {@link #freeRetired} on the
     * user-interface thread.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<UiaObject> RETIRED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private volatile boolean retired;

    /**
     * The registry lets this object go: its own reference is dropped, and the object is freed now
     * if nothing else holds it, or once the platform's last reference is released.
     *
     * <p>P5W-3 (2026-09-22): the registry used to free every object outright when a window's
     * tree went away, on the reading that {@code UiaDisconnectProvider} had released every
     * platform reference first. It had not — a client's proxies release theirs on threads of
     * their own, after the disconnect returns — and closing a date picker's native popup under
     * NVDA freed fourteen objects and died in a freed trampoline ({@code jvm.dll} at one offset,
     * every run). A reference count is what COM gives an object for exactly this, so the count
     * decides: an object the platform still holds outlives the registry, answers nothing (its
     * bridge is closed), and is freed when the platform lets go. One that never lets go is a
     * bounded leak, which is the failure to prefer.
     *
     * @return whether it was freed now
     */
    synchronized boolean retire() {
        retired = true;
        int left = references.decrementAndGet();
        if (left <= 0) {
            free();
            return true;
        }
        return false;
    }

    /**
     * Frees every retired object whose last reference has been released, on the user-interface
     * thread, where no closure of theirs can be executing.
     *
     * @return how many were freed
     */
    static int freeRetired() {
        int freed = 0;
        for (UiaObject object = RETIRED.poll(); object != null; object = RETIRED.poll()) {
            object.free();
            freed++;
        }
        return freed;
    }

    private int query(long riid, long out) {
        if (out == 0) {
            // A caller that passed nowhere to write to. E_POINTER would be the letter of it; what
            // matters is not dereferencing zero.
            return UiaIds.E_NO_INTERFACE;
        }
        // Compared in place against bytes parsed once per identifier: UI Automation probes for
        // many interfaces this bridge never serves, and a miss allocates nothing.
        long answer = 0;
        if (UiaInterfaces.UNKNOWN.isIidAt(riid)) {
            answer = primary;
        } else {
            // Indexed, not for-each: an iterator per list per query is the allocation this avoids.
            for (int i = 0; i < fixed.size(); i++) {
                UiaInterfaces.Vtable iface = fixed.get(i);
                if (iface.isIidAt(riid)) {
                    answer = byIid.get(iface.iid());
                    break;
                }
            }
            if (answer == 0) {
                // Only an IID that names a candidate asks the snapshot anything.
                List<UiaInterfaces.Vtable> candidates = varying.candidates();
                for (int i = 0; i < candidates.size(); i++) {
                    UiaInterfaces.Vtable candidate = candidates.get(i);
                    if (candidate.isIidAt(riid)) {
                        answer = varyingPointer(candidate);
                        break;
                    }
                }
            }
        }
        if (answer == 0) {
            MemoryUtil.memPutAddress(out, 0);
            return UiaIds.E_NO_INTERFACE;
        }
        MemoryUtil.memPutAddress(out, answer);
        references.incrementAndGet();
        return UiaIds.S_OK;
    }

    /**
     * Frees the object's memory and its closures, every interface it ever built included.
     *
     * <p>Never called from {@code Release} and never from a finalizer: the registry decides, after
     * the count has reached zero ({@link #retire}, {@link #freeRetired}), and freeing under a
     * client that still holds a pointer is a crash in this process, inside a closure that no
     * longer exists, which is what P5W-3 was.
     */
    synchronized void free() {
        UiaCom.freeClosures(closures);
        for (long vtable : vtables) {
            MemoryUtil.nmemFree(vtable);
        }
        MemoryUtil.nmemFree(block);
    }
}
