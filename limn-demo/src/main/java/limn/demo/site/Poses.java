package limn.demo.site;

import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.ListView;
import limn.components.PopupMenu;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static limn.testing.SceneDriver.drive;

/**
 * What the harness does to a scene before its still, for the few entries whose subject exists only
 * after a gesture: a context menu is a list until somebody right-clicks it, and a still of the
 * list is a picture of something else.
 *
 * <p>A pose is the film's counterpart for the poster. A film runs after the still and is a
 * function of the built scene for the reason {@link Films} gives; a pose runs once, after the
 * first frame has laid the scene out and before the warm-up that settles what it opened, so the
 * still shows the result. It lives here rather than in the scene, as a film does, so that what
 * the site publishes as the code sample stays the component's own code: the reader is shown how
 * to give a list a menu, not how this repository opens one for a photograph.
 *
 * <p>A pose acts as the user does, through the window's input, so what the picture shows is what
 * the gesture produces, and only what the capture needs is pinned around it: the menu is drawn
 * inside the window, because a menu window of its own is not in the framebuffer the capture reads.
 */
final class Poses {

    private Poses() {
    }

    /** The pose of the entry with this id, or null for an entry whose still needs none. */
    static Consumer<GalleryScenes.Built> forEntry(String id) {
        return switch (id) {
            case "popup-menu" -> Poses::popupMenu;
            default -> null;
        };
    }

    /**
     * A right-click on the selected row, about a third of the way along it, where a user who
     * wants that file's menu presses.
     *
     * <p>{@link PopupMenu#setDefaultDisplayMode} is the documented switch for this, an
     * application screenshotted for its own documentation, and it is put back as soon as the
     * menu is open: it is process-wide, and every later entry should be captured as it was.
     *
     * <p>A right-click that opens nothing fails the capture, loudly, rather than publishing a card
     * of a list under the name of a menu. The one known way there: {@code PopupMenu} opens nothing
     * on a window that reports no display, in the scene included, and a capture run in which the
     * windowing system reports no monitor at all has a window like that: one on a Mac did, on
     * 2026-10-25, and drew this card as a list with nothing open over it.
     */
    private static void popupMenu(GalleryScenes.Built built) {
        ListView<?> list = (ListView<?>) only(built.content(), ListView.class);
        Object selected = list.selectedItem();
        Widget<?> row = null;
        for (Widget<?> label : under(list, Label.class)) {
            if (((Label) label).text().equals(String.valueOf(selected))) {
                row = label;
            }
        }
        if (row == null) {
            throw new IllegalStateException("the popup-menu entry's list shows no row for its"
                    + " selected item " + selected + "; the pose has nothing to right-click");
        }
        Vec2 at = row.localToScene(row.width() * 0.35f, row.height() * 0.5f);
        DisplayMode before = PopupMenu.defaultDisplayMode();
        PopupMenu.setDefaultDisplayMode(DisplayMode.IN_SCENE);
        try {
            drive(built.scene()).mouseMoved(at.x(), at.y());
            drive(built.scene()).mouseButton(Keys.MOUSE_RIGHT, true, 0, at.x(), at.y());
            drive(built.scene()).mouseButton(Keys.MOUSE_RIGHT, false, 0, at.x(), at.y());
            drive(built.scene()).inputBatchEnded();
        } finally {
            PopupMenu.setDefaultDisplayMode(before);
        }
        // An open menu holds the keyboard: the focus is in its surface, an overlay with no parent,
        // and not under the scene's root.
        Widget<?> top = built.scene().focusedWidget();
        while (top != null && top.parent() != null) {
            top = top.parent();
        }
        if (top == null || top == built.scene().root()) {
            throw new IllegalStateException("the right-click on \"" + selected + "\" opened no menu"
                    + (built.scene().window().display() == null
                    ? ": the window reports no display, and PopupMenu opens nothing on such a"
                    + " window, in the scene included; capture where a monitor is visible"
                    : ""));
        }
    }

    /** The one widget of {@code type} under {@code root}; throws naming the count otherwise. */
    private static Widget<?> only(Widget<?> root, Class<?> type) {
        List<Widget<?>> found = under(root, type);
        if (found.size() != 1) {
            throw new IllegalStateException("a pose aims at the one " + type.getSimpleName()
                    + " of its scene, and this one holds " + found.size());
        }
        return found.get(0);
    }

    /** Everything of {@code type} in {@code root}'s tree, root included, in paint order. */
    private static List<Widget<?>> under(Widget<?> root, Class<?> type) {
        List<Widget<?>> queue = new ArrayList<>();
        List<Widget<?>> found = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            Widget<?> widget = queue.get(i);
            if (type.isInstance(widget)) {
                found.add(widget);
            }
            queue.addAll(widget.children());
        }
        return found;
    }
}
