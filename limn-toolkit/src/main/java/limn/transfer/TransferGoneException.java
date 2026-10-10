package limn.transfer;

/**
 * What a read of an {@link Offer} fails with when the thing offered is no longer there: another
 * program replaced the clipboard between the offer and the read, the offer never had that format,
 * or the program that owned it left without handing it over.
 */
public final class TransferGoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what was asked for and why it could not be had */
    public TransferGoneException(String message) {
        super(message);
    }

    /**
     * @param message what was asked for and why it could not be had
     * @param cause   what the platform, or a format's decoder, threw
     */
    public TransferGoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
