package limn.icons.tabler;

import limn.graphics.Icon;

/**
 * One icon of the Tabler set, named at compile time. Every constant of every
 * {@code Tabler*} enum implements this, so an API that takes an icon takes this type and
 * a name that does not exist is a compile error rather than a blank button at runtime.
 *
 * <p>The set is split across one enum per upstream category, and not by preference: a
 * class initialiser is capped at 64KB of bytecode and an enum constant costs roughly
 * twenty, so a single enum over the whole catalogue does not compile. Reach for
 * {@link Tabler#outline} when the name is only known at runtime.
 */
public interface TablerIcon {

    /** The upstream name, lower-case and hyphenated: {@code "arrow-up"}, {@code "trash"}. */
    String iconName();

    /**
     * The outline drawing, shared: every call for one name answers the same instance, so a
     * hundred buttons carrying the same icon rasterize it once and hold one bitmap between
     * them. Safe from any thread; the rasterizing it defers is the UI thread's.
     */
    default Icon icon() {
        return Tabler.outline(iconName());
    }

    /**
     * Whether this icon has a filled twin. Roughly a fifth of the set does, and asking is
     * the only honest way to find out; a name is not enough to tell.
     */
    default boolean hasFilled() {
        return Tabler.hasFilled(iconName());
    }

    /**
     * The filled drawing, shared.
     *
     * @throws java.util.NoSuchElementException if this icon has no filled twin; ask
     *                                          {@link #hasFilled()} first
     */
    default Icon filled() {
        return Tabler.filled(iconName());
    }
}
