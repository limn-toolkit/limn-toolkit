package limn.demo;

import limn.components.Label;
import limn.i18n.I18nString;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;

/**
 * A caption beside a control, with the caption declared as the control's label.
 *
 * <p>This is the one idiom the accessibility gallery exists to show, because a caption that merely
 * sits above or below a field names nothing (ADR 039 §1.7): a reader reaching the field hears
 * "text field" and nothing else. Four scenes of this demo had a caption helper of their own, and
 * none of the four declared the relation, which is exactly the defect the gallery describes.
 * Every caption in the demo now goes through here, and every one names what it captions.
 */
public final class Labelled {

    private Labelled() {
    }

    /**
     * A caption above a control.
     *
     * @param caption the text, never translated
     * @param control what it names
     * @return the column holding both
     */
    public static Widget above(String caption, Widget control) {
        return above(I18nString.literal(caption), control, control);
    }

    /**
     * A caption above a control that sits inside a wrapper &mdash; a sized box, a row with a
     * switch &mdash; where the label must point at the control and not at the box around it.
     *
     * @param caption the text, never translated
     * @param control what it names
     * @param placed  what is laid out under the caption; holds {@code control}
     * @return the column holding both
     */
    public static Widget above(String caption, Widget control, Widget placed) {
        return above(I18nString.literal(caption), control, placed);
    }

    /**
     * The same, for a caption that follows the language.
     *
     * @param caption the text
     * @param control what it names
     * @param placed  what is laid out under the caption; holds {@code control}
     * @return the column holding both
     */
    // #region guide:a11y-labelled
    public static Widget above(I18nString caption, Widget control, Widget placed) {
        Column column = new Column();
        column.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(caption).setLabelFor(control));
        column.add(placed);
        return column;
    }
    // #endregion

    /**
     * A muted caption below a control, the shape the controls, cursors and animations scenes
     * use for a row of specimens.
     *
     * @param caption   the text, never translated
     * @param control   what it names, and what is placed
     * @param alignment how the two sit across the column
     * @return the column holding both
     */
    public static Widget below(String caption, Widget control, Flex.CrossAlignment alignment) {
        Column column = new Column();
        column.gap(6).crossAlignment(alignment);
        column.add(control);
        column.add(new Label(caption).setMuted(true).setLabelFor(control));
        return column;
    }

    /** {@link #below(String, Widget, Flex.CrossAlignment)} with the pair aligned to the start. */
    public static Widget below(String caption, Widget control) {
        return below(caption, control, Flex.CrossAlignment.START);
    }
}
