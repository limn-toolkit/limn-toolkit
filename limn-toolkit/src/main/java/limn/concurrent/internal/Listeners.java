package limn.concurrent.internal;

import limn.backend.CrashPhase;
import limn.backend.Crashes;

import java.lang.System.Logger.Level;
import java.util.Arrays;

/**
 * Copy-on-write listener arrays for UI-thread-confined fan-out: one shape for every notification
 * in the toolkit, so a widget's watchers, a scene's observers and a process-wide axis all
 * register, unregister, dispatch and contain the same way.
 *
 * <p>A holder is a plain {@code L[]} field, null when empty, so an unused one costs four bytes
 * and a dispatch allocates nothing. The array is swapped on registration and on unregistration
 * rather than mutated, which is where the reentrancy rule comes from for free: a dispatch in
 * flight keeps walking the array it started with, so <b>a registration made during a
 * notification does not receive that notification, and an unregistration made during one still
 * runs for it</b>. Four places in this repository relied on those two halves and wrote them four
 * different ways; this is the one way.
 *
 * <p>Measured against seven other shapes on Zulu 21.0.12 (aarch64, {@code -XX:+UseSerialGC},
 * escape analysis off, least of 6 × 100 000): a plain array is fastest at every size and the only
 * one that allocates nothing at every size — 1.47 ns empty, 1.91 ns at one listener, 5.84 ns at
 * three, 14.21 ns at eight. It is copy-on-write without the concurrency machinery a
 * UI-thread-confined mechanism does not need.
 *
 * <p>Static over an array rather than an object wrapping a list, because a holder object per
 * widget would cost the twenty-four bytes a null field does not, ten thousand times over.
 *
 * @see limn.concurrent.Subscription
 */
public final class Listeners {

    private static final System.Logger LOG = System.getLogger(Listeners.class.getName());

    private Listeners() {
    }

    /**
     * {@code current} (may be null) with {@code listener} appended.
     *
     * <p><b>Appends unconditionally</b>: the same listener registered twice is registered twice
     * and is notified twice. De-duplicating by identity is the wrong instrument — two
     * subscriptions are two subscriptions, and a mechanism that silently merges them is how a
     * caller loses one it meant to keep. What answers the double-register is the handle: a
     * second registration yields a second {@link limn.concurrent.Subscription} the caller must decide what to do
     * with.
     *
     * @param current the array to append to, or null when nothing is registered
     * @param listener the listener; never null
     * @param empty a zero-length array of the listener type, which supplies the array type
     * @return a new array; never null
     */
    public static <L> L[] added(L[] current, L listener, L[] empty) {
        if (current == null) {
            L[] one = Arrays.copyOf(empty, 1);
            one[0] = listener;
            return one;
        }
        L[] grown = Arrays.copyOf(current, current.length + 1);
        grown[current.length] = listener;
        return grown;
    }

    /**
     * {@code current} without the <b>first</b> entry identical to {@code listener}, or null when
     * that empties it. Removes one registration, never two, which is what lets a handle's
     * idempotence and a listener registered twice coexist.
     *
     * @param current the array to remove from, or null
     * @param listener the listener, compared by identity
     * @return the new array, null when empty, or {@code current} when it was not there
     */
    public static <L> L[] removed(L[] current, L listener) {
        if (current == null) {
            return null;
        }
        int at = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == listener) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return current;
        }
        if (current.length == 1) {
            return null;
        }
        L[] shrunk = Arrays.copyOf(current, current.length - 1);
        System.arraycopy(current, at + 1, shrunk, at, current.length - 1 - at);
        return shrunk;
    }

    /**
     * {@code current} without every entry of {@code taken}: the removal a one-shot list makes
     * after it has run its snapshot, so a registration made <em>during</em> that run survives
     * instead of being cleared unread.
     *
     * @param current the array to remove from, or null
     * @param taken the entries that ran, compared by identity
     * @return the new array, or null when empty
     */
    public static <L> L[] removedAll(L[] current, L[] taken) {
        L[] left = current;
        for (L one : taken) {
            left = removed(left, one);
        }
        return left;
    }

    /**
     * Contains one listener's throw: logs it and reports it under {@code phase}, and the caller
     * goes on to the next listener.
     *
     * <p>A throwing listener is contained and <b>not evicted</b>. Eviction is a ticker's policy
     * and is right there, because a ticker fires every frame and keeping a thrower would re-throw
     * every frame; a listener fires when something changes, so containing and continuing is the
     * policy — and it is what stops an inspector or a bridge from being able to silence the
     * application's own handler.
     *
     * @param phase the crash phase to report under
     * @param error what the listener threw
     */
    public static void failed(CrashPhase phase, Throwable error) {
        LOG.log(Level.ERROR, "a " + phase + " listener threw; the remaining listeners still run", error);
        Crashes.report(phase, error);
    }
}
