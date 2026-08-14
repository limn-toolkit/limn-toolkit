package limn.icons.tabler;

/**
 * Tabler's <b>Gender</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerGender implements TablerIcon {

    GENDER_AGENDER("gender-agender"),
    GENDER_ANDROGYNE("gender-androgyne"),
    GENDER_BIGENDER("gender-bigender"),
    GENDER_DEMIBOY("gender-demiboy"),
    GENDER_DEMIGIRL("gender-demigirl"),
    GENDER_EPICENE("gender-epicene"),
    GENDER_FEMALE("gender-female"),
    GENDER_FEMME("gender-femme"),
    GENDER_GENDERFLUID("gender-genderfluid"),
    GENDER_GENDERLESS("gender-genderless"),
    GENDER_GENDERQUEER("gender-genderqueer"),
    GENDER_HERMAPHRODITE("gender-hermaphrodite"),
    GENDER_INTERGENDER("gender-intergender"),
    GENDER_MALE("gender-male"),
    GENDER_NEUTROIS("gender-neutrois"),
    GENDER_THIRD("gender-third"),
    GENDER_TRANSGENDER("gender-transgender"),
    GENDER_TRAVESTI("gender-travesti");

    private final String iconName;

    TablerGender(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
