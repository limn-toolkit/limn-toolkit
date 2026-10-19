package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Field;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ScrollGutters;
import limn.components.ScrollView;
import limn.components.SelectionMode;
import limn.components.Separator;
import limn.components.TextField;
import limn.components.Validation;
import limn.concurrent.Ui;
import limn.graphics.Icon;
import limn.scene.Change;
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
     * A field: a caption over a control, and a line under it. The caption names the control to a
     * screen reader, the helper text is the line until a message takes its place, and the asterisk
     * of a required field is the field's to draw.
     */
    // #region guide:form-field
    static Field emailField(TextField email) {
        return new Field("Email", email)
                .setHelperText("The receipt goes here")
                .setRequired(true);
    }
    // #endregion

    /**
     * A field whose helper line counts the characters typed: the guide's example of the second
     * channel.
     *
     * <p>The count must be right after {@code setText} from the application as well as after a
     * keystroke, so it is a <em>watcher</em> and not a handler: {@code observeChanges} hears every
     * change with its origin, where {@code onChange} runs for the user's typing alone. The line is
     * written once from the text field and then follows it, whoever moves it.
     */
    // #region guide:form-counter
    static Field counted(Field field, TextField text, int limit) {
        field.setHelperText(text.text().length() + " / " + limit);
        text.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.TEXT) {
                field.setHelperText(text.text().length() + " / " + limit);
            }
        });
        return field;
    }
    // #endregion

    /**
     * Validation is a handler and one write: the field's state and what to say about it. The line
     * under the control already exists and keeps its height, so the form does not jump the first
     * time the address fails, and a screen reader hears the message as the field's description,
     * after "Email, invalid", rather than as a sentence somewhere else on the screen.
     */
    // #region guide:form-validation
    static void validate(TextField email, Field field) {
        email.onChange(text -> {
            if (text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                field.setValidation(Validation.SUCCESS);
            } else {
                field.setValidation(Validation.ERROR, "Enter an address like ada@example.com");
            }
        });
    }
    // #endregion

    /** The form itself: fields, a validated one, a choice, a toggle and the actions. */
    // #region guide:form
    public static Widget<?> form() {
        TextField name = new TextField();
        name.setText("Ada Lovelace");

        TextField email = new TextField();
        email.setText("ada@example");
        Field emailField = emailField(email);
        validate(email, emailField);
        // setText above was the application's write, and a handler runs for the user alone, so
        // the rule in validate did not run: the starting state is set here, explicitly.
        emailField.setValidation(Validation.ERROR, "Enter an address like ada@example.com");

        PasswordField password = new PasswordField();
        password.setText("correct horse battery");

        ComboBox<String> plan = new ComboBox<>(List.of("Personal", "Team", "Enterprise"));
        plan.setSelectedIndex(1);

        Checkbox updates = new Checkbox(Checkbox.Variant.SWITCH, "Email me release notes");
        updates.setChecked(true);

        Column form = new Column();
        form.gap(18).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(new Label("Create your account").setRole(Label.Role.TITLE));
        form.add(counted(new Field("Full name", name).setRequired(true), name, 40));
        form.add(emailField);
        form.add(new Field("Password", password).setHelperText("At least 12 characters"));
        form.add(new Field("Plan", plan));
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
    static Widget<?> actions() {
        Row actions = new Row();
        actions.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        actions.add(Expanded.spacer(1));
        actions.add(new Button("Cancel").setStyle(Button.Style.SECONDARY));
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
    public static Widget<?> scrolling(Widget<?> form) {
        ScrollView scroll = new ScrollView(new Padding(Insets.all(16), form), false, true);
        scroll.setBarLayout(ScrollGutters.Layout.RESERVED);
        return scroll;
    }
    // #endregion

    /** A customer, as a sales order's "Customer" field holds one. */
    record Customer(String name, String city) {
    }

    /** Where customers are looked up: the application's own, a database or a service. */
    interface CustomerDirectory {
        /** At most {@code limit} customers whose name contains {@code text}. Blocking. */
        List<Customer> find(String text, int limit);
    }

    /**
     * An editable combo over a list in memory: typing filters it, the items starting with the
     * text first, and the value is a {@code Customer}, not its text.
     */
    // #region guide:combo-editable
    static ComboBox<Customer> customerField(List<Customer> customers) {
        ComboBox<Customer> customer = new ComboBox<>(customers, Customer::name).setEditable(true);
        customer.onSelect(() -> System.out.println("Bill to " + customer.selectedItem().city()));
        return customer;
    }
    // #endregion

    /** A field that suggests and still takes a text it does not know. */
    // #region guide:combo-free-text
    static ComboBox<String> cityField(List<String> known) {
        return new ComboBox<>(known).setEditable(true).setFreeText(text -> text.strip());
    }
    // #endregion

    /**
     * An editable combo whose items come from a search run off the user-interface thread: the
     * directory is asked once the typing pauses, and never on the thread that paints.
     */
    // #region guide:combo-search
    static ComboBox<Customer> customerSearch(CustomerDirectory directory) {
        return new ComboBox<Customer>(Customer::name)
                .setSearch(text -> Ui.async(() -> directory.find(text, 50)));
    }
    // #endregion

    /** An order status, what a sales report is filtered by, with its icon. */
    record Status(String label, Icon icon) {
    }

    /** Where a report's filter goes: the application's own. */
    interface Report {
        /** Shows only the orders in these statuses; every order for none. */
        void filterBy(List<Status> statuses);
    }

    /**
     * A combo that holds many: the report's status filter, whose chips show what is chosen and
     * whose hint stands in the field while nothing is.
     */
    // #region guide:combo-many
    static ComboBox<Status> statusFilter(List<Status> statuses, Report report) {
        ComboBox<Status> status = new ComboBox<>(statuses, Status::label)
                .setSelectionMode(SelectionMode.MULTI)
                .setItemIcon(Status::icon)
                .setPlaceholder("Any status");
        status.onSelect(() -> report.filterBy(status.selectedItems()));
        return status;
    }
    // #endregion

    /** A combo holding one item that may hold none: its empty option, worded by the same function. */
    // #region guide:combo-empty
    static ComboBox<Status> statusField(List<Status> statuses) {
        // Asked for null as well: that is the empty option, which reads "No status".
        return new ComboBox<>(statuses, s -> s == null ? "No status" : s.label())
                .setEmptySelectionAllowed(true);
    }
    // #endregion

    /** The form centred on a canvas, for the capture the guide page shows. */
    // #region showcase:form-scene
    public static Scene scene() {
        Row centred = new Row();
        centred.mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.CENTER);
        centred.add(new SizedBox(420, SizedBox.UNSET, form()));
        Scene scene = new Scene(new Padding(Insets.all(32), centred));
        return scene;
    }
    // #endregion
}
