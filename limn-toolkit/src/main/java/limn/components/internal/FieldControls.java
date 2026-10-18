package limn.components.internal;

import limn.components.Theme;
import limn.components.Validation;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a {@code Field} asks of the control it holds beyond what every widget answers: the border
 * it colours with its validation state, and the message a control that validates itself already
 * holds (a date field's "out of range"). Not API: the inputs that draw a border say so here, and a
 * field reaches them here, so that the public face of neither grows a setter for the other.
 *
 * <p><b>By class, registered once, from the class's own static initializer.</b> A class registers
 * before any instance of it exists, so a field holding a control finds its entry, and nothing is
 * kept per instance: a registry of instances would hold every text field ever built, or need weak
 * keys whose values point back at them. A subclass inherits its parent's entry (a password field's
 * is the text field's), and the nearest class with one answers.
 */
public final class FieldControls {

    /** How a class of input shows a field's state on its border. */
    @FunctionalInterface
    public interface Border<W extends Widget<?>> {
        /**
         * @param input the input
         * @param state the field's state; {@link Validation#NONE} gives the border back
         */
        void show(W input, Validation state);
    }

    /** How a class of input that validates itself says what is wrong. */
    @FunctionalInterface
    public interface Message<W extends Widget<?>> {
        /**
         * @param input the input
         * @return why it refuses what it holds, or {@code null} while it holds nothing it refuses
         */
        @Nullable I18nString of(W input);
    }

    private static final Map<Class<?>, Border<?>> BORDERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Message<?>> MESSAGES = new ConcurrentHashMap<>();

    private FieldControls() {
    }

    /**
     * Says that inputs of {@code type}, and of its subclasses, draw a field's state on their border.
     *
     * @param type   the input's class
     * @param border how it shows the state
     * @param <W>    the input's type
     */
    public static <W extends Widget<?>> void ownBorder(Class<W> type, Border<W> border) {
        BORDERS.put(type, border);
    }

    /**
     * Says that inputs of {@code type} validate themselves and what they say when they refuse.
     *
     * @param type    the input's class
     * @param message what it says
     * @param <W>     the input's type
     */
    public static <W extends Widget<?>> void ownMessage(Class<W> type, Message<W> message) {
        MESSAGES.put(type, message);
    }

    /**
     * Shows {@code state} on {@code control}'s border, where it draws one.
     *
     * @param control the control a field holds
     * @param state   the field's state
     * @return whether the control has a border that shows it
     */
    @SuppressWarnings("unchecked")
    public static boolean showBorder(Widget<?> control, Validation state) {
        Border<Widget<?>> border = (Border<Widget<?>>) nearest(BORDERS, control.getClass());
        if (border == null) {
            return false;
        }
        border.show(control, state);
        return true;
    }

    /**
     * @param control the control a field holds
     * @return what it says is wrong with what it holds, or {@code null}: it holds nothing it
     *         refuses, or it does not validate itself
     */
    @SuppressWarnings("unchecked")
    public static @Nullable I18nString ownMessage(Widget<?> control) {
        Message<Widget<?>> message = (Message<Widget<?>>) nearest(MESSAGES, control.getClass());
        return message == null ? null : message.of(control);
    }

    /**
     * The colour a bordered input draws its border in while a field's state is {@code state}:
     * {@code resting} for {@link Validation#NONE}, which is the input's own (its outline, or the
     * focus ring as the focus arrives), and the state's colour otherwise, whatever the focus.
     *
     * @param theme   the palette
     * @param state   the state
     * @param resting what the border is without one
     * @return the colour
     */
    public static Color borderColor(Theme theme, Validation state, Color resting) {
        return switch (state) {
            case NONE -> resting;
            case ERROR -> theme.danger();
            case WARNING -> theme.warning();
            case SUCCESS -> theme.success();
            case INFO -> theme.info();
        };
    }

    /**
     * The larger of two states, which is what an input that validates itself draws when a field
     * says something too: an error above a warning above anything else, so a date field holding a
     * refused day stays red whatever its field says.
     *
     * @param own   the input's own state
     * @param field the field's
     * @return the one to draw
     */
    public static Validation larger(Validation own, Validation field) {
        return rank(own) >= rank(field) ? own : field;
    }

    private static int rank(Validation state) {
        return switch (state) {
            case ERROR -> 3;
            case WARNING -> 2;
            case SUCCESS, INFO -> 1;
            case NONE -> 0;
        };
    }

    private static <V> @Nullable V nearest(Map<Class<?>, V> table, Class<?> type) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            V found = table.get(at);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
