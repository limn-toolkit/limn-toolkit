package limn.components;

import limn.concurrent.Ui;
import limn.scene.Change;
import limn.scene.EditCommand;

import limn.i18n.I18nString;
import org.jspecify.annotations.Nullable;

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

    /**
     * What an item is to the system, beyond its label: one of the three every application has and
     * a system may keep in a menu of its own, or one of the six editing commands.
     *
     * <p><b>{@link #ABOUT}, {@link #SETTINGS} and {@link #QUIT}</b> are written where the item
     * lives in a window's menu bar (Help, Edit, File) and change nothing there. Where the system
     * keeps the menu bar outside the window, which is macOS, the item leaves that menu: About and
     * Settings are shown in the application's own menu under the system's label and accelerator
     * and run the item's action, and Quit is the system's, which asks
     * {@link limn.backend.Backend#requestQuit()} and does not run the item.
     *
     * <p><b>The editing roles</b> make the item the command: it has no action of its own, it is
     * enabled while the widget that has the keyboard can do the command, and choosing it does the
     * command there, on every system. See {@link #of(Role)}.
     */
    public enum Role {
        /** An ordinary item. */
        NONE(null),
        /** Shows what the application is. */
        ABOUT(null),
        /** Opens the application's settings. */
        SETTINGS(null),
        /** Ends the application. */
        QUIT(null),
        /** {@link EditCommand#UNDO}. */
        UNDO(EditCommand.UNDO),
        /** {@link EditCommand#REDO}. */
        REDO(EditCommand.REDO),
        /** {@link EditCommand#CUT}. */
        CUT(EditCommand.CUT),
        /** {@link EditCommand#COPY}. */
        COPY(EditCommand.COPY),
        /** {@link EditCommand#PASTE}. */
        PASTE(EditCommand.PASTE),
        /** {@link EditCommand#SELECT_ALL}. */
        SELECT_ALL(EditCommand.SELECT_ALL);

        private final @Nullable EditCommand command;

        Role(@Nullable EditCommand command) {
            this.command = command;
        }

        /** @return the editing command this role is, or {@code null} for the other four */
        public @Nullable EditCommand editCommand() {
            return command;
        }
    }

    private final Kind kind;
    private final I18nString label;
    private Runnable action = () -> { };
    private Consumer<Boolean> onToggle = checked -> { };
    private Menu submenu;
    private boolean enabled = true;
    private limn.graphics.@Nullable Icon icon;
    private boolean checked;
    private Accelerator accelerator;
    /** The access letter, uppercased; {@code 0} when none is declared. */
    private char mnemonic;
    private Role role = Role.NONE;

    /**
     * A key of the width identity is, minted once per item and never reused.
     *
     * <p>A menu is a model rather than a widget tree, so a menu row reaches the accessible tree as
     * a synthetic child of the surface that draws it, keyed by its owner. The obvious substitute
     * for this counter, {@link System#identityHashCode}, is thirty-two bits wide, and a collision
     * there is not a slow lookup: an identifier is the whole of identity on every platform, so two
     * rows would become one element on all three at once.
     */
    private static long serials;

    private final long serial;

    private MenuItem(Kind kind, I18nString label) {
        this.kind = kind;
        this.label = label;
        this.serial = ++serials;
    }

    /** This item's stable key, for whoever has to name it to something outside the process. */
    long serial() {
        return serial;
    }

    private static I18nString wrap(String label) {
        return I18nString.literal(Objects.requireNonNull(label, "label"));
    }

    /** A command that runs {@code action} when chosen. */
    public static MenuItem of(String label, Runnable action) {
        return of(wrap(label), action);
    }

    /** A command whose label follows the UI language; see {@link I18nString}. UI thread. */
    public static MenuItem of(I18nString label, Runnable action) {
        Ui.checkUiThread();
        MenuItem item = new MenuItem(Kind.COMMAND, Objects.requireNonNull(label, "label"));
        item.action = Objects.requireNonNull(action, "action");
        return item;
    }

    /**
     * The item of an editing role, whole: the command's name in the application's language, the
     * chord the platform gives it, and no action, the role being the action. The label and the
     * accelerator may be changed after.
     *
     * <p>Redo is on Shift and the command key with Z on macOS and on Control with Y elsewhere,
     * where each platform's users look for it; a text field takes both everywhere.
     *
     * @param role one of the six editing roles
     * @throws IllegalArgumentException for a role that is not an editing command, whose item
     *         needs a label and an action: {@link #of(String, Runnable)} and {@link #setRole}
     */
    public static MenuItem of(Role role) {
        Ui.checkUiThread();
        EditCommand command = Objects.requireNonNull(role, "role").editCommand();
        if (command == null) {
            throw new IllegalArgumentException(role + " is not an editing command; make the item and give it the role");
        }
        MenuItem item = new MenuItem(Kind.COMMAND, switch (command) {
            case UNDO -> ComponentStrings.EDIT_UNDO;
            case REDO -> ComponentStrings.EDIT_REDO;
            case CUT -> ComponentStrings.TEXT_MENU_CUT;
            case COPY -> ComponentStrings.TEXT_MENU_COPY;
            case PASTE -> ComponentStrings.TEXT_MENU_PASTE;
            case SELECT_ALL -> ComponentStrings.TEXT_MENU_SELECT_ALL;
        });
        item.role = role;
        item.accelerator = switch (command) {
            case UNDO -> Accelerator.command(limn.input.Keys.Z);
            case REDO -> Accelerator.commandModifier() == limn.input.Keys.MOD_SUPER
                    ? Accelerator.command(limn.input.Keys.Z, limn.input.Keys.MOD_SHIFT)
                    : Accelerator.command(limn.input.Keys.Y);
            case CUT -> Accelerator.command(limn.input.Keys.X);
            case COPY -> Accelerator.command(limn.input.Keys.C);
            case PASTE -> Accelerator.command(limn.input.Keys.V);
            case SELECT_ALL -> Accelerator.command(limn.input.Keys.A);
        };
        return item;
    }

    /**
     * A checkable item showing a check mark for its state; choosing it flips the
     * state and calls {@code onToggle} with the new value.
     */
    public static MenuItem check(String label, boolean checked, Consumer<Boolean> onToggle) {
        return check(wrap(label), checked, onToggle);
    }

    /** A checkable item whose label follows the UI language. UI thread. */
    public static MenuItem check(I18nString label, boolean checked, Consumer<Boolean> onToggle) {
        Ui.checkUiThread();
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

    /**
     * The label as it currently reads, or {@code null} for a separator: its text in the language in
     * effect, an access letter's {@code &} mark taken out ("&amp;Open" reads "Open").
     */
    public @Nullable String label() {
        return label == null ? null : marked().text();
    }

    /** The label's text read for its mark, memoized on the text it was read from. */
    private limn.i18n.internal.AccessMarks.Parsed marked() {
        String raw = label.get();
        if (!raw.equals(markedFrom)) {
            markedFrom = raw;
            markedText = AccessLetters.parse(raw);
            markedWitness++;
        }
        return markedText;
    }

    private String markedFrom;
    private limn.i18n.internal.AccessMarks.Parsed markedText;
    /** Changes whenever {@link #label()} does: what the row's accessible name is diffed by. */
    private long markedWitness;
    private char keyLetter;
    private @Nullable String key;

    /**
     * Read by the menu's accessible row, which names it by the words and not by the source, whose
     * mark a reader would hear spelled out: {@link #label()}, held until it changes.
     */
    @Nullable String labelForReader() {
        return label == null ? null : marked().text();
    }

    /** @return a value that changes whenever {@link #labelForReader()} does */
    long labelWitness() {
        if (label != null) {
            marked();
        }
        return markedWitness;
    }

    /**
     * The access letter as a reader is told of it: in an open menu it is pressed alone, so "L"
     * and not "Alt+L", as a native menu item's access key reads. {@code null} for none, and on
     * macOS, which has no access letters. Held until the letter changes.
     */
    @Nullable String accessKey() {
        char letter = mnemonic();
        if (letter != keyLetter) {
            keyLetter = letter;
            key = letter == 0 ? null : String.valueOf(letter);
        }
        return AccessLetters.onThisSystem() ? key : null;
    }

    /** The localizable value behind {@link #label()}; {@code null} for a separator. */
    public @Nullable I18nString labelSource() {
        return label;
    }

    /** Whether the item can be chosen; a disabled one still occupies a row. */
    public boolean isEnabled() {
        EditCommand command = role.editCommand();
        return command == null ? enabled : enabled && EditTarget.can(command);
    }

    /** What {@link #setEnabled} said, whatever the editing command of the item's role can do now. */
    boolean isEnabledByItsOwnFlag() {
        return enabled;
    }

    /** @return what the item is to the system; {@link Role#NONE} unless {@link #setRole} said */
    public Role role() {
        return role;
    }

    /**
     * Says what the item is to the system; see {@link Role}.
     *
     * @throws IllegalStateException on a separator, a submenu or an item that checks, none of
     *         which the system has a place for; and for an editing role on an item made with an
     *         action, the role being the action: make it with {@link #of(Role)}
     */
    public MenuItem setRole(Role value) {
        Ui.checkUiThread();
        Objects.requireNonNull(value, "role");
        if (value == role) {
            return this;
        }
        if (kind != Kind.COMMAND) {
            throw new IllegalStateException("a " + kind + " item cannot carry a role");
        }
        if (value.editCommand() != null || role.editCommand() != null) {
            throw new IllegalStateException(
                    "an editing role is the item's action; make the item with MenuItem.of(Role)");
        }
        this.role = value;
        return this;
    }

    /**
     * @return the picture shown before the label, or {@code null}
     */
    public limn.graphics.@Nullable Icon icon() {
        return icon;
    }

    /**
     * Sets a picture shown before the label, in a menu the toolkit draws and in one the system
     * draws, a tray's among them. A menu with an icon on any item keeps the same room on every
     * item, so the labels stay in line. A tintable icon takes the label's colour.
     *
     * @param value the icon, or {@code null} for none
     * @return this item
     */
    public MenuItem setIcon(limn.graphics.@Nullable Icon value) {
        Ui.checkUiThread();
        this.icon = value;
        return this;
    }

    /** Enables or disables the item. */
    public MenuItem setEnabled(boolean value) {
        Ui.checkUiThread();
        this.enabled = value;
        return this;
    }

    /** @return whether a {@link #check} item is currently checked */
    public boolean isChecked() {
        return checked;
    }

    /** Sets a check item's state (does not fire {@code onToggle}). UI thread when displayed. */
    public MenuItem setChecked(boolean value) {
        Ui.checkUiThread();
        this.checked = value;
        return this;
    }

    /** The keyboard shortcut shown at the right of the row and dispatched while closed, or null. */
    public @Nullable Accelerator accelerator() {
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
    public MenuItem setAccelerator(@Nullable Accelerator value) {
        Ui.checkUiThread();
        if (kind == Kind.SUBMENU || kind == Kind.SEPARATOR) {
            throw new IllegalStateException("a " + kind + " item cannot carry an accelerator");
        }
        this.accelerator = value;
        return this;
    }

    /**
     * The access letter, uppercased, or {@code 0} when none is declared: the character
     * underlined in the row and the one that chooses this item while its menu is open. A letter the
     * label marks with {@code &} ("&amp;Open") wins over one given to {@link #setMnemonic}, the
     * translation being the one that knows the word.
     */
    public char mnemonic() {
        if (label != null) {
            limn.i18n.internal.AccessMarks.Parsed parsed = marked();
            if (parsed.letter() != 0) {
                return parsed.letter();
            }
        }
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
        Ui.checkUiThread();
        if (kind == Kind.SEPARATOR) {
            throw new IllegalStateException("a separator cannot carry a mnemonic");
        }
        this.mnemonic = MenuInk.checkedMnemonic(value);
        return this;
    }

    /**
     * Index into {@link #label()} of the character to underline: the one the label marks, else the
     * first case-insensitive occurrence of a letter given to {@link #setMnemonic}; {@code -1}
     * when there is no letter, no label, or the letter does not occur in it. Recomputed from the
     * current label, so it follows a label that changed with the UI language.
     */
    public int mnemonicIndex() {
        if (label == null) {
            return -1;
        }
        limn.i18n.internal.AccessMarks.Parsed parsed = marked();
        return parsed.index() >= 0 ? parsed.index() : MenuInk.mnemonicIndex(parsed.text(), mnemonic);
    }

    // -------------------------------------------------------------- internal

    /**
     * @return whether a bare press of {@code key} (a {@link limn.input.Keys} code) chooses this
     *         item by its mnemonic. False for a disabled item and for a separator, which is what
     *         keeps the access letter honest about what it can reach.
     */
    boolean matchesMnemonic(int key, int codePoint) {
        return isSelectable() && AccessLetters.matches(mnemonic(), key, codePoint);
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
     *
     * <p>Package-private, and every caller is an input handler -- a click on a row, Enter or
     * Space on the highlight, and an accelerator chord -- so the origin they hand over is
     * {@code USER} and nothing else, and the item's action and {@code onToggle} are the
     * application's response to it. A menu item is not a widget and announces nothing; what a
     * watcher can hear is the surface that presents it. Reading this method as a caller's verb
     * on the strength of its name would leave every menu in the toolkit clicking and doing
     * nothing, which is why the origin is asserted here rather than assumed.
     */
    void activate(Change.Origin origin) {
        if (origin != Change.Origin.USER) {
            return; // the handlers answer the user; no other origin reaches here today
        }
        switch (kind) {
            case COMMAND -> {
                EditCommand command = role.editCommand();
                if (command == null) {
                    action.run();
                } else {
                    EditTarget.perform(command);
                }
            }
            case CHECK -> {
                checked = !checked;
                onToggle.accept(checked);
            }
            default -> {
            }
        }
    }
}
