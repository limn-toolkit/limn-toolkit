package limn.demo;

import limn.components.Label;
import limn.components.TabbedPane;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Small, single-purpose scenes for the spec's named verification screenshots:
 * {@code textfield-selected}, {@code ellipsis}, {@code textarea-scroll} and
 * a tab-alignment demo. Some need setup after the first layout (scroll), passed
 * back as {@link Built#afterLayout}.
 */
final class CaptureScenes {

    record Built(Scene scene, Runnable afterLayout) {
        Built(Scene scene) {
            this(scene, () -> {
            });
        }
    }

    record ComboBuilt(Scene scene, limn.components.ComboBox combo) {
    }

    private CaptureScenes() {
    }

    record DialogBuilt(Scene scene, java.util.function.Supplier<limn.components.Dialog> opener) {
    }

    /** Owner scene + an opener that shows a modal Dialog in the given style. */
    static DialogBuilt dialogWithStyle(limn.backend.WindowStyle style) {
        Theme.setCurrent(Theme.dark());
        Column col = column();
        col.add(new Label("Owner window: locked and dimmed while the modal is open")
                .setMuted(true).setWrap(true));
        Scene scene = scene(col);
        java.util.function.Supplier<limn.components.Dialog> opener = () -> {
            limn.components.Dialog dialog =
                    new limn.components.Dialog("Style: " + style,
                            "Sample modal dialog in this window style.")
                            .setStyle(style)
                            .addButton("Cancel", "cancel")
                            .addPrimaryButton("OK", "ok");
            dialog.show(scene);
            return dialog;
        };
        return new DialogBuilt(scene, opener);
    }

    static Built textfieldSelected(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        TextField field = new TextField().setText("Selected text in Limn UI");
        Column col = column();
        col.add(new Label("TextField with focus and selection").setMuted(true));
        col.add(field);
        Scene scene = scene(col);
        scene.requestFocus(field);
        field.model().selectAll();
        return new Built(scene);
    }

    static Built textfieldIme(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        TextField field = new TextField().setText("chat: ");
        Column col = column();
        col.add(new Label("TextField with an active IME composition (preedit)").setMuted(true));
        col.add(field);
        Scene scene = scene(col);
        scene.requestFocus(field);
        // Inject a composition the way the platform IME would, once layout exists.
        // Two clauses ("konnichi" | "wa"); the second is being converted (focused).
        // Latin glyphs keep the capture legible: the render path is glyph-agnostic,
        // so it exercises the underline/focused-block/caret exactly as CJK would.
        Runnable afterLayout = () -> {
            scene.preeditChanged("konnichiwa", new int[] {8, 2}, 1, 10);
            scene.inputBatchEnded();
        };
        return new Built(scene, afterLayout);
    }

    static Built fonts(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        FontsScene.Built built = FontsScene.build();
        Scene scene = scene(built.widget());
        // Insert twice: text after the first run's color emoji must keep the text
        // color (regression guard: a drawn color emoji used to leak white).
        Runnable afterLayout = () -> {
            scene.requestFocus(built.field());
            built.insert().run();
            built.insert().run();
        };
        return new Built(scene, afterLayout);
    }

    static Built fontsSwitched(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        FontsScene.Built built = FontsScene.build();
        Scene scene = scene(built.widget());
        Runnable afterLayout = () -> {
            built.insert().run();
            // Switch the live UI font to a visually distinct family if one is on the
            // system, else the bundled Noto Sans CJK (deterministic proof of the switch).
            java.util.List<String> available = limn.graphics.Fonts.available();
            String pick = "Noto Sans CJK";
            for (String preferred : java.util.List.of("Georgia", "Times New Roman", "Menlo", "Courier New")) {
                if (available.stream().anyMatch(f -> f.equalsIgnoreCase(preferred))) {
                    pick = preferred;
                    break;
                }
            }
            limn.graphics.Fonts.setDefaultFamily(pick);
        };
        return new Built(scene, afterLayout);
    }

    static Built textareaIme(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        TextArea area = new TextArea().setText("first line\nsecond line: compose here\nthird line");
        area.setPreferredSize(460, 130);
        Column col = column();
        col.add(new Label("TextArea composing (preedit) on the second line").setMuted(true));
        col.add(area);
        Scene scene = scene(col);
        scene.requestFocus(area);
        Runnable afterLayout = () -> {
            // Put the caret mid-way on the second line, then inject a composition.
            area.model().setCursor("first line\n".length() + 6, false);
            scene.preeditChanged("konnichiwa", new int[] {8, 2}, 1, 10);
            scene.inputBatchEnded();
        };
        return new Built(scene, afterLayout);
    }

    /**
     * The password mask across the whole ramp, each row next to the letters it stands for.
     *
     * <p>The one picture that answers "is the dot proportional to the type?", which a single
     * step cannot, and which is how the old per-step glyph table (a 1.2&nbsp;pt dot at XSMALL,
     * 17.1&nbsp;pt at XLARGE) went unnoticed. The plain Label repeats the same secret at the
     * same step, so the dot is read against the letterforms, not against the previous row.
     *
     * <p>The MEDIUM row is focused with everything selected, which shows the other half of a
     * drawn mask: the selection band is measured, the dots are painted, and the two agree only
     * because both come from the same advance. A band that ran short or long of the dots would
     * be the caret drifting off the character it edits, made visible.
     */
    static Built passwordRamp(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Column col = column();
        col.add(new Label("Password mask: one row per step").setRole(Label.Role.TITLE));
        col.add(new Label("The dot is drawn, not typeset: one ratio of the body font at every step.")
                .setMuted(true).setWrap(true));
        limn.components.PasswordField identityStep = null;
        for (limn.scene.ControlSize step : limn.scene.ControlSize.values()) {
            limn.components.PasswordField field = new limn.components.PasswordField();
            field.setText("Passw0rd!");
            Row row = new Row();
            row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(new Label(step.name()).setMuted(true));
            row.add(field);
            row.add(new Label("Passw0rd!"));
            row.setControlSize(step);
            col.add(row);
            if (step == limn.scene.ControlSize.MEDIUM) {
                identityStep = field;
            }
        }
        Scene scene = scene(col);
        scene.requestFocus(identityStep);
        identityStep.model().selectAll();
        return new Built(scene);
    }

    static Built ellipsis(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        String text = "A long text demonstrating ellipsis truncation measured with real glyphs";
        Column col = column();
        col.add(new Label("Measured ellipsis at decreasing widths").setMuted(true));
        for (float w : new float[] {440, 340, 240, 160, 90}) {
            col.add(new SizedBox(w, 22, new Label(text)));
        }
        return new Built(scene(col));
    }

    static Built textareaScroll(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            sb.append("Line ").append(i);
            if (i % 5 == 0) {
                sb.append(", a very long line to exercise the horizontal scrollbar too");
            }
            sb.append('\n');
        }
        TextArea area = new TextArea();
        area.setText(sb.toString());
        Column col = column();
        col.add(new Label("Scrolled TextArea (vertical and horizontal scrollbars)").setMuted(true));
        col.add(Expanded.of(area, 1));
        Scene scene = scene(col);
        // After layout the content/viewport sizes are known → scroll into the middle.
        return new Built(scene, () -> area.scrollBy(120, 220));
    }

    static Built tabsAlignment(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Column col = column();
        col.add(new Label("TabbedPane: tab alignment").setFont(Theme.current().title));
        col.add(alignedTabs("Left", TabbedPane.TabAlignment.LEFT));
        col.add(alignedTabs("Center", TabbedPane.TabAlignment.CENTER));
        col.add(alignedTabs("Right", TabbedPane.TabAlignment.RIGHT));
        return new Built(scene(col));
    }

    /** A 40-item combo with a deep selection: the popup must clamp, scroll and reveal it. */
    static ComboBuilt comboOverflow(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Column col = column();
        col.add(new Label("ComboBox: popup taller than the screen (clamp + scroll + reveal)")
                .setFont(Theme.current().title));
        java.util.List<String> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            items.add("Option " + i);
        }
        limn.components.ComboBox combo = new limn.components.ComboBox(items);
        combo.setSelectedIndex(39); // the last item: the popup must open pre-scrolled to it
        col.add(combo);
        return new ComboBuilt(scene(col), combo);
    }

    /** Overflow: more tabs than the strip fits → chevrons + tab-list button + reveal. */
    static Built tabsOverflow(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Column col = column();
        col.add(new Label("TabbedPane: overflow (scrollable strip + tab list)")
                .setFont(Theme.current().title));
        TabbedPane tabs = new TabbedPane();
        for (int i = 1; i <= 14; i++) {
            tabs.addTab("Tab " + i, body("Contents of tab " + i));
        }
        col.add(new SizedBox(SizedBox.UNSET, 120, tabs));
        // Select a far tab after layout: it scrolls into view (offset > 0), so both
        // chevrons become enabled (scrollable to either side).
        return new Built(scene(col), () -> tabs.setSelectedIndex(11));
    }

    private static Widget alignedTabs(String label, TabbedPane.TabAlignment alignment) {
        TabbedPane tabs = new TabbedPane().setAlignment(alignment);
        tabs.addTab("One", body(label + ": tab One"));
        tabs.addTab("Two", body("tab Two"));
        tabs.addTab("Three", body("tab Three"));
        return new SizedBox(SizedBox.UNSET, 96, tabs);
    }

    private static Widget body(String text) {
        return new Padding(Insets.all(12), new Label(text).setMuted(true));
    }

    private static Column column() {
        Column col = new Column();
        col.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        return col;
    }

    private static Scene scene(Widget content) {
        Scene scene = new Scene(new Padding(Insets.all(22), content));
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
