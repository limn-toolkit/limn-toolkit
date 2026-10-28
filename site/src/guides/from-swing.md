---
title: "Coming from Swing"
description: "What each Swing class and habit becomes in Limn, the everyday tasks written in both, and what Limn does not have."
---

This page is for someone who already writes Swing. Most of what you know carries over: a
window holds a tree of components, events arrive as callbacks, one thread owns the interface,
and slow work goes elsewhere. What changes is that layout is done by widgets instead of layout
managers, the thread rule is enforced, a list or a table reads your own objects instead of a
model class, and the look comes from one themed palette instead of a pluggable look and feel.

Each section below shows the Swing code first and the Limn code after it, and links to the
page of this guide that says the rest.

## The ideas, side by side

| Swing | Limn |
|---|---|
| `JFrame` | a `NativeWindow` from `backend.createWindow(…)`, and a `Scene` bound to it |
| `JPanel` with a layout manager | a layout widget: `Column`, `Row`, `Grid`, `Wrap`, `Stack` |
| `BorderLayout` | a `Column` or a `Row`, with `Expanded` on the part that takes the space left |
| `GridBagLayout`, `GridLayout` | `Grid` |
| `FlowLayout` | `Wrap` |
| `CardLayout` | `scene.setRoot(…)` for a whole page, `TabbedPane` for tabs |
| `JLabel`, `JButton`, `JCheckBox`, `JRadioButton` | `Label`, `Button`, `Checkbox`, `RadioButton` |
| `JTextField`, `JPasswordField`, `JTextArea` | `TextField`, `PasswordField`, `TextArea` |
| `JFormattedTextField` | `TextField.setMask(…)`, `Spinner`, `DateField` |
| `JComboBox` | `ComboBox`, editable or not |
| `JList`, `JTable`, `JTree` | `ListView`, `Table`, `Tree` |
| `JScrollPane`, `JSplitPane`, `JTabbedPane` | `ScrollView`, `SplitPane`, `TabbedPane` |
| `JToolBar`, `JProgressBar`, `JSlider`, `JSpinner` | `ToolBar`, `ProgressBar`, `Slider`, `Spinner` |
| `JMenuBar`, `JMenu`, `JMenuItem` | `MenuBar`, `Menu`, `MenuItem` |
| `JPopupMenu` | `PopupMenu` |
| `JOptionPane`, `JDialog` | `Dialog` |
| `JFileChooser` | `window.fileDialogs()`, the system's own chooser |
| `JColorChooser` | `ColorPicker`, `ColorPickerButton` |
| `setToolTipText` | `setTooltip` |
| `KeyStroke` on a menu item | `MenuItem.setAccelerator(Accelerator.command(Keys.S))` |
| `SwingUtilities.invokeLater` | `Ui.post` |
| `SwingWorker` | `Ui.work(…)`, which returns a `Job` you can cancel |
| `javax.swing.Timer` | `Ui.postDelayed`, which returns a handle that cancels it |
| `ActionListener` | `onAction` |
| `DocumentListener`, `ItemListener` | `onChange`, which runs for the user's changes |
| `PropertyChangeListener` | `observeChanges`, which hears every change |
| `paintComponent(Graphics)` | a widget of your own with `onPaint(Canvas)` |
| `repaint()`, `revalidate()` | `invalidate()`, `markNeedsLayout()`; a built-in widget does both for itself |
| look and feel, `UIManager` | `Theme` |
| `TransferHandler` | drag and drop and the clipboard ([Drag and drop](/docs/drag-and-drop/)) |
| `PrinterJob`, `JTable.print` | `Document`: saved as PDF, printed, previewed ([Printing and PDF](/docs/printing/)) |
| `SystemTray`, `TrayIcon` | `Tray` |
| `Desktop.browse`, `Desktop.open` | `window.launcher()` |

## The differences that matter

**The thread rule is enforced.** Swing tells you to touch components only on the event
dispatch thread and mostly lets you get away with breaking that, until a repaint goes wrong
somewhere you cannot reproduce. In Limn a setter called from another thread throws at once,
with a message that says so. The rule is the same; finding the mistake is not.

**`main` runs the window.** `backend.runEventLoop()` takes the thread that created the backend
and returns when the application ends. On macOS that thread has to be the process's first one,
which is why the JVM is started with `-XstartOnFirstThread` there.

**A handler answers the user.** `onChange`, `onAction`, `onSelect` and the other handlers run
when the user acts, not when your code calls `setText` or `setSelectedIndex`. A Swing
`DocumentListener` fires for both, and code that fills a form often has to guard against its
own listeners. When you do want every change, the code's included, `observeChanges` is the
watcher that hears them all.

**No model classes.** A `Table` takes your `List<Order>` and a column for each thing to show,
each a function that reads one order. There is no `TableModel` to implement and no
`fireTableDataChanged` to remember: `setRows` with a new list, or `refresh()` after changing
the objects in place.

**Layout is widgets.** There is no `setLayout`: a `Column` is the container and the layout at
once. There is also no absolute positioning with a `null` layout; sizes come from what each
widget measures, in points, and density is a setting (`ControlSize`) rather than a size you
hard-code.

**One look, themed.** There is no pluggable look and feel. Every widget draws from the
current `Theme`, which you can pick from the built-ins, build from your brand colours, or let
your users edit, and every open window repaints in it. Following the operating system's light or
dark appearance is a few lines of yours ([Theming](/docs/theming/#following-the-operating-system)).

## A window

In Swing:

```java
public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
        JFrame frame = new JFrame("Hello, Swing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(new JLabel("A window, drawn by Swing."));
        panel.add(Box.createVerticalStrut(12));
        JButton close = new JButton("Close");
        close.addActionListener(e -> frame.dispose());
        panel.add(close);
        frame.setContentPane(panel);
        frame.setSize(480, 320);
        frame.setVisible(true);
    });
}
```

In Limn:

{% snippet hello-window %}

[Install and first window](/docs/install/) has the dependency and the command line.

## A form

In Swing:

```java
JPanel fields = new JPanel(new GridBagLayout());
GridBagConstraints c = new GridBagConstraints();
c.insets = new Insets(5, 6, 5, 6);
c.anchor = GridBagConstraints.LINE_START;
c.gridx = 0; c.gridy = 0;
fields.add(new JLabel("Name"), c);
c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
fields.add(name, c);
c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
fields.add(new JLabel("Email"), c);
c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
fields.add(email, c);

JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
buttons.add(new JButton("Cancel"));
buttons.add(save);

JPanel form = new JPanel(new BorderLayout(0, 16));
form.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
form.add(fields, BorderLayout.CENTER);
form.add(buttons, BorderLayout.SOUTH);
```

In Limn:

{% snippet guide:migrate-layout %}

`setLabelFor` is what a screen reader reads as the field's name, as `JLabel.setLabelFor` is.
[Layout](/docs/layout/) has every layout widget, and [Forms](/docs/forms/) the `Field` that
puts a label, a hint and a validation message around an input.

## Events

In Swing:

```java
save.addActionListener(e -> status.setText("Saved " + name.getText()));
name.getDocument().addDocumentListener(new DocumentListener() {
    public void insertUpdate(DocumentEvent e) { changed(); }
    public void removeUpdate(DocumentEvent e) { changed(); }
    public void changedUpdate(DocumentEvent e) { changed(); }
    private void changed() { save.setEnabled(!name.getText().isBlank()); }
});
notify.addItemListener(e -> status.setText(notify.isSelected() ? "You will be told" : "Quietly"));
plan.addActionListener(e -> status.setText("Plan: " + plan.getSelectedItem()));
```

In Limn:

{% snippet guide:migrate-events %}

A widget has one handler of each kind, and setting a second throws: pass `null` first to
replace it. Watchers, as many as you like, are what `observeChanges` adds.

## Work off the UI thread

In Swing:

```java
if (worker != null) {
    worker.cancel(true);
}
status.setText("Searching…");
worker = new SwingWorker<List<String>, Void>() {
    @Override protected List<String> doInBackground() {
        return repository.search(query);
    }
    @Override protected void done() {
        if (isCancelled()) return;
        try {
            List<String> found = get();
            results.setListData(found.toArray(new String[0]));
            status.setText(found.size() + " found");
        } catch (Exception e) {
            status.setText("Failed: " + e.getMessage());
        }
    }
};
worker.execute();
```

In Limn:

{% snippet guide:migrate-thread %}

A cancelled job delivers nothing, and every callback runs on the UI thread, so none of them
needs `invokeLater`. [Background work](/docs/background-work/) has progress and cancellation.

## A timer

In Swing:

```java
Timer timer = new Timer(1000, e -> time.setText(LocalTime.now().withNano(0).toString()));
timer.start();
// later
timer.stop();
```

In Limn:

{% snippet guide:migrate-timer %}

For something that moves on screen, a transition or a ticker is the better tool:
[Animations](/docs/animations/).

## A table

In Swing:

```java
class OrderModel extends AbstractTableModel {
    private final List<Order> rows;
    OrderModel(List<Order> rows) { this.rows = rows; }
    public int getRowCount() { return rows.size(); }
    public int getColumnCount() { return 3; }
    public String getColumnName(int c) { return new String[]{"Order", "Customer", "Total"}[c]; }
    public Object getValueAt(int r, int c) {
        Order o = rows.get(r);
        return switch (c) { case 0 -> o.number(); case 1 -> o.customer(); default -> o.total(); };
    }
}
JTable table = new JTable(new OrderModel(rows));
table.setAutoCreateRowSorter(true);
table.addMouseListener(new MouseAdapter() {
    @Override public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
            int row = table.convertRowIndexToModel(table.getSelectedRow());
            status.setText("Opened " + rows.get(row).number());
        }
    }
});
JScrollPane scroll = new JScrollPane(table);
```

In Limn:

{% snippet guide:migrate-table %}

The table scrolls by itself, sorts from its headers, and only builds the rows on screen, so a
table over a million rows costs what one over twenty does. [Lists and scrolling](/docs/lists-and-scrolling/)
has selection, footers, frozen columns and a button in a row.

## Menus and dialogs

In Swing:

```java
JMenu file = new JMenu("File");
JMenuItem saveItem = new JMenuItem("Save");
saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
saveItem.addActionListener(e -> save.run());
file.add(saveItem);
file.addSeparator();
JMenuItem deleteItem = new JMenuItem("Delete…");
deleteItem.addActionListener(e -> {
    int answer = JOptionPane.showOptionDialog(frame, "This cannot be undone.",
            "Delete this order?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, new String[]{"Keep", "Delete"}, "Keep");
    if (answer == 1) delete.run();
});
file.add(deleteItem);
JMenuBar bar = new JMenuBar();
bar.add(file);
frame.setJMenuBar(bar);
```

In Limn:

{% snippet guide:migrate-menu %}

`show` returns at once and the answer arrives later, so the window keeps drawing while the
question is up; nothing blocks the way `showOptionDialog` does. `Accelerator.command` is
Command on macOS and Control elsewhere. [Menus and dialogs](/docs/menus-and-dialogs/) has the
rest, the system's menu bar on macOS included.

## Choosing a file

In Swing:

```java
JFileChooser chooser = new JFileChooser(lastFolder.toFile());
chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
    load.accept(chooser.getSelectedFile().toPath());
}
```

In Limn:

{% snippet guide:migrate-file %}

This is the operating system's own chooser, not a drawing of one: a sheet on macOS, the
Windows dialog, the desktop portal on Linux.

## Painting your own

In Swing:

```java
class Meter extends JComponent {
    private float level;
    void setLevel(float level) { this.level = Math.max(0, Math.min(1, level)); repaint(); }
    @Override public Dimension getPreferredSize() { return new Dimension(160, 12); }
    @Override protected void paintComponent(Graphics g) {
        g.setColor(UIManager.getColor("Separator.foreground"));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(UIManager.getColor("ProgressBar.foreground"));
        g.fillRect(0, 0, Math.round(getWidth() * level), getHeight());
    }
}
```

In Limn:

{% snippet guide:migrate-paint %}

Sizes are in points and the canvas is already scaled for the display, so the same numbers are
sharp on a Retina screen and a plain one. Colours come from the theme, so the meter follows a
palette change and dark mode with no code. [Your own widgets](/docs/custom-widgets/) has input,
focus and what a screen reader is told.

## The look

In Swing:

```java
UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
UIManager.put("Button.background", new Color(0x0F6E4F));
SwingUtilities.updateComponentTreeUI(frame);
```

In Limn:

{% snippet guide:migrate-theme %}

[Theming](/docs/theming/) has the built-in palettes, density, fonts and following the
operating system's appearance.

## What Limn does not have

Some of what Swing gives you has no Limn counterpart, and it is better to know before you
plan a migration than in the middle of one.

- **No mixing.** Limn does not host Swing components, and a Limn scene cannot be put inside a
  `JFrame`. Running both toolkits in one process is not something Limn supports or tests, so a
  migration moves an application, or a separate program of it, at a time.
- **No rich text.** There is no `JEditorPane` or `JTextPane`: no HTML rendering, no styled
  document. A `Label` wraps and a `TextArea` edits plain text.
- **No web view.** There is no embedded browser; `window.launcher()` opens a page in the
  user's own.
- **No internal frames or docking.** There is no `JDesktopPane`. A `SplitPane`, tabs and
  separate windows are what Limn offers.
- **No saved settings.** Limn does not persist window positions or preferences for you;
  `java.util.prefs` or a file of your own is the place for them.
- **No custom layout managers by constraint object.** A layout of your own is a widget that
  places its children ([Your own widgets](/docs/custom-widgets/#a-layout-of-your-own)), not a
  `LayoutManager2` fed with constraints.
