package limn.scene.event;

import limn.transfer.DropAction;
import limn.transfer.Offer;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * A drag is over a widget. The widget looks at what is offered and where, and calls
 * {@link #accept} if it would take it there; it then draws where the drop would land. Saying
 * nothing passes the question to its parent.
 *
 * <p>A drag driven from the keyboard has no pointer: {@link #isKeyboard()} is true, the point is
 * the widget's centre the first time, and each arrow key arrives as a {@link #stepX()} or
 * {@link #stepY()} that moves the place the widget itself keeps. {@link #describePlace} says that
 * place for a screen reader.
 */
public final class DragEvent {

    private final float x;
    private final float y;
    private final int modifiers;
    private final boolean keyboard;
    private final int stepX;
    private final int stepY;
    private final Offer offer;
    private final Set<DropAction> sourceActions;
    private final DropAction proposed;
    private final @Nullable Object source;
    private @Nullable DropAction accepted;
    private @Nullable String place;

    /**
     * @param x             the pointer, in scene coordinates
     * @param y             the pointer, in scene coordinates
     * @param modifiers     the modifier keys held
     * @param keyboard      whether the keyboard drives the drag
     * @param stepX         -1, 0 or 1: an arrow key pressed in a keyboard drag, in reading order
     * @param stepY         -1, 0 or 1: an arrow key pressed in a keyboard drag
     * @param offer         what is dragged
     * @param sourceActions what the source allows, its preference first
     * @param proposed      the action the modifier keys ask for, among those
     * @param source        the widget the drag began on, or null when it began in another program
     */
    public DragEvent(float x, float y, int modifiers, boolean keyboard, int stepX, int stepY,
            Offer offer, Set<DropAction> sourceActions, DropAction proposed, @Nullable Object source) {
        this.x = x;
        this.y = y;
        this.modifiers = modifiers;
        this.keyboard = keyboard;
        this.stepX = stepX;
        this.stepY = stepY;
        this.offer = offer;
        this.sourceActions = sourceActions;
        this.proposed = proposed;
        this.source = source;
    }

    /** @return the pointer, in scene coordinates */
    public float x() {
        return x;
    }

    /** @return the pointer, in scene coordinates */
    public float y() {
        return y;
    }

    /** @return the modifier keys held, as {@code Keys.MOD_*} bits */
    public int modifiers() {
        return modifiers;
    }

    /** @return whether the keyboard drives the drag */
    public boolean isKeyboard() {
        return keyboard;
    }

    /** @return -1, 0 or 1: the arrow key pressed, along the line, in a keyboard drag */
    public int stepX() {
        return stepX;
    }

    /** @return -1, 0 or 1: the arrow key pressed, across the lines, in a keyboard drag */
    public int stepY() {
        return stepY;
    }

    /** @return what is dragged: its formats now, its values at the drop */
    public Offer offer() {
        return offer;
    }

    /** @return what the source allows, its preference first */
    public Set<DropAction> sourceActions() {
        return sourceActions;
    }

    /**
     * @return the action the user is asking for: the one the modifier keys name where the source
     *         allows it, and otherwise the source's preference
     */
    public DropAction proposedAction() {
        return proposed;
    }

    /** @return the widget the drag began on, or null when it began in another program */
    public @Nullable Object source() {
        return source;
    }

    /** Takes the drop here, with the {@linkplain #proposedAction() proposed} action. */
    public void accept() {
        accepted = proposed;
    }

    /**
     * Takes the drop here.
     *
     * @param action what the drop would do
     * @throws IllegalArgumentException if the source does not allow it
     */
    public void accept(DropAction action) {
        if (!sourceActions.contains(Objects.requireNonNull(action, "action"))) {
            throw new IllegalArgumentException("the source allows " + sourceActions + ", not " + action);
        }
        accepted = action;
    }

    /** @return whether a widget accepted */
    public boolean isAccepted() {
        return accepted != null;
    }

    /** @return the action accepted, or null */
    public @Nullable DropAction acceptedAction() {
        return accepted;
    }

    /**
     * Says where the drop would land, for a screen reader following a keyboard drag: "before
     * Order 42", "into Customers".
     *
     * @param where the place, in the user's language
     */
    public void describePlace(String where) {
        place = Objects.requireNonNull(where, "where");
    }

    /** @return the place described, or null */
    public @Nullable String place() {
        return place;
    }
}
