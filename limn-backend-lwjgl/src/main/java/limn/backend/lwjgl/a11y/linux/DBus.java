package limn.backend.lwjgl.a11y.linux;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import limn.concurrent.Threads;

/**
 * A minimal D-Bus client for the Limn screen-reader spike: SASL EXTERNAL over a
 * java.nio unix-domain SocketChannel, the little-endian wire format, and a dispatch loop that
 * both makes blocking calls and answers incoming method calls from exported objects.
 *
 * Java 17 API only: java.nio.channels.SocketChannel + java.net.UnixDomainSocketAddress
 * (both JDK 16+). No FFM/Panama, no AWT, no third-party jar, no native code at all.
 *
 * Deliberate limitations:
 *  - little-endian on the wire when writing (we read either endianness);
 *  - no UNIX fd passing, and it is never negotiated: java.nio cannot do SCM_RIGHTS, and a connection
 *    that agreed to NEGOTIATE_UNIX_FD is one a peer may send a descriptor-carrying message to,
 *    which this reader could not even parse (ADR 039 §2.3). Unnegotiated, the bus refuses to route
 *    such a message here; the type 'h' is neither read nor written;
 *  - no abstract-socket transport (java.net.UnixDomainSocketAddress is filesystem-path only);
 *  - method-call handlers run on the single reader thread, so a handler must not make a
 *    blocking call on the same connection.
 */
final class DBus {

    private DBus() {}

    // ---- message types / flags (spec: Message Format) ------------------------------------

    /** The standard properties interface, for the one property the gate reads. */
    static final String I_PROPS_NAME = "org.freedesktop.DBus.Properties";

    static final byte METHOD_CALL = 1, METHOD_RETURN = 2, ERROR = 3, SIGNAL = 4;
    static final byte NO_REPLY_EXPECTED = 0x1;

    // ---- header field codes (spec: Header Fields) ----------------------------------------

    static final byte F_PATH = 1, F_INTERFACE = 2, F_MEMBER = 3, F_ERROR_NAME = 4,
            F_REPLY_SERIAL = 5, F_DESTINATION = 6, F_SENDER = 7, F_SIGNATURE = 8, F_UNIX_FDS = 9;

    static boolean TRACE = Boolean.getBoolean("probe.trace");

    // =========================================================================================
    // Values
    // =========================================================================================

    /** The AT-SPI object reference "(so)": a bus name plus an object path. */
    static final class Ref {
        final String name, path;
        Ref(String name, String path) { this.name = name; this.path = path; }
        Object[] toStruct() { return new Object[] { name, path }; }
        static Ref of(Object v) {
            Object[] a = (v instanceof Ref) ? ((Ref) v).toStruct() : (Object[]) v;
            return new Ref((String) a[0], (String) a[1]);
        }
        @Override public String toString() { return "(" + name + ", " + path + ")"; }
        @Override public boolean equals(Object o) {
            return o instanceof Ref && ((Ref) o).name.equals(name) && ((Ref) o).path.equals(path);
        }
        @Override public int hashCode() { return name.hashCode() * 31 + path.hashCode(); }
    }

    /** A D-Bus VARIANT: a signature plus a value of that signature. */
    static final class Variant {
        final String sig; public final Object value;
        Variant(String sig, Object value) { this.sig = sig; this.value = value; }
        @Override public String toString() { return "v<" + sig + ">" + fmt(value); }
    }

    static Variant v(String sig, Object value) { return new Variant(sig, value); }

    // =========================================================================================
    // Signature helpers
    // =========================================================================================

    /** Length of the complete single type starting at {@code i}. */
    static int typeLen(String s, int i) {
        char c = s.charAt(i);
        switch (c) {
            case 'a': return 1 + typeLen(s, i + 1);
            case '(': {
                int d = 1, j = i + 1;
                while (d > 0) { char k = s.charAt(j++); if (k == '(') d++; else if (k == ')') d--; }
                return j - i;
            }
            case '{': {
                int d = 1, j = i + 1;
                while (d > 0) { char k = s.charAt(j++); if (k == '{') d++; else if (k == '}') d--; }
                return j - i;
            }
            default: return 1;
        }
    }

    /** Split a signature into its complete top-level types. */
    static List<String> split(String sig) {
        List<String> out = new ArrayList<>();
        if (sig == null) return out;
        for (int i = 0; i < sig.length(); ) { int n = typeLen(sig, i); out.add(sig.substring(i, i + n)); i += n; }
        return out;
    }

    /** Wire alignment of a type, in bytes (spec: Marshalling / alignment table). */
    static int alignOf(char c) {
        switch (c) {
            case 'y': case 'g': case 'v': return 1;
            case 'n': case 'q': return 2;
            case 'b': case 'i': case 'u': case 's': case 'o': case 'a': return 4;
            case 'x': case 't': case 'd': case '(': case '{': case 'r': case 'e': return 8;
            default: throw new IllegalArgumentException("bad type char '" + c + "'");
        }
    }

    // =========================================================================================
    // Writer
    // =========================================================================================

    static final class Writer {
        private byte[] buf = new byte[512];
        int pos = 0;

        private void ensure(int n) {
            if (pos + n > buf.length) {
                int cap = Math.max(buf.length * 2, pos + n);
                buf = Arrays.copyOf(buf, cap);
            }
        }
        void align(int a) { int pad = (a - (pos % a)) % a; ensure(pad); for (int i = 0; i < pad; i++) buf[pos++] = 0; }
        void u8(int v) { ensure(1); buf[pos++] = (byte) v; }
        void u16(int v) { ensure(2); buf[pos++] = (byte) v; buf[pos++] = (byte) (v >>> 8); }
        void u32(int v) { ensure(4); buf[pos++] = (byte) v; buf[pos++] = (byte) (v >>> 8); buf[pos++] = (byte) (v >>> 16); buf[pos++] = (byte) (v >>> 24); }
        void u64(long v) { u32((int) v); u32((int) (v >>> 32)); }
        void raw(byte[] b) { ensure(b.length); System.arraycopy(b, 0, buf, pos, b.length); pos += b.length; }
        void patchU32(int at, int v) {
            buf[at] = (byte) v; buf[at + 1] = (byte) (v >>> 8); buf[at + 2] = (byte) (v >>> 16); buf[at + 3] = (byte) (v >>> 24);
        }
        /** STRING / OBJECT_PATH: 4-aligned uint32 byte-length, the UTF-8 bytes, a NUL. */
        void str(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); align(4); u32(b.length); raw(b); u8(0); }
        /** SIGNATURE: a single byte length, the bytes, a NUL. No alignment. */
        void sigStr(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); u8(b.length); raw(b); u8(0); }
        byte[] toArray() { return Arrays.copyOf(buf, pos); }
    }

    private static Object[] asArray(Object v) {
        if (v instanceof Ref) return ((Ref) v).toStruct();
        if (v instanceof Object[]) return (Object[]) v;
        if (v instanceof List) return ((List<?>) v).toArray();
        throw new IllegalArgumentException("not a struct value: " + v);
    }

    private static List<?> asList(Object v) {
        if (v instanceof List) return (List<?>) v;
        if (v instanceof Object[]) return Arrays.asList((Object[]) v);
        throw new IllegalArgumentException("not an array value: " + v);
    }

    static void write(Writer w, String sig, Object v) {
        char c = sig.charAt(0);
        switch (c) {
            case 'y': w.u8(((Number) v).intValue()); return;
            case 'b': w.align(4); w.u32(((Boolean) v) ? 1 : 0); return;
            case 'n': case 'q': w.align(2); w.u16(((Number) v).intValue()); return;
            case 'i': case 'u': w.align(4); w.u32(((Number) v).intValue()); return;
            case 'x': case 't': w.align(8); w.u64(((Number) v).longValue()); return;
            case 'd': w.align(8); w.u64(Double.doubleToLongBits(((Number) v).doubleValue())); return;
            case 's': case 'o': w.str((String) v); return;
            case 'g': w.sigStr((String) v); return;
            case 'v': {
                Variant var = (Variant) v;
                w.sigStr(var.sig);
                write(w, var.sig, var.value);
                return;
            }
            case 'a': {
                String elem = sig.substring(1);
                w.align(4);
                int lenAt = w.pos;
                w.u32(0);
                w.align(alignOf(elem.charAt(0)));   // padding here is NOT counted in the length
                int start = w.pos;
                if (elem.charAt(0) == '{') {
                    List<String> kv = split(elem.substring(1, elem.length() - 1));
                    if (v instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                            w.align(8);
                            write(w, kv.get(0), e.getKey());
                            write(w, kv.get(1), e.getValue());
                        }
                    } else {
                        for (Object o : asList(v)) {
                            Object[] pair = asArray(o);
                            w.align(8);
                            write(w, kv.get(0), pair[0]);
                            write(w, kv.get(1), pair[1]);
                        }
                    }
                } else {
                    for (Object o : asList(v)) write(w, elem, o);
                }
                w.patchU32(lenAt, w.pos - start);
                return;
            }
            case '(': {
                Object[] items = asArray(v);
                List<String> parts = split(sig.substring(1, sig.length() - 1));
                w.align(8);
                for (int i = 0; i < parts.size(); i++) write(w, parts.get(i), items[i]);
                return;
            }
            default: throw new IllegalArgumentException("cannot write type '" + sig + "'");
        }
    }

    /** Marshal a whole argument list. */
    static byte[] writeArgs(String sig, Object[] args) {
        Writer w = new Writer();
        List<String> parts = split(sig);
        for (int i = 0; i < parts.size(); i++) write(w, parts.get(i), args[i]);
        return w.toArray();
    }

    // =========================================================================================
    // Reader
    // =========================================================================================

    static final class Reader {
        final ByteBuffer b;
        public Reader(ByteBuffer b) { this.b = b; }
        public Reader(byte[] a) { this(ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)); }
        void align(int a) { int p = b.position(); int pad = (a - (p % a)) % a; b.position(p + pad); }
        int u8() { return b.get() & 0xff; }
        int u16() { align(2); return b.getShort() & 0xffff; }
        int u32() { align(4); return b.getInt(); }
        long u64() { align(8); return b.getLong(); }
        String str() { align(4); int n = b.getInt(); byte[] a = new byte[n]; b.get(a); b.get(); return new String(a, StandardCharsets.UTF_8); }
        String sigStr() { int n = u8(); byte[] a = new byte[n]; b.get(a); b.get(); return new String(a, StandardCharsets.UTF_8); }
        boolean hasRemaining() { return b.hasRemaining(); }
    }

    static Object read(Reader r, String sig) {
        char c = sig.charAt(0);
        switch (c) {
            case 'y': return (byte) r.u8();
            case 'b': return r.u32() != 0;
            case 'n': return (short) r.u16();
            case 'q': return r.u16();
            case 'i': case 'u': return r.u32();
            case 'x': case 't': return r.u64();
            case 'd': return Double.longBitsToDouble(r.u64());
            case 's': case 'o': return r.str();
            case 'g': return r.sigStr();
            case 'v': { String s = r.sigStr(); return new Variant(s, read(r, s)); }
            case 'a': {
                String elem = sig.substring(1);
                int len = r.u32();
                r.align(alignOf(elem.charAt(0)));
                int end = r.b.position() + len;
                if (elem.charAt(0) == '{') {
                    List<String> kv = split(elem.substring(1, elem.length() - 1));
                    Map<Object, Object> m = new LinkedHashMap<>();
                    while (r.b.position() < end) {
                        r.align(8);
                        Object k = read(r, kv.get(0));
                        Object val = read(r, kv.get(1));
                        m.put(k, val);
                    }
                    return m;
                }
                List<Object> out = new ArrayList<>();
                while (r.b.position() < end) out.add(read(r, elem));
                return out;
            }
            case '(': {
                List<String> parts = split(sig.substring(1, sig.length() - 1));
                r.align(8);
                Object[] items = new Object[parts.size()];
                for (int i = 0; i < parts.size(); i++) items[i] = read(r, parts.get(i));
                return items;
            }
            default: throw new IllegalArgumentException("cannot read type '" + sig + "'");
        }
    }

    static Object[] readArgs(byte[] body, ByteOrder order, String sig) {
        if (sig == null || sig.isEmpty()) return new Object[0];
        Reader r = new Reader(ByteBuffer.wrap(body).order(order));
        List<String> parts = split(sig);
        Object[] out = new Object[parts.size()];
        for (int i = 0; i < parts.size(); i++) out[i] = read(r, parts.get(i));
        return out;
    }

    // =========================================================================================
    // Message
    // =========================================================================================

    static final class Msg {
        byte type, flags;
        int serial, replySerial;
        String path, iface, member, errorName, destination, sender, signature;
        Object[] body = new Object[0];
        byte[] raw;   // the exact bytes as they came off / went onto the wire

        static Msg call(String dest, String path, String iface, String member, String sig, Object... args) {
            Msg m = new Msg();
            m.type = METHOD_CALL; m.destination = dest; m.path = path; m.iface = iface; m.member = member;
            m.signature = (sig == null || sig.isEmpty()) ? null : sig; m.body = args;
            return m;
        }
        static Msg ret(Msg to, String sig, Object... args) {
            Msg m = new Msg();
            m.type = METHOD_RETURN; m.replySerial = to.serial; m.destination = to.sender;
            m.signature = (sig == null || sig.isEmpty()) ? null : sig; m.body = args;
            return m;
        }
        static Msg err(Msg to, String name, String text) {
            Msg m = new Msg();
            m.type = ERROR; m.replySerial = to.serial; m.destination = to.sender; m.errorName = name;
            m.signature = "s"; m.body = new Object[] { text };
            return m;
        }
        static Msg signal(String path, String iface, String member, String sig, Object... args) {
            Msg m = new Msg();
            m.type = SIGNAL; m.path = path; m.iface = iface; m.member = member;
            m.signature = (sig == null || sig.isEmpty()) ? null : sig; m.body = args;
            return m;
        }

        byte[] marshal(int serial) {
            this.serial = serial;
            Writer w = new Writer();
            w.u8('l');
            w.u8(type);
            w.u8(flags);
            w.u8(1);                       // protocol version
            int bodyLenAt = w.pos; w.u32(0);
            w.u32(serial);

            List<Object> fields = new ArrayList<>();
            if (path != null)        fields.add(new Object[] { F_PATH, v("o", path) });
            if (iface != null)       fields.add(new Object[] { F_INTERFACE, v("s", iface) });
            if (member != null)      fields.add(new Object[] { F_MEMBER, v("s", member) });
            if (errorName != null)   fields.add(new Object[] { F_ERROR_NAME, v("s", errorName) });
            if (type == METHOD_RETURN || type == ERROR) fields.add(new Object[] { F_REPLY_SERIAL, v("u", replySerial) });
            if (destination != null) fields.add(new Object[] { F_DESTINATION, v("s", destination) });
            // The bus writes SENDER on everything it routes and ignores one a client wrote; set only
            // by a test standing in for the bus.
            if (sender != null)      fields.add(new Object[] { F_SENDER, v("s", sender) });
            if (signature != null)   fields.add(new Object[] { F_SIGNATURE, v("g", signature) });
            write(w, "a(yv)", fields);     // starts at offset 12: 4-aligned, contents land on 16

            w.align(8);
            int bodyStart = w.pos;
            List<String> parts = split(signature);
            for (int i = 0; i < parts.size(); i++) write(w, parts.get(i), body[i]);
            w.patchU32(bodyLenAt, w.pos - bodyStart);
            raw = w.toArray();
            return raw;
        }

        static Msg parse(byte[] full) {
            Msg m = parseHeader(full);
            ByteOrder order = full[0] == 'B' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            m.body = readArgs(m.rawBody, order, m.signature);
            m.rawBody = null;
            return m;
        }

        /** The body's bytes between {@link #parseHeader} and the body's own parse. */
        private byte[] rawBody;

        /**
         * The fixed header and the header fields, leaving the body unread: what is still knowable
         * about a message whose body cannot be parsed — its type, flags, serial and sender, which is
         * everything an error reply to it needs.
         */
        @SuppressWarnings("unchecked")
        static Msg parseHeader(byte[] full) {
            ByteOrder order = full[0] == 'B' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            ByteBuffer b = ByteBuffer.wrap(full).order(order);
            Reader r = new Reader(b);
            Msg m = new Msg();
            m.raw = full;
            r.u8();                        // endianness
            m.type = (byte) r.u8();
            m.flags = (byte) r.u8();
            r.u8();                        // protocol version
            int bodyLen = r.u32();
            m.serial = r.u32();
            List<Object> fields = (List<Object>) read(r, "a(yv)");
            for (Object f : fields) {
                Object[] fa = (Object[]) f;
                int code = ((Number) fa[0]).intValue();
                Variant val = (Variant) fa[1];
                switch (code) {
                    case F_PATH: m.path = (String) val.value; break;
                    case F_INTERFACE: m.iface = (String) val.value; break;
                    case F_MEMBER: m.member = (String) val.value; break;
                    case F_ERROR_NAME: m.errorName = (String) val.value; break;
                    case F_REPLY_SERIAL: m.replySerial = ((Number) val.value).intValue(); break;
                    case F_DESTINATION: m.destination = (String) val.value; break;
                    case F_SENDER: m.sender = (String) val.value; break;
                    case F_SIGNATURE: m.signature = (String) val.value; break;
                    default: break;        // UNIX_FDS and anything newer: ignored on purpose
                }
            }
            r.align(8);
            byte[] body = new byte[bodyLen];
            b.get(body);
            m.rawBody = body;
            return m;
        }

        @Override public String toString() {
            String t = type == METHOD_CALL ? "call" : type == METHOD_RETURN ? "return" : type == ERROR ? "error" : "signal";
            StringBuilder sb = new StringBuilder(t).append(" #").append(serial);
            if (replySerial != 0) sb.append(" reply-to#").append(replySerial);
            if (sender != null) sb.append(" from ").append(sender);
            if (destination != null) sb.append(" to ").append(destination);
            if (path != null) sb.append(' ').append(path);
            if (iface != null) sb.append(' ').append(iface);
            if (member != null) sb.append('.').append(member);
            if (errorName != null) sb.append(" ERR ").append(errorName);
            if (signature != null) sb.append(" <").append(signature).append('>');
            sb.append(' ').append(fmt(body));
            return sb.toString();
        }
    }

    static String fmt(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[]) {
            StringBuilder sb = new StringBuilder("[");
            Object[] a = (Object[]) o;
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(fmt(a[i])); }
            return sb.append(']').toString();
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> a = (List<?>) o;
            for (int i = 0; i < a.size(); i++) { if (i > 0) sb.append(", "); sb.append(fmt(a.get(i))); }
            return sb.append(']').toString();
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(", "); first = false;
                sb.append(fmt(e.getKey())).append('=').append(fmt(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof String) return "\"" + o + "\"";
        return String.valueOf(o);
    }

    static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte x : a) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        s = s.replaceAll("\\s+", "");
        byte[] a = new byte[s.length() / 2];
        for (int i = 0; i < a.length; i++) a[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return a;
    }

    // =========================================================================================
    // Connection
    // =========================================================================================

    /** An exported object: answers method calls arriving on some object path. */
    interface Handler {
        /** Return the reply message, or null to let the connection answer UnknownMethod. */
        Msg handle(Conn c, Msg call) throws Exception;
    }

    static final class DBusError extends RuntimeException {
        final String name;
        public DBusError(String name, String text) { super(name + ": " + text); this.name = name; }
    }

    static final class Conn implements Closeable {
        final String address;
        private final SocketChannel ch;
        private final AtomicInteger serial = new AtomicInteger(1);
        private final Object writeLock = new Object();
        private final Map<Integer, ArrayBlockingQueue<Msg>> pending = new ConcurrentHashMap<>();
        private final Map<String, Handler> exports = new ConcurrentHashMap<>();
        public volatile String uniqueName;
        public volatile String saslGuid;
        private volatile boolean running = true;
        private Thread readerThread;
        public volatile Thread lastHandlerThread;
        /** The last reply this connection received, kept so the spike can capture raw wire bytes. */
        public volatile Msg lastReply;

        private Conn(String address, SocketChannel ch) { this.address = address; this.ch = ch; }

        /** Parse "unix:path=/run/user/1000/bus,guid=..." (abstract= is rejected: see class doc). */
        static String unixPathOf(String address) {
            for (String part : address.split(";")) {
                if (!part.startsWith("unix:")) continue;
                String path = null;
                for (String kv : part.substring(5).split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String k = kv.substring(0, eq), val = kv.substring(eq + 1);
                    if (k.equals("path")) path = val;
                    else if (k.equals("abstract")) throw new IllegalArgumentException(
                            "abstract unix socket '" + val + "' — java.net.UnixDomainSocketAddress is filesystem-path only");
                }
                if (path != null) return path;
            }
            throw new IllegalArgumentException("no unix:path= transport in address: " + address);
        }

        static Conn open(String address) throws IOException {
            String path = unixPathOf(address);
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            Conn c;
            try {
                ch.connect(UnixDomainSocketAddress.of(path));
                c = new Conn(address, ch);
                c.auth();
            } catch (IOException | RuntimeException e) {
                // A socket that connected and then failed its handshake is still a descriptor; one
                // left behind per failed join is how a retry loop runs a process out of them.
                try { ch.close(); } catch (IOException ignored) { }
                throw e;
            }
            // The reader thread must be running before the first call(): call() parks on a queue
            // that only the reader thread ever fills, so calling Hello first would deadlock until
            // the timeout. (It did, on the first live run.)
            c.start();
            return c;
        }

        /**
         * A connection with no threads of its own: the thread that opens it writes and reads on it,
         * through {@link #callHere}, {@link #writeHere} and {@link #readHere}, and nothing else may.
         *
         * <p>For a connection whose whole life is one thread waiting on the next message — the
         * accessibility switch's watch, which is the one thread a process keeps when nothing is
         * reading (ADR 039 §6). A reader and a writer thread for it would be two more.
         *
         * @param address the bus address
         * @return the authenticated connection; {@code Hello} not yet sent
         * @throws IOException when the socket or the handshake fails, with the socket closed
         */
        static Conn openOnThisThread(String address) throws IOException {
            String path = unixPathOf(address);
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                ch.connect(UnixDomainSocketAddress.of(path));
                Conn c = new Conn(address, ch);
                c.auth();
                return c;
            } catch (IOException | RuntimeException e) {
                try { ch.close(); } catch (IOException ignored) { }
                throw e;
            }
        }

        /**
         * Writes one message now, on the calling thread. Only on a connection opened with
         * {@link #openOnThisThread}.
         *
         * @return the serial it was sent with
         */
        int writeHere(Msg m) throws IOException {
            synchronized (writeLock) {
                int s = serial.getAndIncrement();
                rawWrite(m.marshal(s));
                if (TRACE) System.err.println("[->] " + m);
                return s;
            }
        }

        /**
         * Reads the next message this thread can parse, answering an unparsable method call with its
         * error on the way. Only on a connection opened with {@link #openOnThisThread}.
         *
         * @throws IOException at the end of the stream
         */
        Msg readHere() throws IOException {
            while (true) {
                Inbound in = Inbound.of(receiveFrame());
                if (in.message() != null) {
                    if (TRACE) System.err.println("[<-] " + in.message());
                    return in.message();
                }
                System.err.println("[conn] unparsable message dropped: " + in.failure());
                if (in.refusal() != null) {
                    writeHere(in.refusal());
                }
            }
        }

        /**
         * A method call made and waited for on the calling thread. Whatever else arrives before its
         * reply — a signal, a call — is handed to {@code meanwhile} in order.
         *
         * @throws DBusError when the reply is an error
         * @throws IOException at the end of the stream
         */
        Msg callHere(Msg call, java.util.function.Consumer<Msg> meanwhile) throws IOException {
            int sent = writeHere(call);
            while (true) {
                Msg m = readHere();
                if ((m.type == METHOD_RETURN || m.type == ERROR) && m.replySerial == sent) {
                    if (m.type == ERROR) {
                        throw new DBusError(m.errorName,
                                m.body.length > 0 ? String.valueOf(m.body[0]) : "");
                    }
                    return m;
                }
                meanwhile.accept(m);
            }
        }

        /** Real uid, read from /proc/self/status; the SASL EXTERNAL identity on Linux. */
        static String uid() {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("Uid:")) return line.split("\\s+")[1];
                }
            } catch (Exception ignored) { }
            try {
                Process p = new ProcessBuilder("id", "-u").start();
                String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            } catch (Exception ignored) { }
            throw new IllegalStateException("cannot determine uid for SASL EXTERNAL");
        }

        private void rawWrite(byte[] b) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b);
            while (bb.hasRemaining()) ch.write(bb);
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            ByteBuffer one = ByteBuffer.allocate(1);
            while (true) {
                one.clear();
                if (ch.read(one) < 0) throw new IOException("EOF during SASL, got: " + sb);
                char c = (char) (one.get(0) & 0xff);
                if (c == '\n') {
                    int n = sb.length();
                    if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                    return sb.toString();
                }
                sb.append(c);
            }
        }

        /**
         * The SASL commands this client sends after its leading NUL, in order: one that expects an
         * answer ({@code AUTH EXTERNAL}) and one that ends the handshake ({@code BEGIN}).
         *
         * <p>There is no {@code NEGOTIATE_UNIX_FD} and there must never be one. The spike sent it
         * "only to see the answer"; both buses answer {@code AGREE_UNIX_FD}, and an agreed connection
         * is one the bus will route a descriptor-carrying message to — a type this reader cannot
         * parse, arriving as ancillary data {@code java.nio} cannot receive (LINUX-NEW-13).
         *
         * @param uid the numeric user id, as text
         * @return the command lines, without their CRLF
         */
        static List<String> saslCommands(String uid) {
            return List.of("AUTH EXTERNAL " + hex(uid.getBytes(StandardCharsets.US_ASCII)), "BEGIN");
        }

        /** SASL: NUL, AUTH EXTERNAL <hex uid>, OK <guid>, BEGIN. */
        private void auth() throws IOException {
            rawWrite(new byte[] { 0 });
            List<String> commands = saslCommands(uid());
            rawWrite((commands.get(0) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String line = readLine();
            if (TRACE) System.err.println("[sasl] <- " + line);
            if (line.startsWith("REJECTED")) throw new IOException("SASL EXTERNAL rejected: " + line);
            if (!line.startsWith("OK ")) throw new IOException("unexpected SASL reply: " + line);
            saslGuid = line.substring(3).trim();
            rawWrite((commands.get(1) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        private void readFully(ByteBuffer bb) throws IOException {
            while (bb.hasRemaining()) if (ch.read(bb) < 0) throw new IOException("EOF on D-Bus socket");
        }

        /**
         * The largest message a bus accepts: 2^27 bytes, headers, padding and body together.
         *
         * <p>Read, not remembered (readings/fedora-dbus-bus-facts.txt, dbus-broker 37;
         * readings/ubuntu-dbus-bus-facts.txt, dbus-daemon 1.14.10; 2026-09-15,
         * {@code scripts/a11y/linux/read-dbus-bus-facts.py} §3): on the session bus and on the
         * accessibility bus of both guests, a fixed header declaring exactly 134217728 bytes is
         * waited on and one declaring 134217736 is disconnected at once. The limits the daemons are
         * configured with are not it: both {@code session.conf} and at-spi2's
         * {@code accessibility.conf} set {@code max_message_size} to 1000000000, above what either
         * bus accepted, and dbus-broker's {@code --max-bytes} is, in its own help on the Fedora
         * guest, the "maximum number of bytes each user may allocate in the broker" — a per-user
         * quota, not a message size. A length past this one is not a message any bus relays, so
         * the stream cannot be followed past it, and nothing is allocated for it.
         */
        static final int MAX_MESSAGE = 1 << 27;

        /**
         * Read one whole message's bytes: 16 fixed bytes give the body length and the header-array
         * length. Nothing here parses what the message says, so a message this client cannot
         * understand still leaves the stream at the next one.
         *
         * @throws IOException at the end of the stream, or when the lengths cannot be a message, in
         *                     which case nothing after it can be found either
         */
        private byte[] receiveFrame() throws IOException {
            ByteBuffer head = ByteBuffer.allocate(16);
            readFully(head);
            head.flip();
            head.order(head.get(0) == 'B' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            long bodyLen = head.getInt(4) & 0xffffffffL;
            long fieldsLen = head.getInt(12) & 0xffffffffL;
            long padded = (fieldsLen + 7) & ~7L;
            long total = 16 + padded + bodyLen;
            if (total > MAX_MESSAGE) {
                throw new IOException("a " + total + "-byte message is not one D-Bus allows; the "
                        + "stream cannot be followed past it");
            }
            byte[] full = new byte[(int) total];
            System.arraycopy(head.array(), 0, full, 0, 16);
            ByteBuffer rest = ByteBuffer.wrap(full, 16, full.length - 16);
            readFully(rest);
            return full;
        }

        /** Everything waiting to be written, and the policy that decides what may wait. */
        private final Outbound outbound = new Outbound();
        private Thread writerThread;

        /** How many signals were refused because the queue was already at its bound. */
        int droppedSignals() { return outbound.dropped(); }

        /**
         * Marshals {@code m} and hands it to the writer thread. Never writes to the connection, so
         * it is safe on the reader thread and on the user-interface thread alike.
         */
        void send(Msg m) throws IOException {
            byte[] b;
            synchronized (writeLock) {
                b = m.marshal(serial.getAndIncrement());
            }
            if (TRACE) System.err.println("[->] " + m);
            outbound.offerReply(b);
        }

        /**
         * {@link #send} for an event signal, which is the one kind of message this connection may
         * refuse.
         *
         * @param m    the signal
         * @param tail whether it belongs to a publish's reserved tail, which an ordinary backlog
         *             never refuses ({@link Outbound#TAIL_BOUND})
         * @return whether it was accepted
         */
        boolean sendSignal(Msg m, boolean tail) throws IOException {
            byte[] b;
            synchronized (writeLock) {
                b = m.marshal(serial.getAndIncrement());
            }
            return tail ? outbound.offerTailSignal(b) : outbound.offerSignal(b);
        }

        /** Drains the outbound queue. The one thread that writes to this connection. */
        private void writeLoop() {
            while (running) {
                byte[] b;
                try {
                    b = outbound.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    rawWrite(b);
                } catch (IOException e) {
                    if (running) System.err.println("[conn] write failed: " + e);
                    lost();
                    return;
                } finally {
                    outbound.written();
                }
            }
        }

        private final Object lostLock = new Object();
        private Runnable onLost;
        private boolean lostUnheard;
        private boolean lostOnce;

        /**
         * What to run, once, when this connection stops working without having been closed: its
         * reader reached the end of the stream or stopped, or its writer could not write. A
         * connection whose reader has stopped answers nobody, and one whose writer has stopped
         * answers into nothing; the owner has to stop treating it as a connection.
         *
         * @param whenLost run on the thread that noticed; must not block
         */
        void onLost(Runnable whenLost) {
            boolean already;
            synchronized (lostLock) {
                onLost = whenLost;
                already = lostUnheard;
                lostUnheard = false;
            }
            if (already) {
                // Lost before anyone was listening for it.
                whenLost.run();
            }
        }

        private void lost() {
            Runnable whenLost;
            synchronized (lostLock) {
                if (!running || lostOnce) {
                    return;
                }
                lostOnce = true;
                whenLost = onLost;
                lostUnheard = whenLost == null;
            }
            close();
            if (whenLost != null) {
                whenLost.run();
            }
        }

        void export(String path, Handler h) { exports.put(path, h); }
        /** Answers calls on any path with no exact export — used to serve the node tree. */
        void exportFallback(Handler h) { fallback = h; }
        public java.util.Set<String> exportedPaths() { return exports.keySet(); }
        private volatile Handler fallback;

        /** Blocking method call. Throws DBusError on an ERROR reply. */
        Msg call(Msg m, long timeoutMs) throws IOException {
            ArrayBlockingQueue<Msg> q = new ArrayBlockingQueue<>(1);
            Msg reply;
            synchronized (writeLock) {
                int s = serial.getAndIncrement();
                pending.put(s, q);
                byte[] b = m.marshal(s);
                if (TRACE) System.err.println("[->] " + m);
                rawWrite(b);
            }
            try {
                reply = q.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for reply to " + m.member, e);
            }
            pending.remove(m.serial);
            if (reply == null) throw new IOException("timeout after " + timeoutMs + " ms waiting for reply to "
                    + m.iface + "." + m.member + " on " + m.path);
            lastReply = reply;
            if (reply.type == ERROR) throw new DBusError(reply.errorName,
                    reply.body.length > 0 ? String.valueOf(reply.body[0]) : "");
            return reply;
        }

        Object[] callArgs(String dest, String path, String iface, String member, String sig, Object... args)
                throws IOException {
            return call(Msg.call(dest, path, iface, member, sig, args), 15000).body;
        }

        /** org.freedesktop.DBus.Hello — must be the first call; returns our unique name. */
        String hello() throws IOException {
            Object[] r = callArgs("org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "Hello", null);
            uniqueName = (String) r[0];
            return uniqueName;
        }

        /** Idempotent: the reader thread is started by open(), before anything can call(). */
        synchronized void start() {
            if (readerThread != null) return;
            // The writer first: the reader may answer a call before start() returns, and its reply
            // has to find a thread already draining rather than a queue nobody reads.
            writerThread = Threads.daemon("limn-a11y-dbus-writer", this::writeLoop);
            readerThread = Threads.daemon("limn-a11y-dbus-reader", this::loop);
        }

        /** The thread that answers inbound calls; nothing else may write to the connection. */
        Thread readerThread() { return readerThread; }

        /** The thread that performs every write. */
        Thread writerThread() { return writerThread; }

        /**
         * Pump messages until closed. Replies go to waiters; calls go to exported handlers.
         *
         * <p><b>Nothing a peer sends may end this loop except the end of the stream.</b> It used to
         * guard only the read: a message whose body did not parse — a type this client does not
         * speak, a header variant it did not expect — threw out of the loop, the reader thread died,
         * and the connection stayed open and embedded with no one answering it, which is exactly
         * what at-spi2-core 2.60 hides from the desktop (LINUX-NEW-13). An unparsable message is
         * logged and, when it was a method call whose header could be read, refused with an error
         * reply; the loop goes on to the next message.
         */
        void loop() {
            try {
                while (running) {
                    byte[] frame;
                    try {
                        frame = receiveFrame();
                    } catch (IOException e) {
                        if (running) System.err.println("[conn] read failed: " + e);
                        return;
                    }
                    Inbound in = Inbound.of(frame);
                    if (in.message() == null) {
                        System.err.println("[conn] unparsable message dropped: " + in.failure());
                        if (in.refusal() != null) {
                            try {
                                send(in.refusal());
                            } catch (IOException | RuntimeException e) {
                                System.err.println("[conn] could not refuse it: " + e);
                            }
                        }
                        continue;
                    }
                    if (TRACE) System.err.println("[<-] " + in.message());
                    try {
                        dispatch(in.message());
                    } catch (Exception e) {
                        System.err.println("[conn] dispatch failed for " + in.message() + ": " + e);
                        e.printStackTrace();
                    }
                }
            } catch (RuntimeException e) {
                if (running) System.err.println("[conn] reader stopped: " + e);
            } finally {
                lost();
            }
        }

        /**
         * One message as it came off the wire: parsed, or not, with the error reply an unparsable
         * method call is owed.
         *
         * @param message the message, or null when it could not be parsed
         * @param refusal the error reply to send for an unparsable method call that expects one, or
         *                null
         * @param failure why it could not be parsed, or null
         */
        record Inbound(Msg message, Msg refusal, RuntimeException failure) {
            static Inbound of(byte[] frame) {
                try {
                    return new Inbound(Msg.parse(frame), null, null);
                } catch (RuntimeException failure) {
                    Msg header;
                    try {
                        header = Msg.parseHeader(frame);
                    } catch (RuntimeException unreadable) {
                        return new Inbound(null, null, failure);
                    }
                    boolean owed = header.type == METHOD_CALL
                            && (header.flags & NO_REPLY_EXPECTED) == 0 && header.sender != null;
                    return new Inbound(null, owed ? Msg.err(header, INVALID_ARGS,
                            "this application cannot read a message of signature '"
                                    + header.signature + "': " + failure) : null, failure);
                }
            }
        }

        void dispatch(Msg m) throws Exception {
            if (m.type == METHOD_RETURN || m.type == ERROR) {
                ArrayBlockingQueue<Msg> q = pending.remove(m.replySerial);
                if (q != null) q.offer(m);
                else if (TRACE) System.err.println("[conn] orphan reply " + m);
                return;
            }
            if (m.type == SIGNAL) {
                if (TRACE) System.err.println("[signal] " + m);
                return;
            }
            lastHandlerThread = Thread.currentThread();
            Handler h = exports.get(m.path);
            if (h == null) h = fallback;
            Msg reply = replyFor(h, this, m);
            if ((m.flags & NO_REPLY_EXPECTED) != 0) {
                return;
            }
            byte[] bytes;
            synchronized (writeLock) {
                try {
                    bytes = reply.marshal(serial.getAndIncrement());
                } catch (RuntimeException e) {
                    // A reply whose body does not match its own signature cannot be sent, and the
                    // caller is still owed one.
                    System.err.println("[conn] reply to " + m + " could not be written: " + e);
                    bytes = Msg.err(m, FAILED, "the reply could not be written: " + e)
                            .marshal(serial.getAndIncrement());
                }
            }
            if (TRACE) System.err.println("[->] " + reply);
            outbound.offerReply(bytes);
        }

        /*
         * The three error names this connection answers with. Each is a NUL-terminated string in
         * the installed libdbus on both guests, and the bus itself answers InvalidArgs to a call
         * with arguments of the wrong type and UnknownMethod to a member it does not have
         * (readings/fedora-dbus-bus-facts.txt, libdbus 1.16.2, dbus-broker 37;
         * readings/ubuntu-dbus-bus-facts.txt, libdbus 1.14.10, dbus-daemon 1.14.10; 2026-09-15,
         * scripts/a11y/linux/read-dbus-bus-facts.py section 1).
         */

        /** The error a method call gets when its handler fails for a reason of its own. */
        static final String FAILED = "org.freedesktop.DBus.Error.Failed";

        /** The error a method call gets when its arguments are not the ones its member takes. */
        static final String INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs";

        /** The error a method call gets when nothing here answers its member on its path. */
        static final String UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod";

        /**
         * The reply a method call is owed, whatever its handler does: the handler's own, an
         * {@code UnknownMethod} when there is no handler or it declines, and an error when it
         * throws. Never null and never a throw.
         *
         * <p>A handler that threw used to leave the caller with nothing at all (LINUX-NEW-9), and
         * libatspi waits out a newly added application's whole call timeout — up to fifteen seconds
         * — before it pings and declares the process hung. Arguments of the wrong number or type
         * (an index past the body, a cast that does not hold) are the caller's mistake and answer
     * {@code InvalidArgs}; anything else is this application's and answers {@code Failed}. Both
         * carry the exception's own text, and both are logged.
         *
         * <p>"Anything else" includes the errors a handler's own code raises — a recursion that
         * overflows the stack, a failed assertion, a class that fails to load — and not only
         * exceptions. It used to catch {@code Exception} alone, so such an error left this method, ended
         * the reader thread and with it the connection, and each client call that provoked it cost a
         * join (the linux-A review). The errors that say the virtual machine itself is failing
         * ({@link OutOfMemoryError}, {@link InternalError}) are not answered here: they still end the
         * reader, and the join's back-off decides when to try again.
         *
         * @param handler what answers the call's path, or null
         * @param conn    the connection it arrived on
         * @param call    the method call
         * @return the reply to send
         */
        static Msg replyFor(Handler handler, Conn conn, Msg call) {
            Msg reply = null;
            if (handler != null) {
                try {
                    reply = handler.handle(conn, call);
                } catch (IndexOutOfBoundsException | ClassCastException e) {
                    System.err.println("[conn] " + call.iface + "." + call.member + " on "
                            + call.path + " refused its arguments: " + e);
                    return Msg.err(call, INVALID_ARGS, call.iface + "." + call.member + " cannot "
                            + "take the arguments '" + call.signature + "': " + e);
                } catch (Exception | StackOverflowError | AssertionError | LinkageError e) {
                    System.err.println("[conn] " + call.iface + "." + call.member + " on "
                            + call.path + " failed: " + e);
                    return Msg.err(call, FAILED, call.iface + "." + call.member + " failed: " + e);
                }
            }
            if (reply == null) {
                reply = Msg.err(call, UNKNOWN_METHOD,
                        "no handler for " + call.iface + "." + call.member + " on " + call.path);
            }
            return reply;
        }

        /** Drive the loop on the caller's thread until the deadline (used by the probe's main). */
        void pump(long millis) {
            long end = System.nanoTime() + millis * 1_000_000L;
            while (running && System.nanoTime() < end) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }

        @Override public void close() {
            running = false;
            if (writerThread != null) writerThread.interrupt();
            try { ch.close(); } catch (IOException ignored) { }
        }
    }
}
