package limn.backend.lwjgl.transfer;

import limn.backend.Clipboard;
import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;
import limn.transfer.TransferGoneException;
import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The clipboard an application sees, over one system's {@link NativeClipboard}: a transfer going
 * out becomes type names and, when a program asks, bytes; what another program offers comes back
 * as formats and, when the application asks, values.
 *
 * <p>While this process owns the clipboard, what it reads is the transfer it set, as it is: no
 * encoding, no round trip, and the in-process formats with it.
 */
public final class PlatformClipboard implements Clipboard {

    private static final System.Logger LOG = System.getLogger(PlatformClipboard.class.getName());

    /** How long {@link #text()} waits for another program before answering that there is none. */
    private static final long TEXT_TIMEOUT_MILLIS = 1000;

    /** How long a program asking for our bytes is kept waiting for the UI thread to make them. */
    private static final long RENDER_TIMEOUT_SECONDS = 20;

    private final NativeClipboard system;
    private final UiRuntime ui;
    private final List<Runnable> listeners = new ArrayList<>();

    /** What this process last set, and the bytes already made from it. Read off the UI thread. */
    private volatile Transfer held = Transfer.EMPTY;
    private volatile Map<String, CompletableFuture<byte[]>> rendered = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param system the windowing system's clipboard
     * @param ui     the runtime whose thread lazy values are made on and results arrive on
     */
    public PlatformClipboard(NativeClipboard system, UiRuntime ui) {
        this.system = system;
        this.ui = ui;
        system.setChangeListener(() -> {
            if (!closed) {
                ui.post(this::changed);
            }
        });
    }

    private void changed() {
        if (!system.isOwner()) {
            held = Transfer.EMPTY; // what was lazy and never asked for is let go
            rendered = new ConcurrentHashMap<>();
        }
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    @Override
    public String text() {
        ui.checkUiThread();
        if (system.isOwner()) {
            String text = held.get(DataFormat.TEXT);
            return text == null ? "" : text;
        }
        if (!system.types().contains(ClipboardTypes.TEXT)) {
            return "";
        }
        try {
            byte[] bytes = system.read(ClipboardTypes.TEXT).get(TEXT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return text(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception goneOrSlow) {
            return "";
        }
    }

    private static String text(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--; // some programs hand a C string, terminator included
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    @Override
    public void set(Transfer content) {
        Objects.requireNonNull(content, "content");
        ui.checkUiThread();
        List<String> types = types(content);
        held = content;
        Map<String, CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();
        rendered = cache;
        // A picture already at hand is encoded now, on a worker, and not when a program pastes:
        // Windows and macOS ask on the UI thread and wait there, and a photograph becoming a PNG
        // is not something to do between two frames. The value is taken here, on the UI thread,
        // so the worker needs nothing from it and waiting for the worker can never deadlock. A
        // lazy value keeps its promise: it is made, and encoded, when it is first asked for.
        for (String type : system.madeAhead(types)) {
            DataFormat<?> format = formatOf(content, type);
            if (format != null && content.isReady(format)) {
                Object value = content.get(format);
                CompletableFuture<byte[]> ahead = new CompletableFuture<>();
                cache.put(type, ahead);
                ui.workerPool().execute(() -> {
                    try {
                        ahead.complete(value == null ? null : encode(type, format, value));
                    } catch (Throwable failed) {
                        LOG.log(Level.WARNING, "the clipboard's " + type + " could not be made", failed);
                        ahead.complete(null);
                    }
                });
            }
        }
        system.offer(types, type -> bytes(content, cache, type, ui));
    }

    /**
     * The types a transfer offers another program, best first: what a copy announces and what a
     * drag carries. A format that never leaves the process is not among them.
     *
     * @param content the transfer
     * @return the canonical type names
     */
    public static List<String> types(Transfer content) {
        List<String> types = new ArrayList<>();
        for (DataFormat<?> format : content.formats()) {
            if (!format.isInProcess()) {
                types.add(typeOf(format));
            }
        }
        return types;
    }

    /**
     * What makes the bytes of one of a transfer's types, once, for a system that asks for them:
     * as a copy's are made, on the UI thread for the value and where it is asked for the rest.
     *
     * @param content the transfer
     * @param ui      the runtime whose thread a lazy value is made on
     * @return from a canonical type to its bytes, or null for what cannot be made
     */
    public static java.util.function.Function<String, byte[]> provider(Transfer content, UiRuntime ui) {
        Map<String, CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();
        return type -> bytes(content, cache, type, ui);
    }

    private static String typeOf(DataFormat<?> format) {
        if (format.equals(DataFormat.TEXT)) {
            return ClipboardTypes.TEXT;
        }
        if (format.equals(DataFormat.HTML)) {
            return ClipboardTypes.HTML;
        }
        if (format.equals(DataFormat.IMAGE)) {
            return ClipboardTypes.IMAGE;
        }
        if (format.equals(DataFormat.FILES)) {
            return ClipboardTypes.FILES;
        }
        return format.name();
    }

    /**
     * The bytes of one type of a transfer, kept once made: any thread. What was started ahead is
     * waited for. Otherwise the value is taken from the transfer on the UI thread, where a lazy
     * one is made, and encoded on the thread that asked: off the UI thread where the system asks
     * from one of its own (X11, Wayland), on it where the system asks there (Windows, macOS).
     */
    private static byte @Nullable [] bytes(Transfer content, Map<String, CompletableFuture<byte[]>> cache,
            String type, UiRuntime ui) {
        CompletableFuture<byte[]> known = cache.get(type);
        try {
            if (known != null) {
                return known.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            DataFormat<?> format = formatOf(content, type);
            if (format == null) {
                return null;
            }
            Object value;
            if (ui.isUiThread()) {
                value = content.get(format);
            } else {
                CompletableFuture<Object> onUi = new CompletableFuture<>();
                ui.post(() -> {
                    try {
                        onUi.complete(content.get(format));
                    } catch (Throwable failed) {
                        onUi.completeExceptionally(failed);
                    }
                });
                value = onUi.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            byte[] made = value == null ? null : encode(type, format, value);
            if (made != null) {
                cache.put(type, CompletableFuture.completedFuture(made));
            }
            return made;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failed) {
            LOG.log(Level.WARNING, "the clipboard's " + type + " could not be made", failed);
            return null;
        }
    }

    private static @Nullable DataFormat<?> formatOf(Transfer content, String type) {
        if (type.equals(ClipboardTypes.PIXELS)) {
            return content.has(DataFormat.IMAGE) ? DataFormat.IMAGE : null;
        }
        for (DataFormat<?> format : content.formats()) {
            if (!format.isInProcess() && typeOf(format).equals(type)) {
                return format;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(String type, DataFormat<?> format, Object value) {
        if (format.equals(DataFormat.TEXT) || format.equals(DataFormat.HTML)) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }
        if (format.equals(DataFormat.IMAGE)) {
            Image image = (Image) value;
            if (type.equals(ClipboardTypes.PIXELS)) {
                byte[] pixels = image.pixels();
                return java.nio.ByteBuffer.allocate(8 + pixels.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(image.width()).putInt(image.height()).put(pixels).array();
            }
            return Images.encode(image, ImageFormat.PNG);
        }
        if (format.equals(DataFormat.FILES)) {
            return UriLists.encode((List<Path>) value);
        }
        return ((DataFormat<Object>) format).encode(value);
    }

    @Override
    public Offer content() {
        ui.checkUiThread();
        if (system.isOwner()) {
            return held.asOffer();
        }
        return offer(system.types(), system::read, ui);
    }

    /**
     * What another program offers, as an offer: the clipboard's content, or what a drag carries.
     *
     * @param offered the canonical types on offer
     * @param read    asks for the bytes of one of them
     * @param ui      the runtime whose thread the values arrive on
     * @return the offer
     */
    public static Offer offer(List<String> offered, java.util.function.Function<String, CompletableFuture<byte[]>> read,
            UiRuntime ui) {
        List<String> types = List.copyOf(offered);
        Set<DataFormat<?>> formats = new LinkedHashSet<>();
        for (String type : types) {
            switch (type) {
                case ClipboardTypes.TEXT -> formats.add(DataFormat.TEXT);
                case ClipboardTypes.HTML -> formats.add(DataFormat.HTML);
                case ClipboardTypes.IMAGE -> formats.add(DataFormat.IMAGE);
                case ClipboardTypes.FILES -> formats.add(DataFormat.FILES);
                default -> {
                    DataFormat<?> named = DataFormat.declared(type);
                    if (named != null) {
                        formats.add(named);
                    }
                }
            }
        }
        Set<DataFormat<?>> listed = java.util.Collections.unmodifiableSet(formats);
        return new Offer() {
            @Override
            public Set<DataFormat<?>> formats() {
                return listed;
            }

            @Override
            public <T> CompletionStage<T> read(DataFormat<T> format) {
                ui.checkUiThread();
                CompletableFuture<T> result = new CompletableFuture<>();
                if (!listed.contains(format)) {
                    result.completeExceptionally(new TransferGoneException(format + " is not on offer"));
                    return result;
                }
                read.apply(typeOf(format)).whenComplete((bytes, failed) -> {
                    // Decoded where the bytes arrived, which is never the UI thread for another
                    // program's data: a photograph is not decoded between two frames.
                    Object value = null;
                    Throwable error = failed;
                    if (error == null) {
                        try {
                            value = decode(format, bytes);
                        } catch (Throwable undecodable) {
                            error = undecodable;
                        }
                    }
                    Object decoded = value;
                    Throwable why = error;
                    ui.post(() -> {
                        if (why != null) {
                            result.completeExceptionally(why instanceof TransferGoneException ? why
                                    : new TransferGoneException(format + " could not be read", why));
                        } else {
                            @SuppressWarnings("unchecked")
                            T typed = (T) decoded;
                            result.complete(typed);
                        }
                    });
                });
                return result;
            }
        };
    }

    private static Object decode(DataFormat<?> format, byte[] bytes) {
        if (format.equals(DataFormat.TEXT)) {
            return text(bytes);
        }
        if (format.equals(DataFormat.HTML)) {
            return text(bytes);
        }
        if (format.equals(DataFormat.IMAGE)) {
            return Images.decode(bytes);
        }
        if (format.equals(DataFormat.FILES)) {
            return UriLists.decode(bytes);
        }
        return Objects.requireNonNull(format.decode(bytes), "the decoder of " + format + " answered null");
    }

    @Override
    public Subscription onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        ui.checkUiThread();
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Lets go of the system's clipboard; called once, as the backend closes. */
    public void close() {
        closed = true;
        system.close();
    }
}
