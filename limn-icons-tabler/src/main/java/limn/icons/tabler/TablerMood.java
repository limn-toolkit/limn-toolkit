package limn.icons.tabler;

/**
 * Tabler's <b>Mood</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerMood implements TablerIcon {

    MOOD_ANGRY("mood-angry"),
    MOOD_ANNOYED("mood-annoyed"),
    MOOD_ANNOYED_2("mood-annoyed-2"),
    MOOD_BITCOIN("mood-bitcoin"),
    MOOD_BOY("mood-boy"),
    MOOD_CHECK("mood-check"),
    MOOD_COG("mood-cog"),
    MOOD_CONFUSED("mood-confused"),
    MOOD_CRAZY_HAPPY("mood-crazy-happy"),
    MOOD_CRY("mood-cry"),
    MOOD_DOLLAR("mood-dollar"),
    MOOD_EDIT("mood-edit"),
    MOOD_EMPTY("mood-empty"),
    MOOD_HAPPY("mood-happy"),
    MOOD_HEART("mood-heart"),
    MOOD_KID("mood-kid"),
    MOOD_LOOK_DOWN("mood-look-down"),
    MOOD_LOOK_LEFT("mood-look-left"),
    MOOD_LOOK_RIGHT("mood-look-right"),
    MOOD_LOOK_UP("mood-look-up"),
    MOOD_MINUS("mood-minus"),
    MOOD_NERD("mood-nerd"),
    MOOD_NERVOUS("mood-nervous"),
    MOOD_NEUTRAL("mood-neutral"),
    MOOD_OFF("mood-off"),
    MOOD_PIN("mood-pin"),
    MOOD_PLUS("mood-plus"),
    MOOD_PUZZLED("mood-puzzled"),
    MOOD_SAD("mood-sad"),
    MOOD_SAD_2("mood-sad-2"),
    MOOD_SAD_DIZZY("mood-sad-dizzy"),
    MOOD_SAD_SQUINT("mood-sad-squint"),
    MOOD_SEARCH("mood-search"),
    MOOD_SHARE("mood-share"),
    MOOD_SICK("mood-sick"),
    MOOD_SILENCE("mood-silence"),
    MOOD_SING("mood-sing"),
    MOOD_SMILE("mood-smile"),
    MOOD_SMILE_BEAM("mood-smile-beam"),
    MOOD_SMILE_DIZZY("mood-smile-dizzy"),
    MOOD_SPARK("mood-spark"),
    MOOD_SURPRISED("mood-surprised"),
    MOOD_TONGUE("mood-tongue"),
    MOOD_TONGUE_WINK("mood-tongue-wink"),
    MOOD_TONGUE_WINK_2("mood-tongue-wink-2"),
    MOOD_UNAMUSED("mood-unamused"),
    MOOD_UP("mood-up"),
    MOOD_WINK("mood-wink"),
    MOOD_WINK_2("mood-wink-2"),
    MOOD_WRRR("mood-wrrr"),
    MOOD_X("mood-x"),
    MOOD_XD("mood-xd");

    private final String iconName;

    TablerMood(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
