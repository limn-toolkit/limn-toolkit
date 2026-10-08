package limn.transfer;

import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A drag about to begin: what it carries, what may be done with it, and what the source wants to
 * hear when it is over. A widget answers one from {@code onDragStart}:
 *
 * <pre>{@code
 * protected Drag onDragStart(DragStartEvent event) {
 *     Order order = orderAt(event.x(), event.y());
 *     return order == null ? null
 *             : Drag.of(Transfer.of(ORDER, order).with(DataFormat.TEXT, order.summary()))
 *                     .allow(DropAction.MOVE, DropAction.COPY)
 *                     .describedAs("Order " + order.number())
 *                     .onEnd(action -> { if (action == DropAction.MOVE) remove(order); });
 * }
 * }</pre>
 */
public final class Drag {

    private final Transfer content;
    private Set<DropAction> actions = Set.of(DropAction.COPY);
    private @Nullable Image image;
    private float hotX;
    private float hotY;
    private String description = "";
    private boolean withinWindow;
    private @Nullable Consumer<@Nullable DropAction> onEnd;
    private boolean ended;

    private Drag(Transfer content) {
        this.content = content;
    }

    /**
     * @param content what is dragged
     * @return a drag that allows a copy, until {@link #allow} says otherwise
     */
    public static Drag of(Transfer content) {
        return new Drag(Objects.requireNonNull(content, "content"));
    }

    /**
     * Says what a target may do with the content. With no modifier key held, the first is what
     * is proposed.
     *
     * @param first the action the source prefers
     * @param more  the others it allows
     * @return this drag
     */
    public Drag allow(DropAction first, DropAction... more) {
        Set<DropAction> allowed = new LinkedHashSet<>();
        allowed.add(Objects.requireNonNull(first, "first"));
        for (DropAction action : more) {
            allowed.add(Objects.requireNonNull(action, "action"));
        }
        actions = Collections.unmodifiableSet(allowed);
        return this;
    }

    /**
     * Shows a picture under the pointer while the drag lasts.
     *
     * @param newImage the picture
     * @param hotSpotX where in it the pointer is, from its left edge, in points
     * @param hotSpotY where in it the pointer is, from its top edge, in points
     * @return this drag
     */
    public Drag image(Image newImage, float hotSpotX, float hotSpotY) {
        image = Objects.requireNonNull(newImage, "image");
        hotX = hotSpotX;
        hotY = hotSpotY;
        return this;
    }

    /**
     * Names what is dragged for someone who cannot see it: a keyboard drag announces "Dragging"
     * and this.
     *
     * @param what the name, in the user's language
     * @return this drag
     */
    public Drag describedAs(String what) {
        description = Objects.requireNonNull(what, "what");
        return this;
    }

    /**
     * Keeps the drag inside the window it began in: it is never handed to the system, and no
     * other program is offered it. What a table's column header is, for one.
     *
     * @return this drag
     */
    public Drag withinWindow() {
        withinWindow = true;
        return this;
    }

    /**
     * Hears the end of the drag, once, on the UI thread: the action a target carried out, and no
     * action at all when the drag was cancelled or nothing took it.
     *
     * @param result what hears it
     * @return this drag
     */
    public Drag onEnd(Consumer<@Nullable DropAction> result) {
        onEnd = Objects.requireNonNull(result, "result");
        return this;
    }

    /** @return what is dragged */
    public Transfer content() {
        return content;
    }

    /** @return what a target may do, the source's preference first */
    public Set<DropAction> actions() {
        return actions;
    }

    /** @return the picture shown under the pointer, or null */
    public @Nullable Image image() {
        return image;
    }

    /** @return where in the picture the pointer is, from its left edge */
    public float hotSpotX() {
        return hotX;
    }

    /** @return where in the picture the pointer is, from its top edge */
    public float hotSpotY() {
        return hotY;
    }

    /** @return the name given with {@link #describedAs}, or the empty string */
    public String description() {
        return description;
    }

    /** @return whether the drag stays in its window */
    public boolean isWithinWindow() {
        return withinWindow;
    }

    /**
     * Tells the source the drag is over; for the scene, or a backend, that drove it. The first
     * call tells, the others do nothing.
     *
     * @param action what a target carried out, or null
     */
    public void end(@Nullable DropAction action) {
        if (!ended) {
            ended = true;
            Consumer<@Nullable DropAction> listener = onEnd;
            if (listener != null) {
                listener.accept(action);
            }
        }
    }
}
