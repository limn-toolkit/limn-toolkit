package limn.backend.spi;

import limn.graphics.Image;
import limn.transfer.DropAction;
import limn.transfer.Transfer;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * A drag the scene hands to the system: what it carries, what may be done with it, the picture
 * the system shows under the pointer, and who hears the end.
 *
 * @param content  what is dragged
 * @param actions  what a target may do with it, the source's preference first
 * @param image    the picture under the pointer, in framebuffer pixels, or null for none
 * @param x        where the picture's left edge is as the drag begins, in the window's logical points
 * @param y        where the picture's top edge is as the drag begins, in the window's logical points
 * @param width    the picture's width on screen, in logical points
 * @param height   the picture's height on screen, in logical points
 * @param ended    told on the UI thread, once, when the system's drag is over, with the action a
 *                 target carried out in this program or another, and with no action when
 *                 nothing took it
 */
public record DragRequest(Transfer content, Set<DropAction> actions, @Nullable Image image,
        float x, float y, float width, float height, Consumer<@Nullable DropAction> ended) {
}
