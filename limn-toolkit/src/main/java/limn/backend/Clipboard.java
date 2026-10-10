package limn.backend;

import limn.concurrent.Subscription;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The system clipboard: what the user last copied, in this application or another, in every
 * format it was offered in.
 *
 * <p>Text has the two direct calls every text control uses, {@link #text()} and {@link #setText}.
 * Anything richer is a {@link Transfer} going in and an {@link Offer} coming out:
 *
 * <pre>{@code
 * clipboard.set(Transfer.of(DataFormat.IMAGE, chart).with(DataFormat.TEXT, title));
 *
 * Offer there = clipboard.content();
 * if (there.has(DataFormat.IMAGE)) {
 *     there.read(DataFormat.IMAGE).thenAccept(attachment::setImage);
 * }
 * }</pre>
 *
 * <p><b>UI thread only</b>, like everything a scene hands out, and what it answers arrives there.
 * A worker that has something for the clipboard posts it with {@code Ui.post}.
 */
public interface Clipboard {

    /** A clipboard of its own that no other program sees, for a widget outside any scene. */
    Clipboard NONE = inMemory();

    /**
     * A clipboard that is only memory: what is set is what is read, and no other program is
     * involved. A headless backend hands one to its windows, and a test hands one to a scene.
     *
     * @return a new, empty clipboard
     */
    static Clipboard inMemory() {
        return new Clipboard() {
            private Transfer held = Transfer.EMPTY;
            private final List<Runnable> listeners = new ArrayList<>();

            @Override
            public String text() {
                String text = held.get(DataFormat.TEXT);
                return text == null ? "" : text;
            }

            @Override
            public void set(Transfer content) {
                held = Objects.requireNonNull(content, "content");
                for (Runnable listener : List.copyOf(listeners)) {
                    listener.run();
                }
            }

            @Override
            public Offer content() {
                return held.asOffer();
            }

            @Override
            public Subscription onChange(Runnable listener) {
                Objects.requireNonNull(listener, "listener");
                listeners.add(listener);
                return () -> listeners.remove(listener);
            }
        };
    }

    /**
     * The clipboard's text, now. A paste in a key handler and a menu that greys its Paste item
     * both want the answer in the same breath, so this one read does not wait: where the text has
     * to come from another program through a pipe, it gives up after a second.
     *
     * @return the text; the empty string when the clipboard holds none, never null
     */
    String text();

    /**
     * Puts text, and nothing else, on the clipboard.
     *
     * @param text the text; {@code null} is the empty string
     */
    default void setText(@Nullable String text) {
        set(Transfer.of(DataFormat.TEXT, text == null ? "" : text));
    }

    /**
     * Replaces what the clipboard holds. A lazy value of the transfer is asked for when a program,
     * this one included, first reads that format.
     *
     * @param content what to offer; {@link Transfer#EMPTY} empties the clipboard
     */
    void set(Transfer content);

    /**
     * What the clipboard holds now. The formats are answered from what the platform last
     * reported, without a round trip, so a menu can ask on every opening; a read of something
     * that has been replaced meanwhile fails with {@code TransferGoneException}.
     *
     * @return the offer; {@link Offer#EMPTY}'s equal when the clipboard is empty
     */
    Offer content();

    /**
     * Hears the clipboard's content change, whoever changed it, where the platform tells.
     *
     * @param listener called on the UI thread after each change
     * @return the way to stop listening
     */
    Subscription onChange(Runnable listener);
}
