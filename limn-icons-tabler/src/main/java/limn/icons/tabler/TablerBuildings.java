package limn.icons.tabler;

/**
 * Tabler's <b>Buildings</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerBuildings implements TablerIcon {

    ARMCHAIR("armchair"),
    ARMCHAIR_2("armchair-2"),
    ARMCHAIR_2_OFF("armchair-2-off"),
    ARMCHAIR_OFF("armchair-off"),
    BATH("bath"),
    BATH_OFF("bath-off"),
    BED_FLAT("bed-flat"),
    BELL_SCHOOL("bell-school"),
    BUILDING("building"),
    BUILDING_AIRPORT("building-airport"),
    BUILDING_ARCH("building-arch"),
    BUILDING_BANK("building-bank"),
    BUILDING_BRIDGE("building-bridge"),
    BUILDING_BRIDGE_2("building-bridge-2"),
    BUILDING_BROADCAST_TOWER("building-broadcast-tower"),
    BUILDING_BURJ_AL_ARAB("building-burj-al-arab"),
    BUILDING_CAROUSEL("building-carousel"),
    BUILDING_CASTLE("building-castle"),
    BUILDING_CHURCH("building-church"),
    BUILDING_CIRCUS("building-circus"),
    BUILDING_COG("building-cog"),
    BUILDING_COMMUNITY("building-community"),
    BUILDING_COTTAGE("building-cottage"),
    BUILDING_EIFFEL_TOWER("building-eiffel-tower"),
    BUILDING_ESTATE("building-estate"),
    BUILDING_FACTORY("building-factory"),
    BUILDING_FACTORY_2("building-factory-2"),
    BUILDING_FORTRESS("building-fortress"),
    BUILDING_HOSPITAL("building-hospital"),
    BUILDING_LIGHTHOUSE("building-lighthouse"),
    BUILDING_MINUS("building-minus"),
    BUILDING_MONUMENT("building-monument"),
    BUILDING_MOSQUE("building-mosque"),
    BUILDING_OFF("building-off"),
    BUILDING_PAVILION("building-pavilion"),
    BUILDING_PLUS("building-plus"),
    BUILDING_SKYSCRAPER("building-skyscraper"),
    BUILDING_STADIUM("building-stadium"),
    BUILDING_STORE("building-store"),
    BUILDING_TUNNEL("building-tunnel"),
    BUILDING_WAREHOUSE("building-warehouse"),
    BUILDING_WIND_TURBINE("building-wind-turbine"),
    BUILDINGS("buildings"),
    CAR_GARAGE("car-garage"),
    CHALKBOARD_TEACHER("chalkboard-teacher"),
    DOOR_HANGER("door-hanger"),
    FENCE("fence"),
    FENCE_OFF("fence-off"),
    HOME("home"),
    HOME_2("home-2"),
    HOME_BITCOIN("home-bitcoin"),
    HOME_BOLT("home-bolt"),
    HOME_CANCEL("home-cancel"),
    HOME_CHECK("home-check"),
    HOME_COG("home-cog"),
    HOME_DOLLAR("home-dollar"),
    HOME_DOT("home-dot"),
    HOME_DOWN("home-down"),
    HOME_ECO("home-eco"),
    HOME_EDIT("home-edit"),
    HOME_EXCLAMATION("home-exclamation"),
    HOME_HAND("home-hand"),
    HOME_HEART("home-heart"),
    HOME_INFINITY("home-infinity"),
    HOME_LINK("home-link"),
    HOME_LOCK("home-lock"),
    HOME_MINUS("home-minus"),
    HOME_MOVE("home-move"),
    HOME_OFF("home-off"),
    HOME_PLUS("home-plus"),
    HOME_QUESTION("home-question"),
    HOME_RIBBON("home-ribbon"),
    HOME_SEARCH("home-search"),
    HOME_SHARE("home-share"),
    HOME_SHIELD("home-shield"),
    HOME_SIGNAL("home-signal"),
    HOME_SPARK("home-spark"),
    HOME_STAR("home-star"),
    HOME_STATS("home-stats"),
    HOME_UP("home-up"),
    HOME_X("home-x"),
    HOTEL_SERVICE("hotel-service"),
    MOSQUE("mosque"),
    PODIUM("podium"),
    PODIUM_OFF("podium-off"),
    ROCKING_CHAIR("rocking-chair"),
    SCHOOL_BELL("school-bell"),
    SMART_HOME("smart-home"),
    SMART_HOME_OFF("smart-home-off"),
    TOWER("tower"),
    TOWER_OFF("tower-off"),
    WALL("wall"),
    WALL_OFF("wall-off"),
    WINDOW("window"),
    WINDOW_OFF("window-off");

    private final String iconName;

    TablerBuildings(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
