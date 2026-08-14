package limn.icons.tabler;

/**
 * Tabler's <b>Database</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerDatabase implements TablerIcon {

    COLUMN_INSERT_LEFT("column-insert-left"),
    COLUMN_INSERT_RIGHT("column-insert-right"),
    COLUMN_REMOVE("column-remove"),
    DATABASE("database"),
    DATABASE_COG("database-cog"),
    DATABASE_DOLLAR("database-dollar"),
    DATABASE_EDIT("database-edit"),
    DATABASE_EXCLAMATION("database-exclamation"),
    DATABASE_EXPORT("database-export"),
    DATABASE_HEART("database-heart"),
    DATABASE_IMPORT("database-import"),
    DATABASE_LEAK("database-leak"),
    DATABASE_MINUS("database-minus"),
    DATABASE_OFF("database-off"),
    DATABASE_PLUS("database-plus"),
    DATABASE_SEARCH("database-search"),
    DATABASE_SHARE("database-share"),
    DATABASE_STAR("database-star"),
    DATABASE_X("database-x"),
    RELATION_MANY_TO_MANY("relation-many-to-many"),
    RELATION_ONE_TO_MANY("relation-one-to-many"),
    RELATION_ONE_TO_ONE("relation-one-to-one"),
    ROW_INSERT_BOTTOM("row-insert-bottom"),
    ROW_INSERT_TOP("row-insert-top"),
    ROW_REMOVE("row-remove"),
    SCHEMA("schema"),
    SCHEMA_OFF("schema-off"),
    TABLE("table"),
    TABLE_ALIAS("table-alias"),
    TABLE_COLUMN("table-column"),
    TABLE_DOWN("table-down"),
    TABLE_EXPORT("table-export"),
    TABLE_HEART("table-heart"),
    TABLE_IMPORT("table-import"),
    TABLE_MINUS("table-minus"),
    TABLE_OFF("table-off"),
    TABLE_OPTIONS("table-options"),
    TABLE_PLUS("table-plus"),
    TABLE_ROW("table-row"),
    TABLE_SHARE("table-share"),
    TABLE_SHORTCUT("table-shortcut"),
    TABLE_SPARK("table-spark");

    private final String iconName;

    TablerDatabase(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
