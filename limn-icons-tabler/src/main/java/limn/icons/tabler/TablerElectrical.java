package limn.icons.tabler;

/**
 * Tabler's <b>Electrical</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerElectrical implements TablerIcon {

    CIRCUIT_AMMETER("circuit-ammeter"),
    CIRCUIT_BATTERY("circuit-battery"),
    CIRCUIT_BULB("circuit-bulb"),
    CIRCUIT_CAPACITOR("circuit-capacitor"),
    CIRCUIT_CAPACITOR_POLARIZED("circuit-capacitor-polarized"),
    CIRCUIT_CELL("circuit-cell"),
    CIRCUIT_CELL_PLUS("circuit-cell-plus"),
    CIRCUIT_CHANGEOVER("circuit-changeover"),
    CIRCUIT_DIODE("circuit-diode"),
    CIRCUIT_DIODE_ZENER("circuit-diode-zener"),
    CIRCUIT_GROUND("circuit-ground"),
    CIRCUIT_GROUND_DIGITAL("circuit-ground-digital"),
    CIRCUIT_INDUCTOR("circuit-inductor"),
    CIRCUIT_MOTOR("circuit-motor"),
    CIRCUIT_PUSHBUTTON("circuit-pushbutton"),
    CIRCUIT_RESISTOR("circuit-resistor"),
    CIRCUIT_SWITCH_CLOSED("circuit-switch-closed"),
    CIRCUIT_SWITCH_OPEN("circuit-switch-open"),
    CIRCUIT_VOLTMETER("circuit-voltmeter");

    private final String iconName;

    TablerElectrical(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
