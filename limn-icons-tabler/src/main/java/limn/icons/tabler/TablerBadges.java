package limn.icons.tabler;

/**
 * Tabler's <b>Badges</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerBadges implements TablerIcon {

    AWARD("award"),
    AWARD_OFF("award-off"),
    BADGE("badge"),
    BADGE_2K("badge-2k"),
    BADGE_3D("badge-3d"),
    BADGE_3K("badge-3k"),
    BADGE_4K("badge-4k"),
    BADGE_5K("badge-5k"),
    BADGE_8K("badge-8k"),
    BADGE_AD("badge-ad"),
    BADGE_AD_OFF("badge-ad-off"),
    BADGE_AR("badge-ar"),
    BADGE_CC("badge-cc"),
    BADGE_HD("badge-hd"),
    BADGE_OFF("badge-off"),
    BADGE_SD("badge-sd"),
    BADGE_TM("badge-tm"),
    BADGE_VO("badge-vo"),
    BADGE_VR("badge-vr"),
    BADGE_WC("badge-wc"),
    BADGES("badges"),
    BADGES_OFF("badges-off");

    private final String iconName;

    TablerBadges(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
