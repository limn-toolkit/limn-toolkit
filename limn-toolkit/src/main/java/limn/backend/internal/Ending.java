package limn.backend.internal;

import limn.backend.ClosePolicy;
import limn.backend.CloseRequest;
import limn.backend.CloseRequestHandler;
import limn.backend.NativeWindow;
import limn.backend.QuitHandler;
import limn.backend.QuitRequest;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * How a window's close is decided and how an application ends by asking once, in one place
 * every backend shares: a backend reports a user's close gesture and a request to quit, and supplies
 * its windows front to back and a way to end; the order, the one-question-at-a-time rule and the
 * refusals are decided here.
 *
 * <p>Everything here runs on the UI thread, which is the backend's to check.
 */
public final class Ending {

    /** What a backend supplies. */
    public interface Host {

        /**
         * @return every open window that no other window owns (through
         *         {@link limn.backend.WindowConfig#popup(NativeWindow, int, int)} or
         *         {@link limn.backend.WindowConfig#ownedBy}), the window used last first
         */
        List<? extends NativeWindow> topLevelFrontToBack();

        /**
         * Every window agreed: close every window, owned ones included, and end the loop with
         * {@link limn.backend.LoopEnd#QUIT}.
         */
        void endApplication();
    }

    private final Host host;
    private @Nullable QuitHandler quitHandler;

    /** The request each window has not answered yet, and who waits for the answer. */
    private final Map<NativeWindow, Asked> unanswered = new IdentityHashMap<>();

    /** The application-wide request being answered, or {@code null}. */
    private @Nullable Round round;

    /** @param host the backend's windows and its way to end */
    public Ending(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** @param handler the handler {@link #requestQuit()} asks first, or {@code null} for none */
    public void setQuitHandler(@Nullable QuitHandler handler) {
        this.quitHandler = handler;
    }

    /** @return whether an application-wide request is being answered */
    public boolean quitPending() {
        return round != null;
    }

    /**
     * The user asked {@code window} to close. Asks its handler, unless a question to it is already
     * unanswered, and does what its policy says once allowed.
     *
     * @param window the window the user closed
     */
    public void userClosed(NativeWindow window) {
        if (window.isClosed() || unanswered.containsKey(window)) {
            return;
        }
        ClosePolicy policy = window.closePolicy();
        if (policy == ClosePolicy.END_APPLICATION) {
            requestQuit();
            return;
        }
        ask(window, CloseRequest.Reason.USER_CLOSE, allowed -> {
            if (allowed && !window.isClosed()) {
                if (policy == ClosePolicy.HIDE) {
                    window.hide();
                } else {
                    window.requestClose();
                }
            }
        });
    }

    /** Asks to end the application, or joins the request already being answered. */
    public void requestQuit() {
        if (round != null) {
            return;
        }
        Round started = new Round();
        round = started;
        QuitHandler handler = quitHandler;
        if (handler == null) {
            started.next();
            return;
        }
        Quit request = new Quit(started);
        try {
            handler.quitRequested(request);
        } catch (RuntimeException | Error thrown) {
            if (!request.isAnswered()) {
                request.refuse();
            }
            throw thrown;
        }
    }

    /**
     * {@code window} is gone. A question to it that was never answered counts as allowed, so a
     * request waiting on it goes on.
     *
     * @param window the window destroyed
     */
    public void forget(NativeWindow window) {
        Asked asked = unanswered.get(window);
        if (asked != null && !asked.isAnswered()) {
            asked.answer(true);
        }
    }

    private void ask(NativeWindow window, CloseRequest.Reason reason, Consumer<Boolean> then) {
        CloseRequestHandler handler = window.closeRequestHandler();
        if (handler == null) {
            then.accept(true);
            return;
        }
        Asked asked = new Asked(window, reason);
        asked.waiting.add(then);
        unanswered.put(window, asked);
        try {
            handler.closeRequested(asked);
        } catch (RuntimeException | Error thrown) {
            if (!asked.isAnswered()) {
                asked.answer(false);
            }
            throw thrown;
        }
    }

    /** One application-wide request: the windows that agreed, and asking the next one. */
    private final class Round {

        private final Set<NativeWindow> agreed = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Asks the first window not yet asked, or ends the application when none is left. */
        void next() {
            while (round == this) {
                NativeWindow window = firstNotAgreed();
                if (window == null) {
                    round = null;
                    host.endApplication();
                    return;
                }
                Asked pending = unanswered.get(window);
                if (pending != null) {
                    pending.waiting.add(allowed -> answered(window, allowed));
                    return;
                }
                if (window.closeRequestHandler() == null) {
                    agreed.add(window);
                    continue;
                }
                // A handler that answers inside its call is gone on from here, in this loop, so a
                // row of windows that all answer at once does not recurse; one that answers later
                // is gone on from answered().
                Answer inline = new Answer();
                ask(window, CloseRequest.Reason.APPLICATION_END, allowed -> {
                    if (inline.returned) {
                        answered(window, allowed);
                    } else {
                        inline.given = true;
                        if (allowed) {
                            agreed.add(window);
                        } else {
                            cancel();
                        }
                    }
                });
                if (!inline.given) {
                    inline.returned = true;
                    return;
                }
            }
        }

        private void answered(NativeWindow window, boolean allowed) {
            if (round != this) {
                return;
            }
            if (!allowed) {
                cancel();
                return;
            }
            agreed.add(window);
            next();
        }

        private void cancel() {
            if (round == this) {
                round = null;
            }
        }

        private @Nullable NativeWindow firstNotAgreed() {
            for (NativeWindow window : host.topLevelFrontToBack()) {
                if (!window.isClosed() && !agreed.contains(window)) {
                    return window;
                }
            }
            return null;
        }
    }

    /** Whether a window's handler answered inside its call, or its call returned first. */
    private static final class Answer {
        boolean given;
        boolean returned;
    }

    /** A window's question. */
    private final class Asked implements CloseRequest {

        private final NativeWindow window;
        private final Reason reason;
        private final List<Consumer<Boolean>> waiting = new ArrayList<>(2);
        private boolean answered;

        Asked(NativeWindow window, Reason reason) {
            this.window = window;
            this.reason = reason;
        }

        @Override
        public NativeWindow window() {
            return window;
        }

        @Override
        public Reason reason() {
            return reason;
        }

        @Override
        public void allow() {
            answer(true);
        }

        @Override
        public void refuse() {
            answer(false);
        }

        @Override
        public boolean isAnswered() {
            return answered;
        }

        void answer(boolean allowed) {
            if (answered) {
                throw new IllegalStateException("the close request of '" + window.title()
                        + "' was already answered");
            }
            answered = true;
            unanswered.remove(window, this);
            for (Consumer<Boolean> then : List.copyOf(waiting)) {
                then.accept(allowed);
            }
        }

        @Override
        public String toString() {
            return "CloseRequest[" + window.title() + ", " + reason + (answered ? ", answered]" : "]");
        }
    }

    /** The application-wide question to the quit handler. */
    private static final class Quit implements QuitRequest {

        private final Round round;
        private boolean answered;

        Quit(Round round) {
            this.round = round;
        }

        @Override
        public void allow() {
            answer();
            round.next();
        }

        @Override
        public void refuse() {
            answer();
            round.cancel();
        }

        @Override
        public boolean isAnswered() {
            return answered;
        }

        private void answer() {
            if (answered) {
                throw new IllegalStateException("the quit request was already answered");
            }
            answered = true;
        }

        @Override
        public String toString() {
            return "QuitRequest[" + (answered ? "answered]" : "unanswered]");
        }
    }
}
