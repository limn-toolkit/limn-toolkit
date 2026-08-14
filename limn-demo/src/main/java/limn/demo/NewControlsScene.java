package limn.demo;

import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Label;
import limn.components.RadioButton;
import limn.components.ScrollView;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.Separator;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.ToolBar;
import limn.graphics.Color;
import limn.graphics.SvgIcon;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.util.List;

/**
 * Showcase for the FlatLaf-inspired short-term batch: bold/italic type,
 * RadioButton groups, Separators, a ToolBar + SegmentedControl, a search field,
 * input validation states, tooltips and themeable SVG icons.
 */
final class NewControlsScene {

    private NewControlsScene() {
    }

    private static String icon(String name) {
        return "/limn/components/icons/" + name + ".svg";
    }

    /** Standalone {@code --scene newcontrols}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** Reusable subtree (kitchen-sink tab). */
    static Widget content() {
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);

        // --- Typography: bold + italic ------------------------------------
        // Role + strong, not setFont(theme.title): setStrong derives bold from whatever
        // the role resolves to, so the pair still tracks the step; setFont would pin
        // MEDIUM's 20 pt and only the weight would survive.
        col.add(new Label("New components").setRole(Label.Role.TITLE).setStrong(true));
        Row typo = new Row();
        typo.gap(18).crossAlignment(Flex.CrossAlignment.CENTER);
        typo.add(new Label("Regular"));
        typo.add(new Label("Bold").setStrong(true));
        typo.add(new Label("Italic").setItalic(true));
        typo.add(new Label("Bold Italic").setStrong(true).setItalic(true));
        col.add(typo);

        col.add(Separator.horizontal());

        // --- RadioButton + ButtonGroup ------------------------------------
        col.add(heading("RadioButton + ButtonGroup"));
        Label choice = new Label("Medium").setMuted(true);
        ButtonGroup group = new ButtonGroup();
        Row radios = new Row();
        radios.gap(22).crossAlignment(Flex.CrossAlignment.CENTER);
        List<String> sizes = List.of("Small", "Medium", "Large");
        for (String name : sizes) {
            RadioButton radio = new RadioButton(name);
            radio.setTooltip("Size: " + name);
            radio.onChange(selected -> {
                if (selected) {
                    choice.setText(name);
                }
            });
            group.add(radio);
            radios.add(radio);
        }
        group.setSelectedIndex(1); // pre-select "Medium"
        radios.add(Expanded.of(choice, 1));
        col.add(radios);

        col.add(Separator.horizontal());

        // --- ToolBar + SegmentedControl -----------------------------------
        col.add(heading("ToolBar + SegmentedControl"));
        ToolBar bar = new ToolBar();
        bar.addItem(iconButton("search", "Search"));
        bar.addItem(iconButton("settings", "Settings"));
        bar.addSeparator();
        bar.addItem(iconButton("info", "Info"));

        List<String> periods = List.of("Day", "Week", "Month", "Year");
        Label period = new Label("Week").setMuted(true);
        SegmentedControl seg = new SegmentedControl(periods).setSelectedIndex(1);
        seg.setTooltip("Report period");
        seg.onSelect(i -> period.setText(periods.get(i)));
        Row toolRow = new Row();
        toolRow.gap(16).crossAlignment(Flex.CrossAlignment.CENTER);
        toolRow.add(bar);
        toolRow.add(seg);
        toolRow.add(Expanded.of(period, 1));
        col.add(toolRow);

        col.add(Separator.horizontal());

        // --- Search field --------------------------------------------------
        col.add(heading("Search field (icon + attached button)"));
        Label searchStatus = new Label("type to filter; Enter to search").setMuted(true);
        SearchField search = new SearchField();
        search.setTooltip("Instant search");
        search.onChange(q -> searchStatus.setText(q.isEmpty()
                ? "type to filter; Enter to search" : "filtering: " + q));
        search.onSubmit(q -> searchStatus.setText("search: " + q));
        Row searchRow = new Row();
        searchRow.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
        searchRow.add(Expanded.of(search, 3));
        searchRow.add(Expanded.of(searchStatus, 4));
        col.add(searchRow);

        col.add(Separator.horizontal());

        // --- Validation states --------------------------------------------
        col.add(heading("Input validation"));
        Row states = new Row();
        states.gap(16).crossAlignment(Flex.CrossAlignment.START);
        states.add(Expanded.of(validated("E-mail", "user@", TextField.Validation.ERROR,
                "close", theme.danger, "Incomplete address."), 1));
        states.add(Expanded.of(validated("Username", "adm", TextField.Validation.WARNING,
                "warning", theme.warning, "Too short (min. 4)."), 1));
        states.add(Expanded.of(validated("Coupon", "LIMN2026", TextField.Validation.SUCCESS,
                "check", theme.success, "Valid coupon!"), 1));
        states.add(Expanded.of(validated("Phone", "+55", TextField.Validation.INFO,
                "info", theme.info, "Optional: include the area code."), 1));
        col.add(states);

        return new ScrollView(col);
    }

    private static Label heading(String text) {
        return new Label(text).setFont(Theme.current().body).setStrong(true);
    }

    private static Button iconButton(String iconName, String label) {
        Button button = new Button(label).setSecondary(true);
        button.setIcon(SvgIcon.fromResource(icon(iconName)));
        button.setTooltip(label);
        return button;
    }

    private static Widget validated(String label, String value, TextField.Validation state,
                                    String iconName, Color color, String message) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(label).setMuted(true));
        // Field + message grouped tightly so the message hugs the field; the message
        // is a touch smaller (its icon follows the font size).
        Column fieldGroup = new Column();
        fieldGroup.gap(3).crossAlignment(Flex.CrossAlignment.STRETCH);
        TextField field = new TextField().setText(value);
        field.setValidation(state);
        fieldGroup.add(field);
        fieldGroup.add(new Label(message)
                .setColor(color)
                .setFont(Theme.current().body.withSize(12))
                .setIcon(SvgIcon.fromResource(icon(iconName))));
        column.add(fieldGroup);
        return column;
    }
}
