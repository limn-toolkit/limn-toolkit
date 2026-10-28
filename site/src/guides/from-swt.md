---
title: "Coming from SWT"
description: "What each SWT and JFace class and habit becomes in Limn, the everyday tasks written in both, and what Limn does not have."
---

This page is for someone who already writes SWT, with or without JFace. The overall shape
carries over: a window holds a tree of controls, events arrive as listeners, one thread owns
the interface, and slow work goes elsewhere. What changes is that Limn draws its own widgets
instead of wrapping the system's, a control is made on its own and added to a container
instead of being born inside a parent with style bits, there is nothing to dispose, and a list
or a table reads your own objects without content and label providers.

Each section below shows the SWT code first and the Limn code after it, and links to the page
of this guide that says the rest.

## The ideas, side by side

| SWT and JFace | Limn |
|---|---|
| `Display` | the backend: `new LwjglBackend()` |
| `Shell` | a `NativeWindow` from `backend.createWindow(…)`, and a `Scene` bound to it |
| the `readAndDispatch` loop | `backend.runEventLoop()` |
| `Composite` with a `Layout` | a layout widget: `Column`, `Row`, `Grid`, `Wrap`, `Stack` |
| `GridLayout` and `GridData` | `Grid`; `Expanded` and `columnAlignment` in place of `grab` and `fill` |
| `RowLayout` | `Row`, or `Wrap` when it wraps |
| `FillLayout` | a `Column` or a `Row` whose children are each `Expanded` |
| `FormLayout`, `StackLayout` | no counterpart for attachments; `scene.setRoot(…)` or `Stack` for stacking |
| `new Button(parent, SWT.PUSH)` | `new Button("…")`, added to its container |
| `SWT.CHECK`, `SWT.RADIO`, `SWT.TOGGLE` | `Checkbox`, `RadioButton`, `Checkbox.Variant.SWITCH` |
| `Label`, `Text`, `Text` with `SWT.PASSWORD`, `SWT.MULTI` | `Label`, `TextField`, `PasswordField`, `TextArea` |
| `Combo`, `CCombo` | `ComboBox`, editable or not |
| `List`, `Table`, `Tree` | `ListView`, `Table`, `Tree` |
| JFace `TableViewer`, `TreeViewer` and their providers | `Table` with a column per value, `Tree` with a `Tree.Model` |
| `ScrolledComposite`, `SashForm`, `TabFolder`, `CTabFolder` | `ScrollView`, `SplitPane`, `TabbedPane` |
| `ToolBar`, `ProgressBar`, `Scale`, `Slider`, `Spinner` | `ToolBar`, `ProgressBar`, `Slider`, `Spinner` |
| `DateTime` | `DateField`, `DatePicker` |
| `ColorDialog` | `ColorPicker`, `ColorPickerButton` |
| `Menu` with `SWT.BAR`, `SWT.CASCADE`, `SWT.POP_UP` | `MenuBar`, `Menu`, `PopupMenu` |
| `MenuItem.setAccelerator(SWT.MOD1 + 'S')` | `MenuItem.setAccelerator(Accelerator.command(Keys.S))` |
| `MessageBox`, JFace `MessageDialog` | `Dialog` |
| `FileDialog`, `DirectoryDialog` | `window.fileDialogs()` |
| `setToolTipText` | `setTooltip` |
| `display.asyncExec` | `Ui.post` |
| `display.timerExec` | `Ui.postDelayed`, which returns a handle that cancels it |
| a worker thread, or an Eclipse `Job` | `Ui.work(…)`, which returns a `Job` you can cancel |
| `SWT.Selection` listener | `onAction` on a button, `onSelect` on a choice |
| `ModifyListener` | `onChange` for the user's changes, `observeChanges` for every change |
| `Canvas` with a `PaintListener` and `GC` | a widget of your own with `onPaint(Canvas)` |
| `redraw()`, `layout()` | `invalidate()`, `markNeedsLayout()`; a built-in widget does both for itself |
| `Color`, `Font`, `Image` with `dispose()` | plain values, collected like any object |
| the system's look | `Theme` |
| `DragSource`, `DropTarget`, `Clipboard` | drag and drop and the clipboard ([Drag and drop](/docs/drag-and-drop/)) |
| `Printer`, `PrintDialog` | `Document`: saved as PDF, printed, previewed ([Printing and PDF](/docs/printing/)) |
| `Tray`, `TrayItem` | `Tray` |
| `Program.launch` | `window.launcher()` |

## The differences that matter

**Limn draws its own widgets.** An SWT button is the system's button; a Limn button is drawn
by Limn, the same on every system, in the palette of the current `Theme`. What stays the
system's own is what a user expects to be: the menu bar on macOS, the file choosers, the print
dialog and the tray. Screen readers are still told everything: Limn speaks to each system's
accessibility interface itself ([Accessibility](/docs/accessibility/)).

**A widget is made on its own.** There is no parent argument and no style bits. A widget is
created, configured with setters, and added to a container, and it can be taken out and put
somewhere else later. What a style bit chose becomes a class (`PasswordField`), a variant
(`Checkbox.Variant.SWITCH`) or a setter.

**Nothing to dispose.** Colours, fonts and images are plain Java values, and a window's widgets
go when the window does. There is no `dispose()` to forget and no "widget is disposed"
exception to chase.

**A handler answers the user.** `onChange`, `onAction`, `onSelect` and the other handlers run
when the user acts, not when your code calls `setText` or `setSelectedIndex`. An SWT
`ModifyListener` fires for both. When you do want every change, the code's included,
`observeChanges` is the watcher that hears them all.

**No providers.** A `Table` takes your `List<Order>` and a column for each thing to show, each a
function that reads one order. That function is the content provider and the label provider
at once: `setRows` with a new list, or `refresh()` after changing the objects in place.

**The thread rule is the same.** A widget setter called from another thread throws at once, as
SWT's "Invalid thread access" does. There is no `syncExec`: hand a result to the UI thread with
`Ui.post`, or use `Ui.work`, whose callbacks already run there.

**`main` runs the window.** `backend.runEventLoop()` is the `readAndDispatch` loop, written once
by the toolkit, and returns when the application ends. On macOS the thread has to be the
process's first one, which is why the JVM is started with `-XstartOnFirstThread` there, as for
SWT.

## A window

In SWT:

```java
public static void main(String[] args) {
    Display display = new Display();
    Shell shell = new Shell(display);
    shell.setText("Hello, SWT");
    shell.setSize(480, 320);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = layout.marginHeight = 24;
    layout.verticalSpacing = 12;
    shell.setLayout(layout);
    new Label(shell, SWT.NONE).setText("A window, drawn by SWT.");
    Button close = new Button(shell, SWT.PUSH);
    close.setText("Close");
    close.addListener(SWT.Selection, e -> shell.close());
    shell.open();
    while (!shell.isDisposed()) {
        if (!display.readAndDispatch()) {
            display.sleep();
        }
    }
    display.dispose();
}
```

In Limn:

{% snippet hello-window %}

[Install and first window](/docs/install/) has the dependency and the command line.

## A form

In SWT:

```java
Composite form = new Composite(parent, SWT.NONE);
GridLayout layout = new GridLayout(2, false);
layout.marginWidth = layout.marginHeight = 20;
layout.horizontalSpacing = 12;
layout.verticalSpacing = 10;
form.setLayout(layout);

new Label(form, SWT.NONE).setText("Name");
Text name = new Text(form, SWT.BORDER);
name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
new Label(form, SWT.NONE).setText("Email");
Text email = new Text(form, SWT.BORDER);
email.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

Composite buttons = new Composite(form, SWT.NONE);
buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
buttons.setLayout(new RowLayout(SWT.HORIZONTAL));
new Button(buttons, SWT.PUSH).setText("Cancel");
Button save = new Button(buttons, SWT.PUSH);
save.setText("Save");
```

In Limn:

{% snippet guide:migrate-layout %}

`setLabelFor` is what a screen reader reads as the field's name, the job a label placed before
a text field does on its own in SWT. [Layout](/docs/layout/) has every layout widget, and
[Forms](/docs/forms/) the `Field` that puts a label, a hint and a validation message around an
input.

## Events

In SWT:

```java
save.addListener(SWT.Selection, e -> status.setText("Saved " + name.getText()));
name.addModifyListener(e -> save.setEnabled(!name.getText().isBlank()));
notify.addListener(SWT.Selection, e ->
        status.setText(notify.getSelection() ? "You will be told" : "Quietly"));
plan.addListener(SWT.Selection, e -> status.setText("Plan: " + plan.getText()));
```

In Limn:

{% snippet guide:migrate-events %}

A widget has one handler of each kind, and setting a second throws: pass `null` first to
replace it. Watchers, as many as you like, are what `observeChanges` adds.

## Work off the UI thread

In SWT:

```java
status.setText("Searching…");
int asked = ++generation;                    // an older search must not land after a newer one
new Thread(() -> {
    try {
        List<String> found = repository.search(query);
        display.asyncExec(() -> {
            if (asked != generation || results.isDisposed()) return;
            results.setItems(found.toArray(new String[0]));
            status.setText(found.size() + " found");
        });
    } catch (Exception e) {
        display.asyncExec(() -> status.setText("Failed: " + e.getMessage()));
    }
}).start();
```

In Limn:

{% snippet guide:migrate-thread %}

Every callback runs on the UI thread, so none of them needs `asyncExec`, and a cancelled job
delivers nothing, which is the generation check written once. [Background work](/docs/background-work/)
has progress and cancellation.

## A timer

In SWT:

```java
Runnable tick = new Runnable() {
    @Override public void run() {
        if (time.isDisposed()) return;
        time.setText(LocalTime.now().withNano(0).toString());
        display.timerExec(1000, this);
    }
};
display.timerExec(0, tick);
// later
display.timerExec(-1, tick);
```

In Limn:

{% snippet guide:migrate-timer %}

For something that moves on screen, a transition or a ticker is the better tool:
[Animations](/docs/animations/).

## A table

In SWT:

```java
TableViewer viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
viewer.getTable().setHeaderVisible(true);
viewer.setContentProvider(ArrayContentProvider.getInstance());

TableViewerColumn number = new TableViewerColumn(viewer, SWT.NONE);
number.getColumn().setText("Order");
number.getColumn().setWidth(90);
number.setLabelProvider(new ColumnLabelProvider() {
    @Override public String getText(Object o) { return ((Order) o).number(); }
});
TableViewerColumn customer = new TableViewerColumn(viewer, SWT.NONE);
customer.getColumn().setText("Customer");
customer.getColumn().setWidth(200);
customer.setLabelProvider(new ColumnLabelProvider() {
    @Override public String getText(Object o) { return ((Order) o).customer(); }
});
TableViewerColumn total = new TableViewerColumn(viewer, SWT.RIGHT);
total.getColumn().setText("Total");
total.getColumn().setWidth(100);
total.setLabelProvider(new ColumnLabelProvider() {
    @Override public String getText(Object o) { return String.valueOf(((Order) o).total()); }
});

viewer.setInput(rows);
viewer.addDoubleClickListener(e -> {
    Order o = (Order) e.getStructuredSelection().getFirstElement();
    status.setText("Opened " + o.number());
});
```

In Limn:

{% snippet guide:migrate-table %}

A column is the label provider and the content provider in one function. The table sorts from
its headers with no `ViewerComparator`, and only builds the rows on screen, so a table over a
million rows costs what one over twenty does, with no `SWT.VIRTUAL` to ask for.
[Lists and scrolling](/docs/lists-and-scrolling/) has selection, footers, frozen columns and a
button in a row.

## Menus and dialogs

In SWT:

```java
Menu bar = new Menu(shell, SWT.BAR);
MenuItem fileItem = new MenuItem(bar, SWT.CASCADE);
fileItem.setText("&File");
Menu file = new Menu(shell, SWT.DROP_DOWN);
fileItem.setMenu(file);

MenuItem saveItem = new MenuItem(file, SWT.PUSH);
saveItem.setText("&Save\tCtrl+S");
saveItem.setAccelerator(SWT.MOD1 + 'S');
saveItem.addListener(SWT.Selection, e -> save.run());
new MenuItem(file, SWT.SEPARATOR);
MenuItem deleteItem = new MenuItem(file, SWT.PUSH);
deleteItem.setText("Delete…");
deleteItem.addListener(SWT.Selection, e -> {
    MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
    box.setText("Delete this order?");
    box.setMessage("This cannot be undone.");
    if (box.open() == SWT.YES) delete.run();
});
shell.setMenuBar(bar);
```

In Limn:

{% snippet guide:migrate-menu %}

`show` returns at once and the answer arrives later, so the window keeps drawing while the
question is up, where `MessageBox.open` waits. The buttons are named for what they do rather
than Yes and No. `Accelerator.command` is `SWT.MOD1`: Command on macOS, Control elsewhere.
[Menus and dialogs](/docs/menus-and-dialogs/) has the rest.

## Choosing a file

In SWT:

```java
FileDialog dialog = new FileDialog(shell, SWT.OPEN);
dialog.setText("Open invoice");
dialog.setFilterPath(lastFolder.toString());
dialog.setFilterNames(new String[]{"PDF"});
dialog.setFilterExtensions(new String[]{"*.pdf"});
String chosen = dialog.open();
if (chosen != null) {
    load.accept(Path.of(chosen));
}
```

In Limn:

{% snippet guide:migrate-file %}

Both are the system's own chooser. Limn's does not block: the window keeps drawing while the
user browses, and an empty answer means cancelled.

## Painting your own

In SWT:

```java
Canvas meter = new Canvas(parent, SWT.DOUBLE_BUFFERED);
meter.setLayoutData(new GridData(160, 12));
meter.addPaintListener(e -> {
    Rectangle area = meter.getClientArea();
    e.gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
    e.gc.fillRectangle(area);
    e.gc.setBackground(display.getSystemColor(SWT.COLOR_LIST_SELECTION));
    e.gc.fillRectangle(0, 0, Math.round(area.width * level), area.height);
});
// after a change of level
meter.redraw();
```

In Limn:

{% snippet guide:migrate-paint %}

The widget says how big it wants to be instead of being given layout data, and its colours
come from the theme, so it follows a palette change with no code and nothing to dispose. Sizes
are in points and the canvas is already scaled for the display.
[Your own widgets](/docs/custom-widgets/) has input, focus and what a screen reader is told.

## The look

SWT has none of its own to change: its controls are the system's. Limn's look is a palette,
which you pick from the built-ins or build from your brand:

{% snippet guide:migrate-theme %}

[Theming](/docs/theming/) has the built-in palettes, density, fonts and following the
operating system's light or dark appearance.

## What Limn does not have

Some of what SWT and the Eclipse platform give you has no Limn counterpart, and it is better
to know before you plan a migration than in the middle of one.

- **No native controls.** That is the design rather than a gap, but it is the first thing an
  SWT user notices: a Limn window looks like Limn on every system, not like that system's own
  controls.
- **No workbench.** There are no views, editors, perspectives, extension points or plug-ins:
  Limn is a toolkit, not an application platform. An RCP application moves its screens, and
  keeps or replaces its own structure around them.
- **No data binding.** There is no counterpart to JFace data binding; values are set from
  handlers and watchers.
- **No mixing.** Limn does not host SWT controls, and a Limn scene cannot be put inside a
  `Shell`. Running both toolkits in one process is not something Limn supports or tests, so a
  migration moves an application, or a separate program of it, at a time.
- **No browser and no styled text.** There is no `Browser` widget; `window.launcher()` opens a
  page in the user's own. There is no `StyledText`: a `Label` wraps and a `TextArea` edits
  plain text.
- **No form attachments.** There is no `FormLayout`; a layout of your own is a widget that places
  its children ([Your own widgets](/docs/custom-widgets/#a-layout-of-your-own)).
