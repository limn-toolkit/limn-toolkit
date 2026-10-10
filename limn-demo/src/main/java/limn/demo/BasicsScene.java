package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.TextArea;
import limn.components.TextField;
import limn.graphics.BitmapIcon;
import limn.graphics.Icon;
import limn.graphics.Images;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * The controls every screen is made of, on one page of the demo: buttons, check boxes and
 * switches, a line of text, a password, a block of text and a progress bar.
 */
final class BasicsScene {

    private BasicsScene() {
    }

    static Widget<?> content() {
        Icon check = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-check.png"));
        Icon download = BitmapIcon.mask(Images.fromResource("/limn/demo/images/icon-download.png"));

        Label clicks = new Label("").setMuted(true);
        int[] count = {0};
        Button primary = new Button("Save").setIcon(check);
        primary.onAction(() -> clicks.setText("Pressed " + ++count[0] + (count[0] == 1 ? " time" : " times")));
        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        Row buttons = row(12);
        buttons.add(primary);
        buttons.add(new Button("Download").setSecondary(true).setIcon(download));
        buttons.add(disabled);
        buttons.add(clicks);

        Checkbox locked = new Checkbox(Checkbox.Variant.BOX, "Locked").setChecked(true);
        locked.setEnabled(false);
        Row checks = row(20);
        checks.add(new Checkbox(Checkbox.Variant.BOX, "Notifications").setChecked(true));
        checks.add(new Checkbox(Checkbox.Variant.BOX, "Newsletter"));
        checks.add(new Checkbox(Checkbox.Variant.SWITCH, "Airplane mode"));
        checks.add(locked);

        PasswordField password = new PasswordField();
        password.setPlaceholder("Password").setText("secret123");
        Checkbox reveal = new Checkbox(Checkbox.Variant.SWITCH, "Reveal");
        reveal.onChange(password::setRevealed);
        Row passwordRow = row(12);
        passwordRow.add(new SizedBox(280, SizedBox.UNSET, password));
        passwordRow.add(reveal);

        TextArea notes = new TextArea();
        notes.setText("""
                A block of text scrolls on both axes, moves across lines with the arrow keys \
                and selects with Shift and the arrows, or with the pointer.
                Line 2
                Line 3
                Line 4
                Line 5
                Line 6
                Line 7
                Line 8, the last.""");
        Checkbox softWrap = new Checkbox(Checkbox.Variant.SWITCH, "Soft wrap");
        softWrap.onChange(notes::setSoftWrap);
        Row areaHead = row(20);
        areaHead.add(heading("Text area"));
        areaHead.add(softWrap);

        ProgressBar running = new ProgressBar();
        // Once the page is in a scene: the bar runs on the scene's clock, and has none before.
        limn.concurrent.Ui.postDelayed(() -> running.setIndeterminate(true), 0);

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(heading("Buttons"));
        page.add(buttons);
        page.add(heading("Check boxes and switches"));
        page.add(checks);
        page.add(heading("Text field"));
        page.add(leading(new SizedBox(280, SizedBox.UNSET, new TextField().setPlaceholder("Type your name"))));
        page.add(heading("Password"));
        page.add(passwordRow);
        page.add(areaHead);
        page.add(new SizedBox(SizedBox.UNSET, 120, notes));
        page.add(heading("Progress"));
        page.add(leading(new SizedBox(360, SizedBox.UNSET, new ProgressBar().setProgress(0.4f))));
        page.add(leading(new SizedBox(360, SizedBox.UNSET, running)));
        return page;
    }

    private static Label heading(String text) {
        return new Label(text).setStrong(true);
    }

    private static Row row(float gap) {
        Row row = new Row();
        row.gap(gap).crossAlignment(Flex.CrossAlignment.CENTER);
        return row;
    }

    /** A control at its own width at the leading edge of a column that stretches its children. */
    private static Widget<?> leading(Widget<?> control) {
        Row row = new Row();
        row.add(control);
        row.add(Expanded.spacer(1));
        return row;
    }
}
