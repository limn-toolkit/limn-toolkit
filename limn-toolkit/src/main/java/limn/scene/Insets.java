package limn.scene;

/** Edge insets in logical points (padding/margins). */
public record Insets(float top, float right, float bottom, float left) {

    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public Insets {
        if (top < 0 || right < 0 || bottom < 0 || left < 0) {
            throw new IllegalArgumentException("negative insets");
        }
    }

    /** The same inset on all four sides. */
    public static Insets all(float value) {
        return new Insets(value, value, value, value);
    }

    /** One inset for top and bottom, another for left and right. */
    public static Insets symmetric(float vertical, float horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }
}
