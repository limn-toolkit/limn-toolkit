package limn.a11y.windows;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.libffi.LibFFI;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

/**
 * A COM object with a vtable of Java methods behind it, built without a line of C.
 *
 * <p>A COM object is one pointer wide as far as a caller is concerned: it points at a structure
 * whose first field points at an array of function pointers. Everything else about the object is
 * ours. So this allocates the two — an array of closures, and a structure holding its address —
 * and a client calling slot 5 of the interface it thinks it has reaches a Java method here.
 *
 * <p><b>Four call interfaces cover every slot this bridge serves</b>, which is the whole reason the
 * slot order was read off a guest rather than guessed: the signatures repeat, so the compiler
 * cannot tell one slot from another and a vtable in the wrong order is a silent misdispatch. They
 * are named by their arguments after the object itself, and every one returns an {@code HRESULT}.
 *
 * <p><b>Nothing here is freed by a garbage collector.</b> A closure is native memory holding
 * executable code, and the structures below are {@code malloc}'d — so an object outlives every Java
 * reference to it, which is exactly what a client holding an element for minutes needs, and it is
 * released by {@link #release} when the registry says so. Losing track of one leaks; freeing one
 * a client still holds crashes that client. That is the ownership §3.4 assigns and this class does
 * not decide.
 */
final class UiaCom {

    private UiaCom() {
    }

    /** {@code HRESULT f(void* this)} — AddRef, Release, Invoke, SetFocus, ScrollIntoView. */
    interface P extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(P.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(argPointer(args, 0)));
        }

        int invoke(long self);
    }

    /** {@code HRESULT f(void* this, T* out)} — every plain getter, and GetRuntimeId. */
    interface PP extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(PP.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(argPointer(args, 0), argPointer(args, 1)));
        }

        int invoke(long self, long out);
    }

    /**
     * {@code LRESULT f(HWND, UINT, WPARAM, LPARAM)} — a window procedure, which is the one
     * callback here that is not a COM method and the one that returns a pointer.
     */
    interface WndProc extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(WndProc.class,
                MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_uint32, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(argPointer(args, 0), argInt(args, 1),
                    argPointer(args, 2), argPointer(args, 3)));
        }

        long invoke(long hwnd, int message, long wparam, long lparam);
    }

    /** {@code HRESULT f(void* this, double value)} — IRangeValueProvider's setter. */
    interface PD extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(PD.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_double));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(argPointer(args, 0), argDouble(args, 1)));
        }

        int invoke(long self, double value);
    }

    /** {@code HRESULT f(void* this, REFIID riid, void** out)} — QueryInterface, and only it. */
    interface PPP extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(PPP.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret,
                    invoke(argPointer(args, 0), argPointer(args, 1), argPointer(args, 2)));
        }

        int invoke(long self, long riid, long out);
    }

    /**
     * {@code HRESULT f(void* this, int which, T* out)} — GetPropertyValue, GetPatternProvider and
     * Navigate, which is three different meanings for one shape and the reason slot order matters.
     */
    interface PIP extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(PIP.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_sint32, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret,
                    invoke(argPointer(args, 0), argInt(args, 1), argPointer(args, 2)));
        }

        int invoke(long self, int which, long out);
    }

    /** {@code HRESULT f(void* this, double x, double y, T* out)} — ElementProviderFromPoint. */
    interface PDDP extends CallbackI {

        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(PDDP.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint32, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_double, LibFFI.ffi_type_double, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(argPointer(args, 0),
                    argDouble(args, 1), argDouble(args, 2), argPointer(args, 3)));
        }

        int invoke(long self, double x, double y, long out);
    }

    /**
     * libffi hands a closure a pointer to an array of pointers, one per argument, each pointing at
     * the value rather than being it. Every decode in this class goes through these three so the
     * double indirection is written once.
     *
     * <p><b>And the callback's own parameters are {@code (ret, args)} and not {@code (args, ret)}</b>
     * — two longs, so the compiler cannot tell them apart, and reading the return slot as the
     * argument array dereferences whatever it happens to hold. That was worth a crash to find: it
     * is the same shape of mistake as a vtable in the wrong order, with the luck of being loud.
     * The order is LWJGL's, read off the bytecode of one of its own generated callbacks.</p>
     */
    private static long argSlot(long args, int index) {
        return MemoryUtil.memGetAddress(args + (long) index * Pointer.POINTER_SIZE);
    }

    static long argPointer(long args, int index) {
        return MemoryUtil.memGetAddress(argSlot(args, index));
    }

    static int argInt(long args, int index) {
        return MemoryUtil.memGetInt(argSlot(args, index));
    }

    static double argDouble(long args, int index) {
        return MemoryUtil.memGetDouble(argSlot(args, index));
    }

    /**
     * One live COM object: the memory a client points at, and everything that has to be freed with
     * it.
     *
     * @param pointer  the address a client holds, which is the structure whose first field is the
     *                 vtable
     * @param vtable   the array of function pointers
     * @param closures the executable memory behind each slot, in slot order
     */
    record Instance(long pointer, long vtable, List<Long> closures) {
    }

    /**
     * Builds a vtable from slots in order and an object pointing at it.
     *
     * <p>The order of {@code slots} is the vtable's order, and it is the caller's business to make
     * it the one {@link UiaInterfaces} read off the guest. Nothing here can check it: every slot is
     * a function pointer and they are indistinguishable once written.
     *
     * @param slots what occupies slot 0 upward, {@code IUnknown}'s three included
     * @return the object, its vtable and the closures behind it
     */
    static Instance instantiate(List<? extends CallbackI> slots) {
        List<Long> closures = new ArrayList<>(slots.size());
        long vtable = MemoryUtil.nmemAllocChecked((long) slots.size() * Pointer.POINTER_SIZE);
        for (int i = 0; i < slots.size(); i++) {
            long closure = slots.get(i).address();
            closures.add(closure);
            MemoryUtil.memPutAddress(vtable + (long) i * Pointer.POINTER_SIZE, closure);
        }
        // One field: the vtable pointer. Anything else this bridge needs about an object it keeps
        // on the Java side, keyed by this address -- §3.4's pointer map -- rather than in a
        // structure a client could be reading while the user-interface thread writes it.
        long object = MemoryUtil.nmemAllocChecked(Pointer.POINTER_SIZE);
        MemoryUtil.memPutAddress(object, vtable);
        return new Instance(object, vtable, List.copyOf(closures));
    }

    /**
     * Releases an object, its vtable and its closures.
     *
     * <p>Called when the registry says this bridge's claim has ended and never before: a client may
     * hold the pointer for minutes, and freeing underneath it is a crash in the client's process
     * rather than a fault anyone would trace back here.
     *
     * @param instance what {@link #instantiate} returned
     */
    static void release(Instance instance) {
        for (long closure : instance.closures()) {
            Callback.free(closure);
        }
        MemoryUtil.nmemFree(instance.vtable());
        MemoryUtil.nmemFree(instance.pointer());
    }

    /**
     * @param object a pointer a client holds
     * @param slot   which function pointer to read
     * @return the address of that slot's implementation, for a test and for a call this bridge
     *         makes into an object of its own
     */
    static long slotOf(long object, int slot) {
        long vtable = MemoryUtil.memGetAddress(object);
        return MemoryUtil.memGetAddress(vtable + (long) slot * Pointer.POINTER_SIZE);
    }
}
