package limn.components.internal;

import org.jspecify.annotations.Nullable;

/**
 * What an application's handlers threw while a component took several steps that must all run (a
 * popup's close: the popups hung in it, its teardown, its own handler): the first throw is kept,
 * the later ones added to it as suppressed, and it is thrown once every step has run. A
 * {@link VirtualMachineError} is never held: it leaves at once.
 *
 * <p><b>Not API</b>: see the package. UI thread only.
 */
public final class HandlerFailures {

    private HandlerFailures() {
    }

    /**
     * @param held  what an earlier step threw, or null
     * @param error what this step threw
     * @return the throw to hold: {@code held} with {@code error} suppressed in it, or {@code error}
     * @throws VirtualMachineError when {@code error} is one, at once
     */
    public static Throwable add(@Nullable Throwable held, Throwable error) {
        if (error instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (held == null) {
            return error;
        }
        if (held != error) {
            held.addSuppressed(error);
        }
        return held;
    }

    /**
     * Throws what was held, as it is: a handler's throw is unchecked.
     *
     * @param held what the steps threw, or null for nothing
     */
    public static void rethrow(@Nullable Throwable held) {
        if (held instanceof RuntimeException e) {
            throw e;
        }
        if (held instanceof Error e) {
            throw e;
        }
        if (held != null) {
            throw new IllegalStateException(held);
        }
    }
}
