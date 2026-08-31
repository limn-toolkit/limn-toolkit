package limn.components;

import limn.backend.Cursor;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A horizontal strip of top-level menu titles; the strip itself is drawn
 * in-scene (part of the window chrome). Clicking a title drops its {@link Menu}
 * as a {@link PopupMenu} (a native window) below it; Left/Right walk between
 * menus, Down/Enter opens the focused one, and its submenus/checkable items
 * behave like any other popup.
 *
 * <pre>{@code
 * MenuBar bar = new MenuBar()
 *     .addMenu("File", 'F', fileMenu)
 *     .addMenu("Edit", 'E', editMenu)
 *     .addMenu("View", 'V', viewMenu);
 * }</pre>
 *
 * <p><b>The bar is the keyboard entry point to the whole menu system</b>, and it is one while it
 * is in a scene rather than only while it is focused. From the moment it is attached it answers,
 * anywhere in the scene and without focus: an item's {@link Accelerator} runs that item; a bare
 * Alt <em>released</em> (or F10) moves focus here; Alt plus a declared access letter opens that
 * menu. All of it is offered only after the focused widget has declined the key, so a shortcut
 * never steals a keystroke from a text field that wanted it.
 *
 * <p><b>The strip mirrors</b> where it reads right to left: the first title is at the right
 * edge, each title's text sits on the pad of the side reading starts from, and Left selects the
 * visually-left title, which is then the <em>next</em> one in declaration order. The dropdown
 * inherits the direction through the anchor and mirrors with it. Accelerator labels do not
 * mirror: they name physical keys.
 *
 * <p>The strip's height <em>is</em> the {@link limn.scene.ControlSize} step's
 * {@code controlHeight}, so a bar and the buttons beside it agree at every step, and the
 * dropdown opens at the bar's step because {@link PopupMenu} is anchored on this widget.
 * Every metric comes from the resolved {@link SizeTokens} row, every weight from
 * {@link Strokes}, including the bottom rule, the most visible hairline in the toolkit,
 * which stays 1&nbsp;pt at XSMALL and at XLARGE.
 */
public final class MenuBar extends Widget {

    /** @param mnemonic the uppercased access letter, or {@code 0} for a title without one */
    private record Entry(limn.i18n.I18nString title, Menu menu, char mnemonic) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private int hoverIndex = -1;
    private int openIndex = -1;
    private PopupMenu openPopup;
    /** Handed to every dropdown this bar opens; see {@link #setDisplayMode}. */
    private DisplayMode displayMode = PopupMenu.defaultDisplayMode();
    /** Unregisters the scene shortcut hook; non-null exactly while attached. */
    private Runnable unhookShortcuts;
    /**
     * Whether the Alt currently held was pressed alone and nothing has been pressed since: the
     * state that separates "reaching for the menu bar" from "typing Alt+F". Cleared by any other
     * key press, which is why the bar activates on Alt's <em>release</em>: at press time a chord
     * and a bare Alt are the same event.
     */
    private boolean altArmed;

    /** An empty bar; add top-level menus with {@link #addMenu}. */
    public MenuBar() {
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    /** Appends a top-level menu with the given title, and no access letter. UI thread only. */
    public MenuBar addMenu(String title, Menu menu) {
        return addMenu(title, (char) 0, menu);
    }

    /** A top-level menu whose title follows the UI language, and no access letter. UI thread only. */
    public MenuBar addMenu(limn.i18n.I18nString title, Menu menu) {
        return addMenu(title, (char) 0, menu);
    }

    /**
     * Appends a top-level menu whose title carries an access letter: it is underlined in the
     * strip, {@code Alt} plus that letter opens the menu from anywhere in the scene, and the bare
     * letter opens it while the bar has focus.
     *
     * @param mnemonic a letter or digit, matched case-insensitively; {@code 0} for none
     * @throws IllegalArgumentException for any other character
     */
    public MenuBar addMenu(String title, char mnemonic, Menu menu) {
        return addMenu(limn.i18n.I18nString.literal(Objects.requireNonNull(title, "title")),
                mnemonic, menu);
    }

    /** {@link #addMenu(String, char, Menu)} with a title that follows the UI language. */
    public MenuBar addMenu(limn.i18n.I18nString title, char mnemonic, Menu menu) {
        if (mnemonic != 0 && !Character.isLetterOrDigit(mnemonic)) {
            throw new IllegalArgumentException("a mnemonic must be a letter or a digit: " + mnemonic);
        }
        entries.add(new Entry(Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(menu, "menu"),
                mnemonic == 0 ? 0 : Character.toUpperCase(mnemonic)));
        markNeedsLayout();
        return this;
    }

    /**
     * Asks for the presentation of every dropdown this bar opens; see {@link DisplayMode}.
     * Default {@link DisplayMode#NATIVE_WINDOW}, which is what a platform menu bar does.
     *
     * <p><b>A preference, not a guarantee</b>: a dropdown asked for {@code NATIVE_WINDOW} is still
     * drawn in scene where the platform cannot place a window at the anchor, and in macOS
     * exclusive fullscreen. {@code IN_SCENE} is always honoured, and the bar keeps its
     * hover-to-switch behaviour under it, because pointer input over the strip falls through the
     * overlay.
     *
     * <p>Takes effect on the next open; a dropdown already down is not re-presented.
     */
    public MenuBar setDisplayMode(DisplayMode mode) {
        this.displayMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /** @return the presentation the next dropdown will be asked for. */
    public DisplayMode displayMode() {
        return displayMode;
    }

    /** @return whether a dropdown is currently open. */
    public boolean isOpen() {
        return openIndex >= 0;
    }

    // --------------------------------------------------------------- geometry
    // Four walks share one titleWidth: measure, paint, titleAt and the dropdown's anchor. Each
    // takes the resolved row AND the resolved direction as parameters rather than resolving its
    // own: two resolutions that disagree inside one pass route a click to the neighbouring menu.
    // The strip is a cursor walk, so it mirrors the way every strip in this toolkit does — at
    // the coordinate, in titleX, with the cursor arithmetic left alone — and all four walks go
    // through that one conversion so a half-mirrored pair cannot exist.

    /** The measured width of a title's text: the one authority its box and its origin share. */
    private float titleTextWidth(String title, SizeTokens t) {
        return textRuler().measure(title, t.body()).width();
    }

    /**
     * A title's box. The floor is the width-axis half of the accessibility answer: the height
     * ramp clears 24&nbsp;pt on the y axis by construction, but {@link #titleAt} has zero slop,
     * so a one-glyph title at XSMALL would be {@code 2 * 6 + ~6 = 18} pt wide. Widening the box
     * (rather than an invisible hit outset) is the right fix here because adjacent titles must
     * not overlap. A no-op at MEDIUM, where the padding alone already pays it.
     */
    private float titleWidth(int i, SizeTokens t) {
        return Math.max(Strokes.MIN_HIT_TARGET,
                titleTextWidth(entries.get(i).title().get(), t) + 2 * t.menuBarPadH());
    }

    /**
     * How far title {@code i} starts from the edge reading starts at: a magnitude along the
     * strip, in declaration order, and the same number in either direction. {@link #titleX}
     * turns it into a coordinate.
     */
    private float titleLeading(int i, SizeTokens t) {
        float x = 0;
        for (int k = 0; k < i; k++) {
            x += titleWidth(k, t);
        }
        return x;
    }

    /**
     * The physical left edge of a box of width {@code w} whose leading edge is {@code leading}
     * points along the strip: the one reflection the four walks share, so that a pair of them
     * cannot end up half mirrored, and so that each walk goes on counting in declaration order.
     * (The title's text inside its box is the only other coordinate here with a side to it.)
     */
    private float titleX(float leading, float w, boolean rtl) {
        return rtl ? width() - leading - w : leading;
    }

    /** The physical left edge of title {@code i}'s box. */
    private float titleX(int i, SizeTokens t, boolean rtl) {
        return titleX(titleLeading(i, t), titleWidth(i, t), rtl);
    }

    private int titleAt(float localX, SizeTokens t, boolean rtl) {
        float cursor = 0;
        for (int i = 0; i < entries.size(); i++) {
            float w = titleWidth(i, t);
            // The boxes tile with neither gap nor overlap in either direction; which side of a
            // shared edge belongs to which title differs, and a title's own points do not.
            float x = titleX(cursor, w, rtl);
            if (localX >= x && localX < x + w) {
                return i;
            }
            cursor += w;
        }
        return -1;
    }

    /** Whether this strip reads right to left. Resolve it once per pass. */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        float total = 0;
        for (int i = 0; i < entries.size(); i++) {
            total += titleWidth(i, t);
        }
        // The strip is chrome, not a text-fit box: it takes the step's control height outright
        // so it lines up with the buttons and fields around it. (The text-fit floor would be
        // slack at every step anyway: 11pt body in a 24pt strip at XSMALL.)
        return constraints.constrain(total, t.controlHeight());
    }

    /** The baseline BASELINE rows align on, the expression {@link #onPaint} draws titles with. */
    @Override
    protected float baselineOffset() {
        if (entries.isEmpty()) {
            return super.baselineOffset(); // no titles: a bare strip aligns on its bottom edge
        }
        TextMetrics fm = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return (height() - fm.height()) / 2 + fm.ascent();
    }

    // ------------------------------------------------------------------ paint

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        // The bottom rule: one locked hairline, half-pixel inset so it lands on a whole device
        // pixel. Scaling it is exactly what the size axis must never do to a border.
        float ruleY = height() - Strokes.HALF_PIXEL_INSET;
        canvas.drawLine(0, ruleY, width(), ruleY, Strokes.HAIRLINE, theme.outline);
        Font font = t.body();
        TextMetrics fm = textRuler().measure("Hg", font);
        float chip = t.menuBarChipInset();
        // One resolution for the whole pass, beside the row, for the same reason.
        boolean rtl = isRtl();
        float cursor = 0;
        for (int i = 0; i < entries.size(); i++) {
            float w = titleWidth(i, t);
            float x = titleX(cursor, w, rtl);
            boolean active = i == openIndex || i == hoverIndex;
            if (active) {
                Color fill = i == openIndex ? theme.primary.withAlpha(0.20f) : theme.surfaceRaised;
                canvas.fillRoundRect(x + chip, chip, w - 2 * chip, height() - 2 * chip,
                        t.radiusSmall(), fill);
            }
            Color ink = i == openIndex ? theme.text : theme.textMuted.lerp(theme.text, i == hoverIndex ? 1 : 0);
            // Titles are aligned on the leading pad, not centred in the (possibly floored) box:
            // the floor only ever widens the last few points on the trailing side. drawText
            // places a run's LEFT edge in either direction, so a mirrored title finds that edge
            // from its own measured width — through the same call its box was sized with, or it
            // would sit a fraction of a point out of its pad.
            String title = entries.get(i).title().get();
            float textX = rtl
                    ? x + w - t.menuBarPadH() - titleTextWidth(title, t)
                    : x + t.menuBarPadH();
            float baseline = (height() - fm.height()) / 2 + fm.ascent();
            canvas.drawText(title, textX, baseline, font, ink);
            // The rule takes its edges off the painted line's own shaping and copes with a
            // right-to-left run itself: the mirrored left edge is the whole of what it needs.
            MenuInk.underlineMnemonic(canvas, textRuler(), title,
                    MenuInk.mnemonicIndex(title, entries.get(i).mnemonic()),
                    textX, baseline, font, ink);
            cursor += w;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // One resolution of each axis for the whole event: the title a press lands on must be
        // the title the last paint drew there.
        SizeTokens t = Theme.current().tokensFor(this);
        boolean rtl = isRtl();
        switch (event.type()) {
            case MOVE, ENTER -> {
                int i = titleAt(sceneToLocalX(event.x()), t, rtl);
                if (i != hoverIndex) {
                    hoverIndex = i;
                    invalidate();
                }
                // Hover-switch while another menu is already open (pointer is over
                // the bar, e.g. no overlay covers it in that split second).
                if (openIndex >= 0 && i >= 0 && i != openIndex) {
                    openMenu(i);
                }
            }
            case EXIT -> {
                if (hoverIndex != -1) {
                    hoverIndex = -1;
                    invalidate();
                }
            }
            case PRESS -> {
                if (event.button() != Keys.MOUSE_LEFT) {
                    return;
                }
                int i = titleAt(sceneToLocalX(event.x()), t, rtl);
                if (i >= 0) {
                    if (i == openIndex) {
                        closeMenu();
                    } else {
                        openMenu(i);
                    }
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || entries.isEmpty()) {
            return;
        }
        int n = entries.size();
        switch (event.key()) {
            case Keys.LEFT, Keys.RIGHT -> {
                // Left must select the visually-left title or the strip disagrees with the
                // pointer that just hovered it. Reading right to left the visually-left title
                // is the NEXT one in declaration order, so the key is flipped here — once, and
                // in the same breath as the dropdown's own arrows, which PopupMenu flips for
                // itself: the callbacks wired between the two mean previous/next and stay put.
                boolean towardsLeading = (event.key() == Keys.LEFT) != isRtl();
                hoverIndex = towardsLeading
                        ? (hoverIndex <= 0 ? n - 1 : hoverIndex - 1)
                        : (hoverIndex + 1) % n;
                invalidate();
            }
            case Keys.DOWN, Keys.ENTER, Keys.SPACE -> openMenu(hoverIndex < 0 ? 0 : hoverIndex);
            case Keys.ESCAPE -> {
                // The way out of the keyboard mode Alt/F10 entered: the bar keeps focus until
                // something takes it, and without this the only exit is Tab or the pointer.
                if (scene() != null) {
                    scene().requestFocus(null);
                }
            }
            default -> {
                // A bare access letter while the bar is focused, the other half of Alt+letter.
                // Modifier-carrying presses are left alone: Alt+letter is the scene hook's, and
                // a Ctrl chord belongs to an accelerator.
                int index = event.modifiers() == 0 ? titleForMnemonic(event.key()) : -1;
                if (index < 0) {
                    return;
                }
                openMenu(index);
            }
        }
        event.consume();
    }

    /** @return the index of the top-level menu whose access letter is {@code key}, or −1. */
    private int titleForMnemonic(int key) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).mnemonic() != 0 && entries.get(i).mnemonic() == key) {
                return i;
            }
        }
        return -1;
    }

    // --------------------------------------------------------- scene shortcuts

    /**
     * The bar answers for the whole scene while it is attached: accelerators, Alt/F10 and
     * Alt+letter all arrive here, after the focused widget has declined them.
     */
    @Override
    protected void onAttached() {
        if (scene() != null && unhookShortcuts == null) {
            unhookShortcuts = scene().addShortcutHandler(this::onShortcut);
        }
    }

    /**
     * A bar removed from the tree must never strand its dropdown on screen, nor keep answering
     * the scene's keyboard from outside it, which is what the unhook is for.
     */
    @Override
    protected void onDetached() {
        closeMenu();
        if (unhookShortcuts != null) {
            unhookShortcuts.run();
            unhookShortcuts = null;
        }
        altArmed = false;
    }

    /**
     * The scene-wide handler. Returns whether the event was consumed.
     *
     * <p>Order inside it is the part worth knowing: Alt+letter is resolved as a mnemonic
     * <b>before</b> the accelerator table, so an application that declares both for one letter
     * gets the menu it can see rather than the command it cannot.
     */
    private boolean onShortcut(KeyEvent event) {
        if (entries.isEmpty() || isOpen()) {
            // An open cascade owns the keyboard: it is showing the very commands these chords
            // would run, and running one behind it would leave the menu up over the effect.
            altArmed = false;
            return false;
        }
        int key = event.key();
        int mods = event.modifiers();
        if (!event.isPressed()) {
            // Bare Alt activates here, on the release: at press time it is indistinguishable
            // from the start of Alt+F, and claiming it there would break every Alt chord.
            if (isAltKey(key) && altArmed) {
                altArmed = false;
                return toggleBarFocus();
            }
            return false;
        }
        if (isAltKey(key)) {
            // Re-arm only for an Alt pressed alone and not auto-repeating; Ctrl+Alt is a chord
            // in progress, and a repeat means it is being held rather than tapped.
            altArmed = !event.isRepeat() && (mods & ~Keys.MOD_ALT) == 0;
            return false; // a modifier press is never consumed
        }
        altArmed = false; // any other key makes the held Alt part of a chord
        if (key == Keys.F10 && mods == 0) {
            return toggleBarFocus();
        }
        if (mods == Keys.MOD_ALT) {
            int index = titleForMnemonic(key);
            if (index >= 0) {
                openMenu(index);
                return true;
            }
        }
        // Auto-repeat is deliberately not an accelerator: a held Ctrl+W would close a document a
        // frame at a time, and a held chord on a check item would flip it at the repeat rate.
        return !event.isRepeat() && runAccelerator(key, mods);
    }

    private static boolean isAltKey(int key) {
        return key == Keys.LEFT_ALT || key == Keys.RIGHT_ALT;
    }

    /** Alt/F10: focus the bar, or give focus back when it already has it. Always consumes. */
    private boolean toggleBarFocus() {
        Scene scene = scene();
        if (scene == null) {
            return false;
        }
        scene.requestFocus(isFocused() ? null : this);
        return true;
    }

    /**
     * Runs the enabled item whose accelerator is exactly this chord, searching every top-level
     * menu and its submenus in declaration order.
     *
     * @return whether an item ran
     */
    private boolean runAccelerator(int key, int modifiers) {
        for (int i = 0; i < entries.size(); i++) {
            MenuItem item = entries.get(i).menu().findAccelerator(key, modifiers);
            if (item != null) {
                item.activate();
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onFocusGained() {
        if (hoverIndex < 0 && !entries.isEmpty()) {
            hoverIndex = 0;
            invalidate();
        }
    }

    @Override
    protected void onFocusLost() {
        if (!isOpen()) {
            hoverIndex = -1;
            invalidate();
        }
    }

    // ------------------------------------------------------------- open/close

    private void openMenu(int index) {
        if (scene() == null || index < 0 || index >= entries.size()) {
            return;
        }
        if (openPopup != null) {
            PopupMenu old = openPopup;
            openPopup = null;
            openIndex = -1;
            old.close(); // its onClose no-ops now (openPopup no longer == old)
        }
        // Resolved once here and threaded into the anchor rect: the dropdown must line up with
        // the title the bar painted, and the popup itself takes this bar's step and its
        // direction through the anchor link below (a PopupMenu is parentless, being a native
        // window's scene root or an overlay, so the tree walk cannot reach us any other way).
        SizeTokens t = Theme.current().tokensFor(this);
        boolean rtl = isRtl();
        int n = entries.size();
        openIndex = index;
        hoverIndex = index;
        // The anchor rect is the title box the bar actually painted, mirrored included; the
        // column then aligns itself to that rect's leading edge on its own, which is the right
        // edge of the title reading right to left.
        float sceneX = localToSceneX() + titleX(index, t, rtl);
        float sceneY = localToSceneY();
        PopupMenu popup = new PopupMenu(entries.get(index).menu());
        popup.setDisplayMode(displayMode);
        popup.onClose(() -> {
            if (openPopup == popup) {
                openPopup = null;
                openIndex = -1;
                invalidate();
            }
        });
        // Previous and next in declaration order, NOT left and right: PopupMenu has already
        // turned the physical arrow into a side, and flipping again here would cancel that and
        // walk the bar against the direction its own submenus open in.
        popup.onRootLeading(() -> openMenu((index - 1 + n) % n));
        popup.onRootTrailing(() -> openMenu((index + 1) % n));
        // Fullscreen fallback: the dropdown is an in-scene overlay covering the bar.
        // Let pointer input over the strip fall through so hovering a sibling title
        // still switches menus (and shows the pointer cursor), as in native mode.
        popup.inScenePassThrough(this::pointOverStrip);
        openPopup = popup;
        invalidate();
        // Anchored on THIS widget, not on the scene: that is what makes a SMALL bar drop a
        // SMALL menu instead of one at the scene's (or the process's) default step.
        popup.showAnchored(this, sceneX, sceneY, titleWidth(index, t), height());
    }

    /** @return whether the scene point lies within this bar's strip (its own bounds). */
    private boolean pointOverStrip(float sceneX, float sceneY) {
        float bx = 0;
        float by = 0;
        for (Widget w = this; w != null; w = w.parent()) {
            bx += w.x();
            by += w.y();
        }
        return sceneX >= bx && sceneX < bx + width() && sceneY >= by && sceneY < by + height();
    }

    private void closeMenu() {
        if (openPopup != null) {
            PopupMenu popup = openPopup;
            openPopup = null;
            openIndex = -1;
            invalidate();
            popup.close();
        }
    }
}
