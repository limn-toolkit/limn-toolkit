package limn.demo;

import limn.components.Label;
import limn.components.Surface;
import limn.components.Theme;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.i18n.I18nString;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The parts every page of the Showcase is made of, so that the pages look like one application:
 * one style of section heading, one of hint, one card, one form row, one toolbar row, and the
 * window's status line for what a control did.
 *
 * <p>A page shows rather than explains. A hint is for what cannot be seen, a key or a gesture,
 * and is one short line; a control that already shows its value is not echoed beside it.
 */
final class Page {

    /** Between the parts of a section. */
    static final float GAP = 12;
    /** Before a section's heading, so it belongs to what follows rather than to what is above. */
    static final float SECTION_GAP = 28;

    private static Consumer<String> status; // null until a frame sets it

    /**
     * The labels drawn in one of the palette's colours, and which one: a label holds a colour and
     * not a token, so each is coloured again when the palette changes. Weak, so a page that is
     * gone takes its labels with it.
     */
    private static final Map<Label, Function<Theme, Color>> TINTED = new WeakHashMap<>();
    private static boolean tintFollowsPalette;

    private Page() {
    }

    /**
     * Where {@link #report} writes: the window's status line, set by the frame once. Pages built
     * with no frame (a capture, a test) report nowhere.
     */
    static void reportTo(Consumer<String> line) {
        status = line;
    }

    /** Says in the window's status line what a control did. */
    static void report(String text) {
        if (status != null) {
            status.accept(text);
        }
    }

    /** A page's column: its sections from the top, stretched to the width. */
    static Column column() {
        Column page = new Column();
        page.gap(GAP).crossAlignment(Flex.CrossAlignment.STRETCH);
        return page;
    }

    /**
     * A section heading, smaller than the page's title and with room above it. The first
     * section of a page takes {@link #firstSection} instead, which has none.
     */
    static Widget<?> section(I18nString text) {
        return new Padding(new Insets(SECTION_GAP - GAP, 0, 0, 0), firstSection(text));
    }

    /** The heading of a page's first section, flush with the top. */
    static Label firstSection(I18nString text) {
        return new Label(text).setStrong(true);
    }

    /** One short line for what cannot be seen: a key, a gesture. */
    static Label hint(I18nString text) {
        return new Label(text).setMuted(true).setWrap(true);
    }

    /** A card: content set a little above the page, with room inside. */
    static Surface card(Widget<?> content) {
        return new Surface(new Padding(Insets.all(16), content));
    }

    /** A caption above a control, the caption naming it to a reader. */
    static Widget<?> field(I18nString caption, Widget<?> control) {
        return Labelled.above(caption, control);
    }

    /** A caption above a control placed inside a wrapper, the caption naming the control. */
    static Widget<?> field(I18nString caption, Widget<?> control, Widget<?> placed) {
        return Labelled.above(caption, control, placed);
    }

    /** A row of controls, centred on one line, {@code gap} apart. */
    static Row row(float gap, Widget<?>... children) {
        Row row = new Row();
        row.gap(gap).crossAlignment(Flex.CrossAlignment.CENTER);
        for (Widget<?> child : children) {
            row.add(child);
        }
        return row;
    }

    /**
     * Cells of one width side by side, their tops on one line: the columns of a form, each cell
     * a {@link #field} or a column of them. A cell may be an empty {@link SizedBox} to leave its
     * place free.
     */
    static Row columns(Widget<?>... cells) {
        Row row = new Row();
        row.gap(24).crossAlignment(Flex.CrossAlignment.START);
        for (Widget<?> cell : cells) {
            row.add(Expanded.of(cell, 1));
        }
        return row;
    }

    /**
     * The line under a field that says what is wrong with it, or right: in the state's ink, a
     * step smaller than the field's text, and declared the field's description so a reader
     * hears it after the field's name and state.
     *
     * @param text  what to say
     * @param ink   the state's colour: {@code Theme::danger}, {@code warning}, {@code success} or
     *              {@code info}
     * @param icon  the state's glyph, or {@code null}
     * @param field what the line describes
     */
    static Label message(I18nString text, Function<Theme, Color> ink, Icon icon, Widget<?> field) {
        Label line = tint(new Label(text).setRole(Label.Role.LABEL).setWrap(true), ink);
        if (icon != null) {
            line.setIcon(icon);
        }
        return line.setDescriptionFor(field);
    }

    /**
     * Draws {@code label} in one of the palette's colours, {@code Theme::danger} say, now and
     * after every change of palette. A colour read once, when the page is built, is the palette
     * of that moment: the one before the frame applied the system's appearance, measured
     * rgb(247,118,142) for a light page's danger of rgb(198,40,40).
     *
     * @param label the label
     * @param ink   which of the palette's colours
     * @return the label
     */
    static Label tint(Label label, Function<Theme, Color> ink) {
        if (!tintFollowsPalette) {
            tintFollowsPalette = true;
            Theme.observeChanges(() -> TINTED.forEach((each, tone) -> each.setColor(tone.apply(Theme.current()))));
        }
        TINTED.put(label, ink);
        return label.setColor(ink.apply(Theme.current()));
    }

    /** A control with its {@link #message} close under it: what a {@link #field} places. */
    static Column withMessage(Widget<?> control, Widget<?> message) {
        Column column = new Column();
        column.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(control);
        column.add(message);
        return column;
    }

    /**
     * {@code content} at most {@code width} wide, at the start of the line: a form or a group of
     * sections that would otherwise run the whole window wide, its ends a screen apart.
     */
    static Widget<?> narrow(float width, Widget<?> content) {
        Row row = new Row();
        row.crossAlignment(Flex.CrossAlignment.START);
        row.add(new SizedBox(width, SizedBox.UNSET, content));
        row.add(Expanded.spacer(1));
        return row;
    }
}
