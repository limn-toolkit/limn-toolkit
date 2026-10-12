package limn.components;

import java.lang.ref.WeakReference;

import limn.scene.EditCommand;
import limn.scene.Scene;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

/**
 * Where a menu's items of an editing role act: the scene the menu was opened over and the widget
 * that had its keyboard then, which the menu itself may have taken since.
 *
 * <p>One for the UI thread, set by whoever presents a menu as it opens and left until the next
 * does: an item chosen is run after its menu has closed, and must still find its widget. Held
 * weakly, a menu long closed keeping nothing alive.
 */
final class EditTarget {

    private static WeakReference<Scene> scene = new WeakReference<>(null);
    private static WeakReference<Widget<?>> from = new WeakReference<>(null);

    /** What each command answered since the menu opened: a menu asks at every frame it paints. */
    private static final java.util.EnumMap<EditCommand, Boolean> ANSWERS = new java.util.EnumMap<>(EditCommand.class);

    private EditTarget() {
    }

    /**
     * Names the scene a menu opens over. The widget is the one with the keyboard there, or what
     * had it before a menu bar took it to be walked with the arrows.
     */
    static void opening(@Nullable Scene over) {
        Widget<?> focused = over == null ? null : over.focusedWidget();
        if (focused instanceof MenuBar bar) {
            // Walked to with Alt or F10, the bar remembers who had the keyboard; pressed with the
            // pointer, it took the keyboard as any widget does and the scene remembers.
            Widget<?> before = bar.focusBeforeBar();
            focused = before != null ? before : limn.scene.internal.SceneAccess.focusedBefore(over);
        }
        scene = new WeakReference<>(over);
        from = new WeakReference<>(focused);
        ANSWERS.clear();
    }

    /** The answer the widget gave when first asked since the menu opened; pasting asks the clipboard. */
    static boolean can(EditCommand command) {
        return ANSWERS.computeIfAbsent(command, asked -> {
            Scene over = scene.get();
            Widget<?> widget = from.get();
            return over != null && widget != null && widget.scene() == over && over.canEdit(asked, widget);
        });
    }

    static boolean perform(EditCommand command) {
        Scene over = scene.get();
        Widget<?> widget = from.get();
        ANSWERS.clear();
        if (over == null || widget == null || widget.scene() != over) {
            return false;
        }
        boolean done = over.edit(command, widget);
        if (over.focusedWidget() instanceof MenuBar && widget.isFocusable() && widget.isVisible()
                && widget.isEnabled()) {
            // The command was done in the widget the bar took the keyboard from: it has it back,
            // so that what was done is seen and typing goes on where it was.
            over.requestFocus(widget);
        }
        return done;
    }
}
