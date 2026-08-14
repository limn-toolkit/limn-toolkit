package limn.icons.tabler;

/**
 * Tabler's <b>Charts</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerCharts implements TablerIcon {

    CHART_ARCS("chart-arcs"),
    CHART_ARCS_3("chart-arcs-3"),
    CHART_AREA("chart-area"),
    CHART_AREA_LINE("chart-area-line"),
    CHART_ARROWS("chart-arrows"),
    CHART_ARROWS_VERTICAL("chart-arrows-vertical"),
    CHART_BAR("chart-bar"),
    CHART_BAR_OFF("chart-bar-off"),
    CHART_BAR_POPULAR("chart-bar-popular"),
    CHART_BUBBLE("chart-bubble"),
    CHART_CANDLE("chart-candle"),
    CHART_CIRCLES("chart-circles"),
    CHART_COHORT("chart-cohort"),
    CHART_COLUMN("chart-column"),
    CHART_COVARIATE("chart-covariate"),
    CHART_DONUT("chart-donut"),
    CHART_DONUT_2("chart-donut-2"),
    CHART_DONUT_3("chart-donut-3"),
    CHART_DONUT_4("chart-donut-4"),
    CHART_DOTS("chart-dots"),
    CHART_DOTS_2("chart-dots-2"),
    CHART_DOTS_3("chart-dots-3"),
    CHART_FUNNEL("chart-funnel"),
    CHART_GRID_DOTS("chart-grid-dots"),
    CHART_HISTOGRAM("chart-histogram"),
    CHART_INFOGRAPHIC("chart-infographic"),
    CHART_LINE("chart-line"),
    CHART_PIE("chart-pie"),
    CHART_PIE_2("chart-pie-2"),
    CHART_PIE_3("chart-pie-3"),
    CHART_PIE_4("chart-pie-4"),
    CHART_PIE_OFF("chart-pie-off"),
    CHART_PPF("chart-ppf"),
    CHART_RADAR("chart-radar"),
    CHART_SANKEY("chart-sankey"),
    CHART_SCATTER("chart-scatter"),
    CHART_SCATTER_3D("chart-scatter-3d"),
    CHART_TREEMAP("chart-treemap"),
    TRENDING_UP_DOWN("trending-up-down");

    private final String iconName;

    TablerCharts(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
