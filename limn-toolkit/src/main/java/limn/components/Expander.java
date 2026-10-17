package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Motion;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A section that opens and closes: a header over content that slides out below it. The header
 * holds an arrow that turns as the section opens, a title, an optional icon at its start and an
 * optional summary at its end, which says what is inside while it is closed ("3 files", "Off").
 *
 * <p>It opens and closes by a click on the header, by Space or Enter while the header holds the
 * keyboard, and from {@link #setExpanded}. The content slides out over {@link Theme#animExpand},
 * and appears at once when the user asked the system for less motion. Once it is closed the
 * content is hidden: it takes no room, no focus and no pointer, and a reader does not find it.
 * While it slides shut it is still drawn, as it was and not greyed, but it answers nothing from
 * the moment the close starts: a press on it reaches no control, Tab and an access letter pass it
 * by, and nothing inside can take the keyboard. Closing it while the keyboard is inside moves the
 * keyboard to the header as the close starts, which is where the user just was. The content's own
 * enabled state is the application's and is never changed; opened again, it answers as before.
 *
 * <p>A reader hears the header as a button, expanded or collapsed, that controls the content;
 * the summary is its description. An {@link ExpanderGroup} keeps at most one of several open.
 *
 * <p><b>Reading right to left</b> the arrow, the icon and the title start from the right and the
 * summary ends on the left, and a closed arrow points to the left, where reading goes.
 */
public final class Expander extends Widget<Expander> {

    private final Header header = new Header();
    private I18nString title;
    private @Nullable Icon icon;
    private Icon.Mirroring iconMirroring = Icon.Mirroring.NEVER;
    private @Nullable I18nString summary;
    private Widget<?> content;
    private boolean expanded;
    private @Nullable Consumer<Boolean> onChange;
    /** The group keeping this one of several open, or null. */
    @Nullable ExpanderGroup group;
    /** The place this section was added to its group at, which it takes again when it comes back. */
    int placeInGroup;

    /** How far open it is drawn, 0 closed and 1 open: what the layout and the arrow read. */
    private float reveal;
    private float revealFrom;
    private double revealElapsed;
    /** Bumped by every new run, so a ticker left from an earlier one stops on its next tick. */
    private int revealRun;
    private boolean revealing;

    /**
     * A closed section with a fixed title; see the {@link I18nString} constructor for a localized
     * one.
     *
     * @param title   the header's title
     * @param content what opens below it
     */
    public Expander(String title, Widget<?> content) {
        this(I18nString.literal(Objects.requireNonNull(title, "title")), content);
    }

    /**
     * A closed section whose title follows the UI language.
     *
     * @param title   the header's title
     * @param content what opens below it
     */
    public Expander(I18nString title, Widget<?> content) {
        this.title = Objects.requireNonNull(title, "title");
        this.content = Objects.requireNonNull(content, "content");
        add(header);
        add(content);
        content.setVisible(false);
        SceneAccess.setInert(content, true);
    }

    // ------------------------------------------------------------------------------ the API

    /** @return the title, resolved in this widget's own language */
    public String title() {
        return title.get();
    }

    /** Sets a fixed title. */
    public Expander setTitle(String newTitle) {
        return setTitle(I18nString.literal(Objects.requireNonNull(newTitle, "newTitle")));
    }

    /** Sets a title that follows the UI language. */
    public Expander setTitle(I18nString newTitle) {
        Ui.checkUiThread();
        this.title = Objects.requireNonNull(newTitle, "newTitle");
        markNeedsLayout();
        header.invalidate();
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        return this;
    }

    /**
     * Sets the icon at the header's start, tinted to the title's colour and drawn as authored;
     * {@code null} takes it away.
     */
    public Expander setIcon(@Nullable Icon newIcon) {
        return setIcon(newIcon, Icon.Mirroring.NEVER);
    }

    /**
     * Sets the icon at the header's start, saying whether its drawing turns around when the
     * interface reads right to left.
     *
     * @param mirroring {@link Icon.Mirroring#NEVER} unless the glyph means a direction
     */
    public Expander setIcon(@Nullable Icon newIcon, Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        this.icon = newIcon;
        this.iconMirroring = Objects.requireNonNull(mirroring, "mirroring");
        markNeedsLayout();
        header.invalidate();
        return this;
    }

    /** @return the icon at the header's start, or null */
    public @Nullable Icon icon() {
        return icon;
    }

    /**
     * Sets the summary at the header's end, in a quieter colour: what is inside, said while it is
     * closed. A reader hears it as the header's description. {@code null} takes it away.
     */
    public Expander setSummary(@Nullable String newSummary) {
        return setSummary(newSummary == null ? null : I18nString.literal(newSummary));
    }

    /** Sets a summary that follows the UI language, or takes it away with {@code null}. */
    public Expander setSummary(@Nullable I18nString newSummary) {
        Ui.checkUiThread();
        this.summary = newSummary;
        markNeedsLayout();
        header.invalidate();
        notifyChange(Change.of(Change.Aspect.DESCRIPTION, Change.Origin.CODE));
        return this;
    }

    /** @return the summary, resolved in this widget's own language, or null */
    public @Nullable String summary() {
        return summary == null ? null : summary.get();
    }

    /** @return what opens below the header */
    public Widget<?> content() {
        return content;
    }

    /**
     * Puts other content below the header, open or closed as the section is.
     *
     * @param newContent what opens below the header
     */
    public Expander setContent(Widget<?> newContent) {
        Ui.checkUiThread();
        Objects.requireNonNull(newContent, "newContent");
        if (newContent == content) {
            return this;
        }
        boolean focusInside = holdsFocus(content);
        Widget<?> old = content;
        remove(old);
        SceneAccess.setInert(old, false); // it is the application's again, to put anywhere
        content = newContent;
        add(newContent);
        newContent.setVisible(expanded || reveal > 0);
        SceneAccess.setInert(newContent, !expanded);
        if (focusInside) {
            header.focusFrom(Change.Origin.ADJUSTMENT);
        }
        markNeedsLayout();
        return this;
    }

    /** @return whether it is open, or opening */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Opens or closes it: a caller's write, so it announces {@code EXPANDED}/{@code CODE} and
     * runs no handler. In a group, opening it closes the one that was open, which announces too.
     * The same state again changes nothing and announces nothing. UI thread only.
     */
    public Expander setExpanded(boolean value) {
        Ui.checkUiThread();
        setExpanded(value, Change.Origin.CODE);
        return this;
    }

    /**
     * The application's response to the user opening or closing it: a click, Space or Enter on
     * the header, or a reader's verb, with the new state. Never for {@link #setExpanded}, nor for
     * a group closing it because another opened from code; to hear every change whatever caused
     * it, {@linkplain #observeChanges watch} it instead.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this expander
     * @throws IllegalStateException if a handler is already registered
     */
    public Expander onChange(@Nullable Consumer<Boolean> listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "Expander.onChange");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.EXPANDED) {
            if (onChange != null) {
                onChange.accept(expanded);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    // ------------------------------------------------------------------------- the state

    /** Every open and close comes through here, with who asked. */
    void setExpanded(boolean value, Change.Origin origin) {
        if (value == expanded) {
            return;
        }
        if (group != null) {
            group.toggled(this, value, origin);
            return;
        }
        applyExpanded(value, origin);
        announceExpanded(origin);
        runExpandedHandler(origin);
    }

    /**
     * The state and everything that follows it, and no announcement: the group settles two
     * members before either speaks.
     */
    void applyExpanded(boolean value, Change.Origin origin) {
        expanded = value;
        if (value) {
            SceneAccess.setInert(content, false);
            content.setVisible(true);
        } else {
            if (holdsFocus(content)) {
                // The keyboard was in what is closing: it goes back to where the user just was.
                header.focusFrom(origin);
            }
            // What slides shut is still drawn, as it was and not greyed, but it answers nothing:
            // a press on it, Tab and a letter pass it by, and no focus can land in it. Its own
            // enabled state is the application's and is not touched.
            SceneAccess.setInert(content, true);
        }
        header.invalidate();
        startReveal(value ? 1 : 0);
    }

    void announceExpanded(Change.Origin origin) {
        announceChange(Change.of(Change.Aspect.EXPANDED, origin));
    }

    /** The handler half, a no-op at any origin but {@code USER}. */
    void runExpandedHandler(Change.Origin origin) {
        runHandler(Change.of(Change.Aspect.EXPANDED, origin));
    }

    private boolean holdsFocus(Widget<?> subtree) {
        if (scene() == null) {
            return false;
        }
        for (Widget<?> w = scene().focusedWidget(); w != null; w = w.parent()) {
            if (w == subtree) {
                return true;
            }
        }
        return false;
    }

    /**
     * Slides toward {@code target} on wall time: a section opening is the interface answering
     * the user, and it must not wait for an application that paused its own scene clock. Each
     * frame lays the section out again, because its height is what moves; at once with no scene,
     * when hidden, or when the user asked for less motion.
     */
    private void startReveal(float target) {
        int run = ++revealRun;
        double duration = Theme.of(this).animExpand;
        if (scene() == null || !isShowing() || Motion.reduced() || duration <= 0) {
            finishReveal(target);
            return;
        }
        revealFrom = reveal;
        revealElapsed = 0;
        revealing = true;
        markNeedsLayout();
        scene().addRealTimeTicker(dt -> {
            if (run != revealRun) {
                return false;
            }
            if (scene() == null || !isShowing()) {
                finishReveal(target);
                return false;
            }
            revealElapsed += dt;
            float t = (float) Math.min(1, revealElapsed / duration);
            reveal = revealFrom + (target - revealFrom) * Theme.of(this).animEasing.apply(t);
            if (t >= 1) {
                finishReveal(target);
                return false;
            }
            markNeedsLayout();
            return true;
        });
    }

    private void finishReveal(float target) {
        revealing = false;
        reveal = target;
        if (target == 0) {
            content.setVisible(false);
        }
        markNeedsLayout();
        header.invalidate();
    }

    /** @return whether it is sliding open or closed now; for tests */
    boolean isRevealing() {
        return revealing;
    }

    // ------------------------------------------------------------------------------ layout

    /** The room around the content: the header's own side padding, and a small gap above and below. */
    private float bodyPadV(SizeTokens t) {
        return t.spacingSmall();
    }

    private float headerHeight(SizeTokens t) {
        float line = textRuler().measure("Hg", t.body()).lineHeight();
        return Math.max(t.controlHeight(), line + 2 * t.padV());
    }

    /**
     * A group does not hold a section that is out of the scene: a form rebuilt against the same
     * group would otherwise leave it every section of every form before, each still counted and
     * each holding its content.
     */
    @Override
    protected void onDetached() {
        super.onDetached();
        if (group != null) {
            group.left(this);
        }
    }

    @Override
    protected void onAttached() {
        super.onAttached();
        if (group != null) {
            group.returned(this);
        }
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float headerH = headerHeight(t);
        float width = constraints.hasBoundedWidth() ? constraints.maxWidth()
                : Math.max(header.naturalWidth(t), content.measure(Constraints.loose(
                        Constraints.UNBOUNDED_LIMIT, Constraints.UNBOUNDED_LIMIT)).width()
                        + 2 * t.padH());
        float height = headerH;
        if (reveal > 0) {
            height += reveal * bodyHeight(t, width);
        }
        return constraints.constrain(width, height);
    }

    private float bodyHeight(SizeTokens t, float width) {
        float inner = Math.max(0, width - 2 * t.padH());
        return content.measure(Constraints.loose(inner, Constraints.UNBOUNDED_LIMIT)).height()
                + 2 * bodyPadV(t);
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float headerH = headerHeight(t);
        header.layoutBox(0, 0, width(), headerH);
        if (content.isVisible()) {
            // Laid out whole and clipped by this section's own edge as it slides: the content
            // keeps its size and its layout while it opens, so nothing inside it reflows per frame.
            float inner = Math.max(0, width() - 2 * t.padH());
            float h = content.measure(Constraints.loose(inner, Constraints.UNBOUNDED_LIMIT)).height();
            content.layoutBox(t.padH(), headerH + bodyPadV(t), inner, h);
        }
    }

    @Override
    protected boolean clipsChildren() {
        return true;
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            super.paintChildren(canvas);
        } finally {
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------------ accessibility

    /**
     * Says of the content what only this section knows: that the header names it. A content that
     * said nothing of itself becomes a group, so the link lands on a node; one that already has a
     * role or a name keeps them.
     */
    @Override
    protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
        if (child != content) {
            return;
        }
        if (!a.hasRole()) {
            a.role(Accessible.Role.GROUP);
        }
        if (!a.hasName()) {
            a.name(title, Accessible.NameFrom.LABEL);
        }
        a.relation(Accessible.Relation.LABELLED_BY, header);
    }

    // ------------------------------------------------------------------------------ header

    /** The band that opens and closes the section: focusable, pressable, and the reader's button. */
    private final class Header extends Widget<Header> {

        private final limn.animation.Transition hover = new limn.animation.Transition(this)
                .duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
        private final limn.animation.Transition focusFade = new limn.animation.Transition(this)
                .duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
        private final Path2D arrow = new Path2D();
        private boolean keyArmed;

        Header() {
            setFocusable(true);
            setCursor(Cursor.POINTER);
        }

        /** Moves the keyboard here with the origin of the change the section is settling. */
        void focusFrom(Change.Origin origin) {
            requestFocus(origin);
        }

        private float arrowBand(SizeTokens t) {
            return t.chevronHalfW() * 2;
        }

        private float iconAdvance(SizeTokens t) {
            return icon == null ? 0 : t.iconBox() + t.gapIcon();
        }

        float naturalWidth(SizeTokens t) {
            float w = 2 * t.padH() + arrowBand(t) + t.gapIcon() + iconAdvance(t)
                    + textRuler().measure(title.get(), t.body()).width();
            if (summary != null) {
                w += t.spacingMedium() + textRuler().measure(summary.get(), t.body()).width();
            }
            return w;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(this);
            return constraints.constrain(naturalWidth(t), headerHeight(t));
        }

        @Override
        protected float baselineOffset() {
            TextMetrics m = textRuler().measure("Hg", Theme.of(this).tokensFor(this).body());
            return (height() - m.height()) / 2 + m.ascent();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            boolean enabled = isEnabled();
            float h = hover.value();
            if (enabled && h > 0.01f) {
                float inset = t.tabHoverInset();
                canvas.fillRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                        t.radiusSmall(), theme.surfaceRaised().withAlpha(0.6f * h));
            }
            boolean rtl = isRightToLeft();
            Color ink = enabled ? theme.text() : theme.disabledText();
            Color quiet = enabled ? theme.textMuted() : theme.disabledText();
            Font font = t.body();
            TextMetrics line = textRuler().measure("Hg", font);
            float baseline = (height() - line.height()) / 2 + line.ascent();

            // From the start of reading: the arrow, the icon, the title; the summary at the end.
            float start = t.padH();
            float band = arrowBand(t);
            paintArrow(canvas, t, rtl ? width() - start - band / 2 : start + band / 2,
                    height() / 2, rtl, quiet);
            start += band + t.gapIcon();
            if (icon != null) {
                float side = t.iconBox();
                icon.paint(canvas, rtl ? width() - start - side : start, (height() - side) / 2,
                        side, ink, theme.isDark(), rtl && iconMirroring == Icon.Mirroring.IN_RTL);
                start += side + t.gapIcon();
            }
            float end = width() - t.padH();
            if (summary != null) {
                String said = summary.get();
                ShapedText run = textRuler().shape(said, font,
                        ShapedText.Direction.of(said, neutralBase()));
                // The summary gives way first: it may take half of what is left after the title's
                // start, and the title the rest.
                float room = Math.max(0, (end - start) / 2);
                if (run.metrics().width() > room) {
                    run = textRuler().ellipsize(run, room);
                }
                float w = run.metrics().width();
                canvas.drawText(run, rtl ? width() - end : end - w, baseline, quiet);
                end -= w + t.spacingMedium();
            }
            String shown = title.get();
            ShapedText run = textRuler().shape(shown, font,
                    ShapedText.Direction.of(shown, neutralBase()));
            float room = Math.max(0, end - start);
            if (run.metrics().width() > room) {
                run = textRuler().ellipsize(run, room);
            }
            canvas.drawText(run, rtl ? width() - start - run.metrics().width() : start, baseline, ink);

            float focus = focusFade.value();
            if (focus > 0.001f) {
                float gap = Strokes.FOCUS_GAP_TAB;
                canvas.drawRoundRect(gap, gap, width() - 2 * gap, height() - 2 * gap,
                        t.radiusSmall(), Strokes.FOCUS_RING_THIN, theme.focusRing().withAlpha(focus));
            }
        }

        /**
         * The tree's disclosure arrow, turned as far as the section is open: toward where reading
         * goes when closed, down when open, a quarter turn between that follows the slide.
         */
        private void paintArrow(Canvas canvas, SizeTokens t, float cx, float cy, boolean rtl,
                Color ink) {
            float halfW = t.chevronHalfW();
            float halfH = halfW / 2;
            // Pointing to the end of reading, then turned toward down: clockwise from the right,
            // counter-clockwise from the left.
            double angle = (rtl ? -1 : 1) * reveal * Math.PI / 2;
            float sign = rtl ? -1 : 1;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float[] xs = {-halfH * sign, halfH * sign, -halfH * sign};
            float[] ys = {-halfW, 0, halfW};
            arrow.reset();
            for (int i = 0; i < 3; i++) {
                float x = cx + xs[i] * cos - ys[i] * sin;
                float y = cy + xs[i] * sin + ys[i] * cos;
                if (i == 0) {
                    arrow.moveTo(x, y);
                } else {
                    arrow.lineTo(x, y);
                }
            }
            canvas.drawPath(arrow, Strokes.ARROW_PEN, ink);
        }

        /**
         * A button that is expanded or collapsed, named by the title and described by the summary,
         * offering the press its click performs and the one of open and close that the state
         * allows, and controlling the content.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            a.name(title, Accessible.NameFrom.CONTENT);
            if (summary != null) {
                a.description(summary);
            }
            a.expand(expanded);
            a.action(Accessible.Action.PRESS,
                    expanded ? Accessible.Action.COLLAPSE : Accessible.Action.EXPAND);
            a.relation(Accessible.Relation.CONTROLLER_FOR, content);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            switch (action) {
                case PRESS -> {
                    setExpanded(!expanded, Change.Origin.USER);
                    return true;
                }
                case EXPAND -> {
                    if (expanded) {
                        return false;
                    }
                    setExpanded(true, Change.Origin.USER);
                    return true;
                }
                case COLLAPSE -> {
                    if (!expanded) {
                        return false;
                    }
                    setExpanded(false, Change.Origin.USER);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> hover.to(1);
                case EXIT -> hover.to(0);
                case PRESS -> {
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                    }
                }
                case CLICK -> {
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                        requestFocus(Change.Origin.USER);
                        setExpanded(!expanded, Change.Origin.USER);
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.key() != Keys.ENTER && event.key() != Keys.SPACE) {
                return;
            }
            // On the release, as a button fires: a key held down does not open and close the
            // section over and over, and a release whose press went elsewhere does nothing.
            if (event.isPressed()) {
                if (!event.isRepeat()) {
                    keyArmed = true;
                }
                event.consume();
            } else {
                boolean fire = keyArmed;
                keyArmed = false;
                event.consume();
                if (fire) {
                    setExpanded(!expanded, Change.Origin.USER);
                }
            }
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
            keyArmed = false;
        }
    }
}
