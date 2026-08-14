package limn.icons.tabler;

/**
 * Tabler's <b>Gestures</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerGestures implements TablerIcon {

    HAND_CLICK("hand-click"),
    HAND_CLICK_OFF("hand-click-off"),
    HAND_FINGER("hand-finger"),
    HAND_FINGER_DOWN("hand-finger-down"),
    HAND_FINGER_LEFT("hand-finger-left"),
    HAND_FINGER_OFF("hand-finger-off"),
    HAND_FINGER_RIGHT("hand-finger-right"),
    HAND_GRAB("hand-grab"),
    HAND_LITTLE_FINGER("hand-little-finger"),
    HAND_LOVE_YOU("hand-love-you"),
    HAND_MIDDLE_FINGER("hand-middle-finger"),
    HAND_MOVE("hand-move"),
    HAND_OFF("hand-off"),
    HAND_RING_FINGER("hand-ring-finger"),
    HAND_STOP("hand-stop"),
    HAND_THREE_FINGERS("hand-three-fingers"),
    HAND_TWO_FINGERS("hand-two-fingers");

    private final String iconName;

    TablerGestures(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
