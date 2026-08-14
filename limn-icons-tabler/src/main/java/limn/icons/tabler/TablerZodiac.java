package limn.icons.tabler;

/**
 * Tabler's <b>Zodiac</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerZodiac implements TablerIcon {

    ZODIAC_AQUARIUS("zodiac-aquarius"),
    ZODIAC_ARIES("zodiac-aries"),
    ZODIAC_CANCER("zodiac-cancer"),
    ZODIAC_CAPRICORN("zodiac-capricorn"),
    ZODIAC_GEMINI("zodiac-gemini"),
    ZODIAC_LEO("zodiac-leo"),
    ZODIAC_LIBRA("zodiac-libra"),
    ZODIAC_PISCES("zodiac-pisces"),
    ZODIAC_SAGITTARIUS("zodiac-sagittarius"),
    ZODIAC_SCORPIO("zodiac-scorpio"),
    ZODIAC_TAURUS("zodiac-taurus"),
    ZODIAC_VIRGO("zodiac-virgo");

    private final String iconName;

    TablerZodiac(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
