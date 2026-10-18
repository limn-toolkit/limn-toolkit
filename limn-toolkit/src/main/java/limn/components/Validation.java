package limn.components;

/**
 * What an application has decided about the value a control holds: nothing yet, wrong, doubtful,
 * accepted, or worth a note. The application decides it, in a handler or a watcher, and the
 * toolkit shows it, on the border of a {@link TextField} or a {@link TextArea}.
 *
 * <p>Only {@link #ERROR} reaches a reader as a state: each platform has one flag for a value that
 * failed validation and none for the other three, and a field that passed announced as failing
 * would be the wrong fact.
 */
public enum Validation {
    /** Nothing has been decided, or the decision was taken back: the control draws as it rests. */
    NONE,
    /** The value is refused; drawn in the theme's {@link Theme#danger() danger} colour. */
    ERROR,
    /** The value is taken with a doubt; drawn in the theme's {@link Theme#warning() warning} colour. */
    WARNING,
    /** The value is accepted; drawn in the theme's {@link Theme#success() success} colour. */
    SUCCESS,
    /** The value carries a note; drawn in the theme's {@link Theme#info() info} colour. */
    INFO
}
