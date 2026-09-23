package limn.scene.layout;

/** Vertical {@link Flex}: children stack top to bottom. */
public class Column extends Flex<Column> {

    /** An empty column laying children out top to bottom. */
    public Column() {
        super(true);
    }
}
