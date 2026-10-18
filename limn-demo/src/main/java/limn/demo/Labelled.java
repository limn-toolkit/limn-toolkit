package limn.demo;

import limn.components.Label;
import limn.i18n.I18nString;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;

/**
 * A caption beside a control that is not a form's field, with the caption declared as the
 * control's label: a caption on a line of its own above a line of controls, a bar with its value
 * on the caption's line, a muted caption under a specimen. A form's field is a
 * {@link limn.components.Field}, which binds its caption itself.
 *
 * <p>A caption that merely sits above or below a control names nothing: a reader reaching it hears
 * "text field" and nothing else. So every caption here names what it captions.
 */
public final class Labelled {

    private Labelled() {
    }

    /**
     * A caption on its own, naming {@code control}: for a form whose captions stand on a line of
     * their own above a line of the controls they name.
     *
     * @param caption the text
     * @param control what it names
     * @return the caption
     */
    // #region guide:a11y-labelled
    public static Label caption(I18nString caption, Widget<?> control) {
        return new Label(caption).setLabelFor(control);
    }
    // #endregion

    /**
     * A caption above a control with the control's value at the end of the caption's line: for a
     * bar thinner than a line of text, which set on one line with its value would be centred on
     * the value's height and sit lower under its caption than a control beside it sits under
     * its own, and shorter too.
     *
     * @param caption the text
     * @param control what it names, and what is placed
     * @param value   what is shown at the end of the caption's line
     * @return the column holding both
     */
    public static Widget<?> aboveWithValue(I18nString caption, Widget<?> control, Widget<?> value) {
        Row head = new Row();
        head.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        // Drawn as a field's caption is, in the label font, muted, and as far above its control,
        // so a bar beside a field sits on the field's line: give the value the label font too.
        head.add(Expanded.of(new Label(caption).setRole(Label.Role.LABEL).setMuted(true)
                .setLabelFor(control), 1));
        head.add(value);
        Column column = new Column();
        // A field's label gap at the medium step, which is the step the Showcase is drawn at.
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(head);
        column.add(control);
        return column;
    }

    /**
     * A muted caption below a control, the shape the controls, cursors and animations scenes
     * use for a row of specimens.
     *
     * @param caption   the text, never translated
     * @param control   what it names, and what is placed
     * @param alignment how the two sit across the column
     * @return the column holding both
     */
    public static Widget<?> below(String caption, Widget<?> control, Flex.CrossAlignment alignment) {
        Column column = new Column();
        column.gap(6).crossAlignment(alignment);
        column.add(control);
        column.add(new Label(caption).setMuted(true).setLabelFor(control));
        return column;
    }

    /** {@link #below(String, Widget, Flex.CrossAlignment)} with the pair aligned to the start. */
    public static Widget<?> below(String caption, Widget<?> control) {
        return below(caption, control, Flex.CrossAlignment.START);
    }
}
