package limn.concurrent.internal;

import limn.concurrent.Subscription;
import limn.concurrent.Ui;

import limn.backend.CrashPhase;

import java.util.Objects;

/**
 * Who wants to hear that a process-wide setting changed: the locale, the fonts, the default
 * control size, the default layout direction, the theme.
 *
 * <p>Five classes keep the same list with the same rules: every listener runs on a change, in
 * registration order, and a throwing one stops neither its neighbours nor the call that made the
 * change. This is the list once, over {@link Listeners}' plain array, so it reentres and contains
 * exactly the way every other fan-out in the toolkit does.
 *
 * <p><b>Registration hands back a {@link Subscription} and there is no remover.</b> The pair it
 * replaced was idempotent per instance — {@code addIfAbsent} — and the de-duplication went with
 * it deliberately: two subscriptions are two subscriptions, and a handle makes a double-register
 * unspellable, because a second call yields a second handle the caller must decide what to do
 * with. It also retires the keep-a-reference-and-pair-it-yourself bookkeeping that every
 * subscriber in this repository was hand-writing.
 *
 * <p>The array is volatile because these axes are process-wide: the setter that fires is on the
 * UI thread and so is every registration, but a field read from a listener the backend holds is
 * cheap enough that confining the publication is not worth reasoning about.
 */
public final class ChangeListeners {

    private static final Runnable[] EMPTY = new Runnable[0];

    private volatile Runnable[] listeners;

    /**
     * Subscribes, and hands back the one way to stop.
     *
     * @param listener what to run on a change
     * @return a handle that unsubscribes; cancelling it twice is a no-op
     */
    public Subscription observe(Runnable listener) {
        checkUiThread();
        Objects.requireNonNull(listener, "listener");
        listeners = Listeners.added(listeners, listener, EMPTY);
        return new Handle(listener);
    }

    /** @return how many registrations are held right now, for the tests that pin the purge */
    public int count() {
        Runnable[] snapshot = listeners;
        return snapshot == null ? 0 : snapshot.length;
    }

    /** Runs every listener, in registration order, containing each one's throw. */
    public void fire() {
        Runnable[] snapshot = listeners;
        if (snapshot == null) {
            return;
        }
        for (Runnable listener : snapshot) {
            try {
                listener.run();
            } catch (Throwable error) {
                Listeners.failed(CrashPhase.OBSERVER, error);
            }
        }
    }

    /**
     * The UI-thread check these axes use, skipped when no runtime is installed: a locale, a font
     * family and a palette are all readable and settable from a plain unit test with no window,
     * which is the leniency {@code I18n} already documents on its own mutators.
     */
    private static void checkUiThread() {
        if (Ui.isInstalled()) {
            Ui.checkUiThread();
        }
    }

    /** One registration, dropped once: a second cancel has nothing left to take off the list. */
    private final class Handle implements Subscription {

        private Runnable listener;

        Handle(Runnable listener) {
            this.listener = listener;
        }

        @Override
        public void cancel() {
            checkUiThread();
            Runnable taken = listener;
            if (taken == null) {
                return;
            }
            listener = null;
            listeners = Listeners.removed(listeners, taken);
        }
    }
}
