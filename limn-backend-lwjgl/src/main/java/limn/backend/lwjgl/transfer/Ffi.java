package limn.backend.lwjgl.transfer;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;

/**
 * Calling a C function, and being called by one, by a signature written as a string: {@code p} is
 * a pointer or anything as wide ({@code long}, {@code Atom}, {@code Window}), {@code i} a 32-bit
 * integer, and after {@code >} the result, {@code v} for none. It is libffi under LWJGL, and what
 * spares this package a native shim for a few dozen calls into Xlib and the C library.
 */
final class Ffi {

    private static final Map<String, FFICIF> CIFS = new ConcurrentHashMap<>();

    private Ffi() {
    }

    private static FFIType type(char code) {
        return switch (code) {
            case 'p' -> LibFFI.ffi_type_pointer;
            case 'i' -> LibFFI.ffi_type_sint32;
            case 'v' -> LibFFI.ffi_type_void;
            default -> throw new IllegalArgumentException("no type '" + code + "'");
        };
    }

    private static FFICIF cif(String signature) {
        return CIFS.computeIfAbsent(signature, s -> {
            int arrow = s.indexOf('>');
            FFIType[] args = new FFIType[arrow];
            for (int i = 0; i < arrow; i++) {
                args[i] = type(s.charAt(i));
            }
            return APIUtil.apiCreateCIF(type(s.charAt(arrow + 1)), args);
        });
    }

    /**
     * Calls a function.
     *
     * @param function  its address
     * @param signature the argument types, {@code >}, the result type: {@code "ppi>p"}
     * @param args      the arguments, each widened to a long
     * @return the result, widened; 0 for {@code v}
     */
    static long call(long function, String signature, long... args) {
        int arrow = signature.indexOf('>');
        if (arrow != args.length) {
            throw new IllegalArgumentException(signature + " takes " + arrow + " arguments, got " + args.length);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long store = stack.ncalloc(8, Math.max(1, args.length), 8);
            long slots = stack.ncalloc(8, Math.max(1, args.length), 8);
            for (int i = 0; i < args.length; i++) {
                memPutAddress(store + 8L * i, args[i]);
                memPutAddress(slots + 8L * i, store + 8L * i);
            }
            long ret = stack.ncalloc(8, 1, 8);
            LibFFI.nffi_call(cif(signature).address(), function, ret, slots);
            return switch (signature.charAt(arrow + 1)) {
                case 'p' -> memGetAddress(ret);
                case 'i' -> memGetInt(ret);
                default -> 0;
            };
        }
    }

    /** What a C caller is answered by: its arguments widened, and a result that is widened back. */
    interface Handler {
        long on(long[] args);
    }

    private static final class Closure extends Callback implements CallbackI {
        private final Descriptor descriptor;
        private final String signature;
        private final Handler handler;

        Closure(Descriptor descriptor, String signature, Handler handler) {
            super(descriptor);
            this.descriptor = descriptor;
            this.signature = signature;
            this.handler = handler;
        }

        @Override
        public Descriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void callback(long ret, long args) {
            int arrow = signature.indexOf('>');
            long[] values = new long[arrow];
            for (int i = 0; i < arrow; i++) {
                long slot = memGetAddress(args + 8L * i);
                values[i] = signature.charAt(i) == 'p' ? memGetAddress(slot) : memGetInt(slot);
            }
            long result = 0;
            try {
                result = handler.on(values);
            } catch (Throwable failed) {
                // Nothing may unwind into the C caller.
                System.getLogger(Ffi.class.getName()).log(System.Logger.Level.WARNING,
                        "a native callback failed", failed);
            }
            if (signature.charAt(arrow + 1) != 'v') {
                memPutAddress(ret, result); // an integer result is widened to a register, as libffi asks
            }
        }
    }

    /**
     * A C function that calls back into Java. The caller keeps it referenced and frees it.
     *
     * @param signature as for {@link #call}
     * @param handler   what answers
     * @return the callback, whose {@code address()} is the function pointer
     */
    static Callback callback(String signature, Handler handler) {
        return new Closure(new Callback.Descriptor(Closure.class, MethodHandles.lookup(), cif(signature)),
                signature, handler);
    }
}
