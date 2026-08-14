package limn.icons.tabler;

/**
 * Tabler's <b>Sport</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerSport implements TablerIcon {

    ACROBATIC("acrobatic"),
    BALL_AMERICAN_FOOTBALL("ball-american-football"),
    BALL_AMERICAN_FOOTBALL_OFF("ball-american-football-off"),
    BALL_BASEBALL("ball-baseball"),
    BALL_BASKETBALL("ball-basketball"),
    BALL_BOWLING("ball-bowling"),
    BALL_FOOTBALL("ball-football"),
    BALL_FOOTBALL_OFF("ball-football-off"),
    BALL_TENNIS("ball-tennis"),
    BALL_VOLLEYBALL("ball-volleyball"),
    BARBELL("barbell"),
    BARBELL_OFF("barbell-off"),
    BOW("bow"),
    BOWLING("bowling"),
    CHESS("chess"),
    CHESS_BISHOP("chess-bishop"),
    CHESS_KING("chess-king"),
    CHESS_KNIGHT("chess-knight"),
    CHESS_QUEEN("chess-queen"),
    CHESS_ROOK("chess-rook"),
    CLIFF_JUMPING("cliff-jumping"),
    CRICKET("cricket"),
    CURLING("curling"),
    DISC_GOLF("disc-golf"),
    DUMBBELL("dumbbell"),
    EXERCISE_BALL("exercise-ball"),
    GOLF("golf"),
    GOLF_OFF("golf-off"),
    HELMET("helmet"),
    HELMET_OFF("helmet-off"),
    HULA_HOOP("hula-hoop"),
    ICE_SKATING("ice-skating"),
    JUMP_ROPE("jump-rope"),
    KARATE("karate"),
    KAYAK("kayak"),
    MEDAL("medal"),
    MEDAL_2("medal-2"),
    MEEPLE("meeple"),
    OLYMPIC_TORCH("olympic-torch"),
    OLYMPICS("olympics"),
    OLYMPICS_OFF("olympics-off"),
    PING_PONG("ping-pong"),
    PLAY_BASKETBALL("play-basketball"),
    PLAY_FOOTBALL("play-football"),
    PLAY_HANDBALL("play-handball"),
    PLAY_VOLLEYBALL("play-volleyball"),
    POOL("pool"),
    POOL_OFF("pool-off"),
    RINGS("rings"),
    ROLLER_SKATING("roller-skating"),
    RUGBY("rugby"),
    RUN("run"),
    RUN_SPRINT("run-sprint"),
    SCOREBOARD("scoreboard"),
    SCUBA_DIVING("scuba-diving"),
    SCUBA_DIVING_TANK("scuba-diving-tank"),
    SCUBA_MASK("scuba-mask"),
    SCUBA_MASK_OFF("scuba-mask-off"),
    SKATEBOARDING("skateboarding"),
    SKI_JUMPING("ski-jumping"),
    SOCCER_FIELD("soccer-field"),
    SPORT_BILLIARD("sport-billiard"),
    STRETCHING("stretching"),
    STRETCHING_2("stretching-2"),
    SWIMMING("swimming"),
    TARGET_2("target-2"),
    TARGET_ARROW("target-arrow"),
    TIC_TAC("tic-tac"),
    TOURNAMENT("tournament"),
    TREADMILL("treadmill"),
    TREKKING("trekking"),
    TROPHY("trophy"),
    TROPHY_OFF("trophy-off"),
    WALK("walk"),
    WATERPOLO("waterpolo"),
    YOGA("yoga");

    private final String iconName;

    TablerSport(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
