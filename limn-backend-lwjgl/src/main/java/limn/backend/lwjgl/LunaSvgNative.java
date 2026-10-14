package limn.backend.lwjgl;

/**
 * The entry points of {@code liblimnsvg}, the shim of the {@code limn-lunasvg-natives} payload.
 * JNI binds a native method by the class that declares it, and the shim's are named for
 * this one; the payload's repository keeps a copy of this class to test what it builds, and the
 * ABI number is what says whether the two agree.
 *
 * <p>Every string crosses as UTF-8 bytes, every call takes the shim's one process-wide lock, and no
 * C++ exception reaches Java: a failure is a {@code false} or a 0.
 */
final class LunaSvgNative {

    private LunaSvgNative() {
    }

    /** @return the shim's ABI number */
    static native int abi();

    /** @return the version of the lunasvg inside */
    static native String lunasvgVersion();

    /** @return a document, or 0 for text that is no SVG */
    static native long open(byte[] text);

    /** Writes width and height into {@code out}; false for no document. */
    static native boolean size(long document, float[] out);

    static native boolean setAttribute(long document, byte[] id, byte[] name, byte[] value);

    /** Draws into {@code out}, width × height × 4 bytes of straight RGBA. */
    static native boolean render(long document, int width, int height, byte[] out);

    static native void close(long document);

    static native boolean addFont(byte[] family, boolean bold, boolean italic, byte[] data);
}
