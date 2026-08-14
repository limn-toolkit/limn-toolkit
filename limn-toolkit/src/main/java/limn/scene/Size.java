package limn.scene;

/** A measured widget size in logical points. */
public record Size(float width, float height) {

    public static final Size ZERO = new Size(0, 0);

    public Size {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative size: " + width + "x" + height);
        }
    }
}
