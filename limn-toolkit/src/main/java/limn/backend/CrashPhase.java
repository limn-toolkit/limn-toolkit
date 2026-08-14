package limn.backend;

/** Where application code was executing when a contained crash was caught. */
public enum CrashPhase {
    /** Scene frame production: pending disposals, tick, layout, paint. */
    FRAME,
    /** Input event dispatch to widget/application handlers. */
    INPUT,
    /** A task posted to the UI thread (drained between frames). */
    TASK,
    /** An animation ticker. */
    TICKER,
    /**
     * A media decode thread: one that is not the UI thread, not owned by the
     * event loop, and therefore reachable by none of the other phases. An
     * exception here stops that stream and nothing else; the player also keeps
     * it, so a widget can show the failure rather than only logging it.
     */
    DECODE,
    /**
     * Native event polling: application code resident in window callbacks
     * (live-resize repaint, close-request veto, content-scale listener) whose
     * exception surfaces when the native poll returns.
     */
    EVENT_POLL,
    /** Window teardown callbacks (focus-lost handlers, close observers). */
    WINDOW_CLOSE
}
