package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.Theme;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Component showcase: Labels with real-measured ellipsis at three widths,
 * an optional-wrap paragraph, Buttons in every state (click counter proves
 * onAction + invalidation), classic Checkbox and animated Switch, plus a
 * light/dark theme toggle; components read every color from the Theme.
 */
final class ComponentsScene {

    private ComponentsScene() {
    }

    static Scene create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());

        Column content = new Column();
        content.gap(14).crossAlignment(Flex.CrossAlignment.START);

        // Role, not setFont(theme.title): setFont would pin MEDIUM's 20 pt even when
        // the scene resolves to another step.
        content.add(new Label("Limn UI: components").setRole(Label.Role.TITLE));

        // Ellipsis at three widths: same long text, measured cuts.
        String longText = "A very long text demonstrating ellipsis truncation measured with real glyphs";
        Column ellipsisBlock = new Column();
        ellipsisBlock.gap(6);
        for (float width : new float[] {420, 260, 120}) {
            // setMuted (not setColor): the color is resolved at paint time and
            // follows runtime theme switches.
            ellipsisBlock.add(new SizedBox(width, 20, new Label(longText).setMuted(true)));
        }
        content.add(ellipsisBlock);

        // Wrapped paragraph in a fixed-width box.
        content.add(new SizedBox(420, SizedBox.UNSET, new Label(
                "With wrap enabled, the paragraph breaks on words using the font's "
                        + "real measurement; words that are too wide break mid-word.")
                .setWrap(true).setMuted(true)));

        // Buttons: action counter, disabled.
        Label counter = new Label("clicks: 0");
        Button clicker = new Button("Click here");
        int[] clicks = {0};
        clicker.onAction(() -> counter.setText("clicks: " + (++clicks[0])));
        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        Row buttons = new Row();
        buttons.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        buttons.add(clicker);
        buttons.add(disabled);
        buttons.add(counter);
        content.add(buttons);

        // Checkboxes: classic box, animated switch, disabled checked.
        Checkbox box = new Checkbox(Checkbox.Variant.BOX, "Notifications");
        Checkbox airplane = new Checkbox(Checkbox.Variant.SWITCH, "Airplane mode");
        Checkbox frozen = new Checkbox(Checkbox.Variant.BOX, "Locked (disabled)");
        frozen.setChecked(true);
        frozen.setEnabled(false);
        Checkbox switchOn = new Checkbox(Checkbox.Variant.SWITCH, "On");
        switchOn.setChecked(true);
        Row toggles = new Row();
        toggles.gap(20).crossAlignment(Flex.CrossAlignment.CENTER);
        toggles.add(box);
        toggles.add(airplane);
        toggles.add(switchOn);
        toggles.add(frozen);
        content.add(toggles);

        content.add(Expanded.spacer(1));

        // Theme toggle: swaps the global theme and re-layouts everything.
        Button themeToggle = new Button(lightTheme ? "Switch to dark theme" : "Switch to light theme");
        content.add(themeToggle);

        Widget root = Padding.all(20, content);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background);
        themeToggle.onAction(() -> {
            boolean toLight = Theme.current() == Theme.dark();
            Theme.setCurrent(toLight ? Theme.light() : Theme.dark());
            themeToggle.setText(toLight ? "Switch to dark theme" : "Switch to light theme");
            scene.setBackground(Theme.current().background);
            root.markNeedsLayout(); // typography/sizes may differ between themes
        });
        return scene;
    }
}
