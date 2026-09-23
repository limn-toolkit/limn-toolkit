package limn.demo.site;

import limn.components.Button;
import limn.components.Label;
import limn.components.TextField;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;

import static limn.testing.SceneDriver.drive;

/**
 * The worked example the testing guide shows: a form driven with no display through
 * {@code limn-test}. Compiled by {@code ./gradlew check}, and run by {@code TestingExampleTest}, so
 * what the guide says the test observes is what it observes.
 */
public final class TestingExample {

    private TestingExample() {
    }

    /** The screen under test: a name, a Save button, and a line that says what happened. */
    static final class NameForm {
        final TextField name = new TextField();
        final Button save = new Button("Save");
        final Label status = new Label("");
        final Column root = new Column();

        NameForm() {
            save.onAction(() -> status.setText("Saved " + name.text()));
            name.onSubmit(text -> status.setText("Saved " + text));
            root.add(name);
            root.add(save);
            root.add(status);
        }
    }

    // #region guide:testing
    @SuppressWarnings("try") // the runtime is used by being installed, not by name
    static String typeANameAndSave() {
        try (HeadlessUi ui = new HeadlessUi()) {       // the UI thread is this one
            NameForm form = new NameForm();
            Scene scene = new Scene(form.root);
            scene.setTextRuler(TestRulers.FIXED);        // no fonts: every glyph is 10 wide
            scene.layoutPass(320, 200);

            drive(scene).click(form.name).type("Ada").click(form.save);
            return form.status.text();                   // "Saved Ada"
        }
    }

    @SuppressWarnings("try")
    static String typeANameAndPressEnter() {
        try (HeadlessUi ui = new HeadlessUi()) {
            NameForm form = new NameForm();
            Scene scene = new Scene(form.root);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(320, 200);

            drive(scene).click(form.name).type("Grace").press(Keys.ENTER);
            return form.status.text();                   // "Saved Grace"
        }
    }
    // #endregion
}
