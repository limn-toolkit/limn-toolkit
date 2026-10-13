package limn.transfer;

import limn.concurrent.Ui;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * One item, offered in several formats at once: what a copy puts on the clipboard and what a drag
 * carries. The taker picks the richest format it understands, so offer the same thing every way
 * it can usefully be had:
 *
 * <pre>{@code
 * Transfer rows = Transfer.of(DataFormat.TEXT, tabSeparated)
 *         .with(DataFormat.HTML, htmlTable)
 *         .with(ORDERS, selectedOrders);
 * scene.clipboard().set(rows);
 * }</pre>
 *
 * <p>Immutable: {@link #with} answers a new transfer. The formats keep the order they were added
 * in, which is the order of preference a taker is shown.
 */
public final class Transfer {

    /** A transfer of nothing: what empties the clipboard. */
    public static final Transfer EMPTY = new Transfer(Map.of());

    /** A value, or the supplier that makes it the first time it is wanted. */
    private static final class Slot {
        private @Nullable Object value;
        private volatile @Nullable Supplier<?> pending;
        /** Whether the value may be made on whichever thread asks first; see {@link #needsUiThread}. */
        private final boolean anyThread;

        Slot(@Nullable Object value, @Nullable Supplier<?> pending) {
            this(value, pending, false);
        }

        Slot(@Nullable Object value, @Nullable Supplier<?> pending, boolean anyThread) {
            this.value = value;
            this.pending = pending;
            this.anyThread = anyThread;
        }

        synchronized Object resolve(DataFormat<?> format) {
            Supplier<?> supplier = pending;
            if (supplier != null) {
                if (!anyThread) {
                    Ui.checkUiThread();
                }
                pending = null;
                value = Objects.requireNonNull(supplier.get(),
                        () -> "the lazy value of " + format + " was null");
            }
            return Objects.requireNonNull(value, () -> "the lazy value of " + format
                    + " failed when it was first asked for");
        }
    }

    private final Map<DataFormat<?>, Slot> slots;

    private Transfer(Map<DataFormat<?>, Slot> slots) {
        this.slots = slots;
    }

    /**
     * A transfer of one value in one format.
     *
     * @param format the format
     * @param value  the value
     * @param <T>    the type of the value
     * @return the transfer
     */
    public static <T> Transfer of(DataFormat<T> format, T value) {
        return EMPTY.with(format, value);
    }

    /**
     * This transfer and one more format. A format already there keeps its place and takes the
     * new value.
     *
     * @param format the format
     * @param value  the value
     * @param <T>    the type of the value
     * @return the new transfer
     */
    public <T> Transfer with(DataFormat<T> format, T value) {
        Objects.requireNonNull(value, "value");
        return plus(format, new Slot(value, null));
    }

    /**
     * This transfer and one more format, whose value is made only if somebody asks for it: a
     * large picture costs nothing to copy until it is pasted. The supplier is called on the UI
     * thread, at most once, and must not answer null. If nobody asks before the application
     * ends, it is never called.
     *
     * @param format the format
     * @param value  what makes the value
     * @param <T>    the type of the value
     * @return the new transfer
     */
    public <T> Transfer withLazy(DataFormat<T> format, Supplier<? extends T> value) {
        Objects.requireNonNull(value, "value");
        return plus(format, new Slot(null, value));
    }

    /**
     * Offers the picture this transfer carries as a file too: a PNG, written to the temporary
     * directory the first time a taker asks for files, and not before. It is for the programs
     * that take a picture only as a file: the desktop, a file manager's folder.
     *
     * <p>Not what a transfer of a picture does by itself: a file is written to disk for it, and
     * where a system asks for a drag's files as the drag begins (macOS does) that is at every
     * drag. The files go in one directory made for this run of the program, under the system's
     * temporary directory. The file of the transfer before is deleted when the next one is
     * written, so that a program left running does not fill the disk one picture a drag, and
     * the directory with whatever is left in it when the program ends. A taker that wants the
     * picture for longer copies it, as a file manager does.
     *
     * @param name the file's name, without a directory: {@code "logo.png"}
     * @return the new transfer
     * @throws IllegalStateException    if this transfer carries no {@link DataFormat#IMAGE}
     * @throws IllegalArgumentException if the name is empty or names a directory
     */
    public Transfer withImageAsFile(String name) {
        Objects.requireNonNull(name, "name");
        if (!has(DataFormat.IMAGE)) {
            throw new IllegalStateException("a transfer with no picture has none to offer as a file");
        }
        if (name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("a file's name, without a directory: " + name);
        }
        // A picture that is at hand is written by whichever thread asks for the file: nothing in
        // it needs the UI thread, and a program asked for its own drag's files while its UI thread
        // waits for them (GLFW on Wayland, at a drop on the window it came from) would otherwise
        // wait on itself.
        boolean atHand = isReady(DataFormat.IMAGE);
        limn.graphics.Image known = atHand ? get(DataFormat.IMAGE) : null;
        return plus(DataFormat.FILES, new Slot(null, () -> {
            limn.graphics.Image picture = known != null ? known
                    : Objects.requireNonNull(get(DataFormat.IMAGE), "the picture");
            try {
                java.nio.file.Path file = RunFiles.next(name);
                java.nio.file.Files.write(file,
                        limn.graphics.Images.encode(picture, limn.graphics.ImageFormat.PNG));
                return java.util.List.of(file);
            } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }, atHand));
    }

    /**
     * The files written for transfers, in a directory of this run's own: each in a directory of
     * its own inside it, since two transfers may give their files the same name.
     */
    private static final class RunFiles {
        private static java.nio.file.Path root;
        private static java.nio.file.Path last;
        private static long made;

        /** @return where the next file goes, the one before it deleted */
        static synchronized java.nio.file.Path next(String name) throws java.io.IOException {
            if (root == null) {
                root = java.nio.file.Files.createTempDirectory(
                        "limn-transfer-" + ProcessHandle.current().pid() + "-");
                java.nio.file.Path whole = root;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(whole), "limn-transfer-files"));
            }
            if (last != null) {
                delete(last);
            }
            last = java.nio.file.Files.createDirectory(root.resolve(Long.toString(++made)));
            return last.resolve(name);
        }

        /** Deletes a directory and what is in it; what cannot be deleted is left, and said nowhere. */
        private static void delete(java.nio.file.Path directory) {
            try (java.util.stream.Stream<java.nio.file.Path> inside = java.nio.file.Files.walk(directory)) {
                inside.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (java.io.IOException | java.io.UncheckedIOException gone) {
                // Already deleted, or not ours to delete any more.
            }
        }
    }

    private Transfer plus(DataFormat<?> format, Slot slot) {
        Objects.requireNonNull(format, "format");
        Map<DataFormat<?>, Slot> next = new LinkedHashMap<>(slots);
        next.put(format, slot);
        return new Transfer(Collections.unmodifiableMap(next));
    }

    /** @return the formats, in the order they were added */
    public Set<DataFormat<?>> formats() {
        return slots.keySet();
    }

    /**
     * @param format a format
     * @return whether this transfer has it
     */
    public boolean has(DataFormat<?> format) {
        return slots.containsKey(format);
    }

    /**
     * @param format a format
     * @return whether the value is at hand: the transfer has the format, and it is not a lazy
     *         value still waiting to be made
     */
    public boolean isReady(DataFormat<?> format) {
        Slot slot = slots.get(format);
        return slot != null && slot.pending == null;
    }

    /** @return whether this transfer carries nothing */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * Whether a format's value has still to be made, and by the UI thread: what an application's
     * {@link #withLazy} value is. A backend handing a transfer to another program reads this to
     * know that asking from another thread means waiting for the UI thread.
     *
     * @param format the format
     * @return false when the value is at hand, or can be made on the thread that asks
     */
    public boolean needsUiThread(DataFormat<?> format) {
        Slot slot = slots.get(format);
        return slot != null && slot.pending != null && !slot.anyThread;
    }

    /**
     * The value in one format, making a lazy one now. UI thread when the value is lazy.
     *
     * @param format the format
     * @param <T>    the type of its value
     * @return the value, or null when this transfer does not have the format
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(DataFormat<T> format) {
        Slot slot = slots.get(format);
        return slot == null ? null : (T) slot.resolve(format);
    }

    /**
     * This transfer as something to take from: every read completes at once.
     *
     * @return the offer
     */
    public Offer asOffer() {
        return new Offer() {
            @Override
            public Set<DataFormat<?>> formats() {
                return Transfer.this.formats();
            }

            @Override
            public <T> CompletionStage<T> read(DataFormat<T> format) {
                try {
                    T value = get(format);
                    if (value == null) {
                        return CompletableFuture.failedFuture(
                                new TransferGoneException(format + " is not on offer"));
                    }
                    return CompletableFuture.completedFuture(value);
                } catch (RuntimeException failed) {
                    return CompletableFuture.failedFuture(failed);
                }
            }
        };
    }

    @Override
    public String toString() {
        return "Transfer" + slots.keySet();
    }
}
