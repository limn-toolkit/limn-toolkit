package limn.backend.lwjgl.transfer;

import limn.transfer.TransferGoneException;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.system.MemoryUtil.nmemAlloc;
import static org.lwjgl.system.MemoryUtil.nmemCalloc;
import static org.lwjgl.system.MemoryUtil.nmemFree;

/**
 * The X11 clipboard: the {@code CLIPBOARD} selection, owned and converted by a hidden window on a
 * connection of our own.
 *
 * <p><b>A second connection, and one thread that makes every call on it.</b> GLFW's connection is
 * GLFW's: its event loop reads every event on it, the selection events included, and hands none
 * on. Ours has a thread that waits on the connection and on a pipe that wakes it; the UI thread
 * only queues work and writes a byte. No two threads ever touch one display, so nothing here
 * depends on {@code XInitThreads} having been the first Xlib call of the process, which it was
 * not.
 *
 * <p>What does not fit one request crosses by {@code INCR}, both ways. XFixes tells of a new
 * owner, which is when the types are asked for again, so {@link #types()} never has to ask.
 *
 * <p>An X error on our display (a requestor that closed mid-transfer) is ignored; one on any
 * other display goes to the handler that was there, as before.
 */
public final class X11Clipboard implements NativeClipboard {

    private static final System.Logger LOG = System.getLogger(X11Clipboard.class.getName());

    private static final int SELECTION_CLEAR = 29;
    private static final int SELECTION_REQUEST = 30;
    private static final int SELECTION_NOTIFY = 31;
    private static final int PROPERTY_NOTIFY = 28;
    private static final int CLIENT_MESSAGE = 33;
    private static final int GENERIC_EVENT = 35;
    private static final long PROPERTY_CHANGE_MASK = 1L << 22;
    private static final int PROPERTY_NEW_VALUE = 0;
    private static final int PROPERTY_DELETE = 1;
    private static final long XA_ATOM = 4;
    private static final int EVENT_BYTES = 192;

    /** One request's worth: well under the smallest maximum request (256 KiB) of any server. */
    private static final int CHUNK = 128 << 10;
    private static final int MAX_BYTES = 512 << 20;
    private static final long TRANSFER_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int READ_SLOTS = 8;

    private final SharedLibrary x11;
    private final SharedLibrary xfixes;
    private final SharedLibrary libc;
    private final Map<String, Long> functions = new HashMap<>();
    private final long display;
    private final long window;
    private final int connection;
    private final int fixesEvent;
    private final long clipboard;
    private final long targets;
    private final long incr;
    private final long targetsProperty;
    private final long[] readProperties = new long[READ_SLOTS];
    private final Map<String, Long> atoms = new HashMap<>();
    private final Map<Long, String> atomNames = new HashMap<>();
    private final Callback errorHandler;
    private final long previousErrorHandler;
    private final int wakeRead;
    private final int wakeWrite;
    private final FileOutputStream wake;
    private final FileInputStream woken;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    // Everything below is the X thread's alone, but for the three volatile answers.
    private Function<String, byte[]> ownBytes = type -> null;
    private List<String> ownTypes = List.of();
    private final Read[] reads = new Read[READ_SLOTS];
    private final List<Send> sends = new ArrayList<>();

    private volatile List<String> offered = List.of();
    private volatile boolean owner;
    private volatile Runnable changeListener = () -> { };
    private volatile boolean closing;
    /** The drags this connection speaks for, once a window asked for them; the X thread reads it. */
    private volatile X11Drag drag;
    /** The pinches over this program's windows, made the first time a window asks. */
    private volatile X11Pinch pinch;
    /** How long the thread sleeps with nothing to do: shorter while a drag of ours is followed. */
    volatile int pollMillis = 250;

    /** A conversion asked of another program, waiting for its answer in a property of ours. */
    private static final class Read {
        final CompletableFuture<byte[]> result;
        final String type;
        final long deadline = System.nanoTime() + TRANSFER_TIMEOUT_NANOS;
        ByteArrayOutputStream incremental;

        Read(CompletableFuture<byte[]> result, String type) {
            this.result = result;
            this.type = type;
        }
    }

    /** What is left of an INCR transfer to another program's window. */
    private static final class Send {
        final long requestor;
        final long property;
        final long target;
        final byte[] bytes;
        int sent;
        long deadline = System.nanoTime() + TRANSFER_TIMEOUT_NANOS;

        Send(long requestor, long property, long target, byte[] bytes) {
            this.requestor = requestor;
            this.property = property;
            this.target = target;
            this.bytes = bytes;
        }
    }

    /**
     * @throws RuntimeException when Xlib or XFixes is absent, or no display opens; the caller then
     *                          keeps GLFW's text clipboard
     */
    public X11Clipboard() {
        x11 = APIUtil.apiCreateLibrary("libX11.so.6");
        xfixes = APIUtil.apiCreateLibrary("libXfixes.so.3");
        libc = APIUtil.apiCreateLibrary("libc.so.6");
        display = x("XOpenDisplay", "p>p", NULL);
        if (display == NULL) {
            throw new IllegalStateException("XOpenDisplay failed");
        }
        long root = x("XDefaultRootWindow", "p>p", display);
        window = x("XCreateSimpleWindow", "ppiiiiipp>p", display, root, -10, -10, 1, 1, 0, 0, 0);
        x("XSelectInput", "ppp>i", display, window, PROPERTY_CHANGE_MASK);
        connection = (int) x("XConnectionNumber", "p>i", display);
        clipboard = atom("CLIPBOARD");
        targets = atom("TARGETS");
        incr = atom("INCR");
        targetsProperty = atom("LIMN_CLIPBOARD_TARGETS");
        for (int i = 0; i < READ_SLOTS; i++) {
            readProperties[i] = atom("LIMN_CLIPBOARD_" + i);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bases = stack.ncalloc(4, 2, 4);
            if (Ffi.call(APIUtil.apiGetFunctionAddress(xfixes, "XFixesQueryExtension"), "ppp>i",
                    display, bases, bases + 4) == 0) {
                x("XCloseDisplay", "p>i", display);
                throw new IllegalStateException("the X server has no XFixes");
            }
            fixesEvent = memGetInt(bases);
        }
        // 1: XFixesSetSelectionOwnerNotifyMask
        Ffi.call(APIUtil.apiGetFunctionAddress(xfixes, "XFixesSelectSelectionInput"), "pppp>v",
                display, window, clipboard, 1);

        long[] previous = new long[1];
        errorHandler = Ffi.callback("pp>i", args -> {
            if (args[0] != display && previous[0] != NULL) {
                return Ffi.call(previous[0], "pp>i", args[0], args[1]);
            }
            return 0;
        });
        previousErrorHandler = x("XSetErrorHandler", "p>p", errorHandler.address());
        previous[0] = previousErrorHandler;

        int[] pipe = pipe();
        wakeRead = pipe[0];
        wakeWrite = pipe[1];
        try {
            wake = new FileOutputStream("/proc/self/fd/" + wakeWrite);
            woken = new FileInputStream("/proc/self/fd/" + wakeRead);
        } catch (IOException noProc) {
            x("XCloseDisplay", "p>i", display);
            throw new IllegalStateException("/proc/self/fd is not readable", noProc);
        }
        commands.add(this::askTargets);
        thread = new Thread(this::run, "limn-x11-clipboard");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    long x(String name, String signature, long... args) {
        long function = functions.computeIfAbsent(name, n -> APIUtil.apiGetFunctionAddress(x11, n));
        return Ffi.call(function, signature, args);
    }

    long atom(String name) {
        Long known = atoms.get(name);
        if (known != null) {
            return known;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long atom = x("XInternAtom", "ppi>p", display, memAddress(stack.UTF8(name)), 0);
            atoms.put(name, atom);
            atomNames.put(atom, name);
            return atom;
        }
    }

    String name(long atom) {
        String known = atomNames.get(atom);
        if (known != null) {
            return known;
        }
        long chars = x("XGetAtomName", "pp>p", display, atom);
        String name = chars == NULL ? "" : memUTF8(chars);
        if (chars != NULL) {
            x("XFree", "p>i", chars);
        }
        atomNames.put(atom, name);
        atoms.putIfAbsent(name, atom);
        return name;
    }

    private int[] pipe() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long fds = stack.ncalloc(4, 2, 4);
            if (Ffi.call(APIUtil.apiGetFunctionAddress(libc, "pipe"), "p>i", fds) != 0) {
                throw new IllegalStateException("pipe() failed");
            }
            return new int[] {memGetInt(fds), memGetInt(fds + 4)};
        }
    }

    void post(Runnable command) {
        commands.add(command);
        try {
            wake.write(1);
        } catch (IOException closed) {
            // The thread is gone, and with it whoever would have run the command.
        }
    }

    // ---- the X thread -----------------------------------------------------------------------------

    private void run() {
        long event = nmemCalloc(1, EVENT_BYTES);
        long pollfds = nmemCalloc(2, 8);
        long poll = APIUtil.apiGetFunctionAddress(libc, "poll");
        try {
            while (!closing) {
                while (x("XPending", "p>i", display) > 0) {
                    x("XNextEvent", "pp>i", display, event);
                    handle(event);
                }
                for (Runnable command = commands.poll(); command != null; command = commands.poll()) {
                    command.run();
                }
                X11Drag following = drag;
                if (following != null) {
                    following.tick();
                }
                expire();
                x("XFlush", "p>i", display);
                if (x("XPending", "p>i", display) > 0 || !commands.isEmpty()) {
                    continue;
                }
                memPutInt(pollfds, connection);
                memPutInt(pollfds + 4, 1);          // events = POLLIN, revents = 0
                memPutInt(pollfds + 8, wakeRead);
                memPutInt(pollfds + 12, 1);
                Ffi.call(poll, "ppi>i", pollfds, 2, pollMillis);
                int waiting = woken.available();
                if (waiting > 0) {
                    woken.read(new byte[waiting]); // read, not skip: a pipe cannot seek
                }
            }
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, "the X11 clipboard stopped", failed);
        } finally {
            nmemFree(event);
            nmemFree(pollfds);
            for (Read read : reads) {
                if (read != null) {
                    read.result.completeExceptionally(new TransferGoneException("the clipboard closed"));
                }
            }
        }
    }

    private void handle(long event) {
        int type = memGetInt(event);
        if (type == SELECTION_REQUEST) {
            answer(memGetAddress(event + 40), memGetAddress(event + 48), memGetAddress(event + 56),
                    memGetAddress(event + 64), memGetAddress(event + 72));
        } else if (type == SELECTION_NOTIFY) {
            converted(memGetAddress(event + 56));
        } else if (type == SELECTION_CLEAR) {
            if (memGetAddress(event + 40) == clipboard && owner) {
                owner = false;
                ownBytes = t -> null;
                ownTypes = List.of();
            }
        } else if (type == PROPERTY_NOTIFY) {
            long on = memGetAddress(event + 32);
            long property = memGetAddress(event + 40);
            int state = memGetInt(event + 56);
            if (on == window && state == PROPERTY_NEW_VALUE) {
                for (int i = 0; i < READ_SLOTS; i++) {
                    if (readProperties[i] == property && reads[i] != null && reads[i].incremental != null) {
                        continueRead(i);
                    }
                }
            } else if (on != window && state == PROPERTY_DELETE) {
                continueSend(on, property);
            }
        } else if (type == GENERIC_EVENT) {
            X11Pinch gestures = pinch;
            if (gestures != null) {
                gestures.event(event);
            }
        } else if (type == CLIENT_MESSAGE) {
            X11Drag asked = drag;
            if (asked != null) {
                asked.message(event);
            }
        } else if (type == fixesEvent) {
            if (memGetAddress(event + 48) == window) {
                changeListener.run();          // our own offer took
            } else {
                askTargets();
            }
        }
    }

    private void askTargets() {
        if (x("XGetSelectionOwner", "pp>p", display, clipboard) == NULL) {
            offered = List.of();
            changeListener.run();
            return;
        }
        x("XConvertSelection", "pppppp>i", display, clipboard, targets, targetsProperty, window, 0);
    }

    /** A SelectionNotify: the answer to a conversion of ours is in a property, or there is none. */
    private void converted(long property) {
        // The event names the property it filled; None is a refusal, and says nothing of which
        // request was refused, so a refused read waits out its deadline.
        if (property == targetsProperty) {
            List<String> names = new ArrayList<>();
            Property got = property(targetsProperty);
            if (got != null && got.format == 32) {
                for (int i = 0; i < got.items; i++) {
                    names.add(name(memGetAddress(got.data + 8L * i)));
                }
            }
            if (got != null) {
                got.free();
            }
            offered = List.copyOf(names);
            changeListener.run();
            return;
        }
        for (int i = 0; i < READ_SLOTS; i++) {
            if (readProperties[i] == property && reads[i] != null) {
                Property got = property(property);
                if (got == null) {
                    finish(i, null);
                } else if (got.type == incr) {
                    got.free();
                    reads[i].incremental = new ByteArrayOutputStream(); // deleting it asked for the first part
                } else {
                    byte[] bytes = got.bytes();
                    got.free();
                    finish(i, bytes);
                }
            }
        }
    }

    private void continueRead(int slot) {
        Property part = property(readProperties[slot]);
        if (part == null) {
            return;
        }
        Read read = reads[slot];
        byte[] bytes = part.bytes();
        part.free();
        if (bytes.length == 0) {
            finish(slot, read.incremental.toByteArray());
        } else if (read.incremental.size() + bytes.length > MAX_BYTES) {
            finish(slot, null);
        } else {
            read.incremental.writeBytes(bytes);
        }
    }

    private void finish(int slot, byte[] bytes) {
        Read read = reads[slot];
        reads[slot] = null;
        if (bytes == null) {
            read.result.completeExceptionally(new TransferGoneException(read.type + " could not be read"));
        } else {
            read.result.complete(bytes);
        }
    }

    /** A property of our window, read whole and deleted. */
    private final class Property {
        long type;
        int format;
        int items;
        long data;

        byte[] bytes() {
            int size = format == 32 ? items * 8 : format == 16 ? items * 2 : items;
            byte[] out = new byte[size];
            if (size > 0) {
                memByteBuffer(data, size).get(out);
            }
            return out;
        }

        void free() {
            if (data != NULL) {
                x("XFree", "p>i", data);
            }
        }
    }

    private Property property(long atom) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = stack.ncalloc(8, 5, 8);
            // offset 0, as long as it is, delete it, any type
            int status = (int) x("XGetWindowProperty", "pppppipppppp>i", display, window, atom, 0,
                    Integer.MAX_VALUE / 4, 1, 0, out, out + 8, out + 16, out + 24, out + 32);
            if (status != 0 || memGetAddress(out) == NULL) {
                long data = memGetAddress(out + 32);
                if (data != NULL) {
                    x("XFree", "p>i", data);
                }
                return null;
            }
            Property got = new Property();
            got.type = memGetAddress(out);
            got.format = memGetInt(out + 8);
            got.items = (int) memGetAddress(out + 16);
            got.data = memGetAddress(out + 32);
            return got;
        }
    }

    /** A SelectionRequest: another program wants one target of what we own. */
    private void answer(long requestor, long selection, long target, long property, long time) {
        long into = property == NULL ? target : property; // an obsolete client names no property
        boolean done = false;
        X11Drag dragged = drag;
        boolean ofDrag = dragged != null && dragged.owns(selection);
        List<String> ownTypes = ofDrag ? dragged.types() : this.ownTypes;
        Function<String, byte[]> ownBytes = ofDrag ? dragged.bytes() : this.ownBytes;
        if (ofDrag || (owner && selection == clipboard)) {
            if (target == targets) {
                List<Long> list = new ArrayList<>();
                list.add(targets);
                for (String type : ownTypes) {
                    for (String spelling : ClipboardTypes.spellings(type)) {
                        list.add(atom(spelling));
                    }
                }
                long data = nmemAlloc(8L * list.size());
                for (int i = 0; i < list.size(); i++) {
                    memPutAddress(data + 8L * i, list.get(i));
                }
                x("XChangeProperty", "ppppiipi>i", display, requestor, into, XA_ATOM, 32, 0, data, list.size());
                nmemFree(data);
                done = true;
            } else {
                String type = ClipboardTypes.canonical(name(target));
                byte[] bytes = ownTypes.contains(type) ? ownBytes.apply(type) : null;
                if (bytes != null && bytes.length <= CHUNK) {
                    put(requestor, into, target, bytes, 0, bytes.length);
                    done = true;
                } else if (bytes != null) {
                    // Too large for one request: announce its size, then a part each time the
                    // requestor deletes the property, and an empty one to end.
                    x("XSelectInput", "ppp>i", display, requestor, PROPERTY_CHANGE_MASK);
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        long size = stack.ncalloc(8, 1, 8);
                        memPutAddress(size, bytes.length);
                        x("XChangeProperty", "ppppiipi>i", display, requestor, into, incr, 32, 0, size, 1);
                    }
                    sends.add(new Send(requestor, into, target, bytes));
                    done = true;
                }
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long notify = stack.ncalloc(8, 1, EVENT_BYTES);
            memPutInt(notify, SELECTION_NOTIFY);
            memPutAddress(notify + 24, display);
            memPutAddress(notify + 32, requestor);
            memPutAddress(notify + 40, selection);
            memPutAddress(notify + 48, target);
            memPutAddress(notify + 56, done ? into : NULL);
            memPutAddress(notify + 64, time);
            x("XSendEvent", "ppipp>i", display, requestor, 0, 0, notify);
        }
    }

    private void put(long requestor, long property, long target, byte[] bytes, int from, int length) {
        long data = nmemAlloc(Math.max(1, length));
        memByteBuffer(data, Math.max(1, length)).put(bytes, from, length);
        x("XChangeProperty", "ppppiipi>i", display, requestor, property, target, 8, 0, data, length);
        nmemFree(data);
    }

    private void continueSend(long requestor, long property) {
        for (Iterator<Send> it = sends.iterator(); it.hasNext();) {
            Send send = it.next();
            if (send.requestor == requestor && send.property == property) {
                int length = Math.min(CHUNK, send.bytes.length - send.sent);
                put(requestor, property, send.target, send.bytes, send.sent, length);
                send.sent += length;
                send.deadline = System.nanoTime() + TRANSFER_TIMEOUT_NANOS;
                if (length == 0) {
                    it.remove(); // the empty part was the end
                }
                return;
            }
        }
    }

    private void expire() {
        long now = System.nanoTime();
        for (int i = 0; i < READ_SLOTS; i++) {
            if (reads[i] != null && now - reads[i].deadline > 0) {
                finish(i, null);
            }
        }
        sends.removeIf(send -> now - send.deadline > 0);
    }

    // ---- NativeClipboard --------------------------------------------------------------------------

    @Override
    public boolean isOwner() {
        return owner;
    }

    @Override
    public List<String> types() {
        return ClipboardTypes.canonical(offered);
    }

    @Override
    public CompletableFuture<byte[]> read(String type) {
        return read(clipboard, type, () -> offered, 0);
    }

    /**
     * Asks the owner of a selection for one type of what it holds: the clipboard's, or a drag's.
     *
     * @param selection the selection
     * @param type      the canonical type
     * @param spellings the names the owner offers, read on the X thread
     * @param time      the timestamp the conversion is asked with
     */
    CompletableFuture<byte[]> read(long selection, String type, java.util.function.Supplier<List<String>> spellings,
            long time) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        post(() -> {
            String spelling = ClipboardTypes.spellingIn(type, spellings.get());
            int slot = -1;
            for (int i = 0; i < READ_SLOTS && slot < 0; i++) {
                if (reads[i] == null) {
                    slot = i;
                }
            }
            if (spelling == null || slot < 0 || closing) {
                result.completeExceptionally(new TransferGoneException(type + " is not on the clipboard"));
                return;
            }
            reads[slot] = new Read(result, type);
            x("XDeleteProperty", "ppp>i", display, window, readProperties[slot]);
            x("XConvertSelection", "pppppp>i", display, selection, atom(spelling), readProperties[slot],
                    window, time);
        });
        return result;
    }

    /** @return whether a conversion asked of another program is still waited for; X thread */
    boolean isReading() {
        for (Read read : reads) {
            if (read != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The drags of this connection, made the first time a window asks. Any thread.
     *
     * @param ui the UI runtime
     * @return what speaks XDND for this program's windows
     */
    public synchronized X11Drag drags(limn.concurrent.UiRuntime ui) {
        if (drag == null) {
            drag = new X11Drag(this, ui);
        }
        return drag;
    }

    /**
     * Sends the pinches over one of this program's windows to its input, read on this connection.
     * Any thread.
     *
     * @return what forgets the window
     */
    public Runnable pinches(long xwindow, java.util.function.Supplier<limn.backend.spi.WindowInput> input,
            java.util.function.DoubleUnaryOperator toLogical, limn.concurrent.UiRuntime ui) {
        X11Pinch gestures;
        synchronized (this) {
            if (pinch == null) {
                pinch = new X11Pinch(this);
            }
            gestures = pinch;
        }
        return gestures.register(xwindow, input, toLogical, ui);
    }

    long display() {
        return display;
    }

    long window() {
        return window;
    }

    @Override
    public void offer(List<String> types, Function<String, byte[]> bytes) {
        List<String> offeredTypes = List.copyOf(types);
        owner = !offeredTypes.isEmpty(); // true from this call on, so a read right after is our own
        post(() -> {
            ownTypes = offeredTypes;
            ownBytes = bytes;
            sends.clear();
            x("XSetSelectionOwner", "pppp>i", display, clipboard, offeredTypes.isEmpty() ? NULL : window, 0);
            owner = !offeredTypes.isEmpty()
                    && x("XGetSelectionOwner", "pp>p", display, clipboard) == window;
        });
    }

    @Override
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        post(() -> { });
        try {
            thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            return; // it holds the display; leaking a connection beats closing it under a call
        }
        x("XSetErrorHandler", "p>p", previousErrorHandler);
        x("XDestroyWindow", "pp>i", display, window);
        x("XCloseDisplay", "p>i", display);
        errorHandler.free();
        try {
            wake.close();
            woken.close();
        } catch (IOException ignored) {
            // nothing depends on it
        }
        Ffi.call(APIUtil.apiGetFunctionAddress(libc, "close"), "i>i", wakeRead);
        Ffi.call(APIUtil.apiGetFunctionAddress(libc, "close"), "i>i", wakeWrite);
    }
}
