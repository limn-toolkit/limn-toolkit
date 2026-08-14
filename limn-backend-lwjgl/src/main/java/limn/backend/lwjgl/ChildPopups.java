package limn.backend.lwjgl;

import limn.backend.NativeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The popups a window owns (a combo's list, a menu cascade, a native dialog, any floating window
 * an app registers) and the two things that must happen to them when the owner moves or dies.
 * Pure bookkeeping over the {@link NativeWindow} interface, so it is unit-testable with fakes;
 * the GLFW half is only the callback that calls {@link #moveBy}.
 *
 * <p><b>Why the owner has to carry them.</b> Every popup here is a separate top-level window
 * placed at an absolute screen position next to the widget that opened it. The OS knows nothing
 * about that relationship: it will not close them with the owner, and it will not move them with
 * the owner. Left alone, dragging a window by its title bar strands its open dropdown wherever it
 * was. That is the single most "unfinished-looking" thing a toolkit made of real windows can do.
 */
final class ChildPopups {

    private record Child(NativeWindow window, NativeWindow.PopupKind kind) {
    }

    private final List<Child> children = new ArrayList<>();

    void add(NativeWindow popup, NativeWindow.PopupKind kind) {
        children.add(new Child(Objects.requireNonNull(popup, "popup"),
                Objects.requireNonNull(kind, "kind")));
    }

    void remove(NativeWindow popup) {
        children.removeIf(c -> c.window() == popup);
    }

    boolean contains(NativeWindow candidate) {
        for (Child child : children) {
            if (child.window() == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code candidate} is registered here as the owner's own content reaching outside
     * its frame, rather than as a window of its own.
     *
     * <p>This is the predicate an in-scene modal's exemption runs on, and the reason the kind is
     * recorded at all: the exemption exists so a host can keep drawing its dropdowns while its
     * overlay is up, and widening it to everything the host owns left a non-modal dialog usable
     * under a modal.
     */
    boolean ownsTransient(NativeWindow candidate) {
        for (Child child : children) {
            if (child.window() == candidate) {
                return child.kind() == NativeWindow.PopupKind.TRANSIENT;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return children.isEmpty();
    }

    /**
     * Translates every live popup by the same screen delta the owner just moved.
     *
     * <p>A translation, not a re-anchor: while the owner only <em>moves</em>, its content moves
     * with it rigidly, so keeping each popup's offset is exactly right and needs no knowledge of
     * which widget opened it. (Under a <em>resize</em> the content reflows and the offset is only
     * approximately right, which is better than staying behind; the popup usually dies to the
     * click that started the resize anyway.)
     *
     * <p>Moving a popup fires its own position callback, so a popup that owns popups (a menu
     * opening a submenu window) propagates the delta down the chain without recursion here.
     * A closed popup is skipped rather than removed: the owner unregisters it on close, and a
     * window's position is not something to touch after it is destroyed.
     */
    void moveBy(int dx, int dy) {
        if ((dx == 0 && dy == 0) || children.isEmpty()) {
            return;
        }
        // Copy: setScreenPosition can re-enter (a popup's own callback), and a re-entrant
        // register/unregister must not fault this iteration.
        for (Child child : List.copyOf(children)) {
            NativeWindow popup = child.window();
            if (!popup.isClosed()) {
                popup.setScreenPosition(popup.screenX() + dx, popup.screenY() + dy);
            }
        }
    }

    /**
     * Closes every popup and forgets them: the owner is going away, and a floating
     * always-on-top popup left behind would be orphaned on screen with nothing to dismiss it.
     */
    void closeAll() {
        for (Child child : List.copyOf(children)) {
            child.window().close();
        }
        children.clear();
    }
}
