package limn.components;

import limn.components.chart.ChartPalette;
import limn.graphics.Color;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 081 §0.3: what identifies each component and each of its states, the colour next to it, and
 * the contrast between the two in every shipped palette, against WCAG 2.1 1.4.11's 3:1.
 *
 * <p>Each row names the colour as the component's paint writes it, with the paint's file and line,
 * read on 2026-10-23. A translucent colour is laid over what it sits on before it is measured. A
 * control drawn inside another surface may sit on the canvas, a card or a popover, so "the parent"
 * is the worst of the three. Several rows of one kind for one component and state are alternatives (a
 * field is identified by its border or by its fill): the state holds if one of them clears the bar.
 *
 * <p>This test asserts only that every widget class the toolkit ships has rows, or a stated reason
 * to have none, so that a component added later is audited when it lands. The ratios are written to
 * {@code build/reports/non-text-contrast.md}; which of them must hold, and how, is the owner's
 * decision (ADR 081 §6), and the bar is asserted once it is taken.
 */
class NonTextContrastAuditTest {

    /** What 1.4.11 asks of a row. */
    enum Kind {
        /** Identifies the component's extent: required. */
        BOUNDARY,
        /** Identifies a state (focused, checked, selected, current, open, invalid): required. */
        STATE,
        /** A graphical object needed to understand the content: required. */
        GRAPHIC,
        /** Identified by its text alone, whose contrast is a text criterion: listed, not measured. */
        TEXT_ONLY,
        /** Groups or decorates rather than identifies (a container's edge, a divider): listed. */
        DECORATION
    }

    record Row(String component, String state, String element, Kind kind,
               Function<Theme, Color> ink, Function<Theme, Color> beside, String source) {
    }

    /** "The parent": the worst of the canvas, a card and a popover. */
    private static final Function<Theme, Color> PARENT = null;

    private static final List<Row> ROWS = new ArrayList<>();

    private static void row(String component, String state, String element, Kind kind,
                            Function<Theme, Color> ink, Function<Theme, Color> beside, String source) {
        ROWS.add(new Row(component, state, element, kind, ink, beside, source));
    }

    private static void listed(String component, String state, String element, Kind kind, String source) {
        ROWS.add(new Row(component, state, element, kind, null, null, source));
    }

    /** A colour with alpha, laid over what it sits on. */
    private static Color over(Color top, Color under) {
        return top.a() >= 1f ? top : under.lerp(top.withAlpha(1f), top.a());
    }

    /** The rows a bordered field box shares: TextField, TextArea, Spinner, ComboBox, the date fields. */
    private static void field(String component, String source) {
        row(component, "rest", "border", Kind.BOUNDARY, Theme::controlBorder, PARENT, source);
        row(component, "rest", "fill", Kind.BOUNDARY, Theme::surface, PARENT, source);
        row(component, "focused", "border (outline toward focusRing, 1 to 2 pt)", Kind.STATE,
                Theme::focusRing, PARENT, source);
        row(component, "invalid", "border (danger)", Kind.STATE, Theme::danger, PARENT, "internal/FieldControls.java:113");
        row(component, "warning", "border (warning)", Kind.STATE, Theme::warning, PARENT, "internal/FieldControls.java:113");
        row(component, "success", "border (success)", Kind.STATE, Theme::success, PARENT, "internal/FieldControls.java:113");
    }

    static {
        // ---------------------------------------------------------------- buttons and toggles
        row("Button", "PRIMARY", "fill", Kind.BOUNDARY, Theme::primary, PARENT, "Button.java:511");
        row("Button", "PRIMARY destructive", "fill", Kind.BOUNDARY, Theme::danger, PARENT, "Button.java:509");
        row("Button", "SECONDARY", "border", Kind.BOUNDARY, Theme::controlBorder, PARENT, "Button.java:520");
        row("Button", "SECONDARY", "fill", Kind.BOUNDARY, Theme::surface, PARENT, "Button.java:519");
        listed("Button", "TERTIARY", "no boundary at rest", Kind.TEXT_ONLY, "Button.java:528");
        row("Button", "focused", "ring outside a 1 pt gap", Kind.STATE, Theme::focusRing, PARENT, "Button.java:544");

        row("Checkbox", "unchecked", "box border", Kind.BOUNDARY, Theme::controlBorder, PARENT, "Checkbox.java:388");
        row("Checkbox", "checked", "box fill", Kind.STATE, Theme::primary, PARENT, "Checkbox.java:392");
        row("Checkbox", "checked", "check mark", Kind.STATE, Theme::onPrimary, Theme::primary, "Checkbox.java:399");
        row("Checkbox", "focused", "ring", Kind.STATE, Theme::focusRing, PARENT, "Checkbox.java:345");
        row("Checkbox", "switch off", "track border", Kind.BOUNDARY, Theme::controlBorder, PARENT, "Checkbox.java:429");
        row("Checkbox", "switch off", "thumb", Kind.STATE, Theme::textMuted, Theme::surfaceRaised, "Checkbox.java:443");
        row("Checkbox", "switch on", "track", Kind.STATE, Theme::primary, PARENT, "Checkbox.java:425");
        row("Checkbox", "switch on", "thumb", Kind.STATE, Theme::onPrimary, Theme::primary, "Checkbox.java:443");

        row("RadioButton", "unselected", "ring", Kind.BOUNDARY, Theme::controlBorder, PARENT, "RadioButton.java:371");
        row("RadioButton", "selected", "ring and dot", Kind.STATE, Theme::primary, PARENT, "RadioButton.java:370");
        row("RadioButton", "focused", "ring", Kind.STATE, Theme::focusRing, PARENT, "RadioButton.java:415");

        row("SegmentedControl", "rest", "track border", Kind.BOUNDARY, Theme::controlBorder, PARENT, "SegmentedControl.java:512");
        row("SegmentedControl", "selected", "pill", Kind.STATE, Theme::primary, Theme::surface, "SegmentedControl.java:517");
        row("SegmentedControl", "focused", "ring", Kind.STATE, Theme::focusRing, PARENT, "SegmentedControl.java:575");

        for (String slider : List.of("Slider", "RangeSlider")) {
            String src = slider + ".java";
            row(slider, "rest", "rail, an empty pill's edge", Kind.BOUNDARY, Theme::controlBorder, PARENT, src + (slider.equals("Slider") ? ":339" : ":380"));
            row(slider, "value", "filled span", Kind.STATE, Theme::primary, PARENT, src + (slider.equals("Slider") ? ":340" : ":386"));
            row(slider, "value", "knob", Kind.STATE, Theme::primary, PARENT, src + (slider.equals("Slider") ? ":363" : ":539"));
            row(slider, "focused", "ring round the knob", Kind.STATE, Theme::focusRing, PARENT, src + (slider.equals("Slider") ? ":360" : ":536"));
        }

        row("ProgressBar", "any", "track, an empty pill's edge", Kind.GRAPHIC, Theme::controlBorder, PARENT, "ProgressBar.java:255");
        row("ProgressBar", "any", "fill", Kind.GRAPHIC, Theme::primary, PARENT, "ProgressBar.java:256");

        row("ScrollBar", "rest", "thumb", Kind.BOUNDARY, Theme::controlBorder, PARENT, "ScrollBar.java:475");

        listed("Expander", "rest", "header: no boundary", Kind.TEXT_ONLY, "Expander.java:520");
        row("Expander", "open or closed", "arrow", Kind.STATE, Theme::textMuted, PARENT, "Expander.java:527");
        row("Expander", "focused", "ring inside the header", Kind.STATE, Theme::focusRing, PARENT, "Expander.java:569");

        row("ToolBar", "rest", "container border", Kind.DECORATION, Theme::outline, PARENT, "ToolBar.java:177");

        row("SplitPane", "rest", "divider line", Kind.BOUNDARY, Theme::controlBorder, PARENT, "SplitPane.java:541");
        row("SplitPane", "focused", "divider in focusRing, 2 pt", Kind.STATE, Theme::focusRing, PARENT, "SplitPane.java:542");

        row("Card", "clickable (ELEVATED, OUTLINED, FILLED)", "edge", Kind.BOUNDARY, Theme::controlBorder, Theme::background, "Card.java:1290");
        row("Card", "focused", "ring", Kind.STATE, Theme::focusRing, Theme::background, "Card.java:1551");

        // ---------------------------------------------------------------- fields and pickers
        field("TextField", "TextField.java:1327");
        field("PasswordField", "TextField.java:1327 (inherited)");
        field("SearchField", "TextField.java:1327 (inherited)");
        row("SearchField", "rest", "magnifier icon", Kind.GRAPHIC, Theme::textMuted, Theme::surface, "TextField.java:1336");
        field("TextArea", "TextArea.java:1713");
        field("Spinner", "Spinner.java:1215");
        row("Spinner", "rest", "step arrows", Kind.GRAPHIC, Theme::textMuted, Theme::surface, "Spinner.java:1348");
        row("Spinner", "TIME, focused", "active field", Kind.STATE, t -> t.primary().withAlpha(0.20f), Theme::surface, "Spinner.java:1255");
        field("ComboBox", "ComboBox.java:1984");
        row("ComboBox", "rest", "chevron", Kind.GRAPHIC, Theme::textMuted, Theme::surface, "ComboBox.java:2066");
        row("ComboBox", "list, keyboard row", "row fill (primary at 0.28)", Kind.STATE, t -> t.primary().withAlpha(0.28f), Theme::surfaceRaised, "ComboBox.java:2997");
        row("ComboBox", "list, selected", "dot or check", Kind.STATE, Theme::primary, Theme::surfaceRaised, "ComboBox.java:3014");
        listed("ComboBox", "multi, chip", "pill: identified by its word, as a badge", Kind.TEXT_ONLY, "ComboBox.java:2673");
        row("ComboBox", "open", "popup edge", Kind.DECORATION, Theme::outline, PARENT, "ComboBox.java:2966");
        field("DateField", "date/DateField.java:1974");
        row("DateField", "focused", "active segment (primary at 0.20)", Kind.STATE, t -> t.primary().withAlpha(0.20f), Theme::surface, "date/DateField.java:2001");
        field("DatePicker", "date/DatePicker.java:1224");
        row("DatePicker", "rest", "calendar glyph", Kind.GRAPHIC, Theme::textMuted, Theme::surface, "date/DatePicker.java:1352");
        row("DatePicker", "button focused", "inner ring", Kind.STATE, Theme::focusRing, Theme::surface, "date/DatePicker.java:1380");
        row("CalendarView", "selected day", "cell", Kind.STATE, Theme::primary, PARENT, "date/CalendarView.java:1846");
        row("CalendarView", "range", "band (primary at 0.18)", Kind.STATE, t -> t.primary().withAlpha(0.18f), PARENT, "date/CalendarView.java:1843");
        row("CalendarView", "today", "ring", Kind.STATE, Theme::primary, PARENT, "date/CalendarView.java:1852");
        row("CalendarView", "focused", "cursor ring", Kind.STATE, Theme::focusRing, PARENT, "date/CalendarView.java:1877");
        row("CalendarView", "rest", "paging chevrons", Kind.GRAPHIC, Theme::textMuted, PARENT, "date/CalendarView.java:2070");
        row("ColorPicker", "rest", "rail outline", Kind.BOUNDARY, Theme::controlBorder, PARENT, "ColorPicker.java:1248");
        row("ColorPicker", "focused", "rail outline in focusRing", Kind.STATE, Theme::focusRing, PARENT, "ColorPicker.java:1248");
        row("ColorPickerButton", "rest", "border", Kind.BOUNDARY, Theme::controlBorder, PARENT, "ColorPickerButton.java:459");
        row("ColorPickerButton", "rest", "fill", Kind.BOUNDARY, Theme::surface, PARENT, "ColorPickerButton.java:456");
        row("ColorPickerButton", "focused", "ring", Kind.STATE, Theme::focusRing, PARENT, "ColorPickerButton.java:485");
        row("MediaControls", "rest", "button glyph on the bar", Kind.GRAPHIC, Theme::text, t -> t.surface().withAlpha(0.92f), "MediaControls.java:672");
        row("MediaControls", "focused", "button ring", Kind.STATE, Theme::focusRing, t -> t.surface().withAlpha(0.92f), "MediaControls.java:667");

        // ---------------------------------------------------------------- lists, menus, tabs
        listed("ListView", "rest", "no boundary of its own", Kind.TEXT_ONLY, "ListView.java:2030");
        row("ListView", "selected", "row outline", Kind.STATE, Theme::outline, PARENT, "ListView.java:2073");
        row("ListView", "focused row", "ring", Kind.STATE, Theme::focusRing, PARENT, "ListView.java:2095");
        listed("Table", "rest", "no boundary of its own", Kind.TEXT_ONLY, "table/Table.java:3580");
        row("Table", "selected", "row fill (primary at 0.18)", Kind.STATE, t -> t.primary().withAlpha(0.18f), PARENT, "table/Table.java:3571");
        row("Table", "focused cell", "ring", Kind.STATE, Theme::focusRing, t -> t.primary().withAlpha(0.18f), "table/Table.java:3662");
        row("Table", "sorted", "sort chevron", Kind.STATE, Theme::textMuted, Theme::surfaceRaised, "table/Table.java:3760");
        row("Table", "header", "column dividers and bottom rule", Kind.DECORATION, Theme::outline, Theme::surfaceRaised, "table/Table.java:3767");
        row("Tree", "selected", "row fill (primary at 0.18)", Kind.STATE, t -> t.primary().withAlpha(0.18f), PARENT, "tree/Tree.java:3827");
        row("Tree", "focused row", "ring", Kind.STATE, Theme::focusRing, t -> t.primary().withAlpha(0.18f), "tree/Tree.java:3939");
        row("Tree", "expanded or collapsed", "disclosure arrow", Kind.STATE, Theme::textMuted, PARENT, "tree/Tree.java:3973");
        row("TabbedPane", "current tab", "indicator", Kind.STATE, Theme::primary, PARENT, "TabbedPane.java:2135");
        row("TabbedPane", "focused tab", "top rule", Kind.STATE, Theme::focusRing, PARENT, "TabbedPane.java:2664");
        row("TabbedPane", "closable", "close cross", Kind.GRAPHIC, Theme::textMuted, t -> t.textMuted().withAlpha(0.12f), "TabbedPane.java:2657");
        row("TabbedPane", "rest", "rule under the strip", Kind.DECORATION, Theme::outline, PARENT, "TabbedPane.java:1816");
        row("MenuBar", "open menu", "chip (primary at 0.20)", Kind.STATE, t -> t.primary().withAlpha(0.20f), PARENT, "MenuBar.java:669");
        row("MenuBar", "keyboard item", "chip (surfaceRaised)", Kind.STATE, Theme::surfaceRaised, PARENT, "MenuBar.java:669");
        row("PopupMenu", "keyboard item", "highlight (primary at 0.28)", Kind.STATE, t -> t.primary().withAlpha(0.28f), Theme::surfaceRaised, "PopupMenu.java:1454");
        row("PopupMenu", "checked", "tick", Kind.STATE, Theme::primary, Theme::surfaceRaised, "PopupMenu.java:1469");
        row("PopupMenu", "open", "card edge", Kind.DECORATION, Theme::outline, PARENT, "PopupMenu.java:1412");
        row("Popover", "open", "card edge", Kind.DECORATION, Theme::outline, PARENT, "Popover.java:537");
        row("Separator", "any", "line", Kind.DECORATION, Theme::outline, PARENT, "Separator.java:114");

        // ---------------------------------------------------------------- graphics
        for (int slot = 0; slot < 8; slot++) {
            int s = slot;
            row("Chart", "series " + slot, "line, bar or slice (default palette)", Kind.GRAPHIC,
                    t -> ChartPalette.defaultFor(t).color(s), PARENT, "chart/ChartPalette.java:39");
        }
        row("Chart", "any", "grid lines (outline at 0.45)", Kind.DECORATION, t -> t.outline().withAlpha(0.45f), PARENT, "chart/CartesianChart.java:633");
        row("Chart", "any", "axis line", Kind.GRAPHIC, Theme::controlBorder, PARENT, "chart/CartesianChart.java:666");
        for (Tone tone : Tone.values()) {
            row("Badge", "dot, " + tone.name().toLowerCase(Locale.ROOT), "dot", Kind.GRAPHIC, tone::colorIn, PARENT, "Badge.java:375");
        }
        listed("Badge", "text or count", "pill: identified by its word", Kind.TEXT_ONLY, "Badge.java:382");
        listed("Avatar", "any", "initials or picture", Kind.TEXT_ONLY, "Avatar.java:360");
        row("Field", "invalid", "message glyph (danger)", Kind.GRAPHIC, Theme::danger, PARENT, "Field.java:626");
        row("Field", "warning", "message glyph (warning)", Kind.GRAPHIC, Theme::warning, PARENT, "Field.java:626");
    }

    /** Widgets with no row, and why: they hold or show content, and identify no control of their own. */
    private static final Map<String, String> NOT_A_CONTROL = Map.ofEntries(
            Map.entry("Label", "text; its contrast is a text criterion"),
            Map.entry("ImageView", "content, which 1.4.11 leaves as the author drew it"),
            Map.entry("VideoView", "content"),
            Map.entry("Viewport3D", "content"),
            Map.entry("DemoCube", "content"),
            Map.entry("Surface", "a container; a control on it is measured against it"),
            Map.entry("BackdropPanel", "a container"),
            Map.entry("ScrollView", "paints nothing but its ScrollBars, which have rows"),
            Map.entry("AvatarGroup", "its avatars, which have rows"),
            Map.entry("ExpanderGroup", "its expanders, which have rows"),
            Map.entry("ButtonGroup", "paints nothing"),
            Map.entry("Dialog", "a container whose buttons have rows"),
            Map.entry("TokenBox", "layout"),
            Map.entry("TokenColumn", "layout"),
            Map.entry("TokenPadding", "layout"),
            Map.entry("TokenRow", "layout"),
            Map.entry("BarChart", "rows under Chart"),
            Map.entry("LineChart", "rows under Chart"),
            Map.entry("DonutChart", "rows under Chart"),
            Map.entry("CartesianChart", "rows under Chart"));

    @Test
    void everyWidgetTheToolkitShipsIsAuditedOrSaysWhyNot() throws Exception {
        Path root = limn.testfixtures.RepositoryRoot.find().resolve("limn-toolkit/src/main/java/limn/components");
        Set<String> audited = new TreeSet<>(NOT_A_CONTROL.keySet());
        ROWS.forEach(r -> audited.add(r.component()));
        List<String> missing = new ArrayList<>();
        int widgets = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("internal/") || relative.endsWith("package-info.java")) {
                    continue;
                }
                String name = relative.substring(0, relative.length() - 5).replace('/', '.');
                Class<?> type = Class.forName("limn.components." + name);
                if (Widget.class.isAssignableFrom(type) && java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
                    widgets++;
                    if (!audited.contains(type.getSimpleName())) {
                        missing.add(type.getSimpleName());
                    }
                }
            }
        }
        assertTrue(widgets > 40, "the scan found the toolkit's widgets: " + widgets);
        assertEquals(List.of(), missing, "widgets with no row in ADR 081's audit and no reason to have none");
    }

    @Test
    void theRatiosAreWrittenForEveryPalette() throws IOException {
        List<Theme> palettes = Theme.builtins();
        StringBuilder out = new StringBuilder("# Non-text contrast, ADR 081 §0.3\n\n");
        out.append("Worst ratio over the palettes; the parent is the worst of canvas, card and popover.\n\n");
        out.append("| Component | State | Element | Kind | Worst | In | Below 3:1 in | Source |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Map<String, Boolean> heldByState = new LinkedHashMap<>();
        int measured = 0;
        for (Row row : ROWS) {
            if (row.ink() == null) {
                out.append(String.format("| %s | %s | %s | %s | — | — | — | %s |%n",
                        row.component(), row.state(), row.element(), row.kind(), row.source()));
                continue;
            }
            double worst = Double.MAX_VALUE;
            String worstIn = "";
            List<String> below = new ArrayList<>();
            for (Theme theme : palettes) {
                double ratio = ratio(row, theme);
                if (ratio < worst) {
                    worst = ratio;
                    worstIn = theme.name();
                }
                if (ratio < 3.0) {
                    below.add(theme.name());
                }
            }
            measured++;
            assertTrue(worst >= 1.0 && worst <= 21.0, row + ": a ratio out of range");
            out.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %.2f | %s | %s | %s |%n",
                    row.component(), row.state(), row.element(), row.kind(), worst, worstIn,
                    below.isEmpty() ? "none" : below.size() == palettes.size() ? "all" : String.join(", ", below),
                    row.source()));
            if (row.kind() == Kind.BOUNDARY || row.kind() == Kind.STATE || row.kind() == Kind.GRAPHIC) {
                // Alternatives only within one kind: an icon beside a field does not identify the box.
                heldByState.merge(row.component() + " / " + row.state() + " / " + row.kind(), below.isEmpty(),
                        Boolean::logicalOr);
            }
        }
        long held = heldByState.values().stream().filter(b -> b).count();
        out.append(String.format("%n%d of %d required component states clear 3:1 in every palette.%n",
                held, heldByState.size()));
        out.append("\nFailing in at least one palette:\n\n");
        heldByState.forEach((state, ok) -> {
            if (!ok) {
                out.append("- ").append(state).append('\n');
            }
        });
        Path report = Path.of("build", "reports", "non-text-contrast.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out, StandardCharsets.UTF_8);
        assertTrue(measured > 50, "the audit measured its rows");
    }

    /** The row's ratio in {@code theme}, against the worst parent when the row names none. */
    private static double ratio(Row row, Theme theme) {
        List<Color> grounds = row.beside() == PARENT
                ? List.of(theme.background(), theme.surface(), theme.surfaceRaised())
                : List.of(row.beside().apply(theme));
        double worst = Double.MAX_VALUE;
        for (Color ground : grounds) {
            Color opaqueGround = over(ground, theme.background());
            worst = Math.min(worst, Color.contrastRatio(over(row.ink().apply(theme), opaqueGround), opaqueGround));
        }
        return worst;
    }
}
