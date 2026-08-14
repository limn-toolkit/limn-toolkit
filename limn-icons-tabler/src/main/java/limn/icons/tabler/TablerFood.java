package limn.icons.tabler;

/**
 * Tabler's <b>Food</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerFood implements TablerIcon {

    APPLE("apple"),
    AVOCADO("avocado"),
    BAGUETTE("baguette"),
    BANANA("banana"),
    BEER("beer"),
    BEER_OFF("beer-off"),
    BONE("bone"),
    BONE_OFF("bone-off"),
    BOTTLE("bottle"),
    BOTTLE_OFF("bottle-off"),
    BOWL("bowl"),
    BOWL_CHOPSTICKS("bowl-chopsticks"),
    BOWL_SPOON("bowl-spoon"),
    BREAD("bread"),
    BREAD_OFF("bread-off"),
    BUBBLE_TEA("bubble-tea"),
    BUBBLE_TEA_2("bubble-tea-2"),
    BURGER("burger"),
    CAKE("cake"),
    CAKE_OFF("cake-off"),
    CAKE_ROLL("cake-roll"),
    CANDY("candy"),
    CANDY_OFF("candy-off"),
    CARAMBOLA("carambola"),
    CARROT("carrot"),
    CARROT_OFF("carrot-off"),
    CHEESE("cheese"),
    CHEF_HAT("chef-hat"),
    CHEF_HAT_OFF("chef-hat-off"),
    CHOCOLATE("chocolate"),
    COFFEE("coffee"),
    COFFEE_OFF("coffee-off"),
    COOKER("cooker"),
    COOKIE("cookie"),
    COOKIE_OFF("cookie-off"),
    CUP("cup"),
    CUP_OFF("cup-off"),
    DOG_BOWL("dog-bowl"),
    DUMPLING("dumpling"),
    EGG("egg"),
    EGG_CRACKED("egg-cracked"),
    EGG_FRIED("egg-fried"),
    EGG_OFF("egg-off"),
    EGGS("eggs"),
    GLASS("glass"),
    GLASS_CHAMPAGNE("glass-champagne"),
    GLASS_COCKTAIL("glass-cocktail"),
    GLASS_FULL("glass-full"),
    GLASS_GIN("glass-gin"),
    GLASS_OFF("glass-off"),
    GRAPE("grape"),
    GRILL("grill"),
    GRILL_FORK("grill-fork"),
    GRILL_OFF("grill-off"),
    GRILL_SPATULA("grill-spatula"),
    ICE_CREAM("ice-cream"),
    ICE_CREAM_2("ice-cream-2"),
    ICE_CREAM_OFF("ice-cream-off"),
    LEMON("lemon"),
    LEMON_2("lemon-2"),
    LOLLIPOP("lollipop"),
    LOLLIPOP_OFF("lollipop-off"),
    MEAT("meat"),
    MEAT_OFF("meat-off"),
    MELON("melon"),
    MICHELIN_BIB_GOURMAND("michelin-bib-gourmand"),
    MICHELIN_STAR("michelin-star"),
    MICHELIN_STAR_GREEN("michelin-star-green"),
    MICROWAVE("microwave"),
    MICROWAVE_OFF("microwave-off"),
    MILK("milk"),
    MILK_OFF("milk-off"),
    MILKSHAKE("milkshake"),
    MUG("mug"),
    MUG_OFF("mug-off"),
    MUSHROOM("mushroom"),
    MUSHROOM_OFF("mushroom-off"),
    NUT("nut"),
    PAPER_BAG("paper-bag"),
    PAPER_BAG_OFF("paper-bag-off"),
    PEPPER("pepper"),
    PEPPER_OFF("pepper-off"),
    PIZZA("pizza"),
    PIZZA_OFF("pizza-off"),
    SALAD("salad"),
    SALT("salt"),
    SAUSAGE("sausage"),
    SOUP("soup"),
    SOUP_OFF("soup-off"),
    TEAPOT("teapot"),
    TOOLS_KITCHEN("tools-kitchen"),
    TOOLS_KITCHEN_2("tools-kitchen-2"),
    TOOLS_KITCHEN_3("tools-kitchen-3"),
    WHEAT("wheat"),
    WHEAT_OFF("wheat-off"),
    WHISK("whisk");

    private final String iconName;

    TablerFood(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
