package limn.icons.tabler;

/**
 * Tabler's <b>Laundry</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerLaundry implements TablerIcon {

    BLEACH("bleach"),
    BLEACH_CHLORINE("bleach-chlorine"),
    BLEACH_NO_CHLORINE("bleach-no-chlorine"),
    BLEACH_OFF("bleach-off"),
    IRONING("ironing"),
    IRONING_1("ironing-1"),
    IRONING_2("ironing-2"),
    IRONING_3("ironing-3"),
    IRONING_OFF("ironing-off"),
    IRONING_STEAM("ironing-steam"),
    IRONING_STEAM_OFF("ironing-steam-off"),
    WASH("wash"),
    WASH_DRY("wash-dry"),
    WASH_DRY_1("wash-dry-1"),
    WASH_DRY_2("wash-dry-2"),
    WASH_DRY_3("wash-dry-3"),
    WASH_DRY_A("wash-dry-a"),
    WASH_DRY_DIP("wash-dry-dip"),
    WASH_DRY_F("wash-dry-f"),
    WASH_DRY_FLAT("wash-dry-flat"),
    WASH_DRY_HANG("wash-dry-hang"),
    WASH_DRY_OFF("wash-dry-off"),
    WASH_DRY_P("wash-dry-p"),
    WASH_DRY_SHADE("wash-dry-shade"),
    WASH_DRY_W("wash-dry-w"),
    WASH_DRYCLEAN("wash-dryclean"),
    WASH_DRYCLEAN_OFF("wash-dryclean-off"),
    WASH_ECO("wash-eco"),
    WASH_GENTLE("wash-gentle"),
    WASH_HAND("wash-hand"),
    WASH_OFF("wash-off"),
    WASH_PRESS("wash-press"),
    WASH_TEMPERATURE_1("wash-temperature-1"),
    WASH_TEMPERATURE_2("wash-temperature-2"),
    WASH_TEMPERATURE_3("wash-temperature-3"),
    WASH_TEMPERATURE_4("wash-temperature-4"),
    WASH_TEMPERATURE_5("wash-temperature-5"),
    WASH_TEMPERATURE_6("wash-temperature-6"),
    WASH_TUMBLE_DRY("wash-tumble-dry"),
    WASH_TUMBLE_OFF("wash-tumble-off");

    private final String iconName;

    TablerLaundry(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
