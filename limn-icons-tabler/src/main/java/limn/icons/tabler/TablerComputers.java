package limn.icons.tabler;

/**
 * Tabler's <b>Computers</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerComputers implements TablerIcon {

    BINARY("binary"),
    BINARY_OFF("binary-off"),
    BINARY_TREE("binary-tree"),
    BINARY_TREE_2("binary-tree-2"),
    CLOUD_COMPUTING("cloud-computing"),
    CLOUD_DATA_CONNECTION("cloud-data-connection"),
    ERROR_404("error-404"),
    ERROR_404_OFF("error-404-off"),
    HTTP_CONNECT("http-connect"),
    HTTP_CONNECT_OFF("http-connect-off"),
    HTTP_DELETE("http-delete"),
    HTTP_DELETE_OFF("http-delete-off"),
    HTTP_GET("http-get"),
    HTTP_GET_OFF("http-get-off"),
    HTTP_HEAD("http-head"),
    HTTP_HEAD_OFF("http-head-off"),
    HTTP_OPTIONS("http-options"),
    HTTP_OPTIONS_OFF("http-options-off"),
    HTTP_PATCH("http-patch"),
    HTTP_PATCH_OFF("http-patch-off"),
    HTTP_POST("http-post"),
    HTTP_POST_OFF("http-post-off"),
    HTTP_PUT("http-put"),
    HTTP_PUT_OFF("http-put-off"),
    HTTP_QUE("http-que"),
    HTTP_QUE_OFF("http-que-off"),
    HTTP_TRACE("http-trace"),
    HTTP_TRACE_OFF("http-trace-off"),
    LOAD_BALANCER("load-balancer"),
    NETWORK("network"),
    NETWORK_OFF("network-off"),
    TOPOLOGY_BUS("topology-bus"),
    TOPOLOGY_COMPLEX("topology-complex"),
    TOPOLOGY_FULL("topology-full"),
    TOPOLOGY_FULL_HIERARCHY("topology-full-hierarchy"),
    TOPOLOGY_RING("topology-ring"),
    TOPOLOGY_RING_2("topology-ring-2"),
    TOPOLOGY_RING_3("topology-ring-3"),
    TOPOLOGY_STAR("topology-star"),
    TOPOLOGY_STAR_2("topology-star-2"),
    TOPOLOGY_STAR_3("topology-star-3"),
    TOPOLOGY_STAR_RING("topology-star-ring"),
    TOPOLOGY_STAR_RING_2("topology-star-ring-2"),
    TOPOLOGY_STAR_RING_3("topology-star-ring-3");

    private final String iconName;

    TablerComputers(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
