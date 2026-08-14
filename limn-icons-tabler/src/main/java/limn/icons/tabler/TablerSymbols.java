package limn.icons.tabler;

/**
 * Tabler's <b>Symbols</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerSymbols implements TablerIcon {

    ANKH("ankh"),
    BIOHAZARD("biohazard"),
    BIOHAZARD_OFF("biohazard-off"),
    CE("ce"),
    CE_OFF("ce-off"),
    COMMAND("command"),
    COMMAND_OFF("command-off"),
    CONFUCIUS("confucius"),
    COPYLEFT("copyleft"),
    COPYLEFT_OFF("copyleft-off"),
    COPYRIGHT("copyright"),
    COPYRIGHT_OFF("copyright-off"),
    CROSS("cross"),
    CROSS_OFF("cross-off"),
    FISH_CHRISTIANITY("fish-christianity"),
    MARS("mars"),
    MENORAH("menorah"),
    OM("om"),
    OPTION("option"),
    PARKING_CIRCLE("parking-circle"),
    PEACE("peace"),
    RADIOACTIVE("radioactive"),
    RADIOACTIVE_OFF("radioactive-off"),
    RATING_12_PLUS("rating-12-plus"),
    RATING_14_PLUS("rating-14-plus"),
    RATING_16_PLUS("rating-16-plus"),
    RATING_18_PLUS("rating-18-plus"),
    RATING_21_PLUS("rating-21-plus"),
    RECYCLE("recycle"),
    RECYCLE_OFF("recycle-off"),
    REGISTERED("registered"),
    RIBBON_HEALTH("ribbon-health"),
    SERVICEMARK("servicemark"),
    TORII("torii"),
    TRADEMARK("trademark"),
    VENUS("venus"),
    YIN_YANG("yin-yang");

    private final String iconName;

    TablerSymbols(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
