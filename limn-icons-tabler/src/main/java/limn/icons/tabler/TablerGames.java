package limn.icons.tabler;

/**
 * Tabler's <b>Games</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerGames implements TablerIcon {

    ARCHERY_ARROW("archery-arrow"),
    AXE("axe"),
    DICE("dice"),
    DICE_1("dice-1"),
    DICE_2("dice-2"),
    DICE_3("dice-3"),
    DICE_4("dice-4"),
    DICE_5("dice-5"),
    DICE_6("dice-6"),
    GHOST("ghost"),
    GHOST_2("ghost-2"),
    GHOST_3("ghost-3"),
    GHOST_OFF("ghost-off"),
    GO_GAME("go-game"),
    HORSE_TOY("horse-toy"),
    JOKER("joker"),
    PACMAN("pacman"),
    PIANO("piano"),
    PICK("pick"),
    PLAY_CARD("play-card"),
    PLAY_CARD_1("play-card-1"),
    PLAY_CARD_10("play-card-10"),
    PLAY_CARD_2("play-card-2"),
    PLAY_CARD_3("play-card-3"),
    PLAY_CARD_4("play-card-4"),
    PLAY_CARD_5("play-card-5"),
    PLAY_CARD_6("play-card-6"),
    PLAY_CARD_7("play-card-7"),
    PLAY_CARD_8("play-card-8"),
    PLAY_CARD_9("play-card-9"),
    PLAY_CARD_A("play-card-a"),
    PLAY_CARD_J("play-card-j"),
    PLAY_CARD_K("play-card-k"),
    PLAY_CARD_OFF("play-card-off"),
    PLAY_CARD_Q("play-card-q"),
    PLAY_CARD_STAR("play-card-star"),
    POKER_CHIP("poker-chip"),
    PUMPKIN_SCARY("pumpkin-scary"),
    PUZZLE("puzzle"),
    PUZZLE_2("puzzle-2"),
    PUZZLE_OFF("puzzle-off"),
    ROBOT("robot"),
    ROULETTE("roulette"),
    SWORD("sword"),
    SWORD_OFF("sword-off"),
    SWORDS("swords"),
    TREASURE_CHEST("treasure-chest"),
    UFO("ufo");

    private final String iconName;

    TablerGames(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
