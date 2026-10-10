package limn.scene.event;

import limn.transfer.DropAction;
import limn.transfer.Offer;
import org.jspecify.annotations.Nullable;

/**
 * What was dragged is dropped on the widget that last accepted it. The widget reads what it
 * wants from {@link #offer()} and carries out {@link #action()}; unless it calls {@link #reject()}
 * before returning, the source is told that action was done.
 */
public final class DropEvent {

    private final float x;
    private final float y;
    private final int modifiers;
    private final boolean keyboard;
    private final Offer offer;
    private final DropAction action;
    private final @Nullable Object source;
    private boolean rejected;

    /**
     * @param x         the pointer, in scene coordinates
     * @param y         the pointer, in scene coordinates
     * @param modifiers the modifier keys held
     * @param keyboard  whether the keyboard drove the drag
     * @param offer     what was dragged
     * @param action    the action the widget accepted
     * @param source    the widget the drag began on, or null when it began in another program
     */
    public DropEvent(float x, float y, int modifiers, boolean keyboard, Offer offer, DropAction action,
            @Nullable Object source) {
        this.x = x;
        this.y = y;
        this.modifiers = modifiers;
        this.keyboard = keyboard;
        this.offer = offer;
        this.action = action;
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

    /** @return whether the keyboard drove the drag: the place is the one the widget kept */
    public boolean isKeyboard() {
        return keyboard;
    }

    /** @return what was dragged; a read may complete after this event returns */
    public Offer offer() {
        return offer;
    }

    /** @return the action this widget accepted while the drag was over it */
    public DropAction action() {
        return action;
    }

    /** @return the widget the drag began on, or null when it began in another program */
    public @Nullable Object source() {
        return source;
    }

    /** Refuses the drop after all: the source is told that nothing took it. */
    public void reject() {
        rejected = true;
    }

    /** @return whether the drop was refused */
    public boolean isRejected() {
        return rejected;
    }
}
