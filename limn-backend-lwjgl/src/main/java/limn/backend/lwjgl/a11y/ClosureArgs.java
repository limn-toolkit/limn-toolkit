package limn.backend.lwjgl.a11y;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/**
 * Reading the arguments a libffi closure was called with.
 *
 * <p>libffi hands a closure a pointer to an array of pointers, one per argument, each pointing
 * at the value rather than being it. Every decode in the two bridges that take platform
 * callbacks goes through these, so the double indirection is written once and the index says
 * which argument is meant rather than an offset in pointer sizes.
 *
 * <p><b>And a callback's own parameters are {@code (ret, args)} and not {@code (args, ret)}</b>
 * &mdash; two longs, so the compiler cannot tell them apart, and reading the return slot as the
 * argument array dereferences whatever it happens to hold. That was worth a crash to find: it
 * is the same shape of mistake as a vtable in the wrong order, with the luck of being loud. The
 * order is LWJGL's, read off the bytecode of one of its own generated callbacks.
 */
public final class ClosureArgs {

    private ClosureArgs() {
    }

    /**
     * @param args  the argument array the closure was handed
     * @param index which argument
     * @return the address of that argument's value, for a struct passed by value
     */
    public static long slot(long args, int index) {
        return MemoryUtil.memGetAddress(args + (long) index * Pointer.POINTER_SIZE);
    }

    /**
     * @param args  the argument array the closure was handed
     * @param index which argument
     * @return that argument, a pointer-sized value: an object, a selector, an address
     */
    public static long pointer(long args, int index) {
        return MemoryUtil.memGetAddress(slot(args, index));
    }

    /**
     * @param args  the argument array the closure was handed
     * @param index which argument
     * @return that argument, a 32-bit integer
     */
    public static int int32(long args, int index) {
        return MemoryUtil.memGetInt(slot(args, index));
    }

    /**
     * @param args  the argument array the closure was handed
     * @param index which argument
     * @return that argument, a double
     */
    public static double float64(long args, int index) {
        return MemoryUtil.memGetDouble(slot(args, index));
    }
}
