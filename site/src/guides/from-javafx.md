---
title: "Coming from JavaFX"
description: "What each JavaFX class and habit becomes in Limn, the everyday tasks written in both, and what Limn does not have."
---

This page is for someone who already writes JavaFX. A lot will look familiar: a scene holds a
tree of nodes, the tree is the layout, one thread owns the interface, and slow work goes
elsewhere. Even the name `Scene` means nearly the same thing. What changes is that there are no
properties to bind, no FXML and no CSS: a screen is plain Java, values are set and read, and
the look comes from one themed palette.

Each section below shows the JavaFX code first and the Limn code after it, and links to the
page of this guide that says the rest.

## The ideas, side by side

| JavaFX | Limn |
|---|---|
| `Application`, `launch`, `start(Stage)` | a `main` that makes a backend and runs `backend.runEventLoop()` |
| `Stage` | a `NativeWindow` from `backend.createWindow(…)` |
| `Scene` | `Scene`, bound to the window with `scene.bind(window)` |
| `Node`, `Parent`, `Region` | `Widget`; a layout is a widget that places its children |
| `VBox`, `HBox` | `Column`, `Row`; `Expanded` in place of `setVgrow`/`setHgrow` |
| `GridPane` | `Grid` |
| `FlowPane` | `Wrap` |
| `StackPane` | `Stack` |
| `BorderPane` | a `Column` of top, `Expanded` middle and bottom; a `Row` inside for left and right |
| `AnchorPane`, `Pane` with `relocate` | no counterpart: sizes come from what each widget measures |
| `Label`, `Button`, `CheckBox`, `RadioButton`, `ToggleButton` | `Label`, `Button`, `Checkbox`, `RadioButton`, `Checkbox.Variant.SWITCH` |
| `TextField`, `PasswordField`, `TextArea` | `TextField`, `PasswordField`, `TextArea` |
| `TextFormatter` | `TextField.setMask(…)`, `Spinner` |
| `ComboBox`, `ChoiceBox` | `ComboBox`, editable or not |
| `ListView`, `TableView`, `TreeView`, `TreeTableView` | `ListView`, `Table`, `Tree` |
| `ScrollPane`, `SplitPane`, `TabPane` | `ScrollView`, `SplitPane`, `TabbedPane` |
| `ToolBar`, `ProgressBar`, `ProgressIndicator`, `Slider`, `Spinner` | `ToolBar`, `ProgressBar`, `ProgressBar.setIndeterminate(true)`, `Slider`, `Spinner` |
| `DatePicker`, `ColorPicker` | `DateField`, `DatePicker`, `ColorPicker`, `ColorPickerButton` |
| `MenuBar`, `Menu`, `MenuItem`, `CheckMenuItem` | `MenuBar`, `Menu`, `MenuItem`, `MenuItem.check` |
| `ContextMenu` | `PopupMenu`, `ContextMenus` |
| `Alert`, `Dialog` | `Dialog` |
| `FileChooser`, `DirectoryChooser` | `window.fileDialogs()` |
| `Tooltip` | `setTooltip` |
| `KeyCombination` on a menu item | `MenuItem.setAccelerator(Accelerator.command(Keys.S))` |
| `Platform.runLater` | `Ui.post` |
| `Task`, `Service` | `Ui.work(…)`, which returns a `Job` you can cancel |
| `Timeline`, `PauseTransition` | `Ui.postDelayed`; transitions and tickers for motion |
| `setOnAction` | `onAction` |
| `textProperty().addListener` | `onChange` for the user's changes, `observeChanges` for every change |
| `bind`, `bindBidirectional` | no counterpart: set the value, and handle or watch the change |
| `ObservableList` | a plain `List`, given again with `setItems`/`setRows`, or `refresh()` |
| `Canvas` and `GraphicsContext` | a widget of your own with `onPaint(Canvas)` |
| CSS, `setStyle`, `-fx-*` | `Theme`, and a few styles per widget (`Button.Style`) |
| FXML | no counterpart: a screen is a method that returns a widget |
| `DropShadow` | `Elevation` |
| `BarChart`, `LineChart`, `PieChart` | `BarChart`, `LineChart`, `DonutChart` ([Charts](/docs/charts/)) |
| `MediaView`, `MediaPlayer` | `VideoView` ([Images and media](/docs/images-and-media/)) |
| `SubScene` with a 3D camera | `Viewport3D` |
| `PrinterJob` | `Document`: saved as PDF, printed, previewed ([Printing and PDF](/docs/printing/)) |
| `HostServices.showDocument` | `window.launcher()` |

## The differences that matter

**No properties, no binding.** A widget holds plain values: `setText`, `text()`,
`setChecked`, `isChecked()`. Nothing is bound to anything. Where you would bind a label to a
field, you set the label from the field's handler, and where you would add a listener to a
property, you watch the widget. That is more lines in a screen with many dependent values, and
fewer surprises about which update ran first.

**A handler answers the user.** `onChange`, `onAction`, `onSelect` and the other handlers run
when the user acts, not when your code calls `setText` or `setSelectedIndex`. A JavaFX change
listener fires for both. When you do want every change, the code's included, `observeChanges`
is the watcher that hears them all.

**No FXML and no CSS.** A screen is a Java method that returns a widget, so it is refactored,
searched and debugged as Java. The look is a `Theme`: built-in palettes, or one built from your
brand, which every widget draws from. There are no selectors and no per-node style strings; a
button chooses one of three styles (`PRIMARY`, `SECONDARY`, `TERTIARY`) and the palette
does the rest.

**Lists are your objects, not observable ones.** A `Table` takes your `List<Order>` and a
column for each thing to show, each a function that reads one order. There is no
`ObservableList` that the table watches: give it the new list with `setRows`, or call
`refresh()` after changing the objects in place.

**The thread rule is the same, and enforced the same way.** JavaFX already throws "Not on FX
application thread" for many calls; Limn throws for every widget setter called off the UI
thread.

**`main` runs the window.** `backend.runEventLoop()` takes the thread that created the backend
and returns when the application ends. On macOS that thread has to be the process's first one,
which is why the JVM is started with `-XstartOnFirstThread` there.

## A window

In JavaFX:

```java
public class Hello extends Application {
    @Override
    public void start(Stage stage) {
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        VBox column = new VBox(12, new Label("A window, drawn by JavaFX."), close);
        column.setPadding(new Insets(24));
        stage.setTitle("Hello, JavaFX");
        stage.setScene(new Scene(column, 480, 320));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

In Limn:

{% snippet hello-window %}

[Install and first window](/docs/install/) has the dependency and the command line.

## A form

In JavaFX:

```java
GridPane fields = new GridPane();
fields.setHgap(12);
fields.setVgap(10);
Label nameLabel = new Label("Name");
nameLabel.setLabelFor(name);
Label emailLabel = new Label("Email");
emailLabel.setLabelFor(email);
fields.addRow(0, nameLabel, name);
fields.addRow(1, emailLabel, email);
ColumnConstraints grow = new ColumnConstraints();
grow.setHgrow(Priority.ALWAYS);
fields.getColumnConstraints().addAll(new ColumnConstraints(), grow);

Region spacer = new Region();
HBox.setHgrow(spacer, Priority.ALWAYS);
Button cancel = new Button("Cancel");
cancel.getStyleClass().add("secondary");
HBox buttons = new HBox(8, spacer, cancel, save);

VBox form = new VBox(16, fields, buttons);
form.setPadding(new Insets(20));
```

In Limn:

{% snippet guide:migrate-layout %}

`Expanded.spacer(1)` is the `Region` with `Hgrow` set to `ALWAYS`. [Layout](/docs/layout/) has
every layout widget, and [Forms](/docs/forms/) the `Field` that puts a label, a hint and a
validation message around an input.

## Events

In JavaFX:

```java
save.setOnAction(e -> status.setText("Saved " + name.getText()));
name.textProperty().addListener((obs, old, text) -> save.setDisable(text.isBlank()));
notify.selectedProperty().addListener((obs, old, on) ->
        status.setText(on ? "You will be told" : "Quietly"));
plan.setOnAction(e -> status.setText("Plan: " + plan.getValue()));
```

In Limn:

{% snippet guide:migrate-events %}

A widget has one handler of each kind, and setting a second throws: pass `null` first to
replace it. Watchers, as many as you like, are what `observeChanges` adds.

## Work off the UI thread

In JavaFX:

```java
if (task != null) {
    task.cancel();
}
status.setText("Searching…");
task = new Task<>() {
    @Override protected List<String> call() {
        return repository.search(query);
    }
};
task.setOnSucceeded(e -> {
    results.getItems().setAll(task.getValue());
    status.setText(task.getValue().size() + " found");
});
task.setOnFailed(e -> status.setText("Failed: " + task.getException().getMessage()));
new Thread(task).start();
```

In Limn:

{% snippet guide:migrate-thread %}

The shape is the one `Task` has: a body on a worker, callbacks on the UI thread, a handle that
cancels. The worker pool is the toolkit's, so there is no thread to start.
[Background work](/docs/background-work/) has progress and cancellation.

## A timer

In JavaFX:

```java
Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1),
        e -> time.setText(LocalTime.now().withNano(0).toString())));
clock.setCycleCount(Animation.INDEFINITE);
clock.play();
// later
clock.stop();
```

In Limn:

{% snippet guide:migrate-timer %}

For something that moves on screen, a transition or a ticker is the counterpart of a
`Timeline` over a property: [Animations](/docs/animations/).

## A table

In JavaFX:

```java
TableColumn<Order, String> number = new TableColumn<>("Order");
number.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().number()));
TableColumn<Order, String> customer = new TableColumn<>("Customer");
customer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().customer()));
TableColumn<Order, Number> total = new TableColumn<>("Total");
total.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().total()));

TableView<Order> table = new TableView<>(FXCollections.observableArrayList(rows));
table.getColumns().addAll(List.of(number, customer, total));
table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
table.setRowFactory(view -> {
    TableRow<Order> row = new TableRow<>();
    row.setOnMouseClicked(e -> {
        if (e.getClickCount() == 2 && !row.isEmpty()) {
            status.setText("Opened " + row.getItem().number());
        }
    });
    return row;
});
```

In Limn:

{% snippet guide:migrate-table %}

A column is a function from your object to its value: no property wrapper and no value factory.
The table sorts from its headers and only builds the rows on screen, so a table over a million
rows costs what one over twenty does. [Lists and scrolling](/docs/lists-and-scrolling/) has
selection, footers, frozen columns and a button in a row.

## Menus and dialogs

In JavaFX:

```java
MenuItem saveItem = new MenuItem("Save");
saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
saveItem.setOnAction(e -> save.run());
MenuItem deleteItem = new MenuItem("Delete…");
deleteItem.setOnAction(e -> {
    ButtonType keep = new ButtonType("Keep", ButtonBar.ButtonData.CANCEL_CLOSE);
    ButtonType remove = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "This cannot be undone.", keep, remove);
    alert.setHeaderText("Delete this order?");
    alert.showAndWait().filter(remove::equals).ifPresent(b -> delete.run());
});
Menu file = new Menu("File", null, saveItem, new SeparatorMenuItem(), deleteItem);
MenuBar bar = new MenuBar(file);
bar.setUseSystemMenuBar(true);
```

In Limn:

{% snippet guide:migrate-menu %}

`show` is `show()` rather than `showAndWait()`: it returns at once and the answer arrives later,
on the UI thread. On macOS the menu bar goes to the system's menu bar without being asked.
[Menus and dialogs](/docs/menus-and-dialogs/) has the rest.

## Choosing a file

In JavaFX:

```java
FileChooser chooser = new FileChooser();
chooser.setTitle("Open invoice");
chooser.setInitialDirectory(lastFolder.toFile());
chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
File chosen = chooser.showOpenDialog(stage);
if (chosen != null) {
    load.accept(chosen.toPath());
}
```

In Limn:

{% snippet guide:migrate-file %}

Both are the operating system's own chooser. Limn's does not block: the window keeps drawing
while the user browses, and an empty answer means cancelled.

## Painting your own

In JavaFX:

```java
class Meter extends Canvas {
    private double level;
    Meter() { super(160, 12); }
    void setLevel(double level) { this.level = Math.max(0, Math.min(1, level)); draw(); }
    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        g.setFill(Color.LIGHTGRAY);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setFill(Color.web("#0F6E4F"));
        g.fillRect(0, 0, getWidth() * level, getHeight());
    }
}
```

In Limn:

{% snippet guide:migrate-paint %}

A widget paints when the scene asks, not when you call a draw method: `invalidate()` marks it,
and the next frame paints it. Colours come from the theme, so the meter follows a palette
change with no code. [Your own widgets](/docs/custom-widgets/) has input, focus and what a
screen reader is told.

## The look

In JavaFX:

```css
.root {
    -fx-accent: #0F6E4F;
    -fx-base: #ffffff;
    -fx-background-radius: 6;
}
```

```java
scene.getStylesheets().add(getClass().getResource("acme.css").toExternalForm());
```

In Limn:

{% snippet guide:migrate-theme %}

[Theming](/docs/theming/) has the built-in palettes, density, fonts and following the
operating system's appearance.

## What Limn does not have

Some of what JavaFX gives you has no Limn counterpart, and it is better to know before you
plan a migration than in the middle of one.

- **No properties or bindings.** Dependent values are set from handlers and watchers. A screen
  that is mostly bindings is the one that takes longest to move.
- **No FXML and no CSS.** Screens move to Java methods, and style sheets to a `Theme`. A design
  that leans on per-node styling will look more uniform in Limn, by design.
- **No mixing.** Limn does not host JavaFX nodes, and a Limn scene cannot be put inside a
  JavaFX one. Running both toolkits in one process is not something Limn supports or tests, so
  a migration moves an application, or a separate program of it, at a time.
- **No web view and no HTML editor.** There is no embedded browser; `window.launcher()` opens a
  page in the user's own. Text is plain: a `Label` wraps and a `TextArea` edits.
- **No free positioning.** There is no `Pane` with `relocate` or `AnchorPane`; a layout of your
  own is a widget that places its children
  ([Your own widgets](/docs/custom-widgets/#a-layout-of-your-own)).
- **No effects pipeline.** Shadows come from `Elevation` and blur from glass surfaces
  ([Theming](/docs/theming/#elevation)); there is no general `Effect` chain on a node.
