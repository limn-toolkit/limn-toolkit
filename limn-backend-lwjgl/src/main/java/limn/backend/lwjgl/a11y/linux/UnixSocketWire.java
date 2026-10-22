package limn.backend.lwjgl.a11y.linux;

import limn.backend.lwjgl.transfer.Ffi;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A D-Bus connection's socket made and used through the C library rather than java.nio, for the
 * one thing java.nio cannot do: send a file descriptor with a message ({@code sendmsg} with
 * {@code SCM_RIGHTS}). The print portal takes the PDF that way, and this is the
 * wire of that one connection; every other connection of the backend stays on its channel.
 *
 * <p>The calls go through {@link Ffi}, libffi under LWJGL, as the backend's other calls into libc
 * do. Reading is a plain {@code read}, with no control buffer: should a descriptor ever arrive on
 * this connection, the kernel closes it rather than leaving it to leak in this process.
 *
 * <p>The structures are Linux's on its 64-bit systems, the same on x86-64 and AArch64:
 * {@code struct msghdr} is 56 bytes (name, namelen padded to 8, iov, iovlen, control, controllen,
 * flags), {@code struct cmsghdr} a {@code size_t} length and two {@code int}s before its data, so
 * one descriptor takes {@code CMSG_SPACE(4)} = 24 bytes of control with {@code CMSG_LEN(4)} = 20.
 */
final class UnixSocketWire implements DBus.Wire {

    static final int AF_UNIX = 1;
    static final int SOCK_STREAM = 1;
    static final int SOCK_CLOEXEC = 0x80000;
    static final int SOL_SOCKET = 1;
    static final int SCM_RIGHTS = 1;
    static final int MSG_NOSIGNAL = 0x4000;
    static final int EINTR = 4;
    /** {@code sizeof(struct sockaddr_un)}: a 2-byte family and 108 bytes of path. */
    static final int SOCKADDR_UN = 110;
    static final int MSGHDR = 56;
    static final int CMSG_SPACE_ONE_FD = 24;
    static final int CMSG_LEN_ONE_FD = 20;

    /** libc's functions, resolved once. */
    static final class C {
        static final SharedLibrary LIBC = APIUtil.apiCreateLibrary("libc.so.6");
        static final long SOCKET = fn("socket");
        static final long CONNECT = fn("connect");
        static final long READ = fn("read");
        static final long WRITE = fn("write");
        static final long SENDMSG = fn("sendmsg");
        static final long CLOSE = fn("close");
        static final long LSEEK = fn("lseek");
        static final long ERRNO = fn("__errno_location");

        private static long fn(String name) {
            long address = LIBC.getFunctionAddress(name);
            if (address == NULL) {
                throw new IllegalStateException("libc exports no " + name);
            }
            return address;
        }

        static int errno() {
            return MemoryUtil.memGetInt(Ffi.call(ERRNO, ">p"));
        }
    }

    private final int fd;
    private boolean closed;

    private UnixSocketWire(int fd) {
        this.fd = fd;
    }

    /**
     * @param path the bus socket's path, from the address's {@code unix:path=}
     * @return a wire connected to it
     * @throws IOException when the socket cannot be made or connected
     */
    static UnixSocketWire connect(String path) throws IOException {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        if (name.length >= SOCKADDR_UN - 2) {
            throw new IOException("a socket path longer than sockaddr_un holds: " + path);
        }
        int fd = (int) Ffi.call(C.SOCKET, "iii>i", AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            throw new IOException("socket() failed, errno " + C.errno());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer address = stack.calloc(SOCKADDR_UN);
            address.putShort(0, (short) AF_UNIX);
            for (int i = 0; i < name.length; i++) {
                address.put(2 + i, name[i]);
            }
            if (Ffi.call(C.CONNECT, "ipi>i", fd, MemoryUtil.memAddress(address), SOCKADDR_UN) < 0) {
                int errno = C.errno();
                Ffi.call(C.CLOSE, "i>i", fd);
                throw new IOException("connect(" + path + ") failed, errno " + errno);
            }
        }
        return new UnixSocketWire(fd);
    }

    @Override
    public int read(ByteBuffer into) throws IOException {
        if (!into.hasRemaining()) {
            return 0;
        }
        ByteBuffer direct = MemoryUtil.memAlloc(into.remaining());
        try {
            while (true) {
                long n = Ffi.call(C.READ, "ipp>p", fd, MemoryUtil.memAddress(direct), direct.remaining());
                if (n < 0) {
                    int errno = C.errno();
                    if (errno == EINTR) {
                        continue;
                    }
                    throw new IOException("read() failed, errno " + errno);
                }
                if (n == 0) {
                    return -1;
                }
                direct.limit((int) n);
                into.put(direct);
                return (int) n;
            }
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    @Override
    public int write(ByteBuffer from) throws IOException {
        int length = from.remaining();
        ByteBuffer direct = MemoryUtil.memAlloc(length);
        try {
            direct.put(from.duplicate()).flip();
            while (true) {
                long n = Ffi.call(C.WRITE, "ipp>p", fd, MemoryUtil.memAddress(direct), length);
                if (n < 0) {
                    int errno = C.errno();
                    if (errno == EINTR) {
                        continue;
                    }
                    throw new IOException("write() failed, errno " + errno);
                }
                from.position(from.position() + (int) n);
                return (int) n;
            }
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    @Override
    public void writeWithDescriptor(ByteBuffer from, int descriptor) throws IOException {
        int length = from.remaining();
        ByteBuffer direct = MemoryUtil.memAlloc(length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            direct.put(from.duplicate()).flip();
            ByteBuffer iov = stack.calloc(8, 16);
            iov.putLong(0, MemoryUtil.memAddress(direct)).putLong(8, length);
            ByteBuffer control = stack.calloc(8, CMSG_SPACE_ONE_FD);
            control.putLong(0, CMSG_LEN_ONE_FD).putInt(8, SOL_SOCKET).putInt(12, SCM_RIGHTS).putInt(16, descriptor);
            ByteBuffer header = stack.calloc(8, MSGHDR);
            header.putLong(16, MemoryUtil.memAddress(iov)).putLong(24, 1)
                    .putLong(32, MemoryUtil.memAddress(control)).putLong(40, CMSG_SPACE_ONE_FD);
            long sent;
            do {
                sent = Ffi.call(C.SENDMSG, "ipi>p", fd, MemoryUtil.memAddress(header), MSG_NOSIGNAL);
            } while (sent < 0 && C.errno() == EINTR);
            if (sent < 0) {
                throw new IOException("sendmsg() failed, errno " + C.errno());
            }
            // The descriptor went with the first byte; whatever the kernel did not take goes plainly.
            direct.position((int) sent);
            while (direct.hasRemaining()) {
                write(direct);
            }
            from.position(from.position() + length);
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            Ffi.call(C.CLOSE, "i>i", fd);
        }
    }
}
