package limn.demo.site;

import limn.backend.FileDialogs;
import limn.backend.NativeWindow;
import limn.components.Accelerator;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Dialog;
import limn.components.Label;
import limn.components.ListView;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.MenuItem;
import limn.components.TextField;
import limn.components.Theme;
import limn.concurrent.Job;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Grid;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Limn half of the three "coming from" pages of the guide (Swing, JavaFX, SWT): the same
 * everyday tasks on each page, written once here, so the three pages show one way of doing each
 * and it compiles. The other toolkit's half of every pair is text on its page.
 *
 * <p>Each region is short on purpose: it answers "how do I do what I did there", and the page
 * links to the guide that says the rest. {@code MigrationExampleTest} builds every piece.
 */
public final class MigrationExample {

    private MigrationExample() {
    }

    /** A labelled form: two columns of label and field, the buttons at the trailing end. */
    // #region guide:migrate-layout
    static Widget<?> contactForm(TextField name, TextField email, Button save) {
        Grid fields = new Grid(2);
        fields.gap(12).rowGap(10).expandedColumn(1)
                .columnAlignment(1, Flex.CrossAlignment.STRETCH);
        fields.add(new Label("Name").setLabelFor(name));
        fields.add(name);
        fields.add(new Label("Email").setLabelFor(email));
        fields.add(email);

        Row buttons = new Row();
        buttons.gap(8);
        buttons.add(Expanded.spacer(1));   // takes the room left: the buttons go to the end
        buttons.add(new Button("Cancel").setStyle(Button.Style.SECONDARY));
        buttons.add(save);

        Column form = new Column();
        form.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        form.add(fields);
        form.add(buttons);
        return new Padding(Insets.all(20), form);
    }
    // #endregion

    /** What runs when the user acts, and what hears every change. */
    // #region guide:migrate-events
    static void wire(Button save, TextField name, Checkbox notify, ComboBox<String> plan,
                     Label status) {
        save.onAction(() -> status.setText("Saved " + name.text()));
        // A handler answers the user: it runs when they type, not on setText.
        name.onChange(text -> save.setEnabled(!text.isBlank()));
        notify.onChange(on -> status.setText(on ? "You will be told" : "Quietly"));
        plan.onSelect(() -> status.setText("Plan: " + plan.selectedItem()));
        // A watcher hears every change, the code's included: the label mirrors the field.
        name.observeChanges((widget, change) -> status.setText(name.text()));
    }
    // #endregion

    /** A search that runs off the UI thread and lands on it; asking again withdraws the last one. */
    // #region guide:migrate-thread
    static final class Search {

        private Job job;

        void find(String query, Function<String, List<String>> repository,
                  ListView<String> results, Label status) {
            if (job != null) {
                job.cancel();   // the answer to an older question is not wanted any more
            }
            status.setText("Searching…");
            job = Ui.work(progress -> repository.apply(query))   // on a worker
                    .onSuccess(found -> {                        // on the UI thread
                        results.setItems(found);
                        status.setText(found.size() + " found");
                    })
                    .onFailure(error -> status.setText("Failed: " + error.getMessage()))
                    .start();
        }
    }
    // #endregion

    /** A clock that ticks every second until it is stopped. */
    // #region guide:migrate-timer
    static final class Clock {

        private Subscription next;

        void start(Label time) {
            time.setText(LocalTime.now().withNano(0).toString());
            next = Ui.postDelayed(() -> start(time), 1000);    // re-armed on every tick
        }

        void stop() {
            if (next != null) {
                next.cancel();
            }
        }
    }
    // #endregion

    /** A menu bar with shortcuts, and a question asked before a destructive command. */
    // #region guide:migrate-menu
    static MenuBar menuBar(Widget<?> owner, Runnable save, Runnable delete) {
        Menu file = new Menu()
                .add(MenuItem.of("Save", save).setAccelerator(Accelerator.command(Keys.S)))
                .addSeparator()
                .add(MenuItem.of("Delete…", () -> confirmDelete(owner, delete)));
        MenuBar bar = new MenuBar();   // on macOS it is shown in the system's menu bar
        bar.addMenu("File", file);
        return bar;
    }

    static void confirmDelete(Widget<?> owner, Runnable delete) {
        new Dialog("Delete this order?", "This cannot be undone.")
                .addPrimaryButton("Keep", "keep")
                .addDestructiveButton("Delete", "delete")
                .setCancelResult("keep")
                .show(owner)              // returns at once
                .thenAccept(answer -> {   // the choice, later, on the UI thread
                    if ("delete".equals(answer)) {
                        delete.run();
                    }
                });
    }
    // #endregion

    /** The system's own file chooser, owned by the window. */
    // #region guide:migrate-file
    static void open(NativeWindow window, Path lastFolder, Consumer<Path> load) {
        window.fileDialogs()
                .openFile("Open invoice", lastFolder, FileDialogs.Filter.of("PDF", "*.pdf"))
                .thenAccept(chosen -> chosen.ifPresent(load)); // empty means cancelled
    }
    // #endregion

    /** A widget of your own: it says how big it wants to be and paints itself. */
    // #region guide:migrate-paint
    public static final class Meter extends Widget<Meter> {

        private float level;    // 0 to 1

        public Meter setLevel(float newLevel) {
            Ui.checkUiThread();
            level = Math.max(0, Math.min(1, newLevel));
            invalidate();   // repaint at the same size; markNeedsLayout() for a new size
            return this;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(160, 12);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            canvas.fillRect(0, 0, width(), height(), theme.outline());
            canvas.fillRect(0, 0, width() * level, height(), theme.primary());
        }
    }
    // #endregion

    /** A palette of the application's own, in place of a look and feel or a style sheet. */
    // #region guide:migrate-theme
    static void brand() {
        Theme.builder("Acme", false)
                .background(Color.WHITE)
                .primary(Color.rgb(0x0F6E4F))
                .deriveAccentStates()   // hover and pressed tones solved from the accent
                .cornerScale(1.6f)
                .build()
                .apply();               // every open window repaints in it
    }
    // #endregion
}
