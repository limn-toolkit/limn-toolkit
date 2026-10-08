package limn.backend.lwjgl.transfer;

import limn.transfer.TransferGoneException;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The Wayland clipboard: a {@code wl_data_device} of our own on the display GLFW opened.
 *
 * <p>GLFW has one too and keeps it; a client may have several, and each is told of the selection.
 * Ours needs three things GLFW does not give away. A {@code wl_seat} bound again from a registry
 * of ours, since GLFW's proxy is private. The serial of the last key or button, which
 * {@code set_selection} must quote and GLFW does not expose: a {@code wl_keyboard} and a
 * {@code wl_pointer} of ours hear the same input for that number alone. And <b>an event queue of
 * our own, dispatched by a thread of ours</b>: GLFW reads what another client offers with a
 * blocking {@code read()} on the UI thread, so when this process is the one offering, the request
 * to send can only be served from another thread. On the default queue that is a deadlock,
 * measured on KWin on 2026-10-08, and with the queue it is not.
 *
 * <p>Bytes cross through pipes, read and written on short-lived threads so that neither the UI
 * thread nor the dispatch thread ever waits on another program. A file descriptor is opened as a
 * stream through {@code /proc/self/fd}, which is what spares this class a binding of
 * {@code read} and {@code write}.
 */
public final class WaylandClipboard implements NativeClipboard {

    private static final System.Logger LOG = System.getLogger(WaylandClipboard.class.getName());

    /** A selection larger than this is refused: nothing sane is, and a hostile source is unbounded. */
    private static final int MAX_BYTES = 512 << 20;

    private static final int SEAT_VERSION = 5;
    private static final int MANAGER_VERSION = 3;
    private static final int CAPABILITY_POINTER = 1;
    private static final int CAPABILITY_KEYBOARD = 2;

    private final Wl wl;
    private final long display;
    private final long queue;
    private final long wrapper;
    private final long registry;
    private final Thread dispatcher;

    private final long offerListener;
    private final long sourceListener;
    private final long keyboardListener;
    private final long pointerListener;

    /** Guards every proxy below against being ended on one thread while another sends on it. */
    private final Object lock = new Object();
    private long seat;
    private long manager;
    private long device;
    private long keyboard;
    private long pointer;
    private final Map<Long, List<String>> offers = new HashMap<>();
    private long selection;
    private long dragOffer;
    private long ownSource;
    private final Map<Long, Function<String, byte[]>> sources = new HashMap<>();

    private volatile List<String> offered = List.of();
    private volatile boolean owner;
    private volatile int serial;
    private volatile Runnable changeListener = () -> { };
    private volatile boolean closing;

    /**
     * @param display GLFW's {@code wl_display}
     * @throws RuntimeException when the library is absent or the compositor has no seat or no
     *                          data device manager; the caller then keeps GLFW's text clipboard
     */
    public WaylandClipboard(long display) {
        this.display = display;
        wl = new Wl();
        queue = JNI.invokePP(display, wl.function("wl_display_create_queue"));
        wrapper = JNI.invokePP(display, wl.function("wl_proxy_create_wrapper"));
        JNI.invokePPV(wrapper, queue, wl.function("wl_proxy_set_queue"));

        offerListener = wl.table(
                wl.on("ppp", a -> offerType(a[1], a[2])),
                wl.ignore("ppi"),                                     // source_actions
                wl.ignore("ppi"));                                    // action
        sourceListener = wl.table(
                wl.ignore("ppp"),                                     // target
                wl.on("pppi", a -> send(a[1], memUTF8(a[2]), (int) a[3])),
                wl.on("pp", a -> cancelled(a[1])),
                wl.ignore("pp"),                                      // dnd_drop_performed
                wl.ignore("pp"),                                      // dnd_finished
                wl.ignore("ppi"));                                    // action
        keyboardListener = wl.table(
                wl.on("ppiii", a -> wl.close((int) a[3])),            // keymap: the fd is ours to close
                wl.on("ppipp", a -> serial = (int) a[2]),             // enter
                wl.ignore("ppip"),                                    // leave
                wl.on("ppiiii", a -> serial = (int) a[2]),            // key
                wl.ignore("ppiiiii"),                                 // modifiers
                wl.ignore("ppii"));                                   // repeat_info
        pointerListener = wl.table(
                wl.ignore("ppipii"),                                  // enter
                wl.ignore("ppip"),                                    // leave
                wl.ignore("ppiii"),                                   // motion
                wl.on("ppiiii", a -> serial = (int) a[2]),            // button
                wl.ignore("ppiii"),                                   // axis
                wl.ignore("pp"),                                      // frame
                wl.ignore("ppi"),                                     // axis_source
                wl.ignore("ppii"),                                    // axis_stop
                wl.ignore("ppii"));                                   // axis_discrete

        int[] seatGlobal = new int[2];
        int[] managerGlobal = new int[2];
        registry = wl.request(wrapper, 1, wl.function("wl_registry_interface"), 0, 0L);
        wl.listen(registry, wl.table(
                wl.on("ppipi", a -> {
                    String name = memUTF8(a[3]);
                    if (name.equals("wl_seat") && seatGlobal[1] == 0) {
                        seatGlobal[0] = (int) a[2];
                        seatGlobal[1] = (int) a[4];
                    } else if (name.equals("wl_data_device_manager") && managerGlobal[1] == 0) {
                        managerGlobal[0] = (int) a[2];
                        managerGlobal[1] = (int) a[4];
                    }
                }),
                wl.ignore("ppi")));
        roundtrip();
        if (seatGlobal[1] == 0 || managerGlobal[1] == 0) {
            wl.free();
            throw new IllegalStateException("the compositor offers no "
                    + (seatGlobal[1] == 0 ? "wl_seat" : "wl_data_device_manager"));
        }
        seat = wl.bind(registry, seatGlobal[0], wl.function("wl_seat_interface"), "wl_seat",
                Math.min(SEAT_VERSION, seatGlobal[1]));
        manager = wl.bind(registry, managerGlobal[0], wl.function("wl_data_device_manager_interface"),
                "wl_data_device_manager", Math.min(MANAGER_VERSION, managerGlobal[1]));
        wl.listen(seat, wl.table(
                wl.on("ppi", a -> capabilities((int) a[2])),
                wl.ignore("ppp")));                                   // name
        device = wl.request(manager, 1, wl.function("wl_data_device_interface"), 0, 0L, seat);
        wl.listen(device, wl.table(
                wl.on("ppp", a -> newOffer(a[2])),                    // data_offer
                wl.on("ppipiip", a -> dragEntered(a[6])),             // enter
                wl.on("pp", a -> dragLeft()),                         // leave
                wl.ignore("ppiii"),                                   // motion
                wl.ignore("pp"),                                      // drop
                wl.on("ppp", a -> selected(a[2]))));                  // selection
        roundtrip(); // the seat's capabilities
        roundtrip(); // the keyboard's first events, and the selection if this client has the focus

        long dispatch = wl.function("wl_display_dispatch_queue");
        dispatcher = new Thread(() -> {
            while (!closing && JNI.invokePPI(display, queue, dispatch) >= 0) {
                // every event of our proxies is handled here
            }
        }, "limn-wayland-data");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    private void roundtrip() {
        JNI.invokePPI(display, queue, wl.function("wl_display_roundtrip_queue"));
    }

    // ---- events, on the dispatch thread (or inside a roundtrip of the constructor) --------------

    private void capabilities(int caps) {
        synchronized (lock) {
            if ((caps & CAPABILITY_KEYBOARD) != 0 && keyboard == NULL) {
                keyboard = wl.request(seat, 1, wl.function("wl_keyboard_interface"), 0, 0L);
                wl.listen(keyboard, keyboardListener);
            } else if ((caps & CAPABILITY_KEYBOARD) == 0 && keyboard != NULL) {
                wl.request(keyboard, 0, NULL, Wl.DESTROY);            // release
                keyboard = NULL;
            }
            if ((caps & CAPABILITY_POINTER) != 0 && pointer == NULL) {
                pointer = wl.request(seat, 0, wl.function("wl_pointer_interface"), 0, 0L);
                wl.listen(pointer, pointerListener);
            } else if ((caps & CAPABILITY_POINTER) == 0 && pointer != NULL) {
                wl.request(pointer, 1, NULL, Wl.DESTROY);             // release
                pointer = NULL;
            }
        }
    }

    private void newOffer(long offer) {
        synchronized (lock) {
            offers.put(offer, new ArrayList<>());
            wl.listen(offer, offerListener);
        }
    }

    private void offerType(long offer, long mime) {
        synchronized (lock) {
            List<String> types = offers.get(offer);
            if (types != null) {
                types.add(memUTF8(mime));
            }
        }
    }

    private void selected(long offer) {
        synchronized (lock) {
            if (selection != NULL && selection != offer) {
                destroyOffer(selection);
            }
            selection = offer;
            offered = offer == NULL ? List.of() : List.copyOf(offers.getOrDefault(offer, List.of()));
        }
        changeListener.run();
    }

    // A drag over one of our windows hands this device an offer too. Until this backend drags,
    // GLFW's device is the one that answers; ours only lets the object go.

    private void dragEntered(long offer) {
        synchronized (lock) {
            dragOffer = offer;
        }
    }

    private void dragLeft() {
        synchronized (lock) {
            if (dragOffer != NULL) {
                destroyOffer(dragOffer);
                dragOffer = NULL;
            }
        }
    }

    private void destroyOffer(long offer) {
        offers.remove(offer);
        wl.request(offer, 2, NULL, Wl.DESTROY);
    }

    private void send(long source, String mime, int fd) {
        Function<String, byte[]> bytes;
        synchronized (lock) {
            bytes = sources.get(source);
        }
        Thread writer = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream("/proc/self/fd/" + fd)) {
                byte[] data = bytes == null ? null : bytes.apply(ClipboardTypes.canonical(mime));
                if (data != null) {
                    out.write(data);
                }
            } catch (IOException gone) {
                // The program that asked stopped reading: its business.
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.WARNING, "the clipboard's " + mime + " could not be sent", failed);
            } finally {
                wl.close(fd);
            }
        }, "limn-wayland-send");
        writer.setDaemon(true);
        writer.start();
    }

    private void cancelled(long source) {
        boolean wasOwner;
        synchronized (lock) {
            sources.remove(source);
            wl.request(source, 1, NULL, Wl.DESTROY);
            wasOwner = source == ownSource;
            if (wasOwner) {
                ownSource = NULL;
                owner = false;
            }
        }
        if (wasOwner) {
            changeListener.run();
        }
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
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        int readEnd;
        synchronized (lock) {
            String mime = ClipboardTypes.spellingIn(type, offered);
            if (selection == NULL || mime == null || closing) {
                result.completeExceptionally(new TransferGoneException(type + " is not on the clipboard"));
                return result;
            }
            int[] fds = wl.pipe();
            readEnd = fds[0];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                wl.request(selection, 1, NULL, 0, memAddress(stack.UTF8(mime)), fds[1]);
            }
            wl.flush(display);
            wl.close(fds[1]);
        }
        Thread reader = new Thread(() -> {
            try (FileInputStream in = new FileInputStream("/proc/self/fd/" + readEnd)) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] chunk = new byte[1 << 16];
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    if (all.size() + n > MAX_BYTES) {
                        throw new IOException("more than " + MAX_BYTES + " bytes");
                    }
                    all.write(chunk, 0, n);
                }
                result.complete(all.toByteArray());
            } catch (IOException failed) {
                result.completeExceptionally(new TransferGoneException(type + " could not be read", failed));
            } finally {
                wl.close(readEnd);
            }
        }, "limn-wayland-receive");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    @Override
    public void offer(List<String> types, Function<String, byte[]> bytes) {
        synchronized (lock) {
            if (closing) {
                return;
            }
            long source = NULL;
            if (!types.isEmpty()) {
                source = wl.request(manager, 0, wl.function("wl_data_source_interface"), 0, 0L);
                wl.listen(source, sourceListener);
                sources.put(source, bytes);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    for (String type : types) {
                        for (String spelling : ClipboardTypes.spellings(type)) {
                            wl.request(source, 0, NULL, 0, memAddress(stack.UTF8(spelling)));
                        }
                    }
                }
            }
            // The source before this one is told it was cancelled, and is let go there.
            wl.request(device, 1, NULL, 0, source, Integer.toUnsignedLong(serial));
            wl.flush(display);
            ownSource = source;
            owner = source != NULL;
        }
        // No word to the listener from here: the compositor answers a selection it accepted
        // with a selection event, and that is the change.
    }

    @Override
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            // Something for the dispatch thread to wake on: the answer to a sync on our queue.
            wl.request(wrapper, 0, wl.function("wl_callback_interface"), 0, 0L);
            wl.flush(display);
        }
        try {
            dispatcher.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!dispatcher.isAlive()) {
            wl.free();
        }
    }
}
