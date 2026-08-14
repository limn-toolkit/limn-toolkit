package limn.icons.tabler;

/**
 * Tabler's <b>Weather</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerWeather implements TablerIcon {

    CLOUD("cloud"),
    CLOUD_BOLT("cloud-bolt"),
    CLOUD_CANCEL("cloud-cancel"),
    CLOUD_CHECK("cloud-check"),
    CLOUD_CODE("cloud-code"),
    CLOUD_COG("cloud-cog"),
    CLOUD_DOLLAR("cloud-dollar"),
    CLOUD_DOWN("cloud-down"),
    CLOUD_EXCLAMATION("cloud-exclamation"),
    CLOUD_FOG("cloud-fog"),
    CLOUD_HEART("cloud-heart"),
    CLOUD_MINUS("cloud-minus"),
    CLOUD_OFF("cloud-off"),
    CLOUD_PAUSE("cloud-pause"),
    CLOUD_PIN("cloud-pin"),
    CLOUD_PLUS("cloud-plus"),
    CLOUD_QUESTION("cloud-question"),
    CLOUD_RAIN("cloud-rain"),
    CLOUD_SEARCH("cloud-search"),
    CLOUD_SHARE("cloud-share"),
    CLOUD_SNOW("cloud-snow"),
    CLOUD_STAR("cloud-star"),
    CLOUD_STORM("cloud-storm"),
    CLOUD_UP("cloud-up"),
    CLOUD_X("cloud-x"),
    COMET("comet"),
    FLARE("flare"),
    FLOOD("flood"),
    HAZE("haze"),
    MIST("mist"),
    MIST_OFF("mist-off"),
    MOON("moon"),
    MOON_2("moon-2"),
    MOON_OFF("moon-off"),
    MOON_STARS("moon-stars"),
    RAINBOW("rainbow"),
    RAINBOW_OFF("rainbow-off"),
    SNOWFLAKE("snowflake"),
    SNOWFLAKE_OFF("snowflake-off"),
    STORM("storm"),
    STORM_OFF("storm-off"),
    SUN("sun"),
    SUN_HIGH("sun-high"),
    SUN_LOW("sun-low"),
    SUN_MOON("sun-moon"),
    SUN_OFF("sun-off"),
    SUN_WIND("sun-wind"),
    SUNRISE("sunrise"),
    SUNSET("sunset"),
    SUNSET_2("sunset-2"),
    TEMPERATURE("temperature"),
    TEMPERATURE_CELSIUS("temperature-celsius"),
    TEMPERATURE_FAHRENHEIT("temperature-fahrenheit"),
    TEMPERATURE_MINUS("temperature-minus"),
    TEMPERATURE_OFF("temperature-off"),
    TEMPERATURE_PLUS("temperature-plus"),
    TORNADO("tornado"),
    UV_INDEX("uv-index"),
    WHIRL("whirl"),
    WIND("wind"),
    WIND_OFF("wind-off");

    private final String iconName;

    TablerWeather(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
