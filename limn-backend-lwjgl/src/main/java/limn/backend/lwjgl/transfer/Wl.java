package limn.backend.lwjgl.transfer;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.nmemAlloc;
import static org.lwjgl.system.MemoryUtil.nmemFree;

/**
 * The few entry points of libwayland-client this backend calls itself, on the {@code wl_display}
 * GLFW opened: sending a request on a proxy, listening to one, and an event queue of our own.
 *
 * <p>There is no native code of ours: a request is {@code wl_proxy_marshal_array_flags}, whose
 * arguments are an array and not a variable list, reached through libffi; a listener is a table
 * of {@link Ffi#callback} closures. The interfaces ({@code wl_seat_interface} and the like) are the library's
 * own exported descriptions.
 */
final class Wl {

    /** {@code WL_MARSHAL_FLAG_DESTROY}: the request ends the proxy. */
    static final int DESTROY = 1;

    private final SharedLibrary library;
    private final SharedLibrary libc;
    private final long marshalArrayFlags;
    private final long addListener;
    private final long getVersion;
    private final long displayFlush;
    private final long pipe;
    private final long close;
    private final FFICIF marshalCif;
    private final List<Callback> closures = new ArrayList<>();
    private final List<Long> tables = new ArrayList<>();

    /** What an event's handler is given: its arguments after the data pointer, widened. */
    interface Handler {
        void on(long[] args);
    }

    /** @throws RuntimeException when the library, or an entry point, is not on this machine */
    Wl() {
        library = APIUtil.apiCreateLibrary("libwayland-client.so.0");
        libc = APIUtil.apiCreateLibrary("libc.so.6");
        marshalArrayFlags = function("wl_proxy_marshal_array_flags");
        addListener = function("wl_proxy_add_listener");
        getVersion = function("wl_proxy_get_version");
        displayFlush = function("wl_display_flush");
        pipe = APIUtil.apiGetFunctionAddress(libc, "pipe");
        close = APIUtil.apiGetFunctionAddress(libc, "close");
        marshalCif = APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                LibFFI.ffi_type_uint32, LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint32,
                LibFFI.ffi_type_uint32, LibFFI.ffi_type_pointer);
    }

    /** The address of a function or of an exported interface description. */
    long function(String name) {
        return APIUtil.apiGetFunctionAddress(library, name);
    }

    /**
     * Sends a request.
     *
     * @param proxy    the object
     * @param opcode   the request's number in its interface
     * @param newIface the interface of the object the request creates, or NULL
     * @param flags    {@link #DESTROY} or 0
     * @param args     one 8-byte slot an argument: an integer, a pointer, or 0 for a new id
     * @return the object created, or NULL
     */
    long request(long proxy, int opcode, long newIface, int flags, long... args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long argv = stack.ncalloc(8, Math.max(1, args.length), 8);
            for (int i = 0; i < args.length; i++) {
                memPutAddress(argv + 8L * i, args[i]);
            }
            long[] values = {proxy, opcode, newIface, version(proxy), flags, argv};
            long slots = stack.ncalloc(8, 6, 8);
            long store = stack.ncalloc(8, 6, 8);
            for (int i = 0; i < 6; i++) {
                memPutAddress(store + 8L * i, values[i]);
                memPutAddress(slots + 8L * i, store + 8L * i);
            }
            long ret = stack.ncalloc(8, 1, 8);
            LibFFI.nffi_call(marshalCif.address(), marshalArrayFlags, ret, slots);
            return memGetAddress(ret);
        }
    }

    /** As {@link #request}, for {@code wl_registry.bind}, whose new object's version is said. */
    long bind(long registry, int name, long iface, String ifaceName, int version) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long argv = stack.ncalloc(8, 4, 8);
            memPutAddress(argv, Integer.toUnsignedLong(name));
            memPutAddress(argv + 8, org.lwjgl.system.MemoryUtil.memAddress(stack.UTF8(ifaceName)));
            memPutAddress(argv + 16, version);
            long[] values = {registry, 0, iface, version, 0, argv};
            long slots = stack.ncalloc(8, 6, 8);
            long store = stack.ncalloc(8, 6, 8);
            for (int i = 0; i < 6; i++) {
                memPutAddress(store + 8L * i, values[i]);
                memPutAddress(slots + 8L * i, store + 8L * i);
            }
            long ret = stack.ncalloc(8, 1, 8);
            LibFFI.nffi_call(marshalCif.address(), marshalArrayFlags, ret, slots);
            return memGetAddress(ret);
        }
    }

    int version(long proxy) {
        return JNI.invokePI(proxy, getVersion);
    }

    /**
     * An event handler, kept until {@link #free}.
     *
     * @param signature the event's C arguments, the listener's data pointer first: {@code p} a
     *                  pointer, {@code i} a 32-bit integer
     */
    Callback on(String signature, Handler handler) {
        Callback closure = Ffi.callback(signature + ">v", args -> {
            handler.on(args);
            return 0;
        });
        synchronized (closures) {
            closures.add(closure);
        }
        return closure;
    }

    /** An event nobody here acts on. */
    Callback ignore(String signature) {
        return on(signature, args -> { });
    }

    /** A listener table of handlers, in the interface's event order. */
    long table(Callback... handlers) {
        long table = nmemAlloc(8L * handlers.length);
        for (int i = 0; i < handlers.length; i++) {
            memPutAddress(table + 8L * i, handlers[i].address());
        }
        synchronized (closures) {
            tables.add(table);
        }
        return table;
    }

    /** @return whether the proxy took the listener (it takes one, once) */
    boolean listen(long proxy, long table) {
        return JNI.invokePPPI(proxy, table, NULL, addListener) == 0;
    }

    void flush(long display) {
        JNI.invokePI(display, displayFlush);
    }

    /**
     * Ends the client's side of a proxy whose interface, at the version it was bound with, has no
     * destructor request: {@code wl_proxy_destroy}. The compositor's side goes with the connection.
     */
    void forget(long proxy) {
        JNI.invokePV(proxy, function("wl_proxy_destroy"));
    }

    /** @return the read and the write end of a new pipe */
    int[] pipe() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long fds = stack.ncalloc(4, 2, 4);
            if (JNI.invokePI(fds, pipe) != 0) {
                throw new IllegalStateException("pipe() failed");
            }
            return new int[] {memGetInt(fds), memGetInt(fds + 4)};
        }
    }

    void close(int fd) {
        JNI.invokeI(fd, close);
    }

    /** Frees every handler and table; nothing of this object dispatches afterwards. */
    void free() {
        synchronized (closures) {
            for (Callback closure : closures) {
                closure.free();
            }
            closures.clear();
            for (long table : tables) {
                nmemFree(table);
            }
            tables.clear();
        }
    }
}
