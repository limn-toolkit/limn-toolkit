package limn.backend;

/**
 * What became of a print asked of the system through {@link Printer}.
 *
 * <p>No system tells a program whether paper came out: {@link #PRINTED} means the system took the
 * job, which is as far as any of them answers.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum PrintResult {

    /** The system's dialog was confirmed and the system took the job. */
    PRINTED,

    /** The user closed the system's dialog without printing. */
    CANCELLED,

    /**
     * Nothing was asked: there is no printing under this window (a headless or embedded window, a
     * Linux desktop with no print portal), or the window had closed.
     */
    UNAVAILABLE,

    /** The system was asked and refused or failed; the reason is logged at {@code WARNING}. */
    FAILED
}
