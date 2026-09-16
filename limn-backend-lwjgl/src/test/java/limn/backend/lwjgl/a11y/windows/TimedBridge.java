package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;

import java.util.function.Consumer;

/**
 * The real bridge with a stopwatch around every emit, for the live probe to count with.
 *
 * <p>A delegating bridge and not a subclass, because what is being measured is the seam the scene
 * sees: the scene hands a bridge one tree and then every event of that tree's difference, so
 * counting at this seam counts what one frame's difference produces, which is the number
 * ADR&nbsp;039 &sect;13.19 asks for.
 *
 * <p><b>What the stopwatch reads is the hand-over and not the raise</b> (corrected 2026-09-16).
 * This javadoc said "on Windows {@code emit} raises straight through — there is no queue between it
 * and {@code UiaRaiseAutomationEvent} — so the time one takes <em>is</em> the cost of one raise",
 * and that stopped being true on 2026-09-07, when the bridge gained the bounded queue
 * &sect;1.10 asks of every bridge ({@link UiaEvents}, &sect;13.28): {@code emit} offers and returns,
 * and a thread of the bridge's own raises. So the per-event times here are the cost of offering,
 * which is what the scene actually pays in its frame, and the cost of a raise is the number
 * &sect;13.28 measured with a reader attached. The counts — how many events a frame produces and how
 * many of them reach the platform at all — are unaffected and are what the probe is for.
 *
 * <p>Every member the scene calls is forwarded unchanged, including {@code reentrant} and the
 * frame's end, so that wrapping the bridge changes nothing about when it walks, what it releases,
 * or when it flushes what a collapse left owed.
 */
final class TimedBridge implements AccessibilityBridge {

    private final UiaBridge real;
    private final EmitTally tally;
    private final Consumer<String> log;
    private int frame;

    /**
     * @param real  the bridge the backend opened
     * @param tally where the counts go
     * @param log   where a line per published frame goes, or {@code null} for nowhere
     */
    TimedBridge(UiaBridge real, EmitTally tally, Consumer<String> log) {
        this.real = real;
        this.tally = tally;
        this.log = log;
    }

    @Override
    public boolean isListening() {
        return real.isListening();
    }

    @Override
    public boolean needsPrimingPublish() {
        return real.needsPrimingPublish();
    }

    @Override
    public void publish(AccessibleTree tree, boolean reentrant) {
        // The frame before this one is complete: nothing more will be emitted for it.
        if (log != null && frame > 0) {
            log.accept(String.format(java.util.Locale.ROOT,
                    "FRAME %d events=%d raised=%d", frame,
                    tally.eventsInOpenFrame(), tally.raisedInOpenFrame()));
        }
        tally.published();
        frame++;
        real.publish(tree, reentrant);
    }

    @Override
    public void emit(AccessibleEvent event) {
        // A focus change -- and a window's activation, the focus change into it -- is raised on
        // the tree's effective focus whether or not a client holds it, because the bridge mints
        // the element (WINDOWS-NEW-4); anything else only for a held one. Decided here, before
        // the drain runs, so it is the estimate it always was: a repeat of the focus the drain
        // already announced, which it skips (semantics 4), is still counted raised.
        boolean focus = event.type() == AccessibleEvent.Type.FOCUS_CHANGED
                || event.type() == AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED
                || event.type() == AccessibleEvent.Type.WINDOW_ACTIVATED;
        boolean raised = focus ? real.tree().effectiveFocus() != 0
                : real.holdsElementFor(event.nodeId());
        long start = System.nanoTime();
        real.emit(event);
        tally.emitted(System.nanoTime() - start, raised);
    }

    /**
     * <p>Forwarded, and not a no-op: since 2026-09-16 the frame's end is the publish boundary the
     * Windows bridge flushes an owed re-announcement at ({@code UiaBridge#frameEnded}, ADR&nbsp;039
     * §2.4). A wrapper that swallowed it would leave the probe's own runs — the only Windows runs
     * there are — as the one place a collapse whose tail is nothing but structure never says where
     * the user is. Not counted: it is a marker and not an event of the frame's difference.
     */
    @Override
    public void frameEnded() {
        real.frameEnded();
    }

    @Override
    public void attach(Host host) {
        real.attach(host);
    }

    @Override
    public void detach() {
        // Closed here and not after the event loop: on this guest the platform half's release
        // has ended runs in native code, and a summary printed after it is a summary never seen.
        tally.close();
        if (log != null) {
            log.accept(tally.summary());
        }
        real.detach();
    }

    /** @return how many trees have been published through this */
    int frames() {
        return frame;
    }
}
