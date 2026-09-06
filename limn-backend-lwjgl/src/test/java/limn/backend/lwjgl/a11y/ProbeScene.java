package limn.backend.lwjgl.a11y;

import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.Separator;
import limn.components.Slider;
import limn.components.TextField;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;

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
 */
public final class ProbeScene {

    /** The widgets a focus cycle walks, in the order it walks them. */
    public final List<Widget> focusable;

    private final Column root;
    private final Checkbox wrap;
    private final Slider volume;
    private int step;

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
        if ("value".equals(System.getProperty("probe.cycle"))) {
            wrap.setChecked(!wrap.isChecked());
            volume.setValue((volume.value() + 10) % 100);
        } else {
            scene.requestFocus(focusable.get(step % focusable.size()));
        }
        step++;
        say("--- step " + step + " ---");
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
