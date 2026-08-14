package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.RadioButton;
import limn.components.SegmentedControl;
import limn.components.SizeTokens;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.Strokes;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pointer-target audit: the 24&nbsp;pt WCAG 2.2 SC 2.5.8 (AA) floor drawn over every
 * interactive element, with the ones that fall short marked as failures.
 *
 * <p><b>Why this is not the scene ADR 002 §9 phase 4 described.</b> That text asked for target
 * circles over "both the paint box and the hit box". Decision 4 cancelled the hit-region
 * mechanism: there is no {@code Widget.hitOutset()} and no second hit pass, so a widget's hit
 * box <em>is</em> its paint box and drawing two circles would be drawing the same circle twice.
 * What survives of the floor is a {@code Math.max} clamp at exactly three sites (see
 * {@link Strokes#MIN_HIT_TARGET}). The interesting question is therefore no longer "do the two
 * boxes agree" but "does the painted box actually clear 24&nbsp;pt", which nothing in the type
 * system answers.
 *
 * <p><b>It is built to fail.</b> Checkbox and RadioButton measure to their indicator (18&nbsp;pt
 * at MEDIUM, 22 at LARGE, and only at XLARGE does the ramp reach 24), and the switch variant's
 * track is 22&nbsp;pt up to and including MEDIUM. Those rows are marked red here. That gap
 * predates the control-size axis (it is today's {@code Checkbox:32} literal, carried through the
 * table verbatim) and the axis neither created nor closed it; a scene that drew only green
 * circles would be decoration, and would quietly assert conformance the toolkit does not have.
 *
 * <p>The ramp rows show the other half of decision 4: because the height ramp starts at 24, a
 * Button / ComboBox / TextField clears the floor <em>in paint</em> at every step, XSMALL
 * included, so pointer dispatch never had to grow a widget beyond its own ink.
 *
 * <p>The overlay is a wrapper widget that paints after its child subtree has been laid out. No
 * component knows this scene exists; the audit reads {@code width()}/{@code height()} of the
 * laid-out tree, which is exactly what a user's pointer meets.
 */
final class ControlSizeAuditScene {

    private ControlSizeAuditScene() {
    }

    static Scene create() {
        Column page = new Column();
        page.gap(18).crossAlignment(Flex.CrossAlignment.STRETCH);

        // Role, not setFont(theme.title): the heading sits outside every stepRow scope, so it
        // follows the process default a reviewer of this scene is most likely to throw.
        page.add(new Label("Control size audit").setRole(Label.Role.TITLE));
        page.add(new Label("Every interactive element carries the 24pt target circle."
                + " A red, slashed ring is an element whose painted box cannot contain it.")
                .setMuted(true).setWrap(true));

        for (ControlSize step : ControlSize.values()) {
            page.add(rampRow(step));
        }

        page.add(new Label("Other targets, all at MEDIUM").setMuted(true));
        page.add(fieldRow());
        page.add(trackRow());

        Scene scene = new Scene(new Padding(Insets.all(20), new TargetOverlay(page)));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /**
     * One row per step, all of it at {@code step}. Button and ComboBox pass at every step;
     * Checkbox and RadioButton fail until XLARGE: the ramp of the gap, in one picture.
     */
    private static Widget rampRow(ControlSize step) {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Label(step.name()).setMuted(true));
        row.add(new Button("Save"));
        row.add(new ComboBox(List.of("One", "Two", "Three")));
        row.add(new Checkbox(Checkbox.Variant.BOX, "Box"));
        row.add(new RadioButton("Radio"));
        // The whole row is one scope: every descendant that declares nothing inherits this.
        row.setControlSize(step);
        return row;
    }

    /** Text-cluster and composite targets at the default step. The switch is 22pt here. */
    private static Widget fieldRow() {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new TextField().setPlaceholder("Text"));
        row.add(new Spinner(0, 100, 1));
        row.add(new Checkbox(Checkbox.Variant.SWITCH, "Switch"));
        return row;
    }

    /**
     * Slider and SegmentedControl: the two composites the audit can only judge whole. Their
     * internal targets (the knob, and each segment) are painted regions, not widgets, so the
     * overlay cannot see them; the segment's own floor is enforced inside
     * {@code SegmentedControl}'s measure, the second of the three {@code MIN_HIT_TARGET} sites.
     */
    private static Widget trackRow() {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Slider(0, 100).setValue(40));
        row.add(new SegmentedControl(List.of("Day", "Week", "Month")));
        return row;
    }

    // =====================================================================
    //  The overlay
    // =====================================================================

    /**
     * Wraps the audited page, then draws the floor over it once layout has run.
     *
     * <p>Painting happens in {@code onPaintOverlay} (the pass that runs <em>after</em>
     * {@code paintChildren}), because an annotation that landed under the widgets it annotates
     * would be invisible on every filled control.
     *
     * <p>Every stroke this class draws is a {@link Strokes} constant, and that is deliberate
     * rather than decorative: the marker must read identically on the XSMALL row and the XLARGE
     * one. A marker that scaled with the thing it measures would make the five rows
     * incomparable, which is the one job this scene has.
     */
    private static final class TargetOverlay extends Widget {

        /** Half the floor: the radius of the circle a conforming target must contain. */
        private static final float TARGET_RADIUS = Strokes.MIN_HIT_TARGET / 2;

        /** Where the slash crosses the ring, as a fraction of the radius. */
        private static final float SLASH_REACH = 0.72f;

        private final Widget content;

        TargetOverlay(Widget content) {
            this.content = content;
            add(content);
        }

        /**
         * The strip reserved at the bottom for the verdict: one gap, then two label lines.
         * One formula, called from measure, layout and paint; three copies would drift and the
         * footer would either overlap the last row or float above the window edge.
         *
         * <p>Resolved from the tokens handed in by the caller (never re-resolved here) and from
         * the overlay's <em>own</em> step, not the audited row's: this text is chrome of the
         * audit tool, so it must stay put while the rows underneath it vary.
         */
        private float footerHeight(SizeTokens t) {
            return t.spacingMedium() + 2 * textRuler().measure("0", t.label()).lineHeight();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(this); // once per pass
            float footer = footerHeight(t);
            Constraints inner = new Constraints(
                    constraints.minWidth(), constraints.maxWidth(),
                    0, constraints.hasBoundedHeight()
                            ? Math.max(0, constraints.maxHeight() - footer)
                            : constraints.maxHeight());
            Size size = content.measure(inner);
            return constraints.constrain(size.width(), size.height() + footer);
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.current().tokensFor(this); // once per pass
            content.layoutBox(0, 0, width(), Math.max(0, height() - footerHeight(t)));
        }

        /**
         * A 24 pt circle centred on an 18 pt box reaches 3 pt past it, and a target sitting on
         * this widget's edge would reach that far past <em>this</em> box. Declaring the full
         * radius is the honest upper bound; under partial rendering an undeclared overshoot
         * leaves stale ink behind.
         */
        @Override
        protected float paintOutset() {
            return TARGET_RADIUS;
        }

        @Override
        protected void onPaintOverlay(Canvas canvas) {
            Theme theme = Theme.current();
            SizeTokens t = theme.tokensFor(this); // once per pass
            Tally page = new Tally();
            // Each direct child of the audited column is one group. A group with no targets in
            // it (the headings) simply gets no verdict; no structural knowledge needed beyond
            // "the page is a column of rows", which is this scene's own shape.
            for (Widget group : content.children()) {
                Tally row = new Tally();
                markTargets(canvas, theme, group,
                        content.x() + group.x(), content.y() + group.y(), row);
                if (row.targets > 0) {
                    drawVerdict(canvas, theme, t,
                            content.y() + group.y() + group.height() / 2, row);
                }
                page.absorb(row);
            }
            drawFooter(canvas, theme, t, page);
        }

        /**
         * Walks the laid-out subtree, marking every target and tallying it.
         *
         * <p>"Target" is {@code isFocusable()}: the toolkit's only structural signal that a
         * widget takes input, so the audit cannot drift out of sync with a component list
         * maintained here. Two consequences worth knowing before reading the count. A
         * pointer-only target (ScrollBar) is not focusable and is not audited; decision 5
         * exempts it from the size axis anyway. And TabbedPane's roving focus leaves exactly one
         * tab header focusable, so a tab strip would report one target rather than n; no tab
         * strip is in this scene for that reason.
         */
        private void markTargets(Canvas canvas, Theme theme, Widget w, float x, float y,
                                 Tally tally) {
            if (!w.isVisible()) {
                return;
            }
            if (w.isFocusable() && w.isEnabled() && w.width() > 0 && w.height() > 0) {
                mark(canvas, theme, w, x, y, tally);
            }
            for (Widget child : w.children()) {
                markTargets(canvas, theme, child, x + child.x(), y + child.y(), tally);
            }
        }

        /** Outlines one target's painted box and draws the floor circle over its centre. */
        private void mark(Canvas canvas, Theme theme, Widget w, float x, float y, Tally tally) {
            float shorter = Math.min(w.width(), w.height());
            boolean meets = shorter >= Strokes.MIN_HIT_TARGET;
            tally.record(w.getClass().getSimpleName(), shorter, meets);

            Color ink = meets ? theme.success : theme.danger;
            // The box, at the half-pixel inset every 1pt outline in the toolkit uses, so the
            // annotation lands on the same device pixel row as the border it traces.
            canvas.drawRect(x + Strokes.HALF_PIXEL_INSET, y + Strokes.HALF_PIXEL_INSET,
                    w.width() - Strokes.BORDER, w.height() - Strokes.BORDER,
                    Strokes.BORDER, ink.withAlpha(0.55f));

            float cx = x + w.width() / 2;
            float cy = y + w.height() / 2;
            if (meets) {
                canvas.drawCircle(cx, cy, TARGET_RADIUS, Strokes.BORDER, ink);
                return;
            }
            // A failure is marked by shape as well as by colour: a red/green pair is the one
            // encoding an accessibility scene has no business relying on.
            canvas.fillCircle(cx, cy, TARGET_RADIUS, ink.withAlpha(0.18f));
            canvas.drawCircle(cx, cy, TARGET_RADIUS, Strokes.FOCUS_RING, ink);
            float reach = TARGET_RADIUS * SLASH_REACH;
            canvas.drawLine(cx - reach, cy + reach, cx + reach, cy - reach, Strokes.FOCUS_RING, ink);
        }

        /** One row's count, right-aligned in the gutter the rows leave free. */
        private void drawVerdict(Canvas canvas, Theme theme, SizeTokens t, float centreY,
                                 Tally row) {
            String text = row.belowFloor == 0
                    ? row.targets + " targets, all pass"
                    : row.targets + " targets, " + row.belowFloor + " short";
            TextMetrics m = canvas.measureText(text, t.label());
            // Optical centring on the row: the visual middle of a line of text is the middle of
            // its ink box, which sits above the baseline by (ascent - descent) / 2.
            canvas.drawText(text, width() - m.width(), centreY + (m.ascent() - m.descent()) / 2,
                    t.label(), row.belowFloor == 0 ? theme.textMuted : theme.danger);
        }

        /** The page total, plus the worst measured extent per offending component. */
        private void drawFooter(Canvas canvas, Theme theme, SizeTokens t, Tally page) {
            TextMetrics ref = canvas.measureText("0", t.label());
            float top = height() - 2 * ref.lineHeight();
            canvas.drawLine(0, top - t.spacingSmall(), width(), top - t.spacingSmall(),
                    Strokes.HAIRLINE, theme.outline);

            String head = String.format(Locale.ROOT,
                    "WCAG 2.2 SC 2.5.8 (AA), %.0fpt: %d targets, %d short",
                    Strokes.MIN_HIT_TARGET, page.targets, page.belowFloor);
            canvas.drawText(head, 0, top + ref.ascent(), t.label(), theme.text);

            String detail;
            if (page.belowFloor == 0) {
                detail = "Every target contains the circle.";
            } else {
                StringBuilder sb = new StringBuilder("Short: ");
                // The key is the class name, so a Checkbox that fails at 18 as a box and at 22
                // as a switch reports its worst case once; the number to fix is the worst one.
                for (Map.Entry<String, Float> e : page.worst.entrySet()) {
                    if (sb.length() > "Short: ".length()) {
                        sb.append(" · ");
                    }
                    sb.append(String.format(Locale.ROOT, "%s %.0fpt", e.getKey(), e.getValue()));
                }
                detail = ellipsize(canvas, sb.toString(), t);
            }
            canvas.drawText(detail, 0, top + ref.lineHeight() + ref.ascent(), t.label(),
                    page.belowFloor == 0 ? theme.success : theme.danger);
        }

        /** Trims the detail line to the overlay's width; it grows with the failure list. */
        private String ellipsize(Canvas canvas, String text, SizeTokens t) {
            if (canvas.measureText(text, t.label()).width() <= width()) {
                return text;
            }
            String cut = text;
            while (!cut.isEmpty()
                    && canvas.measureText(cut + "…", t.label()).width() > width()) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "…";
        }
    }

    /** Running count for one row, and for the page once the rows are absorbed into it. */
    private static final class Tally {
        int targets;
        int belowFloor;
        /** Component simple name to its smallest measured extent. Insertion order = read order. */
        final Map<String, Float> worst = new LinkedHashMap<>();

        void record(String name, float shorter, boolean meets) {
            targets++;
            if (meets) {
                return;
            }
            belowFloor++;
            worst.merge(name, shorter, Math::min);
        }

        void absorb(Tally other) {
            targets += other.targets;
            belowFloor += other.belowFloor;
            other.worst.forEach((name, extent) -> worst.merge(name, extent, Math::min));
        }
    }
}
