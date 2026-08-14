package limn.icons.tabler;

/**
 * Tabler's <b>Vehicles</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerVehicles implements TablerIcon {

    AERIAL_LIFT("aerial-lift"),
    AIR_BALLOON("air-balloon"),
    AMBULANCE("ambulance"),
    AUTOMATIC_GEARBOX("automatic-gearbox"),
    BACKHOE("backhoe"),
    BATTERY_AUTOMOTIVE("battery-automotive"),
    BIKE("bike"),
    BIKE_OFF("bike-off"),
    BULLDOZER("bulldozer"),
    BUS("bus"),
    BUS_OFF("bus-off"),
    BUS_STOP("bus-stop"),
    CAMPER("camper"),
    CAR("car"),
    CAR_4WD("car-4wd"),
    CAR_CRANE("car-crane"),
    CAR_CRASH("car-crash"),
    CAR_DOOR("car-door"),
    CAR_FAN("car-fan"),
    CAR_FAN_1("car-fan-1"),
    CAR_FAN_2("car-fan-2"),
    CAR_FAN_3("car-fan-3"),
    CAR_FAN_AUTO("car-fan-auto"),
    CAR_LIFTER("car-lifter"),
    CAR_OFF("car-off"),
    CAR_OFF_ROAD("car-off-road"),
    CAR_SUSPENSION("car-suspension"),
    CAR_SUV("car-suv"),
    CAR_TURBINE("car-turbine"),
    CARAVAN("caravan"),
    CHARGING_PILE("charging-pile"),
    CRANE("crane"),
    CRANE_OFF("crane-off"),
    DRONE("drone"),
    DRONE_OFF("drone-off"),
    ENGINE("engine"),
    ENGINE_OFF("engine-off"),
    FERRY("ferry"),
    FIRETRUCK("firetruck"),
    FORKLIFT("forklift"),
    GARDEN_CART("garden-cart"),
    GARDEN_CART_OFF("garden-cart-off"),
    GAS_STATION("gas-station"),
    GAS_STATION_OFF("gas-station-off"),
    HELICOPTER("helicopter"),
    HELICOPTER_LANDING("helicopter-landing"),
    JETSKI("jetski"),
    MOPED("moped"),
    MOTORBIKE("motorbike"),
    PARACHUTE("parachute"),
    PARACHUTE_OFF("parachute-off"),
    PLANE("plane"),
    PLANE_ARRIVAL("plane-arrival"),
    PLANE_DEPARTURE("plane-departure"),
    PLANE_INFLIGHT("plane-inflight"),
    PLANE_OFF("plane-off"),
    PLANE_TILT("plane-tilt"),
    ROLLERCOASTER("rollercoaster"),
    ROLLERCOASTER_OFF("rollercoaster-off"),
    SAILBOAT("sailboat"),
    SAILBOAT_2("sailboat-2"),
    SAILBOAT_OFF("sailboat-off"),
    SCOOTER("scooter"),
    SCOOTER_ELECTRIC("scooter-electric"),
    SEGWAY("segway"),
    SHIP("ship"),
    SHIP_OFF("ship-off"),
    SKATEBOARD("skateboard"),
    SKATEBOARD_OFF("skateboard-off"),
    SLEIGH("sleigh"),
    SPEEDBOAT("speedboat"),
    STEERING_WHEEL("steering-wheel"),
    STEERING_WHEEL_OFF("steering-wheel-off"),
    SUBMARINE("submarine"),
    TANK("tank"),
    TIR("tir"),
    TRACK("track"),
    TRACTOR("tractor"),
    TRAIN("train"),
    TROLLEY("trolley"),
    TRUCK("truck"),
    TRUCK_OFF("truck-off"),
    UNICYCLE("unicycle"),
    WHEELCHAIR("wheelchair"),
    WHEELCHAIR_OFF("wheelchair-off"),
    WIPER("wiper"),
    WIPER_WASH("wiper-wash"),
    WRECKING_BALL("wrecking-ball"),
    ZEPPELIN("zeppelin"),
    ZEPPELIN_OFF("zeppelin-off");

    private final String iconName;

    TablerVehicles(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
