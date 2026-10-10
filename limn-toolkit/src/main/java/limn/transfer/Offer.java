package limn.transfer;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * What is there to take: the contents of the clipboard at one moment, or what a drag is carrying.
 * It lists its formats at once and hands over a value only when asked, since the value may have
 * to come from another program.
 *
 * <p><b>UI thread only.</b> A stage returned by {@link #read} completes on the UI thread.
 */
public interface Offer {

    /** An offer of nothing. */
    Offer EMPTY = Transfer.EMPTY.asOffer();

    /**
     * The formats on offer: the standard ones, and those the application has declared with
     * {@link DataFormat#of} or put there itself. What another program offers under a name the
     * application never declared is not listed.
     *
     * @return the formats, in the order the source prefers them; empty when nothing is offered
     */
    Set<DataFormat<?>> formats();

    /**
     * @param format a format
     * @return whether it is on offer
     */
    default boolean has(DataFormat<?> format) {
        return formats().contains(format);
    }

    /**
     * Asks for the value in one format.
     *
     * @param format the format, one of {@link #formats()}
     * @param <T>    the type of its value
     * @return the value; completed exceptionally with {@link TransferGoneException} when it is not,
     *         or no longer, on offer
     */
    <T> CompletionStage<T> read(DataFormat<T> format);
}
