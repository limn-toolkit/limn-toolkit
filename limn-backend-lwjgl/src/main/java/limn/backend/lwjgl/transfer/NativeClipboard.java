package limn.backend.lwjgl.transfer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One windowing system's clipboard, in the terms every one of them shares: a list of type names
 * and the bytes behind each. The names are the canonical ones of {@link ClipboardTypes} for the
 * four standard formats, whatever the system calls them, and an application's own format under
 * its own name.
 *
 * <p>Every method may be called from the UI thread; what an implementation does on threads of its
 * own is its business, and the two callbacks it is handed say which thread they tolerate.
 */
public interface NativeClipboard {

    /**
     * @return whether what the clipboard holds was put there by the last {@link #offer} of this
     *         process and has not been replaced since
     */
    boolean isOwner();

    /**
     * The types on the clipboard as the system last reported them, without asking it again.
     *
     * @return canonical type names, best first; empty when the clipboard is empty or unknown
     */
    List<String> types();

    /**
     * Asks for the bytes of one type.
     *
     * @param type one of {@link #types()}
     * @return the bytes, completed on whatever thread the implementation reads on; completed
     *         exceptionally when the type is not, or no longer, there
     */
    CompletableFuture<byte[]> read(String type);

    /**
     * Makes this process the clipboard's owner.
     *
     * @param types what is offered, best first; empty empties the clipboard
     * @param bytes the bytes of one of those types, asked for when a program wants them; it may
     *              be called on any thread and may block, and answers null for what it cannot make
     */
    void offer(List<String> types, Function<String, byte[]> bytes);

    /**
     * The types worth making ahead of anybody asking, because this system asks on the UI thread
     * and waits there: the picture's, where making it is slow.
     *
     * @param offered the types about to be offered
     * @return those of {@code offered}, and of the private types this clipboard asks for itself,
     *         that should be made in the background as soon as they are offered
     */
    default List<String> madeAhead(List<String> offered) {
        return offered.contains(ClipboardTypes.IMAGE) ? List.of(ClipboardTypes.IMAGE) : List.of();
    }

    /**
     * @param listener called, on any thread, after the clipboard's types or its owner change,
     *                 an {@link #offer} of this process included; replaces the one before
     */
    void setChangeListener(Runnable listener);

    /** Lets go of the system's resources. The clipboard is not used afterwards. */
    void close();
}
