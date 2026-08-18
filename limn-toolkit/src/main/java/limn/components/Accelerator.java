package limn.components;

import limn.input.Keys;

import java.util.Locale;

/**
 * A keyboard shortcut for a {@link MenuItem}: one key plus the modifier mask that must be
 * held with it. Immutable, comparable by value, and cheap enough to build inline.
 *
 * <pre>{@code
 * MenuItem.of("Save", this::save).setAccelerator(Accelerator.command(Keys.S));       // Cmd+S / Ctrl+S
 * MenuItem.of("Save As…", this::saveAs).setAccelerator(
 *         Accelerator.command(Keys.S, Keys.MOD_SHIFT));                              // + Shift
 * MenuItem.of("Refresh", this::refresh).setAccelerator(Accelerator.of(Keys.F5));     // no modifier
 * }</pre>
 *
 * <p><b>{@link #command} is the portable form and the one to reach for.</b> It resolves to
 * the Command key on macOS and to Control everywhere else, which is the difference between a
 * shortcut that reads as native on both and one that fights the platform's own bindings.
 * {@link #of(int, int)} takes the mask literally and is for the rare shortcut that means a
 * specific physical modifier on every platform.
 *
 * <p>The mask is matched <b>exactly</b>: {@code Ctrl+S} does not fire on {@code Ctrl+Shift+S},
 * so the two can be different commands. Only the four {@code Keys.MOD_*} bits are legal.
 *
 * <p><b>{@link #display()} follows the platform</b>: {@code ⇧⌘S} on macOS, where a shortcut is
 * written as an unbroken run of symbols in the platform's own order, and {@code Ctrl+Shift+S}
 * everywhere else, where the words are the convention. Modifier names are untranslated on both,
 * as they are on every platform's own menus.
 *
 * <p><b>The symbols are not free, and the reason is worth knowing before removing them.</b> No
 * font in the ordinary UI stack has them (not Roboto, and not the Noto faces the toolkit falls
 * back to for CJK and emoji), so the toolkit ships a small face built for exactly this set. A
 * build stripped of that face draws a .notdef box where each modifier belongs, which is worse
 * than the words it replaced.
 */
public record Accelerator(int key, int modifiers) {

    /** Every modifier bit an accelerator may carry; anything else is rejected. */
    private static final int LEGAL_MODIFIERS =
            Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER;

    /**
     * The one platform test in the toolkit's menu system. Everything per-platform an
     * accelerator does (which modifier {@link #command} means, and how {@link #display}
     * spells the mask) is a function of this field, so there is exactly one place to change
     * when a platform is added and no second answer to drift from it.
     */
    private static final boolean MAC = isMac(System.getProperty("os.name", ""));

    /**
     * @param key       a {@link Keys} key code: the physical key, so {@code Keys.S} and
     *                  {@code 'S'} are the same accelerator
     * @param modifiers a mask of {@code Keys.MOD_*} bits; {@code 0} means the bare key
     * @throws IllegalArgumentException for a non-positive key, for a modifier key used as the
     *         key (a chord whose key is Shift could never complete), or for a bit outside the
     *         four {@code Keys.MOD_*} values
     */
    public Accelerator {
        if (key <= 0) {
            throw new IllegalArgumentException("key must be a positive Keys code: " + key);
        }
        if (key >= Keys.LEFT_SHIFT && key <= Keys.RIGHT_SUPER) {
            throw new IllegalArgumentException(
                    "a modifier key cannot be an accelerator's key: " + key);
        }
        if ((modifiers & ~LEGAL_MODIFIERS) != 0) {
            throw new IllegalArgumentException("unknown modifier bits: " + modifiers);
        }
    }

    /** The bare key, with no modifier: a function key, typically. */
    public static Accelerator of(int key) {
        return new Accelerator(key, 0);
    }

    /** The key with an explicit, literal {@code Keys.MOD_*} mask on every platform. */
    public static Accelerator of(int key, int modifiers) {
        return new Accelerator(key, modifiers);
    }

    /** The key with the platform's command modifier: Command on macOS, Control elsewhere. */
    public static Accelerator command(int key) {
        return new Accelerator(key, commandModifier());
    }

    /** {@link #command(int)} plus further {@code Keys.MOD_*} bits (typically {@code MOD_SHIFT}). */
    public static Accelerator command(int key, int extraModifiers) {
        return new Accelerator(key, commandModifier() | extraModifiers);
    }

    /**
     * @return {@link Keys#MOD_SUPER} on macOS, {@link Keys#MOD_CONTROL} elsewhere: the bit an
     *         application needs when it tests a raw {@link limn.scene.event.KeyEvent} mask itself
     *         and wants the same answer this class gives
     */
    public static int commandModifier() {
        return MAC ? Keys.MOD_SUPER : Keys.MOD_CONTROL;
    }

    /**
     * Whether a key press with this exact modifier mask is this accelerator. Exact, not
     * "contains": an extra modifier makes it a different chord.
     */
    public boolean matches(int pressedKey, int pressedModifiers) {
        return pressedKey == key && pressedModifiers == modifiers;
    }

    /**
     * The hint a menu row shows, right-aligned: {@code "⇧⌘S"} on macOS, {@code "Ctrl+Shift+S"}
     * elsewhere. Never empty and never null.
     *
     * <p>Both forms put the modifiers in their own platform's order, which is not the same order:
     * macOS is Control, Option, Shift, Command and writes them with no separator at all, because
     * the symbols already read as separate keys. The rest write Control, Alt, Shift, Meta joined
     * with {@code +}.
     */
    public String display() {
        return display(MAC);
    }

    /** {@link #display()} for a chosen platform, the seam both branches are tested through. */
    String display(boolean mac) {
        StringBuilder out = new StringBuilder();
        if (mac) {
            // No separator: ⌃⌥⇧⌘ are one glyph each and a '+' between them is what a menu
            // showing this on a Mac never has.
            if ((modifiers & Keys.MOD_CONTROL) != 0) {
                out.append('\u2303');
            }
            if ((modifiers & Keys.MOD_ALT) != 0) {
                out.append('\u2325');
            }
            if ((modifiers & Keys.MOD_SHIFT) != 0) {
                out.append('\u21E7');
            }
            if ((modifiers & Keys.MOD_SUPER) != 0) {
                out.append('\u2318');
            }
            return out.append(keyName(key, true)).toString();
        }
        if ((modifiers & Keys.MOD_CONTROL) != 0) {
            out.append("Ctrl+");
        }
        if ((modifiers & Keys.MOD_ALT) != 0) {
            out.append("Alt+");
        }
        if ((modifiers & Keys.MOD_SHIFT) != 0) {
            out.append("Shift+");
        }
        if ((modifiers & Keys.MOD_SUPER) != 0) {
            out.append("Meta+");
        }
        return out.append(keyName(key, false)).toString();
    }

    /** The platform test itself, as a pure function of {@code os.name} so both branches are testable. */
    static boolean isMac(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * The display name of a key. Printable ASCII names itself (uppercased), so letters, digits
     * and punctuation need no table; everything else is spelled out in the short form menus use
     * ({@code Esc}, {@code Del}, {@code PgUp}). An unrecognized code falls back to
     * {@code "Key<code>"} rather than to an empty string, so a wrong constant is visible in the
     * menu instead of silently rendering as a bare modifier.
     */
    private static String keyName(int key, boolean mac) {
        if (key > 32 && key < 127) {
            return String.valueOf(Character.toUpperCase((char) key));
        }
        if (key >= Keys.F1 && key <= Keys.F12) {
            return "F" + (key - Keys.F1 + 1);
        }
        // Insert has no symbol on any platform and is a word on both: the one key in this
        // table where the two branches agree by having nothing else to say.
        if (mac) {
            return switch (key) {
                case Keys.SPACE -> "\u2423";
                case Keys.ESCAPE -> "\u238B";
                case Keys.ENTER -> "\u23CE";
                case Keys.TAB -> "\u21E5";
                case Keys.BACKSPACE -> "\u232B";
                case Keys.INSERT -> "Ins";
                case Keys.DELETE -> "\u2326";
                case Keys.RIGHT -> "\u2192";
                case Keys.LEFT -> "\u2190";
                case Keys.DOWN -> "\u2193";
                case Keys.UP -> "\u2191";
                case Keys.PAGE_UP -> "\u21DE";
                case Keys.PAGE_DOWN -> "\u21DF";
                case Keys.HOME -> "\u2196";
                case Keys.END -> "\u2198";
                default -> "Key" + key;
            };
        }
        return switch (key) {
            case Keys.SPACE -> "Space";
            case Keys.ESCAPE -> "Esc";
            case Keys.ENTER -> "Enter";
            case Keys.TAB -> "Tab";
            case Keys.BACKSPACE -> "Backspace";
            case Keys.INSERT -> "Ins";
            case Keys.DELETE -> "Del";
            case Keys.RIGHT -> "Right";
            case Keys.LEFT -> "Left";
            case Keys.DOWN -> "Down";
            case Keys.UP -> "Up";
            case Keys.PAGE_UP -> "PgUp";
            case Keys.PAGE_DOWN -> "PgDn";
            case Keys.HOME -> "Home";
            case Keys.END -> "End";
            default -> "Key" + key;
        };
    }
}
