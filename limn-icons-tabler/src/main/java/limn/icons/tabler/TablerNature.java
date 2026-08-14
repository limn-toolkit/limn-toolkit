package limn.icons.tabler;

/**
 * Tabler's <b>Nature</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerNature implements TablerIcon {

    ACORN("acorn"),
    ALIEN("alien"),
    ATOM("atom"),
    ATOM_2("atom-2"),
    ATOM_OFF("atom-off"),
    BALLOON("balloon"),
    BALLOON_OFF("balloon-off"),
    BUTTERFLY("butterfly"),
    CACTUS("cactus"),
    CACTUS_OFF("cactus-off"),
    CANNABIS("cannabis"),
    CHERRY("cherry"),
    CHRISTMAS_TREE("christmas-tree"),
    CHRISTMAS_TREE_OFF("christmas-tree-off"),
    CLOVER("clover"),
    CLOVER_2("clover-2"),
    CRYSTAL_BALL("crystal-ball"),
    DROP_CIRCLE("drop-circle"),
    DROPLETS("droplets"),
    FEATHER("feather"),
    FEATHER_OFF("feather-off"),
    FLAME("flame"),
    FLAME_OFF("flame-off"),
    FLOWER("flower"),
    FLOWER_OFF("flower-off"),
    GROWTH("growth"),
    ICEBERG("iceberg"),
    LEAF("leaf"),
    LEAF_2("leaf-2"),
    LEAF_MAPLE("leaf-maple"),
    LEAF_OFF("leaf-off"),
    METEOR("meteor"),
    METEOR_OFF("meteor-off"),
    MOUNTAIN("mountain"),
    MOUNTAIN_OFF("mountain-off"),
    PLANT("plant"),
    PLANT_2("plant-2"),
    PLANT_2_OFF("plant-2-off"),
    PLANT_OFF("plant-off"),
    RIPPLE("ripple"),
    RIPPLE_OFF("ripple-off"),
    SEEDLING("seedling"),
    SEEDLING_OFF("seedling-off"),
    SNOWMAN("snowman"),
    SUN_ELECTRICITY("sun-electricity"),
    TWIG("twig"),
    WIND_ELECTRICITY("wind-electricity");

    private final String iconName;

    TablerNature(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
