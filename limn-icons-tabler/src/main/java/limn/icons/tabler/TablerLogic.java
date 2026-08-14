package limn.icons.tabler;

/**
 * Tabler's <b>Logic</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerLogic implements TablerIcon {

    LOGIC_AND("logic-and"),
    LOGIC_BUFFER("logic-buffer"),
    LOGIC_NAND("logic-nand"),
    LOGIC_NOR("logic-nor"),
    LOGIC_NOT("logic-not"),
    LOGIC_OR("logic-or"),
    LOGIC_XNOR("logic-xnor"),
    LOGIC_XOR("logic-xor");

    private final String iconName;

    TablerLogic(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
