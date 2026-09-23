package limn.backend.lwjgl.internal;

import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Sending an Objective-C message from Java, in one place: {@code objc_msgSend} resolved once,
 * and the handful of typed trampolines over it that every caller in this backend needs.
 *
 * <p>Three classes used to carry their own copy of this &mdash; the accessibility bridge, the
 * software-GL fallback and the window's own {@code setLevel:} &mdash; each resolving the symbol
 * for itself and each spelling {@code JNI.invokePPP(self, sel, msgSend)} again. None of it is
 * native code of ours: a class and a selector are the runtime's own functions, and the send
 * is LWJGL's JNI trampoline into {@code libobjc}. A caller that needs the raw address, to hand
 * to libffi for a struct-by-value message, takes {@link #msgSend()}.
 *
 * <p>Usable on every platform: on one that is not macOS {@link #isAvailable()} is false and
 * nothing else here may be called. Internal to the backend; nothing above it names this type.
 */
public final class ObjC {

    private static final long MSG_SEND = resolve();

    private ObjC() {
    }

    private static long resolve() {
        try {
            return ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        } catch (Throwable notAMac) {
            return NULL;
        }
    }

    /** @return whether the Objective-C runtime is here to be sent to */
    public static boolean isAvailable() {
        return MSG_SEND != NULL;
    }

    /** @return the address of {@code objc_msgSend}, for a call made through libffi; zero off macOS */
    public static long msgSend() {
        return MSG_SEND;
    }

    /**
     * @param name a selector's name, colons included
     * @return the selector
     */
    public static long sel(String name) {
        return ObjCRuntime.sel_getUid(name);
    }

    /**
     * @param name a class's name
     * @return the class object
     */
    public static long cls(String name) {
        return ObjCRuntime.objc_getClass(name);
    }

    /** {@code id objc_msgSend(id, SEL)} */
    public static long msg(long self, String selector) {
        return JNI.invokePPP(self, sel(selector), MSG_SEND);
    }

    /** {@code id objc_msgSend(id, SEL, id|NSInteger)} */
    public static long msg(long self, String selector, long a) {
        return JNI.invokePPPP(self, sel(selector), a, MSG_SEND);
    }

    /** {@code id objc_msgSend(id, SEL, id|NSInteger, id|NSInteger)} */
    public static long msg(long self, String selector, long a, long b) {
        return JNI.invokePPPPP(self, sel(selector), a, b, MSG_SEND);
    }

    /** {@code void objc_msgSend(id, SEL)} */
    public static void msgVoid(long self, String selector) {
        JNI.invokePPV(self, sel(selector), MSG_SEND);
    }

    /** {@code void objc_msgSend(id, SEL, id|NSInteger|BOOL)}; a BOOL rides the low byte of x2. */
    public static void msgVoid(long self, String selector, long a) {
        JNI.invokePPPV(self, sel(selector), a, MSG_SEND);
    }

    /** {@code void objc_msgSend(id, SEL, id|NSInteger, id|NSInteger)} */
    public static void msgVoid(long self, String selector, long a, long b) {
        JNI.invokePPPPV(self, sel(selector), a, b, MSG_SEND);
    }
}
