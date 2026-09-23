package limn.backend.lwjgl.a11y;

import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ListView;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.Separator;
import limn.components.Slider;
import limn.components.TextField;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.i18n.I18nString;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * The widgets every live probe puts in front of a screen reader, and the way it moves them.
 *
 * <p>One scene for all three platforms, because a widget added for one of them is a widget the
 * other two should be read against: the roles differ per platform, the tree does not, and three
 * probes that had drifted apart would make a difference between platforms look like a difference
 * between bridges.
 *
 * <p><b>The set is chosen for what each widget can go wrong at, not for coverage.</b> A switch and a
 * check box are different roles and only one platform makes the difference cheap; a password field
 * and a search field are one role with different subroles; the slider, the progress bar and the
 * combo box are the three that share a single attribute on macOS and are three facets for exactly
 * that reason. And <b>half of them carry no value</b>, which matters more than it looks.
 *
 * <p><b>The cycle moves the focus and touches nothing else.</b> That is not tidiness either. With a
 * cycle that also toggled the check box, the macOS bridge looked half-working for four runs: the
 * check box spoke, so focus seemed to be followed, and only the button — which has nothing to say
 * but its own name — was silent. Nothing was being followed at all. A probe whose every step also
 * changes a value cannot see that class of defect, so {@code probe.cycle=value} drives the other
 * half separately.
 *
 * <p><b>Two more cycles exist for one count and not for reading.</b> ADR&nbsp;039 &sect;13.19 says
 * the event queue's capacity is a policy with no measurement behind it, and names the measurement:
 * how many events one frame's difference produces while a list scrolls and a slider is dragged,
 * with a reader attached. {@code probe.cycle=scroll} pages the list by a viewport per tick and
 * {@code probe.cycle=drag} moves the slider by one per tick, which is a drag sampled once per
 * frame when the tick is short. The list is in the scene on every run so that the tree a reader
 * hears is the same tree the count is taken over.
 */
public final class ProbeScene {

    /** The widgets a focus cycle walks, in the order it walks them. */
    public final List<Widget<?>> focusable;

    private final Column root;
    private final Checkbox wrap;
    private final Slider volume;
    private final ListView<I18nString> rows;
    private int step;

    /** How many rows the list carries: enough that a page scroll realizes a new set every tick. */
    public static final int ROWS = 300;
    /** A row's box, fixed so that the list is the same shape with and without a font. */
    static final float ROW_WIDTH = 240;
    static final float ROW_HEIGHT = 32;
    /** The viewport: five rows, so a page scroll releases one set and realizes another. */
    static final float LIST_HEIGHT = 5 * ROW_HEIGHT;

    public ProbeScene() {
        root = new Column();
        root.add(new Label("Limn accessibility probe"));

        Button save = new Button("Save");
        save.onAction(() -> say("Save pressed, through the toolkit's own path"));
        root.add(save);

        wrap = new Checkbox(Checkbox.Variant.BOX, "Wrap lines");
        wrap.onChange(on -> say("Wrap toggled to " + on));
        root.add(wrap);

        Checkbox dark = new Checkbox(Checkbox.Variant.SWITCH, "Dark mode");
        root.add(dark);

        TextField name = new TextField();
        name.setPlaceholder("Nome do arquivo");
        name.setText("relatório.txt");
        root.add(name);

        PasswordField secret = new PasswordField();
        secret.setText("hunter2");
        root.add(secret);

        SearchField search = new SearchField();
        search.setPlaceholder("Buscar");
        root.add(search);

        volume = new Slider(0, 100);
        volume.setValue(40);
        root.add(volume);

        ProgressBar progress = new ProgressBar();
        progress.setProgress(0.6f);
        root.add(progress);

        ComboBox format = new ComboBox(List.of("PDF", "Markdown", "HTML"));
        format.setSelectedIndex(1);
        root.add(format);

        RadioButton daily = new RadioButton("Diário");
        RadioButton weekly = new RadioButton("Semanal");
        new ButtonGroup().add(daily).add(weekly).setSelectedIndex(0);
        root.add(daily);
        root.add(weekly);

        root.add(Separator.horizontal());

        // Names held by the adapter, as the Adapter contract asks: a name built inside rowName
        // would be a fresh object per realized row per frame and republish the tree every frame,
        // which would make this count measure the probe rather than the toolkit.
        I18nString[] names = new I18nString[ROWS];
        for (int i = 0; i < ROWS; i++) {
            names[i] = I18nString.literal("Linha " + (i + 1));
        }
        // A fixed-height box around the label, and not the label alone: with no font installed a
        // label measures to nothing, and a list whose every row is zero points tall walks its
        // anchor to the last row before the first frame — so the headless test that checks this
        // cycle would read a list already scrolled to its end. The reader still hears the label;
        // the box is what the row is tall by.
        rows = new ListView<>(text -> new SizedBox(ROW_WIDTH, ROW_HEIGHT, new Label(text)));
        rows.setItems(List.of(names));
        rows.setItemName(text -> text);
        root.add(new SizedBox(ROW_WIDTH, LIST_HEIGHT, rows));

        focusable = List.of(save, wrap, dark, name, secret, search, volume, format, daily, weekly);
    }

    /** @return the tree to hand a {@link Scene}. */
    public Column root() {
        return root;
    }

    /**
     * Moves the probe on by one step, on the user-interface thread.
     *
     * @param scene the scene to move focus in
     */
    public void tick(Scene scene) {
        String cycle = System.getProperty("probe.cycle", "focus");
        switch (cycle) {
            case "value" -> {
                wrap.setChecked(!wrap.isChecked());
                volume.setValue((volume.value() + 10) % 100);
            }
            case "scroll" -> rows.scrollBy(step % 2 == 0 ? LIST_HEIGHT : -LIST_HEIGHT);
            case "drag" -> volume.setValue((volume.value() + 1) % 100);
            default -> scene.requestFocus(focusable.get(step % focusable.size()));
        }
        step++;
        say("--- step " + step + " ---");
    }

    /** @return the list the scroll cycle pages, for a probe that wants to focus it first. */
    public ListView<I18nString> rows() {
        return rows;
    }

    /** @return how many steps have run. */
    public int steps() {
        return step;
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
