package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ScrollGutters;
import limn.components.ScrollView;
import limn.components.Separator;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * The worked form the guide's Forms page is built from: a whole screen rather than a
 * single widget, because the interesting part of a form is the arrangement, not the field.
 *
 * <p>Every region marked here becomes a code block on that page, and this file is compiled
 * by {@code ./gradlew check}, so the sample a reader copies is a sample that builds. Each
 * marker sits BELOW its Javadoc: the site publishes the marked text verbatim, and a doc
 * comment with {@code @link} tags in it is documentation for a reader of this file rather
 * than a code sample for a reader of the guide.
 *
 * <p>Deterministic on purpose: the field states shown are set explicitly rather than reached
 * by typing, so two capture runs produce the same pixels.
 */
public final class FormExample {

    private FormExample() {
    }

    /**
     * A caption over a control, the shape every field on this form uses. STRETCH is what
     * makes the control fill the column's width instead of shrinking to its own text.
     */
    // #region guide:form-field
    static Widget field(String caption, Widget control) {
        Column group = new Column();
        group.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        group.add(new Label(caption).setMuted(true));
        group.add(control);
        return group;
    }
    // #endregion

    /**
     * Validation is a listener and two writes: the field's own state, which recolours its
     * border, and a message beneath it. The message label is created whether or not it has
     * text, so the form does not jump by a line the first time it fails.
     */
    // #region guide:form-validation
    static void validate(TextField email, Label message) {
        email.onChange(text -> {
            boolean ok = text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
            email.setValidation(ok ? TextField.Validation.SUCCESS : TextField.Validation.ERROR);
            message.setText(ok ? "" : "Enter an address like ada@example.com");
        });
    }
    // #endregion

    /** The form itself: fields, a validated one, a choice, a toggle and the actions. */
    // #region guide:form
    public static Widget form() {
        TextField name = new TextField();
        name.setText("Ada Lovelace");

        TextField email = new TextField();
        email.setText("ada@example");
        Label emailMessage = new Label("Enter an address like ada@example.com");
        emailMessage.setColor(Theme.current().danger);
        validate(email, emailMessage);
        email.setValidation(TextField.Validation.ERROR);

        PasswordField password = new PasswordField();
        password.setText("correct horse battery");

        ComboBox plan = new ComboBox(List.of("Personal", "Team", "Enterprise"));
        plan.setSelectedIndex(1);

        Checkbox updates = new Checkbox(Checkbox.Variant.SWITCH, "Email me release notes");
        updates.setChecked(true);

        Column emailGroup = new Column();
        emailGroup.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        emailGroup.add(email);
        emailGroup.add(emailMessage);

        Column form = new Column();
        form.gap(18).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(new Label("Create your account").setRole(Label.Role.TITLE));
        form.add(field("Full name", name));
        form.add(field("Email", emailGroup));
        form.add(field("Password", password));
        form.add(field("Plan", plan));
        form.add(updates);
        form.add(Separator.horizontal());
        form.add(actions());
        return form;
    }
    // #endregion

    /**
     * The actions row. The spacer takes every leftover point, which is what pushes the two
     * buttons to the trailing edge without anyone measuring anything.
     */
    // #region guide:form-actions
    static Widget actions() {
        Row actions = new Row();
        actions.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        actions.add(Expanded.spacer(1));
        actions.add(new Button("Cancel").setSecondary(true));
        actions.add(new Button("Create account"));
        return actions;
    }
    // #endregion

    /**
     * A form in a scroll view, which is where a form of any length ends up.
     *
     * <p>Two things are being said here, and they solve different halves of one complaint.
     *
     * <p><b>The padding goes inside.</b> A viewport clips at its own edge, and a focused
     * control paints its ring <em>outside</em> its box: a Button's ring spans −3…−1 (see
     * {@code Strokes.FOCUS_RING_OUTSET}). A field flush against that edge loses the ring
     * that says it is focused. Padding outside the scroll view would not help, because the
     * clip travels with the viewport, not with the box around it.
     *
     * <p><b>The bar takes a strip.</b> {@code RESERVED} rather than the overlay default,
     * because every row of a form ends in something the reader is aiming at. An overlay bar
     * is drawn over the viewport and cannot know the content has a margin, so it lands on
     * the trailing edge of the fields themselves. Reserving narrows the content instead,
     * and nothing is ever painted under a bar. Media and prose want the opposite default,
     * and keep it.
     */
    // #region guide:form-scroll
    public static Widget scrolling(Widget form) {
        ScrollView scroll = new ScrollView(new Padding(Insets.all(16), form), false, true);
        scroll.setBarLayout(ScrollGutters.Layout.RESERVED);
        return scroll;
    }
    // #endregion

    /** The form centred on a canvas, for the capture the guide page shows. */
    public static Scene scene() {
        Row centred = new Row();
        centred.mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.CENTER);
        centred.add(new SizedBox(420, SizedBox.UNSET, form()));
        Scene scene = new Scene(new Padding(Insets.all(32), centred));
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
