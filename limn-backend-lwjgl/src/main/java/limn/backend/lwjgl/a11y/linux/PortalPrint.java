package limn.backend.lwjgl.a11y.linux;

import limn.backend.lwjgl.transfer.Ffi;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The desktop's own print dialog, through the XDG desktop portal's
 * {@code org.freedesktop.portal.Print}: {@code PreparePrint} shows the dialog,
 * parented to the window where the platform can name it, and answers with the settings and a token;
 * {@code Print} then hands the PDF to the desktop's print service as a file descriptor, with that
 * token, so the dialog is not shown a second time.
 *
 * <p><b>The descriptor.</b> The PDF travels as a descriptor of a memory file ({@code memfd_create}),
 * written whole and wound back to its start, which is what the portal's backends read the job from;
 * where the system has no memory files, an unlinked temporary file. It is sent with the call as
 * {@code SCM_RIGHTS} over a connection of this request's own, made through the C library
 * ({@link UnixSocketWire}) because java.nio cannot pass one, and the only connection of the backend
 * that negotiates descriptor passing. Both calls go over it, so the token is presented by the same
 * peer that received it.
 *
 * <p>One request is one connection on the calling thread, which the caller makes a thread of its
 * own, as {@link PortalFileChooser} does: each {@code Response} is subscribed to before its call, on
 * the request path the {@code handle_token} option fixes in advance.
 */
public final class PortalPrint {

    static final String PRINT = "org.freedesktop.portal.Print";
    private static final String DRIVER = "org.freedesktop.DBus";
    private static final String DRIVER_PATH = "/org/freedesktop/DBus";

    /**
     * What the portal answered: {@code Response}'s code, or that nothing could be asked.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Outcome {
        /** Both calls answered 0: the desktop took the job. */
        PRINTED,
        /** A dialog was closed: code 1 to either call. */
        CANCELLED,
        /** The portal or its print interface is not there to ask. */
        UNAVAILABLE,
        /** Code 2, or a call refused; the reason is in the exception's message. */
        FAILED
    }

    /** The paper's names in the portal's page setup, by their portrait size in millimetres. */
    private static final Object[][] PAPERS = {
            {"iso_a3", "A3", 297.0, 420.0},
            {"iso_a4", "A4", 210.0, 297.0},
            {"iso_a5", "A5", 148.0, 210.0},
            {"iso_b5", "B5", 176.0, 250.0},
            {"jis_b5", "JIS B5", 182.0, 257.0},
            {"na_letter", "US Letter", 215.9, 279.4},
            {"na_legal", "US Legal", 215.9, 355.6},
            {"na_executive", "Executive", 184.15, 266.7},
            {"na_ledger", "Tabloid", 279.4, 431.8},
    };

    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final String PORTAL = PortalFileChooser.PORTAL;
    private static final String PORTAL_PATH = PortalFileChooser.PORTAL_PATH;
    private static final String REQUEST = PortalFileChooser.REQUEST;

    private PortalPrint() {
    }

    /**
     * Reads the print interface's {@code version} property, on the calling thread.
     *
     * @param address the session bus address
     * @return the version, or 0 when the portal has no print interface
     * @throws IOException when the bus cannot be reached
     */
    public static int version(String address) throws IOException {
        DBus.Conn connection = DBus.Conn.openOnThisThread(address);
        try {
            Consumer<DBus.Msg> ignore = m -> { };
            connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), ignore);
            DBus.Msg reply = connection.callHere(DBus.Msg.call(PORTAL, PORTAL_PATH, DBus.I_PROPS_NAME, "Get", "ss",
                    PRINT, "version"), ignore);
            Object value = reply.body.length > 0 ? reply.body[0] : null;
            if (value instanceof DBus.Variant variant) {
                value = variant.value;
            }
            return value instanceof Number number ? number.intValue() : 0;
        } catch (DBus.DBusError absent) {
            // No portal on the bus (ServiceUnknown), or one without the interface (InvalidArgs, UnknownProperty).
            return 0;
        } finally {
            connection.close();
        }
    }

    /**
     * Asks the portal and waits for both answers, on the calling thread.
     *
     * @param address     the session bus address
     * @param parent      the portal's name for the owner window ({@code "x11:1c00007"}), or {@code ""}
     * @param title       the dialog's title and the job's name
     * @param pdf         the document
     * @param paperWidth  the paper's width in points, 0 when not known
     * @param paperHeight its height in points, 0 when not known
     * @param log         where a refusal is described, for the caller's log
     * @return what became of it
     */
    public static Outcome print(String address, String parent, String title, byte[] pdf, float paperWidth,
                                float paperHeight, Consumer<String> log) {
        DBus.Conn connection;
        try {
            connection = DBus.Conn.openOnThisThread(address, UnixSocketWire.connect(DBus.Conn.unixPathOf(address)),
                    true);
        } catch (IOException | RuntimeException | LinkageError unreachable) {
            log.accept("the session bus cannot be reached with descriptor passing: " + unreachable);
            return Outcome.UNAVAILABLE;
        }
        int descriptor = -1;
        try {
            List<DBus.Msg> early = new ArrayList<>();
            Consumer<DBus.Msg> meanwhile = early::add;
            DBus.Msg hello = connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "Hello", null), meanwhile);
            String unique = String.valueOf(hello.body[0]);

            String prepareToken = token();
            String prepareHandle = handle(unique, prepareToken);
            subscribe(connection, prepareHandle, meanwhile);
            Map<String, DBus.Variant> pageSetup = pageSetup(paperWidth, paperHeight);
            try {
                connection.callHere(DBus.Msg.call(PORTAL, PORTAL_PATH, PRINT, "PreparePrint", "ssa{sv}a{sv}a{sv}",
                        parent, title, new LinkedHashMap<String, DBus.Variant>(), pageSetup,
                        prepareOptions(prepareToken)), meanwhile);
            } catch (DBus.DBusError refused) {
                if (refused.name.endsWith(".ServiceUnknown") || refused.name.endsWith(".UnknownInterface")
                        || refused.name.endsWith(".UnknownMethod")) {
                    log.accept("the desktop portal has no print dialog: " + refused.getMessage());
                    return Outcome.UNAVAILABLE;
                }
                throw refused;
            }
            Object[] prepared = await(connection, prepareHandle, early);
            int code = ((Number) prepared[0]).intValue();
            if (code == 2) {
                // The dialog was dismissed some other way than its Cancel button: measured on GNOME
                // (the GTK portal backend), Escape and the window's close answer 2, the button 1. No
                // job reached the print service, so it is the user's cancel either way.
                return Outcome.CANCELLED;
            }
            if (code != 0) {
                return answer(code, "PreparePrint", log);
            }
            Long printToken = tokenIn(prepared[1]);
            if (printToken == null) {
                log.accept("PreparePrint answered with no token: " + DBus.fmt(prepared[1]));
                return Outcome.FAILED;
            }

            descriptor = descriptorOf(pdf);
            String token = token();
            String handle = handle(unique, token);
            subscribe(connection, handle, meanwhile);
            DBus.Msg call = DBus.Msg.call(PORTAL, PORTAL_PATH, PRINT, "Print", "ssha{sv}", parent, title, 0,
                    printOptions(token, printToken));
            int sent = connection.writeHere(call, descriptor);
            DBus.Msg reply = replyTo(connection, sent, early);
            if (reply.type == DBus.ERROR) {
                log.accept("Print was refused: " + reply.errorName + " "
                        + (reply.body.length > 0 ? reply.body[0] : ""));
                return Outcome.FAILED;
            }
            Object[] printed = await(connection, handle, early);
            return answer(((Number) printed[0]).intValue(), "Print", log);
        } catch (DBus.DBusError refused) {
            log.accept("the print portal refused the request: " + refused.getMessage());
            return Outcome.FAILED;
        } catch (IOException | RuntimeException failed) {
            log.accept("the print portal could not be asked: " + failed);
            return Outcome.FAILED;
        } finally {
            if (descriptor >= 0) {
                // The bus holds its own copy once the message is sent.
                Ffi.call(UnixSocketWire.C.CLOSE, "i>i", descriptor);
            }
            connection.close();
        }
    }

    private static Outcome answer(int code, String call, Consumer<String> log) {
        if (code == 1) {
            return Outcome.CANCELLED;
        }
        if (code != 0) {
            log.accept(call + " ended with response " + code);
            return Outcome.FAILED;
        }
        return Outcome.PRINTED;
    }

    private static String token() {
        return "limn" + ProcessHandle.current().pid() + "_p" + TOKENS.incrementAndGet();
    }

    /** The request path the portal will use for {@code token}: the sender's name with '.' as '_'. */
    static String handle(String unique, String token) {
        return PORTAL_PATH + "/request/" + unique.substring(1).replace('.', '_') + "/" + token;
    }

    private static void subscribe(DBus.Conn connection, String handle, Consumer<DBus.Msg> meanwhile)
            throws IOException {
        connection.callHere(DBus.Msg.call(DRIVER, DRIVER_PATH, DRIVER, "AddMatch", "s",
                "type='signal',interface='" + REQUEST + "',member='Response',path='" + handle + "'"), meanwhile);
    }

    /** The {@code Response(u, a{sv})} for {@code handle}: from what came early, or read until it does. */
    private static Object[] await(DBus.Conn connection, String handle, List<DBus.Msg> early) throws IOException {
        for (int i = 0; i < early.size(); i++) {
            DBus.Msg m = early.get(i);
            if (isResponse(m, handle)) {
                early.remove(i);
                return m.body;
            }
        }
        while (true) {
            DBus.Msg m = connection.readHere();
            if (isResponse(m, handle)) {
                return m.body;
            }
            answerCall(connection, m);
        }
    }

    /** The reply to the call sent with {@code serial}, keeping whatever else comes before it. */
    private static DBus.Msg replyTo(DBus.Conn connection, int serial, List<DBus.Msg> early) throws IOException {
        while (true) {
            DBus.Msg m = connection.readHere();
            if ((m.type == DBus.METHOD_RETURN || m.type == DBus.ERROR) && m.replySerial == serial) {
                return m;
            }
            if (m.type == DBus.SIGNAL) {
                early.add(m);
            } else {
                answerCall(connection, m);
            }
        }
    }

    private static void answerCall(DBus.Conn connection, DBus.Msg m) throws IOException {
        if (m.type == DBus.METHOD_CALL && (m.flags & DBus.NO_REPLY_EXPECTED) == 0) {
            connection.writeHere(DBus.Conn.replyFor(null, connection, m));
        }
    }

    static boolean isResponse(DBus.Msg m, String handle) {
        return m.type == DBus.SIGNAL && "Response".equals(m.member) && REQUEST.equals(m.iface)
                && handle.equals(m.path) && m.body.length >= 2 && m.body[0] instanceof Number;
    }

    /** The {@code token} of {@code PreparePrint}'s results, or null when it has none. */
    static @Nullable Long tokenIn(Object results) {
        if (!(results instanceof Map<?, ?> map)) {
            return null;
        }
        Object token = map.get("token");
        if (token instanceof DBus.Variant variant) {
            token = variant.value;
        }
        return token instanceof Number number ? Integer.toUnsignedLong(number.intValue()) : null;
    }

    /** {@code PreparePrint}'s options: the request's token, and a dialog modal to its parent. */
    static Map<String, DBus.Variant> prepareOptions(String handleToken) {
        Map<String, DBus.Variant> options = new LinkedHashMap<>();
        options.put("handle_token", DBus.v("s", handleToken));
        options.put("modal", DBus.v("b", true));
        return options;
    }

    /** {@code Print}'s options: the request's token, modal, and the token {@code PreparePrint} gave. */
    static Map<String, DBus.Variant> printOptions(String handleToken, long printToken) {
        Map<String, DBus.Variant> options = prepareOptions(handleToken);
        options.put("token", DBus.v("u", (int) printToken));
        return options;
    }

    /**
     * The page setup the dialog starts from: empty when the paper is not known, else the paper's
     * size in millimetres (portrait, as the portal's page setup keeps it), its orientation, zero
     * margins (the document's margins are on its pages already), and the paper's names when it is
     * a standard one within half a millimetre.
     */
    static Map<String, DBus.Variant> pageSetup(float widthPoints, float heightPoints) {
        Map<String, DBus.Variant> setup = new LinkedHashMap<>();
        if (!(widthPoints > 0 && heightPoints > 0)) {
            return setup;
        }
        double shortSide = millimetres(Math.min(widthPoints, heightPoints));
        double longSide = millimetres(Math.max(widthPoints, heightPoints));
        for (Object[] paper : PAPERS) {
            if (Math.abs((double) paper[2] - shortSide) <= 0.5 && Math.abs((double) paper[3] - longSide) <= 0.5) {
                setup.put("PPDName", DBus.v("s", ""));
                setup.put("Name", DBus.v("s", paper[0]));
                setup.put("DisplayName", DBus.v("s", paper[1]));
                shortSide = (double) paper[2];
                longSide = (double) paper[3];
                break;
            }
        }
        setup.put("Width", DBus.v("d", shortSide));
        setup.put("Height", DBus.v("d", longSide));
        setup.put("MarginTop", DBus.v("d", 0.0));
        setup.put("MarginBottom", DBus.v("d", 0.0));
        setup.put("MarginLeft", DBus.v("d", 0.0));
        setup.put("MarginRight", DBus.v("d", 0.0));
        setup.put("Orientation", DBus.v("s", widthPoints > heightPoints ? "landscape" : "portrait"));
        return setup;
    }

    private static double millimetres(float points) {
        return Math.round(points * 25.4 / 72 * 100) / 100.0;
    }

    /** {@code MFD_CLOEXEC}. */
    private static final int MFD_CLOEXEC = 1;
    private static final int O_RDONLY_CLOEXEC = 0x80000;

    /**
     * A descriptor holding the PDF from its start: a memory file, or where the C library has none an
     * unlinked temporary file. The caller closes it.
     */
    static int descriptorOf(byte[] pdf) throws IOException {
        long memfd = UnixSocketWire.C.LIBC.getFunctionAddress("memfd_create");
        if (memfd != 0) {
            int fd;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                fd = (int) Ffi.call(memfd, "pi>i", MemoryUtil.memAddress(stack.ASCII("limn-print")),
                        MFD_CLOEXEC);
            }
            if (fd >= 0) {
                try {
                    writeAll(fd, pdf);
                    if (Ffi.call(UnixSocketWire.C.LSEEK, "ipi>p", fd, 0, 0) < 0) {
                        throw new IOException("lseek() failed, errno " + UnixSocketWire.C.errno());
                    }
                    return fd;
                } catch (IOException | RuntimeException failed) {
                    Ffi.call(UnixSocketWire.C.CLOSE, "i>i", fd);
                    throw failed;
                }
            }
        }
        Path file = Files.createTempFile("limn-print", ".pdf");
        try {
            Files.write(file, pdf);
            long open = UnixSocketWire.C.LIBC.getFunctionAddress("open");
            int fd;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                fd = (int) Ffi.call(open, "pi>i", MemoryUtil.memAddress(stack.UTF8(file.toString())),
                        O_RDONLY_CLOEXEC);
            }
            if (fd < 0) {
                throw new IOException("open(" + file + ") failed, errno " + UnixSocketWire.C.errno());
            }
            return fd;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void writeAll(int fd, byte[] bytes) throws IOException {
        ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
        try {
            direct.put(bytes).flip();
            while (direct.hasRemaining()) {
                long n = Ffi.call(UnixSocketWire.C.WRITE, "ipp>p", fd,
                        MemoryUtil.memAddress(direct), direct.remaining());
                if (n < 0) {
                    if (UnixSocketWire.C.errno() == UnixSocketWire.EINTR) {
                        continue;
                    }
                    throw new IOException("write() to the memory file failed, errno " + UnixSocketWire.C.errno());
                }
                direct.position(direct.position() + (int) n);
            }
        } finally {
            MemoryUtil.memFree(direct);
        }
    }
}
