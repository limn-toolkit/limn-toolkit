package limn.icons.tabler;

/**
 * Tabler's <b>Extensions</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerExtensions implements TablerIcon {

    BMP("bmp"),
    CSV("csv"),
    GIF("gif"),
    HTML("html"),
    JPG("jpg"),
    JSON("json"),
    PDF("pdf"),
    PNG("png"),
    SQL("sql"),
    SVG("svg"),
    TOML("toml"),
    TXT("txt"),
    ZIP("zip");

    private final String iconName;

    TablerExtensions(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
