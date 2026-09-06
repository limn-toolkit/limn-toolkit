package limn.a11y.windows;

import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>The reference count lives here in Java rather than in the object's memory, because it is
 * touched from every RPC thread at once and an {@code AtomicInteger} says what a hand-rolled
 * interlocked field would have to prove.
 */
final class UiaObject {

    /** What one interface contributes: its identity, and the slots after {@code IUnknown}'s. */
    record Served(UiaInterfaces.Vtable iface, List<? extends CallbackI> ownSlots) {
    }

    private final Map<String, Long> byIid = new LinkedHashMap<>();
    private final List<Long> vtables = new ArrayList<>();
    private final List<Long> closures = new ArrayList<>();
    private final long block;
    private final long primary;
    private final AtomicInteger references = new AtomicInteger(1);
    private final Runnable onLastRelease;

    private UiaObject(List<Served> served, Runnable onLastRelease) {
        this.onLastRelease = onLastRelease;
        this.block = MemoryUtil.nmemAllocChecked((long) served.size() * Pointer.POINTER_SIZE);
        // IUnknown's three, shared by every vtable: a client calling Release through the fragment
        // interface and one calling it through the simple interface are releasing one object.
        CallbackI queryInterface = (UiaCom.PPP) (self, riid, out) -> query(riid, out);
        CallbackI addRef = (UiaCom.P) self -> references.incrementAndGet();
        CallbackI release = (UiaCom.P) self -> {
            int left = references.decrementAndGet();
            if (left == 0) {
                onLastRelease.run();
            }
            return left;
        };
        for (int i = 0; i < served.size(); i++) {
            Served one = served.get(i);
            List<CallbackI> slots = new ArrayList<>(one.iface().slotCount());
            slots.add(queryInterface);
            slots.add(addRef);
            slots.add(release);
            slots.addAll(one.ownSlots());
            if (slots.size() != one.iface().slotCount()) {
                throw new IllegalArgumentException(one.iface().name() + " needs "
                        + one.iface().slotCount() + " slots and was given " + slots.size());
            }
            long vtable = MemoryUtil.nmemAllocChecked((long) slots.size() * Pointer.POINTER_SIZE);
            for (int slot = 0; slot < slots.size(); slot++) {
                long closure = slots.get(slot).address();
                closures.add(closure);
                MemoryUtil.memPutAddress(vtable + (long) slot * Pointer.POINTER_SIZE, closure);
            }
            vtables.add(vtable);
            long pointer = block + (long) i * Pointer.POINTER_SIZE;
            MemoryUtil.memPutAddress(pointer, vtable);
            byIid.put(one.iface().iid(), pointer);
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
        if (served.isEmpty()) {
            throw new IllegalArgumentException("an object with no interfaces has no identity");
        }
        return new UiaObject(served, onLastRelease);
    }

    /** @return the pointer a client holds for this object, which is also its {@code IUnknown} */
    long pointer() {
        return primary;
    }

    /**
     * @param iface one of the interfaces this object serves
     * @return the pointer for it, or {@code 0} if it does not serve that one
     */
    long pointerFor(UiaInterfaces.Vtable iface) {
        return byIid.getOrDefault(iface.iid(), 0L);
    }

    /** @return every pointer a call can arrive on, for the registry to resolve back to here */
    List<Long> pointers() {
        return List.copyOf(byIid.values());
    }

    /** @return how many references are outstanding, for a test and for a leak hunt */
    int references() {
        return references.get();
    }

    private int query(long riid, long out) {
        if (out == 0) {
            // A caller that passed nowhere to write to. E_POINTER would be the letter of it; what
            // matters is not dereferencing zero.
            return UiaIds.E_NO_INTERFACE;
        }
        byte[] asked = new byte[16];
        for (int i = 0; i < asked.length; i++) {
            asked[i] = MemoryUtil.memGetByte(riid + i);
        }
        long answer = 0;
        if (Arrays.equals(asked, UiaInterfaces.UNKNOWN.iidBytes())) {
            answer = primary;
        } else {
            for (Map.Entry<String, Long> entry : byIid.entrySet()) {
                if (Arrays.equals(asked, UiaInterfaces.iidBytes(entry.getKey()))) {
                    answer = entry.getValue();
                    break;
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
     * Frees the object's memory and its closures.
     *
     * <p>Never called from {@code Release} and never from a finalizer: the registry decides, after
     * the count has reached zero, and freeing under a client that still holds a pointer is a crash
     * in that client's process rather than a fault anyone would trace here.
     */
    void free() {
        for (long closure : closures) {
            Callback.free(closure);
        }
        for (long vtable : vtables) {
            MemoryUtil.nmemFree(vtable);
        }
        MemoryUtil.nmemFree(block);
    }
}
