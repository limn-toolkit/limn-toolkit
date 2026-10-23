package limn.backend.lwjgl.transfer;

import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;

import java.util.Map;

import static java.util.Map.entry;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;

/**
 * The typed shape of every signature a callback of {@link Ffi#callback} is made with, one interface
 * each, and the one closure that answers them all.
 *
 * <p><b>Why a shape per signature.</b> On Java 17 to 24 LWJGL makes a callback with libffi, which
 * reads the arguments from the signature's CIF and hands them to {@link CallbackI#callback} as a
 * vector, so one generic closure served every signature. On Java 25 it makes the callback with the
 * platform's foreign-function linker instead, which reads the arguments from a Java method: the
 * callback's type must be an interface annotated {@code @FunctionalInterface} whose one abstract
 * method has the C function's parameters, a {@code long} for a pointer and an {@code int} for a
 * 32-bit integer on these 64-bit systems. A generic closure was refused there ("The binder must be
 * parameterized with an interface"), and every callback made through it failed.
 *
 * <p>The closure implements every shape and the vector form both, so the same object serves libffi
 * and the linker. A signature that has no shape here is refused by {@link Ffi#callback}; the test
 * of this class reads every signature the backend's sources pass and checks that each has one.
 */
final class FfiShapes {

    private FfiShapes() {
    }

    /** What every shape inherits: the closure answers these two itself. */
    interface Shape extends CallbackI {
        @Override
        default Callback.Descriptor getDescriptor() {
            throw new UnsupportedOperationException("answered by the closure");
        }

        @Override
        default void callback(long ret, long args) {
            throw new UnsupportedOperationException("answered by the closure");
        }
    }

    /** {@code p>i} */
    @FunctionalInterface
    interface P_I extends Shape {
        int p_i(long a0);
    }

    /** {@code pi>i} */
    @FunctionalInterface
    interface PI_I extends Shape {
        int pi_i(long a0, int a1);
    }

    /** {@code pp>i} */
    @FunctionalInterface
    interface PP_I extends Shape {
        int pp_i(long a0, long a1);
    }

    /** {@code pp>v} */
    @FunctionalInterface
    interface PP_V extends Shape {
        void pp_v(long a0, long a1);
    }

    /** {@code pii>i} */
    @FunctionalInterface
    interface PII_I extends Shape {
        int pii_i(long a0, int a1, int a2);
    }

    /** {@code pip>i} */
    @FunctionalInterface
    interface PIP_I extends Shape {
        int pip_i(long a0, int a1, long a2);
    }

    /** {@code ppi>v} */
    @FunctionalInterface
    interface PPI_V extends Shape {
        void ppi_v(long a0, long a1, int a2);
    }

    /** {@code ppp>i} */
    @FunctionalInterface
    interface PPP_I extends Shape {
        int ppp_i(long a0, long a1, long a2);
    }

    /** {@code ppp>p} */
    @FunctionalInterface
    interface PPP_P extends Shape {
        long ppp_p(long a0, long a1, long a2);
    }

    /** {@code ppp>v} */
    @FunctionalInterface
    interface PPP_V extends Shape {
        void ppp_v(long a0, long a1, long a2);
    }

    /** {@code pipp>i} */
    @FunctionalInterface
    interface PIPP_I extends Shape {
        int pipp_i(long a0, int a1, long a2, long a3);
    }

    /** {@code ppii>v} */
    @FunctionalInterface
    interface PPII_V extends Shape {
        void ppii_v(long a0, long a1, int a2, int a3);
    }

    /** {@code ppip>v} */
    @FunctionalInterface
    interface PPIP_V extends Shape {
        void ppip_v(long a0, long a1, int a2, long a3);
    }

    /** {@code pppi>i} */
    @FunctionalInterface
    interface PPPI_I extends Shape {
        int pppi_i(long a0, long a1, long a2, int a3);
    }

    /** {@code pppi>v} */
    @FunctionalInterface
    interface PPPI_V extends Shape {
        void pppi_v(long a0, long a1, long a2, int a3);
    }

    /** {@code pppp>p} */
    @FunctionalInterface
    interface PPPP_P extends Shape {
        long pppp_p(long a0, long a1, long a2, long a3);
    }

    /** {@code pppp>v} */
    @FunctionalInterface
    interface PPPP_V extends Shape {
        void pppp_v(long a0, long a1, long a2, long a3);
    }

    /** {@code ppiii>v} */
    @FunctionalInterface
    interface PPIII_V extends Shape {
        void ppiii_v(long a0, long a1, int a2, int a3, int a4);
    }

    /** {@code ppipi>v} */
    @FunctionalInterface
    interface PPIPI_V extends Shape {
        void ppipi_v(long a0, long a1, int a2, long a3, int a4);
    }

    /** {@code ppipp>i} */
    @FunctionalInterface
    interface PPIPP_I extends Shape {
        int ppipp_i(long a0, long a1, int a2, long a3, long a4);
    }

    /** {@code ppipp>v} */
    @FunctionalInterface
    interface PPIPP_V extends Shape {
        void ppipp_v(long a0, long a1, int a2, long a3, long a4);
    }

    /** {@code pppip>v} */
    @FunctionalInterface
    interface PPPIP_V extends Shape {
        void pppip_v(long a0, long a1, long a2, int a3, long a4);
    }

    /** {@code ppppp>v} */
    @FunctionalInterface
    interface PPPPP_V extends Shape {
        void ppppp_v(long a0, long a1, long a2, long a3, long a4);
    }

    /** {@code ppiiii>v} */
    @FunctionalInterface
    interface PPIIII_V extends Shape {
        void ppiiii_v(long a0, long a1, int a2, int a3, int a4, int a5);
    }

    /** {@code ppiipi>v} */
    @FunctionalInterface
    interface PPIIPI_V extends Shape {
        void ppiipi_v(long a0, long a1, int a2, int a3, long a4, int a5);
    }

    /** {@code ppipii>v} */
    @FunctionalInterface
    interface PPIPII_V extends Shape {
        void ppipii_v(long a0, long a1, int a2, long a3, int a4, int a5);
    }

    /** {@code pppppp>v} */
    @FunctionalInterface
    interface PPPPPP_V extends Shape {
        void pppppp_v(long a0, long a1, long a2, long a3, long a4, long a5);
    }

    /** {@code ppiiiii>v} */
    @FunctionalInterface
    interface PPIIIII_V extends Shape {
        void ppiiiii_v(long a0, long a1, int a2, int a3, int a4, int a5, int a6);
    }

    /** {@code ppipiip>v} */
    @FunctionalInterface
    interface PPIPIIP_V extends Shape {
        void ppipiip_v(long a0, long a1, int a2, long a3, int a4, int a5, long a6);
    }

    /** Each signature's shape. */
    static final Map<String, Class<? extends Shape>> SHAPES = Map.ofEntries(
            entry("p>i", P_I.class),
            entry("pi>i", PI_I.class),
            entry("pp>i", PP_I.class),
            entry("pp>v", PP_V.class),
            entry("pii>i", PII_I.class),
            entry("pip>i", PIP_I.class),
            entry("ppi>v", PPI_V.class),
            entry("ppp>i", PPP_I.class),
            entry("ppp>p", PPP_P.class),
            entry("ppp>v", PPP_V.class),
            entry("pipp>i", PIPP_I.class),
            entry("ppii>v", PPII_V.class),
            entry("ppip>v", PPIP_V.class),
            entry("pppi>i", PPPI_I.class),
            entry("pppi>v", PPPI_V.class),
            entry("pppp>p", PPPP_P.class),
            entry("pppp>v", PPPP_V.class),
            entry("ppiii>v", PPIII_V.class),
            entry("ppipi>v", PPIPI_V.class),
            entry("ppipp>i", PPIPP_I.class),
            entry("ppipp>v", PPIPP_V.class),
            entry("pppip>v", PPPIP_V.class),
            entry("ppppp>v", PPPPP_V.class),
            entry("ppiiii>v", PPIIII_V.class),
            entry("ppiipi>v", PPIIPI_V.class),
            entry("ppipii>v", PPIPII_V.class),
            entry("pppppp>v", PPPPPP_V.class),
            entry("ppiiiii>v", PPIIIII_V.class),
            entry("ppipiip>v", PPIPIIP_V.class));

    /** The closure: the vector form for libffi, and every shape's method for the linker. */
    static final class Closure extends Callback implements P_I, PI_I, PP_I, PP_V, PII_I, PIP_I, PPI_V, PPP_I, PPP_P, PPP_V, PIPP_I, PPII_V, PPIP_V, PPPI_I, PPPI_V, PPPP_P, PPPP_V, PPIII_V, PPIPI_V, PPIPP_I, PPIPP_V, PPPIP_V, PPPPP_V, PPIIII_V, PPIIPI_V, PPIPII_V, PPPPPP_V, PPIIIII_V, PPIPIIP_V {

        private final Callback.Descriptor descriptor;
        private final String signature;
        private final Ffi.Handler handler;

        Closure(Callback.Descriptor descriptor, String signature, Ffi.Handler handler) {
            super(descriptor);
            this.descriptor = descriptor;
            this.signature = signature;
            this.handler = handler;
        }

        @Override
        public Callback.Descriptor getDescriptor() {
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
            long result = answer(values);
            if (signature.charAt(arrow + 1) != 'v') {
                memPutAddress(ret, result); // an integer result is widened to a register, as libffi asks
            }
        }

        /** The handler's answer; nothing it throws may unwind into the C caller. */
        private long answer(long... values) {
            try {
                return handler.on(values);
            } catch (Throwable failed) {
                System.getLogger(Ffi.class.getName()).log(System.Logger.Level.WARNING,
                        "a native callback failed", failed);
                return 0;
            }
        }

        @Override
        public int p_i(long a0) {
            return (int) answer(a0);
        }

        @Override
        public int pi_i(long a0, int a1) {
            return (int) answer(a0, a1);
        }

        @Override
        public int pp_i(long a0, long a1) {
            return (int) answer(a0, a1);
        }

        @Override
        public void pp_v(long a0, long a1) {
            answer(a0, a1);
        }

        @Override
        public int pii_i(long a0, int a1, int a2) {
            return (int) answer(a0, a1, a2);
        }

        @Override
        public int pip_i(long a0, int a1, long a2) {
            return (int) answer(a0, a1, a2);
        }

        @Override
        public void ppi_v(long a0, long a1, int a2) {
            answer(a0, a1, a2);
        }

        @Override
        public int ppp_i(long a0, long a1, long a2) {
            return (int) answer(a0, a1, a2);
        }

        @Override
        public long ppp_p(long a0, long a1, long a2) {
            return answer(a0, a1, a2);
        }

        @Override
        public void ppp_v(long a0, long a1, long a2) {
            answer(a0, a1, a2);
        }

        @Override
        public int pipp_i(long a0, int a1, long a2, long a3) {
            return (int) answer(a0, a1, a2, a3);
        }

        @Override
        public void ppii_v(long a0, long a1, int a2, int a3) {
            answer(a0, a1, a2, a3);
        }

        @Override
        public void ppip_v(long a0, long a1, int a2, long a3) {
            answer(a0, a1, a2, a3);
        }

        @Override
        public int pppi_i(long a0, long a1, long a2, int a3) {
            return (int) answer(a0, a1, a2, a3);
        }

        @Override
        public void pppi_v(long a0, long a1, long a2, int a3) {
            answer(a0, a1, a2, a3);
        }

        @Override
        public long pppp_p(long a0, long a1, long a2, long a3) {
            return answer(a0, a1, a2, a3);
        }

        @Override
        public void pppp_v(long a0, long a1, long a2, long a3) {
            answer(a0, a1, a2, a3);
        }

        @Override
        public void ppiii_v(long a0, long a1, int a2, int a3, int a4) {
            answer(a0, a1, a2, a3, a4);
        }

        @Override
        public void ppipi_v(long a0, long a1, int a2, long a3, int a4) {
            answer(a0, a1, a2, a3, a4);
        }

        @Override
        public int ppipp_i(long a0, long a1, int a2, long a3, long a4) {
            return (int) answer(a0, a1, a2, a3, a4);
        }

        @Override
        public void ppipp_v(long a0, long a1, int a2, long a3, long a4) {
            answer(a0, a1, a2, a3, a4);
        }

        @Override
        public void pppip_v(long a0, long a1, long a2, int a3, long a4) {
            answer(a0, a1, a2, a3, a4);
        }

        @Override
        public void ppppp_v(long a0, long a1, long a2, long a3, long a4) {
            answer(a0, a1, a2, a3, a4);
        }

        @Override
        public void ppiiii_v(long a0, long a1, int a2, int a3, int a4, int a5) {
            answer(a0, a1, a2, a3, a4, a5);
        }

        @Override
        public void ppiipi_v(long a0, long a1, int a2, int a3, long a4, int a5) {
            answer(a0, a1, a2, a3, a4, a5);
        }

        @Override
        public void ppipii_v(long a0, long a1, int a2, long a3, int a4, int a5) {
            answer(a0, a1, a2, a3, a4, a5);
        }

        @Override
        public void pppppp_v(long a0, long a1, long a2, long a3, long a4, long a5) {
            answer(a0, a1, a2, a3, a4, a5);
        }

        @Override
        public void ppiiiii_v(long a0, long a1, int a2, int a3, int a4, int a5, int a6) {
            answer(a0, a1, a2, a3, a4, a5, a6);
        }

        @Override
        public void ppipiip_v(long a0, long a1, int a2, long a3, int a4, int a5, long a6) {
            answer(a0, a1, a2, a3, a4, a5, a6);
        }
    }
}
