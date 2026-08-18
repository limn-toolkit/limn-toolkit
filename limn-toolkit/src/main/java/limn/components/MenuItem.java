package limn.components;

import limn.i18n.I18nString;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One entry in a {@link Menu}: a command, a checkable toggle, a submenu, or a
 * separator. Pure model (no widgets): a {@link PopupMenu} or {@link MenuBar}
 * renders it. Build with the static factories:
 *
 * <pre>{@code
 * Menu edit = new Menu()
 *     .add(MenuItem.of("Undo", () -> undo()).setAccelerator(Accelerator.command(Keys.Z)))
 *     .addSeparator()
 *     .add(MenuItem.check("Word wrap", true, on -> setWrap(on)).setMnemonic('W'))
 *     .add(MenuItem.submenu("Export", exportMenu));
 * }</pre>
 */
public final class MenuItem {

    /** What an item does when activated / how it renders. */
    enum Kind { COMMAND, CHECK, SUBMENU, SEPARATOR }

    private final Kind kind;
    private final I18nString label;
    private Runnable action = () -> { };
    private Consumer<Boolean> onToggle = checked -> { };
    private Menu submenu;
    private boolean enabled = true;
    private boolean checked;
    private Accelerator accelerator;
    /** The access letter, uppercased; {@code 0} when none is declared. */
    private char mnemonic;

    private MenuItem(Kind kind, I18nString label) {
        this.kind = kind;
        this.label = label;
    }

    private static I18nString wrap(String label) {
        return I18nString.literal(Objects.requireNonNull(label, "label"));
    }

    /** A command that runs {@code action} when chosen. */
    public static MenuItem of(String label, Runnable action) {
        return of(wrap(label), action);
    }

    /** A command whose label follows the UI language; see {@link I18nString}. */
    public static MenuItem of(I18nString label, Runnable action) {
        MenuItem item = new MenuItem(Kind.COMMAND, Objects.requireNonNull(label, "label"));
        item.action = Objects.requireNonNull(action, "action");
        return item;
    }

    /**
     * A checkable item showing a check mark for its state; choosing it flips the
     * state and calls {@code onToggle} with the new value.
     */
    public static MenuItem check(String label, boolean checked, Consumer<Boolean> onToggle) {
        return check(wrap(label), checked, onToggle);
    }

    /** A checkable item whose label follows the UI language. */
    public static MenuItem check(I18nString label, boolean checked, Consumer<Boolean> onToggle) {
        MenuItem item = new MenuItem(Kind.CHECK, Objects.requireNonNull(label, "label"));
        item.checked = checked;
        item.onToggle = Objects.requireNonNull(onToggle, "onToggle");
        return item;
    }

    /** An item that opens a nested {@code submenu} on hover / right-arrow. */
    public static MenuItem submenu(String label, Menu submenu) {
        return submenu(wrap(label), submenu);
    }

    /** A submenu item whose label follows the UI language. */
    public static MenuItem submenu(I18nString label, Menu submenu) {
        MenuItem item = new MenuItem(Kind.SUBMENU, Objects.requireNonNull(label, "label"));
        item.submenu = Objects.requireNonNull(submenu, "submenu");
        return item;
    }

    /** A non-interactive divider line between groups of items. */
    public static MenuItem separator() {
        return new MenuItem(Kind.SEPARATOR, null);
    }

    // ------------------------------------------------------------------- API

    /** The label as it currently reads, or {@code null} for a separator. */
    public String label() {
        return label == null ? null : label.get();
    }

    /** The localizable value behind {@link #label()}; {@code null} for a separator. */
    public I18nString labelSource() {
        return label;
    }

    /** Whether the item can be chosen; a disabled one still occupies a row. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Enables or disables the item. */
    public MenuItem setEnabled(boolean value) {
        this.enabled = value;
        return this;
    }

    /** @return whether a {@link #check} item is currently checked */
    public boolean isChecked() {
        return checked;
    }

    /** Sets a check item's state (does not fire {@code onToggle}). UI thread when displayed. */
    public MenuItem setChecked(boolean value) {
        this.checked = value;
        return this;
    }

    /** The keyboard shortcut shown at the right of the row and dispatched while closed, or null. */
    public Accelerator accelerator() {
        return accelerator;
    }

    /**
     * Declares the keyboard shortcut that runs this item while the menu is <b>closed</b>, and the
     * hint drawn right-aligned in its row. {@code null} removes it. Set it before the menu is
     * shown: an open cascade measured its rows against the hint it had then, and a change is not
     * picked up until it is rebuilt.
     *
     * @throws IllegalStateException on a submenu item or a separator. No platform gives a
     *         submenu a shortcut, because a shortcut runs a command and opening a submenu is not
     *         one; a shortcut on the <em>items inside</em> it is what was meant.
     */
    public MenuItem setAccelerator(Accelerator value) {
        if (kind == Kind.SUBMENU || kind == Kind.SEPARATOR) {
            throw new IllegalStateException("a " + kind + " item cannot carry an accelerator");
        }
        this.accelerator = value;
        return this;
    }

    /**
     * The access letter, uppercased, or {@code 0} when none is declared: the character
     * underlined in the row and the one that chooses this item while its menu is open.
     */
    public char mnemonic() {
        return mnemonic;
    }

    /**
     * Declares the access letter: it is underlined in the row, and pressing it while this item's
     * menu is open chooses the item (opens it, for a submenu). Matched case-insensitively, and
     * only against an <b>enabled</b> item: a disabled row is not reachable by its letter any
     * more than by its arrow keys.
     *
     * <p>The letter does not have to occur in the label; when it does not, nothing is underlined
     * and the key still works.
     *
     * @param value a letter or digit; {@code 0} removes the mnemonic
     * @throws IllegalArgumentException for any other character (there is nothing to underline and
     *         no key code to match)
     * @throws IllegalStateException on a separator
     */
    public MenuItem setMnemonic(char value) {
        if (kind == Kind.SEPARATOR) {
            throw new IllegalStateException("a separator cannot carry a mnemonic");
        }
        if (value != 0 && !Character.isLetterOrDigit(value)) {
            throw new IllegalArgumentException("a mnemonic must be a letter or a digit: " + value);
        }
        this.mnemonic = value == 0 ? 0 : Character.toUpperCase(value);
        return this;
    }

    /**
     * Index into {@link #label()} of the character to underline (the first case-insensitive
     * occurrence of the mnemonic), or {@code -1} when there is no mnemonic, no label, or the
     * letter does not occur in it. Recomputed from the current label, so it follows a label that
     * changed with the UI language.
     */
    public int mnemonicIndex() {
        String text = label();
        if (mnemonic == 0 || text == null) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.toUpperCase(text.charAt(i)) == mnemonic) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------- internal

    /**
     * @return whether a bare press of {@code key} (a {@link limn.input.Keys} code) chooses this
     *         item by its mnemonic. False for a disabled item and for a separator, which is what
     *         keeps the access letter honest about what it can reach.
     */
    boolean matchesMnemonic(int key) {
        return mnemonic != 0 && isSelectable() && key == mnemonic;
    }

    Kind kind() {
        return kind;
    }

    Menu submenu() {
        return submenu;
    }

    boolean isSeparator() {
        return kind == Kind.SEPARATOR;
    }

    boolean hasSubmenu() {
        return kind == Kind.SUBMENU && submenu != null && !submenu.isEmpty();
    }

    /** @return whether the pointer/keyboard can land on this item at all. */
    boolean isSelectable() {
        return kind != Kind.SEPARATOR && enabled;
    }

    /**
     * Runs the item's effect: a command runs its action; a check flips and
     * reports its state. Submenus/separators do nothing here (the presenter
     * opens the submenu). Never called for a disabled item.
     */
    void activate() {
        switch (kind) {
            case COMMAND -> action.run();
            case CHECK -> {
                checked = !checked;
                onToggle.accept(checked);
            }
            default -> {
            }
        }
    }
}
