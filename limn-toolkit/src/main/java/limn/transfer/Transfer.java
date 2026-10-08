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
        private @Nullable Supplier<?> pending;

        Slot(@Nullable Object value, @Nullable Supplier<?> pending) {
            this.value = value;
            this.pending = pending;
        }

        Object resolve(DataFormat<?> format) {
            Supplier<?> supplier = pending;
            if (supplier != null) {
                Ui.checkUiThread();
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
