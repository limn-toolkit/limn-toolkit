package limn.components.internal;

import java.util.function.Consumer;

import limn.components.Accelerator;
import limn.input.Keys;
import limn.scene.EditCommand;
import limn.scene.event.KeyEvent;

/**
 * The chord each editing command is on the keyboard, so that a widget does for the command
 * exactly what it does for the key: one handler, reached two ways.
 */
public final class EditKeys {

    private EditKeys() {
    }

    /** @return a press of the platform's chord for {@code command} */
    public static KeyEvent press(EditCommand command) {
        int command_ = Accelerator.commandModifier();
        return switch (command) {
            case UNDO -> new KeyEvent(Keys.Z, true, false, command_);
            case REDO -> new KeyEvent(Keys.Z, true, false, command_ | Keys.MOD_SHIFT);
            case CUT -> new KeyEvent(Keys.X, true, false, command_);
            case COPY -> new KeyEvent(Keys.C, true, false, command_);
            case PASTE -> new KeyEvent(Keys.V, true, false, command_);
            case SELECT_ALL -> new KeyEvent(Keys.A, true, false, command_);
        };
    }

    /**
     * Hands a widget's own key handler the chord of {@code command}.
     *
     * @return whether the handler took it
     */
    public static boolean send(EditCommand command, Consumer<KeyEvent> onKeyEvent) {
        KeyEvent event = press(command);
        onKeyEvent.accept(event);
        return event.isConsumed();
    }
}
