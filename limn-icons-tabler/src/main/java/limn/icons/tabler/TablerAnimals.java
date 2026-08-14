package limn.icons.tabler;

/**
 * Tabler's <b>Animals</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerAnimals implements TablerIcon {

    BAT("bat"),
    CAT("cat"),
    DEER("deer"),
    DOG("dog"),
    DRAGON("dragon"),
    FISH("fish"),
    FISH_BONE("fish-bone"),
    FISH_HOOK("fish-hook"),
    FISH_HOOK_OFF("fish-hook-off"),
    FISH_OFF("fish-off"),
    HORSE("horse"),
    HORSESHOE("horseshoe"),
    PAW("paw"),
    PAW_OFF("paw-off"),
    PIG("pig"),
    PIG_MONEY("pig-money"),
    PIG_OFF("pig-off"),
    SPIDER("spider");

    private final String iconName;

    TablerAnimals(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
