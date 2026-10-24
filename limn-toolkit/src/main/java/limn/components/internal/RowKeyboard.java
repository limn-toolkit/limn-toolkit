package limn.components.internal;

import limn.scene.Scene;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The keyboard's place inside a realized row, as a path of child indexes from the row's cell, and
 * its return to the same place in the cell realized afresh for that row: what lets a row kept for
 * a popup be let go of without taking a click into one of its controls back.
 */
public final class RowKeyboard {

    private RowKeyboard() {
    }

    /**
     * @param cell a row's cell
     * @return the path from {@code cell} to the widget holding the keyboard, or {@code null} when
     *         the keyboard is not inside it
     */
    public static int @Nullable [] placeIn(Widget<?> cell) {
        Scene scene = cell.scene();
        Widget<?> focused = scene == null ? null : scene.focusedWidget();
        if (focused == null) {
            return null;
        }
        int depth = 0;
        for (Widget<?> w = focused; w != cell; w = w.parent()) {
            if (w == null) {
                return null; // not inside the cell
            }
            depth++;
        }
        int[] path = new int[depth];
        Widget<?> w = focused;
        for (int i = depth - 1; i >= 0; i--) {
            Widget<?> parent = w.parent();
            path[i] = parent.children().indexOf(w);
            w = parent;
        }
        return path;
    }

    /**
     * Gives the keyboard to the widget at {@code path} in {@code cell}, or to {@code fallback} when
     * there is none there or it does not take the keyboard.
     *
     * @param cell     a row's cell, realized afresh
     * @param path     what {@link #placeIn} answered for the cell it replaces
     * @param fallback the list, table or tree
     */
    public static void giveBack(Widget<?> cell, int[] path, Widget<?> fallback) {
        Widget<?> w = cell;
        for (int index : path) {
            List<Widget<?>> children = w.children();
            if (index < 0 || index >= children.size()) {
                fallback.requestFocus();
                return;
            }
            w = children.get(index);
        }
        if (w.isFocusable() && w.scene() != null) {
            w.requestFocus();
        } else {
            fallback.requestFocus();
        }
    }
}
