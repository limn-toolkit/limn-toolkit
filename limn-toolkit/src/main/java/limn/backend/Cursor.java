package limn.backend;

/**
 * A standard mouse-cursor shape a widget can request while hovered: the OS's
 * native pointer, so it looks and feels exactly like every other application.
 *
 * <p>Widgets declare one via {@link limn.scene.Widget#setCursor}; the scene
 * resolves the hovered widget's cursor (walking up ancestors so a container can
 * set a cursor for its whole subtree) and pushes it to the window through
 * {@link NativeWindow#setCursor}. {@link #DEFAULT} is the plain arrow, the
 * fallback when nothing under the pointer requests otherwise.
 *
 * <p>A shape unsupported by the platform falls back to {@link #DEFAULT}.
 */
public enum Cursor {

    /** The plain arrow, the default when nothing requests otherwise. */
    DEFAULT,

    /** Pointing hand, the "this is clickable" hint (buttons, checkboxes, tabs, links). */
    POINTER,

    /** I-beam: editable or selectable text (text fields and areas). */
    TEXT,

    /** Crosshair: precise picking/placement. */
    CROSSHAIR,

    /** Horizontal (east–west ↔) resize. */
    RESIZE_EW,

    /** Vertical (north–south ↕) resize. */
    RESIZE_NS,

    /** Diagonal (north-east ↔ south-west ⤢) resize. */
    RESIZE_NESW,

    /** Diagonal (north-west ↔ south-east ⤡) resize. */
    RESIZE_NWSE,

    /** Move / resize-all (✥). */
    MOVE,

    /** Not-allowed (🚫): the action can't happen here. */
    NOT_ALLOWED
}
